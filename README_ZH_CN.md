<div align="center">

<img src="docs/icon.png" width="96" height="96" alt="RikkaHub Agent" style="border-radius: 24px" />

# RikkaHub Agent

**你的手机，全自动。**

这是 [RikkaHub](https://github.com/rikkahub/rikkahub) 的一个分支（fork），把原生 Android LLM 聊天客户端变成了一个真正的设备端 Agent：**80+ 设备工具、AI 自写工作流、定时任务、AI 驱动的应用内浏览器、免 Key 联网搜索、Linux 工作区、SSH、屏幕自动化、文件管理器、音乐播放器、语音转文字、可下载的端侧 LLM，以及远程 Telegram 机器人**。所有功能默认关闭、按需开启。

<p>
  <a href="https://github.com/ExTV/rikkahub-agent/releases"><img src="https://img.shields.io/github/v/release/ExTV/rikkahub-agent?include_prereleases&style=flat-square&label=release&color=blue" alt="Release" /></a>
  <a href="https://github.com/ExTV/rikkahub-agent/releases"><img src="https://img.shields.io/github/downloads/ExTV/rikkahub-agent/total?style=flat-square&color=brightgreen" alt="Downloads" /></a>
  <a href="https://github.com/ExTV/rikkahub-agent/stargazers"><img src="https://img.shields.io/github/stars/ExTV/rikkahub-agent?style=flat-square&color=yellow" alt="Stars" /></a>
  <img src="https://img.shields.io/badge/platform-Android%208%2B-3DDC84?style=flat-square&logo=android&logoColor=white" alt="Android 8+" />
</p>

<a href="https://extv.github.io/rikkahub-agent/">官网</a> ·
<a href="https://github.com/ExTV/rikkahub-agent/releases/latest">下载</a> ·
<a href="#它能做什么">它能做什么</a> ·
<a href="#快速上手">快速上手</a> ·
<a href="#从源码构建">从源码构建</a>

</div>

---

## 它能做什么？

用大白话告诉它要干什么，手机就会在后台帮你执行，你该干嘛干嘛去。

> *"每周一早上9点，把我没读的 WhatsApp 消息汇总成一条 Telegram 消息发给我。"*
> *"如果我家里服务器的磁盘满了，就提醒我。"*
> *"盯着我的通知，只要有老板发来的消息，就转发到 Telegram。"*
> *"在我手机上找到提到'发票'的 PDF，把第一段读给我听。"*
> *"接下来4小时每30分钟截一张图，让我看看我下午到底干了啥。"*
> *"用 Termux 给我做一个列出你所有能力的网页，然后在我浏览器里打开。"*
> *"当我晚上7点后在家连上 WiFi 并插上耳机时，自动播放我的晚间歌单。"*
> *"打开我路由器的管理页面，用保存的密码登录，告诉我现在哪些设备最占带宽。"*
> *"并行开两个研究任务：一个查这个月去东京最便宜的单程机票，另一个列涩谷100美元以下的酒店。"*

上面每一条都只是一句话的设置。

---

## 功能特性

### 设备控制（Device Control）

点击、滑动、滚动、打字、截图、打开应用、调节亮度/音量、发通知、查看电量/WiFi/信号/定位/传感器、读取联系人 & 短信、发短信、设置壁纸、读写 NFC 标签、用 Android 密钥库签名和加密数据、访问外部存储和 SD 卡、管理 ZIP 压缩包。**80+ 个工具**，全部内置在 Android 里，每个工具默认关闭，你打开才启用。

### 工作流 & 定时任务（Workflows & Schedules）

**工作流（Workflows）** —— 用大白话描述触发条件和动作：*"我到家时，把铃声关掉。"* 有 **19 种触发器**（WiFi、蓝牙、耳机、地理围栏、应用启动、通知、时间、充电、屏幕状态等）和 **14 种条件**（电量阈值、日出日落、星期几、前台应用、屏幕状态）来决定何时触发。接收器只在需要时注册——耗电极少。

**定时任务（Schedules）** —— 按任意节奏运行任务：*"每周一早上8点"*、*"每两小时"*、*"下周五下午3点"*。重启后和电池省电模式下都能存活。可以让 AI 在运行时思考，也可以预置固定动作（不烧 token）。

### Telegram 机器人

随时随地跟你的助手对话。可以发问题、图片、PDF 或语音消息。审批提示用简单的 是/否 按钮。AI 需要输入时，会在聊天里弹出可点击的多选题。长消息会以可下载文件形式送达。消息按节奏发送，避免触发 Telegram 限流。

### 应用内浏览器（In-App Browser）

App 内置一个真正的浏览器。AI 可以自己点击 cookie 横幅、填写搜索框、滚动页面，然后把页面内容读给你听。每执行一步都会把最新截图流式发到聊天里。浮动聊天小窗让你不离开页面也能继续和 AI 说话。内置文章提取和"操作后差异对比"，帮你省 token。

### 联网搜索 & 抓取（Web Search & Fetch）

开箱即用、无需 API Key 的搜索：**内置引擎（DuckDuckGo）**是默认选项，由于有熔断器，反爬拦截会如实报出"可重试的错误"而不是假装"没有结果"。引擎选择器总共列出 19 个，你也可以用自己的 Key：Tavily、Exa、Brave、Perplexity、Jina、Firecrawl、SearXNG、Bing、Serper、Ollama 等，还支持自定义脚本引擎。

另外，助手可以直接抓取任意网页。**网页抓取和提取默认开启**（设置 → 搜索），不在单个助手的工具菜单里：

- `web_fetch` —— 获取网页，按响应字符集解码，长文档自动分页，避免撑爆上下文窗口
- `web_extract` —— 基于 jsoup 的可读性处理，把导航栏和样板内容剥离，只留正文

两者都限制在 30 秒内，响应体有大小限制（超大页面不会 OOM），并且在 DNS 解析阶段就屏蔽私有网络目标。

### 文件管理器

查找文件、读取、保存、复制、移动、重命名、删除。*"在我手机上找到所有提到'发票'的 PDF"* 一句话搞定。App 沙箱之外的系统目录不可访问，你求它也不行。

### 工作区（Workspace）

手机上的一个真正的 Linux 环境。AI 可以在里面执行 shell 命令、读写和打补丁改文件，并通过内置文件管理器（带文本编辑器和图片/视频预览）查看结果。可以通过系统文件选择器从设备任意位置拷文件进来。

长时间运行的任务跨回合存活：`workspace_run_background` 启动开发服务器、安装或文件监听，返回任务 ID；`workspace_background_status` 轮询它的最新输出；`workspace_background_kill` 停止任务。任务 ID 限定在工作区内，删除工作区会杀掉它启动的所有东西。

### SSH

把服务器保存一次。运行命令、上传文件、拉取备份、查看磁盘空间、追踪日志——全都在聊天里完成。可以给命令输入管道数据、写远程文件、或启动长驻服务器（返回 PID 而不是卡住）。WiFi 或流量下都能用。

### 音乐 & 媒体

通过 Android 标准媒体控制播放音乐：锁屏封面、耳机按键，一应俱全。暂停、恢复、调音量——从聊天或 Telegram 都能操作。队列通过快照兜底，强制停止也不丢。

### 技能（Skills）

丢一个 Markdown 技能文件进去，AI 就获得一套新玩法。内置技能目录自带二维码生成器、维基百科查询框、钢琴、交互式地图等。开箱即用启用两个技能：一个常驻 agent 玩法手册和一个 OpenClaw 转换器。可以从 URL 添加技能，也可以把 Markdown 文件分享进 App。

### 子代理（Sub-Agents）

长任务时，主助手会把任务分派给专注的子代理，在干净的子上下文中运行，可以选用更小更便宜的模型。支持并行运行多个。每个结果以一段摘要返回。`/stop` 一键级联取消所有存活的子代理。

### 医生（Doctor）

内置健康检查。全面审计权限、后台服务、数据库完整性、网络、Termux 和诊断信息。点一下自动修复就能授权权限、重启服务或重建搜索索引。也可以通过 Telegram 远程 `/doctor` 调用。

### MCP 服务器

连接 [Model Context Protocol](https://modelcontextprotocol.io) 服务器，AI 就能获得它们暴露的任何工具。AI 可以自己添加、更新、管理 MCP 连接——每次变更都需审批。

### 通知 & 外部触发器

AI 可以读取、汇总、转发你选定的 App 的通知。白名单默认是空的。Agent 发出的通知会深链回产生它的对话，冷启动时点一下也能打开完整回复。其他应用（Tasker、自动化工具、ADB）可以通过外部自动化 Intent API 给 Agent 派任务。

### 安全 & 隐私

三层保护：

1. **每个助手独立开关** —— 所有工具默认关闭，只打开你想要的。
2. **每次调用审批** —— 会改变状态的工具在执行前会先征求同意。
3. **HARDLINE 底线** —— 真正危险的命令（格式化、重启、fork 炸弹、破坏系统文件）无条件拦截。

密码和 API Key 永远不会写进日志文件。云备份跳过已保存的凭据。Telegram 机器人只理你的白名单。网页抓取如果在 DNS 解析时发现指向私有网络地址，会直接拒绝——AI 不可能被忽悠去扫描你的局域网或云元数据接口。

---

## 快速上手

### 1. 安装

从 [Releases](https://github.com/ExTV/rikkahub-agent/releases/latest) 下载最新的 `*-release.apk`。允许安装未知来源应用，然后打开。

> **注意：** 如果装过旧版 debug 构建，先卸载——release 版签名不同。

> **从 `2.3.1-agent.0` 之前升级？** 应用 ID 改成了 `excp.rikkahub`，所以这个分支可以和上游 RikkaHub 并存安装。迁移数据：打开旧应用 → 设置 → 备份 → 安装这个版本 → 恢复备份。

### 2. 添加 LLM 提供商

**设置 → 提供商 → 选一个 → 粘贴你的 API Key。**

- **OpenRouter** —— 一等公民支持，自动识别模型能力、价格和路由；主模型挂了/限流/拒绝时，按顺序尝试备用模型列表
- **Codex** —— 用你的 ChatGPT 账号登录（OpenAI 计划走 OAuth）
- **Grok** —— 用你的 xAI 账号登录（SuperGrok 或 X Premium+ 走 OAuth）
- **本地 · LiteRT** —— 下载本地模型（Gemma、Qwen）。无需 Key、无需网络，支持 GPU 加速的设备上在端侧运行
- **AICore** —— Pixel 8/9/10 用户可以启用 Gemini Nano 做端侧推理（目前需要 AICore Beta）

### 3. 打开你想要的功能

**设置 → 助手 → 点你的助手 → 本地工具** —— 打开你想启用的分类。

如果你什么都不开，这个 App 表现得和原版 RikkaHub 一模一样。

### 4.（可选）Telegram 机器人

1. 给 [@BotFather](https://t.me/BotFather) 发 `/newbot` 获取 token
2. 给 [@userinfobot](https://t.me/userinfobot) 发 `/start` 获取你的数字用户 ID
3. 告诉助手：*"设置 Telegram 机器人。Token 是 `<token>`。我的用户 ID 是 `<id>`。把我设为默认会话。启用它。"*

---

## 系统要求

| | |
|---|---|
| **架构** | arm64 或 x86_64 |
| **Android** | 8.0+（API 26），目标 API 37 |
| **存储** | 约 80 MB |
| **LLM 提供商** | OpenAI、Google、Anthropic、OpenRouter、Codex、Grok、Ollama，或任何兼容 OpenAI 的端点；也可以用 Google 账号登录代替 Gemini API Key；Pixel 8/9/10+ 还可以用 AICore 跑 Gemini Nano |

---

## 语言

界面支持 **英文、简体中文、繁体中文、日语、韩语、俄语、阿拉伯语**。App 跟随系统语言，找不到翻译时回退英文。RTL 语言（阿拉伯语、波斯语、乌尔都语）在聊天中正确渲染——代码块保持 LTR。

---

## 从源码构建

需要 PATH 里有 [bun](https://bun.sh) 和 [pnpm](https://pnpm.io) —— bun 安装 web-ui 依赖，pnpm 构建 bundle。

```bash
git clone https://github.com/ExTV/rikkahub-agent.git
cd rikkahub-agent
./gradlew :app:installDebug
```

---

## 致谢

站在巨人的肩膀上：

| 项目 | 作用 |
|---|---|
| [RikkaHub](https://github.com/rikkahub/rikkahub) | 本分支的上游聊天客户端 |
| [cron-utils](https://github.com/jmrozanec/cron-utils) | 定时任务的 Cron 解析器 |
| [whisper.cpp](https://github.com/ggerganov/whisper.cpp) | 通过 Termux 做设备端语音转文字 |
| [Termux](https://github.com/termux/termux-app) | Shell + 包管理器 |
| [JSch (mwiede fork)](https://github.com/mwiede/jsch) | 原生 SSH 客户端 |
| [FlorisBoard](https://github.com/florisboard/florisboard) | 配套 [agent-keyboard](https://github.com/ExTV/agent-keyboard) 的基础 |

本分支与上游 RikkaHub 维护者无关联。底层聊天客户端、提供商抽象和 UI 设计的所有功劳归上游团队。

---

## 许可证

GNU AGPL-3.0，继承自[上游](https://github.com/rikkahub/rikkahub)。见 [LICENSE](LICENSE)。
