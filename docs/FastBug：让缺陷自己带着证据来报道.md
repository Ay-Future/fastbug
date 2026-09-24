# FastBug：让缺陷自己带着证据来报道

> 一次「报缺陷」不该是打开聊天窗口、回忆复现步骤、到处找截图、再问一句“刚才的日志还在吗？”的耐力赛。FastBug 的目标很朴素：出问题时按一下按钮，让证据先到场，文字随后慢慢补。

测试现场最常见的悲剧，大概是这样的：问题刚刚出现，测试同学还没来得及截图，它已经像从未存在过一样消失；日志在滚；复现路径在脑内蒸发；最后只剩一句充满哲学意味的“刚才明明有问题”。

FastBug 是一个面向安卓平板的本地 POC，专门把这段混乱时刻固定下来。它不要求平板和电脑互通测试网络，只要一根正常工作的 USB 数据线和 ADB 调试连接即可——当然，数据线是否“正常工作”仍是全宇宙最值得怀疑的事情之一。

## 它做什么

FastBug 由两个角色组成：

| 角色 | 目录 | 职责 |
| --- | --- | --- |
| Capture Agent | `android-agent/` | 安装在安卓平板上，提供悬浮窗触发、录屏与回放录像。 |
| Collector | `collector/` | 运行在 Windows 上，经 USB ADB 接收事件、抓取现场证据，并提供本地工作台。 |

当测试人员在平板上点击悬浮窗的“报缺陷”时，Agent 通知 Collector；Collector 会立即保存截图、当前 UI XML、窗口信息、设备信息、目标 App 信息，以及持续保留的 logcat。随后 Agent 再把触发前后的视频片段拼成回放录像交给 Collector。

最终，每个缺陷都有一个本地证据包，通常在项目同级目录：

```text
../collector-data/captures/<capture_id>/
├── raw/                 # 截图、日志、UI XML、设备与应用信息、原始录像片段
├── manifest/            # 触发事件、采集进度、SHA-256 清单
├── replay.mp4           # 便于回看的拼接录像
└── draft/               # 可编辑的本地缺陷草稿（JSON 与 Markdown）
```

换句话说，FastBug 不负责判断谁写了 Bug；它负责在 Bug 逃跑前，把它的照片、行踪和现场录像一起扣下来。

## 启动前：把工具和演员请到位

运行环境是 Windows 10/11、Node.js 18+、Android Platform Tools（`adb` 已加入 `PATH`）、JDK 11 或 17，以及一台 Android 8.0（API 26）或更高版本的平板。构建平板端时，可以使用 Android Studio 或兼容的 Gradle 环境。

在项目根目录打开 PowerShell，先做三项朴素但有效的检查：

```powershell
node --version
adb version
adb devices
```

目标设备在 `adb devices` 中必须显示为 `device`。如果是 `unauthorized`，请看看平板屏幕——它大概率正在安静地等你点“允许 USB 调试”。

还需要记下设备序列号与被测 App 的包名：

```powershell
adb devices
adb -s <设备序列号> shell getprop ro.product.model
```

Collector 的 `-Package` 参数应填写实际被测 App 的包名。若只是先验证 FastBug 自己的链路，可填 `com.fastbug.captureagent`。

## 第一次安装平板端 Agent

最省心的方式是用 Android Studio 打开 `android-agent/`，等待 Gradle 同步完成后运行 `app` 模块到平板。喜欢命令行的朋友也可以这样做：

```powershell
Set-Location android-agent
gradle assembleDebug
adb -s <设备序列号> install -r app\build\outputs\apk\debug\app-debug.apk
Set-Location ..
```

首次打开 Agent 时，请完成两项授权：

1. 悬浮窗权限；
2. 录屏 / 屏幕捕获权限。

它们不是形式主义：没有悬浮窗，临场报缺陷会变成临场找应用；没有录屏，现场就只剩口供。

## 配置云效与 OSS：把敏感信息留在该待的地方

FastBug 可把确认后的缺陷草稿同步到云效，并将完整证据包上传到 OSS。Collector 启动时要求下列 Windows 用户环境变量存在：

```text
YUNXIAO_TOKEN
FASTBUG_OSS_ENDPOINT
FASTBUG_OSS_ACCESS_KEY_ID
FASTBUG_OSS_ACCESS_KEY_SECRET
```

请在 Windows 用户环境变量中配置它们，或在新开的 PowerShell 会话中将用户变量注入当前进程：

```powershell
$env:YUNXIAO_TOKEN = [Environment]::GetEnvironmentVariable('YUNXIAO_TOKEN', 'User')
$env:FASTBUG_OSS_ENDPOINT = [Environment]::GetEnvironmentVariable('FASTBUG_OSS_ENDPOINT', 'User')
$env:FASTBUG_OSS_ACCESS_KEY_ID = [Environment]::GetEnvironmentVariable('FASTBUG_OSS_ACCESS_KEY_ID', 'User')
$env:FASTBUG_OSS_ACCESS_KEY_SECRET = [Environment]::GetEnvironmentVariable('FASTBUG_OSS_ACCESS_KEY_SECRET', 'User')
```

不要把令牌或 AccessKey 写进 JSON、源码、截图，或任何名为“临时备忘”的文件。它们的归宿是环境变量，不是 Git 历史。

云效的固定配置从示例文件复制到项目同级的数据目录：

```powershell
Copy-Item collector\yunxiao.config.example.json ..\collector-data\yunxiao.config.json
```

再根据企业实际情况填写云效接入点、项目空间、缺陷类型和默认负责人。`collector-data/` 是本机运行数据区，不应提交到仓库。

## 启动 Collector：让电脑开始值班

回到项目根目录，执行：

```powershell
.\collector\start.ps1 -Serial <设备序列号> -Package <被测包名>
```

例如，验证 Agent 自身时可以写成：

```powershell
.\collector\start.ps1 `
  -Serial JYTC656A251222000569 `
  -Package com.fastbug.captureagent
```

Collector 会做几件不显山不露水、但很关键的事：

- 建立 `adb reverse tcp:52741 tcp:52741`，让平板能通过 `127.0.0.1:52741` 访问电脑上的服务；
- 开始保留最多 12 MiB 的 `logcat -v epoch`；
- 生成一个会话 ID；
- 在 `http://127.0.0.1:52741` 启动本地工作台。

启动成功后，终端会打印会话 ID，以及一条可直接配置 Agent 的命令。把其中的会话 ID 交给平板端：

```powershell
adb -s <设备序列号> shell am start -n com.fastbug.captureagent/.MainActivity `
  --es collector_url http://127.0.0.1:52741 `
  --es session_id <Collector 输出的会话ID>
```

也可以在 Agent 页面中手工填写 Collector 地址和会话 ID。二者必须一致；它们不一致时，系统比相亲对象更果断：直接拒绝建立联系。

启动后可做一次健康检查：

```powershell
Invoke-RestMethod http://127.0.0.1:52741/health
adb -s <设备序列号> reverse --list
```

前者应返回 `ok: True` 和当前会话 ID，后者应包含 `tcp:52741 tcp:52741`。

## 日常使用：从“它又坏了”到可提交的缺陷

一条典型流程只有七步：

1. 在平板 Agent 中点击“开始采集并授权录屏”。
2. 正常执行测试；FastBug 会维持录屏和与 Collector 的会话。
3. 发现异常时，点击悬浮窗中的“报缺陷”。
4. 稍等片刻，让 Collector 收集截图、日志、页面信息，并接收录像收尾。
5. 在电脑浏览器打开 `http://127.0.0.1:52741/`，选择新出现的捕获记录。
6. 根据录像和证据完善标题、复现步骤、预期结果、实际结果、严重程度、应用与负责人，然后保存本地草稿。
7. 点击“提交到云效”。系统会上传完整证据 ZIP 到 OSS，再创建或更新云效工作项，并把下载入口放在“技术证据”中。

本地工作台支持查看录像、截图和日志；每一条记录都可继续编辑。这样测试人员可以在问题刚发生时先保住证据，文字整理可以稍后进行——人类记忆终于不再是缺陷管理系统的一部分。

## 重启与排障：服务偶尔需要一杯重启咖啡

需要重启时，在 Collector 所在 PowerShell 窗口按 `Ctrl+C`，然后使用原来的参数重启。若继续传入同一个 `-Session`，平板端不需要重新配置：

```powershell
.\collector\start.ps1 `
  -Serial <设备序列号> `
  -Package <被测包名> `
  -Session <原会话ID>
```

常见情况可以按下面的顺序排查：

| 现象 | 先查什么 |
| --- | --- |
| 设备不是 `device` | USB 连接、USB 调试授权、`adb devices` 输出。 |
| Collector 提示缺环境变量 | 用户环境变量是否已设置；设置后是否重新打开了 PowerShell。 |
| 平板无法上报 | Collector 是否运行、`adb reverse --list` 是否有 `tcp:52741`、会话 ID 是否一致。 |
| 端口 52741 被占用 | 找到并停止该端口的旧监听进程，再启动 Collector。 |
| 云效选项或提交失败 | 检查云效令牌权限与 `yunxiao.config.json` 的企业/项目配置。 |
| OSS 上传失败 | 检查 Endpoint、Bucket、RAM 密钥以及 `fastbug/` 前缀权限。 |

如果端口被旧进程占用，可只处理 52741 的监听者：

```powershell
$collectorPid = (Get-NetTCPConnection -LocalPort 52741 -State Listen).OwningProcess
Stop-Process -Id $collectorPid
```

请确认它确实是旧 Collector，再结束进程；别让一次“重启服务”演变为团队午后的悬疑剧。

## 结尾：把复现留给系统，把判断留给人

FastBug 目前是 POC，但已经完成了缺陷现场的核心闭环：平板一键触发、USB 通道上报、本地证据固化、草稿编辑，以及可选的 OSS 与云效同步。它不试图替测试人员思考，而是把最容易丢失、最难补回的那段现场保存下来。

下一次当有人说“这个问题刚才真的出现过”，你可以温和地回答：“很好，FastBug 应该已经在等我们了。”
