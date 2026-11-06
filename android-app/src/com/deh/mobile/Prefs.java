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

    public static boolean autoUpdate(Context context) {
        return sp(context).getBoolean("auto_update", false);
    }

    public static void setAutoUpdate(Context context, boolean enabled) {
        sp(context).edit().putBoolean("auto_update", enabled).apply();
    }

    public static String shizukuToken(Context context) {
        return sp(context).getString("shizuku_token", "");
    }

    public static void setShizukuToken(Context context, String token) {
        sp(context).edit().putString("shizuku_token", token).apply();
    }

    /** AI 接口类型：deepseek（官方）或 custom（第三方 OpenAI 兼容）。 */
    public static String apiProvider(Context context) {
        return sp(context).getString("api_provider", "deepseek");
    }

    public static void setApiProvider(Context context, String provider) {
        sp(context).edit().putString("api_provider", provider == null ? "deepseek" : provider.trim()).apply();
    }

    /** 接口 Base URL（不含 /chat/completions），空则用官方默认。 */
    public static String apiBase(Context context) {
        return sp(context).getString("api_base", "");
    }

    public static void setApiBase(Context context, String base) {
        sp(context).edit().putString("api_base", base == null ? "" : base.trim()).apply();
    }

    /** 模型名，空则用 deepseek-chat。 */
    public static String apiModel(Context context) {
        return sp(context).getString("api_model", "");
    }

    public static void setApiModel(Context context, String model) {
        sp(context).edit().putString("api_model", model == null ? "" : model.trim()).apply();
    }

    public static String apiKey(Context context) {
        return sp(context).getString("api_key", "");
    }

    public static void setApiKey(Context context, String key) {
        sp(context).edit().putString("api_key", key == null ? "" : key.trim()).apply();
    }

    public static void clear(Context context) {
        sp(context).edit().clear().apply();
    }

    private static SharedPreferences sp(Context context) {
        return context.getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    /** 通用字符串存取（兼容 ADB 协议类）。 */
    public static String get(String key, String def) {
        return sharedPrefs().getString(key, def);
    }

    public static void set(String key, String value) {
        sharedPrefs().edit().putString(key, value).apply();
    }

    public static boolean getBool(String key, boolean def) {
        return sharedPrefs().getBoolean(key, def);
    }

    public static void setBool(String key, boolean value) {
        sharedPrefs().edit().putBoolean(key, value).apply();
    }

    public static int getInt(String key, int def) {
        return sharedPrefs().getInt(key, def);
    }

    public static void setInt(String key, int value) {
        sharedPrefs().edit().putInt(key, value).apply();
    }

    private static android.content.SharedPreferences sharedPrefs() {
        return App.ctx().getSharedPreferences("dsh", android.content.Context.MODE_PRIVATE);
    }
}
