# Dsh Dock - DeepSeek Harness IDEA 插件

<!-- Plugin description -->
DeepSeek Harness (dsh) for IntelliJ IDEA: launch the `dsh web` AI assistant with one click and
chat with DeepSeek models in the built-in WebUI — no terminal or separate browser needed.

**English**

1. **One-click start of dsh**: Click the whale icon in the right tool window bar to open the Dsh Dock
   tool window and start the DeepSeek Harness assistant (`dsh web`) with one click; the WebUI is shown
   directly in the built-in browser panel of the tool window, so you can chat with DeepSeek models
   without leaving the IDE
2. **Two launch modes (optional)**: Start in **WSL** or **directly in Windows**, with separately
   configurable ports and extra arguments for each mode
3. **Workspace follows the project**: Uses the root path of the currently open project as the dsh workspace;
   with multiple open windows it follows the **active window**, so other windows never hijack the project space
4. **Auto-detects a running dsh**: If the port is already in use (e.g. you started dsh yourself),
   it skips launching and opens the WebUI directly in the built-in browser — no duplicate processes
5. **Right-click references**: Send the selected code or file to the Dsh input box as a `@path`
   reference with one click, so the assistant can read the file when needed
6. **Windows launch note**: dsh is launched through a hidden PowerShell process — if your security
   software intercepts it, please allow it
7. **Compatibility**: Compatible with IntelliJ IDEA 2022.3 and later
8. **Open source**: <https://gitee.com/kkcoco/dsh-idea-plugin>
9. **Auto-refresh after dsh edits**: Listens to dsh's session event stream; when a file-writing
   tool (Edit / write, etc.) completes successfully, the file's VFS is refreshed and any open
   editors update to the new content immediately — no more stale code or reopening files

**中文**

DeepSeek Harness（dsh）IntelliJ IDEA 插件：一键启动 `dsh web` AI 助手，在 IDEA 内置浏览器中直接与
DeepSeek 模型对话，无需终端、无需另开浏览器。

1. **一键启动 dsh**：点击右侧工具栏的鲸鱼图标，打开 Dsh Dock 工具窗口并一键启动 DeepSeek Harness（`dsh web`），
   WebUI 直接显示在工具窗口内置的浏览器面板中，无需离开 IDE 即可与 DeepSeek 模型对话
2. **双启动方式（设置可选）**：支持在 **WSL 中启动**或在 **Windows 中直接启动**，
   两种方式的端口与附加参数可分别配置
3. **工作空间跟随项目**：以当前打开的项目根目录作为 dsh 工作空间；多窗口时跟随当前**活动窗口**，
   其他窗口不会抢占项目空间
4. **已启动自动识别**：端口已被监听时（比如你自己已启动过 dsh），跳过启动步骤、
   直接在内置浏览器中打开 WebUI，不会重复启动进程
5. **右键发送代码/文件**：选中的代码或文件可一键以 `@路径` 引用发送到 Dsh 输入框，方便模型按需读取
6. **Windows 启动说明**：通过隐藏的 PowerShell 调用启动命令，如遇安全软件拦截请放行
7. **兼容版本**：兼容 IntelliJ IDEA 2022.3 及之后的所有版本
8. **项目开源**：<https://gitee.com/kkcoco/dsh-idea-plugin>
9. **dsh 编辑后自动刷新**：监听 dsh 会话事件流，文件写入工具（Edit / write 等）成功执行后
   自动刷新 VFS 并即时更新已打开的编辑器，不再显示旧代码、无需重新打开文件
<!-- Plugin description end -->

## 开源地址

项目开源：<https://gitee.com/kkcoco/dsh-idea-plugin>，欢迎 Star、提交 Issue / PR。

## 功能

- **右侧工具栏鲸鱼图标**：安装后在右侧工具栏出现黑色的 dsh 鲸鱼图标，点击即可打开 Dsh Dock 工具窗口
- **双启动方式（设置可选）**：
  - **WSL 中启动**：通过 `wsl.exe` 在 WSL 里运行 dsh（参考 `ldsh.cmd` 的启动思路），
    自动加载 WSL 用户的 `~/.bashrc`，因此 nvm/PATH 安装的 dsh 都能直接用，不绑定任何本机路径
  - **Windows 直接启动**：直接在 Windows 上隐藏后台运行 `dsh web`（未全局安装时自动改用
    `npx @deepseek-ai/dsh web` 启动）。启动通过**隐藏的 PowerShell** 调用执行，
    若安全软件（杀毒/防火墙）弹出拦截提示，请选择**允许/放行**，否则可能导致启动失败
  - 两种启动方式的**端口与附加参数分别配置**（互不影响），切换启动方式无需改回设置
- **内置浏览器窗口**：侧边栏内嵌 JCEF 浏览器，WebUI 直接显示在工具窗口中，无需打开系统浏览器
- **已启动自动识别**：如果对应端口已被监听（比如你自己已经启动了 dsh），插件会**跳过启动步骤**，
  直接在内置浏览器中打开 WebUI，不会重复启动进程；这类「外部启动的 dsh」也不会被插件误停
- **右键发送代码/文件到 Dsh Dock**：在编辑器中选中代码或在项目视图中选中文件，右键即可把
  `@路径` 引用直接发送到 Dsh 输入框（自动打开工具窗口、必要时自动启动 dsh）：
  - 选中代码 → `@<path>#L<start>-<end>`（如 `@/mnt/c/.../.gitignore#L5-6`）
  - 选中文件 → `@<path>`（如 `@/mnt/c/.../gradlew`）
  - 路径按启动模式自动转换（WSL 模式用 `/mnt/...`，Windows 模式用 `C:/...`），
    与 dsh 的 `@路径` 文件引用约定一致，模型需要时会通过 read 工具读取
- **项目空间默认项目根目录**：以当前打开的项目根路径作为 dsh 的工作目录启动，
  并自动把当前项目同步为 WebUI 的会话工作空间（即使上次用过别的项目，也会切回当前项目）。
  多个窗口同时打开插件页时，工作空间跟随当前**活动窗口**：后台窗口不刷新页面、不抢占，
  切换到某窗口时若工作空间已是自己的项目则不打扰（dsh 页面会自动重连自愈），必要时才切回
- **主题/语言**：WebUI 主题跟随 IDE（暗色 IDE 自动深色）；语言可选跟随 IDE/浏览器、简体中文或 English
  （跟随模式下按 IDE 界面语言自动同步 dsh 语言偏好：英文 IDE 显示英文 WebUI，中文 IDE 显示中文 WebUI）
- **dsh 编辑后自动刷新**：dsh 的文件写入类工具（Edit / str_replace_editor / write 等）成功执行完时，
  自动刷新对应文件的 VFS 并把已打开的编辑器立即更新为新代码（WSL 模式下 Windows 侧收不到文件变更
  通知的老问题也一并解决）；有未保存修改的文件不会被覆盖；默认开启，可在设置中关闭
- **进程管理**：启动 / 停止 / 刷新 / 在系统浏览器中打开，状态一目了然
- **操作日志**：底部日志面板展示 dsh 进程输出，方便排查问题
- **宽版本支持**：支持 IntelliJ IDEA 2022.3 (223) 及以上的所有版本（不设上限），并打包 Kotlin stdlib 以兼容旧版 IDE

## 使用

1. 使用 IDEA 打开本项目，等待 Gradle 同步完成
2. 运行 `Run Plugin`（`gradlew runIde`），会启动一个带插件的测试 IDE 实例（IC 2024.2.5）
3. 在测试 IDE 右侧工具栏点击黑色的鲸鱼图标，打开 Dsh Dock 工具窗口
4. 首次打开会自动启动 dsh，并加载 `http://localhost:3080/`
5. 如果端口 3080 已有 dsh 在运行（包括你自己启动的 dsh 进程），插件会跳过启动、直接加载 WebUI，而不会重复启动
6. 未全局安装 dsh 也没关系：插件会自动使用官方启动命令 `npx @deepseek-ai/dsh web`（首次运行会自动下载），
   不需要手动执行 `npm i -g @deepseek-ai/dsh`

### 右键发送代码/文件

- 在编辑器里**选中一段代码**，右键 → **发送选中代码到 Dsh Dock**：
  会在 Dsh 输入框追加 `@<文件路径>#L<起>-<止>`（行号从 1 开始、含端点），
  例如选中 `.gitignore` 的第 5-6 行 → `@C:/Workspace/.../.gitignore#L5-6`（Windows 模式）
- 在项目视图中**选中文件/目录**，右键 → **发送文件路径到 Dsh Dock**：
  会在 Dsh 输入框追加 `@<路径>`，例如 `@C:/Workspace/.../gradlew`
- 发送时若 Dsh Dock 工具窗口未打开会自动打开；若 dsh 尚未启动会自动启动；输入框会聚焦并追加引用
  （每次发送都会追加，可对同一段代码多次发送）；发送的是引用文本，
  按 Enter 后模型会在需要时用 read 工具读取对应文件

> 路径按启动模式自动区分：
>
> | 启动模式 | 引用示例 | 说明 |
> | --- | --- | --- |
> | WSL 中启动 | `@/mnt/c/Workspace/.../.gitignore#L5-6` | dsh 运行在 WSL 内，路径为 WSL 路径 |
> | Windows 直接启动 | `@C:/Workspace/.../.gitignore#L5-6` | dsh 运行在 Windows，路径为 Windows 路径 |
>
> 通过 `\\wsl$\<distro>\...` 打开的项目：WSL 模式转成 `/...`，Windows 模式保留 `//wsl$/...`；
> 路径含空格时按 dsh 约定加引号（`@"path with spaces"`）。

### 测试（使用 mybatis-sql-parser 的 IDEA 版本）

```bash
./gradlew runIde
```

### 打包

```bash
./gradlew buildPlugin
# 产物: build/distributions/dsh-idea-plugin-1.0.1.zip
# 通过 IDEA: Settings -> Plugins -> Install Plugin from Disk... 安装
```

## 设置

工具窗口右上角齿轮按钮（或 `Settings -> Other Settings -> Dsh Dock`）可配置：

| 配置项 | 说明 | 默认值 |
| --- | --- | --- |
| 启动方式 | `在 Windows 中直接启动`（默认）或 `在 WSL 中启动`（Windows+WSL） | Windows |
| WSL 端口 | WSL 模式下 dsh web 监听端口（WSL 与 Windows 可各自使用不同端口） | `3090` |
| WSL 附加参数 | WSL 模式下追加到 `dsh web` 后的参数（如 `--host 0.0.0.0`） | 空 |
| Windows 端口 | Windows 模式下 dsh web 监听端口 | `3080` |
| Windows 附加参数 | Windows 模式下追加到 `dsh web` 后的参数（如 `--host 0.0.0.0`） | 空 |
| 自动启动 | 打开工具窗口时自动启动 dsh | 开启 |
| 同时打开系统浏览器 | WebUI 就绪后额外用系统浏览器打开 | 关闭 |
| 自动刷新编辑器 | dsh 编辑文件后自动刷新 VFS 与已打开的编辑器（监听 dsh 事件流，工具成功执行后触发） | 开启 |
| WebUI 主题跟随 IDE | 暗色 IDEA 时内嵌 WebUI 自动使用深色主题 | 开启 |
| WebUI 语言 | 单选：跟随 IDE 语言 / 简体中文 / English（跟随模式下按 IDE 界面语言自动同步 dsh 语言偏好，如英文 IDE → 英文 WebUI） | 跟随 |

> 端口与附加参数按启动方式分别配置，**实际启动时只使用当前选中的启动方式对应的那一组**；
> 切换到另一种启动方式无需改回端口/参数。

> 兼容旧版本：插件设置是通用化的，WSL 发行版、NVM 路径、ldsh.cmd 等本机细节
> 一律不配置、不持久化，由用户自己的环境负责，换机器/换 IDE 无需重新配置。

## 原理

- **WSL 模式**：插件以**登录 + 交互 shell**（`wsl.exe bash -lic`，等价于你自己的终端环境）执行
  一个生成的 `dsh-run-wsl-*.sh` 脚本（切换项目工作目录、加载 `~/.profile` / `~/.bashrc`，
  并兜底从 `NVM_DIR` 或 `~/.nvm` 加载 nvm），再通过隐藏的 PowerShell 调用，避免弹出控制台窗口
- **Windows 模式**：生成 `dsh-run-win-*.cmd`（`cd /d` 项目目录 + `dsh web --port <port>`），
  通过隐藏的 PowerShell 执行（若安全软件拦截 PowerShell 请放行，否则启动可能失败）
- dsh 进程的标准输出/错误输出被捕获到工具窗口的日志面板
- 轮询 `127.0.0.1:<port>` 端口，就绪后由内嵌 JCEF 浏览器加载 WebUI
- **工作空间跟随项目**：dsh 的"工作空间"是持久化注册表（存于 `~/.dsh/storages`，跨进程重启保留），
  WebUI 启动时自动选中**最近活跃的工作空间**（按会话时间排序），与 dsh 进程的启动目录无关。
  因此插件在每次加载 WebUI 前，通过 dsh 自带的 `/api` RPC 通道（`POST /api/workspace.create` 等，
  与 dsh 客户端同一协议、经 loopback 信任围栏放行）把当前项目注册为工作空间，并在它不是"最近"时
  新建一个会话提升其新鲜度——这样页面初始选中就会落在当前项目上，而不是上次用过的项目。
  **多窗口隔离**：多个窗口共用同一个 dsh 实例（同一进程的多个项目窗口、或复用同一端口的多个
  IDE 实例），为避免互相把工作空间抢成别的项目，插件只在**当前活动窗口**同步工作空间：
  后台窗口不刷新页面、不抢占「项目空间」；窗口重新获得焦点时若工作空间已是自己的项目则保持
  页面（dsh 页面在服务重启后由客户端自动重连自愈），必要时才同步并切回自己的项目。
  同时还会：
  - **空白会话卫生**：归档项目工作空间里遗留的空白会话（空会话无内容损失）。dsh 的"新会话"
    会**复用**工作空间里已有的空白会话（它就是"新会话"槽），遗留的旧空白会让"新会话"点了没反应；
    归档后每次都会新建真正的新会话
  - **条件清除持久化会话选择**：dsh WebUI 会把"上次打开的会话"持久化到浏览器 localStorage
    （`dsh.sessions.current`），页面加载时恢复它、从而跳过工作空间初始选中。插件在加载前检查：
    持久化的会话不属于当前项目工作空间时清除它（让页面落到当前项目）；属于则保留（回到原会话）
- **防止 dsh 打开系统浏览器**：dsh 自 v0.1.0-rc.8 起 `dsh web` 默认会用系统浏览器打开 UI，
  与插件内嵌浏览器冲突。插件启动前会探测 dsh 版本，仅在支持 `--no-open` 的版本上为 `dsh web`
  自动附加 `--no-open`（`web --port <port> --no-open`）；不支持的旧版本会自动跳过该参数，避免启动报错
- **右键发送引用**：`@路径` 引用由注入脚本写入 WebUI 输入框——dsh 输入框是 React 受控
  组件，直接改 `value` 会被覆盖，因此用原生 `value` setter + 派发 `input` 事件更新草稿；
  页面未就绪时引用先暂存，主框架 `onLoadEnd` 后自动补发
- **dsh 编辑后自动刷新**：WSL 模式（或其他外部进程）写文件时，Windows 侧的文件变更通知
  （IDEA 原生文件监听依赖它）经常不触发，导致 IDEA 一直显示旧代码。插件监听 dsh 会话
  事件流 `GET /api/events.mux`（新版 dsh 为 WebSocket、旧版为 SSE，自动降级）：在
  `tool/call` 事件中登记文件写入类工具（str_replace_editor / edit / write 等）的调用与
  文件路径，`tool/result` 事件确认工具**成功**（非错误）后把路径换算回 Windows 侧
  （`/mnt/c/...` -> `C:/...`），定向刷新该文件的 VFS 并重载编辑器中无未保存修改的文档；
  失败的工具调用不刷新；事件流不可用（旧版 dsh）时只记一行日志，不影响 WebUI 正常使用
- **只停自己启动的 dsh**：WSL 模式启动时把 dsh 的真实 PID 写入标识文件（`dsh-owner-*.pid`），
  停止/退出清理时 `kill` 该 PID；Windows 模式 `taskkill` 本插件持有的进程树。
  **外部启动的同端口 dsh（如手动 `ldsh.cmd`）绝不会被误杀**
- **退出清理三层保障**（异步、不拖慢 IDEA 关闭）：IDEA 退出流程的 `AppLifecycleListener`
  早期回调（异步触发，不阻塞退出）+ JVM shutdown hook 兜底 + 独立 PowerShell 看门狗
  （监视 IDE 进程，IDE 消失且端口仍开时自动执行停止脚本；即使 IDE 被强杀/崩溃也会清理）

## 常见问题

- **WebUI 区域显示空白/加载失败**：确认 `Settings -> Tools -> Web Browsers and Preview` 中
  JCEF 已启用（或注册表键 `ide.browser.jcef.enabled` 为 true）；插件内置回退面板可直接用系统浏览器打开，
  且 JCEF 不可用时 WebUI 就绪后会自动改用系统浏览器打开
- **Windows 模式启动被安全软件拦截**：Windows 模式通过**隐藏的 PowerShell** 执行启动命令，
  若杀毒软件/防火墙弹出拦截提示，请将 `powershell.exe`（及其生成的 `cmd.exe` / `node` 进程）
  **加入白名单/放行**（或临时关闭防护后重试）；已被放行仍失败时，请查看工具窗口底部日志面板排查
- **提示找不到 dsh / Node.js**：未全局安装 dsh 时，插件会自动改用官方启动命令
  `npx @deepseek-ai/dsh web`（首次运行自动下载），一般无需手动安装。仅当机器上**完全没有 Node.js 环境**
  （node / npm / npx 均不可用）时才会启动失败并提示：
  - Windows 模式：安装 [Node.js](https://nodejs.org/) 后重新打开终端，再点击「启动」
  - WSL 模式：在 WSL 中安装 Node.js（建议 [nvm](https://github.com/nvm-sh/nvm)），再点击「启动」
  - 若已安装仍提示找不到，WSL 模式请确认 WSL 终端里 `node -v` / `npx --version` 能正常执行；
    详细日志见工具窗口底部日志面板
- **项目根路径在 WSL 中的形态**：Windows 路径 `C:\x` 会被转换为 `/mnt/c/x`；
  通过 `\\wsl$\<distro>\...` 打开的项目会转换为对应的 WSL 路径
- **右键发送后输入框没有出现引用**：确认 JCEF 内嵌浏览器可用（工具窗口中间显示的是 WebUI 页面）；
  若 WebUI 尚未加载完成，引用会等页面就绪后自动补发；仍不行就刷新一次 WebUI 再试
- **dsh 编辑文件后 IDEA 没有自动更新**：确认 `Settings -> Other Settings -> Dsh Dock` 中
  「dsh 编辑文件后自动刷新编辑器」已开启，且工具窗口日志出现过「文件同步监听已连接」；
  若 dsh 版本过旧不支持事件流，插件会静默降级（日志会提示），可手动 `Ctrl+Alt+Y` 同步
  或重新打开文件；另外，文件在 IDEA 里有**未保存修改**时不会被自动覆盖（这是防丢改动的保护）

## 国内镜像加速

项目构建已默认配置国内镜像，避免直连国外仓库慢的问题：

| 下载内容 | 配置位置 | 镜像 |
| --- | --- | --- |
| Maven Central / Gradle Plugin Portal 依赖 | `settings.gradle.kts`、`build.gradle.kts` | `https://maven.aliyun.com/repository/{public,central,gradle-plugin}` |
| Gradle 发行包 (wrapper) | `gradle/wrapper/gradle-wrapper.properties` | `https://mirrors.cloud.tencent.com/gradle/gradle-8.13-bin.zip` |
| IntelliJ 平台包 (IC-2024.2.5) | 无阿里云镜像 | 直连 `cache-redirector.jetbrains.com`，首次构建约 1GB，属正常 |