# Changelog / 变更日志

## [Unreleased]

### 中文 (Chinese)

- **变更（Changed）**：设置页布局优化：端口与附加参数改为**按启动方式（WSL / Windows）分别配置**，
  每种启动方式可设置不同的监听端口与附加参数，实际启动时只使用当前选中的启动方式对应的配置。
  注意：旧版单一的「端口」「附加参数」配置不再保留，请到设置页重新确认
  （默认 WSL `3090` / Windows `3080`，附加参数均为空）

### English

- **Changed**: Settings page layout optimized: port and extra arguments are now configured
  **separately per launch mode (WSL / Windows)**, so each mode can use its own listening port and
  extra arguments; only the configuration of the currently selected launch mode is used at launch.
  Note: the old single "Port" / "Extra arguments" settings are no longer kept — please re-check
  them on the settings page (defaults are `3090` for WSL / `3080` for Windows, both empty for
  extra arguments).

## [1.0.1] - 2026-08-17

### 中文 (Chinese)

- **变更（Changed）**：未全局安装 dsh 时，自动使用官方启动命令 `npx @deepseek-ai/dsh web` 启动（首次运行会自动下载），
  无需手动执行 `npm i -g`
- **变更（Changed）**：默认端口改为 `3080`；默认启动方式改为「在 Windows 中直接启动」
- **变更（Changed）**：未检测到 Node.js 环境（node / npm / npx 均不可用）时不再尝试启动，并提示先安装 Node.js
- **修复（Fixed）**：多窗口状态保持一致：同时打开多个 IDE 窗口时，任一窗口启动或停止 dsh，所有窗口的状态显示都会同步刷新，
  不再出现某个窗口显示「未启动」但实际已在运行的情况
- **修复（Fixed）**：兼容性改进：修复新版 IDE（2024.2 及以上）打开新窗口时的报错，以及编辑器右键菜单的偶发报错
- **修复（Fixed）**：兼容更多 IDEA 版本：插件现在可安装到 IntelliJ IDEA 2022.3 及之后的所有版本（不再有版本上限）
- **修复（Fixed）**：修复 Windows 下启动失败与日志乱码：批处理改用 goto 结构（消息里的圆括号不再导致
  cmd 解析错误），并统一 Windows 启动通道的 UTF-8 输出编码；启动检查顺序修正为
  先全局 dsh 后 npx，避免误报"无 Node.js 环境"
- **修复（Fixed）**：修复工作空间同步偶发失败：同步失败时页面加载完成后自动刷新一次重试
  （与手动刷新等效，无需手动操作）

### English

- **Changed**: When dsh is not installed globally, the plugin now starts it automatically with the
  official command `npx @deepseek-ai/dsh web` (auto-downloaded on first run) — no need to run
  `npm i -g` manually
- **Changed**: Default port changed to `3080`; default launch mode changed to "Start directly in Windows"
- **Changed**: No longer attempts to start when no Node.js environment is detected (node / npm / npx
  all unavailable); instead, it prompts to install Node.js first
- **Fixed**: Consistent state across windows: with several IDE windows open, starting or stopping
  dsh in any window now refreshes the status in all windows — no more "Not started" shown in one
  window while dsh is actually running
- **Fixed**: Compatibility improvements: fixed the error when opening a new window on newer IDEs
  (2024.2+) and the occasional error in the editor context menu
- **Fixed**: Wider IDE support: the plugin now installs on all IntelliJ IDEA versions from 2022.3
  onward (no upper version limit)
- **Fixed**: Fixed startup failure and garbled logs on Windows: batch scripts now use a `goto`
  structure (parentheses in messages no longer break cmd parsing) and the Windows launch channel
  uses unified UTF-8 output; the startup check order was corrected to look for a global `dsh`
  before `npx`, avoiding false "No Node.js environment" reports
- **Fixed**: Fixed occasional workspace sync failures: after a failed sync, the page now
  auto-refreshes once when loading completes (equivalent to a manual refresh — no manual action needed)

## [1.0.0] - 2026-08-16

### 中文 (Chinese)

- **新增（Added）**：右侧工具栏 dsh 鲸鱼图标，点击打开 Dsh Dock 工具窗口
- **新增（Added）**：一键启动 dsh web（WSL / Windows 两种方式），隐藏后台运行，WebUI 直接显示在工具窗口内嵌的浏览器中
- **新增（Added）**：项目空间默认使用当前打开的 IDEA 项目根路径
- **新增（Added）**：启动 / 停止 / 刷新 / 在系统浏览器中打开 / 设置
- **新增（Added）**：操作日志面板、端口自动探测、打开工具窗口自动启动
- **新增（Added）**：支持 IDEA 2022.1 ~ 2025.2
- **新增（Added）**：右键发送代码/文件到 Dsh Dock：编辑器选中代码 → `@路径#L行号`，项目视图选中文件 → `@路径`，
  路径按启动模式自动转换（WSL: `/mnt/...`；Windows: `C:/...`），发送后自动打开工具窗口并填入输入框
- **新增（Added）**：工作空间跟随当前项目：打开 WebUI 时自动把当前项目设为工作空间，不会停留在上次用过的项目，
  并自动清理遗留的空白会话以保证「新会话」始终可用
- **新增（Added）**：启动失败提示：未安装 dsh（或启动失败）时快速弹窗提示，并给出安装命令
  `npm i -g @deepseek-ai/dsh`（WSL / Windows 模式均覆盖）
- **新增（Added）**：WebUI 语言跟随 IDE：中文界面下自动切换为中文（不再受浏览器默认语言影响）

### English

- **Added**: Whale icon in the right tool window bar; click it to open the Dsh Dock tool window
- **Added**: One-click start of `dsh web` (both WSL and Windows modes), running hidden in the
  background, with the WebUI shown directly in the built-in browser of the tool window
- **Added**: The workspace defaults to the root path of the currently open IDEA project
- **Added**: Start / Stop / Refresh / Open in system browser / Settings
- **Added**: Operation log panel, automatic port probing, auto-start when the tool window opens
- **Added**: Supports IntelliJ IDEA 2022.1 – 2025.2
- **Added**: Right-click to send code/files to Dsh Dock: selected code → `@path#Lline`,
  selected file → `@path`, with paths converted automatically per launch mode (WSL: `/mnt/...`,
  Windows: `C:/...`); the tool window opens automatically and the reference is inserted into the
  input box
- **Added**: Workspace follows the current project: the current project is set as the workspace
  when the WebUI opens instead of staying on the last used project; leftover empty sessions are
  cleaned up automatically so "New session" always works
- **Added**: Startup failure notification: a quick popup explains the failure when dsh is missing
  (or fails to start) and shows the install command `npm i -g @deepseek-ai/dsh` (covers both WSL
  and Windows modes)
- **Added**: WebUI language follows the IDE: automatically switches to Chinese when the IDE UI is
  Chinese (no longer affected by the browser's default language)
```