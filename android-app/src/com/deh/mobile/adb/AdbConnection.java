package com.deh.mobile.adb;

import com.deh.mobile.Logx;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.CRC32;

/**
 * ADB 协议客户端（TCP / USB 通用）：完成 CNXN/AUTH 握手、OPEN/OKAY/WRTE/CLSE 流复用。
 *
 * <p>线程模型：一个常驻读线程解析设备发来的消息并按 local-id 分发给 {@link AdbStream}；
 * 写操作由 {@link #send} 加锁串行化，保证 24 字节头 + 负载不会交错。
 * 单连接可同时复多条流（shell / sync / localabstract 等）。</p>
 *
 * <p>消息头 24 字节，全部小端：command / arg0 / arg1 / data_length / data_crc32 / magic，
 * magic = command ^ 0xFFFFFFFF。CONNECT 消息的 arg0=version、arg1=maxdata，
 * 负载为 "host::features=...\0"（系统标识串）。</p>
 *
 * <p>来源说明：协议细节参考 AOSP packages/modules/adb 的 transport.cpp / sockets.cpp
 * 与 JADB（Apache-2.0），本文件为独立实现，未引入任何外部依赖。</p>
 */
public final class AdbConnection {

    /* ------------------------------ 协议常量 ------------------------------ */
    public static final int A_SYNC = 0x434e5953;
    public static final int A_CNXN = 0x4e584e43;
    public static final int A_AUTH = 0x48545541;
    public static final int A_OPEN = 0x4e45504f;
    public static final int A_OKAY = 0x59414b4f;
    public static final int A_CLSE = 0x45534c43;
    public static final int A_WRTE = 0x45545257;
    public static final int A_STLS = 0x534c5453;

    public static final int AUTH_TOKEN = 1;
    public static final int AUTH_SIGNATURE = 2;
    public static final int AUTH_RSAPUBLICKEY = 3;

    public static final int VERSION = 0x01000001;
    public static final int MAX_PAYLOAD = 1024 * 1024;
    private static final int DEFAULT_DEVICE_PAYLOAD = 256 * 1024;
    private static final int MAX_MESSAGE = 8 * 1024 * 1024;
    private static final int HEADER_SIZE = 24;

    /** 向设备声明支持的 host 特性（设备据此决定 shell v2 / stat v2 等能力）。 */
    public static final String HOST_FEATURES =
            "shell_v2,cmd,stat_v2,ls_v2,fixed_push_mkdir,apex,abb,abb_exec,remount_shell,track_app,sendrecv_v2";

    private static final Charset UTF8 = Charset.forName("UTF-8");
    private static final byte[] EMPTY = new byte[0];

    /* ------------------------------ 状态 ------------------------------ */
    private final AdbTransport transport;
    private final Object writeLock = new Object();
    private final Map<Integer, AdbStream> streams = new HashMap<Integer, AdbStream>();
    private final AtomicInteger nextLocalId = new AtomicInteger(1);
    private final Set<String> features = new HashSet<String>();
    private final CountDownLatch connected = new CountDownLatch(1);

    private volatile boolean closed;
    private volatile String failure;
    private volatile int deviceMaxPayload = DEFAULT_DEVICE_PAYLOAD;
    private volatile int deviceVersion = VERSION;
    private volatile String banner = "";
    private volatile String deviceSerial = "";
    private volatile String deviceModel = "";
    private volatile String deviceProduct = "";
    private volatile String deviceCodename = "";
    private volatile String deviceFeaturesRaw = "";
    private volatile boolean publicKeySent;
    private int authAttempts;
    private Thread readerThread;

    public AdbConnection(AdbTransport transport) {
        if (transport == null) {
            throw new IllegalArgumentException("transport == null");
        }
        this.transport = transport;
    }

    /* ------------------------------ 握手 ------------------------------ */

    /**
     * 发起握手并阻塞等待成功（含首次授权弹窗等待）。
     *
     * @param timeoutMs 基础超时；实际等待至少 90 秒，给设备端授权弹窗留时间
     */
    public void connect(long timeoutMs) throws Exception {
        final Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    readLoop();
                } catch (Throwable ignored) {
                    // USB 断开/设备异常：读线程静默退出，不影响进程
                }
            }
        }, "adb-reader-" + transport.description());
        t.setDaemon(true);
        this.readerThread = t;
        t.start();

        sendConnect();

        // 基础等待：设备无响应（未开调试/端口不对）时尽快失败；
        // 一旦进入授权流程（已发公钥），额外给用户 90 秒点“允许”。
        long baseWait = Math.max(timeoutMs, 30000L);
        long deadline = System.currentTimeMillis() + baseWait;
        long authDeadline = 0;
        boolean ok = false;
        while (true) {
            try {
                if (connected.await(200, TimeUnit.MILLISECONDS)) {
                    ok = true;
                    break;
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
            long now = System.currentTimeMillis();
            if (publicKeySent) {
                if (authDeadline == 0) {
                    authDeadline = now + 90000L;
                }
                if (now > authDeadline) {
                    break;
                }
            } else if (now > deadline) {
                break;
            }
        }
        if (!ok) {
            fail("ADB 握手超时，请确认设备已开启 USB 调试/无线调试并已授权本机公钥");
        }
        String reason = failure;
        if (reason != null) {
            close();
            throw new IOException("ADB 握手失败: " + reason + "（" + transport.description() + "）");
        }
        Logx.i("ADB 已连接 " + transport.description() + " : " + banner);
    }

    private void sendConnect() throws IOException {
        byte[] identity = ("host::features=" + HOST_FEATURES + "\0").getBytes(UTF8);
        // arg0=version, arg1=maxdata, 负载=系统标识串
        send(A_CNXN, VERSION, MAX_PAYLOAD, identity, 0, identity.length);
    }

    private void fail(String msg) {
        if (failure == null) {
            failure = msg;
        }
        connected.countDown();
    }

    /* ------------------------------ 读线程 ------------------------------ */

    private void readLoop() {
        byte[] header = new byte[HEADER_SIZE];
        try {
            while (!closed) {
                if (!readFully(header, HEADER_SIZE)) {
                    if (!closed) {
                        fail("连接被设备关闭（读取消息头时 EOF）");
                    }
                    break;
                }
                int cmd = getInt(header, 0);
                int arg0 = getInt(header, 4);
                int arg1 = getInt(header, 8);
                int len = getInt(header, 12);
                int magic = getInt(header, 20);
                if ((cmd ^ 0xFFFFFFFF) != magic) {
                    fail("ADB 消息头 magic 校验失败: cmd=0x" + Integer.toHexString(cmd));
                    break;
                }
                if (len < 0 || len > MAX_MESSAGE) {
                    fail("ADB 负载长度非法: " + len);
                    break;
                }
                byte[] payload = EMPTY;
                if (len > 0) {
                    payload = new byte[len];
                    if (!readFully(payload, len)) {
                        if (!closed) {
                            fail("读取 ADB 负载失败（需要 " + len + " 字节）");
                        }
                        break;
                    }
                }
                handleMessage(cmd, arg0, arg1, payload);
            }
        } catch (Throwable t) {
            if (!closed) {
                fail("ADB 连接中断: " + t);
            }
        } finally {
            closed = true;
            List<AdbStream> copy = new ArrayList<AdbStream>();
            synchronized (streams) {
                copy.addAll(streams.values());
                streams.clear();
            }
            for (AdbStream s : copy) {
                try {
                    s.onRemoteClose();
                } catch (Throwable ignored) {
                }
            }
            connected.countDown();
            transport.close();
            Logx.i("ADB 读线程退出: " + transport.description());
        }
    }

    private boolean readFully(byte[] b, int len) throws IOException {
        int done = 0;
        while (done < len) {
            int n = transport.read(b, done, len - done);
            if (n < 0) {
                return false;
            }
            if (n == 0) {
                continue;
            }
            done += n;
        }
        return true;
    }

    private void handleMessage(int cmd, int arg0, int arg1, byte[] payload) {
        switch (cmd) {
            case A_CNXN:
                handleConnect(arg0, arg1, payload);
                break;
            case A_AUTH:
                handleAuth(arg0, payload);
                break;
            case A_OKAY:
                handleOkay(arg0, arg1);
                break;
            case A_WRTE:
                handleWrite(arg0, arg1, payload);
                break;
            case A_CLSE:
                handleClose(arg0, arg1);
                break;
            case A_OPEN:
                handleDeviceOpen(arg0, payload);
                break;
            case A_STLS:
                fail("设备要求加密传输（STLS），本实现仅支持明文 ADB 通道");
                break;
            case A_SYNC:
                // 已废弃命令，忽略
                break;
            default:
                Logx.e("忽略未知 ADB 命令: 0x" + Integer.toHexString(cmd));
                break;
        }
    }

    private void handleConnect(int version, int maxPayload, byte[] payload) {
        deviceVersion = version;
        if (maxPayload > 0) {
            deviceMaxPayload = Math.max(4096, Math.min(maxPayload, MAX_PAYLOAD));
        }
        banner = new String(payload, 0, payload.length, UTF8).trim();
        parseBanner(banner);
        connected.countDown();
    }

    /** 解析设备 banner："device::ro.product.name=xxx;ro.product.model=xxx;...;features=..." */
    private void parseBanner(String text) {
        if (text == null) {
            return;
        }
        String props = text;
        int sep = props.indexOf("::");
        if (sep >= 0) {
            props = props.substring(sep + 2);
        }
        String[] parts = props.split(";");
        StringBuilder feats = new StringBuilder();
        for (String part : parts) {
            if (part == null || part.isEmpty()) {
                continue;
            }
            int eq = part.indexOf('=');
            if (eq <= 0) {
                continue;
            }
            String key = part.substring(0, eq).trim();
            String value = part.substring(eq + 1).trim();
            if ("features".equals(key)) {
                for (String f : value.split(",")) {
                    String item = f.trim();
                    if (!item.isEmpty()) {
                        features.add(item);
                        if (feats.length() > 0) {
                            feats.append(',');
                        }
                        feats.append(item);
                    }
                }
            } else if ("ro.product.model".equals(key)) {
                deviceModel = value;
            } else if ("ro.product.name".equals(key)) {
                deviceProduct = value;
            } else if ("ro.product.device".equals(key)) {
                deviceCodename = value;
            } else if ("ro.serialno".equals(key)) {
                deviceSerial = value;
            }
        }
        deviceFeaturesRaw = feats.toString();
    }

    private void handleAuth(int type, byte[] payload) {
        try {
            if (type == AUTH_TOKEN) {
                authAttempts++;
                if (authAttempts == 2 && !publicKeySent) {
                    // 第一次签名被拒：发送公钥，触发设备端"允许 USB 调试"弹窗
                    byte[] pub = AdbCrypto.get().adbPublicKeyPayload();
                    send(A_AUTH, AUTH_RSAPUBLICKEY, 0, pub, 0, pub.length);
                    publicKeySent = true;
                    Logx.i("已向设备发送 ADB 公钥，请在设备上确认授权（" + transport.description() + "）");
                } else {
                    byte[] signature = AdbCrypto.get().signToken(payload);
                    send(A_AUTH, AUTH_SIGNATURE, 0, signature, 0, signature.length);
                    Logx.i("已回复 ADB 签名（第 " + authAttempts + " 次）");
                }
                if (authAttempts > 8) {
                    fail("ADB 认证反复失败（设备未授权本机公钥）");
                }
            } else {
                Logx.e("未知 AUTH 类型: " + type);
            }
        } catch (Throwable t) {
            fail("ADB 认证失败: " + t);
        }
    }

    private void handleOkay(int deviceLocalId, int myLocalId) {
        AdbStream stream = findStream(myLocalId);
        if (stream != null) {
            stream.onOkay(deviceLocalId);
        } else if (myLocalId != 0) {
            Logx.e("收到未知流的 OKAY: local=" + myLocalId + " remote=" + deviceLocalId);
        }
    }

    private void handleWrite(int deviceLocalId, int myLocalId, byte[] payload) {
        AdbStream stream = findStream(myLocalId);
        if (stream == null) {
            // 流已关闭：回 CLSE 让设备释放
            try {
                send(A_CLSE, myLocalId, deviceLocalId, EMPTY, 0, 0);
            } catch (Throwable ignored) {
            }
            return;
        }
        stream.onRemoteData(payload);
    }

    private void handleClose(int deviceLocalId, int myLocalId) {
        AdbStream stream = null;
        synchronized (streams) {
            if (myLocalId != 0) {
                stream = streams.remove(Integer.valueOf(myLocalId));
            }
            if (stream == null && deviceLocalId != 0) {
                stream = streams.remove(Integer.valueOf(deviceLocalId));
            }
        }
        if (stream == null) {
            return;
        }
        // OPEN 失败时设备发的是 CLSE(local-id=0, remote-id=我方 local-id)，
        // 必须以“设备侧 local-id 是否为 0”判断失败，不能用 myLocalId。
        if (deviceLocalId == 0) {
            stream.markFailed("设备拒绝了服务请求（" + stream.destination() + "）");
        } else {
            stream.onRemoteClose();
        }
    }

    /**
     * 设备主动 OPEN：reverse 端口转发的合流入口。
     * destination 形如 "reverse:forward:localabstract:xxx;tcp:1234"，此时需要连接本机 tcp 端口。
     */
    private void handleDeviceOpen(int deviceLocalId, byte[] payload) {
        String destination = new String(payload, 0, payload.length, UTF8).trim();
        if (destination.endsWith("\0")) {
            destination = destination.substring(0, destination.length() - 1);
        }
        int port = -1;
        if (destination.startsWith("reverse:")) {
            int idx = destination.lastIndexOf(';');
            String localSpec = idx >= 0 ? destination.substring(idx + 1) : "";
            port = parseTcpPort(localSpec);
        }
        if (port <= 0) {
            Logx.e("拒绝设备发起的 OPEN（不支持的目标）: " + destination);
            try {
                send(A_CLSE, 0, deviceLocalId, EMPTY, 0, 0);
            } catch (Throwable ignored) {
            }
            return;
        }
        int localId = nextLocalId.getAndIncrement();
        final AdbStream stream = new AdbStream(this, localId, deviceLocalId, destination);
        registerStream(stream);
        try {
            send(A_OKAY, localId, deviceLocalId, EMPTY, 0, 0);
        } catch (IOException e) {
            unregisterStream(localId);
            stream.onRemoteClose();
            return;
        }
        Logx.i("设备反向连接到达，转发到本机 tcp:127.0.0.1:" + port);
        pumpReverse(stream, port);
    }

    private void pumpReverse(final AdbStream stream, final int port) {
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                Socket socket = new Socket();
                try {
                    socket.setTcpNoDelay(true);
                    socket.connect(new InetSocketAddress("127.0.0.1", port), 5000);
                    final Socket s = socket;
                    Thread up = new Thread(new Runnable() {
                        @Override
                        public void run() {
                            try {
                                InputStream in = s.getInputStream();
                                byte[] buf = new byte[32 * 1024];
                                int n;
                                while ((n = in.read(buf)) > 0) {
                                    stream.write(buf, 0, n);
                                }
                            } catch (Throwable ignored) {
                            } finally {
                                stream.close();
                            }
                        }
                    }, "adb-reverse-up");
                    up.setDaemon(true);
                    up.start();
                    OutputStream out = socket.getOutputStream();
                    byte[] buf = new byte[32 * 1024];
                    int n;
                    while ((n = stream.read(buf, 0, buf.length)) > 0) {
                        out.write(buf, 0, n);
                        out.flush();
                    }
                } catch (Throwable e) {
                    Logx.e("反向转发失败: " + e.getMessage());
                } finally {
                    stream.close();
                    try {
                        socket.close();
                    } catch (Throwable ignored) {
                    }
                }
            }
        }, "adb-reverse-pump");
        t.setDaemon(true);
        t.start();
    }

    private static int parseTcpPort(String spec) {
        if (spec == null) {
            return -1;
        }
        String s = spec.trim();
        if (s.endsWith("\0")) {
            s = s.substring(0, s.length() - 1);
        }
        if (!s.startsWith("tcp:")) {
            return -1;
        }
        int idx = s.lastIndexOf(':');
        if (idx < 0) {
            return -1;
        }
        try {
            int p = Integer.parseInt(s.substring(idx + 1).trim());
            return (p > 0 && p <= 65535) ? p : -1;
        } catch (Throwable t) {
            return -1;
        }
    }

    private AdbStream findStream(int localId) {
        synchronized (streams) {
            return streams.get(Integer.valueOf(localId));
        }
    }

    /* ------------------------------ 流管理 ------------------------------ */

    /** 打开一个设备服务（OPEN），阻塞直到设备回 OKAY，失败抛异常。 */
    public AdbStream open(String destination) throws Exception {
        return open(destination, 30000);
    }

    public AdbStream open(String destination, long timeoutMs) throws Exception {
        if (destination == null || destination.trim().isEmpty()) {
            throw new IllegalArgumentException("ADB service destination 为空");
        }
        if (closed || connected.getCount() != 0) {
            throw new IOException("ADB 连接尚未就绪或已关闭: " + transport.description());
        }
        final int localId = nextLocalId.getAndIncrement();
        AdbStream stream = new AdbStream(this, localId, destination);
        registerStream(stream);
        byte[] payload = (destination + "\0").getBytes(UTF8);
        try {
            send(A_OPEN, localId, 0, payload, 0, payload.length);
        } catch (IOException e) {
            unregisterStream(localId);
            throw e;
        }
        if (!stream.awaitReady(timeoutMs)) {
            unregisterStream(localId);
            String reason = stream.failureReason();
            if (reason == null) {
                reason = "等待 OKAY 超时 " + timeoutMs + "ms";
            }
            stream.markFailed(reason);
            throw new IOException("打开 ADB 服务失败: " + destination + "（" + reason + "）");
        }
        return stream;
    }

    void registerStream(AdbStream stream) {
        synchronized (streams) {
            streams.put(Integer.valueOf(stream.localId()), stream);
        }
    }

    void unregisterStream(int localId) {
        synchronized (streams) {
            streams.remove(Integer.valueOf(localId));
        }
    }

    void writeStream(AdbStream stream, byte[] b, int off, int len) throws IOException {
        int max = maxPayload();
        int pos = 0;
        while (pos < len) {
            int n = Math.min(max, len - pos);
            send(A_WRTE, stream.localId(), stream.remoteId(), b, off + pos, n);
            pos += n;
        }
    }

    void sendOkay(int localId, int remoteId) {
        try {
            send(A_OKAY, localId, remoteId, EMPTY, 0, 0);
        } catch (Throwable t) {
            Logx.e("发送 OKAY 失败: " + t.getMessage());
        }
    }

    void sendClose(int localId, int remoteId) throws IOException {
        send(A_CLSE, localId, remoteId, EMPTY, 0, 0);
    }

    /* ------------------------------ 发送 ------------------------------ */

    private void send(int cmd, int arg0, int arg1, byte[] payload, int off, int len) throws IOException {
        if (closed) {
            throw new IOException("ADB 连接已关闭: " + transport.description());
        }
        byte[] header = new byte[HEADER_SIZE];
        putInt(header, 0, cmd);
        putInt(header, 4, arg0);
        putInt(header, 8, arg1);
        putInt(header, 12, len);
        CRC32 crc = new CRC32();
        if (len > 0) {
            crc.update(payload, off, len);
        }
        putInt(header, 16, (int) crc.getValue());
        putInt(header, 20, cmd ^ 0xFFFFFFFF);
        synchronized (writeLock) {
            transport.write(header, 0, HEADER_SIZE);
            if (len > 0) {
                transport.write(payload, off, len);
            }
        }
    }

    /* ------------------------------ 工具 ------------------------------ */

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

    /* ------------------------------ 查询 ------------------------------ */

    public boolean isConnected() {
        return !closed && connected.getCount() == 0 && failure == null;
    }

    public boolean isClosed() {
        return closed;
    }

    public String failureReason() {
        return failure;
    }

    public String banner() {
        return banner;
    }

    public int deviceVersion() {
        return deviceVersion;
    }

    public int maxPayload() {
        int max = deviceMaxPayload;
        if (max <= 0) {
            max = DEFAULT_DEVICE_PAYLOAD;
        }
        return Math.max(4096, Math.min(max, MAX_PAYLOAD));
    }

    public Set<String> features() {
        return new HashSet<String>(features);
    }

    public boolean hasFeature(String name) {
        return name != null && features.contains(name);
    }

    public String deviceModel() {
        return deviceModel;
    }

    public String deviceProduct() {
        return deviceProduct;
    }

    public String deviceCodename() {
        return deviceCodename;
    }

    /** 设备序列号：优先 banner 的 ro.serialno，否则为空（由上层用 getprop 兜底）。 */
    public String deviceSerial() {
        return deviceSerial;
    }

    public String deviceFeaturesRaw() {
        return deviceFeaturesRaw;
    }

    public AdbTransport transport() {
        return transport;
    }

    /** 断开连接：关闭所有流并关闭底层传输。可重复调用。 */
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        List<AdbStream> copy = new ArrayList<AdbStream>();
        synchronized (streams) {
            copy.addAll(streams.values());
            streams.clear();
        }
        for (AdbStream s : copy) {
            try {
                s.onRemoteClose();
            } catch (Throwable ignored) {
            }
        }
        connected.countDown();
        transport.close();
    }
}
