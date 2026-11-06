package com.deh.mobile.adb;

import android.hardware.usb.UsbDevice;
import android.util.Base64;

import com.deh.mobile.Logx;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 远程 ADB 设备目标（TCP / USB OTG），实现 {@link DeviceTarget}。
 *
 * <ul>
 *   <li>exec：优先 shell v2（shell,v2,raw:cmd，stdout/stderr/exit 分流），失败回退 shell v1</li>
 *   <li>push/pull/list/stat/readText/writeText：sync 协议（SyncClient）</li>
 *   <li>forward：本机 127.0.0.1 监听 + 每个连接 OPEN 一条设备服务流并双向转发
 *       （等价于 adb server 的 forward，返回实际本地端口）</li>
 *   <li>reverse：OPEN "reverse:forward:&lt;remote&gt;;tcp:&lt;localPort&gt;"</li>
 *   <li>close：关闭所有流、转发监听、执行中的 shell</li>
 * </ul>
 */
public final class RemoteTarget implements DeviceTarget {

    private static final Charset UTF8 = Charset.forName("UTF-8");
    private static final int OPEN_TIMEOUT_MS = 30000;
    private static final byte[] EMPTY = new byte[0];

    private final AdbConnection connection;
    private final SyncClient sync;
    private final String type;       // tcp / usb
    private final String id;         // tcp:host:port / usb:<deviceName>
    private final String host;
    private final int port;
    private final String usbName;

    private final AtomicInteger execSeq = new AtomicInteger();
    private final Map<String, ExecSession> running = new ConcurrentHashMap<String, ExecSession>();
    private final List<ServerSocket> forwardServers = new ArrayList<ServerSocket>();
    private final List<AdbStream> reverseStreams = new ArrayList<AdbStream>();

    private volatile boolean closed;
    private volatile String cachedModel;
    private volatile String cachedSerial;

    private RemoteTarget(AdbConnection connection, String type, String id,
                         String host, int port, String usbName) {
        this.connection = connection;
        this.sync = new SyncClient(connection);
        this.type = type;
        this.id = id;
        this.host = host;
        this.port = port;
        this.usbName = usbName;
    }

    /* ------------------------------ 静态工厂 ------------------------------ */

    /** 通过 TCP 连接设备 adbd（无线调试端口 5555 或已转发端口）。 */
    public static DeviceTarget connectTcp(String host, int port, int timeoutMs) throws Exception {
        TcpTransport transport = new TcpTransport(host, port, timeoutMs);
        AdbConnection conn = new AdbConnection(transport);
        try {
            conn.connect(timeoutMs);
        } catch (Exception e) {
            try {
                conn.close();
            } catch (Throwable ignored) {
            }
            try {
                transport.close();
            } catch (Throwable ignored) {
            }
            throw e;
        }
        RemoteTarget target = new RemoteTarget(conn, "tcp",
                "tcp:" + host + ":" + port, host, port, null);
        target.initInfo();
        return target;
    }

    /** 通过 USB OTG 连接设备 adbd。需要已获得 UsbManager 权限。 */
    public static DeviceTarget connectUsb(UsbDevice device, int timeoutMs) throws Exception {
        if (device == null) {
            throw new IllegalArgumentException("USB 设备为空");
        }
        UsbTransport transport = UsbHost.open(device);
        AdbConnection conn = new AdbConnection(transport);
        try {
            conn.connect(timeoutMs);
        } catch (Exception e) {
            try {
                conn.close();
            } catch (Throwable ignored) {
            }
            try {
                transport.close();
            } catch (Throwable ignored) {
            }
            throw e;
        }
        RemoteTarget target = new RemoteTarget(conn, "usb",
                "usb:" + device.getDeviceName(), null, 0, device.getDeviceName());
        target.initInfo();
        return target;
    }

    /** 连接后补全型号/序列号（banner 里通常已有，缺失时用 getprop 兜底）。 */
    private void initInfo() {
        try {
            String model = connection.deviceModel();
            if (model != null && !model.isEmpty()) {
                cachedModel = model;
            }
        } catch (Throwable ignored) {
        }
        try {
            String serial = connection.deviceSerial();
            if (serial != null && !serial.isEmpty()) {
                cachedSerial = serial;
            }
        } catch (Throwable ignored) {
        }
        try {
            if (cachedModel == null || cachedModel.isEmpty()) {
                String model = getProperty("ro.product.model");
                if (model != null && !model.isEmpty()) {
                    cachedModel = model;
                }
            }
        } catch (Throwable ignored) {
        }
        try {
            if (cachedSerial == null || cachedSerial.isEmpty()) {
                String serial = getProperty("ro.serialno");
                if (serial != null && !serial.isEmpty()) {
                    cachedSerial = serial;
                }
            }
        } catch (Throwable ignored) {
        }
    }

    /* ------------------------------ DeviceTarget 基本信息 ------------------------------ */

    @Override
    public String id() {
        return id;
    }

    @Override
    public String name() {
        String model = cachedModel;
        if (model == null || model.isEmpty()) {
            model = connection.deviceModel();
        }
        if (model == null || model.isEmpty()) {
            model = connection.deviceProduct();
        }
        if (model == null || model.isEmpty() || "unknown".equalsIgnoreCase(model)) {
            model = connection.deviceCodename();
        }
        if (model == null || model.isEmpty() || "unknown".equalsIgnoreCase(model)) {
            model = type;
            if ("usb".equals(type) && usbName != null) {
                model = usbName;
            } else if (host != null) {
                model = host + ":" + port;
            }
        }
        return model + " (" + serial() + ")";
    }

    @Override
    public String type() {
        return type;
    }

    @Override
    public String serial() {
        String serial = cachedSerial;
        if (serial == null || serial.isEmpty()) {
            serial = connection.deviceSerial();
        }
        if (serial == null || serial.isEmpty()) {
            if ("usb".equals(type) && usbName != null) {
                serial = usbName;
            } else if (host != null) {
                serial = host + ":" + port;
            } else {
                serial = "unknown";
            }
        }
        return serial;
    }

    @Override
    public boolean isLocal() {
        return false;
    }

    @Override
    public boolean isConnected() {
        return !closed && connection.isConnected();
    }

    /** 暴露底层连接（投屏等模块需要监听 reverse 时可用）。 */
    public AdbConnection connection() {
        return connection;
    }

    /* ------------------------------ 执行命令 ------------------------------ */

    @Override
    public ExecHandle exec(final String command, final ExecSink sink) throws Exception {
        ensureConnected();
        if (command == null) {
            throw new IllegalArgumentException("命令为空");
        }
        final String execId = id + "#" + execSeq.incrementAndGet();
        final ExecSession session = new ExecSession(execId);
        running.put(execId, session);
        Thread worker = new Thread(new Runnable() {
            @Override
            public void run() {
                int code = -1;
                try {
                    code = execInternal(session, command, sink);
                } catch (Throwable t) {
                    if (!session.cancelled) {
                        try {
                            sink.onData("\n执行失败: " + message(t) + "\n", true);
                        } catch (Throwable ignored) {
                        }
                    }
                    code = -1;
                } finally {
                    session.stream = null;
                    running.remove(execId);
                    try {
                        sink.onExit(code);
                    } catch (Throwable ignored) {
                    }
                }
            }
        }, "adb-exec-" + execId);
        worker.setDaemon(true);
        worker.start();
        return session;
    }

    /** 优先 shell v2；v2 打不开时回退 shell v1。 */
    private int execInternal(ExecSession session, String command, ExecSink sink) throws Exception {
        if (connection.hasFeature("shell_v2")) {
            AdbStream shell = null;
            try {
                shell = connection.open("shell,v2,raw:" + command, OPEN_TIMEOUT_MS);
            } catch (Exception e) {
                Logx.e("shell v2 打开失败，回退 v1: " + message(e));
            }
            if (shell != null) {
                return readShellV2(session, shell, sink);
            }
        }
        return readShellV1(session, command, sink);
    }

    /** shell v2 流：1B 包类型（1=stdout 2=stderr 3=exit）+ 4B 小端长度/退出码。 */
    private int readShellV2(ExecSession session, AdbStream shell, ExecSink sink) throws Exception {
        session.stream = shell;
        try {
            byte[] header = new byte[5];
            byte[] buf = new byte[64 * 1024];
            Utf8Decoder outDec = new Utf8Decoder();
            Utf8Decoder errDec = new Utf8Decoder();
            int exitCode = -1;
            boolean gotExit = false;
            while (!session.cancelled) {
                if (!shell.readFully(header, 0, 5)) {
                    break;
                }
                int packetId = header[0] & 0xFF;
                int length = getIntLE(header, 1);
                if (packetId == 3) {
                    // exit 包：长度字段即退出码，无数据体
                    exitCode = length;
                    gotExit = true;
                    break;
                }
                if (length < 0 || length > 4 * 1024 * 1024) {
                    throw new IOException("shell v2 包长度非法: " + length);
                }
                if (length > buf.length) {
                    buf = new byte[length];
                }
                if (length > 0 && !shell.readFully(buf, 0, length)) {
                    break;
                }
                if (packetId == 1) {
                    String text = outDec.feed(buf, length);
                    if (text.length() > 0) {
                        sink.onData(text, false);
                    }
                } else if (packetId == 2) {
                    String text = errDec.feed(buf, length);
                    if (text.length() > 0) {
                        sink.onData(text, true);
                    }
                }
                // 其他包类型（如 close-stdin）忽略
            }
            String tail = outDec.finish();
            if (tail.length() > 0) {
                sink.onData(tail, false);
            }
            String errTail = errDec.finish();
            if (errTail.length() > 0) {
                sink.onData(errTail, true);
            }
            return exitCode;
        } finally {
            try {
                shell.close();
            } catch (Throwable ignored) {
            }
            session.stream = null;
        }
    }

    /** shell v1 流：纯输出，无退出码（成功时按 0 处理）。 */
    private int readShellV1(ExecSession session, String command, ExecSink sink) throws Exception {
        AdbStream shell = connection.open("shell:" + command, OPEN_TIMEOUT_MS);
        session.stream = shell;
        try {
            Utf8Decoder dec = new Utf8Decoder();
            byte[] buf = new byte[8192];
            int n;
            while (!session.cancelled && (n = shell.read(buf, 0, buf.length)) > 0) {
                String text = dec.feed(buf, n);
                if (text.length() > 0) {
                    sink.onData(text, false);
                }
            }
            String tail = dec.finish();
            if (tail.length() > 0) {
                sink.onData(tail, false);
            }
            return 0;
        } finally {
            try {
                shell.close();
            } catch (Throwable ignored) {
            }
            session.stream = null;
        }
    }

    @Override
    public void cancelExec(String execId) {
        if (execId == null) {
            return;
        }
        ExecSession session = running.remove(execId);
        if (session != null) {
            session.cancel();
        }
    }

    /** 同步执行命令并返回合并输出（供本类内部与 AdbManager 冷路径使用）。 */
    public String execSync(String command, long timeoutMs) throws Exception {
        ExecResult result = execCapture(command, timeoutMs);
        return result.output;
    }

    private String execChecked(String command, long timeoutMs) throws Exception {
        ExecResult result = execCapture(command, timeoutMs);
        if (result.code != 0) {
            throw new IOException("远程命令失败（exit=" + result.code + "）: " + command
                    + (result.output.isEmpty() ? "" : "\n" + result.output.trim()));
        }
        return result.output;
    }

    private ExecResult execCapture(String command, long timeoutMs) throws Exception {
        final StringBuilder sb = new StringBuilder();
        final int[] code = new int[]{0};
        final CountDownLatch latch = new CountDownLatch(1);
        exec(command, new ExecSink() {
            @Override
            public void onData(String chunk, boolean stderr) {
                sb.append(chunk);
            }

            @Override
            public void onExit(int exitCode) {
                code[0] = exitCode;
                latch.countDown();
            }
        });
        if (!latch.await(timeoutMs, TimeUnit.MILLISECONDS)) {
            throw new IOException("远程命令超时（" + timeoutMs + "ms）: " + command);
        }
        ExecResult r = new ExecResult();
        r.code = code[0];
        r.output = sb.toString();
        return r;
    }

    /* ------------------------------ 文件 ------------------------------ */

    @Override
    public void push(String localPath, String remotePath, ProgressSink progress) throws Exception {
        ensureConnected();
        File src = new File(localPath);
        if (!src.exists()) {
            throw new IllegalArgumentException("源文件不存在: " + localPath);
        }
        sync.push(src, remotePath, progress);
    }

    @Override
    public void pull(String remotePath, String localPath, ProgressSink progress) throws Exception {
        ensureConnected();
        sync.pull(remotePath, new File(localPath), progress);
    }

    @Override
    public List<FileEntry> list(String path) throws Exception {
        ensureConnected();
        return sync.list(path);
    }

    @Override
    public FileEntry stat(String path) throws Exception {
        ensureConnected();
        return sync.stat(path);
    }

    @Override
    public void delete(String path, boolean recursive) throws Exception {
        ensureConnected();
        execChecked("rm " + (recursive ? "-rf " : "-f ") + quote(path), 60000);
    }

    @Override
    public void mkdirs(String path) throws Exception {
        ensureConnected();
        execChecked("mkdir -p " + quote(path), 30000);
    }

    @Override
    public void rename(String from, String to) throws Exception {
        ensureConnected();
        execChecked("mv -f " + quote(from) + " " + quote(to), 30000);
    }

    @Override
    public String readText(String path, int maxBytes) throws Exception {
        ensureConnected();
        int limit = maxBytes <= 0 ? 256 * 1024 : Math.min(maxBytes, 8 * 1024 * 1024);
        try {
            byte[] data = sync.pullBytes(path, limit);
            return new String(data, UTF8);
        } catch (Exception e) {
            // 回退：部分特殊文件 sync 打不开（如 /proc 下的伪文件），用 shell 兜底
            return execChecked("head -c " + limit + " " + quote(path), 30000);
        }
    }

    @Override
    public void writeText(String path, String content, boolean append) throws Exception {
        ensureConnected();
        byte[] data = (content == null ? "" : content).getBytes(UTF8);
        if (!append) {
            sync.pushBytes(path, data);
            return;
        }
        // sync 协议不支持追加：用 base64 管道追加，避免 shell 转义问题
        String b64 = Base64.encodeToString(data, Base64.NO_WRAP);
        execChecked("printf '%s' '" + b64 + "' | base64 -d >> " + quote(path), 60000);
    }

    /** 让设备 adbd 切到 TCP 模式（等价 adb tcpip 5555）。 */
    public String tcpip(int port) throws Exception {
        ensureConnected();
        AdbStream stream = connection.open("tcpip:" + port, 15000);
        StringBuilder sb = new StringBuilder();
        try {
            for (int i = 0; i < 8; i++) {
                String line = stream.readLine(512);
                if (line == null || line.isEmpty()) break;
                sb.append(line).append('\n');
            }
        } catch (Throwable ignored) {
        } finally {
            try { stream.close(); } catch (Throwable ignored) {}
        }
        return sb.toString().trim();
    }

    public String getProperty(String key) throws Exception {
        ensureConnected();
        if (key == null || key.trim().isEmpty()) {
            return "";
        }
        final StringBuilder out = new StringBuilder();
        final java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
        exec("getprop " + quote(key), new ExecSink() {
            @Override
            public void onData(String chunk, boolean stderr) {
                if (!stderr) out.append(chunk);
            }

            @Override
            public void onExit(int exitCode) {
                latch.countDown();
            }
        });
        latch.await(15, java.util.concurrent.TimeUnit.SECONDS);
        return out.toString().trim();
    }

    /* ------------------------------ 端口转发 ------------------------------ */

    /**
     * 本机监听 localTcpPort（0 表示自动分配），每个进来的连接打开一条
     * {@code remoteSpec} 设备服务流并双向转发；返回实际本地端口。
     */
    @Override
    public int forward(int localTcpPort, String remoteSpec) throws Exception {
        ensureConnected();
        if (remoteSpec == null || remoteSpec.trim().isEmpty()) {
            throw new IllegalArgumentException("转发目标为空");
        }
        final String spec = remoteSpec.trim();
        final ServerSocket server = new ServerSocket();
        server.setReuseAddress(true);
        try {
            server.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"),
                    Math.max(0, localTcpPort)));
        } catch (IOException e) {
            throw new IOException("无法监听本地端口 " + localTcpPort + "（" + e.getMessage() + "）", e);
        }
        final int actualPort = server.getLocalPort();
        synchronized (forwardServers) {
            forwardServers.add(server);
        }
        Thread acceptor = new Thread(new Runnable() {
            @Override
            public void run() {
                acceptLoop(server, spec);
            }
        }, "adb-forward-" + actualPort);
        acceptor.setDaemon(true);
        acceptor.start();
        Logx.i("已建立端口转发 127.0.0.1:" + actualPort + " -> " + spec);
        return actualPort;
    }

    private void acceptLoop(ServerSocket server, final String spec) {
        while (!closed) {
            final Socket socket;
            try {
                socket = server.accept();
            } catch (IOException e) {
                if (closed) {
                    return;
                }
                // 单个 accept 失败不致命，稍等后继续
                try {
                    Thread.sleep(50);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return;
                }
                continue;
            }
            Thread handler = new Thread(new Runnable() {
                @Override
                public void run() {
                    AdbStream stream = null;
                    try {
                        socket.setTcpNoDelay(true);
                        stream = connection.open(spec, OPEN_TIMEOUT_MS);
                    } catch (Throwable t) {
                        Logx.e("端口转发打开设备服务失败: " + message(t));
                        try {
                            socket.close();
                        } catch (Throwable ignored) {
                        }
                        return;
                    }
                    pumpTcp(socket, stream);
                }
            }, "adb-forward-conn");
            handler.setDaemon(true);
            handler.start();
        }
    }

    /** Socket <-> AdbStream 双向搬运，任一端 EOF/异常即整体关闭。 */
    private void pumpTcp(final Socket socket, final AdbStream stream) {
        try {
            Thread upstream = new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        InputStream in = socket.getInputStream();
                        byte[] buf = new byte[32 * 1024];
                        int n;
                        while ((n = in.read(buf)) > 0) {
                            stream.write(buf, 0, n);
                        }
                    } catch (Throwable ignored) {
                    } finally {
                        stream.close();
                        try {
                            socket.close();
                        } catch (Throwable ignored) {
                        }
                    }
                }
            }, "adb-forward-up");
            upstream.setDaemon(true);
            upstream.start();

            OutputStream out = socket.getOutputStream();
            byte[] buf = new byte[32 * 1024];
            int n;
            while ((n = stream.read(buf, 0, buf.length)) > 0) {
                out.write(buf, 0, n);
                out.flush();
            }
        } catch (Throwable ignored) {
        } finally {
            stream.close();
            try {
                socket.close();
            } catch (Throwable ignored) {
            }
        }
    }

    /**
     * 设备端反向监听：设备上 remoteSpec 有连接时，adbd 会通过本连接回到本机
     * tcp:localTcpPort（由 AdbConnection 处理设备发起的 OPEN）。
     */
    @Override
    public void reverse(String remoteSpec, int localTcpPort) throws Exception {
        ensureConnected();
        if (remoteSpec == null || remoteSpec.trim().isEmpty()) {
            throw new IllegalArgumentException("反向监听目标为空");
        }
        if (localTcpPort <= 0 || localTcpPort > 65535) {
            throw new IllegalArgumentException("本地端口非法: " + localTcpPort);
        }
        String request = "reverse:forward:" + remoteSpec.trim() + ";tcp:" + localTcpPort;
        AdbStream stream = connection.open(request, 15000);
        synchronized (reverseStreams) {
            reverseStreams.add(stream);
        }
        Logx.i("已建立设备反向监听 " + remoteSpec + " -> 127.0.0.1:" + localTcpPort);
    }

    /* ------------------------------ 生命周期 ------------------------------ */

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        List<ExecSession> sessions = new ArrayList<ExecSession>(running.values());
        running.clear();
        for (ExecSession s : sessions) {
            try {
                s.cancel();
            } catch (Throwable ignored) {
            }
        }
        synchronized (forwardServers) {
            for (ServerSocket server : forwardServers) {
                try {
                    server.close();
                } catch (Throwable ignored) {
                }
            }
            forwardServers.clear();
        }
        synchronized (reverseStreams) {
            for (AdbStream s : reverseStreams) {
                try {
                    s.close();
                } catch (Throwable ignored) {
                }
            }
            reverseStreams.clear();
        }
        try {
            connection.close();
        } catch (Throwable ignored) {
        }
    }

    private void ensureConnected() throws IOException {
        if (closed) {
            throw new IOException("设备连接已关闭: " + id);
        }
        if (!connection.isConnected()) {
            String reason = connection.failureReason();
            throw new IOException("设备连接已断开: " + id
                    + (reason == null ? "" : "（" + reason + "）"));
        }
    }

    /* ------------------------------ 内部工具 ------------------------------ */

    private static String message(Throwable t) {
        if (t == null) {
            return "未知错误";
        }
        String m = t.getMessage();
        return m == null || m.isEmpty() ? t.toString() : m;
    }

    private static String quote(String s) {
        return "'" + s.replace("'", "'\\''") + "'";
    }

    private static int getIntLE(byte[] b, int off) {
        return (b[off] & 0xFF)
                | ((b[off + 1] & 0xFF) << 8)
                | ((b[off + 2] & 0xFF) << 16)
                | ((b[off + 3] & 0xFF) << 24);
    }

    private static void putIntLE(byte[] b, int off, int v) {
        b[off] = (byte) (v & 0xFF);
        b[off + 1] = (byte) ((v >>> 8) & 0xFF);
        b[off + 2] = (byte) ((v >>> 16) & 0xFF);
        b[off + 3] = (byte) ((v >>> 24) & 0xFF);
    }

    /** 执行结果（退出码 + 合并输出）。 */
    private static final class ExecResult {
        int code;
        String output = "";
    }

    /** 一个执行中的 shell 会话（同时作为 ExecHandle 返回给上层）。 */
    private final class ExecSession implements ExecHandle {
        private final String execId;
        volatile AdbStream stream;
        volatile boolean cancelled;

        ExecSession(String execId) {
            this.execId = execId;
        }

        @Override
        public String id() {
            return execId;
        }

        @Override
        public void cancel() {
            cancelled = true;
            AdbStream s = stream;
            if (s != null) {
                try {
                    s.close();
                } catch (Throwable ignored) {
                }
            }
            running.remove(execId);
        }
    }

    /**
     * 增量 UTF-8 解码器：shell 输出按 64KB 分片，可能在多字节字符中间切断，
     * 这里把不完整字节留到下一次 feed，避免出现乱码替换符。
     */
    private static final class Utf8Decoder {
        private final CharsetDecoder decoder = Charset.forName("UTF-8").newDecoder()
                .onMalformedInput(CodingErrorAction.REPLACE)
                .onUnmappableCharacter(CodingErrorAction.REPLACE);
        private byte[] pending = EMPTY;

        String feed(byte[] data, int len) {
            int extra = pending.length;
            int total = extra + len;
            byte[] all;
            if (extra == 0) {
                all = data;
            } else {
                all = new byte[total];
                System.arraycopy(pending, 0, all, 0, extra);
                System.arraycopy(data, 0, all, extra, len);
            }
            ByteBuffer bb = ByteBuffer.wrap(all, 0, total);
            CharBuffer cb = CharBuffer.allocate(total + 1);
            try {
                decoder.decode(bb, cb, false);
                int remain = bb.remaining();
                if (remain > 0) {
                    pending = new byte[remain];
                    bb.get(pending);
                } else {
                    pending = EMPTY;
                }
                cb.flip();
                return cb.toString();
            } catch (Throwable t) {
                pending = EMPTY;
                return new String(all, 0, total, UTF8);
            }
        }

        String finish() {
            if (pending.length == 0) {
                return "";
            }
            String s;
            try {
                s = new String(pending, UTF8);
            } catch (Throwable t) {
                s = "";
            }
            pending = EMPTY;
            return s;
        }
    }
}
