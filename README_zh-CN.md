<h1 align="center">Lyra Code</h1>

<p align="center">
  <img src="logo.png" alt="Lyra Code Logo" width="140" />
</p>

<p align="center">
  <strong>AI 驱动的 Android 端全栈开发环境</strong>
</p>

<p align="center">
  <a href="README.md">English</a> ·
  <a href="https://github.com/lyracode-app/Lyra-Code">GitHub</a> ·
  <a href="https://gitee.com/yukisoffd/lyra-code">Gitee</a>
</p>

<p align="center">
  <img alt="Version" src="https://img.shields.io/badge/version-4.0.1-blue" />
  <img alt="Android" src="https://img.shields.io/badge/Android-8.0%2B-3DDC84?logo=android&logoColor=white" />
  <img alt="PRoot Linux" src="https://img.shields.io/badge/PRoot-Linux-FCC624?logo=linux&logoColor=black" />
  <img alt="Termux" src="https://img.shields.io/badge/Termux-integrated-000000" />
  <img alt="License" src="https://img.shields.io/badge/license-AGPL--3.0-orange" />
</p>

Lyra Code 将 Android 设备变成 AI 辅助的全栈开发环境。它的核心不只是对话、文件管理或代码编辑：Agent 可以进入应用内管理的 **PRoot Linux 环境**，也能接入已有的 **Termux 环境**，直接在 Android 上编辑项目、安装工具链、执行命令、构建测试、启动服务和调试应用。

## 界面预览

| AI 对话 | 设置 | Agent 工具 |
| --- | --- | --- |
| <img src="example-img/chat.png" alt="AI 对话页" width="260" /> | <img src="example-img/set.png" alt="设置页" width="260" /> | <img src="example-img/agent.png" alt="Agent 工具页" width="260" /> |

## 为 Android 端全栈开发而生

- **PRoot 完整 Linux 用户空间：** 可下载经过校验的 Debian Trixie，也可导入 Ubuntu、Alpine 等兼容的 rootfs；支持多个环境共存，可使用发行版包管理器和开发工具链，并通过共用终端或 Agent 的 `proot_command` 调用。
- **深度集成 Termux：**`run_command` 通过 Termux RunCommandService 执行命令，并返回退出码、stdout 和 stderr，让 Agent 复用已有的 Termux 软件包、脚本和工作区；Termux:API 不是必需项。
- **Agent 开发闭环：** 支持 OpenAI 兼容接口、Anthropic 和 Gemini，能够搜索、读取、修改及审查项目文件，执行命令、查看 Diff、跟进 TODO、联网检索并使用 Skills。
- **移动端应用与 Web 工作流：** 可在 Linux/Termux 中运行前后端开发命令，再用内置 HTTP/HTTPS 微型服务器预览静态站点，并查看实时请求和 JavaScript 错误日志。

## 两条本地执行路径

| | 应用内 PRoot Linux | 外部 Termux |
| --- | --- | --- |
| 环境 | 由 Lyra Code 管理的完整 Linux 用户空间 | 用户已有的 Termux 安装及软件包 |
| Agent 工具 | `proot_command(linux_id, ...)` | `run_command(...)` |
| 终端 | 应用内置；每个 Linux 环境拥有独立持久会话 | 继续使用 Termux 自身终端 |
| 适合场景 | Debian/Ubuntu/Alpine 工具链与相互独立的发行版环境 | Android 原生 Termux 工作流与已有配置 |

不安装 Termux 也能使用 PRoot，两种模式也可同时使用。PRoot 共享 Android 内核，不是虚拟机、Root 权限或安全隔离边界。

## 配套工具

- 工作区文件搜索与编辑、变更审查、Diff 可视化、TODO/过程记录、原生下载、后台任务和定时任务。
- 集成 Sora Editor，支持 TextMate 高亮、行号、搜索跳转、换行、`.bak` 备份和 AI 编辑侧栏；另有双栏 Android 文件管理器。
- MCP 客户端/服务端、SSH、SMTP/IMAP、WebDAV、FTP/FTPS/SFTP、原生 HTTP/HTTPS 下载及自然语言配置管理。
- Skills 导入、图片输入与标注、Markdown/LaTeX/媒体渲染、设备诊断、用量统计及本地/WebDAV 备份。
- 可选 Shizuku 和 Root 工具，均可独立开关。

## 快速开始

1. 添加模型服务商并选择模型。
2. 打开“**设置 > PRoot Linux**”，下载 Debian 或导入兼容的 rootfs；这条路径不需要安装 Termux。
3. 打开终端，或让 Agent 通过 `proot_command` 为项目配置语言运行时、依赖、构建工具和服务。
4. 如需复用已有的 Termux 开发环境，再按需连接 Termux。

启用 Termux 命令执行时，先在 Termux 中运行一次：

```bash
mkdir -p ~/.termux && (grep -qxF 'allow-external-apps=true' ~/.termux/termux.properties || echo 'allow-external-apps=true' >> ~/.termux/termux.properties) && termux-reload-settings
```

然后在 Lyra Code 设置中授予 Termux `RUN_COMMAND` 权限。未授权时只会禁用 `run_command`，应用内 PRoot Linux 仍可正常使用。

### PRoot Linux 说明

- 每个 APK 都包含小型 PRoot 引擎，但不打包 rootfs。运行时与设备本身的架构匹配，不提供其他架构模拟。
- 授予 Android“所有文件访问”权限后，共享存储挂载到 `/storage`，主存储也可通过 `/sdcard` 访问；可直接访问的工作区挂载到 `/workspace`。访问仍受应用 UID 和 SELinux 限制。
- Linux rootfs 是可变的应用数据。清除应用数据或卸载 Lyra Code 会将其删除，Android 云备份和设备迁移也不会包含这些环境。
- 支持的归档格式、生命周期、存储、限制和源码信息详见[应用内 PRoot Linux 环境](docs/DEBIAN_RUNTIME.md)。

## 构建

需要 JDK 17、Android SDK 37，以及 Android Studio 或命令行 Android SDK。最低支持 Android 8.0（API 26）。

```powershell
.\gradlew.bat assembleDebug
```

APK 会生成到 `app/build/outputs/apk/debug/`。Release 签名应仅在本地配置，请勿提交签名密钥、keystore、API Key、`.env`、`local.properties` 或其他隐私文件。

## 安全说明

Lyra Code 可以执行命令、修改文件、启动服务器并连接第三方服务。请审查 Agent 工具调用，为远程端点使用 HTTPS/TLS 或可信网络，妥善保管含密钥的备份，并且只安装可信的 rootfs、Skills、脚本和 MCP Server。PRoot 进程拥有 Lyra Code 的 Android 权限，不能视为与应用隔离。

## 参与贡献

本项目接受组织成员的 PR；如需加入，可联系管理员。欢迎 AI 辅助贡献，但提交者必须完成实际测试与人工审查、对结果负责，并在 PR 中说明测试范围。

## 许可证

Lyra Code 原创源代码仅依据 GNU AGPL v3 开源。第三方组件保留各自许可证，详见 [LICENSE](LICENSE) 和 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)。
