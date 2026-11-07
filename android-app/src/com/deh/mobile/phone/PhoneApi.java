package com.deh.mobile.phone;

import android.content.Context;

import com.deh.mobile.ShizukuBridge;

import org.json.JSONObject;

import java.io.File;

/** 手机能力接口：UI 树 / 点击 / 输入 / 截图 / 内录（由 LocalBridge /phone/* 调用）。 */
public final class PhoneApi {
    private static Context CTX;

    public static void init(Context c) { CTX = c.getApplicationContext(); }

    private static String esc(String s) {
        return s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    /** 确保无障碍服务已开启（Shizuku：settings put secure）。 */
    public static String ensureAccessibility() {
        String svc = CTX.getPackageName() + "/com.deh.mobile.phone.PhoneUiService";
        try {
            String cur = ShizukuBridge.run("settings get secure enabled_accessibility_services", 15000);
            if (cur != null && cur.contains("PhoneUiService")) {
                return "{\"ok\":true,\"already\":true}";
            }
            String val = (cur == null || cur.trim().isEmpty() || "null".equals(cur.trim()))
                    ? svc : cur.trim() + ":" + svc;
            ShizukuBridge.run("settings put secure enabled_accessibility_services '" + val + "'", 15000);
            ShizukuBridge.run("settings put secure accessibility_enabled 1", 15000);
            return "{\"ok\":true,\"enabled\":true}";
        } catch (Throwable t) {
            return "{\"ok\":false,\"error\":\"" + esc(String.valueOf(t)) + "\"}";
        }
    }

    /** UI 树。 */
    public static String dumpUi() {
        PhoneUiService s = PhoneUiService.get();
        if (s == null) {
            return "{\"ok\":false,\"error\":\"accessibility service not running\"}";
        }
        return s.dumpUi(600);
    }

    public static String clickText(String text) {
        PhoneUiService s = PhoneUiService.get();
        if (s != null && s.clickText(text)) return "{\"ok\":true,\"via\":\"a11y\"}";
        return "{\"ok\":false,\"error\":\"not found / a11y off\"}";
    }

    public static String clickAt(int x, int y) {
        PhoneUiService s = PhoneUiService.get();
        if (s != null && s.clickAt(x, y)) return "{\"ok\":true,\"via\":\"a11y\"}";
        try {
            ShizukuBridge.run("input tap " + x + " " + y, 15000);
            return "{\"ok\":true,\"via\":\"shell\"}";
        } catch (Throwable t) {
            return "{\"ok\":false,\"error\":\"" + esc(String.valueOf(t)) + "\"}";
        }
    }

    public static String typeText(String text) {
        PhoneUiService s = PhoneUiService.get();
        if (s != null && s.typeText(text)) return "{\"ok\":true,\"via\":\"a11y\"}";
        try {
            String q = text.replace(" ", "%s");
            ShizukuBridge.run("input text '" + q.replace("'", "'\\''") + "'", 15000);
            return "{\"ok\":true,\"via\":\"shell\"}";
        } catch (Throwable t) {
            return "{\"ok\":false,\"error\":\"" + esc(String.valueOf(t)) + "\"}";
        }
    }

    public static String swipe(int x1, int y1, int x2, int y2, int ms) {
        PhoneUiService s = PhoneUiService.get();
        if (s != null && s.swipe(x1, y1, x2, y2, ms)) return "{\"ok\":true,\"via\":\"a11y\"}";
        try {
            ShizukuBridge.run("input swipe " + x1 + " " + y1 + " " + x2 + " " + y2 + " " + Math.max(50, ms), 15000);
            return "{\"ok\":true,\"via\":\"shell\"}";
        } catch (Throwable t) {
            return "{\"ok\":false,\"error\":\"" + esc(String.valueOf(t)) + "\"}";
        }
    }

    /** 截图（Shizuku shell screencap，无弹窗）→ 返回文件路径。 */
    public static String screenshot() {
        try {
            File dir = new File("/storage/emulated/0/DEH/.phone");
            if (!dir.isDirectory()) dir.mkdirs();
            String path = dir.getAbsolutePath() + "/shot_" + System.currentTimeMillis() + ".png";
            ShizukuBridge.run("screencap -p '" + path + "'", 30000);
            File f = new File(path);
            if (!f.isFile() || f.length() < 100) {
                return "{\"ok\":false,\"error\":\"screencap failed\"}";
            }
            JSONObject o = new JSONObject();
            o.put("ok", true);
            o.put("path", path);
            o.put("size", f.length());
            return o.toString();
        } catch (Throwable t) {
            return "{\"ok\":false,\"error\":\"" + esc(String.valueOf(t)) + "\"}";
        }
    }

    /** 执行 shell（Shizuku）。 */
    public static String shell(String cmd) {
        try {
            String out = ShizukuBridge.run(cmd, 60000);
            return "{\"ok\":true,\"output\":\"" + esc(out) + "\"}";
        } catch (Throwable t) {
            return "{\"ok\":false,\"error\":\"" + esc(String.valueOf(t)) + "\"}";
        }
    }
}
