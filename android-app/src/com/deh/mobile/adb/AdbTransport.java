package com.deh.mobile.adb;

import java.io.IOException;

/**
 * ADB 传输层抽象：TCP（Socket）与 USB（Bulk Transfer）共用同一套 ADB 协议栈。
 *
 * <p>协议参考：AOSP packages/modules/adb（Apache-2.0）与 JADB（Apache-2.0）。
 * 本文件为独立实现，未整包引入任何第三方依赖。</p>
 */
public interface AdbTransport {

    /**
     * 读入最多 len 字节，阻塞直到至少读到 1 字节。
     *
     * @return 实际读到的字节数；返回 -1 表示对端已关闭连接（EOF）
     */
    int read(byte[] b, int off, int len) throws IOException;

    /** 完整写出 len 字节（内部负责循环直到写完），失败抛 IOException。 */
    void write(byte[] b, int off, int len) throws IOException;

    /** 关闭传输；必须可重复调用且不抛异常。 */
    void close();

    /** 传输描述，用于日志与错误提示，例如 tcp://127.0.0.1:5555 / usb:/dev/bus/usb/001/002。 */
    String description();
}
