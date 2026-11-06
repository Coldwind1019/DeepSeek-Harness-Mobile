package com.deh.mobile;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.webkit.WebSettings;
import android.webkit.WebView;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * WebView 版本检测：
 * ① 从 UA 里解析真实 Chrome 内核版本（华为等定制包名版本号不可靠，UA 更准）；
 * ② 扫描 /storage/emulated/0/DEH/WebView检测安装包/ 下的 APK，读取包名/版本，
 *    用于判断是否有对应/可升级的 WebView 安装包。
 */
public final class WebViewProbe {
    public static final String APK_DIR = "/storage/emulated/0/DEH/WebView检测安装包";

    public static final class Result {
        public String ua = "";
        public int chromeMajor = 0;
        public String webviewPackage = "";
        public String webviewVersion = "";
        public final List<String> apks = new ArrayList<>();

        /** 档位：native / polyfill / es2017 / es2015 / unsupported */
        public String tier() {
            if (chromeMajor <= 0) return "unknown";
            if (chromeMajor >= 119) return "native";
            if (chromeMajor >= 94) return "polyfill";
            if (chromeMajor >= 72) return "es2017";
            if (chromeMajor >= 49) return "es2015";
            return "unsupported";
        }

        @Override
        public String toString() {
            return "UA版本=" + (chromeMajor > 0 ? "Chrome/" + chromeMajor : "?")
                    + " 包=" + webviewPackage + " " + webviewVersion
                    + " 档位=" + tier()
                    + " 检测包=" + apks;
        }
    }

    /** 最近一次检测结果（供 Runtime 选择兼容档位）。 */
    public static volatile Result LAST;

    private WebViewProbe() {
    }

    public static Result detect(Context context) {
        Result r = new Result();
        try {
            String ua = WebSettings.getDefaultUserAgent(context);
            if (ua == null || ua.isEmpty()) {
                try {
                    WebView probe = new WebView(context);
                    ua = probe.getSettings().getUserAgentString();
                    probe.destroy();
                } catch (Throwable ignored) {
                }
            }
            if (ua != null) {
                r.ua = ua;
                Matcher m = Pattern.compile("Chrome/(\\d+)").matcher(ua);
                if (m.find()) {
                    try {
                        r.chromeMajor = Integer.parseInt(m.group(1));
                    } catch (Throwable ignored) {
                    }
                }
            }
        } catch (Throwable ignored) {
        }

        try {
            PackageInfo pi = WebView.getCurrentWebViewPackage();
            if (pi != null) {
                r.webviewPackage = pi.packageName == null ? "" : pi.packageName;
                r.webviewVersion = pi.versionName == null ? "" : pi.versionName;
                if (r.chromeMajor <= 0 && r.webviewVersion != null) {
                    // 包名版本号兜底（Google 系 WebView 版本号近似 Chromium 主版本）
                    Matcher m = Pattern.compile("^(\\d+)").matcher(r.webviewVersion);
                    if (m.find()) {
                        try {
                            r.chromeMajor = Integer.parseInt(m.group(1));
                        } catch (Throwable ignored) {
                        }
                    }
                }
            }
        } catch (Throwable ignored) {
        }

        // 扫描检测包目录
        try {
            File dir = new File(APK_DIR);
            File[] files = dir.listFiles();
            if (files != null) {
                PackageManager pm = context.getPackageManager();
                for (File f : files) {
                    if (!f.getName().toLowerCase().endsWith(".apk")) continue;
                    try {
                        PackageInfo info = pm.getPackageArchiveInfo(f.getAbsolutePath(), 0);
                        if (info == null) continue;
                        String pkg = info.packageName == null ? "?" : info.packageName;
                        String ver = info.versionName == null ? "?" : info.versionName;
                        r.apks.add(f.getName() + " → " + pkg + " " + ver);
                    } catch (Throwable ignored) {
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        LAST = r;
        return r;
    }
}
