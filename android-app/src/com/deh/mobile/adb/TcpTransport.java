package com.deh.mobile.adb;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketException;

/**
 * TCP 传输：连接设备的 adbd（无线调试 / 已开 5555 端口 / 转发出来的本地端口）。
 *
 * <p>读超时设为 0（无限阻塞），由上层（AdbConnection）用独立线程 + 超时控制。
 * 连接成功后禁用 Nagle，避免 ADB 小包被合并造成延迟。</p>
 */
public final class TcpTransport implements AdbTransport {

    private final Socket socket;
    private final InputStream in;
    private final OutputStream out;
    private final String host;
    private final int port;
    private volatile boolean closed;

    public TcpTransport(String host, int port, int timeoutMs) throws IOException {
        if (host == null || host.trim().isEmpty()) {
            throw new IOException("ADB 主机地址为空");
        }
        if (port <= 0 || port > 65535) {
            throw new IOException("ADB 端口非法: " + port);
        }
        this.host = host.trim();
        this.port = port;
        Socket s = new Socket();
        try {
            s.setTcpNoDelay(true);
            s.setKeepAlive(true);
            s.connect(new InetSocketAddress(this.host, port), Math.max(1000, timeoutMs));
            s.setSoTimeout(0);
        } catch (IOException e) {
            try {
                s.close();
            } catch (Throwable ignored) {
            }
            String msg = e instanceof SocketException ? String.valueOf(e.getMessage()) : String.valueOf(e);
            throw new IOException("无法连接 ADB 服务 " + this.host + ":" + port + "（" + msg + "）", e);
        }
        this.socket = s;
        this.in = s.getInputStream();
        this.out = s.getOutputStream();
    }

    @Override
    public int read(byte[] b, int off, int len) throws IOException {
        if (closed) {
            throw new IOException("TCP 传输已关闭: " + description());
        }
        if (len <= 0) {
            return 0;
        }
        try {
            return in.read(b, off, len);
        } catch (IOException e) {
            throw new IOException("TCP 读取失败（连接可能已断开）: " + description() + " : " + e, e);
        }
    }

    @Override
    public void write(byte[] b, int off, int len) throws IOException {
        if (closed) {
            throw new IOException("TCP 传输已关闭: " + description());
        }
        if (len <= 0) {
            return;
        }
        try {
            out.write(b, off, len);
            out.flush();
        } catch (IOException e) {
            throw new IOException("TCP 写入失败（连接可能已断开）: " + description() + " : " + e, e);
        }
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        try {
            socket.close();
        } catch (Throwable ignored) {
        }
    }

    @Override
    public String description() {
        return "tcp://" + host + ":" + port;
    }

    public String host() {
        return host;
    }

    public int port() {
        return port;
    }
}
