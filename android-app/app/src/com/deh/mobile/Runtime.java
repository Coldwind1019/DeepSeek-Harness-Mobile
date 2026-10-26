package com.deh.mobile;

import android.content.Context;
import android.os.Environment;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 独立版运行时：解压内置 rootfs/Node/proot，安装 DSH，按工作区根启动本地 dsh web。
 * 工作区根支持「内部存储」与「应用私有目录」两种，由 Prefs 决定。
 */
public final class Runtime {
    public interface LogListener { void onLog(String line); }
    public interface UrlListener { void onUrl(String url); }

    private static final Pattern URL_PATTERN = Pattern.compile("dsh web:\\s*(http://\\S+)");
    private static final String NODE_DIR = "node-v24.21.0-linux-arm64";
    private static final String GUEST_NODE_BIN = "/usr/local/" + NODE_DIR + "/bin";
    private static final String DSH_BIN = "/usr/local/" + NODE_DIR + "/lib/node_modules/@deepseek-ai/dsh/lib/bin.js";

    private final Context context;
    public final File base;
    public final File rootfs;
    public final File bin;
    public final File tmp;

    /** 工作区根（宿主路径）与容器内路径。 */
    public final File workRoot;
    public final String guestWorkRoot;
    public final String guestHome;

    private Process hostProcess;
    /** 当前运行的 dsh web 地址（供 Activity 重建时复用）。 */
    public volatile String lastUrl;

    public Runtime(Context context) {
        this.context = context;
        this.base = new File(context.getFilesDir(), "dsh");
        this.rootfs = new File(base, "rootfs");
        this.bin = new File(base, "bin");
        this.tmp = new File(base, "tmp");

        if ("private".equals(Prefs.workRootMode(context))) {
            File priv = new File(context.getFilesDir(), "work");
            this.workRoot = new File(priv, "DEH");
            this.guestWorkRoot = "/work/DEH";
            this.guestHome = "/work";
        } else {
            this.workRoot = new File(Environment.getExternalStorageDirectory(), "DEH");
            this.guestWorkRoot = "/sdcard/DEH";
            this.guestHome = "/storage/emulated/0";
        }
        if (!workRoot.exists()) workRoot.mkdirs();
    }

    public String workModeLabel() {
        return "private".equals(Prefs.workRootMode(context)) ? "应用私有目录" : "内部存储";
    }

    public boolean isPrepared() {
        return new File(bin, "proot").isFile()
                && new File(bin, "loader").isFile()
                && new File(rootfs, "bin/bash").isFile()
                && new File(rootfs, "usr/local/" + NODE_DIR + "/bin/node").isFile();
    }

    public boolean isInstalled() {
        return isPrepared() && new File(base, ".installed").isFile();
    }

    public void prepare(LogListener log) throws Exception {
        base.mkdirs();
        bin.mkdirs();
        tmp.mkdirs();

        log.onLog("[1/4] 解压 Linux 根文件系统（约 30MB，首次较慢）...");
        extractAsset("runtime/ubuntu-base-arm64.tar.gz", rootfs, log);

        log.onLog("[2/4] 解压 Node.js 运行时（约 56MB）...");
        File usrLocal = new File(rootfs, "usr/local");
        if (!usrLocal.exists() && !usrLocal.mkdirs()) throw new IOException("无法创建 /usr/local");
        extractAsset("runtime/node-linux-arm64.tar.gz", usrLocal, log);

        log.onLog("[3/4] 解压 proot 与依赖库...");
        copyAsset("runtime/proot", new File(bin, "proot"));
        copyAsset("runtime/libtalloc.so.2", new File(bin, "libtalloc.so.2"));
        copyAsset("runtime/libandroid-shmem.so", new File(bin, "libandroid-shmem.so"));
        copyAsset("runtime/loader", new File(bin, "loader"));
        copyAsset("runtime/loader32", new File(bin, "loader32"));
        new File(bin, "proot").setExecutable(true, false);
        new File(bin, "loader").setExecutable(true, false);

        log.onLog("[4/4] 写入初始化脚本...");
        writeInitScript();
        selfCheck(log);
        new File(base, ".prepared").createNewFile();
        log.onLog("运行环境准备完成。");
    }

    /** 从备份包恢复整个 rootfs（跳过首次安装），恢复后 .installed 标记存在。 */
    public void restoreFromBackup(File backupGz, LogListener log) throws Exception {
        base.mkdirs();
        bin.mkdirs();
        tmp.mkdirs();
        log.onLog("[恢复] 正在从备份恢复运行时（" + (backupGz.length() / 1024 / 1024) + " MB），请耐心等待...");
        InputStream in = new FileInputStream(backupGz);
        try {
            TarGzExtractor.extract(in, rootfs, new TarGzExtractor.ProgressListener() {
                @Override
                public void onFile(int count, long bytes) {
                }
            });
        } finally {
            in.close();
        }
        copyAsset("runtime/proot", new File(bin, "proot"));
        copyAsset("runtime/libtalloc.so.2", new File(bin, "libtalloc.so.2"));
        copyAsset("runtime/libandroid-shmem.so", new File(bin, "libandroid-shmem.so"));
        copyAsset("runtime/loader", new File(bin, "loader"));
        copyAsset("runtime/loader32", new File(bin, "loader32"));
        new File(bin, "proot").setExecutable(true, false);
        new File(bin, "loader").setExecutable(true, false);
        new File(base, ".prepared").createNewFile();
        new File(base, ".installed").createNewFile();
        log.onLog("[恢复] 运行时恢复完成（包含 DSH 与历史会话）。");
    }

    public void install(LogListener log) throws Exception {
        writeInitScript();
        log.onLog("开始安装系统依赖与 DeepSeek Harness（需要联网，约 5-20 分钟）...");
        log.onLog("如果长时间无输出属于正常现象（apt/npm 在下载）。");
        int code = runProot("bash /root/.dsh-init.sh", log);
        if (code != 0) {
            throw new IOException("安装失败（退出码 " + code + "）。请检查网络后重试。");
        }
        new File(base, ".installed").createNewFile();
        log.onLog("DeepSeek Harness 安装完成。");
    }

    public Process startHost(final LogListener log, final UrlListener urlListener) throws Exception {
        log.onLog("[启动] 准备 DSH 环境...");
        killStaleHosts(log);
        String killOld = "for p in /proc/[0-9]*; do pid=${p#/proc/}; [ \"$pid\" = \"$$\" ] && continue; cmd=$(tr '\\0' ' ' < \"$p/cmdline\" 2>/dev/null); case \"$cmd\" in node\\ *dsh/lib/bin.js\\ web*) kill -9 \"$pid\" 2>/dev/null;; esac; done; sleep 1; ";
        String prep = "[ -x /usr/local/node-v24.21.0-linux-arm64/bin/pnpm ] || npm i -g pnpm >/dev/null 2>&1; [ -x /usr/bin/adb ] || apt-get install -y android-tools-adb >/dev/null 2>&1; ";
        String command = killOld + prep + "node " + DSH_BIN + " web --host 127.0.0.1 --port 38600 --no-open < /dev/null; echo '[dsh] exit=' $?";
        ProcessBuilder builder = prootBuilder(command);
        builder.redirectErrorStream(true);
        final Process process = builder.start();
        hostProcess = process;

        final AtomicBoolean urlSent = new AtomicBoolean(false);
        Thread reader = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), "UTF-8"));
                    String line;
                    while ((line = reader.readLine()) != null) {
                        log.onLog("[dsh] " + line);
                        if (!urlSent.get()) {
                            Matcher m = URL_PATTERN.matcher(line);
                            if (m.find()) {
                                urlSent.set(true);
                                lastUrl = m.group(1);
                                urlListener.onUrl(m.group(1));
                            }
                        }
                    }
                } catch (Exception e) {
                    log.onLog("[dsh] 读取输出失败: " + e.getMessage());
                }
            }
        }, "dsh-host-reader");
        reader.setDaemon(true);
        reader.start();

        Thread watchdog = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    Thread.sleep(45000);
                    if (!urlSent.get()) log.onLog("[dsh] 启动较慢（45 秒未检测到地址），继续等待...");
                } catch (InterruptedException ignored) {
                }
            }
        }, "dsh-startup-watch");
        watchdog.setDaemon(true);
        watchdog.start();
        return process;
    }

    public boolean isHostRunning() {
        return hostProcess != null && lastUrl != null;
    }

    public void stopHost() {
        if (hostProcess != null) {
            hostProcess.destroy();
            hostProcess = null;
        }
    }

    /** 宿主层清理：杀同 uid 的 node/bash 旧进程（不依赖 cmdline 读取权限）。 */
    private void killStaleHosts(LogListener log) {
        diagLine("killStaleHosts start");
        try {
            File[] procs = new File("/proc").listFiles();
            if (procs == null) return;
            int self = android.os.Process.myPid();
            int myUid = android.os.Process.myUid();
            for (File proc : procs) {
                String name = proc.getName();
                if (!name.matches("\\d+")) continue;
                int pid;
                try {
                    pid = Integer.parseInt(name);
                } catch (Exception e) {
                    continue;
                }
                if (pid == self) continue;
                try {
                    byte[] commBytes = new byte[64];
                    java.io.FileInputStream commIn = new java.io.FileInputStream(new File(proc, "comm"));
                    int commLen = commIn.read(commBytes);
                    commIn.close();
                    String comm = commLen > 0 ? new String(commBytes, 0, commLen, "UTF-8").trim() : "";
                    if (!"node".equals(comm) && !"bash".equals(comm)) continue;

                    boolean sameUid = false;
                    java.io.BufferedReader reader = new java.io.BufferedReader(new java.io.InputStreamReader(
                            new java.io.FileInputStream(new File(proc, "status")), "UTF-8"));
                    String line;
                    while ((line = reader.readLine()) != null) {
                        if (line.startsWith("Uid:")) {
                            String[] parts = line.trim().split("\\s+");
                            if (parts.length > 1 && String.valueOf(myUid).equals(parts[1])) sameUid = true;
                            break;
                        }
                    }
                    reader.close();
                    if (sameUid) {
                        log.onLog("[启动] 清理旧进程 pid=" + pid + " (" + comm + ")");
                        diagLine("kill pid=" + pid + " comm=" + comm);
                        try {
                            android.os.Process.killProcess(pid);
                        } catch (Throwable ignored) {
                        }
                    }
                } catch (Throwable ignored) {
                }
            }
            Thread.sleep(800);
            diagLine("killStaleHosts done");
        } catch (Throwable ignored) {
        }
    }

    private static void diagLine(String message) {
        try {
            File dir = new File("/storage/emulated/0/DEH");
            if (!dir.exists()) dir.mkdirs();
            java.io.FileOutputStream out = new java.io.FileOutputStream(new File(dir, "diag.log"), true);
            String line = new java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.US).format(new java.util.Date())
                    + " [runtime] " + message + "\n";
            out.write(line.getBytes("UTF-8"));
            out.close();
        } catch (Throwable ignored) {
        }
    }

    private void ensurePnpm(LogListener log) throws Exception {
        File pnpm = new File(rootfs, "usr/local/" + NODE_DIR + "/bin/pnpm");
        if (!pnpm.exists()) {
            log.onLog("[启动] 未检测到 pnpm，正在安装...");
            int code = runProot("npm i -g pnpm", log);
            if (code != 0) throw new IOException("安装 pnpm 失败（退出码 " + code + "）");
            log.onLog("[启动] pnpm 安装完成");
        }
    }

    private void ensureAdb(LogListener log) throws Exception {
        writeAdbHelper();
        File adb = new File(rootfs, "usr/bin/adb");
        if (!adb.exists()) {
            log.onLog("[adb] 未检测到 adb，正在安装（约 30 秒）...");
            int code = runProot("apt-get install -y android-tools-adb", log);
            if (code != 0) log.onLog("[adb] 安装失败（退出码 " + code + "）");
        }
    }

    private void writeInitScript() throws IOException {
        File script = new File(rootfs, "root/.dsh-init.sh");
        script.getParentFile().mkdirs();
        StringBuilder sb = new StringBuilder();
        sb.append("#!/bin/bash\n");
        sb.append("set -e\n");
        sb.append("export PATH=" + GUEST_NODE_BIN + ":/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin\n");
        sb.append("export HOME=/root\n");
        sb.append("export DEBIAN_FRONTEND=noninteractive\n");
        sb.append("echo 'nameserver 223.5.5.5' > /etc/resolv.conf\n");
        sb.append("echo 'nameserver 8.8.8.8' >> /etc/resolv.conf\n");
        sb.append("sed -i 's|http://archive.ubuntu.com/ubuntu/|http://mirrors.aliyun.com/ubuntu/|g; s|http://security.ubuntu.com/ubuntu/|http://mirrors.aliyun.com/ubuntu/|g' /etc/apt/sources.list.d/ubuntu.sources 2>/dev/null || true\n");
        sb.append("sed -i 's|http://archive.ubuntu.com/ubuntu|http://mirrors.aliyun.com/ubuntu|g; s|http://security.ubuntu.com/ubuntu|http://mirrors.aliyun.com/ubuntu|g' /etc/apt/sources.list 2>/dev/null || true\n");
        sb.append("apt-get update\n");
        sb.append("apt-get install -y --no-install-recommends build-essential python3 ca-certificates curl git android-tools-adb\n");
        sb.append("npm config set registry https://registry.npmmirror.com\n");
        sb.append("export npm_config_disturl=https://npmmirror.com/mirrors/node\n");
        sb.append("npm i -g @deepseek-ai/dsh\n");
        sb.append("npm i -g pnpm\n");
        sb.append("echo '[DSH-INIT] 安装完成'\n");
        writeText(script, sb.toString());
        script.setExecutable(true, false);
    }

    private void writeAdbHelper() throws IOException {
        File script = new File(rootfs, "root/adb-connect.sh");
        script.getParentFile().mkdirs();
        StringBuilder sb = new StringBuilder();
        sb.append("#!/bin/bash\n");
        sb.append("export PATH=" + GUEST_NODE_BIN + ":/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin\n");
        sb.append("export HOME=/root\n");
        sb.append("adb start-server >/dev/null 2>&1\n");
        sb.append("if [ -n \"$1\" ]; then\n");
        sb.append("  pair_port=$(adb mdns services 2>/dev/null | grep '_adb-tls-pairing' | grep -o '127\\.0\\.0\\.1:[0-9]*' | head -n1)\n");
        sb.append("  if [ -n \"$pair_port\" ]; then adb pair \"$pair_port\" \"$1\"; else echo '[adb] 未发现配对服务，请确认已开启无线调试'; fi\n");
        sb.append("fi\n");
        sb.append("connect_port=$(adb mdns services 2>/dev/null | grep '_adb-tls-connect' | grep -o '127\\.0\\.0\\.1:[0-9]*' | head -n1)\n");
        sb.append("if [ -n \"$connect_port\" ]; then adb connect \"$connect_port\"; else adb connect 127.0.0.1:5555; fi\n");
        sb.append("adb devices\n");
        writeText(script, sb.toString());
        script.setExecutable(true, false);
    }

    private void selfCheck(LogListener log) {
        log.onLog("[CHECK] rootfs bash exists=" + new File(rootfs, "bin/bash").exists());
        log.onLog("[CHECK] proot loader exists=" + new File(bin, "loader").exists());
    }

    private int runProot(String command, LogListener log) throws Exception {
        ProcessBuilder builder = prootBuilder(command);
        builder.redirectErrorStream(true);
        Process process = builder.start();
        BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), "UTF-8"));
        String line;
        while ((line = reader.readLine()) != null) log.onLog(line);
        return process.waitFor();
    }

    private ProcessBuilder prootBuilder(String command) {
        List<String> cmd = new ArrayList<String>();
        String nativeDir = context.getApplicationInfo().nativeLibraryDir;
        File proot = new File(nativeDir, "libproot.so");
        File loader = new File(nativeDir, "libprootloader.so");

        cmd.add(proot.getAbsolutePath());
        cmd.add("-0");
        cmd.add("--link2symlink");
        cmd.add("-r");
        cmd.add(rootfs.getAbsolutePath());
        cmd.add("-b");
        cmd.add("/dev");
        cmd.add("-b");
        cmd.add("/proc");
        cmd.add("-b");
        cmd.add("/sys");
        if (new File("/storage").exists()) {
            cmd.add("-b");
            cmd.add("/storage:/storage");
        }
        if (new File("/storage/emulated/0").exists()) {
            cmd.add("-b");
            cmd.add("/storage/emulated/0:/sdcard");
        }
        if ("private".equals(Prefs.workRootMode(context))) {
            cmd.add("-b");
            cmd.add(new File(context.getFilesDir(), "work").getAbsolutePath() + ":/work");
        }
        cmd.add("-w");
        cmd.add(guestWorkRoot);
        cmd.add("/usr/bin/env");
        cmd.add("-i");
        cmd.add("HOME=" + guestHome);
        cmd.add("DSH_HOME=/root/.dsh");
        cmd.add("TERM=xterm-256color");
        cmd.add("DISPLAY=:0");
        cmd.add("WAYLAND_DISPLAY=wayland-0");
        cmd.add("PATH=" + GUEST_NODE_BIN + ":/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin");
        cmd.add("/bin/bash");
        cmd.add("-l");
        cmd.add("-c");
        cmd.add(command);

        ProcessBuilder builder = new ProcessBuilder(cmd);
        builder.directory(bin);
        Map<String, String> env = builder.environment();
        env.put("LD_LIBRARY_PATH", nativeDir);
        env.put("PROOT_TMP_DIR", tmp.getAbsolutePath());
        env.put("PROOT_NO_SECCOMP", "1");
        env.put("PROOT_LOADER", loader.getAbsolutePath());
        env.put("DISPLAY", ":0");
        env.put("WAYLAND_DISPLAY", "wayland-0");
        return builder;
    }

    private void extractAsset(String assetName, File dest, LogListener log) throws Exception {
        InputStream in = null;
        try {
            in = context.getAssets().open(assetName);
            TarGzExtractor.extract(in, dest, new TarGzExtractor.ProgressListener() {
                @Override
                public void onFile(int count, long bytes) {
                }
            });
        } finally {
            if (in != null) in.close();
        }
    }

    private void copyAsset(String assetName, File dest) throws Exception {
        InputStream in = context.getAssets().open(assetName);
        try {
            dest.getParentFile().mkdirs();
            OutputStream out = new FileOutputStream(dest);
            try {
                byte[] buf = new byte[65536];
                int read;
                while ((read = in.read(buf)) >= 0) out.write(buf, 0, read);
            } finally {
                out.close();
            }
        } finally {
            in.close();
        }
    }

    private static void writeText(File file, String text) throws IOException {
        OutputStream out = new FileOutputStream(file);
        try {
            out.write(text.getBytes("UTF-8"));
        } finally {
            out.close();
        }
    }

    public static void deleteRecursively(File file) {
        if (file == null || !file.exists()) return;
        File[] children = file.listFiles();
        if (children != null) {
            for (File child : children) deleteRecursively(child);
        }
        file.delete();
    }
}
