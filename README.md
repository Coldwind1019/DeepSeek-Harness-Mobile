# DeepSeek Harness Mobile

**在 Android 手机上本地运行 [DeepSeek Harness](https://github.com/deepseek-ai/dsh) 的独立版应用**（无需电脑、无需远程服务器）。

[English](#english) | 中文

---

## 简介

DeepSeek Harness Mobile 把 DeepSeek Harness 的完整 Web UI 搬进 Android 手机：

- 内置 **proot Ubuntu 容器** + Node.js 运行时，手机上直接跑 `dsh web`
- 用 **WebView** 打开本机 `127.0.0.1` 的 DSH 界面
- 集成 **Shizuku**：让 DSH 里的 agent 获得 shell 权限（截屏、点击、系统命令）
- 集成 **Dhizuku**：Device Owner 级能力（设备策略、静默安装等）
- 内置 **ADB**：容器内直接 `adb` 命令，支持无线调试配对/连接，控制其他 Android 设备
- 内置 **构建工具链**：aapt/javac/d8/apksigner，可在手机内编译 APK
- 文件"打开方式"面板（仿 MT 管理器：长按设为默认打开应用）

## 功能特性

| 功能 | 说明 |
|---|---|
| 本地运行 | proot + Node.js + DSH，全部在手机内 |
| 双工作区 | 内部存储 `/storage/emulated/0/DEH` 或应用私有目录 |
| Shizuku 权限 | shell 级命令执行（uid=2000），支持 `pm`/`settings`/`dumpsys`/截图/输入 |
| Dhizuku 权限 | Device Owner 代理授权（通过官方 Dhizuku SDK） |
| 文件打开面板 | 仿 MT 管理器：类型筛选、4 列图标网格、长按设为默认打开方式 |
| **内置 ADB** | 容器内 `adb`（android-tools-adb 34.x），`adb-connect.sh` 自动扫描无线调试端口完成配对/连接 |
| **内置构建链** | aapt / javac / d8 / apksigner，可在手机内直接编译打包 APK |
| **自动维护** | 启动自检修复原生模块（allow-scripts / node-pty spawn-helper）；自动检查并升级 DSH 新版本（设置内可关） |
| 缩放控制 | 桌面视图缩放 50%–150% 滑条 |
| 通知栏入口 | 运行中从通知栏打开设置 |
| 前台服务 | 切后台保持运行 |
| 文件上传 | WebView 文件选择器（系统文件选择器） |
| 崩溃日志 | `/storage/emulated/0/DEH/crash.log` |

## 架构

```
Android App (Java)
├── MainActivity        WebView + 启动界面 + 设置
├── Runtime             proot 容器管理 + 环境准备 + 启动 dsh web
├── LocalBridge         127.0.0.1:38620 本地桥（/status /run /open /dhizuku ...）
├── ShizukuBridge       Shizuku 集成（newProcess）
├── DhizukuBridge       Dhizuku 集成（官方 SDK）
├── SimpleFileProvider  文件共享 provider
├── DshService          前台服务（保活 + 通知设置入口）
└── DshApp / Prefs / TarGzExtractor / ShellService

运行时还内置：android-tools-adb（`/usr/bin/adb`）、`/root/adb-connect.sh`（无线调试配对脚本）、
build-essential / python3 / git（手机内构建环境）。
```

## 自动维护与排障

- 启动时自动执行健康自检：写入 npm `allow-scripts` 配置、修复 `node-pty` 的 spawn-helper 执行位。npm 11 默认拦截 install scripts，这一步保证 DSH 的原生依赖可用。
- 自动检查 `@deepseek-ai/dsh` 新版本并在后台升级（下次启动生效），设置里可关闭。
- 若启动白屏：检查日志中是否出现 `install-scripts` 警告或 `dsh exit=1`，本项目的自检会在下次启动自动修复。

## 构建

详见 [docs/BUILD.md](docs/BUILD.md)。简要步骤：

1. 准备 Android SDK（build-tools 29.0.3+、platform android-34）
2. 准备 Java 依赖：
   - Shizuku API 12.2.0（Maven: `dev.rikka.shizuku:api:12.2.0`、`provider:12.2.0`、`shared:12.2.0`）
   - Dhizuku API（Maven: `io.github.iamr0s:Dhizuku-API:2.5.2`）
   - Shizuku server 类（`moe.shizuku.server.*`，从 ShizukuRunner 示例 APK 提取，本仓库不含，见 docs/BUILD.md）
3. 准备运行时资源 `android-app/app/assets/runtime/`（DSH 运行时压缩包，见 docs/BUILD.md）
4. 执行 `scripts/build.sh`（需按环境修改变量路径）

> ⚠️ 本仓库**不包含**：签名密钥（请用自己的 keystore）、DSH 运行时 tarball（85MB）、Shizuku server 二进制（需从 ShizukuRunner 示例提取）、MT 管理器相关代码（已从本项目移除）。这些都需自行准备。

## 版本

- versionCode: `15`
- versionName: `3.0-stable`
- 包名: `com.deh.mobile`
- targetSdk 33 / minSdk 26

## 依赖与致谢

- [DeepSeek Harness](https://github.com/deepseek-ai/dsh) — 本体
- [Shizuku](https://github.com/RikkaApps/Shizuku) — shell 权限桥
- [Dhizuku](https://github.com/iamr0s/Dhizuku) / [Dhizuku-API](https://github.com/iamr0s/Dhizuku-API) — Device Owner 桥
- proot / proot-distro — 用户态容器
- Node.js — JS 运行时

## 免责声明

本项目为个人开发的第三方工具，**非 DeepSeek 官方项目**。DeepSeek Harness、Shizuku、Dhizuku、MT 管理器等名称与商标归各自作者所有。使用本软件造成的任何损失由使用者自行承担。

## 许可证

[MIT](LICENSE)

---

## English

**DeepSeek Harness Mobile** — run DeepSeek Harness entirely on your Android phone.

- Bundled proot Ubuntu container + Node.js, runs `dsh web` locally
- WebView frontend connected to `127.0.0.1`
- [Shizuku](https://github.com/RikkaApps/Shizuku) integration for shell-level commands
- [Dhizuku](https://github.com/iamr0s/Dhizuku-API) integration for Device Owner capabilities
- MT-style "open with" panel with long-press default app
- Zoom slider (50%–150%), notification settings entry, foreground service

Build instructions: [docs/BUILD.md](docs/BUILD.md). Licensed under MIT.

> This repo does not include signing keys, the DSH runtime tarball, or extracted third-party binaries.
