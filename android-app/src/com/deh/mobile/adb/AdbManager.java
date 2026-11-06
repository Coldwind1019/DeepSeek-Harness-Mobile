package com.deh.mobile.adb;

import android.content.Context;
import android.hardware.usb.UsbDevice;

import com.deh.mobile.Prefs;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

/** 设备目标总线：统一管理本机目标与远程 ADB 目标（TCP / USB OTG）。 */
public final class AdbManager {
    private static final AdbManager INSTANCE = new AdbManager();

    private final LocalTarget local = new LocalTarget();
    private final LinkedHashMap<String, DeviceTarget> targets = new LinkedHashMap<>();
    private Context ctx;

    private AdbManager() {
    }

    public static AdbManager get() {
        return INSTANCE;
    }

    public synchronized void init(Context context) {
        this.ctx = context.getApplicationContext();
        if (!targets.containsKey(LocalTarget.ID)) {
            targets.put(LocalTarget.ID, local);
        }
    }

    public LocalTarget local() {
        return local;
    }

    public synchronized DeviceTarget get(String id) {
        if (id == null || id.isEmpty()) {
            return local;
        }
        return targets.get(id);
    }

    public synchronized List<DeviceTarget> listTargets() {
        return new ArrayList<>(targets.values());
    }

    public synchronized DeviceTarget connectTcp(String host, int port, int timeoutMs) throws Exception {
        DeviceTarget t = RemoteTarget.connectTcp(host, port, timeoutMs);
        DeviceTarget old = targets.put(t.id(), t);
        if (old != null && old != t && !old.isLocal()) {
            try {
                old.close();
            } catch (Throwable ignored) {
            }
        }
        String saved = Prefs.get("saved_tcp", "");
        String entry = host + ":" + port;
        if (!saved.contains(entry)) {
            saved = saved.isEmpty() ? entry : saved + "," + entry;
            Prefs.set("saved_tcp", saved);
        }
        return t;
    }

    public synchronized DeviceTarget connectUsb(UsbDevice device) throws Exception {
        DeviceTarget t = RemoteTarget.connectUsb(device, 20000);
        DeviceTarget old = targets.put(t.id(), t);
        if (old != null && old != t && !old.isLocal()) {
            try {
                old.close();
            } catch (Throwable ignored) {
            }
        }
        return t;
    }

    public synchronized void disconnect(String id) {
        DeviceTarget t = targets.get(id);
        if (t != null && !t.isLocal()) {
            targets.remove(id);
            try {
                t.close();
            } catch (Throwable ignored) {
            }
        }
    }

    public synchronized void shutdown() {
        for (DeviceTarget t : targets.values()) {
            if (!t.isLocal()) {
                try {
                    t.close();
                } catch (Throwable ignored) {
                }
            }
        }
        targets.clear();
        targets.put(LocalTarget.ID, local);
    }

    public List<UsbDevice> usbCandidates() {
        try {
            return UsbHost.findAdbDevices(ctx);
        } catch (Throwable t) {
            return new ArrayList<>();
        }
    }
}
