package com.deh.mobile;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.GZIPInputStream;

/**
 * 纯 Java 的 tar.gz 解压器，用于把内置的 Ubuntu rootfs 与 Node 运行时解压到 App 私有目录。
 * 支持普通文件、目录、符号链接、硬链接、PAX 扩展头（path/linkpath）与 GNU 长文件名（L）。
 */
public final class TarGzExtractor {
    public interface ProgressListener {
        void onFile(int count, long bytes);
    }

    private TarGzExtractor() {}

    public static void extract(InputStream gzStream, File destDir, ProgressListener listener) throws IOException {
        if (!destDir.exists() && !destDir.mkdirs()) {
            throw new IOException("无法创建目录: " + destDir);
        }
        try (GZIPInputStream gz = new GZIPInputStream(new BufferedInputStream(gzStream, 65536))) {
            extractTar(gz, destDir, listener);
        }
    }

    private static void extractTar(InputStream in, File destDir, ProgressListener listener) throws IOException {
        byte[] header = new byte[512];
        String pendingLongName = null;
        Map<String, String> pax = new HashMap<String, String>();
        int count = 0;
        long bytes = 0;
        String rootPath = destDir.getCanonicalPath();

        while (true) {
            int n = readFully(in, header);
            if (n < 512 || isAllZero(header)) break;

            String name = readString(header, 0, 100);
            int mode = (int) readOctal(header, 100, 8);
            long size = readOctal(header, 124, 12);
            char type = (char) (header[156] & 0xFF);
            String linkName = readString(header, 157, 100);
            String prefix = readString(header, 345, 155);

            if (!prefix.isEmpty()) name = prefix + "/" + name;

            if (type == 'L') {
                pendingLongName = readDataString(in, size);
                skipPadding(in, size);
                continue;
            }
            if (type == 'x' || type == 'g') {
                String data = readDataString(in, size);
                for (String line : data.split("\n")) {
                    int eq = line.indexOf('=');
                    if (eq > 0) pax.put(line.substring(0, eq), line.substring(eq + 1));
                }
                skipPadding(in, size);
                continue;
            }

            if (pendingLongName != null) {
                name = pendingLongName;
                pendingLongName = null;
            }
            if (pax.containsKey("path")) name = pax.get("path");
            if (pax.containsKey("linkpath")) linkName = pax.get("linkpath");

            File target = safeResolve(destDir, rootPath, name);
            if (target == null) {
                skipFile(in, size);
                pax.clear();
                continue;
            }

            if (type == '5' || name.endsWith("/")) {
                target.mkdirs();
            } else if (type == '2') {
                target.getParentFile().mkdirs();
                try {
                    if (target.exists()) target.delete();
                    Files.createSymbolicLink(target.toPath(), Paths.get(linkName));
                } catch (Exception ignored) {
                    // 某些符号链接在 Android 上无法创建（例如绝对路径目标），忽略不影响其他文件
                }
            } else if (type == '1') {
                File source = safeResolve(destDir, rootPath, linkName);
                target.getParentFile().mkdirs();
                try {
                    Files.deleteIfExists(target.toPath());
                    Files.createLink(target.toPath(), source.toPath());
                } catch (Exception ignored) {
                    // 硬链接失败时退化为复制
                    try {
                        if (source != null && source.isFile()) {
                            copyFile(source, target);
                        }
                    } catch (Exception ignored2) { }
                }
            } else {
                target.getParentFile().mkdirs();
                if (target.exists()) target.delete();
                try (OutputStream out = new FileOutputStream(target)) {
                    copyBytes(in, out, size);
                }
                if ((mode & 0111) != 0) {
                    target.setExecutable(true, false);
                }
                bytes += size;
                count++;
                if (listener != null && count % 200 == 0) listener.onFile(count, bytes);
            }

            skipPadding(in, size);
            pax.clear();
        }

        if (listener != null) listener.onFile(count, bytes);
    }

    private static File safeResolve(File destDir, String rootPath, String name) throws IOException {
        String clean = name;
        while (clean.startsWith("/")) clean = clean.substring(1);
        if (clean.isEmpty()) return null;
        File f = new File(destDir, clean);
        String canonical = f.getCanonicalPath();
        if (!canonical.equals(rootPath) && !canonical.startsWith(rootPath + File.separator)) return null;
        return f;
    }

    private static void copyBytes(InputStream in, OutputStream out, long size) throws IOException {
        byte[] buf = new byte[65536];
        long remaining = size;
        while (remaining > 0) {
            int want = (int) Math.min(buf.length, remaining);
            int read = in.read(buf, 0, want);
            if (read < 0) throw new IOException("tar 数据意外结束");
            out.write(buf, 0, read);
            remaining -= read;
        }
    }

    private static void skipPadding(InputStream in, long size) throws IOException {
        long padding = (512 - (size % 512)) % 512;
        long remaining = padding;
        byte[] buf = new byte[512];
        while (remaining > 0) {
            int read = in.read(buf, 0, (int) Math.min(buf.length, remaining));
            if (read < 0) break;
            remaining -= read;
        }
    }

    private static void skipFile(InputStream in, long size) throws IOException {
        long remaining = size;
        byte[] buf = new byte[65536];
        while (remaining > 0) {
            int read = in.read(buf, 0, (int) Math.min(buf.length, remaining));
            if (read < 0) break;
            remaining -= read;
        }
        skipPadding(in, size);
    }

    private static void copyFile(File src, File dst) throws IOException {
        try (InputStream in = Files.newInputStream(src.toPath());
             OutputStream out = Files.newOutputStream(dst.toPath())) {
            byte[] buf = new byte[65536];
            int read;
            while ((read = in.read(buf)) >= 0) out.write(buf, 0, read);
        }
    }

    private static int readFully(InputStream in, byte[] buf) throws IOException {
        int total = 0;
        while (total < buf.length) {
            int read = in.read(buf, total, buf.length - total);
            if (read < 0) break;
            total += read;
        }
        return total;
    }

    private static boolean isAllZero(byte[] buf) {
        for (byte b : buf) if (b != 0) return false;
        return true;
    }

    private static String readString(byte[] buf, int offset, int length) {
        int end = offset;
        while (end < offset + length && buf[end] != 0) end++;
        return new String(buf, offset, end - offset).trim();
    }

    private static long readOctal(byte[] buf, int offset, int length) {
        long value = 0;
        boolean started = false;
        for (int i = offset; i < offset + length; i++) {
            byte b = buf[i];
            if (b == 0 || b == ' ') {
                if (started) break;
                continue;
            }
            if (b < '0' || b > '7') {
                if (started) break;
                continue;
            }
            started = true;
            value = value * 8 + (b - '0');
        }
        return value;
    }

    private static String readDataString(InputStream in, long size) throws IOException {
        byte[] data = new byte[(int) size];
        int total = 0;
        while (total < data.length) {
            int read = in.read(data, total, data.length - total);
            if (read < 0) break;
            total += read;
        }
        String value = new String(data, 0, total, java.nio.charset.StandardCharsets.UTF_8);
        int nul = value.indexOf('\0');
        return nul >= 0 ? value.substring(0, nul) : value;
    }
}
