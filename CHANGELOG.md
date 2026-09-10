# Changelog / 变更日志

## [1.2.0] - 2026-09-11

### 中文 (Chinese)

- **新增（Added）**：点击 dsh 界面里的文件路径（含消息中的文件提及、交付卡片的「打开」按钮）时，直接在 IDE 编辑器中打开该文件——能拿到行号时跳到对应行（如 read 工具的读取起始行），编辑/写入工具还会定位并选中写入的新内容；文件不在本机、是目录或路径无法解析时仍走 dsh 侧边栏预览。默认开启，可在 `Settings -> Other Settings -> Dsh Dock` 关闭
- **变更（Changed）**：最低支持版本提升到 IntelliJ IDEA 2023.1（231）——「自动启动」改用平台推荐的新机制，不再依赖将于未来移除的旧 API（2022.3 用户请升级 IDE）
- **变更（Changed）**：兼容最新版 dsh（0.1.5-rc.1），插件已针对该版本完成适配验证（dsh 0.1.0-rc.8 及以上版本均受支持）
- **变更（Changed）**：清理新版 IDE 中已废弃的接口调用，避免将来版本失效（无功能变化）
- **修复（Fixed）**：兼容 IntelliJ IDEA 2026.2 及以上版本——内嵌浏览器改由 IDE 的独立组件提供，此前插件在这些版本上启动会报错、工具窗口无法显示内嵌浏览器；现已恢复正常，2023.1 起的旧版本不受影响

### English

- **Added**: Clicking any clickable file path in the dsh UI (including file mentions in messages and the Open button on delivered-file cards) now opens the file in the IDE editor — jumping to the line when dsh UI data provides one (e.g. the line a read tool started from), and locating/selecting the newly written content for edit/write results; when the file is not on this machine, is a directory, or the path cannot be resolved, the dsh sidebar preview is still used. Enabled by default and can be turned off under `Settings -> Other Settings -> Dsh Dock`
- **Changed**: Minimum supported version raised to IntelliJ IDEA 2023.1 (231) — the "Auto-start" feature now uses the platform-recommended mechanism and no longer relies on an API scheduled for removal (users on 2022.3 should upgrade their IDE)
- **Changed**: Compatible with the latest dsh (0.1.5-rc.1) — verified against this release (all dsh versions from 0.1.0-rc.8 onward are supported)
- **Changed**: Removed calls to APIs deprecated in the latest IDE versions to avoid future breakage (no functional change)
- **Fixed**: Compatibility with IntelliJ IDEA 2026.2 and later — the embedded browser is now provided as a separate IDE component, which previously caused a startup error and left the tool window without an embedded browser on those versions; both work again, and older versions from 2023.1 remain unaffected

## [1.1.0] - 2026-09-04

### 中文 (Chinese)

- **新增（Added）**：新增「自动启动」设置：IDE 启动时后台预热 dsh 与内嵌浏览器，首次打开工具窗口几乎无需等待，退出时随 IDE 一并停止
- **新增（Added）**：工具窗口日志每行增加毫秒级时间戳
- **变更（Changed）**：⚠️ 兼容新版 dsh（0.1.2-rc.1 起）的启动令牌安全认证，插件最低支持 **dsh 0.1.0-rc.8 及以上版本**
- **变更（Changed）**：启动 dsh 固定附加 `--no-open`，启动更快（需 dsh 0.1.0-rc.8+）
- **变更（Changed）**：「刷新」立即重载页面，工作空间同步转后台，不再卡顿数秒
- **变更（Changed）**：右键发送文件路径/选中代码到 DSH 时，引用现在插入到 dsh 输入框的光标处（不再插到输入框末尾），并且路径后自动带一个空格
- **修复（Fixed）**：修复上次退出未关闭 Dsh Dock 窗口时，下次启动窗口一直空白的问题
- **修复（Fixed）**：兼容新版 dsh（0.1.2-rc.1 起）的认证与 API 变化，恢复工作空间同步与语言跟随，dsh 编辑文件后编辑器即时刷新（旧版 dsh 不受影响）
- **修复（Fixed）**：修复退出 IDE 时未关闭 Dsh Dock 窗口、再次打开 IDE 后 dsh 上的工作空间可能停在上一个项目的问题——现在只有确认工作空间已切到当前项目才算同步完成，无法确认时会自动重试，不再误报成功
- **修复（Fixed）**：修复工具窗口打开时预热中的浏览器未能被复用的问题（预热页面加载完成前窗口提前判定预热结束，导致页面重复创建、预热浏览器闲置）

### English

- **Added**: New "Auto-start" setting: dsh and the embedded browser are warmed up at IDE startup, so the first open of the tool window needs virtually no wait, and dsh stops along with the IDE
- **Added**: Millisecond timestamps in the tool window log
- **Changed**: ⚠️ Compatible with the launch-token security authentication of the new dsh (0.1.2-rc.1 onward); minimum supported dsh version is **0.1.0-rc.8 or later**
- **Changed**: `--no-open` is now always appended when starting dsh, making startup faster (requires dsh 0.1.0-rc.8+)
- **Changed**: Refresh now reloads the page immediately with the workspace sync moved to the background
- **Changed**: When sending a file path or selected code to DSH from the context menu, the reference is now inserted at the cursor position in the dsh input box (no longer appended to the end), with a trailing space added after the path
- **Fixed**: Fixed the Dsh Dock window staying empty after an IDE restart when it was left open at the previous shutdown
- **Fixed**: Compatibility with the new dsh web (0.1.2-rc.1 onward) authentication and API changes, restoring workspace sync and language following; editors refresh immediately after dsh edits files (older dsh versions are unaffected)
- **Fixed**: Fixed the workspace staying on the previous project after an IDE restart when the Dsh Dock window was left open at shutdown — the sync now only reports success after the current project is confirmed as the active workspace, and retries automatically when it cannot be confirmed
- **Fixed**: Fixed the pre-warmed browser not being reused when the tool window opened while warm-up was still running — the window no longer gives up on warm-up before its page is ready, avoiding a duplicate browser and page load

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