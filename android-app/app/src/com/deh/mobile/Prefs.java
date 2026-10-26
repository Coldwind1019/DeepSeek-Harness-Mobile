package com.deh.mobile;

import android.content.Context;
import android.content.SharedPreferences;

/** 轻量偏好设置：工作区根、桌面视图。 */
public final class Prefs {
    private static final String FILE = "dsh_mobile";

    private Prefs() {
    }

    /** 工作区根模式：internal（内部存储）或 private（应用私有目录）。 */
    public static String workRootMode(Context context) {
        return sp(context).getString("work_root", "internal");
    }

    public static void setWorkRootMode(Context context, String mode) {
        sp(context).edit().putString("work_root", mode).apply();
    }

    public static boolean deskView(Context context) {
        return sp(context).getBoolean("desk_view", false);
    }

    public static void setDeskView(Context context, boolean enabled) {
        sp(context).edit().putBoolean("desk_view", enabled).apply();
    }

    public static int deskScale(Context context) {
        return sp(context).getInt("desk_scale", 80);
    }

    public static void setDeskScale(Context context, int scale) {
        sp(context).edit().putInt("desk_scale", scale).apply();
    }

    public static String defaultOpenApp(Context context, String mime) {
        return sp(context).getString("open_default_" + mime, "");
    }

    public static void setDefaultOpenApp(Context context, String mime, String flat) {
        sp(context).edit().putString("open_default_" + mime, flat).apply();
    }

    public static void clearDefaultOpenApp(Context context) {
        android.content.SharedPreferences.Editor editor = sp(context).edit();
        for (String key : sp(context).getAll().keySet()) {
            if (key.startsWith("open_default_")) editor.remove(key);
        }
        editor.apply();
    }

    public static String shizukuToken(Context context) {
        return sp(context).getString("shizuku_token", "");
    }

    public static void setShizukuToken(Context context, String token) {
        sp(context).edit().putString("shizuku_token", token).apply();
    }

    public static void clear(Context context) {
        sp(context).edit().clear().apply();
    }

    private static SharedPreferences sp(Context context) {
        return context.getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }
}
