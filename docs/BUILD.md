# 构建指南

## 环境要求

| 组件 | 版本/说明 |
|---|---|
| Android SDK build-tools | 29.0.3+（需 aapt2、zipalign、apksigner、aidl） |
| Android platform jar | android-34（`$ANDROID_HOME/platforms/android-34/android.jar`） |
| JDK | 17（javac） |
| d8 | build-tools 里的 `lib/d8.jar`（或独立 r8） |
| Python 3 | 用于 APK 打包/合并 |

本仓库的 `scripts/build.sh` 默认路径面向开发者本地环境，请按需修改顶部变量：

```bash
BT=/usr/lib/android-sdk/build-tools/29.0.3        # build-tools 目录
PLATFORM=/root/toolchain/platform/android-34/android.jar
D8_JAR=/root/toolchain/build-tools/android-14/lib/d8.jar
# ...
```

## 依赖准备

### 1. Shizuku API（12.2.0）
从 Maven 下载三个 jar（aar 解压取 classes.jar）：
- `dev.rikka.shizuku:api:12.2.0` → `unzip-api/classes.jar`
- `dev.rikka.shizuku:provider:12.2.0` → `unzip-provider/classes.jar`
- `dev.rikka.shizuku:shared:12.2.0` → `unzip-shared/classes.jar`

### 2. Shizuku server 类（moe.shizuku.server.*）
`ShizukuProvider` 运行时需要 `moe.shizuku.server.IShizukuApplication` 等 16 个 AIDL 类。
这些类**不在** Maven AAR 中，需从 ShizukuRunner 示例 APK 提取：
1. 用 apktool 反编译示例 APK
2. 将 `smali/moe/shizuku/server/` 下 16 个类汇编成 dex
3. 作为 `d8` 输入之一（`server.dex`）

本仓库不含该二进制（第三方提取物）。

### 3. Dhizuku API
Maven：`io.github.iamr0s:Dhizuku-API:2.5.2` → `classes.jar`（本站已含 `com.rosan.dhizuku.*` 33 类）。

### 4. DSH 运行时资源
`android-app/app/assets/runtime/runtime.tar.gz` 是手机内解压的 proot 运行时（Debian/Ubuntu rootfs + Node.js + DSH）。
本仓库不包含（85MB），请自行打包：
```
tar czf runtime.tar.gz rootfs-skeleton/
```
结构要求见 `Runtime.java`（解压到应用私有目录）。

### 5. 签名
准备自己的 keystore，`build.sh` 中修改：
```bash
KS=/path/to/your.keystore
KS_PASS=yourpass
KEY_ALIAS=youralias
```
**切勿提交签名密钥**。

## 构建步骤

```bash
# 1. 准备依赖（见上）
# 2. 修改 scripts/build.sh 里的路径变量
# 3. 构建
bash scripts/build.sh
# 产物：dist/DeepSeek-Harness.apk
```

## 版本策略

- `versionCode` / `versionName` 固定（本项目 15 / 3.0-stable），更新内容写在**文件名**里。
- 覆盖安装：同 versionCode + 同签名 → 数据保留。

## 注意

- APK 内含 proot 运行时，体积约 85MB 属正常。
- 首次安装需要申请存储权限（Android 11+ 需「所有文件访问」）。
