# FastBug POC

安卓平板一键捕获缺陷的本地 POC：`android-agent` 负责录屏与浮窗触发，`collector` 通过 USB ADB 收集截图、日志和元数据，并生成本地证据包。

详见 [POC 实施计划](安卓平板一键捕获云效缺陷草稿_POC实施计划.md)。

## 快速启动

1. 使用 Android Studio 打开 `android-agent`，安装 `app` 到测试平板。
2. 在 Windows 电脑执行 `node collector/index.js start --serial <设备序列号> --package <被测包名>`。
3. 按 Collector 输出的命令把会话 ID 写入 Agent，打开 Agent 后授权录屏和悬浮窗，点击“开始采集”。
4. 平板点击“报缺陷”。证据包位于 `collector-data/captures/<capture_id>/`。

只要 Agent 与 Collector 位于 USB ADB 反向映射链路中，不需要测试网络互通。

