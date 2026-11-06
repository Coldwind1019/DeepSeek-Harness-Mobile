package com.deh.mobile;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.drawable.Drawable;
import android.content.res.Configuration;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.method.ScrollingMovementMethod;
import android.view.View;
import android.view.WindowManager;
import android.webkit.CookieManager;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.GridView;
import android.widget.PopupMenu;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.io.FileOutputStream;

/**
 * DeepSeek Harness 手机端主界面：
 * 本机解压 Linux 运行时、安装 DSH、启动本地 dsh web，用 WebView 打开。
 * 支持横竖屏自适应、会话内文件上传、内部存储/应用私有目录双工作区根。
 */
public class MainActivity extends Activity {
    private static final int REQUEST_FILE = 1001;
    private static final String POLYFILL_JS =
            "(function(){"
            + "if(typeof Promise!=='undefined'&&!Promise.withResolvers){Promise.withResolvers=function(){var r,j;var p=new Promise(function(a,b){r=a;j=b;});return{promise:p,resolve:r,reject:j};};}"
            + "if(!Array.prototype.at){Array.prototype.at=function(i){var n=this.length>>>0;var k=Math.trunc(i)||0;if(k<0)k+=n;return k<0||k>=n?undefined:this[k];};}"
            + "if(!Object.hasOwn){Object.hasOwn=function(o,p){return Object.prototype.hasOwnProperty.call(o,p);};}"
            + "if(!Object.fromEntries){Object.fromEntries=function(it){var o={};it.forEach(function(kv){o[kv[0]]=kv[1];});return o;};}"
            + "if(!Array.prototype.flat){Array.prototype.flat=function(d){d=d===undefined?1:d;var out=[];for(var i=0;i<this.length;i++){if(Array.isArray(this[i])&&d>0){out=out.concat(this[i].flat(d-1));}else{out.push(this[i]);}}return out;};}"
            + "if(!String.prototype.replaceAll){String.prototype.replaceAll=function(a,b){return this.split(a).join(b);};}"
            + "if(typeof structuredClone!=='function'){structuredClone=function(v){return JSON.parse(JSON.stringify(v));};}"
            + "if(typeof queueMicrotask!=='function'){queueMicrotask=function(f){Promise.resolve().then(f);};}"
            + "})();";


    private Runtime runtime;
    private FrameLayout root;
    private LinearLayout setupView;
    private TextView statusText;
    private TextView workText;
    private TextView logText;
    private ScrollView logScroll;
    private WebView webView;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private boolean busy;
    private ValueCallback<Uri[]> fileCallback;
    private boolean resumed;
    private AlertDialog settingsDialog;
    private Runnable settingsTicker;
    private String pendingUrl;
    private String hostUrl;
    private TextView floatingSettings;
    private boolean probeDone;
    private final java.util.List<String> recentErrors = new java.util.ArrayList<>();
    private boolean bootstrapStarted;
    private int blankCount;
    private TextView loadErrorView;
    private AlertDialog pickerDialog;
    private static MainActivity current;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        killStaleProcesses();
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        runtime = ((DshApp) getApplication()).runtime;
        diag("=== app start, uid=" + android.os.Process.myUid() + " pid=" + android.os.Process.myPid() + " ===");
        if (Build.VERSION.SDK_INT >= 33) {
            try {
                requestPermissions(new String[] { android.Manifest.permission.POST_NOTIFICATIONS }, 1002);
            } catch (Exception ignored) {
            }
        }
        requestStoragePermission();
        buildUi();
        if (webView == null) {
            // WebView 创建失败（锁冲突），已提示用户，直接结束本进程避免崩溃循环
            finishAndRemoveTask();
            System.exit(0);
            return;
        }
        loadHistoryLog();
        ShizukuBridge.init(this);
        DhizukuBridge.init(this);
        LocalBridge.start(this);
        writeShizukuFiles();
        try {
            startForegroundService(new Intent(this, DshService.class));
        } catch (Exception ignored) {
        }
        handleIntent(getIntent());
        probeCompatThenBootstrap();
    }

    private boolean isLandscape() {
        return getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE;
    }

    private void openInBrowser(String url) {
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
            diag("[自适应] 已用系统浏览器打开 " + url);
        } catch (Throwable t) {
            diag("[自适应] 系统浏览器打开失败: " + t);
            showLoadError("无法打开浏览器：" + t);
        }
    }

    private String loadPolyfill() {
        try {
            java.io.File f = new java.io.File("/storage/emulated/0/DEH/polyfill.js");
            if (f.isFile() && f.length() > 0) {
                byte[] buf = new byte[(int) f.length()];
                java.io.FileInputStream in = new java.io.FileInputStream(f);
                int n = in.read(buf);
                in.close();
                if (n > 0) {
                    diag("polyfill: 使用外部 polyfill.js（" + n + " 字节）");
                    return new String(buf, 0, n, "UTF-8");
                }
            }
        } catch (Throwable t) {
            diag("polyfill external read error: " + t);
        }
        return JsCompat.ALL;
    }

    /** 把不兼容的 WebView 信息预填到 GitHub Issue（用户用自己的 GitHub 账号提交）。 */
    private void reportIncompatibleWebView() {
        try {
            String pkg = "";
            String ver = "";
            try {
                android.content.pm.PackageInfo pi = android.webkit.WebView.getCurrentWebViewPackage();
                if (pi != null) {
                    pkg = pi.packageName == null ? "" : pi.packageName;
                    ver = pi.versionName == null ? "" : pi.versionName;
                }
            } catch (Throwable ignored) {
            }
            String profile = JsCompat.PROFILE == null ? "未知（探测未完成）" : JsCompat.PROFILE.toString();
            StringBuilder errs = new StringBuilder();
            synchronized (recentErrors) {
                for (String e : recentErrors) {
                    errs.append("- ").append(e).append("\n");
                }
            }
            if (errs.length() == 0) errs.append("- （暂无错误记录）\n");
            String body = "## WebView 不兼容反馈\n\n"
                    + "- WebView 包名: `" + pkg + "`\n"
                    + "- WebView 版本: `" + ver + "`\n"
                    + "- Android SDK: " + android.os.Build.VERSION.SDK_INT + "\n"
                    + "- 设备: " + android.os.Build.MANUFACTURER + " " + android.os.Build.MODEL + "\n"
                    + "- ABI: " + (android.os.Build.SUPPORTED_ABIS.length > 0 ? android.os.Build.SUPPORTED_ABIS[0] : "?") + "\n"
                    + "- 探测结果: " + profile + "\n"
                    + "- 应用版本: 3.0-stable\n\n"
                    + "### 最近 JS 错误\n" + errs + "\n"
                    + "（提交后维护者会统一适配该 WebView 版本）\n";
            String title = "[WebView 不兼容] " + (pkg.isEmpty() ? "unknown" : pkg) + " " + ver;
            String url = "https://github.com/Coldwind1019/DeepSeek-Harness-Mobile/issues/new"
                    + "?title=" + java.net.URLEncoder.encode(title, "UTF-8")
                    + "&body=" + java.net.URLEncoder.encode(body, "UTF-8")
                    + "&labels=webview-compat";
            diag("[反馈] 打开 GitHub Issue: " + url);
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (Throwable t) {
            diag("[反馈] 打开失败: " + t);
            Toast.makeText(this, "打开浏览器失败：" + t, Toast.LENGTH_LONG).show();
        }
    }

    private void probeCompatThenBootstrap() {
        try {
            WebViewProbe.Result wv = WebViewProbe.detect(this);
            diag("[检测] " + wv);
            probeDone = false;
            webView.setVisibility(View.INVISIBLE);
            webView.loadData("<html><body>probe</body></html>", "text/html", "UTF-8");
            mainHandler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    probeAttempt(0);
                }
            }, 300);
        } catch (Throwable t) {
            diag("probe error: " + t);
            bootstrapSafely();
        }
    }

    private void probeAttempt(final int attempt) {
        if (probeDone) return;
        try {
            webView.evaluateJavascript(JsCompat.PROBE_JS, new ValueCallback<String>() {
                @Override
                public void onReceiveValue(String value) {
                    if (probeDone) return;
                    if (value != null && value.contains("staticBlock")) {
                        try {
                            JsCompat.PROFILE = JsCompat.parseProfile(value);
                            diag("[自适应] 探测完成（第 " + (attempt + 1) + " 次尝试）: " + JsCompat.PROFILE);
                        } catch (Throwable t) {
                            diag("probe parse error: " + t);
                        }
                        probeDone = true;
                        bootstrapSafely();
                        return;
                    }
                    retryProbe(attempt + 1);
                }
            });
        } catch (Throwable t) {
            retryProbe(attempt + 1);
        }
    }

    private void retryProbe(final int attempt) {
        if (probeDone) return;
        if (attempt >= 10) {
            probeDone = true;
            JsCompat.PROFILE = fallbackProfile();
            diag("[自适应] 探测未完成（10 次轮询），改用版本探测: " + JsCompat.PROFILE);
            bootstrapSafely();
            return;
        }
        mainHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                probeAttempt(attempt);
            }
        }, 400);
    }

    private void bootstrapSafely() {
        if (bootstrapStarted) return;
        bootstrapStarted = true;
        bootstrap();
    }

    private JsCompat.Profile fallbackProfile() {
        JsCompat.Profile p = new JsCompat.Profile();
        try {
            android.content.pm.PackageInfo pi = android.webkit.WebView.getCurrentWebViewPackage();
            String pkg = pi == null ? "" : pi.packageName;
            String ver = pi == null || pi.versionName == null ? "" : pi.versionName;
            boolean huawei = pkg.contains("huawei");
            boolean google = pkg.contains("google") || pkg.contains("android.webview");
            int major = 0;
            try {
                major = Integer.parseInt(ver.split("\\.")[0]);
            } catch (Throwable ignored) {
            }
            if (!huawei && google && major >= 119) {
                p.staticBlock = true;
                p.classFields = true;
                p.nullishAssign = true;
                p.withResolvers = true;
                p.hasOwn = true;
                p.arrayAt = true;
                p.replaceAll = true;
                p.structuredClone = true;
                p.queueMicrotask = true;
                p.letConst = true;
                p.classSupport = true;
            } else {
                p.letConst = true;
                p.classSupport = true;
            }
        } catch (Throwable ignored) {
        }
        return p;
    }

    /** 启动时清理同包残留进程，避免 WebView 数据目录锁冲突（多进程会直接崩）。 */
    private void killStaleProcesses() {
        try {
            android.app.ActivityManager am = (android.app.ActivityManager) getSystemService(ACTIVITY_SERVICE);
            if (am == null) return;
            int myPid = android.os.Process.myPid();
            java.util.List<android.app.ActivityManager.RunningAppProcessInfo> procs = am.getRunningAppProcesses();
            if (procs == null) return;
            for (android.app.ActivityManager.RunningAppProcessInfo p : procs) {
                if (p.pid == myPid) continue;
                if (p.processName != null && p.processName.equals(getPackageName())) {
                    diag("[启动] 发现残留进程 pid=" + p.pid + "，清理以避免 WebView 锁冲突");
                    am.killBackgroundProcesses(getPackageName());
                    break;
                }
            }
        } catch (Throwable t) {
            diag("[启动] 清理残留进程失败: " + t);
        }
    }

    private void buildUi() {
        root = new FrameLayout(this);
        setupView = new LinearLayout(this);
        setupView.setOrientation(isLandscape() ? LinearLayout.HORIZONTAL : LinearLayout.VERTICAL);
        setupView.setBackgroundColor(0xFF0F1115);
        int pad = isLandscape() ? dp(14) : dp(18);
        setupView.setPadding(pad, pad, pad, pad);

        LinearLayout info = new LinearLayout(this);
        info.setOrientation(LinearLayout.VERTICAL);
        info.setBackgroundResource(R.drawable.card_bg);
        info.setPadding(dp(18), dp(16), dp(18), dp(16));

        LinearLayout titleRow = new LinearLayout(this);
        titleRow.setOrientation(LinearLayout.HORIZONTAL);
        ImageView icon = new ImageView(this);
        icon.setImageResource(R.mipmap.ic_launcher);
        LinearLayout.LayoutParams iconLp = new LinearLayout.LayoutParams(dp(30), dp(30));
        iconLp.rightMargin = dp(10);
        titleRow.addView(icon, iconLp);

        LinearLayout titleCol = new LinearLayout(this);
        titleCol.setOrientation(LinearLayout.VERTICAL);
        TextView title = new TextView(this);
        title.setText("DeepSeek Harness");
        title.setTextSize(20);
        title.setTextColor(0xFFE8EAED);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        titleCol.addView(title);
        TextView subtitle = new TextView(this);
        subtitle.setText("手机本地运行 · 独立版");
        subtitle.setTextSize(12);
        subtitle.setTextColor(0xFF9AA3B2);
        titleCol.addView(subtitle);
        titleRow.addView(titleCol);
        info.addView(titleRow);

        statusText = new TextView(this);
        statusText.setTextSize(13);
        statusText.setTextColor(0xFF8AB4F8);
        statusText.setPadding(0, dp(12), 0, 0);
        info.addView(statusText);

        workText = new TextView(this);
        workText.setTextSize(11);
        workText.setTextColor(0xFF6B7280);
        workText.setPadding(0, dp(6), 0, 0);
        workText.setText("工作区：" + runtime.workModeLabel() + "\n" + runtime.workRoot.getAbsolutePath());
        info.addView(workText);

        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams btnLp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        btnLp.topMargin = dp(12);
        btnLp.rightMargin = dp(8);

        Button reset = new Button(this);
        reset.setText("重置");
        reset.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                confirmReset();
            }
        });
        buttons.addView(reset, btnLp);

        Button settings = new Button(this);
        settings.setText("设置");
        settings.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showSettings();
            }
        });
        LinearLayout.LayoutParams btnLp2 = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        btnLp2.topMargin = dp(12);
        buttons.addView(settings, btnLp2);
        info.addView(buttons);

        logScroll = new ScrollView(this);
        logScroll.setBackgroundColor(0xFF0B0D11);
        logText = new TextView(this);
        logText.setTextSize(10.5f);
        logText.setTextColor(0xFFB0B6C0);
        logText.setTypeface(android.graphics.Typeface.MONOSPACE);
        logText.setTextIsSelectable(true);
        logText.setMovementMethod(new ScrollingMovementMethod());
        logText.setPadding(dp(10), dp(8), dp(10), dp(8));
        logScroll.addView(logText);

        if (isLandscape()) {
            LinearLayout.LayoutParams infoLp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f);
            infoLp.rightMargin = dp(12);
            setupView.addView(info, infoLp);
            setupView.addView(logScroll, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1.4f));
        } else {
            setupView.addView(info, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
            LinearLayout.LayoutParams logLp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f);
            logLp.topMargin = dp(12);
            setupView.addView(logScroll, logLp);
        }

        root.addView(setupView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        try {
            webView = new WebView(this);
        } catch (Throwable t) {
            // WebView 数据目录锁冲突（旧进程残留）：提示而不是崩溃循环
            diag("[启动] WebView 创建失败（锁冲突）: " + t);
            try {
                new android.app.AlertDialog.Builder(this)
                        .setTitle("应用有残留实例")
                        .setMessage("检测到另一个 DeepSeek Harness 实例仍在运行（WebView 锁冲突）。\n\n请到系统「最近任务」彻底划掉本应用（或重启手机）后再打开。")
                        .setPositiveButton("退出", new android.content.DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(android.content.DialogInterface d, int w) {
                                finishAndRemoveTask();
                                System.exit(0);
                            }
                        })
                        .setCancelable(false)
                        .show();
            } catch (Throwable ignored) {
            }
            return;
        }
        webView.setVisibility(View.GONE);
        root.addView(webView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        setContentView(root);
    }

    private void diag(String message) {
        try {
            File dir = new File("/storage/emulated/0/DEH");
            if (!dir.exists()) dir.mkdirs();
            FileOutputStream out = new FileOutputStream(new File(dir, "diag.log"), true);
            String line = new java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.US).format(new java.util.Date())
                    + " " + message + "\n";
            out.write(line.getBytes("UTF-8"));
            out.close();
        } catch (Throwable ignored) {
        }
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private void bootstrap() {
        if (busy) return;
        if (runtime.isHostRunning() && runtime.lastUrl != null) {
            hostUrl = runtime.lastUrl;
            showWeb(runtime.lastUrl);
            return;
        }
        if (runtime.isInstalled()) {
            startHostWork();
        } else if (runtime.isPrepared()) {
            runInstall();
        } else {
            File backup = new File("/storage/emulated/0/DEH/备份/dsh-restore.tar.gz");
            if (backup.isFile()) {
                runRestore(backup);
            } else {
                runPrepare();
            }
        }
    }

    private void runRestore(final File backup) {
        busy = true;
        status("检测到运行时备份，正在恢复（" + (backup.length() / 1024 / 1024) + " MB）...");
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    runtime.restoreFromBackup(backup, logger);
                    mainHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            busy = false;
                            startHostWork();
                        }
                    });
                } catch (final Exception e) {
                    fail("恢复备份失败", e);
                }
            }
        }, "dsh-restore").start();
    }

    private void runPrepare() {
        busy = true;
        status("正在准备运行环境（首次约 1-3 分钟）...");
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    runtime.prepare(logger);
                    mainHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            busy = false;
                            runInstall();
                        }
                    });
                } catch (final Exception e) {
                    fail("准备运行环境失败", e);
                }
            }
        }, "dsh-prepare").start();
    }

    private void runInstall() {
        busy = true;
        status("正在安装依赖（需要联网，约 5-20 分钟）...");
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    runtime.install(logger);
                    mainHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            busy = false;
                            startHostWork();
                        }
                    });
                } catch (final Exception e) {
                    fail("安装失败", e);
                }
            }
        }, "dsh-install").start();
    }

    private void startHostWork() {
        busy = true;
        hostUrl = null;
        runtime.lastUrl = null;
        status("正在启动 DeepSeek Harness...");
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    runtime.startHost(logger, new Runtime.UrlListener() {
                        @Override
                        public void onUrl(final String url) {
                            mainHandler.post(new Runnable() {
                                @Override
                                public void run() {
                                    showWeb(url);
                                }
                            });
                        }
                    });
                } catch (final Exception e) {
                    fail("启动失败", e);
                }
            }
        }, "dsh-start").start();
    }

    private void showWeb(String url) {
        blankCount = 0;
        if (!resumed) {
            pendingUrl = url;
            return;
        }
        busy = false;
        status("已启动");
        setupView.setVisibility(View.GONE);
        webView.setVisibility(View.VISIBLE);
        configureWebView();
        hostUrl = url;
        runtime.lastUrl = url;
        diag("showWeb url=" + url + " resumed=" + resumed + " w=" + webView.getWidth() + " h=" + webView.getHeight());
        if (JsCompat.PROFILE != null && JsCompat.PROFILE.tooOld()) {
            diag("[自适应] WebView 过于老旧（连 class/let 都不支持），改用系统浏览器打开");
            showLoadError("当前 WebView 过旧，已尝试用系统浏览器打开。可安装最新 WebView 后重开本应用。");
            openInBrowser(url);
            return;
        }
        checkWebViewVersion();
        webView.loadUrl(url);
        applyZoom();
        try {
            webView.onResume();
            webView.requestLayout();
            webView.postInvalidate();
        } catch (Throwable ignored) {
        }
        webView.postDelayed(new Runnable() {
            @Override
            public void run() {
                try {
                    webView.onPause();
                    webView.onResume();
                    webView.postInvalidate();
                } catch (Throwable ignored) {
                }
                diag("render-reset done, w=" + webView.getWidth() + " h=" + webView.getHeight());
            }
        }, 2500);
        webView.postDelayed(new Runnable() {
            @Override
            public void run() {
                checkWebBlank();
            }
        }, 8000);
        webView.postDelayed(new Runnable() {
            @Override
            public void run() {
                checkWebBlank();
            }
        }, 15000);
        try {
            startForegroundService(new Intent(this, DshService.class));
        } catch (Exception ignored) {
        }
        Toast.makeText(this, "DeepSeek Harness 已启动", Toast.LENGTH_SHORT).show();
    }

    private void applyZoom() {
        if (webView == null) return;
        int scale = Prefs.deskScale(this);
        try {
            webView.setInitialScale(scale);
        } catch (Throwable ignored) {
        }
        try {
            webView.evaluateJavascript("document.documentElement.style.zoom='" + scale + "%'", null);
        } catch (Throwable ignored) {
        }
    }

    private void checkWebViewVersion() {
        try {
            android.content.pm.PackageInfo p = WebView.getCurrentWebViewPackage();
            if (p == null) {
                diag("webview: unknown");
                return;
            }
            String ver = p.versionName == null ? "" : p.versionName;
            diag("webview: " + p.packageName + " " + ver);
            String major = ver.split("\\.")[0];
            int maj = 0;
            try {
                maj = Integer.parseInt(major);
            } catch (Throwable ignored) {
            }
            if (maj > 0 && maj < 119) {
                diag("webview 较旧（" + ver + "），由自适应兼容层接管（转译 + polyfill）");
            }
        } catch (Throwable t) {
            diag("webview check error: " + t);
        }
    }

    private void showLoadError(final String message) {
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                diag("LOAD ERROR: " + message);
                if (loadErrorView == null) {
                    loadErrorView = new TextView(MainActivity.this);
                    loadErrorView.setTextSize(12);
                    loadErrorView.setTextColor(0xFFFFD7D7);
                    loadErrorView.setBackgroundColor(0xE61A1414);
                    loadErrorView.setPadding(dp(14), dp(10), dp(14), dp(10));
                    loadErrorView.setOnClickListener(new View.OnClickListener() {
                        @Override
                        public void onClick(View v) {
                            hideLoadError();
                            blankCount = 0;
                            if (hostUrl != null) webView.loadUrl(hostUrl);
                        }
                    });
                    FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT);
                    lp.gravity = android.view.Gravity.TOP;
                    lp.topMargin = dp(90);
                    root.addView(loadErrorView, lp);
                }
                loadErrorView.setText("⚠ 加载异常（点击重试）\n" + message);
                loadErrorView.setVisibility(View.VISIBLE);
            }
        });
    }

    private void hideLoadError() {
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                if (loadErrorView != null) loadErrorView.setVisibility(View.GONE);
            }
        });
    }

    private void configureWebView() {
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setSupportZoom(true);
        settings.setBuiltInZoomControls(true);
        settings.setDisplayZoomControls(false);
        settings.setUseWideViewPort(true);
        settings.setLoadWithOverviewMode(true);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        webView.setInitialScale(Prefs.deskScale(this));

        CookieManager cookieManager = CookieManager.getInstance();
        cookieManager.setAcceptCookie(true);
        cookieManager.setAcceptThirdPartyCookies(webView, true);

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                diag("pageFinished " + url);
                if ("about:blank".equals(url) && !probeDone) {
                    try {
                        webView.evaluateJavascript(JsCompat.PROBE_JS, new ValueCallback<String>() {
                            @Override
                            public void onReceiveValue(String value) {
                                try {
                                    JsCompat.PROFILE = JsCompat.parseProfile(value);
                                    diag("[自适应] 探测结果: " + JsCompat.PROFILE);
                                } catch (Throwable t) {
                                    diag("probe parse error: " + t);
                                }
                                probeDone = true;
                                bootstrapSafely();
                            }
                        });
                    } catch (Throwable t) {
                        diag("probe evaluate error: " + t);
                        probeDone = true;
                        bootstrapSafely();
                    }
                    return;
                }
                try {
                    webView.evaluateJavascript(AutoFix.AGENT, null);
                    diag("autofix agent injected");
                } catch (Throwable t) {
                    diag("autofix agent error: " + t);
                }
            }

            @Override
            public void onPageStarted(WebView view, String url, android.graphics.Bitmap favicon) {
                super.onPageStarted(view, url, favicon);
                try {
                    webView.evaluateJavascript(loadPolyfill(), null);
                    diag("polyfill via onPageStarted " + url);
                } catch (Throwable t) {
                    diag("polyfill onPageStarted error: " + t);
                }
            }

            @Override
            public android.webkit.WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                try {
                    String url = request.getUrl() == null ? "" : request.getUrl().toString();
                    boolean isIndex = url.equals("http://127.0.0.1:38600/")
                            || url.startsWith("http://127.0.0.1:38600/?");
                    if (isIndex) {
                        java.net.HttpURLConnection conn = (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
                        conn.setConnectTimeout(3000);
                        conn.setReadTimeout(6000);
                        try {
                            if (conn.getResponseCode() == 200) {
                                java.io.InputStream in = conn.getInputStream();
                                java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
                                byte[] buffer = new byte[8192];
                                int n;
                                while ((n = in.read(buffer)) >= 0) bos.write(buffer, 0, n);
                                in.close();
                                String html = new String(bos.toByteArray(), "UTF-8");
                                int head = html.indexOf("<head>");
                                if (head >= 0) {
                                    html = html.substring(0, head + 6) + "<script>" + POLYFILL_JS + "</script>" + html.substring(head + 6);
                                } else {
                                    html = "<script>" + POLYFILL_JS + "</script>" + html;
                                }
                                diag("polyfill injected for " + url);
                                return new android.webkit.WebResourceResponse("text/html", "UTF-8",
                                        new java.io.ByteArrayInputStream(html.getBytes("UTF-8")));
                            }
                        } finally {
                            conn.disconnect();
                        }
                    }
                } catch (Throwable t) {
                    diag("polyfill intercept error: " + t);
                }
                return null;
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request, android.webkit.WebResourceError error) {
                if (request != null && request.isForMainFrame()) {
                    diag("main frame error: " + error.getDescription() + " url=" + (request.getUrl() == null ? "" : request.getUrl().toString()));
                }
            }

            @Override
            public boolean onRenderProcessGone(WebView view, android.webkit.RenderProcessGoneDetail detail) {
                root.removeView(view);
                view.destroy();
                webView = new WebView(MainActivity.this);
                root.addView(webView, new FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
                configureWebView();
                if (hostUrl != null) webView.loadUrl(hostUrl);
                return true;
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                String host = request.getUrl().getHost();
                if (host != null && (host.equals("127.0.0.1") || host.equals("localhost"))) {
                    return false;
                }
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, request.getUrl()));
                    return true;
                } catch (Exception e) {
                    return false;
                }
            }
        });

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onConsoleMessage(android.webkit.ConsoleMessage msg) {
                String text = msg == null ? "" : msg.message();
                diag("console[" + (msg == null ? "" : msg.sourceId() + ":" + msg.lineNumber()) + "] " + text);
                String kind = AutoFix.classify(text);
                if (text != null && !text.isEmpty() && (text.contains("not a function") || text.contains("not defined")
                        || text.contains("SyntaxError") || text.contains("Unexpected token"))) {
                    synchronized (recentErrors) {
                        recentErrors.add(text.length() > 160 ? text.substring(0, 160) : text);
                        while (recentErrors.size() > 8) recentErrors.remove(0);
                    }
                }
                if ("api".equals(kind)) {
                    String api = AutoFix.missingApi(text);
                    diag("autofix: 识别到缺失 API " + api + "，自动注入兼容层");
                    if (api != null && !api.isEmpty() && !JsCompat.isBuiltInApi(api)) {
                        diag("autofix: 内置兼容层未覆盖 " + api + "（可在设置里反馈不兼容 WebView）");
                    }
                    try {
                        webView.evaluateJavascript(loadPolyfill(), null);
                        webView.evaluateJavascript(AutoFix.AGENT, null);
                    } catch (Throwable t) {
                        diag("autofix inject error: " + t);
                    }
                } else if ("syntax".equals(kind)) {
                    diag("autofix: 识别到语法错误（前端已转译；不重载，避免循环）");
                } else if ("runtime".equals(kind)) {
                    diag("autofix: 页面运行时错误，注入兼容层兜底");
                    try {
                        webView.evaluateJavascript(loadPolyfill(), null);
                    } catch (Throwable ignored) {
                    }
                }
                return false;
            }

            @Override
            public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback, FileChooserParams params) {
                if (fileCallback != null) {
                    fileCallback.onReceiveValue(null);
                }
                fileCallback = callback;
                try {
                    Intent intent = null;
                    try {
                        intent = params.createIntent();
                    } catch (Exception ignored) {
                    }
                    if (intent == null) {
                        intent = new Intent(Intent.ACTION_GET_CONTENT);
                        intent.addCategory(Intent.CATEGORY_OPENABLE);
                        intent.setType("*/*");
                    }
                    startActivityForResult(intent, REQUEST_FILE);
                } catch (Exception e) {
                    fileCallback = null;
                    Toast.makeText(MainActivity.this, "无法打开文件选择器：" + e.getMessage(), Toast.LENGTH_LONG).show();
                }
                return true;
            }
        });
    }

    private void checkWebBlank() {
        if (webView == null || webView.getVisibility() != View.VISIBLE) return;
        String current = webView.getUrl();
        if (current == null || current.isEmpty()) {
            if (hostUrl != null) webView.loadUrl(hostUrl);
            return;
        }
        try {
            webView.evaluateJavascript(
                    "(function(){try{var t=(document.body&&document.body.innerText)?document.body.innerText.length:-1;return document.readyState+'|'+t}catch(e){return 'error|-1'}})()",
                    new ValueCallback<String>() {
                        @Override
                        public void onReceiveValue(String value) {
                            String raw = value == null ? "" : value.trim();
                            if (raw.startsWith("\"") && raw.endsWith("\"")) raw = raw.substring(1, raw.length() - 1);
                            String ready = "unknown";
                            int length = -1;
                            int bar = raw.indexOf('|');
                            if (bar >= 0) {
                                ready = raw.substring(0, bar);
                                try {
                                    length = Integer.parseInt(raw.substring(bar + 1).trim());
                                } catch (Throwable ignored) {
                                }
                            }
                            int w = webView.getWidth();
                            int h = webView.getHeight();
                            int ch = webView.getContentHeight();
                            diag("checkBlank ready=" + ready + " text=" + length + " w=" + w + " h=" + h + " contentH=" + ch + " count=" + blankCount);
                            // 页面还没加载完 → 继续等待，不要 reload
                            if (!"complete".equals(ready)) {
                                diag("not complete, wait");
                                return;
                            }
                            boolean blank = (length >= 0 && length < 10) || (w <= 0 || h <= 0);
                            if (blank) {
                                blankCount++;
                                diag("blank detected, count=" + blankCount);
                                if (blankCount <= 1 && hostUrl != null) {
                                    diag("reload triggered (once)");
                                    webView.loadUrl(hostUrl);
                                } else {
                                    showLoadError("页面空白。URL: " + hostUrl);
                                }
                            } else {
                                blankCount = 0;
                                hideLoadError();
                            }
                        }
                    });
        } catch (Throwable ignored) {
        }
    }

    private void status(final String text) {
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                if (statusText != null) statusText.setText(text);
            }
        });
    }

    private final Runtime.LogListener logger = new Runtime.LogListener() {
        @Override
        public void onLog(final String line) {
            appendLog(line);
            mainHandler.post(new Runnable() {
                @Override
                public void run() {
                    if (logText == null) return;
                    logText.append(line + "\n");
                    if (logText.getLineCount() > 500) {
                        CharSequence all = logText.getText();
                        int cut = all.length() / 2;
                        logText.setText(all.subSequence(cut, all.length()));
                    }
                    if (logScroll != null) {
                        logScroll.post(new Runnable() {
                            @Override
                            public void run() {
                                logScroll.fullScroll(View.FOCUS_DOWN);
                            }
                        });
                    }
                }
            });
        }
    };

    private void appendLog(String line) {
        try {
            File dir = new File(getFilesDir(), "dsh");
            if (!dir.exists()) dir.mkdirs();
            File file = new File(dir, "app.log");
            if (file.length() > 262144) file.delete();
            FileOutputStream out = new FileOutputStream(file, true);
            out.write((line + "\n").getBytes("UTF-8"));
            out.close();
        } catch (Exception ignored) {
        }
    }

    private void loadHistoryLog() {
        try {
            File file = new File(new File(getFilesDir(), "dsh"), "app.log");
            if (!file.isFile()) return;
            java.io.BufferedReader reader = new java.io.BufferedReader(new java.io.InputStreamReader(new java.io.FileInputStream(file), "UTF-8"));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) sb.append(line).append("\n");
            reader.close();
            logText.setText(sb.toString());
            logScroll.post(new Runnable() {
                @Override
                public void run() {
                    logScroll.fullScroll(View.FOCUS_DOWN);
                }
            });
        } catch (Exception ignored) {
        }
    }

    private void fail(String prefix, Exception e) {
        busy = false;
        final String message = prefix + "：" + (e.getMessage() == null ? e.toString() : e.getMessage());
        status(message);
        logger.onLog("[ERROR] " + message);
    }


    private String currentDshVersion() {
        try {
            java.io.File pkg = new java.io.File(runtime.rootfs, "usr/local/node-v24.21.0-linux-arm64/lib/node_modules/@deepseek-ai/dsh/package.json");
            if (!pkg.isFile()) return "未安装";
            byte[] data = new byte[(int) pkg.length()];
            java.io.FileInputStream in = new java.io.FileInputStream(pkg);
            int n = in.read(data);
            in.close();
            org.json.JSONObject json = new org.json.JSONObject(new String(data, 0, n, "UTF-8"));
            return json.optString("version", "?");
        } catch (Throwable t) {
            return "?";
        }
    }

    private void showDshVersionPicker() {
        final String current = currentDshVersion();
        Toast.makeText(this, "获取版本列表…", Toast.LENGTH_SHORT).show();
        new Thread(new Runnable() {
            @Override
            public void run() {
                final java.util.List<String> versions = runtime.listDshVersions(new Runtime.LogListener() {
                    @Override
                    public void onLog(String line) {
                        diag(line);
                    }
                });
                mainHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        if (versions.isEmpty()) {
                            showLoadError("获取 DSH 版本列表失败（检查网络/npm）");
                            return;
                        }
                        final String[] items = new String[versions.size()];
                        int checked = -1;
                        for (int i = 0; i < versions.size(); i++) {
                            items[i] = versions.get(i) + (versions.get(i).equals(current) ? "（当前）" : "");
                            if (versions.get(i).equals(current)) checked = i;
                        }
                        new android.app.AlertDialog.Builder(MainActivity.this)
                                .setTitle("选择 DSH 版本（当前 " + current + "）")
                                .setSingleChoiceItems(items, checked, new android.content.DialogInterface.OnClickListener() {
                                    @Override
                                    public void onClick(android.content.DialogInterface dialog, final int which) {
                                        dialog.dismiss();
                                        final String version = versions.get(which);
                                        new android.app.AlertDialog.Builder(MainActivity.this)
                                                .setTitle("切换到 " + version + "？")
                                                .setMessage("将安装该版本并清空兼容前端；重启 App 后自动重新转译前端。")
                                                .setPositiveButton("切换", new android.content.DialogInterface.OnClickListener() {
                                                    @Override
                                                    public void onClick(android.content.DialogInterface dialog2, int w) {
                                                        final android.app.AlertDialog progress = new android.app.AlertDialog.Builder(MainActivity.this)
                                                                .setTitle("正在安装 DSH " + version)
                                                                .setMessage("预检 → 备份 → 安装 → 校验…\n（可能需要几分钟）")
                                                                .setCancelable(false)
                                                                .create();
                                                        progress.show();
                                                        new Thread(new Runnable() {
                                                            @Override
                                                            public void run() {
                                                                final StringBuilder logBuf = new StringBuilder();
                                                                final boolean ok = runtime.installDshVersion(version, new Runtime.LogListener() {
                                                                    @Override
                                                                    public void onLog(String line) {
                                                                        diag(line);
                                                                        logBuf.append(line).append("\n");
                                                                    }
                                                                });
                                                                mainHandler.post(new Runnable() {
                                                                    @Override
                                                                    public void run() {
                                                                        progress.dismiss();
                                                                        new android.app.AlertDialog.Builder(MainActivity.this)
                                                                                .setTitle(ok ? "切换成功" : "切换失败（已回滚）")
                                                                                .setMessage(ok
                                                                                        ? "已安装 " + version + "。重启 App 生效；首次启动会自动重新转译兼容前端。"
                                                                                        : "安装未通过校验，已自动回滚到原版本。可重试或检查网络/磁盘。")
                                                                                .setPositiveButton("知道了", null)
                                                                                .show();
                                                                    }
                                                                });
                                                            }
                                                        }).start();
                                                    }
                                                })
                                                .setNegativeButton("取消", null)
                                                .show();
                                    }
                                })
                                .setNegativeButton("关闭", null)
                                .show();
                    }
                });
            }
        }).start();
    }

    private String readTextFile(String path) {
        try {
            java.io.File f = new java.io.File(path);
            if (!f.isFile()) return "";
            byte[] data = new byte[(int) f.length()];
            java.io.FileInputStream in = new java.io.FileInputStream(f);
            int n = in.read(data);
            in.close();
            return new String(data, 0, n, "UTF-8");
        } catch (Throwable t) {
            return "";
        }
    }

    private void showSessionCleanup() {
        Toast.makeText(this, "正在扫描会话…", Toast.LENGTH_SHORT).show();
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    runtime.runProot("command -v zstd >/dev/null 2>&1 || (apt-get install -y -qq zstd) >/dev/null 2>&1; node /storage/emulated/0/DEH/session-titles.mjs >/dev/null 2>&1", null);
                } catch (Throwable t) {
                    diag("[会话] 扫描失败: " + t);
                }
                final String json = readTextFile("/storage/emulated/0/DEH/session-titles.json");
                mainHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        showSessionList(json);
                    }
                });
            }
        }).start();
    }

    private void showSessionList(String json) {
        try {
            org.json.JSONArray arr = json == null || json.trim().isEmpty()
                    ? new org.json.JSONArray()
                    : new org.json.JSONArray(json);
            if (arr.length() == 0) {
                showLoadError("没有扫描到会话（可能 zstd 未安装或会话目录为空）");
                return;
            }
            final java.util.List<String> labels = new java.util.ArrayList<>();
            final java.util.List<String> paths = new java.util.ArrayList<>();
            for (int i = 0; i < arr.length(); i++) {
                org.json.JSONObject o = arr.getJSONObject(i);
                String title = o.optString("title", "");
                String ws = o.optString("ws", "");
                String id = o.optString("id", "");
                long created = o.optLong("createdAt", 0);
                long size = o.optLong("size", 0);
                if (title.isEmpty()) {
                    String cwd = o.optString("cwd", "");
                    int slash = cwd.lastIndexOf('/');
                    String base = slash >= 0 ? cwd.substring(slash + 1) : cwd;
                    title = "（空会话" + (base.isEmpty() ? "" : " · " + base) + "）";
                }
                String time = "";
                if (created > 0) {
                    java.text.SimpleDateFormat f = new java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.getDefault());
                    time = f.format(new java.util.Date(created));
                }
                labels.add(title + "\n" + time + " · " + (size / 1024) + "KB · " + ws);
                paths.add(new java.io.File(runtime.rootfs, "root/.dsh/sessions/" + ws + "/" + id).getAbsolutePath());
            }
            final boolean[] checked = new boolean[labels.size()];
            new android.app.AlertDialog.Builder(this)
                    .setTitle("会话清理（共 " + labels.size() + " 个，勾选后彻底删除）")
                    .setMultiChoiceItems(labels.toArray(new String[0]), checked, new android.content.DialogInterface.OnMultiChoiceClickListener() {
                        @Override
                        public void onClick(android.content.DialogInterface d, int which, boolean isChecked) {
                            checked[which] = isChecked;
                        }
                    })
                    .setPositiveButton("删除选中", new android.content.DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(android.content.DialogInterface d, int w) {
                            int removed = 0;
                            for (int i = 0; i < checked.length; i++) {
                                if (!checked[i]) continue;
                                if (deleteTree(new java.io.File(paths.get(i)))) removed++;
                            }
                            diag("[会话] 已删除 " + removed + " 个会话");
                            Toast.makeText(MainActivity.this, "已删除 " + removed + " 个会话（重启 App 生效）", Toast.LENGTH_LONG).show();
                        }
                    })
                    .setNegativeButton("取消", null)
                    .show();
        } catch (Throwable t) {
            showLoadError("会话列表解析失败: " + t);
        }
    }

    private boolean deleteTree(java.io.File f) {
        if (f == null) return false;
        if (f.isDirectory()) {
            java.io.File[] children = f.listFiles();
            if (children != null) {
                for (java.io.File c : children) deleteTree(c);
            }
        }
        return f.delete();
    }

    private void showOtgAdb() {
        Toast.makeText(this, "扫描 OTG ADB 设备…", Toast.LENGTH_SHORT).show();
        new Thread(new Runnable() {
            @Override
            public void run() {
                StringBuilder sb = new StringBuilder();
                try {
                    final android.hardware.usb.UsbManager usb = (android.hardware.usb.UsbManager) getSystemService(USB_SERVICE);
                    java.util.List<android.hardware.usb.UsbDevice> devices = com.deh.mobile.adb.UsbHost.findAdbDevices(MainActivity.this);
                    if (devices.isEmpty()) {
                        sb.append("未发现 ADB USB 设备。\n\n请确认：\n1. OTG 线已连接对方手机\n2. 对方手机已开启「开发者选项 → USB 调试」\n3. 对方手机弹窗已点「允许」\n\n当前 USB 设备：");
                        for (android.hardware.usb.UsbDevice d : usb.getDeviceList().values()) {
                            sb.append("\n· ").append(d.getDeviceName()).append(" VID=").append(String.format("%04x", d.getVendorId())).append(" PID=").append(String.format("%04x", d.getProductId()));
                        }
                    } else {
                        for (android.hardware.usb.UsbDevice d : devices) {
                            sb.append("设备: ").append(d.getDeviceName()).append(" VID=").append(String.format("%04x", d.getVendorId())).append(" PID=").append(String.format("%04x", d.getProductId())).append("\n");
                            if (!com.deh.mobile.adb.UsbHost.hasPermission(MainActivity.this, d)) {
                                OtgManager.requestPermission(MainActivity.this, d);
                                sb.append("→ 已请求 USB 授权，请点「确定」后再次点击本按钮。\n\n");
                                continue;
                            }
                            try {
                                sb.append("→ 连接中…\n");
                                com.deh.mobile.adb.AdbManager.get().init(MainActivity.this);
                                com.deh.mobile.adb.DeviceTarget target = com.deh.mobile.adb.AdbManager.get().connectUsb(d);
                                sb.append("→ 已连接: ").append(target.name()).append(" (").append(target.serial()).append(")\n");
                                try {
                                    String model = target.getProperty("ro.product.model");
                                    sb.append("型号: ").append(model).append("\n");
                                } catch (Throwable t) {
                                    sb.append("属性读取失败: ").append(t).append("\n");
                                }
                            } catch (Throwable t) {
                                sb.append("→ 连接失败: ").append(t).append("\n");
                            }
                        }
                    }
                } catch (Throwable t) {
                    sb.append("扫描失败: ").append(t);
                }
                final String text = sb.toString();
                diag("[OTG-ADB] " + text.replace("\n", " | "));
                mainHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        new android.app.AlertDialog.Builder(MainActivity.this)
                                .setTitle("OTG ADB（调试其他手机）")
                                .setMessage(text)
                                .setPositiveButton("关闭", null)
                                .show();
                    }
                });
            }
        }).start();
    }

    private long sizeOf(java.io.File f) {
        if (f == null) return 0;
        if (f.isFile()) return f.length();
        long total = 0;
        java.io.File[] children = f.listFiles();
        if (children != null) {
            for (java.io.File c : children) total += sizeOf(c);
        }
        return total;
    }

    private String fmtSize(long bytes) {
        if (bytes >= 1024L * 1024 * 1024) return String.format(java.util.Locale.US, "%.2f GB", bytes / 1073741824.0);
        if (bytes >= 1024L * 1024) return String.format(java.util.Locale.US, "%.1f MB", bytes / 1048576.0);
        return (bytes / 1024) + " KB";
    }

    private void showStorageAnalysis() {
        Toast.makeText(this, "统计中…", Toast.LENGTH_SHORT).show();
        new Thread(new Runnable() {
            @Override
            public void run() {
                final StringBuilder sb = new StringBuilder();
                final java.util.List<String[]> dirs = new java.util.ArrayList<>();
                try {
                    java.io.File base = runtime.base;
                    sb.append("数据目录: ").append(base.getAbsolutePath()).append("\n\n");
                    java.io.File[] children = base.listFiles();
                    if (children != null) {
                        java.util.List<java.io.File> list = new java.util.ArrayList<>();
                        for (java.io.File c : children) list.add(c);
                        list.sort((a, b) -> Long.compare(sizeOf(b), sizeOf(a)));
                        for (java.io.File c : list) {
                            long size = sizeOf(c);
                            if (size < 1024 * 1024) continue;
                            sb.append(c.getName()).append(": ").append(fmtSize(size)).append("\n");
                            dirs.add(new String[]{c.getAbsolutePath(), c.getName()});
                        }
                    }
                } catch (Throwable t) {
                    sb.append("统计失败: ").append(t);
                }
                mainHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        new android.app.AlertDialog.Builder(MainActivity.this)
                                .setTitle("存储分析（DSH 数据）")
                                .setMessage(sb.toString())
                                .setPositiveButton("清理缓存类", new android.content.DialogInterface.OnClickListener() {
                                    @Override
                                    public void onClick(android.content.DialogInterface d, int w) {
                                        new Thread(new Runnable() {
                                            @Override
                                            public void run() {
                                                long freed = 0;
                                                for (String[] item : new String[][]{
                                                        {"tmp"}, {"webpatch"}, {"bin"}
                                                }) {
                                                    java.io.File f = new java.io.File(runtime.base, item[0]);
                                                    if (f.exists()) {
                                                        long before = sizeOf(f);
                                                        deleteTree(f);
                                                        freed += before;
                                                    }
                                                }
                                                final long total = freed;
                                                mainHandler.post(new Runnable() {
                                                    @Override
                                                    public void run() {
                                                        Toast.makeText(MainActivity.this, "已清理 " + fmtSize(total), Toast.LENGTH_LONG).show();
                                                    }
                                                });
                                            }
                                        }).start();
                                    }
                                })
                                .setNegativeButton("关闭", null)
                                .show();
                    }
                });
            }
        }).start();
    }

    private void showSettings() {
        if (settingsDialog != null && settingsDialog.isShowing()) {
            settingsDialog.dismiss();
        }
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(10), dp(20), dp(6));

        final TextView statusView = new TextView(this);
        statusView.setTextSize(13);
        statusView.setTextColor(0xFF8AB4F8);
        statusView.setPadding(0, dp(4), 0, dp(10));
        statusView.setText("Shizuku：" + shizukuLabel());
        box.addView(statusView);


        addSettingsButton(box, "下载兼容前端（WebView 适配）", new Runnable() {
            @Override
            public void run() {
                Toast.makeText(MainActivity.this, "开始下载兼容前端…", Toast.LENGTH_SHORT).show();
                new Thread(new Runnable() {
                    @Override
                    public void run() {
                        runtime.downloadWebpatchNow(new Runtime.LogListener() {
                            @Override
                            public void onLog(String line) {
                                diag(line);
                            }
                        });
                        mainHandler.post(new Runnable() {
                            @Override
                            public void run() {
                                Toast.makeText(MainActivity.this, "兼容前端已更新，重启 App 生效", Toast.LENGTH_LONG).show();
                            }
                        });
                    }
                }).start();
            }
        });
        addSettingsButton(box, "存储分析（DSH 数据）", new Runnable() {
            @Override
            public void run() {
                showStorageAnalysis();
            }
        });
        addSettingsButton(box, "会话清理（删除 DSH 会话）", new Runnable() {
            @Override
            public void run() {
                showSessionCleanup();
            }
        });
        addSettingsButton(box, "清除 WebView 缓存（插件更新后必点）", new Runnable() {
            @Override
            public void run() {
                try {
                    webView.clearCache(true);
                    webView.clearFormData();
                    diag("[缓存] WebView 缓存已清除");
                    Toast.makeText(MainActivity.this, "缓存已清除，请重启 App 或重新打开页面", Toast.LENGTH_LONG).show();
                } catch (Throwable t) {
                    Toast.makeText(MainActivity.this, "清除失败: " + t, Toast.LENGTH_LONG).show();
                }
            }
        });
        addSettingsButton(box, "OTG ADB（调试其他手机）", new Runnable() {
            @Override
            public void run() {
                showOtgAdb();
            }
        });
        addSettingsButton(box, "OTG 设备：" + OtgManager.summary(this).split("\n")[0] + "（点击查看）", new Runnable() {
            @Override
            public void run() {
                new android.app.AlertDialog.Builder(MainActivity.this)
                        .setTitle("OTG / USB 设备")
                        .setMessage(OtgManager.summary(MainActivity.this))
                        .setPositiveButton("刷新", new android.content.DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(android.content.DialogInterface d, int w) {
                                Toast.makeText(MainActivity.this, OtgManager.summary(MainActivity.this), Toast.LENGTH_LONG).show();
                            }
                        })
                        .setNegativeButton("关闭", null)
                        .show();
            }
        });
        addSettingsButton(box, "反馈不兼容 WebView（GitHub）", new Runnable() {
            @Override
            public void run() {
                reportIncompatibleWebView();
            }
        });
        addSettingsButton(box, "工作区：" + runtime.workModeLabel() + "（点击切换）", new Runnable() {
            @Override
            public void run() {
                toggleWorkRoot();
            }
        });
        addSettingsButton(box, "Shizuku 权限（点击授权）", new Runnable() {
            @Override
            public void run() {
                handleShizukuAction();
            }
        });
        addSettingsButton(box, "Dhizuku 权限：" + DhizukuBridge.statusLabel() + "（点击授权）", new Runnable() {
            @Override
            public void run() {
                DhizukuBridge.requestPermission(MainActivity.this);
            }
        });
        addSettingsButton(box, "DSH 版本：" + currentDshVersion() + "（点击选择）", new Runnable() {
            @Override
            public void run() {
                showDshVersionPicker();
            }
        });
        LinearLayout zoomRow = new LinearLayout(this);
        zoomRow.setOrientation(LinearLayout.VERTICAL);
        zoomRow.setPadding(0, dp(8), 0, dp(8));
        final TextView zoomLabel = new TextView(this);
        zoomLabel.setTextSize(13);
        zoomLabel.setTextColor(0xFFE8EAED);
        zoomLabel.setText("桌面缩放：" + Prefs.deskScale(this) + "%");
        zoomRow.addView(zoomLabel);
        android.widget.SeekBar seekBar = new android.widget.SeekBar(this);
        seekBar.setMax(100);
        seekBar.setProgress(Prefs.deskScale(this) - 50);
        seekBar.setOnSeekBarChangeListener(new android.widget.SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(android.widget.SeekBar bar, int progress, boolean fromUser) {
                int scale = 50 + progress;
                zoomLabel.setText("桌面缩放：" + scale + "%");
                Prefs.setDeskScale(MainActivity.this, scale);
                applyZoom();
            }

            @Override
            public void onStartTrackingTouch(android.widget.SeekBar bar) {
            }

            @Override
            public void onStopTrackingTouch(android.widget.SeekBar bar) {
            }
        });
        zoomRow.addView(seekBar, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        box.addView(zoomRow);
        addSettingsButton(box, "重置运行时", new Runnable() {
            @Override
            public void run() {
                confirmReset();
            }
        });
        addSettingsButton(box, "退出", new Runnable() {
            @Override
            public void run() {
                runtime.stopHost();
                finish();
            }
        });

        settingsDialog = new AlertDialog.Builder(this)
                .setTitle("设置")
                .setView(box)
                .setNegativeButton("关闭", null)
                .setOnDismissListener(new DialogInterface.OnDismissListener() {
                    @Override
                    public void onDismiss(DialogInterface dialog) {
                        stopSettingsTicker();
                    }
                })
                .show();

        stopSettingsTicker();
        lastShizukuLabel = "";
        settingsTicker = new Runnable() {
            @Override
            public void run() {
                if (settingsDialog != null && settingsDialog.isShowing()) {
                    String label = shizukuLabel();
                    if (!label.equals(lastShizukuLabel)) {
                        lastShizukuLabel = label;
                        statusView.setText("Shizuku：" + label);
                    }
                    mainHandler.postDelayed(this, 200);
                }
            }
        };
        mainHandler.postDelayed(settingsTicker, 100);
    }

    public static void pickOpenForBridge(final String path) {
        final MainActivity activity = current;
        if (activity == null) return;
        activity.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                activity.pickOpen(path);
            }
        });
    }

    public void pickOpen(final String path) {
        try {
            final File file = new File(path);
            final Uri contentUri = SimpleFileProvider.uriFor(file);
            final Uri fileUri = Uri.fromFile(file);
            final PackageManager pm = getPackageManager();

            LinearLayout box = new LinearLayout(this);
            box.setOrientation(LinearLayout.VERTICAL);
            box.setPadding(dp(18), dp(8), dp(18), dp(4));

            // 标题行：打开方式… + 类型筛选
            LinearLayout titleRow = new LinearLayout(this);
            titleRow.setOrientation(LinearLayout.HORIZONTAL);
            titleRow.setGravity(android.view.Gravity.CENTER_VERTICAL);
            TextView title = new TextView(this);
            title.setText("打开方式…");
            title.setTextSize(20);
            title.setTypeface(null, android.graphics.Typeface.BOLD);
            title.setTextColor(0xFFE8EAED);
            titleRow.addView(title, new LinearLayout.LayoutParams(0,
                    LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

            final TextView typeButton = new TextView(this);
            typeButton.setText("全部 ▾");
            typeButton.setTextSize(13);
            typeButton.setTextColor(0xFF8AB4F8);
            typeButton.setPadding(dp(10), dp(6), dp(10), dp(6));
            titleRow.addView(typeButton);
            box.addView(titleRow);

            final GridView grid = new GridView(this);
            grid.setNumColumns(4);
            grid.setPadding(dp(2), dp(10), dp(2), dp(2));
            box.addView(grid, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, dp(400)));

            final List<ResolveInfo> appsList = new ArrayList<>();
            final BaseAdapter adapter = new BaseAdapter() {
                @Override
                public int getCount() {
                    return appsList.size();
                }

                @Override
                public Object getItem(int position) {
                    return appsList.get(position);
                }

                @Override
                public long getItemId(int position) {
                    return position;
                }

                @Override
                public View getView(int position, View convertView, android.view.ViewGroup parent) {
                    LinearLayout item = new LinearLayout(MainActivity.this);
                    item.setOrientation(LinearLayout.VERTICAL);
                    item.setGravity(android.view.Gravity.CENTER);
                    item.setPadding(dp(4), dp(10), dp(4), dp(10));
                    ImageView icon = new ImageView(MainActivity.this);
                    try {
                        icon.setImageDrawable(appsList.get(position).loadIcon(pm));
                    } catch (Throwable ignored) {
                    }
                    item.addView(icon, new LinearLayout.LayoutParams(dp(52), dp(52)));
                    TextView label = new TextView(MainActivity.this);
                    try {
                        label.setText(appsList.get(position).loadLabel(pm));
                    } catch (Throwable ignored) {
                    }
                    label.setTextSize(11);
                    label.setTextColor(0xFFE8EAED);
                    label.setGravity(android.view.Gravity.CENTER);
                    label.setMaxLines(2);
                    label.setPadding(0, dp(5), 0, 0);
                    item.addView(label);
                    return item;
                }
            };
            grid.setAdapter(adapter);

            final String[] categoryNames = new String[] { "全部", "系统建议", "文本", "图片", "视频", "音频" };
            final String[] categoryMimes = new String[] { null, "", "text/*", "image/*", "video/*", "audio/*" };
            final int[] currentCategory = new int[] { 0 };

            final Runnable reload = new Runnable() {
                @Override
                public void run() {
                    appsList.clear();
                    appsList.addAll(loadAppsForCategory(fileUri, contentUri, categoryMimes[currentCategory[0]], pm));
                    adapter.notifyDataSetChanged();
                    typeButton.setText(categoryNames[currentCategory[0]] + " ▾");
                }
            };

            typeButton.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    PopupMenu popup = new PopupMenu(MainActivity.this, typeButton);
                    for (int i = 0; i < categoryNames.length; i++) {
                        popup.getMenu().add(0, i, i, categoryNames[i]);
                    }
                    popup.setOnMenuItemClickListener(new PopupMenu.OnMenuItemClickListener() {
                        @Override
                        public boolean onMenuItemClick(android.view.MenuItem item) {
                            currentCategory[0] = item.getItemId();
                            reload.run();
                            return true;
                        }
                    });
                    popup.show();
                }
            });

            grid.setOnItemLongClickListener(new AdapterView.OnItemLongClickListener() {
                @Override
                public boolean onItemLongClick(AdapterView<?> parent, View view, int position, long id) {
                    ResolveInfo ri = appsList.get(position);
                    String mime = guessMimeOf(path);
                    String flat = ri.activityInfo.packageName + "/" + ri.activityInfo.name;
                    Prefs.setDefaultOpenApp(MainActivity.this, mime, flat);
                    Toast.makeText(MainActivity.this, "已设为该类型文件的默认打开方式", Toast.LENGTH_SHORT).show();
                    if (pickerDialog != null) pickerDialog.dismiss();
                    return true;
                }
            });

            grid.setOnItemClickListener(new AdapterView.OnItemClickListener() {
                @Override
                public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                    try {
                        ResolveInfo ri = appsList.get(position);
                        Intent intent = new Intent(Intent.ACTION_VIEW);
                        intent.setDataAndType(fileUri, guessMimeOf(path));
                        intent.setComponent(new ComponentName(ri.activityInfo.packageName, ri.activityInfo.name));
                        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_GRANT_READ_URI_PERMISSION);
                        startActivity(intent);
                        diag("pick open " + ri.activityInfo.packageName);
                    } catch (Throwable t) {
                        diag("pick open error: " + t);
                        Toast.makeText(MainActivity.this, "打开失败：" + t, Toast.LENGTH_SHORT).show();
                    }
                    if (pickerDialog != null) pickerDialog.dismiss();
                }
            });

            reload.run();

            pickerDialog = new AlertDialog.Builder(this)
                    .setView(box)
                    .setNegativeButton("关闭", null)
                    .show();
            diag("pickOpen entries=" + appsList.size());
        } catch (Throwable t) {
            diag("pickOpen error: " + t);
        }
    }

    private List<ResolveInfo> loadAppsForCategory(Uri fileUri, Uri contentUri, String mime, PackageManager pm) {
        List<ResolveInfo> result = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        boolean defaultOnly = mime != null && mime.isEmpty();
        String[] probes = (mime == null || mime.isEmpty()) ? new String[] { "*/*" } : new String[] { mime };
        for (String probe : probes) {
            for (Uri probeUri : new Uri[] { fileUri, contentUri }) {
                try {
                    Intent intent = new Intent(Intent.ACTION_VIEW);
                    intent.setDataAndType(probeUri, probe);
                    int flags = defaultOnly ? PackageManager.MATCH_DEFAULT_ONLY : 0;
                    for (ResolveInfo ri : pm.queryIntentActivities(intent, flags)) {
                        String pkg = ri.activityInfo.packageName;
                        if ("com.deh.mobile".equals(pkg)) continue;
                        if (seen.add(pkg)) result.add(ri);
                    }
                } catch (Throwable ignored) {
                }
            }
        }
        return result;
    }

    private String guessMimeOf(String path) {
        String lower = path.toLowerCase();
        if (lower.endsWith(".txt") || lower.endsWith(".log") || lower.endsWith(".json")
                || lower.endsWith(".md") || lower.endsWith(".xml") || lower.endsWith(".java")
                || lower.endsWith(".js") || lower.endsWith(".py") || lower.endsWith(".sh")) {
            return "text/plain";
        }
        if (lower.endsWith(".png")) return "image/png";
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) return "image/jpeg";
        if (lower.endsWith(".gif")) return "image/gif";
        if (lower.endsWith(".webp")) return "image/webp";
        if (lower.endsWith(".mp4")) return "video/mp4";
        if (lower.endsWith(".mp3")) return "audio/mpeg";
        if (lower.endsWith(".pdf")) return "application/pdf";
        if (lower.endsWith(".zip")) return "application/zip";
        if (lower.endsWith(".apk")) return "application/vnd.android.package-archive";
        return "application/octet-stream";
    }

    private void addSettingsButton(LinearLayout parent, String text, final Runnable action) {
        Button button = new Button(this);
        button.setText(text);
        button.setAllCaps(false);
        button.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                action.run();
            }
        });
        parent.addView(button, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
    }

    private String lastShizukuLabel = "";

    private void startSettingsTicker() {
        stopSettingsTicker();
        lastShizukuLabel = "";
        settingsTicker = new Runnable() {
            @Override
            public void run() {
                if (settingsDialog != null && settingsDialog.isShowing()) {
                    String label = shizukuLabel();
                    if (!label.equals(lastShizukuLabel)) {
                        lastShizukuLabel = label;
                        settingsDialog.setMessage("Shizuku：" + label);
                    }
                    mainHandler.postDelayed(this, 200);
                }
            }
        };
        mainHandler.postDelayed(settingsTicker, 100);
    }

    private void stopSettingsTicker() {
        if (settingsTicker != null) {
            mainHandler.removeCallbacks(settingsTicker);
            settingsTicker = null;
        }
    }

    private void handleShizukuAction() {
        String status = ShizukuBridge.status();
        ShizukuBridge.requestPermission(1003);
        if ("granted".equals(status)) {
            Toast.makeText(this, "已授权。如需重新授权，请先在 Shizuku 中撤销，再点此请求", Toast.LENGTH_LONG).show();
        } else if ("denied".equals(status)) {
            Toast.makeText(this, "已申请权限，请在弹出的 Shizuku 授权窗口点「允许」", Toast.LENGTH_LONG).show();
        } else {
            Toast.makeText(this, "Shizuku 服务未就绪，正在后台重试；稍等几秒后再点一次", Toast.LENGTH_LONG).show();
        }
    }

    private void toggleWorkRoot() {
        final boolean toPrivate = !"private".equals(Prefs.workRootMode(this));
        new AlertDialog.Builder(this)
                .setTitle("切换工作区")
                .setMessage("工作区根将切换到：" + (toPrivate ? "应用私有目录（/data/user/0/com.deh.mobile/files/work/DEH）" : "内部存储（/storage/emulated/0/DEH）")
                        + "\n\n切换后需要重启 DSH。已安装的运行时保留，不会重新安装。是否切换？")
                .setPositiveButton("切换并重启", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        Prefs.setWorkRootMode(MainActivity.this, toPrivate ? "private" : "internal");
                        runtime.stopHost();
                        recreate();
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void confirmReset() {
        new AlertDialog.Builder(this)
                .setTitle("重置运行时")
                .setMessage("将删除已解压的 rootfs、Node 和已安装的 DSH，下次启动会重新初始化（工作区文件不受影响）。确定吗？")
                .setPositiveButton("重置", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        runtime.stopHost();
                        Runtime.deleteRecursively(runtime.base);
                        recreate();
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void writeShizukuFiles() {
        try {
            File dir = new File("/storage/emulated/0/DEH");
            dir.mkdirs();
            File tokenFile = new File(dir, ".shizuku-token");
            FileOutputStream tokenOut = new FileOutputStream(tokenFile);
            tokenOut.write(LocalBridge.token().getBytes("UTF-8"));
            tokenOut.close();

            String shizukuScript = "#!/bin/sh\n"
                    + "TOKEN=$(cat /storage/emulated/0/DEH/.shizuku-token 2>/dev/null)\n"
                    + "if [ -z \"$1\" ]; then echo '\u7528\u6cd5: shizuku.sh <command>'; exit 1; fi\n"
                    + "ENC=$(printf '%s' \"$1\" | python3 -c 'import sys,urllib.parse;print(urllib.parse.quote(sys.stdin.read()))' 2>/dev/null)\n"
                    + "curl -s \"http://127.0.0.1:38620/run?token=$TOKEN&cmd=$ENC\"\n";
            writeExecutable(new File(dir, "shizuku.sh"), shizukuScript);

            String otgAdbScript = "#!/bin/sh\n"
                    + "TOKEN=$(cat /storage/emulated/0/DEH/.shizuku-token 2>/dev/null)\n"
                    + "B=http://127.0.0.1:38620\n"
                    + "ENC() { printf '%s' \"$1\" | python3 -c 'import sys,urllib.parse;print(urllib.parse.quote(sys.stdin.read()))' 2>/dev/null; }\n"
                    + "case \"$1\" in\n"
                    + "  list) curl -s \"$B/adb/list?token=$TOKEN\";;\n"
                    + "  connect) curl -s \"$B/adb/connect?token=$TOKEN&device=$2\";;\n"
                    + "  shell) shift; curl -s \"$B/adb/shell?token=$TOKEN&cmd=$(ENC \"$*\")\";;\n"
                    + "  install) curl -s \"$B/adb/install?token=$TOKEN&path=$(ENC \"$2\")\";;\n"
                    + "  push) curl -s \"$B/adb/push?token=$TOKEN&path=$(ENC \"$2\")&cmd=$(ENC \"$3\")\";;\n"
                    + "  pull) curl -s \"$B/adb/pull?token=$TOKEN&path=$(ENC \"$2\")&cmd=$(ENC \"$3\")\";;\n"
                    + "  *) echo \"用法: otg-adb.sh list|connect <device>|shell <cmd>|install <apk>|push <local> <remote>|pull <remote> <local>\";;\n"
                    + "esac\n";
            writeExecutable(new File(dir, "otg-adb.sh"), otgAdbScript);

            String openScript = "#!/bin/sh\n"
                    + "TARGET=\"$1\"\n"
                    + "TOKEN=$(cat /storage/emulated/0/DEH/.shizuku-token 2>/dev/null)\n"
                    + "ENC=$(printf '%s' \"$TARGET\" | python3 -c 'import sys,urllib.parse;print(urllib.parse.quote(sys.stdin.read()))' 2>/dev/null)\n"
                    + "[ -z \"$ENC\" ] && ENC=\"$TARGET\"\n"
                    + "curl -s \"http://127.0.0.1:38620/open?token=$TOKEN&path=$ENC\"\n";
            writeExecutable(new File(runtime.rootfs, "usr/local/bin/xdg-open"), openScript);
            writeExecutable(new File(runtime.rootfs, "usr/local/bin/open"), openScript);
        } catch (Exception ignored) {
        }
    }

    private static void writeExecutable(File file, String content) throws Exception {
        file.getParentFile().mkdirs();
        FileOutputStream out = new FileOutputStream(file);
        out.write(content.getBytes("UTF-8"));
        out.close();
        file.setExecutable(true, false);
    }

    private String shizukuLabel() {
        String status = ShizukuBridge.status();
        if ("granted".equals(status)) return "已授权";
        if ("denied".equals(status)) return "未授权";
        if ("connecting".equals(status)) return "连接中…";
        return "连接中…（等待 Shizuku）";
    }

    private void requestStoragePermission() {
        if (Build.VERSION.SDK_INT >= 30) {
            if (!Environment.isExternalStorageManager()) {
                try {
                    Intent intent = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION);
                    intent.setData(Uri.parse("package:" + getPackageName()));
                    startActivity(intent);
                } catch (Exception e) {
                    try {
                        startActivity(new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));
                    } catch (Exception ignored) {
                    }
                }
            }
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == REQUEST_FILE) {
            Uri[] results = null;
            try {
                results = WebChromeClient.FileChooserParams.parseResult(resultCode, data);
            } catch (Exception ignored) {
            }
            if (results == null && resultCode == RESULT_OK && data != null && data.getData() != null) {
                results = new Uri[] { data.getData() };
            }
            if (fileCallback != null) {
                fileCallback.onReceiveValue(results);
                fileCallback = null;
            }
            super.onActivityResult(requestCode, resultCode, data);
            return;
        }
        super.onActivityResult(requestCode, resultCode, data);
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        if (webView != null && webView.getVisibility() == View.VISIBLE) {
            return;
        }
        CharSequence logs = logText == null ? "" : logText.getText();
        CharSequence status = statusText == null ? "" : statusText.getText();
        buildUi();
        logText.setText(logs);
        if (statusText != null && status.length() > 0) statusText.setText(status);
        if (logScroll != null) {
            logScroll.post(new Runnable() {
                @Override
                public void run() {
                    logScroll.fullScroll(View.FOCUS_DOWN);
                }
            });
        }
    }

    @Override
    public void onBackPressed() {
        if (webView != null && webView.getVisibility() == View.VISIBLE && webView.canGoBack()) {
            webView.goBack();
            return;
        }
        super.onBackPressed();
    }

    private void handleIntent(Intent intent) {
        if (intent != null && "com.deh.mobile.OPEN_SETTINGS".equals(intent.getAction())) {
            intent.setAction(null);
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    showSettings();
                }
            });
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        handleIntent(intent);
    }

    private android.content.BroadcastReceiver usbReceiver;

    private void registerUsbReceiver() {
        if (usbReceiver != null) {
            return;
        }
        usbReceiver = new android.content.BroadcastReceiver() {
            @Override
            public void onReceive(android.content.Context context, android.content.Intent intent) {
                String action = intent == null ? "" : intent.getAction();
                if (action == null) return;
                if ("android.hardware.usb.action.USB_DEVICE_ATTACHED".equals(action)
                        || "android.hardware.usb.action.USB_DEVICE_DETACHED".equals(action)) {
                    if ("android.hardware.usb.action.USB_DEVICE_DETACHED".equals(action)) {
                        // 必须放到后台线程：USB close 可能阻塞，主线程做会 ANR
                        new Thread(new Runnable() {
                            @Override
                            public void run() {
                                try {
                                    com.deh.mobile.adb.AdbManager.get().shutdown();
                                } catch (Throwable ignored) {
                                }
                            }
                        }, "adb-detach").start();
                    }
                    diag("[OTG] " + action + " → " + OtgManager.summary(MainActivity.this).replace("\n", " | "));
                    Toast.makeText(MainActivity.this, OtgManager.summary(MainActivity.this), Toast.LENGTH_LONG).show();
                }
            }
        };
        android.content.IntentFilter filter = new android.content.IntentFilter();
        filter.addAction("android.hardware.usb.action.USB_DEVICE_ATTACHED");
        filter.addAction("android.hardware.usb.action.USB_DEVICE_DETACHED");
        try {
            registerReceiver(usbReceiver, filter);
        } catch (Throwable ignored) {
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        registerUsbReceiver();
        resumed = true;
        current = this;
        diag("onResume pending=" + pendingUrl);
        if (pendingUrl != null) {
            String url = pendingUrl;
            pendingUrl = null;
            showWeb(url);
            return;
        }
        if (webView != null && webView.getVisibility() == View.VISIBLE) {
            webView.onResume();
            checkWebBlank();
        }
    }

    @Override
    protected void onPause() {
        resumed = false;
        if (current == this) current = null;
        diag("onPause");
        CookieManager.getInstance().flush();
        if (webView != null) webView.onPause();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        if (isFinishing()) {
            if (runtime != null) runtime.stopHost();
            stopService(new Intent(this, DshService.class));
        }
        if (webView != null) webView.destroy();
        super.onDestroy();
    }
}
