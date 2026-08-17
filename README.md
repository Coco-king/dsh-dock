# Dsh Dock - DeepSeek Harness IDEA 插件

<!-- Plugin description -->
Dsh Dock is an IntelliJ IDEA plugin that runs the DeepSeek Harness command-line tool (`dsh web`) and opens its WebUI in a built-in JCEF browser docked to the right tool window, with one click from the IDE toolbar. It works with both WSL and native Windows startup, and uses the current project root as the dsh workspace.

Dsh Dock 是一个 IntelliJ IDEA 插件：在 IDE 右侧工具栏提供黑色的 dsh 鲸鱼图标，一键启动 DeepSeek Harness（`dsh web`），并在内置的侧边栏浏览器窗口中打开 WebUI。支持两种启动方式：在 WSL 中启动，或在 Windows 中直接启动。项目空间默认使用当前打开的 IDEA 项目根路径。

Right-click actions can send code or file references (e.g. `@path#L5-6`) straight into the Dsh input box, and the plugin supports IntelliJ IDEA 2022.1 (221) and newer.
<!-- Plugin description end -->

## 功能

- **右侧工具栏鲸鱼图标**：安装后在右侧工具栏出现黑色的 dsh 鲸鱼图标，点击即可打开 Dsh Dock 工具窗口
- **双启动方式（设置可选）**：
  - **WSL 中启动**：通过 `wsl.exe` 在 WSL 里运行 dsh（参考 `ldsh.cmd` 的启动思路），
    自动加载 WSL 用户的 `~/.bashrc`，因此 nvm/PATH 安装的 dsh 都能直接用，不绑定任何本机路径
  - **Windows 直接启动**：直接在 Windows 上隐藏后台运行 `dsh web`（要求 dsh 已加入 Windows PATH）
- **内置浏览器窗口**：侧边栏内嵌 JCEF 浏览器，WebUI 直接显示在工具窗口中，无需打开系统浏览器
- **右键发送代码/文件到 Dsh Dock**：在编辑器中选中代码或在项目视图中选中文件，右键即可把
  `@路径` 引用直接发送到 Dsh 输入框（自动打开工具窗口、必要时自动启动 dsh）：
  - 选中代码 → `@<path>#L<start>-<end>`（如 `@/mnt/c/.../.gitignore#L5-6`）
  - 选中文件 → `@<path>`（如 `@/mnt/c/.../gradlew`）
  - 路径按启动模式自动转换（WSL 模式用 `/mnt/...`，Windows 模式用 `C:/...`），
    与 dsh 的 `@路径` 文件引用约定一致，模型需要时会通过 read 工具读取
- **项目空间默认项目根目录**：以当前打开的项目根路径作为 dsh 的工作目录启动，
  并自动把当前项目同步为 WebUI 的会话工作空间（即使上次用过别的项目，也会切回当前项目）
- **主题/语言**：WebUI 主题跟随 IDE（暗色 IDE 自动深色）；语言可选跟随 IDE/浏览器、简体中文或 English
- **进程管理**：启动 / 停止 / 刷新 / 在系统浏览器中打开，状态一目了然
- **操作日志**：底部日志面板展示 dsh 进程输出，方便排查问题
- **宽版本支持**：支持 IntelliJ IDEA 2022.1 (221) 及以上的所有版本（不设上限），并打包 Kotlin stdlib 以兼容旧版 IDE

## 使用

1. 使用 IDEA 打开本项目，等待 Gradle 同步完成
2. 运行 `Run Plugin`（`gradlew runIde`），会启动一个带插件的测试 IDE 实例（IC 2024.2.5）
3. 在测试 IDE 右侧工具栏点击黑色的鲸鱼图标，打开 Dsh Dock 工具窗口
4. 首次打开会自动启动 dsh，并加载 `http://localhost:3090/`
5. 如端口 3090 已有 dsh 在运行，插件会直接加载 WebUI，而不会重复启动

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
| 启动方式 | `在 WSL 中启动`（Windows+WSL）或 `在 Windows 中直接启动`（dsh 需在 Windows PATH 中） | WSL |
| 端口 | dsh web 监听端口 | `3090` |
| 自动启动 | 打开工具窗口时自动启动 dsh | 开启 |
| 同时打开系统浏览器 | WebUI 就绪后额外用系统浏览器打开 | 关闭 |
| WebUI 主题跟随 IDE | 暗色 IDEA 时内嵌 WebUI 自动使用深色主题 | 开启 |
| WebUI 语言 | 单选：跟随 IDE 语言 / 简体中文 / English（JCEF 默认 en-US，跟随模式下按 IDE 语言自动注入中文） | 跟随 |
| 附加参数 | 追加到 `dsh web` 后的参数（如 `--host 0.0.0.0`） | 空 |

> 兼容旧版本：插件设置是通用化的，WSL 发行版、NVM 路径、ldsh.cmd 等本机细节
> 一律不配置、不持久化，由用户自己的环境负责，换机器/换 IDE 无需重新配置。

## 原理

- **WSL 模式**：插件以**登录 + 交互 shell**（`wsl.exe bash -lic`，等价于你自己的终端环境）执行
  一个生成的 `dsh-run-wsl-*.sh` 脚本（切换项目工作目录、加载 `~/.profile` / `~/.bashrc`，
  并兜底从 `NVM_DIR` 或 `~/.nvm` 加载 nvm），再通过隐藏的 PowerShell 调用，避免弹出控制台窗口
- **Windows 模式**：生成 `dsh-run-win-*.cmd`（`cd /d` 项目目录 + `dsh web --port <port>`），
  通过隐藏的 PowerShell 执行
- dsh 进程的标准输出/错误输出被捕获到工具窗口的日志面板
- 轮询 `127.0.0.1:<port>` 端口，就绪后由内嵌 JCEF 浏览器加载 WebUI
- **工作空间跟随项目**：dsh 的"工作空间"是持久化注册表（存于 `~/.dsh/storages`，跨进程重启保留），
  WebUI 启动时自动选中**最近活跃的工作空间**（按会话时间排序），与 dsh 进程的启动目录无关。
  因此插件在每次加载 WebUI 前，通过 dsh 自带的 `/api` RPC 通道（`POST /api/workspace.create` 等，
  与 dsh 客户端同一协议、经 loopback 信任围栏放行）把当前项目注册为工作空间，并在它不是"最近"时
  新建一个会话提升其新鲜度——这样页面初始选中就会落在当前项目上，而不是上次用过的项目。
  同时还会：
  - **空白会话卫生**：归档项目工作空间里遗留的空白会话（空会话无内容损失）。dsh 的"新会话"
    会**复用**工作空间里已有的空白会话（它就是"新会话"槽），遗留的旧空白会让"新会话"点了没反应；
    归档后每次都会新建真正的新会话
  - **条件清除持久化会话选择**：dsh WebUI 会把"上次打开的会话"持久化到浏览器 localStorage
    （`dsh.sessions.current`），页面加载时恢复它、从而跳过工作空间初始选中。插件在加载前检查：
    持久化的会话不属于当前项目工作空间时清除它（让页面落到当前项目）；属于则保留（回到原会话）
- **右键发送引用**：`@路径` 引用由注入脚本写入 WebUI 输入框——dsh 输入框是 React 受控
  组件，直接改 `value` 会被覆盖，因此用原生 `value` setter + 派发 `input` 事件更新草稿；
  页面未就绪时引用先暂存，主框架 `onLoadEnd` 后自动补发
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
- **提示找不到 dsh/node 命令**：启动时若检测不到 dsh（未安装 `@deepseek-ai/dsh`），插件会
  快速失败并弹出「Dsh 启动失败」通知，提示安装命令 `npm i -g @deepseek-ai/dsh`
  （WSL 模式在 WSL 终端安装、nvm 环境需 `nvm use default`；Windows 模式装完需重开终端刷新 PATH）。
  若已安装仍提示找不到，WSL 模式请确认 WSL 终端里 `node -v` / `dsh --version` 能正常执行；
  详细警告见工具窗口底部日志面板
- **项目根路径在 WSL 中的形态**：Windows 路径 `C:\x` 会被转换为 `/mnt/c/x`；
  通过 `\\wsl$\<distro>\...` 打开的项目会转换为对应的 WSL 路径
- **右键发送后输入框没有出现引用**：确认 JCEF 内嵌浏览器可用（工具窗口中间显示的是 WebUI 页面）；
  若 WebUI 尚未加载完成，引用会等页面就绪后自动补发；仍不行就刷新一次 WebUI 再试

## 国内镜像加速

项目构建已默认配置国内镜像，避免直连国外仓库慢的问题：

| 下载内容 | 配置位置 | 镜像 |
| --- | --- | --- |
| Maven Central / Gradle Plugin Portal 依赖 | `settings.gradle.kts`、`build.gradle.kts` | `https://maven.aliyun.com/repository/{public,central,gradle-plugin}` |
| Gradle 发行包 (wrapper) | `gradle/wrapper/gradle-wrapper.properties` | `https://mirrors.cloud.tencent.com/gradle/gradle-8.13-bin.zip` |
| IntelliJ 平台包 (IC-2024.2.5) | 无阿里云镜像 | 直连 `cache-redirector.jetbrains.com`，首次构建约 1GB，属正常 |