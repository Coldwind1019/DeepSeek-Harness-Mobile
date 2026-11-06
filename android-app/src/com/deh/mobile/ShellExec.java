package com.deh.mobile;

import android.app.Activity;
import android.content.Context;
import android.content.pm.PackageManager;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import rikka.shizuku.Shizuku;
import rikka.shizuku.ShizukuProvider;

/**
 * 本机 Shell 执行层：优先 Shizuku（shell 身份），其次 root，最后普通应用身份。
 * 所有命令均异步执行，支持流式输出与取消。
 */
public final class ShellExec {
    public interface Callback {
        void onData(String chunk, boolean stderr);

        void onExit(int code);
    }

    public interface Handle {
        String id();

        void cancel();
    }

    public static final int MODE_UNKNOWN = 0;
    public static final int MODE_APP = 1;
    public static final int MODE_SHIZUKU = 2;
    public static final int MODE_ROOT = 3;

    private static final int SHIZUKU_REQ = 0x5A1D;
    private static final AtomicInteger SEQ = new AtomicInteger(0);
    private static final ConcurrentHashMap<String, Process> RUNNING = new ConcurrentHashMap<>();
    private static final ExecutorService POOL = Executors.newCachedThreadPool();

    private static Context ctx;
    private static volatile boolean shizukuListening = false;
    private static volatile int rootState = -1;
    private static volatile boolean shizukuEverConnected = false;

    private ShellExec() {
    }

    public static void init(Context context) {
        ctx = context.getApplicationContext();
        if (shizukuListening) {
            return;
        }
        shizukuListening = true;
        try {
            Shizuku.addBinderReceivedListenerSticky(new Shizuku.OnBinderReceivedListener() {
                @Override
                public void onBinderReceived() {
                    shizukuEverConnected = true;
                    Logx.i("Shizuku binder 已连接");
                }
            });
            Shizuku.addBinderDeadListener(new Shizuku.OnBinderDeadListener() {
                @Override
                public void onBinderDead() {
                    Logx.i("Shizuku binder 已断开");
                }
            });
            Shizuku.addRequestPermissionResultListener(new Shizuku.OnRequestPermissionResultListener() {
                @Override
                public void onRequestPermissionResult(int requestCode, int grantResult) {
                    if (requestCode == SHIZUKU_REQ) {
                        Logx.i("Shizuku 授权结果: " + (grantResult == PackageManager.PERMISSION_GRANTED));
                    }
                }
            });
            try {
                ShizukuProvider.requestBinderForNonProviderProcess(ctx);
            } catch (Throwable ignored) {
            }
        } catch (Throwable t) {
            Logx.i("Shizuku 初始化失败: " + t);
        }
        Thread keeper = new Thread(new Runnable() {
            @Override
            public void run() {
                while (true) {
                    try {
                        if (!pingShizuku() && ctx != null) {
                            try {
                                ShizukuProvider.requestBinderForNonProviderProcess(ctx);
                            } catch (Throwable ignored) {
                            }
                        }
                    } catch (Throwable ignored) {
                    }
                    try {
                        Thread.sleep(5000);
                    } catch (InterruptedException e) {
                        return;
                    }
                }
            }
        }, "shizuku-keeper");
        keeper.setDaemon(true);
        keeper.start();
    }

    private static boolean pingShizuku() {
        try {
            return Shizuku.pingBinder();
        } catch (Throwable t) {
            return false;
        }
    }

    public static boolean isShizukuGranted() {
        try {
            return pingShizuku() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED;
        } catch (Throwable t) {
            return false;
        }
    }

    public static String shizukuStatus() {
        try {
            if (!pingShizuku()) {
                return shizukuEverConnected ? "connecting" : "unavailable";
            }
            if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
                return "denied";
            }
            return "granted";
        } catch (Throwable t) {
            return "unavailable";
        }
    }

    public static void requestShizuku(Activity activity) {
        try {
            if (!pingShizuku()) {
                ShizukuProvider.requestBinderForNonProviderProcess(activity);
                return;
            }
            if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
                Shizuku.requestPermission(SHIZUKU_REQ);
            }
        } catch (Throwable t) {
            Logx.i("请求 Shizuku 权限失败: " + t);
        }
    }

    public static boolean rootAvailable() {
        if (rootState >= 0) {
            return rootState == 1;
        }
        synchronized (ShellExec.class) {
            if (rootState >= 0) {
                return rootState == 1;
            }
            rootState = 0;
            Process p = null;
            try {
                p = new ProcessBuilder("su", "-c", "id").redirectErrorStream(true).start();
                final StringBuilder sb = new StringBuilder();
                final InputStream in = p.getInputStream();
                final byte[] buf = new byte[256];
                final long deadline = System.currentTimeMillis() + 1500;
                while (System.currentTimeMillis() < deadline) {
                    if (in.available() > 0) {
                        int n = in.read(buf);
                        if (n > 0) {
                            sb.append(new String(buf, 0, n, "UTF-8"));
                        }
                    } else if (!isAlive(p)) {
                        break;
                    } else {
                        Thread.sleep(40);
                    }
                }
                if (sb.toString().contains("uid=0")) {
                    rootState = 1;
                }
            } catch (Throwable ignored) {
                rootState = 0;
            } finally {
                if (p != null) {
                    try {
                        p.destroy();
                    } catch (Throwable ignored) {
                    }
                }
            }
            Logx.i("root 探测结果: " + (rootState == 1));
            return rootState == 1;
        }
    }

    private static boolean isAlive(Process p) {
        try {
            p.exitValue();
            return false;
        } catch (IllegalThreadStateException e) {
            return true;
        }
    }

    public static int mode() {
        if (isShizukuGranted()) {
            return MODE_SHIZUKU;
        }
        if (rootAvailable()) {
            return MODE_ROOT;
        }
        return MODE_APP;
    }

    public static String modeName() {
        switch (mode()) {
            case MODE_SHIZUKU:
                return "Shizuku";
            case MODE_ROOT:
                return "Root";
            default:
                return "应用";
        }
    }

    public static String modeDescription() {
        switch (mode()) {
            case MODE_SHIZUKU:
                return "已通过 Shizuku 获得 shell 权限，可执行绝大多数 ADB 命令";
            case MODE_ROOT:
                return "已通过 root 获得最高权限";
            default:
                return "仅有应用权限，本机命令受限；可连接远程设备使用完整 ADB";
        }
    }

    private static Process spawn(String cmd) throws Exception {
        int m = mode();
        if (m == MODE_SHIZUKU) {
            Process p = Shizuku.newProcess(new String[]{"sh", "-c", cmd}, null, null);
            if (p == null) {
                throw new IllegalStateException("Shizuku.newProcess 返回 null");
            }
            return p;
        }
        if (m == MODE_ROOT) {
            return new ProcessBuilder("su", "-c", cmd).redirectErrorStream(false).start();
        }
        return new ProcessBuilder("/system/bin/sh", "-c", cmd).start();
    }

    public static Handle exec(final String cmd, final Callback cb) {
        final String id = "p" + SEQ.incrementAndGet();
        POOL.execute(new Runnable() {
            @Override
            public void run() {
                Process p = null;
                int code = -127;
                try {
                    p = spawn(cmd);
                    RUNNING.put(id, p);
                    final Process fp = p;
                    Thread out = pump(fp, false, cb);
                    Thread err = pump(fp, true, cb);
                    code = p.waitFor();
                    try {
                        out.join(800);
                        err.join(800);
                    } catch (InterruptedException ignored) {
                    }
                } catch (Throwable t) {
                    cb.onData("\n[执行失败] " + t + "\n", true);
                    code = -127;
                } finally {
                    RUNNING.remove(id);
                    try {
                        cb.onExit(code);
                    } catch (Throwable ignored) {
                    }
                    if (p != null) {
                        try {
                            p.destroy();
                        } catch (Throwable ignored) {
                        }
                    }
                }
            }
        });
        return new Handle() {
            @Override
            public String id() {
                return id;
            }

            @Override
            public void cancel() {
                Process p = RUNNING.remove(id);
                if (p != null) {
                    try {
                        p.destroy();
                    } catch (Throwable ignored) {
                    }
                }
            }
        };
    }

    private static Thread pump(final Process process, final boolean stderr, final Callback cb) {
        final InputStream in = stderr ? process.getErrorStream() : process.getInputStream();
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                InputStreamReader reader = null;
                try {
                    reader = new InputStreamReader(in, "UTF-8");
                    char[] buf = new char[4096];
                    int n;
                    while ((n = reader.read(buf)) > 0) {
                        cb.onData(new String(buf, 0, n), stderr);
                    }
                } catch (Throwable ignored) {
                } finally {
                    try {
                        if (reader != null) {
                            reader.close();
                        }
                    } catch (Throwable ignored) {
                    }
                }
            }
        }, stderr ? "exec-err" : "exec-out");
        t.setDaemon(true);
        t.start();
        return t;
    }

    public static String execSync(String cmd, long timeoutMs) throws Exception {
        int m = mode();
        Process p;
        if (m == MODE_SHIZUKU) {
            p = Shizuku.newProcess(new String[]{"sh", "-c", cmd}, null, null);
        } else if (m == MODE_ROOT) {
            p = new ProcessBuilder("su", "-c", cmd).start();
        } else {
            p = new ProcessBuilder("/system/bin/sh", "-c", cmd).start();
        }
        if (p == null) {
            throw new IllegalStateException("无法创建进程");
        }
        final StringBuilder sb = new StringBuilder();
        final StringBuilder eb = new StringBuilder();
        Thread o = collect(p.getInputStream(), sb);
        Thread e = collect(p.getErrorStream(), eb);
        boolean finished = p.waitFor(timeoutMs, TimeUnit.MILLISECONDS);
        if (!finished) {
            p.destroy();
            throw new IllegalStateException("命令超时(" + timeoutMs + "ms): " + cmd);
        }
        o.join(500);
        e.join(500);
        String out = sb.toString();
        String err = eb.toString();
        if (out.isEmpty() && !err.isEmpty()) {
            return err;
        }
        if (!err.isEmpty()) {
            return out + "\n" + err;
        }
        return out;
    }

    private static Thread collect(final InputStream in, final StringBuilder sb) {
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    InputStreamReader r = new InputStreamReader(in, "UTF-8");
                    char[] buf = new char[4096];
                    int n;
                    while ((n = r.read(buf)) > 0) {
                        sb.append(buf, 0, n);
                    }
                    r.close();
                } catch (Throwable ignored) {
                }
            }
        });
        t.setDaemon(true);
        t.start();
        return t;
    }

    public static List<String> runningIds() {
        return new ArrayList<>(RUNNING.keySet());
    }
}
