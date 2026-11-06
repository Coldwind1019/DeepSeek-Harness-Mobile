# DeepSeek Harness Mobile · Android App

在 Android 手机上本地运行 DeepSeek Harness 的独立版应用。

本目录是 Android 应用源码（Java），通过 `build.sh` 构建 APK：

```bash
bash build.sh              # 开源版
PRIVATE=1 bash build.sh    # 私用版（需要 assets/private/）
```

依赖：Android SDK build-tools（aapt2/d8/apksigner）、JDK 17+。

构建产物输出到 `/storage/emulated/0/DEH/交付目录/`。

许可：见仓库根 LICENSE。
