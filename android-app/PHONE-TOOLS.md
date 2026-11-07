# 手机能力工具（phone-tools.sh）

召唤式（AI 主动调用），命令即用。需 DSH App 运行 + 相关授权。

## 画面
- `shot`                截图 → PNG 路径
- `see N [fps]`         连续 N 秒画面 → 变化帧筛选 → 拼图（see-now 安卓版）
- `ui`                  读 UI 树（JSON：文本/坐标/可点击）

## 声音（召唤式，时长 AI 定）
- `asr N`               录 N 秒 → 离线转写（一次性）
- `asrlive N`           实时增量：逐段返回带时间戳的文本
- `listen N`            录 N 秒 → WAV（不转写）

## 组合
- `observe N [fps]`     并行采集：音频转写 + 画面拼图 + UI 树（时间对齐）

## 使用数据
- `usage [小时]`        最近 N 小时 App 使用排行（需「使用情况访问」权限）

## 操作
- `click "文本"` / `click X Y`   点按
- `type "文本"`                   输入（ASCII）
- `swipe x1 y1 x2 y2 [ms]`        滑动
- `shell "cmd"`                   Shizuku shell

## 中文输入（ADBKeyboard 内置）
```
ime set com.deh.mobile/.ime.AdbIME        # 切到内置输入法
am broadcast -a ADB_INPUT_B64 --es msg "$(echo -n '中文' | base64)"
ime set com.sohu.inputmethod.sogou/.SogouIME   # 切回搜狗
```

## 前提
- DSH App 运行中（LocalBridge 127.0.0.1:38620）
- Shizuku 已授权（截图/shell/操作）
- 首次需授权内录（MediaProjection）+ 使用情况访问（usage）
