# 权限集成说明

## Shizuku

- 依赖 `dev.rikka.shizuku:api:12.2.0` + `provider:12.2.0` + 从 ShizukuRunner 提取的 `moe.shizuku.server.*`
- 初始化：`ShizukuProvider` 由系统拉起 → 通过 binder 收到 Shizuku 服务
- 执行命令：`Shizuku.newProcess(new String[]{"sh","-c",cmd}, null, null)` → 读流 → `waitFor()`
- 授权：`Shizuku.requestPermission(code)` → Shizuku 授权弹窗
- 清单声明：
  ```xml
  <provider android:name="rikka.shizuku.ShizukuProvider"
      android:authorities="com.deh.mobile.shizuku"
      android:permission="android.permission.INTERACT_ACROSS_USERS_FULL"
      android:exported="true" android:multiprocess="false" />
  <meta-data android:name="moe.shizuku.client.V3_SUPPORT" android:value="true" />
  <meta-data android:name="moe.shizuku.client.V3_API_VERSION" android:value="4.2.0" />
  ```
- 注意：Shizuku 13.x 需要 `V3_API_VERSION` 声明，否则 binder 传输不稳定。

## Dhizuku

- 依赖 `io.github.iamr0s:Dhizuku-API:2.5.2`（`com.rosan.dhizuku.api.*` + AIDL）
- 初始化：`Dhizuku.init(context)` → 通过 provider（`com.rosan.dhizuku.server.provider`）获取服务
- 授权：`Dhizuku.requestPermission(listener)` → Dhizuku 授权弹窗
- 执行：`Dhizuku.newProcess(String[], String[], File)`（Dhizuku UserService 进程身份）
- Device Owner 能力：`Dhizuku.binderWrapper(IBinder)` / `Dhizuku.remoteTransact(...)` 包装系统服务
- 清单：`<uses-permission android:name="com.rosan.dhizuku.permission.API" />` + `<queries><package android:name="com.rosan.dhizuku" /></queries>`

## 本地桥（LocalBridge）

监听 `127.0.0.1:38620`，token 存在 `.shizuku-token`：

| 端点 | 说明 |
|---|---|
| `/status` | Shizuku 状态 |
| `/run?cmd=` | Shizuku 执行命令 |
| `/open?path=` | 打开文件（MT 风格面板） |
| `/dhizuku` | Dhizuku 状态 + info |
| `/dhizuku-run?cmd=` | Dhizuku 执行命令 |

## 文件打开面板

- 同时用 `file://` 和 `content://` 查询 `ACTION_VIEW` 应用（第三方应用大多注册 file://）
- 按包名去重
- 长按图标 → 按 MIME 记忆默认打开应用（下次直接打开）
- 参考 MT 管理器：标题「打开方式…」+ 类型筛选 + 4 列图标网格
