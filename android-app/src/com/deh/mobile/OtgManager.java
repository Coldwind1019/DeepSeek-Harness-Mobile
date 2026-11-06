package com.deh.mobile;

import android.content.Context;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.os.Build;
import android.os.storage.StorageManager;
import android.os.storage.StorageVolume;

import java.io.File;
import java.lang.reflect.Method;
import java.util.Map;

/**
 * OTG（USB Host）支持：枚举已连接 USB 设备、识别 USB 存储卷，提供状态摘要。
 */
public final class OtgManager {
    private OtgManager() {
    }

    /** 状态摘要（用于设置页显示 / Toast）。 */
    public static String summary(Context context) {
        StringBuilder sb = new StringBuilder();
        try {
            UsbManager usb = (UsbManager) context.getSystemService(Context.USB_SERVICE);
            Map<String, UsbDevice> devices = usb == null ? null : usb.getDeviceList();
            int n = devices == null ? 0 : devices.size();
            sb.append(n == 0 ? "未连接" : "已连接 " + n + " 个设备");
            if (devices != null) {
                for (UsbDevice d : devices.values()) {
                    sb.append("\n· ");
                    String name = null;
                    try {
                        name = d.getProductName();
                    } catch (Throwable ignored) {
                    }
                    if (name == null || name.isEmpty()) name = d.getDeviceName();
                    sb.append(name)
                            .append("  VID=").append(String.format("%04x", d.getVendorId()))
                            .append(" PID=").append(String.format("%04x", d.getProductId()));
                    int cls = d.getDeviceClass();
                    if (cls == 8) sb.append(" [存储]");
                    else if (cls == 3) sb.append(" [键鼠]");
                    else if (cls == 2) sb.append(" [串口/通信]");
                    else if (cls == 0) sb.append(" [复合]");
                }
            }
        } catch (Throwable t) {
            sb.append("USB 枚举失败: ").append(t);
        }

        // USB 存储卷（U 盘挂载）
        try {
            StorageManager sm = (StorageManager) context.getSystemService(Context.STORAGE_SERVICE);
            if (sm != null) {
                for (StorageVolume v : sm.getStorageVolumes()) {
                    if (!v.isRemovable()) continue;
                    String desc = "";
                    try {
                        desc = String.valueOf(v.getDescription(context));
                    } catch (Throwable ignored) {
                    }
                    String path = volumePath(v);
                    sb.append("\n存储: ").append(desc);
                    if (path != null) sb.append(" → ").append(path);
                }
            }
        } catch (Throwable ignored) {
        }
        return sb.toString();
    }

    /** 取 USB 存储挂载路径（API 30+ 用 getDirectory，旧版反射 getPath）。 */
    public static String volumePath(StorageVolume v) {
        try {
            if (Build.VERSION.SDK_INT >= 30) {
                File dir = v.getDirectory();
                if (dir != null) return dir.getAbsolutePath();
            }
        } catch (Throwable ignored) {
        }
        try {
            Method m = StorageVolume.class.getMethod("getPath");
            Object p = m.invoke(v);
            return p == null ? null : p.toString();
        } catch (Throwable ignored) {
        }
        try {
            Method m = StorageVolume.class.getMethod("getPathFile");
            Object p = m.invoke(v);
            return p == null ? null : p.toString();
        } catch (Throwable ignored) {
        }
        return null;
    }

    /** 申请某个 USB 设备访问权限。 */
    public static void requestPermission(Context context, UsbDevice device) {
        try {
            UsbManager usb = (UsbManager) context.getSystemService(Context.USB_SERVICE);
            if (usb == null || device == null) return;
            if (usb.hasPermission(device)) return;
            android.app.PendingIntent pi = android.app.PendingIntent.getBroadcast(
                    context, 0,
                    new android.content.Intent("com.deh.mobile.USB_PERMISSION"),
                    android.app.PendingIntent.FLAG_IMMUTABLE | android.app.PendingIntent.FLAG_UPDATE_CURRENT);
            usb.requestPermission(device, pi);
        } catch (Throwable ignored) {
        }
    }

    /** 列出已连接 USB 设备的名字数组（供选择弹窗）。 */
    public static String[] deviceNames(Context context) {
        try {
            UsbManager usb = (UsbManager) context.getSystemService(Context.USB_SERVICE);
            if (usb == null) return new String[0];
            Map<String, UsbDevice> devices = usb.getDeviceList();
            String[] out = new String[devices.size()];
            int i = 0;
            for (UsbDevice d : devices.values()) {
                String name = null;
                try {
                    name = d.getProductName();
                } catch (Throwable ignored) {
                }
                if (name == null || name.isEmpty()) name = d.getDeviceName();
                out[i++] = name + "  (" + String.format("%04x:%04x", d.getVendorId(), d.getProductId()) + ")";
            }
            return out;
        } catch (Throwable t) {
            return new String[0];
        }
    }
}
