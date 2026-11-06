package com.deh.mobile.adb;

import java.util.List;

/**
 * 统一设备目标抽象：本机（Shizuku/root）与远程 ADB 设备（TCP/USB OTG）
 * 对上层提供完全一致的接口。
 */
public interface DeviceTarget {
    String id();

    String name();

    /** local / tcp / usb */
    String type();

    String serial();

    boolean isLocal();

    boolean isConnected();

    ExecHandle exec(String command, ExecSink sink) throws Exception;

    void cancelExec(String execId);

    void push(String localPath, String remotePath, ProgressSink progress) throws Exception;

    void pull(String remotePath, String localPath, ProgressSink progress) throws Exception;

    List<FileEntry> list(String path) throws Exception;

    FileEntry stat(String path) throws Exception;

    void delete(String path, boolean recursive) throws Exception;

    void mkdirs(String path) throws Exception;

    void rename(String from, String to) throws Exception;

    String readText(String path, int maxBytes) throws Exception;

    void writeText(String path, String content, boolean append) throws Exception;

    String getProperty(String key) throws Exception;

    /** 返回实际本地端口 */
    int forward(int localTcpPort, String remoteSpec) throws Exception;

    void reverse(String remoteSpec, int localTcpPort) throws Exception;

    void close();

    interface ExecSink {
        void onData(String chunk, boolean stderr);

        void onExit(int code);
    }

    interface ProgressSink {
        void onProgress(long done, long total);
    }

    interface ExecHandle {
        String id();

        void cancel();
    }

    final class FileEntry {
        public String name;
        public String path;
        public boolean dir;
        public boolean link;
        public long size;
        public long mtime;
        public String mode;
    }
}
