package com.hdl.m3u8;

import android.os.Environment;
import android.os.Handler;
import android.os.Message;

import com.hdl.m3u8.bean.M3U8;
import com.hdl.m3u8.bean.M3U8Listener;
import com.hdl.m3u8.bean.M3U8Ts;
import com.hdl.m3u8.utils.MUtils;

import org.apache.commons.io.IOUtils;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * M3u8管理器
 */
public class M3U8Manger {
    private static final int WHAT_ON_START = 166;
    private static final int WHAT_ON_ERROR = 711;
    private static final int WHAT_ON_GETINFO = 840;
    private static final int WHAT_ON_COMPLITED = 625;
    private static final String KEY_DEFAULT_TEMP_DIR = "/sdcard/1m3u8temp/";
    private static M3U8Manger mM3U8Manger;
    private String url;//m3u8的路径
    private String saveFilePath = "/sdcard/Movie/" + System.currentTimeMillis() + ".ts";//文件保存路径
    private String tempDir = KEY_DEFAULT_TEMP_DIR;//m3u8临时文件夹
    private ExecutorService executor;//10个线程池
    private M3U8Listener downLoadListener;
    private boolean isRunning = false;//任务是否正在运行
    private Handler mHandler = new Handler() {
        @Override
        public void handleMessage(Message msg) {
            if (downLoadListener != null) {
                switch (msg.what) {
                    case WHAT_ON_START:
                        downLoadListener.onStart();
                        break;
                    case WHAT_ON_ERROR:
                        downLoadListener.onError((Throwable) msg.obj);
                        break;
                    case WHAT_ON_GETINFO:
                        M3U8 m3U8 = (M3U8) msg.obj;
                        downLoadListener.onM3U8Info(m3U8);
                        break;
                    case WHAT_ON_COMPLITED:
                        downLoadListener.onCompleted();
                        break;
                }
            }
        }
    };

    private M3U8Manger() {
    }

    public static M3U8Manger getInstance() {
        synchronized (M3U8Manger.class) {
            if (mM3U8Manger == null) {
                mM3U8Manger = new M3U8Manger();
            }
        }
        return mM3U8Manger;
    }

    /**
     * 下载
     *
     * @param downLoadListener
     */
    public synchronized void download(M3U8Listener downLoadListener) {
        this.downLoadListener = downLoadListener;
        if (Environment.getExternalStorageState().equals(Environment.MEDIA_MOUNTED)) {
            if (!isRunning) {
                startDownload();
            } else {
                handlerError(new Throwable("Task isRunning"));
            }
        } else {//没有找到sdcard
            handlerError(new Throwable("SDcard not found"));
        }
    }

    /**
     * 停止任务
     */
    public synchronized void stop() {
        isRunning=false;
        if (executor != null && executor.isTerminated()) {
            executor.shutdownNow();
            executor = null;
        }
        mHandler.sendEmptyMessage(WHAT_ON_COMPLITED);
    }

    /**
     * 获取m3u8
     *
     * @param downLoadListener
     */
    public synchronized void getM3U8(M3U8Listener downLoadListener) {
        this.downLoadListener = downLoadListener;
        if (!isRunning) {
            new Thread() {
                @Override
                public void run() {
                    isRunning = true;
                    try {
                        M3U8 m3u8 = MUtils.parseIndex(url);
                        isRunning = false;//获取成功之后要复位
                        sendM3u8Info(m3u8);
                        mHandler.sendEmptyMessage(WHAT_ON_COMPLITED);
                    } catch (IOException e) {
                        e.printStackTrace();
                        handlerError(e);
                    }
                }
            }.start();
        } else {
            handlerError(new Throwable("Task isRunning"));
        }

    }

    /**
     * 开始下载了
     */
    private synchronized void startDownload() {
        mHandler.sendEmptyMessage(WHAT_ON_START);
        isRunning = true;//开始下载了
        new Thread() {
            @Override
            public void run() {
                try {
                    M3U8 m3u8 = null;
                    try {
                        m3u8 = MUtils.parseIndex(url);
                        sendM3u8Info(m3u8);
                    } catch (Exception e) {
                        handlerError(e);
                        return;
                    }
//                    float f = 0;
//                    for (M3U8Ts ts : m3u8.getTsList()) {
//                        f += ts.getSeconds();
//                    }
//                    System.out.println("movie length: " + ((int) f / 60) + "min " + (int) f % 60 + " sec");
                    if (executor != null && executor.isTerminated()) {
                        executor.shutdownNow();
                        executor = null;
                    }
                    executor = Executors.newFixedThreadPool(10);
                    download(m3u8, tempDir);//开始下载,保存在临时文件中
                    executor.shutdown();//下载完成之后要关闭线程池
//                    System.out.println("Wait for downloader...");
                    while (!executor.isTerminated()) {
                        Thread.sleep(100);
                    }

                    //到这里说明下载完成了
                    MUtils.merge(m3u8, tempDir);//合并ts
                    //移动到指定的目录
                    MUtils.moveFile(tempDir, saveFilePath);
                    mHandler.sendEmptyMessage(WHAT_ON_START);
                    isRunning = false;//复位
                } catch (IOException e) {
                    e.printStackTrace();
                    handlerError(e);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                    handlerError(e);
                } finally {
                    //清空临时目录
                    MUtils.clearDir(new File(tempDir).getParentFile());
                }
            }
        }.start();
    }

    /**
     * 通知拿到消息
     *
     * @param m3u8
     */
    private void sendM3u8Info(M3U8 m3u8) {
        Message msg = mHandler.obtainMessage();
        msg.obj = m3u8;
        msg.what = WHAT_ON_GETINFO;
        mHandler.sendMessage(msg);
    }

    /**
     * 通知异常
     *
     * @param e
     */
    private void handlerError(Throwable e) {
        Message msg = mHandler.obtainMessage();
        msg.obj = e;
        msg.what = WHAT_ON_ERROR;
        mHandler.sendMessage(msg);
    }

    /**
     * 设置m3u8文件的路径
     *
     * @param url
     * @return
     */
    public synchronized M3U8Manger setUrl(String url) {
        this.url = url;
        return this;
    }

    /**
     * 设置保存文件的名字
     *
     * @param saveFilePath
     * @return
     */
    public synchronized M3U8Manger setSaveFilePath(String saveFilePath) {
        this.saveFilePath = saveFilePath;
        tempDir = KEY_DEFAULT_TEMP_DIR;
        tempDir += new File(saveFilePath).getName();
        return this;
    }

    /**
     * 下载
     *
     * @param m3u8
     * @param saveFileName
     * @throws IOException
     */
    private void download(final M3U8 m3u8, final String saveFileName) throws IOException {
        final File dir = new File(saveFileName).getParentFile();
        if (!dir.exists()) {
            dir.mkdirs();
        } else if (dir.list().length > 0) {//保存的路径必须必须为空或者文件夹不存在
            MUtils.clearDir(dir);//清空文件
        }

        for (final M3U8Ts ts : m3u8.getTsList()) {
            executor.execute(new Runnable() {

                @Override
                public void run() {
                    try {
//                        System.out.println("download " + (m3u8.getTsList().indexOf(ts) + 1) + "/"
//                                + m3u8.getTsList().size() + ": " + ts);
                        FileOutputStream writer = new FileOutputStream(new File(dir, ts.getFile()));
                        IOUtils.copyLarge(new URL(m3u8.getBasepath() + ts.getFile()).openStream(), writer);
                        writer.close();
//                        System.out.println("download ok for: " + ts);
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                }
            });

        }

    }
}