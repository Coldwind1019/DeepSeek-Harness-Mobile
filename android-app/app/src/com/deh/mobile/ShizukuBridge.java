package com.deh.mobile;

import android.content.pm.PackageManager;

import rikka.shizuku.Shizuku;
import rikka.shizuku.ShizukuProvider;

public final class ShizukuBridge {
    private static volatile boolean initialized = false;
    private static volatile android.content.Context appContext = null;
    private static volatile boolean hasEverConnected = false;

    private ShizukuBridge() {
    }

    public static void init(android.content.Context context) {
        appContext = context.getApplicationContext();
        if (initialized) return;
        initialized = true;
        try {
            Shizuku.addBinderReceivedListenerSticky(new Shizuku.OnBinderReceivedListener() {
                @Override
                public void onBinderReceived() {
                    hasEverConnected = true;
                    logLine("binder received");
                }
            });
            Shizuku.addBinderDeadListener(new Shizuku.OnBinderDeadListener() {
                @Override
                public void onBinderDead() {
                    logLine("binder dead");
                }
            });
            try {
                ShizukuProvider.requestBinderForNonProviderProcess(appContext);
            } catch (Throwable ignored) {
            }
        } catch (Throwable t) {
            logLine("init error: " + t);
        }
        Thread retry = new Thread(new Runnable() {
            @Override
            public void run() {
                int i = 0;
                while (true) {
                    try {
                        if (!Shizuku.pingBinder() && appContext != null) {
                            ShizukuProvider.requestBinderForNonProviderProcess(appContext);
                        }
                    } catch (Throwable ignored) {
                    }
                    i++;
                    if (i % 20 == 0) logLine("retry state: ping=" + safePing());
                    try {
                        Thread.sleep(3000);
                    } catch (InterruptedException ignored) {
                        return;
                    }
                }
            }
        }, "shizuku-retry");
        retry.setDaemon(true);
        retry.start();
    }

    public static String status() {
        String result;
        try {
            if (!Shizuku.pingBinder()) {
                result = hasEverConnected ? "connecting" : "unavailable";
            } else if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
                result = "denied";
            } else {
                result = "granted";
            }
        } catch (Throwable t) {
            result = hasEverConnected ? "connecting" : "unavailable";
        }
        return result;
    }

    public static void requestPermission(int requestCode) {
        try {
            Shizuku.requestPermission(requestCode);
        } catch (Throwable ignored) {
        }
    }

    public static String run(String command, long timeoutMs) throws Exception {
        try {
            Process process = Shizuku.newProcess(new String[] { "sh", "-c", command }, null, null);
            if (process == null) throw new IllegalStateException("newProcess 返回 null");
            final StringBuilder out = new StringBuilder();
            Thread stdout = readStream(process.getInputStream(), out);
            Thread stderr = readStream(process.getErrorStream(), out);
            int exitCode = process.waitFor();
            stdout.join(1000);
            stderr.join(1000);
            logLine("run ok [" + command + "] code=" + exitCode + " len=" + out.length());
            return out.toString();
        } catch (Throwable t) {
            logLine("run error [" + command + "]: " + t);
            throw t;
        }
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

    private static String safePing() {
        try {
            return String.valueOf(Shizuku.pingBinder());
        } catch (Throwable t) {
            return "err";
        }
    }

    private static void logLine(String message) {
        try {
            java.io.File dir = new java.io.File("/storage/emulated/0/DEH");
            if (!dir.exists()) dir.mkdirs();
            java.io.FileOutputStream out = new java.io.FileOutputStream(new java.io.File(dir, "shizuku.log"), true);
            out.write((new java.util.Date() + " " + message + "\n").getBytes("UTF-8"));
            out.close();
        } catch (Throwable ignored) {
        }
    }
}
