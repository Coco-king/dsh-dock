# Changelog / 变更日志

## [1.0.3] - 2026-08-27

### 中文 (Chinese)

- **新增（Added）**：插件界面、右键菜单、通知与启动日志支持国际化并自动跟随 IDE 语言——中文 IDE 显示中文，英文 IDE 显示英文（简体/繁体中文 IDE 均显示简体中文）
- **新增（Added）**：右键菜单新增「复制 AI 引用」——选中代码或文件后一键把 AI 引用复制到剪贴板，方便粘贴到任意支持 @引用 的输入框
- **新增（Added）**：dsh 编辑完文件后，IDEA 里打开着的编辑器会自动更新为新内容，不用再重新打开文件（此前 WSL 模式下会一直显示旧代码的问题一并解决）；新增「dsh 编辑文件后自动刷新编辑器」开关（默认开启，可在 `Settings -> Other Settings -> Dsh Dock` 关闭），文件在 IDEA 中还有未保存的修改时不会被自动覆盖
- **修复（Fixed）**：修复 dsh 界面语言与 IDE 不一致的问题——英文 IDE 下 dsh 也显示英文、中文 IDE 下显示中文，切换 IDE 语言后无需再手动调整
- **修复（Fixed）**：修复工具窗口状态栏端口号带千位逗号的问题（如 3,090 → 3090）

### English

- **Added**: The plugin UI, context menus, notifications and launch logs are now internationalized and follow the IDE language automatically — a Chinese IDE shows Chinese, an English IDE shows English (both Simplified and Traditional Chinese IDEs show Simplified Chinese)
- **Added**: New "Copy AI Reference" context-menu action — copy the AI reference of the selected code or file to the clipboard with one click, ready to paste into any @reference-aware input
- **Added**: After dsh finishes editing a file, open editors in IDEA now update to the new content automatically — no more stale code or reopening files (the WSL mode issue of the IDE always showing old code is also fixed); a new "Auto-refresh editors after dsh edits files" toggle (enabled by default, under `Settings -> Other Settings -> Dsh Dock`) turns this off, and files with unsaved local changes are never overwritten
- **Fixed**: The dsh interface language is now consistent with the IDE — an English IDE shows English, a Chinese IDE shows Chinese, with no manual adjustment needed after switching the IDE language
- **Fixed**: The tool-window status bar no longer shows a thousands separator in the port number (e.g. 3,090 → 3090)

## [1.0.2] - 2026-08-24

### 中文 (Chinese)

- **修复（Fixed）**：修复多个窗口同时打开插件页时（同一 IDE 的多个项目窗口，或复用同一端口的
  多个 IDE 实例），共享 dsh 的「项目空间」可能被其他窗口抢占、切换成别的项目路径的问题。
  现在工作空间只跟随当前**活动窗口**：后台窗口不刷新页面、也不抢占工作空间；窗口重新获得焦点时
  若工作空间已是自己的项目则保持页面（dsh 页面会自动重连自愈），需要时才切回自己项目的路径
- **修复（Fixed）**：dsh 升级到 v0.1.0-rc.8 后 `dsh web` 默认会用系统浏览器打开 UI，
  与插件内嵌浏览器冲突。插件现在会自动为受支持版本附加 `--no-open`；
  低于该版本的 dsh 不认识此参数，会自动跳过以避免启动报错
- **修复（Fixed）**：启动时不再出现"先加载失败、再整页刷新重试"的多次刷新/多次工作空间同步 ——
  同步改为在加载页面前带短重试（约 8s 上限），成功后一次性加载页面；
  激活恢复只在窗口真正从"非活动"切回"活动"时触发，启动时不再误触发

### English

- **Fixed**: Fixed an issue where, when multiple windows had the plugin page open (multiple project
  windows in one IDE, or multiple IDE instances reusing the same port), the shared dsh "project space"
  could be hijacked by another window and point to a different project path. Now the workspace follows
  only the **active window**: a background window neither refreshes its page nor hijacks the workspace;
  when a window regains focus it keeps its page if the workspace is already its own (the dsh page
  reconnects and recovers automatically), and only switches back to its own project when needed
- **Fixed**: Since dsh v0.1.0-rc.8, `dsh web` opens the UI in the default browser by default, which
  conflicts with the plugin's embedded browser. The plugin now appends `--no-open` automatically where
  the installed dsh supports it; on older versions that reject the flag it is skipped to avoid a
  startup error
- **Fixed**: Startup no longer goes through "load page first, then full-page-refresh retry" with
  repeated reloads and repeated workspace syncs. The workspace sync now retries briefly (up to ~8s)
  **before** loading the page, so the page loads once with the correct workspace; activation
  recovery only triggers on a real "inactive -> active" window switch and no longer misfires at startup

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