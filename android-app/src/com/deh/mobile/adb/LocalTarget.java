package com.deh.mobile.adb;

import android.util.Base64;

import com.deh.mobile.ShellExec;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** 本机设备：通过 Shizuku / root / 应用权限执行命令。 */
public final class LocalTarget implements DeviceTarget {
    public static final String ID = "local";
    private static final String STAGE_DIR = "/storage/emulated/0/Download/.adbtool";

    private final Map<String, ShellExec.Handle> running = new ConcurrentHashMap<>();

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String name() {
        return "本机";
    }

    @Override
    public String type() {
        return "local";
    }

    @Override
    public String serial() {
        return "local";
    }

    @Override
    public boolean isLocal() {
        return true;
    }

    @Override
    public boolean isConnected() {
        return true;
    }

    @Override
    public ExecHandle exec(String command, final ExecSink sink) {
        final String execId = "local-" + System.nanoTime();
        final ShellExec.Handle handle = ShellExec.exec(command, new ShellExec.Callback() {
            @Override
            public void onData(String chunk, boolean stderr) {
                sink.onData(chunk, stderr);
            }

            @Override
            public void onExit(int code) {
                sink.onExit(code);
            }
        });
        running.put(execId, handle);
        return new ExecHandle() {
            @Override
            public String id() {
                return execId;
            }

            @Override
            public void cancel() {
                running.remove(execId);
                handle.cancel();
            }
        };
    }

    @Override
    public void cancelExec(String execId) {
        ShellExec.Handle h = running.remove(execId);
        if (h != null) {
            h.cancel();
        }
    }

    @Override
    public void push(String localPath, String remotePath, ProgressSink progress) throws Exception {
        File src = new File(localPath);
        if (!src.exists()) {
            throw new IllegalArgumentException("源文件不存在: " + localPath);
        }
        boolean stage = !isShellReadable(localPath);
        String from = localPath;
        File staged = null;
        if (stage) {
            staged = stage(src);
            from = staged.getAbsolutePath();
        }
        try {
            execChecked("cp -f " + quote(from) + " " + quote(remotePath), 120000);
            if (progress != null) {
                progress.onProgress(src.length(), src.length());
            }
        } finally {
            if (staged != null) {
                staged.delete();
            }
        }
    }

    @Override
    public void pull(String remotePath, String localPath, ProgressSink progress) throws Exception {
        File dest = new File(localPath);
        File parent = dest.getParentFile();
        if (parent != null && !parent.exists()) {
            parent.mkdirs();
        }
        File stageDir = new File(STAGE_DIR);
        if (!stageDir.exists()) {
            stageDir.mkdirs();
        }
        File staged = new File(STAGE_DIR, "down_" + System.nanoTime());
        try {
            execChecked("cp -f " + quote(remotePath) + " " + quote(staged.getAbsolutePath()), 120000);
            copy(staged, dest);
            if (progress != null) {
                progress.onProgress(dest.length(), dest.length());
            }
        } finally {
            staged.delete();
        }
    }

    @Override
    public List<FileEntry> list(String path) throws Exception {
        String p = path.endsWith("/") && path.length() > 1 ? path.substring(0, path.length() - 1) : path;
        String cmd = "for f in " + quote(p) + "/* " + quote(p) + "/.[!.]* " + quote(p) + "/..?*; "
                + "do [ -e \"$f\" ] || continue; "
                + "stat -c '%n\t%s\t%Y\t%F\t%a' \"$f\" 2>/dev/null; done";
        String out = execChecked(cmd, 20000);
        List<FileEntry> result = new ArrayList<>();
        String[] lines = out.split("\n");
        for (String line : lines) {
            line = line.trim();
            if (line.isEmpty()) {
                continue;
            }
            String[] parts = line.split("\t");
            if (parts.length < 5) {
                continue;
            }
            FileEntry e = new FileEntry();
            e.path = parts[0];
            int slash = e.path.lastIndexOf('/');
            e.name = slash >= 0 ? e.path.substring(slash + 1) : e.path;
            e.size = parseLong(parts[1]);
            e.mtime = parseLong(parts[2]) * 1000L;
            String type = parts[3];
            e.dir = type.contains("directory");
            e.link = type.contains("link");
            e.mode = parts[4];
            result.add(e);
        }
        return result;
    }

    @Override
    public FileEntry stat(String path) throws Exception {
        String out = execChecked("stat -c '%n\t%s\t%Y\t%F\t%a' " + quote(path), 10000).trim();
        String[] parts = out.split("\t");
        if (parts.length < 5) {
            throw new IllegalArgumentException("路径不存在: " + path);
        }
        FileEntry e = new FileEntry();
        e.path = path;
        int slash = path.lastIndexOf('/');
        e.name = slash >= 0 ? path.substring(slash + 1) : path;
        e.size = parseLong(parts[1]);
        e.mtime = parseLong(parts[2]) * 1000L;
        e.dir = parts[3].contains("directory");
        e.link = parts[3].contains("link");
        e.mode = parts[4];
        return e;
    }

    @Override
    public void delete(String path, boolean recursive) throws Exception {
        execChecked("rm " + (recursive ? "-rf " : "-f ") + quote(path), 60000);
    }

    @Override
    public void mkdirs(String path) throws Exception {
        execChecked("mkdir -p " + quote(path), 20000);
    }

    @Override
    public void rename(String from, String to) throws Exception {
        execChecked("mv -f " + quote(from) + " " + quote(to), 20000);
    }

    @Override
    public String readText(String path, int maxBytes) throws Exception {
        int n = Math.max(1, Math.min(maxBytes, 4 * 1024 * 1024));
        return execChecked("head -c " + n + " " + quote(path), 20000);
    }

    @Override
    public void writeText(String path, String content, boolean append) throws Exception {
        byte[] b = content.getBytes("UTF-8");
        String b64 = Base64.encodeToString(b, Base64.NO_WRAP);
        String cmd = "printf '%s' '" + b64 + "' | base64 -d " + (append ? ">> " : "> ") + quote(path);
        execChecked(cmd, 60000);
    }

    @Override
    public String getProperty(String key) throws Exception {
        return execChecked("getprop " + quote(key), 8000).trim();
    }

    @Override
    public int forward(int localTcpPort, String remoteSpec) {
        throw new UnsupportedOperationException("本机模式无需端口转发，可直接访问 localhost");
    }

    @Override
    public void reverse(String remoteSpec, int localTcpPort) {
        throw new UnsupportedOperationException("本机模式不支持 reverse");
    }

    @Override
    public void close() {
        for (ShellExec.Handle h : running.values()) {
            try {
                h.cancel();
            } catch (Throwable ignored) {
            }
        }
        running.clear();
    }

    private String execChecked(String cmd, long timeoutMs) throws Exception {
        String out = ShellExec.execSync(cmd, timeoutMs);
        if (out.startsWith("执行失败")) {
            throw new IllegalStateException(out);
        }
        return out;
    }

    private boolean isShellReadable(String path) {
        return path.startsWith("/sdcard/") || path.startsWith("/storage/")
                || path.startsWith("/data/local/tmp/") || path.startsWith("/system/");
    }

    private File stage(File src) throws Exception {
        File dir = new File(STAGE_DIR);
        if (!dir.exists()) {
            dir.mkdirs();
        }
        if (!dir.exists() && !dir.mkdirs()) {
            throw new IllegalStateException("无法创建中转目录: " + STAGE_DIR);
        }
        File f = new File(dir, "up_" + System.nanoTime() + "_" + src.getName());
        copy(src, f);
        f.setReadable(true, false);
        return f;
    }

    private static void copy(File src, File dst) throws Exception {
        InputStream in = new FileInputStream(src);
        OutputStream out = new FileOutputStream(dst);
        try {
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
            out.flush();
        } finally {
            try {
                in.close();
            } catch (Throwable ignored) {
            }
            try {
                out.close();
            } catch (Throwable ignored) {
            }
        }
    }

    private static long parseLong(String s) {
        try {
            return Long.parseLong(s.trim());
        } catch (Throwable t) {
            return 0;
        }
    }

    public static String quote(String s) {
        return "'" + s.replace("'", "'\\''") + "'";
    }
}
