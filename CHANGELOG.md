# Changelog

## [3.0-stable-hotfix-2] - 2026-11-06

### Fixed
- 修复 npm 安装中断导致 `semver` 缺 `internal/re.js`（启动报 `Cannot find module './internal/re'`）
  - `.dsh-init.sh` 安装完成后校验 `semver/internal/re.js`，缺失自动重装
  - 启动自检 `repairDshIfBroken()` 增加 semver 完整性检查，缺失自动修复

### Improved
- **DSH 版本自选**大幅优化：
  - 版本列表 24h 本地缓存（离线可用），超时 60s → 30s，网络失败自动回退旧缓存
  - 安装前预检：磁盘 ≥2GB、registry 连通性
  - 安装原子化：先备份当前版本（`dsh.bak`），失败自动回滚
  - 安装后校验：`lib/bin.js` + `semver/internal/re.js` + `dsh-app-boot` + `dsh --version`
  - 安装进度对话框 + 成功/失败结果弹窗（失败明确提示"已回滚"）
- 构建脚本支持 `PRIVATE=1` 私用版（`assets/private/` 不进仓库）
- APK 输出自动命名区分「开源版 / 私用版」

### Build
- 新增 `.gitignore`（排除 `assets/private/`、`out/`、`gen/`、`*.apk`）
