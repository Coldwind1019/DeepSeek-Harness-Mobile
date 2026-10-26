package com.deh.mobile;

import android.app.Application;
import android.os.StrictMode;

import java.io.File;
import java.io.FileOutputStream;

/** 全局单例：持有 Runtime，让前台服务与 Activity 共享同一个运行中的 DSH 进程。 */
public class DshApp extends Application {
    public Runtime runtime;

    @Override
    public void onCreate() {
        try {
            StrictMode.setVmPolicy(new StrictMode.VmPolicy.Builder().build());
        } catch (Throwable ignored) {
        }
        super.onCreate();
        runtime = new Runtime(this);
        installCrashHandler();
    }

    private void installCrashHandler() {
        final Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
            @Override
            public void uncaughtException(Thread thread, Throwable error) {
                try {
                    File dir = new File("/storage/emulated/0/DEH");
                    if (!dir.exists()) dir.mkdirs();
                    FileOutputStream out = new FileOutputStream(new File(dir, "crash.log"), true);
                    java.io.StringWriter sw = new java.io.StringWriter();
                    error.printStackTrace(new java.io.PrintWriter(sw));
                    String text = "===== " + new java.util.Date() + " thread=" + thread.getName() + " =====\n" + sw.toString() + "\n";
                    out.write(text.getBytes("UTF-8"));
                    out.close();
                } catch (Throwable ignored) {
                }
                if (previous != null) {
                    previous.uncaughtException(thread, error);
                }
            }
        });
    }
}
