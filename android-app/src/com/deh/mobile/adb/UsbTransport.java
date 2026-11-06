package com.deh.mobile.adb;

import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;

import java.io.IOException;

/**
 * USB OTG 传输：在 ADB 接口（class 0xFF / subclass 0x42 / protocol 0x01）的两个
 * Bulk 端点上收发原始 ADB 消息。
 *
 * <p>读取超时设为 0（无限等待），由上层读线程负责生命周期；写超时 5 秒。
 * 断线时 bulkTransfer 返回负值，这里统一转成带中文说明的 IOException。</p>
 */
public final class UsbTransport implements AdbTransport {

    private static final int WRITE_TIMEOUT_MS = 5000;
    private static final int READ_TIMEOUT_MS = 0; // 0 = 无限等待，直到有数据或断开

    private final UsbDeviceConnection connection;
    private final UsbEndpoint endpointIn;
    private final UsbEndpoint endpointOut;
    private final UsbInterface usbInterface;
    private final UsbDevice device;
    private final String description;
    private volatile boolean closed;

    public UsbTransport(UsbDeviceConnection connection, UsbInterface usbInterface,
                        UsbEndpoint endpointIn, UsbEndpoint endpointOut, UsbDevice device) {
        if (connection == null || usbInterface == null || endpointIn == null || endpointOut == null) {
            throw new IllegalArgumentException("USB 传输参数不完整");
        }
        this.connection = connection;
        this.usbInterface = usbInterface;
        this.endpointIn = endpointIn;
        this.endpointOut = endpointOut;
        this.device = device;
        this.description = "usb:" + (device == null ? "unknown" : device.getDeviceName());
    }

    @Override
    public int read(byte[] b, int off, int len) throws IOException {
        if (closed) {
            throw new IOException("USB 传输已关闭: " + description);
        }
        if (b == null) {
            throw new NullPointerException("buffer == null");
        }
        if (off < 0 || len < 0 || off + len > b.length) {
            throw new IndexOutOfBoundsException("off=" + off + " len=" + len + " capacity=" + b.length);
        }
        if (len == 0) {
            return 0;
        }
        int n;
        try {
            if (off == 0) {
                n = connection.bulkTransfer(endpointIn, b, len, READ_TIMEOUT_MS);
            } else {
                byte[] tmp = new byte[len];
                n = connection.bulkTransfer(endpointIn, tmp, len, READ_TIMEOUT_MS);
                if (n > 0) {
                    System.arraycopy(tmp, 0, b, off, n);
                }
            }
        } catch (Throwable t) {
            throw new IOException("USB 读取失败（设备可能已断开）: " + description + " : " + t, t);
        }
        if (n < 0) {
            throw new IOException("USB 读取失败（设备可能已断开或端点异常）: " + description);
        }
        return n;
    }

    @Override
    public void write(byte[] b, int off, int len) throws IOException {
        if (closed) {
            throw new IOException("USB 传输已关闭: " + description);
        }
        if (b == null) {
            throw new NullPointerException("buffer == null");
        }
        if (off < 0 || len < 0 || off + len > b.length) {
            throw new IndexOutOfBoundsException("off=" + off + " len=" + len + " capacity=" + b.length);
        }
        int written = 0;
        while (written < len) {
            int n;
            try {
                n = connection.bulkTransfer(endpointOut, b, off + written, len - written,
                        WRITE_TIMEOUT_MS);
            } catch (Throwable t) {
                throw new IOException("USB 写入失败（设备可能已断开）: " + description + " : " + t, t);
            }
            if (n <= 0) {
                throw new IOException("USB 写入失败（设备可能已断开，已写 " + written + "/" + len
                        + " 字节）: " + description);
            }
            written += n;
        }
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        try {
            connection.releaseInterface(usbInterface);
        } catch (Throwable ignored) {
        }
        try {
            connection.close();
        } catch (Throwable ignored) {
        }
    }

    @Override
    public String description() {
        return description;
    }

    public UsbDevice device() {
        return device;
    }

    public boolean isClosed() {
        return closed;
    }
}
