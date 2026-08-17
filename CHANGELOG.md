# Changelog / 变更日志

## [1.0.1] - 2026-08-17

### 中文 (Chinese)

- **新增（Added）**：右侧工具栏鲸鱼图标，点击打开 Dsh Dock 工具窗口，一键启动 `dsh web`，
  WebUI 直接显示在工具窗口内置浏览器中
- **新增（Added）**：支持 **WSL / Windows 两种启动方式**；未全局安装 dsh 时自动改用官方命令
  `npx @deepseek-ai/dsh web` 启动（首次运行自动下载）
- **新增（Added）**：右键发送代码/文件到输入框（`@路径` 引用，按启动方式自动转换）；工作空间自动跟随当前项目
- **新增（Added）**：端口自动探测——端口已被监听时跳过启动、直接打开 WebUI，不会重复启动；启动失败时自动提示并给出安装命令
- **变更（Changed）**：设置页支持按启动方式分别配置端口与附加参数
  （旧版单一的「端口」「附加参数」不再保留）；默认端口 WSL `3090` / Windows `3080`，
  默认启动方式为 Windows 直接启动
- **变更（Changed）**：未检测到 Node.js 环境（node / npm / npx 均不可用）时不再尝试启动，并提示先安装 Node.js
- **修复（Fixed）**：兼容 IntelliJ IDEA 2022.3 及以上全版本；修复多窗口状态不同步、
  Windows 启动失败与日志乱码、工作空间同步偶发失败等问题

### English

- **Added**: Whale icon in the right tool window bar; click to open the Dsh Dock tool window and
  start `dsh web` with one click — the WebUI is shown directly in the built-in browser of the
  tool window
- **Added**: Two launch modes — start in **WSL** or **directly in Windows**; when dsh is not
  installed globally, it starts automatically via the official command `npx @deepseek-ai/dsh web`
  (auto-downloaded on first run)
- **Added**: Right-click to send code/files to the input box as `@path` references (converted
  automatically per launch mode); the workspace follows the current project automatically
- **Added**: Automatic port probing — skips launching and opens the WebUI directly when the port
  is already in use, without duplicate processes; startup failures prompt with the install command
- **Changed**: Settings support separate ports and extra arguments per launch mode (the old single
  "Port" / "Extra arguments" are no longer kept); defaults are `3090` (WSL) / `3080` (Windows),
  with Windows direct launch as the default mode
- **Changed**: No longer attempts to start when no Node.js environment is detected
  (node / npm / npx all unavailable); prompts to install Node.js first
- **Fixed**: Compatible with all IntelliJ IDEA versions from 2022.3 onward; fixed inconsistent
  state across windows, Windows startup failures with garbled logs, and occasional workspace sync
  failures
```