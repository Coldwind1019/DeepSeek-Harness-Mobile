package com.deh.mobile.adb;

import android.content.Context;
import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;

import com.deh.mobile.App;
import com.deh.mobile.Logx;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * USB 主机（OTG）枚举与打开：筛选 ADB 接口
 * （interface class = 0xFF、subclass = 0x42、protocol = 0x01），
 * claim 接口后返回 {@link UsbTransport}。
 *
 * <p>注意：首次使用某设备需要系统授权（Bridge 已通过 UsbManager.requestPermission
 * 触发弹窗），未授权时这里抛出明确异常。</p>
 */
public final class UsbHost {

    private static final int ADB_CLASS = 0xFF;
    private static final int ADB_SUBCLASS = 0x42;
    private static final int ADB_PROTOCOL = 0x01;

    private UsbHost() {
    }

    /** 列出所有带 ADB 接口的 USB 设备（已排除 U 盘/键鼠等无关设备）。 */
    public static List<UsbDevice> findAdbDevices(Context ctx) {
        List<UsbDevice> out = new ArrayList<UsbDevice>();
        try {
            UsbManager um = manager(ctx);
            if (um == null) {
                return out;
            }
            for (UsbDevice d : um.getDeviceList().values()) {
                if (hasAdbInterface(d)) {
                    out.add(d);
                }
            }
        } catch (Throwable t) {
            Logx.e("枚举 USB 设备失败: " + t);
        }
        return out;
    }

    /** 是否存在 ADB 接口。 */
    public static boolean hasAdbInterface(UsbDevice device) {
        return findAdbInterface(device) != null;
    }

    /** 找到第一个 ADB 接口；没有则返回 null。 */
    public static UsbInterface findAdbInterface(UsbDevice device) {
        if (device == null) {
            return null;
        }
        try {
            for (int i = 0; i < device.getInterfaceCount(); i++) {
                UsbInterface intf = device.getInterface(i);
                if (intf == null) {
                    continue;
                }
                if (intf.getInterfaceClass() == ADB_CLASS
                        && intf.getInterfaceSubclass() == ADB_SUBCLASS
                        && intf.getInterfaceProtocol() == ADB_PROTOCOL) {
                    return intf;
                }
            }
        } catch (Throwable t) {
            Logx.e("读取 USB 接口失败: " + t);
        }
        return null;
    }

    /** 用应用上下文打开设备。 */
    public static UsbTransport open(UsbDevice device) throws IOException {
        return open(App.ctx(), device);
    }

    /**
     * 打开设备并 claim ADB 接口、定位 Bulk 端点，返回可读写的传输对象。
     *
     * @throws IOException 权限缺失 / 接口不匹配 / 设备被占用 / 端点缺失
     */
    public static UsbTransport open(Context ctx, UsbDevice device) throws IOException {
        if (device == null) {
            throw new IOException("USB 设备为空");
        }
        UsbManager um = manager(ctx);
        if (um == null) {
            throw new IOException("UsbManager 不可用");
        }
        if (!um.hasPermission(device)) {
            throw new IOException("没有该 USB 设备的访问权限，请先在设备列表中授权（deviceName="
                    + device.getDeviceName() + "）");
        }
        UsbInterface intf = findAdbInterface(device);
        if (intf == null) {
            throw new IOException("目标 USB 设备不是 ADB 设备（缺少 class=0xFF/subclass=0x42/proto=0x01 接口）");
        }
        UsbDeviceConnection connection = um.openDevice(device);
        if (connection == null) {
            throw new IOException("打开 USB 设备失败（可能被其他程序占用或系统未授权）: "
                    + device.getDeviceName());
        }
        boolean claimed;
        try {
            // force=true：即使内核已绑定驱动也强制 claim（ADB 接口通常未被内核驱动占用）
            claimed = connection.claimInterface(intf, true);
        } catch (Throwable t) {
            claimed = false;
        }
        if (!claimed) {
            try {
                connection.close();
            } catch (Throwable ignored) {
            }
            throw new IOException("claimInterface 失败（接口可能已被占用）: " + device.getDeviceName());
        }
        UsbEndpoint epIn = null;
        UsbEndpoint epOut = null;
        for (int i = 0; i < intf.getEndpointCount(); i++) {
            UsbEndpoint ep = intf.getEndpoint(i);
            if (ep == null || ep.getType() != UsbConstants.USB_ENDPOINT_XFER_BULK) {
                continue;
            }
            if (ep.getDirection() == UsbConstants.USB_DIR_IN) {
                if (epIn == null) {
                    epIn = ep;
                }
            } else if (epOut == null) {
                epOut = ep;
            }
        }
        if (epIn == null || epOut == null) {
            try {
                connection.releaseInterface(intf);
            } catch (Throwable ignored) {
            }
            try {
                connection.close();
            } catch (Throwable ignored) {
            }
            throw new IOException("ADB 接口缺少 Bulk 端点（in=" + (epIn != null)
                    + ", out=" + (epOut != null) + "）: " + device.getDeviceName());
        }
        Logx.i("已打开 USB ADB 设备: " + device.getDeviceName());
        return new UsbTransport(connection, intf, epIn, epOut, device);
    }

    /** 按 deviceName（/dev/bus/usb/...）查找设备。 */
    public static UsbDevice findByDeviceName(Context ctx, String deviceName) {
        UsbManager um = manager(ctx);
        if (um == null || deviceName == null) {
            return null;
        }
        try {
            Map<String, UsbDevice> map = um.getDeviceList();
            for (UsbDevice d : map.values()) {
                if (deviceName.equals(d.getDeviceName())) {
                    return d;
                }
            }
        } catch (Throwable t) {
            Logx.e("查找 USB 设备失败: " + t);
        }
        return null;
    }

    /** 设备是否已被系统授权。 */
    public static boolean hasPermission(Context ctx, UsbDevice device) {
        UsbManager um = manager(ctx);
        return um != null && device != null && um.hasPermission(device);
    }

    private static UsbManager manager(Context ctx) {
        Context c = ctx != null ? ctx : App.ctx();
        if (c == null) {
            return null;
        }
        try {
            return (UsbManager) c.getSystemService(Context.USB_SERVICE);
        } catch (Throwable t) {
            return null;
        }
    }
}
