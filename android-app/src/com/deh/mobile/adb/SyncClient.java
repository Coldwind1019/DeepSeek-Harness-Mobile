package com.deh.mobile.adb;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;

/**
 * ADB 文件同步协议客户端（adb "sync:" service），v1 实现：
 * SEND / RECV / STAT(LSTAT) / LIST / DENT / DATA / DONE / OKAY / FAIL。
 *
 * <p>协议细节与 AOSP packages/modules/adb 的 file_sync_protocol.h /
 * file_sync_service.cpp 完全一致：</p>
 * <ul>
 *   <li>请求 = SyncRequest{uint32 id, uint32 path_length} + path（无结尾 NUL）</li>
 *   <li>SEND 的 path 需要 "路径,mode"（mode 为十进制或 0 开头八进制字符串）</li>
 *   <li>推送数据帧 = sync_data{uint32 id=DATA, uint32 size} + data（size ≤ 64KB）</li>
 *   <li>推送结束帧 = sync_data{id=DONE, size=mtime秒}（注意：DONE 的 size 是 mtime）</li>
 *   <li>推送结果 = sync_status{uint32 id=OKAY/FAIL, uint32 msglen} [+ 失败消息]</li>
 *   <li>拉取：RECV 后设备依次回 DATA 帧，最后 DATA 帧之外发 DONE{id,size=0}</li>
 *   <li>STAT 返回 sync_stat_v1{id,mode,size,mtime} 共 16 字节</li>
 *   <li>LIST 返回 sync_dent_v1{id,mode,size,mtime,namelen} 共 20 字节 + 文件名；
 *       结束帧同样是 20 字节的 DONE{id=DONE}（其余字段为 0）</li>
 * </ul>
 */
public final class SyncClient {

    private static final Charset UTF8 = Charset.forName("UTF-8");

    public static final int SYNC_DATA_MAX = 64 * 1024;
    private static final int MAX_PATH = 1024;

    /* sync 协议消息 id：MKID(a,b,c,d) = a | b<<8 | c<<16 | d<<24 */
    private static final int ID_LSTAT_V1 = id('S', 'T', 'A', 'T');
    private static final int ID_STAT_V2 = id('S', 'T', 'A', '2');
    private static final int ID_LSTAT_V2 = id('L', 'S', 'T', '2');
    private static final int ID_LIST_V1 = id('L', 'I', 'S', 'T');
    private static final int ID_LIST_V2 = id('L', 'I', 'S', '2');
    private static final int ID_DENT_V1 = id('D', 'E', 'N', 'T');
    private static final int ID_DENT_V2 = id('D', 'N', 'T', '2');
    private static final int ID_SEND_V1 = id('S', 'E', 'N', 'D');
    private static final int ID_RECV_V1 = id('R', 'E', 'C', 'V');
    private static final int ID_DONE = id('D', 'O', 'N', 'E');
    private static final int ID_DATA = id('D', 'A', 'T', 'A');
    private static final int ID_OKAY = id('O', 'K', 'A', 'Y');
    private static final int ID_FAIL = id('F', 'A', 'I', 'L');

    /* stat mode 的 S_IFMT 位 */
    private static final int S_IFMT = 0170000;
    private static final int S_IFDIR = 0040000;
    private static final int S_IFLNK = 0120000;

    public static final int DEFAULT_FILE_MODE = 0100644; // 八进制

    private final AdbConnection connection;

    public SyncClient(AdbConnection connection) {
        if (connection == null) {
            throw new IllegalArgumentException("connection == null");
        }
        this.connection = connection;
    }

    /* ------------------------------ 推送 ------------------------------ */

    /** 推送本地文件到设备（流式，最大 64KB 分片）。 */
    public void push(File local, String remotePath, DeviceTarget.ProgressSink sink) throws Exception {
        if (local == null || !local.exists()) {
            throw new IOException("本地文件不存在: " + local);
        }
        if (!local.isFile()) {
            throw new IOException("不是普通文件: " + local);
        }
        long total = local.length();
        long mtime = Math.max(0, local.lastModified() / 1000L);
        AdbStream stream = connection.open("sync:", 30000);
        try {
            OutputStream out = stream.getOutputStream();
            String spec = remotePath + "," + DEFAULT_FILE_MODE;
            sendRequest(out, ID_SEND_V1, spec);

            InputStream in = new FileInputStream(local);
            try {
                byte[] buf = new byte[SYNC_DATA_MAX];
                long done = 0;
                int n;
                while ((n = in.read(buf)) > 0) {
                    sendData(out, ID_DATA, buf, n);
                    done += n;
                    if (sink != null) {
                        sink.onProgress(done, total);
                    }
                }
            } finally {
                try {
                    in.close();
                } catch (Throwable ignored) {
                }
            }
            // 结束帧：DONE 的 size 字段承载 mtime（秒）
            sendData(out, ID_DONE, new byte[0], 0, 0, (int) mtime);
            out.flush();
            readStatus(stream, "推送 " + remotePath);
            if (sink != null) {
                sink.onProgress(total, total);
            }
        } finally {
            stream.close();
        }
    }

    /** 把内存数据写到设备文件（覆盖）。 */
    public void pushBytes(String remotePath, byte[] data) throws Exception {
        pushBytes(remotePath, data, DEFAULT_FILE_MODE);
    }

    public void pushBytes(String remotePath, byte[] data, int mode) throws Exception {
        AdbStream stream = connection.open("sync:", 30000);
        try {
            OutputStream out = stream.getOutputStream();
            sendRequest(out, ID_SEND_V1, remotePath + "," + mode);
            int offset = 0;
            while (offset < data.length) {
                int n = Math.min(SYNC_DATA_MAX, data.length - offset);
                sendData(out, ID_DATA, data, offset, n, -1);
                offset += n;
            }
            int mtime = (int) (System.currentTimeMillis() / 1000L);
            sendData(out, ID_DONE, new byte[0], 0, 0, mtime);
            out.flush();
            readStatus(stream, "写入 " + remotePath);
        } finally {
            stream.close();
        }
    }

    /* ------------------------------ 拉取 ------------------------------ */

    /** 拉取设备文件到本地（流式）。 */
    public void pull(String remotePath, File dest, DeviceTarget.ProgressSink sink) throws Exception {
        if (dest == null) {
            throw new IOException("本地目标为空");
        }
        File parent = dest.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new IOException("无法创建本地目录: " + parent);
        }
        long total = 0;
        try {
            DeviceTarget.FileEntry entry = stat(remotePath);
            if (entry != null && !entry.dir) {
                total = entry.size;
            }
        } catch (Throwable ignored) {
        }
        AdbStream stream = connection.open("sync:", 30000);
        try {
            OutputStream out = stream.getOutputStream();
            sendRequest(out, ID_RECV_V1, remotePath);
            out.flush();
            FileOutputStream file = new FileOutputStream(dest, false);
            try {
                long done = 0;
                byte[] header = new byte[8];
                byte[] buf = new byte[SYNC_DATA_MAX];
                while (true) {
                    if (!stream.readFully(header, 0, 8)) {
                        throw new IOException("拉取中断（读取消息头失败）: " + remotePath);
                    }
                    int msgId = getInt(header, 0);
                    int size = getInt(header, 4);
                    if (msgId == ID_DONE) {
                        break;
                    }
                    if (msgId == ID_FAIL) {
                        throw new IOException("拉取失败: " + readFailMessage(stream, size));
                    }
                    if (msgId != ID_DATA) {
                        throw new IOException("拉取失败：未知消息 id=0x" + Integer.toHexString(msgId));
                    }
                    if (size < 0 || size > SYNC_DATA_MAX) {
                        throw new IOException("拉取失败：数据块长度非法 " + size);
                    }
                    int remain = size;
                    while (remain > 0) {
                        int n = stream.read(buf, 0, Math.min(remain, buf.length));
                        if (n < 0) {
                            throw new IOException("拉取中断（数据不完整）: " + remotePath);
                        }
                        file.write(buf, 0, n);
                        remain -= n;
                        done += n;
                        if (sink != null) {
                            sink.onProgress(done, total);
                        }
                    }
                }
            } finally {
                try {
                    file.flush();
                } catch (Throwable ignored) {
                }
                try {
                    file.close();
                } catch (Throwable ignored) {
                }
            }
        } finally {
            stream.close();
        }
    }

    /** 读取设备文件到内存（最多 maxBytes，超出抛异常）。 */
    public byte[] pullBytes(String remotePath, int maxBytes) throws Exception {
        AdbStream stream = connection.open("sync:", 30000);
        try {
            OutputStream out = stream.getOutputStream();
            sendRequest(out, ID_RECV_V1, remotePath);
            out.flush();
            byte[] header = new byte[8];
            byte[] buf = new byte[8192];
            java.io.ByteArrayOutputStream acc = new java.io.ByteArrayOutputStream();
            while (true) {
                if (!stream.readFully(header, 0, 8)) {
                    throw new IOException("读取设备文件中断: " + remotePath);
                }
                int msgId = getInt(header, 0);
                int size = getInt(header, 4);
                if (msgId == ID_DONE) {
                    break;
                }
                if (msgId == ID_FAIL) {
                    throw new IOException("读取失败: " + readFailMessage(stream, size));
                }
                if (msgId != ID_DATA) {
                    throw new IOException("读取失败：未知消息 id=0x" + Integer.toHexString(msgId));
                }
                if (size < 0 || size > SYNC_DATA_MAX) {
                    throw new IOException("读取失败：数据块长度非法 " + size);
                }
                int remain = size;
                while (remain > 0) {
                    int n = stream.read(buf, 0, Math.min(remain, buf.length));
                    if (n < 0) {
                        throw new IOException("读取设备文件中断（数据不完整）: " + remotePath);
                    }
                    if (acc.size() + n > maxBytes) {
                        throw new IOException("设备文件超过读取上限 " + maxBytes + " 字节: " + remotePath);
                    }
                    acc.write(buf, 0, n);
                    remain -= n;
                }
            }
            return acc.toByteArray();
        } finally {
            stream.close();
        }
    }

    /* ------------------------------ stat / list ------------------------------ */

    /** stat（等价 lstat，不跟随符号链接），返回 FileEntry；路径不存在抛异常。 */
    public DeviceTarget.FileEntry stat(String path) throws Exception {
        AdbStream stream = connection.open("sync:", 30000);
        try {
            OutputStream out = stream.getOutputStream();
            sendRequest(out, ID_LSTAT_V1, path);
            out.flush();
            byte[] head = new byte[8];
            if (!stream.readFully(head, 0, 8)) {
                throw new IOException("stat 读取失败: " + path);
            }
            int msgId = getInt(head, 0);
            int mode = getInt(head, 4);
            if (msgId == ID_FAIL) {
                throw new IOException("stat 失败: " + readFailMessage(stream, mode));
            }
            if (msgId != ID_LSTAT_V1) {
                throw new IOException("stat 返回了意外消息 id=0x" + Integer.toHexString(msgId));
            }
            byte[] rest = new byte[8];
            if (!stream.readFully(rest, 0, 8)) {
                throw new IOException("stat 读取失败（尾部）: " + path);
            }
            int size = getInt(rest, 0);
            int mtime = getInt(rest, 4);
            if (mode == 0 && size == 0 && mtime == 0) {
                throw new IOException("路径不存在: " + path);
            }
            DeviceTarget.FileEntry entry = toEntry(path, mode, size, mtime);
            entry.path = path;
            return entry;
        } finally {
            stream.close();
        }
    }

    /** 列出目录（不含 . 与 ..）。 */
    public List<DeviceTarget.FileEntry> list(String path) throws Exception {
        List<DeviceTarget.FileEntry> result = new ArrayList<DeviceTarget.FileEntry>();
        String dir = path;
        if (dir == null || dir.isEmpty()) {
            dir = "/";
        }
        AdbStream stream = connection.open("sync:", 30000);
        try {
            OutputStream out = stream.getOutputStream();
            sendRequest(out, ID_LIST_V1, dir);
            out.flush();
            byte[] head = new byte[8];
            byte[] rest = new byte[12];
            while (true) {
                if (!stream.readFully(head, 0, 8)) {
                    throw new IOException("列目录中断: " + dir);
                }
                int msgId = getInt(head, 0);
                int mode = getInt(head, 4);
                if (msgId == ID_DONE) {
                    // v1 的 DONE 帧是完整的 20 字节 dent 结构，需要把剩余 12 字节读掉
                    stream.readFully(rest, 0, 12);
                    break;
                }
                if (msgId == ID_FAIL) {
                    throw new IOException("列目录失败: " + readFailMessage(stream, mode));
                }
                if (msgId != ID_DENT_V1) {
                    throw new IOException("列目录返回了意外消息 id=0x" + Integer.toHexString(msgId));
                }
                if (!stream.readFully(rest, 0, 12)) {
                    throw new IOException("列目录中断（读取 dent 尾部）: " + dir);
                }
                int size = getInt(rest, 0);
                int mtime = getInt(rest, 4);
                int nameLen = getInt(rest, 8);
                if (nameLen < 0 || nameLen > 4096) {
                    throw new IOException("列目录失败：文件名长度非法 " + nameLen);
                }
                byte[] nameBytes = new byte[nameLen];
                if (nameLen > 0 && !stream.readFully(nameBytes, 0, nameLen)) {
                    throw new IOException("列目录中断（读取文件名）: " + dir);
                }
                String name = new String(nameBytes, UTF8);
                if (".".equals(name) || "..".equals(name)) {
                    continue;
                }
                String child = joinPath(dir, name);
                DeviceTarget.FileEntry entry = toEntry(child, mode, size, mtime);
                entry.name = name;
                entry.path = child;
                result.add(entry);
            }
        } finally {
            stream.close();
        }
        return result;
    }

    /* ------------------------------ 内部工具 ------------------------------ */

    private static void sendRequest(OutputStream out, int id, String path) throws IOException {
        byte[] pathBytes = path.getBytes(UTF8);
        if (pathBytes.length > MAX_PATH) {
            throw new IOException("路径过长（>" + MAX_PATH + "）: " + path);
        }
        byte[] buf = new byte[8 + pathBytes.length];
        putInt(buf, 0, id);
        putInt(buf, 4, pathBytes.length);
        System.arraycopy(pathBytes, 0, buf, 8, pathBytes.length);
        out.write(buf, 0, buf.length);
    }

    private static void sendData(OutputStream out, int id, byte[] data, int len) throws IOException {
        sendData(out, id, data, 0, len, -1);
    }

    private static void sendData(OutputStream out, int id, byte[] data, int off, int len,
                                 int doneTimestamp) throws IOException {
        byte[] header = new byte[8];
        putInt(header, 0, id);
        putInt(header, 4, doneTimestamp >= 0 ? doneTimestamp : len);
        out.write(header, 0, 8);
        if (len > 0) {
            out.write(data, off, len);
        }
    }

    /** 读取 sync_status：id + msglen（8 字节），OKAY 返回 null，FAIL 抛异常。 */
    private static void readStatus(AdbStream stream, String action) throws IOException {
        byte[] head = new byte[8];
        if (!stream.readFully(head, 0, 8)) {
            throw new IOException(action + " 失败：设备未返回状态");
        }
        int id = getInt(head, 0);
        int msglen = getInt(head, 4);
        if (id == ID_OKAY) {
            return;
        }
        if (id == ID_FAIL) {
            throw new IOException(action + " 失败: " + readFailMessage(stream, msglen));
        }
        throw new IOException(action + " 失败：未知状态 id=0x" + Integer.toHexString(id));
    }

    private static String readFailMessage(AdbStream stream, int msglen) throws IOException {
        if (msglen <= 0 || msglen > 64 * 1024) {
            return "（设备未提供错误信息）";
        }
        byte[] msg = new byte[msglen];
        if (!stream.readFully(msg, 0, msglen)) {
            return "（读取错误信息失败）";
        }
        return new String(msg, UTF8);
    }

    private static DeviceTarget.FileEntry toEntry(String path, int mode, int size, int mtime) {
        DeviceTarget.FileEntry e = new DeviceTarget.FileEntry();
        e.path = path;
        int slash = path.lastIndexOf('/');
        e.name = slash >= 0 ? path.substring(slash + 1) : path;
        if (e.name.isEmpty() && slash == 0) {
            e.name = "/";
        }
        e.dir = (mode & S_IFMT) == S_IFDIR;
        e.link = (mode & S_IFMT) == S_IFLNK;
        e.size = size & 0xFFFFFFFFL;
        e.mtime = (mtime & 0xFFFFFFFFL) * 1000L;
        e.mode = permissionString(mode);
        return e;
    }

    /** 把 stat 的 mode 转成 "644" 形式（与 LocalTarget 的 stat -c %a 一致）。 */
    public static String permissionString(int mode) {
        return Integer.toOctalString(mode & 0777);
    }

    private static String joinPath(String dir, String name) {
        if (dir.endsWith("/")) {
            return dir + name;
        }
        return dir + "/" + name;
    }

    private static int id(char a, char b, char c, char d) {
        return (a) | ((b) << 8) | ((c) << 16) | ((d) << 24);
    }

    private static int getInt(byte[] b, int off) {
        return (b[off] & 0xFF)
                | ((b[off + 1] & 0xFF) << 8)
                | ((b[off + 2] & 0xFF) << 16)
                | ((b[off + 3] & 0xFF) << 24);
    }

    private static void putInt(byte[] b, int off, int value) {
        b[off] = (byte) (value & 0xFF);
        b[off + 1] = (byte) ((value >>> 8) & 0xFF);
        b[off + 2] = (byte) ((value >>> 16) & 0xFF);
        b[off + 3] = (byte) ((value >>> 24) & 0xFF);
    }
}
