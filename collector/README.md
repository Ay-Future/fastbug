# Collector

运行命令：

```powershell
.\collector\start.ps1 -Serial JYTC656A251222000569 -Package com.example.target
```

启动脚本会从 Windows 用户环境变量加载并检查 `YUNXIAO_TOKEN`、`FASTBUG_OSS_ENDPOINT`、`FASTBUG_OSS_ACCESS_KEY_ID` 与 `FASTBUG_OSS_ACCESS_KEY_SECRET`。缺少任何一项时，Collector 不会启动。

Collector 会：

- 建立 `adb reverse tcp:52741 tcp:52741`；
- 持续保留最多 12 MiB 的 `logcat -v epoch`；
- 收到平板即时触发事件时采集截图、UI XML、窗口、设备与 App 信息；
- 收到 10 秒录屏收尾事件时拉取 `replay.mp4` 与原始 MP4 片段，写出带 SHA-256 的 `manifest.json`。

证据保存在项目同级目录 `../collector-data/captures/`。Agent 会在平板端无重编码拼接出 `replay.mp4`，但原始片段也会保留，便于验证拼接质量和排障。

## 云效（准备阶段）

将 `collector/yunxiao.config.example.json` 复制为 `../collector-data/yunxiao.config.json`，填写本企业云效的服务接入点、项目空间、缺陷类型和默认负责人。令牌**不写入文件**，仅在启动 Collector 的终端会话设置 `YUNXIAO_TOKEN` 环境变量。

目前适配器仅创建人工确认后的云效工作项；录像和日志附件将在对象存储或云效附件上传链路确认后再接入。
