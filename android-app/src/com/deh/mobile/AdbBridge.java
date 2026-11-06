package com.deh.mobile;

import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;

import com.deh.mobile.adb.AdbManager;
import com.deh.mobile.adb.DeviceTarget;
import com.deh.mobile.adb.UsbHost;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** OTG ADB 桥：给容器内 AI 提供 HTTP 接口（/adb/*），走纯 Java ADB over USB。 */
public final class AdbBridge {
    private static volatile DeviceTarget current;
    private static volatile String currentSerial = "";

    private AdbBridge() {
    }

    public static synchronized String connect(String deviceName) {
        try {
            AdbManager.get().init(App.ctx());
            UsbDevice device = null;
            List<UsbDevice> list = UsbHost.findAdbDevices(App.ctx());
            for (UsbDevice d : list) {
                if (deviceName == null || deviceName.isEmpty() || deviceName.equals(d.getDeviceName())) {
                    device = d;
                    break;
                }
            }
            if (device == null) {
                if (list.isEmpty()) {
                    UsbManager usb = (UsbManager) App.ctx().getSystemService(android.content.Context.USB_SERVICE);
                    StringBuilder sb = new StringBuilder();
                    for (UsbDevice d : usb.getDeviceList().values()) {
                        sb.append(d.getDeviceName()).append("(").append(String.format("%04x:%04x", d.getVendorId(), d.getProductId())).append(") ");
                    }
                    return "{\"error\":\"no adb usb device; usb devices: " + esc(sb.toString()) + "\"}";
                }
                device = list.get(0);
            }
            if (!UsbHost.hasPermission(App.ctx(), device)) {
                OtgManager.requestPermission(App.ctx(), device);
                return "{\"error\":\"usb permission requested; allow the dialog on the phone then retry connect\"}";
            }
            current = AdbManager.get().connectUsb(device);
            currentSerial = current.serial();
            return "{\"ok\":true,\"serial\":" + esc(currentSerial) + ",\"name\":" + esc(current.name()) + "}";
        } catch (Throwable t) {
            return "{\"error\":" + esc(String.valueOf(t)) + "}";
        }
    }

    public static String list() {
        try {
            AdbManager.get().init(App.ctx());
            UsbManager usb = (UsbManager) App.ctx().getSystemService(android.content.Context.USB_SERVICE);
            StringBuilder sb = new StringBuilder("[");
            boolean first = true;
            for (UsbDevice d : usb.getDeviceList().values()) {
                if (!first) sb.append(',');
                first = false;
                boolean adb = UsbHost.hasAdbInterface(d);
                sb.append("{\"device\":").append(esc(d.getDeviceName()))
                        .append(",\"vid\":").append(esc(String.format("%04x", d.getVendorId())))
                        .append(",\"pid\":").append(esc(String.format("%04x", d.getProductId())))
                        .append(",\"adb\":").append(adb)
                        .append(",\"permission\":").append(UsbHost.hasPermission(App.ctx(), d))
                        .append('}');
            }
            sb.append(']');
            return "{\"connected\":" + esc(currentSerial) + ",\"devices\":" + sb + "}";
        } catch (Throwable t) {
            return "{\"error\":" + esc(String.valueOf(t)) + "}";
        }
    }

    private static DeviceTarget target() throws Exception {
        if (current != null && current.isConnected()) return current;
        String c = connect(null);
        if (current == null || !current.isConnected()) {
            throw new IllegalStateException("adb not connected: " + c);
        }
        return current;
    }

    public static String tcpip(int port) {
        try {
            DeviceTarget t = target();
            if (!(t instanceof com.deh.mobile.adb.RemoteTarget)) {
                return "{\"error\":\"not a remote target\"}";
            }
            String resp = ((com.deh.mobile.adb.RemoteTarget) t).tcpip(port);
            return "{\"ok\":true,\"port\":" + port + ",\"response\":" + esc(resp) + "}";
        } catch (Throwable e) {
            return "{\"error\":" + esc(String.valueOf(e)) + "}";
        }
    }

    public static String shell(String cmd) {
        try {
            if (cmd == null || cmd.isEmpty()) return "{\"error\":\"cmd required\"}";
            DeviceTarget t = target();
            final StringBuilder out = new StringBuilder();
            final CountDownLatch latch = new CountDownLatch(1);
            final int[] code = {0};
            t.exec(cmd, new DeviceTarget.ExecSink() {
                @Override
                public void onData(String chunk, boolean stderr) {
                    out.append(chunk);
                }

                @Override
                public void onExit(int exitCode) {
                    code[0] = exitCode;
                    latch.countDown();
                }
            });
            latch.await(90, TimeUnit.SECONDS);
            return "{\"ok\":true,\"exit\":" + code[0] + ",\"output\":" + esc(out.toString()) + "}";
        } catch (Throwable e) {
            return "{\"error\":" + esc(String.valueOf(e)) + "}";
        }
    }

    public static String install(String apkPath) {
        try {
            if (apkPath == null || apkPath.isEmpty()) return "{\"error\":\"path required\"}";
            java.io.File f = new java.io.File(apkPath);
            if (!f.isFile()) return "{\"error\":\"apk not found: " + esc(apkPath) + "\"}";
            DeviceTarget t = target();
            String remote = "/data/local/tmp/" + f.getName();
            final CountDownLatch pushLatch = new CountDownLatch(1);
            t.push(f.getAbsolutePath(), remote, null);
            String r = shell("pm install -r " + remote);
            return r;
        } catch (Throwable e) {
            return "{\"error\":" + esc(String.valueOf(e)) + "}";
        }
    }

    public static String push(String local, String remote) {
        try {
            if (local == null || remote == null) return "{\"error\":\"path and cmd required\"}";
            DeviceTarget t = target();
            t.push(local, remote, null);
            return "{\"ok\":true}";
        } catch (Throwable e) {
            return "{\"error\":" + esc(String.valueOf(e)) + "}";
        }
    }

    public static String pull(String remote, String local) {
        try {
            if (remote == null || local == null) return "{\"error\":\"path and cmd required\"}";
            DeviceTarget t = target();
            t.pull(remote, local, null);
            return "{\"ok\":true}";
        } catch (Throwable e) {
            return "{\"error\":" + esc(String.valueOf(e)) + "}";
        }
    }

    static String esc(String s) {
        if (s == null) return "\"\"";
        StringBuilder sb = new StringBuilder("\"");
        for (char c : s.toCharArray()) {
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default: sb.append(c);
            }
        }
        return sb.append('"').toString();
    }
}
