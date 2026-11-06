package com.deh.mobile;

import android.content.Context;
import android.os.Environment;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 独立版运行时：解压内置 rootfs/Node/proot，安装 DSH，按工作区根启动本地 dsh web。
 * 工作区根支持「内部存储」与「应用私有目录」两种，由 Prefs 决定。
 */
public final class Runtime {
    public interface LogListener { void onLog(String line); }
    public interface UrlListener { void onUrl(String url); }

    private static final Pattern URL_PATTERN = Pattern.compile("dsh web:\\s*(http://\\S+)");
    private static final String NODE_DIR = "node-v24.21.0-linux-arm64";
    private static final String GUEST_NODE_BIN = "/usr/local/" + NODE_DIR + "/bin";
    private static final String DSH_BIN = "/usr/local/" + NODE_DIR + "/lib/node_modules/@deepseek-ai/dsh/lib/bin.js";

    private final Context context;
    public final File base;
    public final File rootfs;
    public final File bin;
    public final File tmp;

    /** 工作区根（宿主路径）与容器内路径。 */
    public final File workRoot;
    public final String guestWorkRoot;
    public final String guestHome;

    private Process hostProcess;
    /** 当前运行的 dsh web 地址（供 Activity 重建时复用）。 */
    public volatile String lastUrl;

    public Runtime(Context context) {
        this.context = context;
        this.base = new File(context.getFilesDir(), "dsh");
        this.rootfs = new File(base, "rootfs");
        this.bin = new File(base, "bin");
        this.tmp = new File(base, "tmp");

        if ("private".equals(Prefs.workRootMode(context))) {
            File priv = new File(context.getFilesDir(), "work");
            this.workRoot = new File(priv, "DEH");
            this.guestWorkRoot = "/work/DEH";
            this.guestHome = "/work";
        } else {
            this.workRoot = new File(Environment.getExternalStorageDirectory(), "DEH");
            this.guestWorkRoot = "/sdcard/DEH";
            this.guestHome = "/storage/emulated/0";
        }
        if (!workRoot.exists()) workRoot.mkdirs();
    }

    public String workModeLabel() {
        return "private".equals(Prefs.workRootMode(context)) ? "应用私有目录" : "内部存储";
    }

    public boolean isPrepared() {
        return new File(bin, "proot").isFile()
                && new File(bin, "loader").isFile()
                && new File(rootfs, "bin/bash").isFile()
                && new File(rootfs, "usr/local/" + NODE_DIR + "/bin/node").isFile();
    }

    public boolean isInstalled() {
        return isPrepared() && new File(base, ".installed").isFile();
    }

    public void prepare(LogListener log) throws Exception {
        base.mkdirs();
        bin.mkdirs();
        tmp.mkdirs();

        log.onLog("[1/4] 解压 Linux 根文件系统（约 30MB，首次较慢）...");
        extractAsset("runtime/ubuntu-base-arm64.tar.gz", rootfs, log);

        log.onLog("[2/4] 解压 Node.js 运行时（约 56MB）...");
        File usrLocal = new File(rootfs, "usr/local");
        if (!usrLocal.exists() && !usrLocal.mkdirs()) throw new IOException("无法创建 /usr/local");
        extractAsset("runtime/node-linux-arm64.tar.gz", usrLocal, log);

        log.onLog("[3/4] 解压 proot 与依赖库...");
        copyAsset("runtime/proot", new File(bin, "proot"));
        copyAsset("runtime/libtalloc.so.2", new File(bin, "libtalloc.so.2"));
        copyAsset("runtime/libandroid-shmem.so", new File(bin, "libandroid-shmem.so"));
        copyAsset("runtime/loader", new File(bin, "loader"));
        copyAsset("runtime/loader32", new File(bin, "loader32"));
        new File(bin, "proot").setExecutable(true, false);
        new File(bin, "loader").setExecutable(true, false);

        log.onLog("[4/4] 写入初始化脚本...");
        writeInitScript();
        selfCheck(log);
        new File(base, ".prepared").createNewFile();
        log.onLog("运行环境准备完成。");
    }

    /** 从备份包恢复整个 rootfs（跳过首次安装），恢复后 .installed 标记存在。 */
    public void restoreFromBackup(File backupGz, LogListener log) throws Exception {
        base.mkdirs();
        bin.mkdirs();
        tmp.mkdirs();
        log.onLog("[恢复] 正在从备份恢复运行时（" + (backupGz.length() / 1024 / 1024) + " MB），请耐心等待...");
        InputStream in = new FileInputStream(backupGz);
        try {
            TarGzExtractor.extract(in, rootfs, new TarGzExtractor.ProgressListener() {
                @Override
                public void onFile(int count, long bytes) {
                }
            });
        } finally {
            in.close();
        }
        copyAsset("runtime/proot", new File(bin, "proot"));
        copyAsset("runtime/libtalloc.so.2", new File(bin, "libtalloc.so.2"));
        copyAsset("runtime/libandroid-shmem.so", new File(bin, "libandroid-shmem.so"));
        copyAsset("runtime/loader", new File(bin, "loader"));
        copyAsset("runtime/loader32", new File(bin, "loader32"));
        new File(bin, "proot").setExecutable(true, false);
        new File(bin, "loader").setExecutable(true, false);
        new File(base, ".prepared").createNewFile();
        new File(base, ".installed").createNewFile();
        log.onLog("[恢复] 运行时恢复完成（包含 DSH 与历史会话）。");
    }

    public void install(LogListener log) throws Exception {
        writeInitScript();
        log.onLog("开始安装系统依赖与 DeepSeek Harness（需要联网，约 5-20 分钟）...");
        log.onLog("如果长时间无输出属于正常现象（apt/npm 在下载）。");
        int code = runProot("bash /root/.dsh-init.sh", log);
        if (code != 0) {
            throw new IOException("安装失败（退出码 " + code + "）。请检查网络后重试。");
        }
        new File(base, ".installed").createNewFile();
        log.onLog("DeepSeek Harness 安装完成。");
    }

    /** 启动前健康自检（幂等、快速）：允许 install scripts、修复 node-pty spawn-helper 执行位。 */

    /** 检测服务端文件是否被误转译（minify 单行），若是则重装 DSH 恢复。 */
    private void repairDshIfBroken(LogListener log) {
        try {
            File boot = new File(rootfs, "usr/local/node-v24.21.0-linux-arm64/lib/node_modules/@deepseek-ai/dsh/node_modules/@deepseek-ai/dsh-app-boot/lib/index.js");
            if (!boot.isFile()) return;
            // 校验 semver 完整性（npm 安装中断时 semver 会缺 internal/re.js）
            File semverRe = new File(rootfs, "usr/local/node-v24.21.0-linux-arm64/lib/node_modules/@deepseek-ai/dsh/node_modules/semver/internal/re.js");
            if (!semverRe.isFile()) {
                log.onLog("[修复] 检测到 semver 不完整，正在重装 semver…");
                try {
                    runProot("export PATH=/usr/local/node-v24.21.0-linux-arm64/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin; npm config set registry https://registry.npmmirror.com; npm install --prefix /usr/local/node-v24.21.0-linux-arm64/lib/node_modules/@deepseek-ai/dsh semver@7 --force 2>&1 | tail -3", log);
                } catch (Exception e) {
                    log.onLog("[修复] semver 重装失败: " + e.getMessage());
                }
            }
            int len = (int) Math.min(boot.length(), 8192);
            byte[] head = new byte[len];
            java.io.FileInputStream in = new java.io.FileInputStream(boot);
            int n = in.read(head);
            in.close();
            if (n <= 0) return;
            String text = new String(head, 0, n, "UTF-8");
            if (text.indexOf('\n') < 0) {
                log.onLog("[修复] 服务端文件被误转译（单行 minify），重装 DSH 组件…");
                runProot("npm i -g @deepseek-ai/dsh@latest --force 2>&1 | tail -3", log);
                log.onLog("[修复] DSH 组件已重装");
            }
        } catch (Throwable t) {
            log.onLog("[修复] 检测失败: " + t);
        }
    }

    private void ensureRuntimeHealth(LogListener log) {
        try {
            String script = "npm config set allow-scripts \"@deepseek-ai/dsh-subprocess-local,koffi,node-pty,@google/genai,protobufjs\" --location=user >/dev/null 2>&1 || true; "
                    + "node /usr/local/node-v24.21.0-linux-arm64/lib/node_modules/@deepseek-ai/dsh/node_modules/@deepseek-ai/dsh-subprocess-local/scripts/ensure-spawn-helper.mjs >/dev/null 2>&1 || true; true";
            runProot(script, log);
        } catch (Throwable t) {
            log.onLog("[自检] 失败: " + t);
        }
    }

    /** 用内置的 ES2017 转译版前端覆盖 DSH Web 前端（兼容旧 WebView）。 */

    /** 根据本机能力下载对应的转译前端到外部存储（一次性，1.3MB）。 */

    private boolean downloadTo(File ext, String url, LogListener log) {
        java.net.HttpURLConnection conn = null;
        try {
            log.onLog("[自适应] 尝试获取: " + url);
            conn = (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
            conn.setConnectTimeout(12000);
            conn.setReadTimeout(60000);
            conn.setInstanceFollowRedirects(true);
            conn.setRequestProperty("User-Agent", "DeepSeekHarnessMobile");
            int code = conn.getResponseCode();
            if (code != 200) {
                log.onLog("[自适应] HTTP " + code);
                return false;
            }
            ext.mkdirs();
            TarGzExtractor.extract(conn.getInputStream(), ext, null);
            return true;
        } catch (Throwable t) {
            log.onLog("[自适应] 获取失败: " + t);
            return false;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    /** 手动触发：下载/重新生成兼容前端到外部存储。 */
    public void downloadWebpatchNow(LogListener log) {
        try {
            File ext = new File("/storage/emulated/0/DEH/webpatch");
            if (ext.isDirectory()) {
                File[] olds = ext.listFiles();
                if (olds != null) {
                    for (File f : olds) {
                        deleteTree(f);
                    }
                }
            }
            ensureAdaptiveWebpatch(log);
        } catch (Throwable t) {
            log.onLog("[自适应] 手动下载失败: " + t);
        }
    }

    private void deleteTree(File f) {
        if (f.isDirectory()) {
            File[] children = f.listFiles();
            if (children != null) {
                for (File c : children) deleteTree(c);
            }
        }
        f.delete();
    }


    /** 本机转译：内置 transpile.mjs → proot + esbuild（npmmirror）生成匹配当前版本的前端。 */
    private void localTranspile(final String target, LogListener log) {
        try {
            File script = new File("/storage/emulated/0/DEH/transpile.mjs");
            java.io.InputStream in = context.getAssets().open("transpile.mjs");
            java.io.FileOutputStream out = new java.io.FileOutputStream(script);
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) >= 0) out.write(buf, 0, n);
            in.close();
            out.close();
            String front = "/usr/local/node-v24.21.0-linux-arm64/lib/node_modules/@deepseek-ai/dsh/node_modules/@deepseek-ai/dsh-web-frontend/dist";
            String cmd = "mkdir -p /root/esb; cd /root/esb; "
                    + "[ -d node_modules/esbuild ] || npm i esbuild --registry=https://registry.npmmirror.com --no-audit --no-fund 2>&1 | tail -2; "
                    + "node /storage/emulated/0/DEH/transpile.mjs " + target + " " + front + " /storage/emulated/0/DEH/webpatch 2>&1 | tail -3";
            log.onLog("[自适应] 本机转译（匹配当前 DSH 版本）…");
            runProot(cmd, log);
            File[] files = new File("/storage/emulated/0/DEH/webpatch").listFiles();
            log.onLog("[自适应] 本机转译完成，条目 " + (files == null ? 0 : files.length));
        } catch (Throwable t) {
            log.onLog("[自适应] 本机转译失败: " + t);
        }
    }

    private void ensureAdaptiveWebpatch(LogListener log) {
        try {
            File ext = new File("/storage/emulated/0/DEH/webpatch");
            if (ext.isDirectory() && ext.list() != null && ext.list().length > 0) {
                return;
            }
            com.deh.mobile.JsCompat.Profile p = com.deh.mobile.JsCompat.PROFILE;
            String target = (p != null && p.needsEs2015()) ? "es2015" : "es2017";
            String[] sources = new String[] {
                    "https://github.com/Coldwind1019/DeepSeek-Harness-Mobile/releases/download/v3.0-stable/webpatch-" + target + ".tgz",
                    "https://cdn.jsdelivr.net/gh/Coldwind1019/DeepSeek-Harness-Mobile@main/webpatch-" + target + ".tgz",
                    "https://raw.githubusercontent.com/Coldwind1019/DeepSeek-Harness-Mobile/main/webpatch-" + target + ".tgz"
            };
            boolean ok = false;
            for (String url : sources) {
                if (downloadTo(ext, url, log)) {
                    java.io.File[] files = ext.listFiles();
                    log.onLog("[自适应] 已生成外部 webpatch（" + target + "），条目 " + (files == null ? 0 : files.length));
                    ok = true;
                    break;
                }
            }
            if (!ok) {
                log.onLog("[自适应] 兼容前端下载失败：页面可能残缺，请在设置里点“下载兼容前端”或手动放入 DEH/webpatch");
            }
        } catch (Throwable t) {
            log.onLog("[自适应] 获取/解压失败（降级为原生前端 + polyfill）: " + t);
        }
    }


    private String indexHash(File indexHtml) {
        try {
            byte[] data = new byte[(int) indexHtml.length()];
            java.io.FileInputStream in = new java.io.FileInputStream(indexHtml);
            int n = in.read(data);
            in.close();
            return indexHashText(new String(data, 0, n, "UTF-8"));
        } catch (Throwable t) {
            return "?";
        }
    }

    private String indexHashText(String html) {
        try {
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("assets/(index-[A-Za-z0-9_]+\\.js)").matcher(html);
            if (m.find()) return m.group(1);
        } catch (Throwable ignored) {
        }
        return "?";
    }


    /** 应用插件 client.js 兼容版（76 个插件，sidebar 等旧 WebView 会崩）。 */
    private void applyPluginClients(File webpatchDir, LogListener log) {
        try {
            File plugins = new File(webpatchDir, "plugins");
            if (!plugins.isDirectory()) {
                return;
            }
            File dsh = new File(rootfs, "usr/local/node-v24.21.0-linux-arm64/lib/node_modules/@deepseek-ai/dsh");
            File target = new File(dsh, "node_modules/@deepseek-ai");
            File[] pkgs = plugins.listFiles();
            int n = 0;
            if (pkgs != null) {
                for (File pkg : pkgs) {
                    File pkgLib = new File(pkg, "lib");
                    File dstLib = new File(target, pkg.getName() + "/lib");
                    if (pkgLib.isDirectory()) {
                        // transform-all 模式：整个 lib 目录（client.js + 依赖模块）
                        n += copyDirTree(pkgLib, dstLib);
                    } else {
                        File cj = new File(pkg, "client.js");
                        if (!cj.isFile()) continue;
                        File dst = new File(target, pkg.getName() + "/lib/client.js");
                        File parent = dst.getParentFile();
                        if (parent != null) parent.mkdirs();
                        copyFile(cj, dst);
                        n++;
                    }
                }
            }
            log.onLog("[兼容] 已应用插件兼容版文件 " + n + " 个");
        } catch (Throwable t) {
            log.onLog("[兼容] 插件兼容应用失败: " + t);
        }
    }

    private void copyFile(File src, File dst) throws java.io.IOException {
        java.io.FileInputStream in = new java.io.FileInputStream(src);
        java.io.FileOutputStream out = new java.io.FileOutputStream(dst);
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) >= 0) out.write(buf, 0, n);
        in.close();
        out.close();
    }

    private void patchDshFrontend(LogListener log) {
        try {
            File dist = new File(rootfs, "usr/local/node-v24.21.0-linux-arm64/lib/node_modules/@deepseek-ai/dsh/node_modules/@deepseek-ai/dsh-web-frontend/dist");
            if (!dist.isDirectory()) {
                log.onLog("[兼容] DSH 前端目录不存在，跳过");
                return;
            }
            String wv = "unknown";
            try {
                android.content.pm.PackageInfo pi = android.webkit.WebView.getCurrentWebViewPackage();
                if (pi != null && pi.versionName != null) wv = pi.versionName;
            } catch (Throwable ignored) {
            }
            com.deh.mobile.JsCompat.Profile profile = com.deh.mobile.JsCompat.PROFILE;
            log.onLog("[自适应] WebView " + wv + " " + (profile == null ? "未探测" : profile.toString()));
            // 依据 UA 检测到的 Chrome 主版本选择兼容档位
            String tier = "unknown";
            try {
                if (com.deh.mobile.WebViewProbe.LAST != null) {
                    tier = com.deh.mobile.WebViewProbe.LAST.tier();
                }
            } catch (Throwable ignored) {
            }
            log.onLog("[检测] 兼容档位: " + tier);
            if ("native".equals(tier)) {
                log.onLog("[兼容] Chrome 119+：使用原生前端 + 轻量 polyfill");
                return;
            }
            final String transpileTarget = "es2015".equals(tier) ? "es2015" : "es2017";

            // 开源版：外部 webpatch 优先，但必须与当前 DSH 版本匹配（index hash 一致）
            File external = new File("/storage/emulated/0/DEH/webpatch");
            String distHash = indexHash(new File(dist, "index.html"));
            String extHash = "?";
            File extIndex = new File(external, "index.html");
            if (extIndex.isFile()) {
                extHash = indexHash(extIndex);
            }
            boolean extExists = external.isDirectory() && external.list() != null && external.list().length > 0;
            if (extExists && extHash.equals(distHash)) {
                int count = copyDirTree(external, dist);
                log.onLog("[兼容] 已应用外部 webpatch（版本匹配 " + distHash + "），文件数 " + count);
                applyPluginClients(external, log);
                return;
            }
            if (extExists && !extHash.equals(distHash)) {
                log.onLog("[自适应] 外部 webpatch 版本不匹配（" + extHash + " != " + distHash + "），丢弃并本机转译");
                deleteTree(external);
            }
            // 本机转译：按当前 DSH 版本生成匹配前端（不依赖下载 / 不受版本更新影响）
            log.onLog("[自适应] 本机转译当前 DSH 前端（" + distHash + "）…");
            localTranspile(transpileTarget, log);
            File localPatch = new File("/storage/emulated/0/DEH/webpatch");
            if (localPatch.isDirectory()) {
                int c2 = copyDirTree(localPatch, dist);
                log.onLog("[兼容] 已应用本机转译前端，文件数 " + c2);
                applyPluginClients(localPatch, log);
            } else {
                // 私用版兜底：内置 es2017 前端（私用 APK 内含 assets/webpatch）
                try {
                    int builtin = copyAssetTree(context.getAssets(), "webpatch", dist);
                    log.onLog("[兼容] 已应用内置 webpatch（es2017），文件数 " + builtin);
                } catch (Throwable t) {
                    log.onLog("[兼容] 本机转译失败，页面可能残缺（请检查网络/npm）: " + t);
                }
            }
        } catch (Throwable t) {
            log.onLog("[兼容] 前端补丁失败: " + t);
        }
    }


    private int copyDirTree(File src, File dst) throws java.io.IOException {
        if (src.isFile()) {
            dst.getParentFile().mkdirs();
            java.io.FileInputStream in = new java.io.FileInputStream(src);
            java.io.FileOutputStream out = new java.io.FileOutputStream(dst);
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) >= 0) {
                out.write(buf, 0, n);
            }
            in.close();
            out.close();
            return 1;
        }
        File[] children = src.listFiles();
        if (children == null) return 0;
        int total = 0;
        for (File child : children) {
            total += copyDirTree(child, new File(dst, child.getName()));
        }
        return total;
    }

    private int copyAssetTree(android.content.res.AssetManager am, String path, File target) throws java.io.IOException {
        String[] children = am.list(path);
        if (children == null || children.length == 0) {
            java.io.InputStream in = am.open(path);
            target.getParentFile().mkdirs();
            java.io.FileOutputStream fos = new java.io.FileOutputStream(target);
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) >= 0) {
                fos.write(buf, 0, n);
            }
            in.close();
            fos.close();
            return 1;
        }
        int total = 0;
        for (String child : children) {
            total += copyAssetTree(am, path + "/" + child, new File(target, child));
        }
        return total;
    }


    /** 后台检查并自动升级 DSH（不阻塞启动，升级后下次启动生效）。 */

    /** 从 npm registry 拉取可用 DSH 版本列表（新→旧）；24h 缓存 + 离线回退。 */
    public java.util.List<String> listDshVersions(LogListener log) {
        java.util.List<String> out = new java.util.ArrayList<>();
        final File cache = new File("/storage/emulated/0/DEH/.dsh_versions.json");
        try {
            // 24h 内直接用缓存（秒开）
            if (cache.isFile() && System.currentTimeMillis() - cache.lastModified() < 86400000L) {
                out = readVersionCache(cache);
                if (!out.isEmpty()) {
                    log.onLog("[版本] 使用本地缓存（" + out.size() + " 个版本）");
                    return out;
                }
            }
            log.onLog("[版本] 获取 DSH 可用版本…");
            String json = runProotCapture("npm view @deepseek-ai/dsh versions --json 2>/dev/null", 30000);
            if (json != null && json.contains("[")) {
                org.json.JSONArray arr = new org.json.JSONArray(json.trim());
                for (int i = arr.length() - 1; i >= 0 && out.size() < 40; i--) {
                    out.add(arr.getString(i));
                }
                writeVersionCache(cache, out);
            }
            if (out.isEmpty() && cache.isFile()) {
                out = readVersionCache(cache);
                log.onLog("[版本] 网络失败，使用旧缓存（" + out.size() + " 个版本）");
            }
        } catch (Throwable t) {
            log.onLog("[版本] 获取失败: " + t);
            if (cache.isFile()) out = readVersionCache(cache);
        }
        return out;
    }

    private java.util.List<String> readVersionCache(File f) {
        java.util.List<String> out = new java.util.ArrayList<>();
        try {
            byte[] data = new byte[(int) f.length()];
            java.io.FileInputStream in = new java.io.FileInputStream(f);
            int n = in.read(data);
            in.close();
            org.json.JSONArray arr = new org.json.JSONArray(new String(data, 0, n, "UTF-8"));
            for (int i = 0; i < arr.length(); i++) out.add(arr.getString(i));
        } catch (Throwable t) {
            // ignore
        }
        return out;
    }

    private void writeVersionCache(File f, java.util.List<String> versions) {
        try {
            org.json.JSONArray arr = new org.json.JSONArray();
            for (String v : versions) arr.put(v);
            java.io.FileOutputStream out = new java.io.FileOutputStream(f);
            out.write(arr.toString().getBytes("UTF-8"));
            out.close();
        } catch (Throwable t) {
            // ignore
        }
    }

    /** 校验当前 DSH 是否完整可用（版本 + semver + app-boot）。 */
    public boolean verifyDsh(String expected, StringBuilder why) {
        try {
            File dsh = new File(rootfs, "usr/local/node-v24.21.0-linux-arm64/lib/node_modules/@deepseek-ai/dsh");
            if (!new File(dsh, "lib/bin.js").isFile()) {
                if (why != null) why.append("缺少 lib/bin.js");
                return false;
            }
            if (!new File(dsh, "node_modules/semver/internal/re.js").isFile()) {
                if (why != null) why.append("semver 不完整（internal/re.js 缺失）");
                return false;
            }
            if (!new File(dsh, "node_modules/@deepseek-ai/dsh-app-boot/lib/index.js").isFile()) {
                if (why != null) why.append("缺少 dsh-app-boot");
                return false;
            }
            if (expected != null) {
                String out = runProotCapture("dsh --version 2>/dev/null | tail -1", 30000);
                if (out == null || !out.contains(expected)) {
                    if (why != null) why.append("版本校验不符（期望 " + expected + "，实际 " + (out == null ? "无输出" : out.trim()) + "）");
                    return false;
                }
            }
            return true;
        } catch (Throwable t) {
            if (why != null) why.append("校验异常: " + t.getMessage());
            return false;
        }
    }

    /** 安装指定版本的 DSH：预检 → 备份 → 安装 → 校验 → 失败回滚。返回 true=成功。 */
    public boolean installDshVersion(final String version, LogListener log) {
        final String dshPath = "usr/local/node-v24.21.0-linux-arm64/lib/node_modules/@deepseek-ai/dsh";
        File dshDir = new File(rootfs, dshPath);
        File bakDir = new File(rootfs, dshPath + ".bak");
        try {
            // 1. 预检：磁盘空间（留 2GB 余量）
            long free = new File("/storage/emulated/0").getUsableSpace();
            log.onLog("[版本] 磁盘可用 " + (free / 1024 / 1024) + " MB");
            if (free < 2L * 1024 * 1024 * 1024) {
                log.onLog("[版本] 空间不足（需 ≥2GB），取消安装");
                return false;
            }
            // 2. 预检：registry 可达
            String ping = runProotCapture("npm ping --registry=https://registry.npmmirror.com 2>&1 | tail -1", 20000);
            log.onLog("[版本] registry: " + (ping == null ? "无响应（继续尝试）" : ping.trim()));
            // 3. 备份当前版本（原子性：失败可回滚）
            if (bakDir.exists()) deleteTree(bakDir);
            if (dshDir.exists()) {
                log.onLog("[版本] 备份当前版本…");
                if (!dshDir.renameTo(bakDir)) {
                    log.onLog("[版本] 备份失败，保守取消");
                    return false;
                }
            }
            // 4. 安装
            log.onLog("[版本] 安装 DSH " + version + " …（可能需要几分钟）");
            runProot("export PATH=/usr/local/node-v24.21.0-linux-arm64/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin; "
                    + "npm config set registry https://registry.npmmirror.com >/dev/null 2>&1; "
                    + "npm i -g @deepseek-ai/dsh@" + version + " 2>&1 | tail -5; "
                    + "node /usr/local/node-v24.21.0-linux-arm64/lib/node_modules/@deepseek-ai/dsh/node_modules/@deepseek-ai/dsh-subprocess-local/scripts/ensure-spawn-helper.mjs >/dev/null 2>&1 || true; true", log);
            // 5. 校验
            StringBuilder why = new StringBuilder();
            boolean ok = verifyDsh(version, why);
            log.onLog("[版本] 校验: " + (ok ? "通过" : "失败（" + why + "）"));
            // 6. 失败回滚
            if (!ok) {
                log.onLog("[版本] 回滚到旧版本…");
                deleteTree(dshDir);
                if (bakDir.exists()) bakDir.renameTo(dshDir);
                log.onLog("[版本] 已回滚，当前版本未变");
                return false;
            }
            // 7. 成功：清理备份 + 清空 webpatch（下次启动重转译）
            deleteTree(bakDir);
            deleteTree(new File("/storage/emulated/0/DEH/webpatch"));
            log.onLog("[版本] 已切换到 " + version + "（兼容前端已清空，重启后自动重新转译）");
            return true;
        } catch (Throwable t) {
            log.onLog("[版本] 安装异常: " + t);
            // 异常也回滚
            try {
                if (dshDir.exists()) deleteTree(dshDir);
                if (bakDir.exists()) bakDir.renameTo(dshDir);
            } catch (Throwable ignored) {}
            return false;
        }
    }

    /** 执行命令并返回 stdout（通过临时文件捕获）。 */
    public String runProotCapture(String cmd, int timeoutMs) {
        try {
            File out = new File("/storage/emulated/0/DEH/.probe-out.txt");
            if (out.exists()) out.delete();
            runProot(cmd + " > /storage/emulated/0/DEH/.probe-out.txt 2>&1", null);
            if (!out.isFile()) return null;
            byte[] data = new byte[(int) out.length()];
            java.io.FileInputStream in = new java.io.FileInputStream(out);
            int n = in.read(data);
            in.close();
            return new String(data, 0, n, "UTF-8");
        } catch (Throwable t) {
            return null;
        }
    }

    private void autoUpdateAsync(final LogListener log) {
        if (!Prefs.autoUpdate(context)) {
            log.onLog("[更新] 自动更新已关闭");
            return;
        }
        Thread thread = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    log.onLog("[更新] 检查 DSH 新版本...");
                    String script = "npm config delete disturl >/dev/null 2>&1 || true; "
                            + "npm config set fund false >/dev/null 2>&1 || true; "
                            + "npm config set update-notifier false >/dev/null 2>&1 || true; "
                            + "npm config set audit false >/dev/null 2>&1 || true; "
                            + "echo '[维护] 清理 npm 废弃配置完成'; "
                            + "echo '[维护] 更新系统包（apt upgrade，可能较慢）...'; "
                            + "apt-get update >/dev/null 2>&1; "
                            + "apt-get -y upgrade 2>&1 | tail -3; "
                            + "echo '[维护] 检查 pnpm...'; "
                            + "npm i -g pnpm@latest 2>&1 | tail -2; "
                            + "echo '[维护] 检查 DSH...'; "
                            + "if [ -d /storage/emulated/0/DEH/webpatch ] && [ -n \"$(ls -A /storage/emulated/0/DEH/webpatch 2>/dev/null)\" ]; then echo '[更新] 已存在兼容前端，跳过 DSH 更新以保持前端一致'; else "
                            + "latest=$(npm view @deepseek-ai/dsh version 2>/dev/null | tail -1); "
                            + "current=$(dsh --version 2>/dev/null | tail -1); "
                            + "if [ -n \"$latest\" ] && [ \"$latest\" != \"$current\" ]; then "
                            + "  echo \"[更新] 发现新版本 $latest（当前 $current），开始更新...\"; "
                            + "  npm config set allow-scripts \"@deepseek-ai/dsh-subprocess-local,koffi,node-pty,@google/genai,protobufjs\" --location=user >/dev/null 2>&1 || true; "
                            + "  npm i -g @deepseek-ai/dsh@latest 2>&1 | tail -5; "
                            + "  node /usr/local/node-v24.21.0-linux-arm64/lib/node_modules/@deepseek-ai/dsh/node_modules/@deepseek-ai/dsh-subprocess-local/scripts/ensure-spawn-helper.mjs >/dev/null 2>&1 || true; "
                            + "  echo \"[更新] 完成，下次启动生效\"; "
                            + "else echo \"[更新] DSH 已是最新版本 $current\"; fi; fi; "
                            + "node /usr/local/node-v24.21.0-linux-arm64/lib/node_modules/@deepseek-ai/dsh/node_modules/@deepseek-ai/dsh-subprocess-local/scripts/ensure-spawn-helper.mjs >/dev/null 2>&1 || true; "
                            + "echo '[维护] 完成'";
                    runProot(script, log);
                    // npm 更新 DSH 会覆盖前端 dist → 重新应用兼容前端（幂等）
                    try {
                        patchDshFrontend(log);
                    } catch (Throwable t2) {
                        log.onLog("[兼容] 维护后重新应用前端失败: " + t2);
                    }
                } catch (Throwable t) {
                    log.onLog("[更新] 检查失败: " + t);
                }
            }
        }, "dsh-auto-update");
        thread.setDaemon(true);
        thread.start();
    }

    public Process startHost(final LogListener log, final UrlListener urlListener) throws Exception {
        if (hostProcess != null && hostProcess.isAlive()) {
            log.onLog("[启动] 检测到 DSH 进程已在运行，复用现有实例");
            if (lastUrl != null && !lastUrl.isEmpty()) {
                urlListener.onUrl(lastUrl);
            }
            return hostProcess;
        }
        log.onLog("[启动] 准备 DSH 环境...");
        repairDshIfBroken(log);
        killStaleHosts(log);
        // 端口占用兜底：用 Shizuku shell 权限 + 容器内 pkill 杀旧的 dsh web 进程
        try {
            if ("granted".equals(ShizukuBridge.status())) {
                ShizukuBridge.run("pkill -f 'dsh/lib/bin.js web' 2>/dev/null; sleep 1; true", 15000);
                log.onLog("[启动] 已用 Shizuku 清理旧 dsh 进程");
            }
        } catch (Throwable t) {
            log.onLog("[启动] Shizuku 清理跳过: " + t);
        }
        try {
            runProot("pkill -f 'dsh/lib/bin.js web' 2>/dev/null; sleep 1; true", log);
            log.onLog("[启动] 已用容器内 pkill 清理旧 dsh 进程");
        } catch (Throwable t) {
            log.onLog("[启动] 容器内清理跳过: " + t);
        }
        ensureRuntimeHealth(log);
        patchDshFrontend(log);
        // 自动更新已移除：用户可在设置里自主选择 DSH 版本
        String killOld = "for p in /proc/[0-9]*; do pid=${p#/proc/}; [ \"$pid\" = \"$$\" ] && continue; cmd=$(tr '\\0' ' ' < \"$p/cmdline\" 2>/dev/null); case \"$cmd\" in node\\ *dsh/lib/bin.js\\ web*) kill -9 \"$pid\" 2>/dev/null;; esac; done; sleep 1; ";
        String prep = "[ -x /usr/local/node-v24.21.0-linux-arm64/bin/pnpm ] || npm i -g pnpm >/dev/null 2>&1; [ -x /usr/bin/adb ] || apt-get install -y android-tools-adb >/dev/null 2>&1; ";
        String command = killOld + prep + "node " + DSH_BIN + " web --host 127.0.0.1 --port 38600 --no-open < /dev/null; echo '[dsh] exit=' $?";
        ProcessBuilder builder = prootBuilder(command);
        builder.redirectErrorStream(true);
        final Process process = builder.start();
        hostProcess = process;

        final AtomicBoolean urlSent = new AtomicBoolean(false);
        Thread reader = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), "UTF-8"));
                    String line;
                    while ((line = reader.readLine()) != null) {
                        log.onLog("[dsh] " + line);
                        if (!urlSent.get()) {
                            Matcher m = URL_PATTERN.matcher(line);
                            if (m.find()) {
                                urlSent.set(true);
                                lastUrl = m.group(1);
                                urlListener.onUrl(m.group(1));
                            }
                        }
                    }
                } catch (Exception e) {
                    log.onLog("[dsh] 读取输出失败: " + e.getMessage());
                }
            }
        }, "dsh-host-reader");
        reader.setDaemon(true);
        reader.start();

        Thread watchdog = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    Thread.sleep(45000);
                    if (!urlSent.get()) log.onLog("[dsh] 启动较慢（45 秒未检测到地址），继续等待...");
                } catch (InterruptedException ignored) {
                }
            }
        }, "dsh-startup-watch");
        watchdog.setDaemon(true);
        watchdog.start();
        return process;
    }

    public boolean isHostRunning() {
        return hostProcess != null && lastUrl != null;
    }

    public void stopHost() {
        if (hostProcess != null) {
            hostProcess.destroy();
            hostProcess = null;
        }
    }

    /** 宿主层清理：杀同 uid 的 node/bash 旧进程（不依赖 cmdline 读取权限）。 */
    private void killStaleHosts(LogListener log) {
        diagLine("killStaleHosts start");
        try {
            File[] procs = new File("/proc").listFiles();
            if (procs == null) return;
            int self = android.os.Process.myPid();
            int myUid = android.os.Process.myUid();
            for (File proc : procs) {
                String name = proc.getName();
                if (!name.matches("\\d+")) continue;
                int pid;
                try {
                    pid = Integer.parseInt(name);
                } catch (Exception e) {
                    continue;
                }
                if (pid == self) continue;
                try {
                    byte[] commBytes = new byte[64];
                    java.io.FileInputStream commIn = new java.io.FileInputStream(new File(proc, "comm"));
                    int commLen = commIn.read(commBytes);
                    commIn.close();
                    String comm = commLen > 0 ? new String(commBytes, 0, commLen, "UTF-8").trim() : "";
                    if (!"node".equals(comm)) continue;
                    // 只清理 dsh web 进程，避免误杀自检/更新等 bash 进程
                    byte[] cmdBytes = new byte[512];
                    java.io.FileInputStream cmdIn = new java.io.FileInputStream(new File(proc, "cmdline"));
                    int cmdLen = cmdIn.read(cmdBytes);
                    cmdIn.close();
                    String cmdline = cmdLen > 0 ? new String(cmdBytes, 0, cmdLen, "UTF-8") : "";
                    if (!cmdline.contains("dsh/lib/bin.js") || !cmdline.contains("web")) continue;

                    boolean sameUid = false;
                    java.io.BufferedReader reader = new java.io.BufferedReader(new java.io.InputStreamReader(
                            new java.io.FileInputStream(new File(proc, "status")), "UTF-8"));
                    String line;
                    while ((line = reader.readLine()) != null) {
                        if (line.startsWith("Uid:")) {
                            String[] parts = line.trim().split("\\s+");
                            if (parts.length > 1 && String.valueOf(myUid).equals(parts[1])) sameUid = true;
                            break;
                        }
                    }
                    reader.close();
                    if (sameUid) {
                        log.onLog("[启动] 清理旧进程 pid=" + pid + " (" + comm + ")");
                        diagLine("kill pid=" + pid + " comm=" + comm);
                        try {
                            android.os.Process.killProcess(pid);
                        } catch (Throwable ignored) {
                        }
                    }
                } catch (Throwable ignored) {
                }
            }
            Thread.sleep(800);
            diagLine("killStaleHosts done");
        } catch (Throwable ignored) {
        }
    }

    private static void diagLine(String message) {
        try {
            File dir = new File("/storage/emulated/0/DEH");
            if (!dir.exists()) dir.mkdirs();
            java.io.FileOutputStream out = new java.io.FileOutputStream(new File(dir, "diag.log"), true);
            String line = new java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.US).format(new java.util.Date())
                    + " [runtime] " + message + "\n";
            out.write(line.getBytes("UTF-8"));
            out.close();
        } catch (Throwable ignored) {
        }
    }

    private void ensurePnpm(LogListener log) throws Exception {
        File pnpm = new File(rootfs, "usr/local/" + NODE_DIR + "/bin/pnpm");
        if (!pnpm.exists()) {
            log.onLog("[启动] 未检测到 pnpm，正在安装...");
            int code = runProot("npm i -g pnpm", log);
            if (code != 0) throw new IOException("安装 pnpm 失败（退出码 " + code + "）");
            log.onLog("[启动] pnpm 安装完成");
        }
    }

    private void ensureAdb(LogListener log) throws Exception {
        writeAdbHelper();
        File adb = new File(rootfs, "usr/bin/adb");
        if (!adb.exists()) {
            log.onLog("[adb] 未检测到 adb，正在安装（约 30 秒）...");
            int code = runProot("apt-get install -y android-tools-adb", log);
            if (code != 0) log.onLog("[adb] 安装失败（退出码 " + code + "）");
        }
    }

    private void writeInitScript() throws IOException {
        File script = new File(rootfs, "root/.dsh-init.sh");
        script.getParentFile().mkdirs();
        StringBuilder sb = new StringBuilder();
        sb.append("#!/bin/bash\n");
        sb.append("set -e\n");
        sb.append("export PATH=" + GUEST_NODE_BIN + ":/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin\n");
        sb.append("export HOME=/root\n");
        sb.append("export DEBIAN_FRONTEND=noninteractive\n");
        sb.append("echo 'nameserver 223.5.5.5' > /etc/resolv.conf\n");
        sb.append("echo 'nameserver 8.8.8.8' >> /etc/resolv.conf\n");
        sb.append("sed -i 's|http://archive.ubuntu.com/ubuntu/|http://mirrors.aliyun.com/ubuntu/|g; s|http://security.ubuntu.com/ubuntu/|http://mirrors.aliyun.com/ubuntu/|g' /etc/apt/sources.list.d/ubuntu.sources 2>/dev/null || true\n");
        sb.append("sed -i 's|http://archive.ubuntu.com/ubuntu|http://mirrors.aliyun.com/ubuntu|g; s|http://security.ubuntu.com/ubuntu|http://mirrors.aliyun.com/ubuntu|g' /etc/apt/sources.list 2>/dev/null || true\n");
        sb.append("apt-get update\n");
        sb.append("apt-get -y upgrade || true\n");
        sb.append("apt-get install -y --no-install-recommends build-essential python3 ca-certificates curl git android-tools-adb\n");
        sb.append("npm config set registry https://registry.npmmirror.com\n");
        sb.append("unset npm_config_disturl 2>/dev/null || true\n");
        sb.append("npm config set allow-scripts \"@deepseek-ai/dsh-subprocess-local,koffi,node-pty,@google/genai,protobufjs\" --location=user 2>/dev/null || true\n");
        sb.append("npm i -g @deepseek-ai/dsh\n");
        sb.append("DSHDIR=/usr/local/node-v24.21.0-linux-arm64/lib/node_modules/@deepseek-ai/dsh\n");
        sb.append("if [ ! -f $DSHDIR/node_modules/semver/internal/re.js ]; then echo '[fix] semver 不完整，重新安装'; npm install --prefix $DSHDIR semver@7 --force 2>&1 | tail -2; fi\n");
        sb.append("if [ ! -f $DSHDIR/node_modules/semver/internal/re.js ]; then echo '[fix] semver 仍缺失，尝试全量重装'; npm i -g @deepseek-ai/dsh --force 2>&1 | tail -3; fi\n");
        sb.append("node /usr/local/node-v24.21.0-linux-arm64/lib/node_modules/@deepseek-ai/dsh/node_modules/@deepseek-ai/dsh-subprocess-local/scripts/ensure-spawn-helper.mjs 2>/dev/null || true\n");
        sb.append("npm i -g pnpm\n");
        sb.append("echo '[DSH-INIT] 安装完成'\n");
        writeText(script, sb.toString());
        script.setExecutable(true, false);
    }

    private void writeAdbHelper() throws IOException {
        File script = new File(rootfs, "root/adb-connect.sh");
        script.getParentFile().mkdirs();
        StringBuilder sb = new StringBuilder();
        sb.append("#!/bin/bash\n");
        sb.append("export PATH=" + GUEST_NODE_BIN + ":/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin\n");
        sb.append("export HOME=/root\n");
        sb.append("adb start-server >/dev/null 2>&1\n");
        sb.append("if [ -n \"$1\" ]; then\n");
        sb.append("  pair_port=$(adb mdns services 2>/dev/null | grep '_adb-tls-pairing' | grep -o '127\\.0\\.0\\.1:[0-9]*' | head -n1)\n");
        sb.append("  if [ -n \"$pair_port\" ]; then adb pair \"$pair_port\" \"$1\"; else echo '[adb] 未发现配对服务，请确认已开启无线调试'; fi\n");
        sb.append("fi\n");
        sb.append("connect_port=$(adb mdns services 2>/dev/null | grep '_adb-tls-connect' | grep -o '127\\.0\\.0\\.1:[0-9]*' | head -n1)\n");
        sb.append("if [ -n \"$connect_port\" ]; then adb connect \"$connect_port\"; else adb connect 127.0.0.1:5555; fi\n");
        sb.append("adb devices\n");
        writeText(script, sb.toString());
        script.setExecutable(true, false);
    }

    private void selfCheck(LogListener log) {
        log.onLog("[CHECK] rootfs bash exists=" + new File(rootfs, "bin/bash").exists());
        log.onLog("[CHECK] proot loader exists=" + new File(bin, "loader").exists());
    }

    public int runProot(String command, LogListener log) throws Exception {
        ProcessBuilder builder = prootBuilder(command);
        builder.redirectErrorStream(true);
        Process process = builder.start();
        BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), "UTF-8"));
        String line;
        while ((line = reader.readLine()) != null) log.onLog(line);
        return process.waitFor();
    }

    private ProcessBuilder prootBuilder(String command) {
        List<String> cmd = new ArrayList<String>();
        String nativeDir = context.getApplicationInfo().nativeLibraryDir;
        File proot = new File(nativeDir, "libproot.so");
        File loader = new File(nativeDir, "libprootloader.so");

        cmd.add(proot.getAbsolutePath());
        cmd.add("-0");
        cmd.add("--link2symlink");
        cmd.add("-r");
        cmd.add(rootfs.getAbsolutePath());
        cmd.add("-b");
        cmd.add("/dev");
        cmd.add("-b");
        cmd.add("/proc");
        cmd.add("-b");
        cmd.add("/sys");
        if (new File("/storage").exists()) {
            cmd.add("-b");
            cmd.add("/storage:/storage");
        }
        if (new File("/storage/emulated/0").exists()) {
            cmd.add("-b");
            cmd.add("/storage/emulated/0:/sdcard");
        }
        if ("private".equals(Prefs.workRootMode(context))) {
            cmd.add("-b");
            cmd.add(new File(context.getFilesDir(), "work").getAbsolutePath() + ":/work");
        }
        cmd.add("-w");
        cmd.add(guestWorkRoot);
        cmd.add("/usr/bin/env");
        cmd.add("-i");
        cmd.add("HOME=" + guestHome);
        cmd.add("DSH_HOME=/root/.dsh");
        cmd.add("TERM=xterm-256color");
        cmd.add("DISPLAY=:0");
        cmd.add("WAYLAND_DISPLAY=wayland-0");
        cmd.add("PATH=" + GUEST_NODE_BIN + ":/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin");
        cmd.add("/bin/bash");
        cmd.add("-l");
        cmd.add("-c");
        cmd.add(command);

        ProcessBuilder builder = new ProcessBuilder(cmd);
        builder.directory(bin);
        Map<String, String> env = builder.environment();
        env.put("LD_LIBRARY_PATH", nativeDir);
        env.put("PROOT_TMP_DIR", tmp.getAbsolutePath());
        env.put("PROOT_NO_SECCOMP", "1");
        env.put("PROOT_LOADER", loader.getAbsolutePath());
        env.put("DISPLAY", ":0");
        env.put("WAYLAND_DISPLAY", "wayland-0");
        return builder;
    }

    private void extractAsset(String assetName, File dest, LogListener log) throws Exception {
        InputStream in = null;
        try {
            in = context.getAssets().open(assetName);
            TarGzExtractor.extract(in, dest, new TarGzExtractor.ProgressListener() {
                @Override
                public void onFile(int count, long bytes) {
                }
            });
        } finally {
            if (in != null) in.close();
        }
    }

    private void copyAsset(String assetName, File dest) throws Exception {
        InputStream in = context.getAssets().open(assetName);
        try {
            dest.getParentFile().mkdirs();
            OutputStream out = new FileOutputStream(dest);
            try {
                byte[] buf = new byte[65536];
                int read;
                while ((read = in.read(buf)) >= 0) out.write(buf, 0, read);
            } finally {
                out.close();
            }
        } finally {
            in.close();
        }
    }

    private static void writeText(File file, String text) throws IOException {
        OutputStream out = new FileOutputStream(file);
        try {
            out.write(text.getBytes("UTF-8"));
        } finally {
            out.close();
        }
    }

    public static void deleteRecursively(File file) {
        if (file == null || !file.exists()) return;
        File[] children = file.listFiles();
        if (children != null) {
            for (File child : children) deleteRecursively(child);
        }
        file.delete();
    }
}
