package com.deh.mobile;

import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** 统一日志：logcat + 应用目录文件（方便排障）。 */
public final class Logx {
    private static final String TAG = "AdbTool";
    private static final Object LOCK = new Object();
    private static final long MAX = 512 * 1024L;

    private Logx() {
    }

    public static void i(String msg) {
        Log.i(TAG, msg);
        write("I", msg);
    }

    public static void e(String msg) {
        Log.e(TAG, msg);
        write("E", msg);
    }

    public static void e(String msg, Throwable t) {
        Log.e(TAG, msg, t);
        write("E", msg + " : " + t);
    }

    private static void write(String level, String msg) {
        try {
            File dir = App.ctx().getExternalFilesDir("logs");
            if (dir == null) {
                dir = App.ctx().getFilesDir();
            }
            if (!dir.exists()) {
                dir.mkdirs();
            }
            File f = new File(dir, "adbtool.log");
            if (f.length() > MAX) {
                File old = new File(dir, "adbtool.log.1");
                if (old.exists()) {
                    old.delete();
                }
                f.renameTo(old);
            }
            String time = new SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US).format(new Date());
            synchronized (LOCK) {
                FileOutputStream out = new FileOutputStream(f, true);
                out.write((time + " " + level + " " + msg + "\n").getBytes("UTF-8"));
                out.close();
            }
        } catch (Throwable ignored) {
        }
    }
}
