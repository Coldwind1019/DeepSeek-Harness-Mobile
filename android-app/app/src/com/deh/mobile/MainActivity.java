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
    private AlertDialog pickerDialog;
    private static MainActivity current;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
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
        bootstrap();
    }

    private boolean isLandscape() {
        return getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE;
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

        webView = new WebView(this);
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
                checkWebBlank();
            }
        }, 2500);
        webView.postDelayed(new Runnable() {
            @Override
            public void run() {
                checkWebBlank();
            }
        }, 7000);
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
                    "(function(){try{return (document.body&&document.body.innerText?document.body.innerText.length:0)}catch(e){return -1}})()",
                    new ValueCallback<String>() {
                        @Override
                        public void onReceiveValue(String value) {
                            int length = -1;
                            try {
                                length = Integer.parseInt(value == null ? "-1" : value.trim());
                            } catch (Throwable ignored) {
                            }
                            int w = webView.getWidth();
                            int h = webView.getHeight();
                            int ch = webView.getContentHeight();
                            diag("checkBlank text=" + length + " w=" + w + " h=" + h + " contentH=" + ch);
                            if (hostUrl != null && (length >= 0 && length < 80) || (w <= 0 || h <= 0)) {
                                diag("reload triggered");
                                webView.loadUrl(hostUrl);
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

    @Override
    protected void onResume() {
        super.onResume();
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
