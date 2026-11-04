# AndCode

<p align="center">
  <a href="https://github.com/yuga-hashimoto/and-code/actions/workflows/android.yml"><img src="https://github.com/yuga-hashimoto/and-code/actions/workflows/android.yml/badge.svg" alt="CI" /></a>
  <a href="https://github.com/yuga-hashimoto/and-code/releases/latest"><img src="https://img.shields.io/github/v/release/yuga-hashimoto/and-code" alt="Release" /></a>
  <a href="LICENSE"><img src="https://img.shields.io/github/license/yuga-hashimoto/and-code" alt="License: MIT" /></a>
  <a href="https://github.com/yuga-hashimoto/and-code/releases/latest"><img src="https://img.shields.io/github/downloads/yuga-hashimoto/and-code/total" alt="Downloads" /></a>
</p>

**通过原生图形界面在 Android 上本地运行 AI 编程智能体——无需终端。**

AndCode 是一款原生 Android 图形界面应用，将 AI 编程智能体带到你的手机上。你可以通过以触控为核心的界面与 [OpenCode](https://github.com/sst/opencode)、[Claude Code](https://github.com/anthropics/claude-code)、[Google Antigravity](https://github.com/google-antigravity/antigravity-cli) 和 [OpenAI Codex](https://github.com/openai/codex) 对话——在设备上使用时无需终端、SSH 或 PC。它通过 PRoot 在设备上运行智能体运行时，或远程连接到你在 PC/Mac/Linux 上现有的 OpenCode 服务器。

[Releases](https://github.com/yuga-hashimoto/and-code/releases/latest)

[English](README.md) | [日本語](README.ja.md) | [简体中文](README.zh-CN.md) | [Español](README.es.md) | [Français](README.fr.md) | [Português (Brasil)](README.pt-BR.md) | [Русский](README.ru.md) | [العربية](README.ar.md)

> [!IMPORTANT]
> AndCode 是一个独立的本地优先图形界面，可在用户自己的 Android 设备上安装或启动受支持的第三方命令行工具。AndCode 本身不提供或转售底层 AI 服务、订阅、模型访问权限或账户权益。身份验证、模型访问、推理以及与提供商的通信均由相应的官方 CLI 或用户配置的提供商处理。AndCode 与 OpenCode、Anthropic、Google 或 OpenAI 无关联，也未获得其认可、赞助或官方支持。详见下方[法律与第三方软件](#法律与第三方软件)。

<div align="center">

### 截图

<table>
  <tr>
    <td align="center"><img src="screenshots/navigation-drawer.jpg" width="180" alt="包含智能体、项目和最近对话的导航抽屉"><br><em>导航抽屉</em></td>
    <td align="center"><img src="screenshots/model-picker.jpg" width="180" alt="可搜索收藏模型的模型与运行时选择器"><br><em>模型与运行时选择器</em></td>
    <td align="center"><img src="screenshots/repository-chat.jpg" width="180" alt="与正在检查 AndCode 仓库的智能体对话"><br><em>仓库对话</em></td>
  </tr>
  <tr>
    <td align="center"><img src="screenshots/image-generation.jpg" width="180" alt="对话中显示的智能体生成图片"><br><em>图片生成</em></td>
    <td align="center"><img src="screenshots/schedules.jpg" width="180" alt="已启用每日运行的定时提示词"><br><em>定时任务</em></td>
    <td align="center"><img src="screenshots/run-result.jpg" width="180" alt="已完成的定时运行及打开对话的选项"><br><em>运行结果</em></td>
  </tr>
</table>

</div>

> [!IMPORTANT]
> AndCode 是一个独立的开源项目。它**不**隶属于 OpenCode、Anthropic、Google 或 OpenAI。

---

## 目录

- [支持的智能体](#支持的智能体)
- [功能](#功能)
- [Antigravity](#antigravity)
- [Codex](#codex)
- [远程 OpenCode](#远程-opencode)
- [界面](#界面)
- [快速开始](#快速开始)
- [安全](#安全)
- [设备端运行时详解](#设备端运行时详解)
- [交接（对话中途切换运行时）](#交接对话中途切换运行时)
- [连接 OpenCode Desktop](#连接-opencode-desktop)
- [从源码构建](#从源码构建)
- [文档](#文档)
- [贡献](#贡献)
- [法律与第三方软件](#法律与第三方软件)
- [许可证](#许可证)

## 支持的智能体

| 智能体 | 设备端 | 远程 PC | 状态 |
|--------|:------:|:-------:|------|
| [OpenCode](https://github.com/sst/opencode) | ✓ | ✓ | 稳定 |
| [Claude Code](https://github.com/anthropics/claude-code) | ✓ | — | Beta |
| [Google Antigravity](https://github.com/google-antigravity/antigravity-cli) | ✓ | — | Beta |
| [OpenAI Codex](https://github.com/openai/codex) | ✓ | — | Beta |

设备端智能体通过 PRoot 在 Linux 环境中运行。OpenCode、Claude Code 和 Codex 使用 Alpine Linux；Google Antigravity 还会额外安装 Debian Bookworm rootfs，因为官方 `agy` 二进制文件依赖 glibc。

## 功能

- **原生 Android 界面** — 面向编程智能体的触控优先界面；日常使用无需 CLI 或终端
- **设备端运行时** — Alpine Linux、Git、bash、curl、ripgrep 和编程智能体通过 PRoot 自动安装到你的 Android 设备
- **仓库与工作区** — 在设备上打开 git 仓库、浏览文件树，并以语法高亮查看文件
- **设备文件** — 授予全部文件访问权限后，整个手机（`/sdcard`、SD 卡、USB 驱动器）都可在文件夹选择器中浏览，并可被智能体访问；文件就地打开，而不是复制到应用内
- **Git 支持** — 在界面中查看状态和审阅 diff；其他 Git 操作可使用内置运行时终端
- **Diff 查看器** — 接受前以内联方式审阅代码更改
- **内置终端** — 在设备端运行时中运行受限的 shell 命令
- **Pull request 徽章** — 在对话中打开的 PR 会固定在输入框上方，显示 diff 大小和状态（草稿、打开、冲突、已合并、已关闭）；点击即可在 GitHub 中打开
- **工具审批** — 批准或拒绝危险的工具有操作
- **会话管理** — 创建、恢复、重命名和删除会话
- **定时任务** — 按一次性或循环（cron）计划自动运行提示词，并保留运行历史
- **动态模型** — 从已连接的智能体实例实时获取模型、提供商和智能体
- **实时流式输出** — 基于 SSE 的实时响应、工具执行和审批请求
- **结构化时间线** — 可折叠的推理过程、工具调用和命令输出
- **语音 + 唤醒词** — 使用 Android 语音识别的按键说话 + 唤醒词检测
- **文字转语音** — 朗读响应内容
- **数字助手** — 注册为 Android 默认助手（主页手势 / 角落滑动）
- **主屏幕小组件** — 无需先打开应用，直接从启动器发送提示词
- **安全存储** — 连接凭据使用 Android Keystore 加密
- **本地化界面** — 英语、日语、简体中文、俄语、西班牙语、法语、葡萄牙语（巴西）和阿拉伯语，可在设置中切换

## Antigravity

Google Antigravity（`agy`）在同一 PRoot 环境中于设备上运行。与运行在 Alpine Linux 中的 OpenCode 和 Claude Code 不同，Antigravity 使用 Debian Bookworm rootfs，以兼容官方 CLI 二进制文件的 glibc 要求。

- **OAuth 登录** — 通过浏览器 URL + 一次性代码流程进行身份验证；凭据仅存储在 Linux rootfs（`~/.gemini`）中，绝不存入 Android 偏好设置
- **模型选择** — 从已登录的 `agy` 实例实时获取模型目录（Gemini、Claude、GPT-OSS 各变体）
- **权限模式** — 三级控制：Plan、Accept Edits 和 Full Access（`--dangerously-skip-permissions`）
- **MCP 服务器** — 直接读写 `~/.gemini/config/mcp_config.json`，与官方 CLI 的配置格式一致
- **沙箱启动器** — 基于 PTY 的进程，显式指定终端尺寸；`AGY_CLI_DISABLE_AUTO_UPDATE=1` 让更新由应用控制

## Codex

OpenAI 的 Codex CLI 在设备上与 OpenCode、Claude Code 相同的 Alpine/PRoot 沙箱中运行，并通过其 `app-server` JSON-RPC 协议与应用通信。

- **登录** — 通过与其他提供商相同的登录对话框，使用 ChatGPT 账户（浏览器流程）或 OpenAI API 密钥
- **MCP 服务器** — 使用官方 `codex mcp` 子命令管理，应用绝不会手动编辑 `~/.codex/config.toml`
- **图片生成** — 内置 `image_gen` 工具生成的图片会内联显示在对话中
- **安装** — 可从设置指南或 设置 → 智能体 → Codex 安装；无需单独的 rootfs，因为 Codex 的 musl 链接二进制可直接在共享 Alpine 环境中运行

实现细节见 [docs/CODEX.md](docs/CODEX.md)。

## 远程 OpenCode

除设备端智能体外，AndCode 还可以作为附加功能连接到运行在 PC/Mac/Linux 上的 OpenCode：

- **远程连接** — 通过 LAN 或 Tailscale 连接
- **运行时切换** — 在本地与远程执行之间无缝切换，甚至可在对话中途切换（交接）
- **发现** — 通过二维码或 mDNS（零配置 LAN 发现）查找 PC

## 界面

| 界面 | 说明 |
|------|------|
| 对话 | 主屏幕与对话视图——最近会话、可折叠推理/工具、语音输入、模型切换、审批、交接 |
| 工作区 | 本地运行时设置、PC 连接、工作文件夹、文件浏览器、代码查看器、内置终端 |
| 定时任务 | 创建、编辑、启用/禁用并查看一次性或循环（cron）提示词的运行历史 |
| 设置 | 智能体与提供商、GitHub、MCP 服务器、模型可见性、语音/TTS、数字助手、语言 |

## 快速开始

### 方式 A：设备端（无需 PC）

1. 从 [Releases](https://github.com/yuga-hashimoto/and-code/releases/latest) 安装 APK
2. 打开应用 → 点击 **工作区** → **此 Android 设备** → **在此设备上设置**
3. 等待运行时下载并安装（网络良好时约 2 分钟）
4. 选择你的编程智能体并开始对话

### 方式 B：远程 PC

1. 在 PC 上启动 OpenCode：

```bash
OPENCODE_SERVER_PASSWORD='your-strong-password' \
  opencode serve --hostname 0.0.0.0 --port 4096 --mdns
```

2. 在 Android 设备上安装 APK
3. 打开应用 → **工作区** → **添加连接**
4. 输入 PC 的 IP（或使用 **LAN 搜索** / **二维码** 自动发现）

```text
Name:     Mac mini
URL:      http://192.168.1.10:4096
Username: opencode
Password: your-strong-password
```

> 也可以使用 Tailscale：`http://100.x.y.z:4096` 或 `http://your-mac.tailnet-name.ts.net:4096`

### 二维码设置

在 PC 上生成二维码：

```bash
npx qrcode "opencode://connect?name=Mac%20mini&url=http%3A%2F%2F192.168.1.10%3A4096&username=opencode&password=your-password&insecure=true"
```

然后在应用中通过 **工作区** → **通过二维码添加** 扫描。

## 安全

- **切勿**将 4096 端口直接暴露到互联网
- 使用 LAN 或 Tailscale 进行连接
- 在公共网络上使用 HTTPS 反向代理
- 应用绝不会自动批准危险操作
- LAN 上的明文 HTTP 需要为每个连接显式确认
- PRoot 是兼容性运行时，并非完整的安全沙箱。完全访问 / 绕过权限模式允许官方 CLI 在不询问的情况下执行命令和编辑文件；应用会在开启前警告，但你仍应备份重要文件，并且只让智能体访问你信任的仓库、提示词和 MCP 服务器
- 为 `opencode://connect` 流程生成的二维码可能包含明文连接密码——只扫描来自可信来源的二维码，并将打印或显示的二维码视为共享该密码

## 设备端运行时详解

设置流程（从“工作区”触发）：

1. 校验 APK 中内置的原生 PRoot 运行器
2. 从官方 CDN 下载 Alpine Linux minirootfs
3. 从 GitHub Releases 下载智能体二进制文件
4. 校验两者的 SHA-256 校验和
5. 解压到应用私有目录
6. 在 Alpine 内安装 Git、bash、curl、ripgrep 和 CA 证书
7. 在 `127.0.0.1:4097` 启动智能体服务器
8. 将应用切换到本地运行时

固定版本（可通过应用发布更新，无需更改智能体；见 [`local-runtime-manifest.json`](app/src/main/assets/local-runtime-manifest.json)）：

- Alpine Linux 3.24.1
- OpenCode 1.18.5
- Google Antigravity CLI 1.1.7（Debian Bookworm rootfs）
- 架构：arm64-v8a、x86_64

## 交接（对话中途切换运行时）

从对话标题栏菜单 → **在其他运行时继续**——应用会生成对话摘要提示词并发送到所选运行时，让你从上次中断处继续（例如通勤时在设备上开始，回家后在 PC 上继续）。

## 连接 OpenCode Desktop

在 `~/.config/opencode/opencode.json` 中添加服务器配置：

```json
{
  "server": {
    "port": 4096,
    "hostname": "0.0.0.0",
    "mdns": true
  }
}
```

重启桌面应用，然后在 Android 应用中通过 **LAN 搜索** 发现它。

## 从源码构建

要求：JDK 17、Android SDK、Python 3、网络访问（仅首次构建）

```bash
./gradlew detekt spotlessCheck :app:lintGithubDebug :app:testGithubDebugUnitTest :app:assembleGithubDebug :app:assembleGithubRelease
```

生成的 APK：

```text
app/build/outputs/apk/github/debug/app-github-debug.apk
app/build/outputs/apk/github/release/app-github-release-unsigned.apk
```

安装到设备：

```bash
adb install -r app/build/outputs/apk/github/debug/app-github-debug.apk
```

## 文档

- [设备端运行时设计](docs/LOCAL_RUNTIME.md)
- [Antigravity 本地运行时](docs/ANTIGRAVITY.md)
- [Antigravity 智能体对等设计](docs/superpowers/specs/2026-07-27-antigravity-agent-parity-design.md)
- [Codex 本地运行时](docs/CODEX.md)
- [CI 指南](docs/CI.md)
- [发布指南](docs/RELEASE.md)
- [翻译指南](docs/TRANSLATION.md)
- [设备验证矩阵](docs/device-matrix.md)

## 贡献

欢迎各种贡献——代码、缺陷报告和翻译！安装与工作流程请见 [CONTRIBUTING.md](CONTRIBUTING.md)，如果你想帮助翻译应用，请见 [docs/TRANSLATION.md](docs/TRANSLATION.md)。

## 法律与第三方软件

- **官方 CLI 在设备上运行。** OpenCode、Claude Code、Google Antigravity 和 OpenAI Codex 是各自项目的官方未修改二进制文件，从其官方分发渠道下载，并在你设备的 PRoot Linux 环境中执行（对于 OpenCode，则在你自己的 PC/Mac/Linux 机器上执行）。AndCode 不会 fork、修改或重新实现它们的智能体逻辑。
- **你自带账户或 API 访问权限。** AndCode 不销售、转售或以其他方式提供 Claude、Antigravity、Codex、OpenCode 模型或 GitHub 的访问权限。你使用自己的账户或提供商配置进行身份验证，并受该提供商当前服务条款约束。
- **AndCode 不通过自己的服务器中转提示词或令牌。** 不存在会经过提示词、文件或 OAuth 令牌的 AndCode 后端。请求直接从设备端 CLI（或你 PC 上的 OpenCode 服务器）发送到你配置的提供商。见 [docs/AUTHENTICATION_AND_DATA_FLOW.md](docs/AUTHENTICATION_AND_DATA_FLOW.md)。
- **OAuth 令牌不会在工具之间重复使用。** Claude Code、Antigravity 和 Codex 各自按照其官方 CLI 的常规方式，将 OAuth 凭据保存在 Linux/Debian rootfs 内；AndCode 绝不会将其复制到 Android 应用设置、其他智能体的凭据存储或外部服务中。
- **另见：** [PRIVACY.md](PRIVACY.md)、[TERMS.md](TERMS.md)、[THIRD_PARTY_SERVICES.md](THIRD_PARTY_SERVICES.md)、[TRADEMARKS.md](TRADEMARKS.md) 和 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)（内置运行时组件和依赖项的 OSS 许可证）。这些文件也可在应用内 **设置 → 法律与隐私** 中离线查看。

运行时生成复用了为编程智能体重构的通用 Termux 包解析/解压逻辑，灵感来自 MIT 许可的 Hermes Agent Android 实现。内置第三方组件及其许可证的完整列表见 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)。

## 许可证

本仓库中的 **AndCode 源代码** 采用 [MIT 许可证](LICENSE)。该许可证仅涵盖 AndCode 自身的 Kotlin/Android 代码——**不**适用于 AndCode 安装或启动的第三方 CLI、运行时或软件包（Claude Code、Google Antigravity、OpenAI Codex、OpenCode、PRoot、Alpine/Debian 软件包等），它们各自按其上游许可证分发。见 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md) 和 [TRADEMARKS.md](TRADEMARKS.md)。
