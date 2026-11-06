package com.deh.mobile.adb;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.Charset;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * 一条 ADB 流（对应设备端一个 service，例如 shell / sync / localabstract:xxx）。
 *
 * <p>线程模型：AdbConnection 的单一读线程把 WRTE 负载放入本流的阻塞队列；
 * 业务线程通过 {@link #read(byte[], int, int)} 消费。队列满时读线程阻塞，
 * 从而让"回 OKAY"延迟，形成 ADB 协议级的背压。</p>
 */
public final class AdbStream implements Closeable {

    private static final Charset UTF8 = Charset.forName("UTF-8");
    private static final int QUEUE_CAPACITY = 128;

    private final AdbConnection connection;
    private final int localId;
    private final String destination;
    private final LinkedBlockingQueue<byte[]> queue = new LinkedBlockingQueue<byte[]>(QUEUE_CAPACITY);
    private final CountDownLatch ready = new CountDownLatch(1);
    private final Object readLock = new Object();
    private final Object streamLock = new Object();

    private volatile int remoteId;
    private volatile boolean closed;
    private volatile String failure;
    private byte[] current;
    private int currentPos;
    private InputStream inputStreamAdapter;
    private OutputStream outputStreamAdapter;

    /** 本端发起（OPEN）的流，等待设备 OKAY。 */
    AdbStream(AdbConnection connection, int localId, String destination) {
        this.connection = connection;
        this.localId = localId;
        this.destination = destination;
    }

    /** 设备发起（reverse 场景）的流，已知 remote-id，直接进入就绪状态。 */
    AdbStream(AdbConnection connection, int localId, int remoteId, String destination) {
        this.connection = connection;
        this.localId = localId;
        this.remoteId = remoteId;
        this.destination = destination;
        this.ready.countDown();
    }

    /* ------------------------------ 属性 ------------------------------ */

    public int localId() {
        return localId;
    }

    public int remoteId() {
        return remoteId;
    }

    public String destination() {
        return destination;
    }

    public boolean isClosed() {
        return closed;
    }

    String failureReason() {
        return failure;
    }

    /* ------------------------------ 读 ------------------------------ */

    /** 读取数据；返回 -1 表示设备已关闭该流（EOF）。 */
    public int read(byte[] b, int off, int len) throws IOException {
        if (b == null) {
            throw new NullPointerException("buffer == null");
        }
        if (off < 0 || len < 0 || off + len > b.length) {
            throw new IndexOutOfBoundsException("off=" + off + " len=" + len + " capacity=" + b.length);
        }
        if (len == 0) {
            return 0;
        }
        while (true) {
            synchronized (readLock) {
                if (current != null && currentPos < current.length) {
                    int n = Math.min(len, current.length - currentPos);
                    System.arraycopy(current, currentPos, b, off, n);
                    currentPos += n;
                    return n;
                }
                current = null;
                currentPos = 0;
            }
            byte[] chunk = takeChunk();
            if (chunk == null) {
                return -1;
            }
            synchronized (readLock) {
                current = chunk;
                currentPos = 0;
            }
        }
    }

    /** 读满 len 字节（用于协议头）；返回 false 表示中途 EOF。 */
    public boolean readFully(byte[] b, int off, int len) throws IOException {
        int done = 0;
        while (done < len) {
            int n = read(b, off + done, len - done);
            if (n < 0) {
                return false;
            }
            done += n;
        }
        return true;
    }

    /** 读取一行（遇到 \n 结束，去掉行尾 \r），EOF 且无数据时返回 null。 */
    public String readLine(int maxBytes) throws IOException {
        StringBuilder sb = new StringBuilder();
        while (sb.length() <= maxBytes) {
            int c = readOne();
            if (c < 0) {
                return sb.length() == 0 ? null : sb.toString();
            }
            if (c == '\n') {
                int n = sb.length();
                if (n > 0 && sb.charAt(n - 1) == '\r') {
                    sb.setLength(n - 1);
                }
                return sb.toString();
            }
            sb.append((char) (c & 0xFF));
        }
        throw new IOException("读取行超长（>" + maxBytes + "）: " + destination);
    }

    private int readOne() throws IOException {
        byte[] one = new byte[1];
        int n = read(one, 0, 1);
        return n <= 0 ? -1 : (one[0] & 0xFF);
    }

    private byte[] takeChunk() throws IOException {
        while (true) {
            if (closed && queue.isEmpty()) {
                return null;
            }
            try {
                byte[] chunk = queue.poll(200, TimeUnit.MILLISECONDS);
                if (chunk != null) {
                    return chunk;
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("读取 ADB 流被中断: " + destination, e);
            }
            if (closed && queue.isEmpty()) {
                return null;
            }
        }
    }

    /* ------------------------------ 写 ------------------------------ */

    public void write(byte[] b, int off, int len) throws IOException {
        if (len <= 0) {
            return;
        }
        if (closed) {
            throw new IOException("ADB 流已关闭: " + destination);
        }
        if (remoteId == 0) {
            throw new IOException("ADB 流尚未就绪（等待 OKAY）: " + destination);
        }
        connection.writeStream(this, b, off, len);
    }

    public void write(byte[] b) throws IOException {
        write(b, 0, b.length);
    }

    public void write(String text) throws IOException {
        write(text.getBytes(UTF8));
    }

    /** 标准流适配：方便 SyncClient / 上层解析直接当 InputStream 使用。 */
    public InputStream getInputStream() {
        synchronized (streamLock) {
            if (inputStreamAdapter == null) {
                inputStreamAdapter = new InputStream() {
                    @Override
                    public int read() throws IOException {
                        byte[] one = new byte[1];
                        int n = AdbStream.this.read(one, 0, 1);
                        return n <= 0 ? -1 : (one[0] & 0xFF);
                    }

                    @Override
                    public int read(byte[] b, int off, int len) throws IOException {
                        return AdbStream.this.read(b, off, len);
                    }

                    @Override
                    public void close() {
                        AdbStream.this.close();
                    }
                };
            }
            return inputStreamAdapter;
        }
    }

    public OutputStream getOutputStream() {
        synchronized (streamLock) {
            if (outputStreamAdapter == null) {
                outputStreamAdapter = new OutputStream() {
                    @Override
                    public void write(int b) throws IOException {
                        AdbStream.this.write(new byte[]{(byte) b}, 0, 1);
                    }

                    @Override
                    public void write(byte[] b, int off, int len) throws IOException {
                        AdbStream.this.write(b, off, len);
                    }

                    @Override
                    public void close() {
                        AdbStream.this.close();
                    }
                };
            }
            return outputStreamAdapter;
        }
    }

    /** 关闭流：通知设备 CLSE，并唤醒所有阻塞读。可重复调用。 */
    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        connection.unregisterStream(localId);
        if (remoteId != 0) {
            try {
                connection.sendClose(localId, remoteId);
            } catch (Throwable ignored) {
            }
        }
        queue.clear();
        ready.countDown();
    }

    /* ------------------------------ 连接层回调 ------------------------------ */

    void onOkay(int remoteIdFromDevice) {
        if (remoteId == 0 && remoteIdFromDevice != 0) {
            remoteId = remoteIdFromDevice;
        }
        ready.countDown();
    }

    void onRemoteData(byte[] data) {
        if (closed) {
            return;
        }
        boolean queued = false;
        while (!closed) {
            try {
                if (queue.offer(data, 100, TimeUnit.MILLISECONDS)) {
                    queued = true;
                    break;
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        // 入队成功后才回 OKAY，让设备端按我们的消费速度继续发数据（背压）
        if (queued && !closed && remoteId != 0) {
            connection.sendOkay(localId, remoteId);
        }
    }

    void onRemoteClose() {
        closed = true;
        connection.unregisterStream(localId);
        ready.countDown();
    }

    boolean awaitReady(long timeoutMs) {
        try {
            return ready.await(timeoutMs, TimeUnit.MILLISECONDS) && failure == null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /** OPEN 被设备拒绝（CLSE 且 remote-id=0）时调用。 */
    void markFailed(String reason) {
        this.failure = reason;
        this.closed = true;
        connection.unregisterStream(localId);
        ready.countDown();
    }

    @Override
    public String toString() {
        return "AdbStream{" + destination + ", local=" + localId + ", remote=" + remoteId + ", closed=" + closed + "}";
    }
}
