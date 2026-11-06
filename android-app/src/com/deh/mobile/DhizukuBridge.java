package com.deh.mobile;

import android.content.Context;

import com.rosan.dhizuku.api.Dhizuku;
import com.rosan.dhizuku.api.DhizukuRemoteProcess;
import com.rosan.dhizuku.api.DhizukuRequestPermissionListener;

/** Dhizuku 集成（官方 SDK）：授权状态、授权请求、Device Owner 下执行命令。 */
public final class DhizukuBridge {
    private static volatile boolean initialized = false;
    private static volatile Context appContext = null;

    private DhizukuBridge() {
    }

    public static void init(Context context) {
        appContext = context.getApplicationContext();
        if (initialized) return;
        initialized = true;
        try {
            boolean ok = Dhizuku.init(appContext);
            logLine("init=" + ok);
        } catch (Throwable t) {
            logLine("init error: " + t);
        }
    }

    public static String status() {
        try {
            if (appContext == null) return "unavailable";
            if (!Dhizuku.init(appContext)) return "not_installed";
            if (!Dhizuku.isPermissionGranted()) return "denied";
            return "granted";
        } catch (Throwable t) {
            return "unavailable";
        }
    }

    public static String statusLabel() {
        String status = status();
        if ("not_installed".equals(status)) return "未安装或未运行";
        if ("unavailable".equals(status)) return "不可用";
        if ("denied".equals(status)) return "未授权";
        return "已授权";
    }

    public static void requestPermission(final android.app.Activity activity) {
        try {
            Dhizuku.requestPermission(new DhizukuRequestPermissionListener() {
                @Override
                public void onRequestPermission(int requestCode) {
                    logLine("permission result code=" + requestCode);
                }
            });
        } catch (Throwable t) {
            logLine("request error: " + t);
            try {
                android.content.Intent intent = new android.content.Intent(
                        "com.rosan.dhizuku.action.request.permission");
                intent.setPackage("com.rosan.dhizuku");
                activity.startActivity(intent);
            } catch (Throwable ignored) {
            }
        }
    }

    public static String info() {
        try {
            if (!Dhizuku.init(appContext)) return "not available";
            return "version=" + Dhizuku.getVersionName()
                    + " granted=" + Dhizuku.isPermissionGranted()
                    + " owner=" + Dhizuku.getOwnerPackageName();
        } catch (Throwable t) {
            return "error: " + t;
        }
    }

    public static String run(String command) throws Exception {
        if (!Dhizuku.init(appContext)) throw new IllegalStateException("Dhizuku 未就绪");
        if (!Dhizuku.isPermissionGranted()) throw new IllegalStateException("Dhizuku 未授权");
        DhizukuRemoteProcess process = Dhizuku.newProcess(new String[] { "sh", "-c", command }, null, null);
        if (process == null) throw new IllegalStateException("newProcess 返回 null");
        final StringBuilder out = new StringBuilder();
        Thread stdout = readStream(process.getInputStream(), out);
        Thread stderr = readStream(process.getErrorStream(), out);
        int exitCode = process.waitFor();
        stdout.join(1000);
        stderr.join(1000);
        logLine("run ok code=" + exitCode + " len=" + out.length());
        return out.toString();
    }

    private static Thread readStream(final java.io.InputStream in, final StringBuilder out) {
        Thread thread = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    byte[] buffer = new byte[8192];
                    int read;
                    while ((read = in.read(buffer)) >= 0) {
                        synchronized (out) {
                            out.append(new String(buffer, 0, read, "UTF-8"));
                        }
                    }
                } catch (Throwable ignored) {
                }
            }
        });
        thread.setDaemon(true);
        thread.start();
        return thread;
    }

    private static void logLine(String message) {
        try {
            java.io.File dir = new java.io.File("/storage/emulated/0/DEH");
            if (!dir.exists()) dir.mkdirs();
            java.io.FileOutputStream out = new java.io.FileOutputStream(new java.io.File(dir, "dhizuku.log"), true);
            out.write((new java.util.Date() + " " + message + "\n").getBytes("UTF-8"));
            out.close();
        } catch (Throwable ignored) {
        }
    }
}
