# Dsh Dock — DeepSeek Harness Plugin for JetBrains IDEs

DeepSeek Harness (dsh) inside your IDE: one click starts `dsh web` and its WebUI runs right in the
built-in browser panel — chat with DeepSeek models without a terminal or a separate browser.

**Supported dsh**: 0.1.0-rc.8 or later (the new-style authentication and APIs are supported too)

**English**

1. **One-click start**: click the whale icon in the right tool window bar (or Tools menu) to open the
   Dsh Dock tool window and start `dsh web`; the WebUI is shown in the built-in browser panel
2. **Two launch modes**: start in **WSL** or **directly in Windows**, each with its own port
   (3090 / 3080 by default) and extra arguments; if dsh is not installed globally it falls back to
   `npx @deepseek-ai/dsh web`, and a missing Node.js is reported with install hints
3. **Auto-detects a running dsh**: if the port is already in use, launching is skipped and the WebUI
   opens directly — an externally started dsh is never stopped; when the plugin has no launch token
   (new dsh versions) it reuses the browser's existing credentials, so everything keeps working
4. **Workspace follows the project**: the project root becomes the dsh workspace; with several open
   windows it follows the **active window** and every window's page stays pinned to its own project;
   each open starts a blank new session (a setting restores the last session instead)
5. **Right-click references**: selected code → `@path#L12-34`, a file or folder → `@path`, inserted
   into the Dsh input box (window and dsh start automatically); paths are converted per launch mode
   (`/mnt/c/...` in WSL, `C:/...` in Windows)
6. **Copy AI reference**: the same `@path` reference can be copied to the clipboard from the editor
   or project view context menu, ready to paste into any @-aware input
7. **Auto-refresh after dsh edits**: dsh session events are watched in real time — when a
   file-writing tool succeeds, the file and any open editors update instantly (unsaved changes are
   never overwritten); can be turned off
8. **File paths open in the IDE**: clicking a path, file mention or Open button in the WebUI opens
   the file in the IDE editor, at the line or the newly written content, and falls back to the dsh
   preview when it cannot be opened; can be turned off
9. **Sidebar locate and highlight**: opening the session list scrolls to the current session and
   highlights its workspace group, and keeps following when you switch workspaces or sessions
10. **Start timing and warm-up**: auto-start at IDE startup (dsh and the embedded browser are warmed
    up in the background, so the first open is almost instant), when the tool window opens, or
    manually; dsh stops together with the IDE
11. **Theme and language**: the WebUI theme follows the IDE; its language follows the IDE/browser or
    is forced to Simplified Chinese or English; the plugin's own UI, menus and notifications follow
    the IDE language
12. **Control panel and log**: start, stop, refresh, open in the system browser, live status, and a
    dsh log panel with millisecond timestamps; a gear button opens the settings
13. **No stray processes**: only the dsh started by the plugin is stopped; on IDE exit a three-layer
    cleanup stops it even if the IDE crashes
14. **Embedded-browser fallback**: when JCEF is unavailable, a fallback panel opens the WebUI in the
    system browser; the WebUI can also be opened in the system browser alongside the panel (optional)
15. **Compatibility**: JetBrains IDEs 2024.2 and later, with no upper limit — IntelliJ IDEA, PyCharm,
    WebStorm, GoLand and the rest
16. **Windows launch note**: dsh is launched through a hidden PowerShell process — please allow it if
    your security software intercepts it
17. **Open source**: GitHub <https://github.com/Coco-king/dsh-dock> · Gitee mirror
    <https://gitee.com/kkcoco/dsh-idea-plugin>

**中文**

在 IDE 内运行 DeepSeek Harness（dsh）：一键启动 `dsh web`，WebUI 直接显示在工具窗口内置浏览器中，
无需终端、无需另开浏览器。

**支持的 dsh**：0.1.0-rc.8 及以上（新版认证与接口已适配）

1. **一键启动 dsh**：点击右侧工具栏的鲸鱼图标（或 Tools 菜单）打开 Dsh Dock 工具窗口并启动
   `dsh web`，WebUI 直接显示在内置浏览器面板中
2. **双启动方式**：可在 **WSL 中启动**或**在 Windows 中直接启动**，端口（默认 3090 / 3080）与
   附加参数分别配置；未全局安装 dsh 时自动改用 `npx @deepseek-ai/dsh web`，缺少 Node.js 会给出安装提示
3. **已启动自动识别**：端口已被监听时跳过启动、直接打开 WebUI，外部启动的 dsh 绝不会被停掉；
   新版 dsh 拿不到启动令牌时会复用浏览器已有的认证，各项功能照常工作
4. **工作空间跟随项目**：以项目根目录作为 dsh 工作空间；多窗口时跟随**活动窗口**，
   且每个窗口的页面固定在自己的项目上；默认每次打开停在空白新会话（可改为恢复上次会话）
5. **右键发送引用**：选中代码 → `@路径#L12-34`，文件/目录 → `@路径`，直接送入 Dsh 输入框
   （自动打开窗口、必要时自动启动）；路径按启动方式转换（WSL 用 `/mnt/c/...`，Windows 用 `C:/...`）
6. **复制 AI 引用**：编辑器与项目视图右键可将同样的 `@路径` 引用复制到剪贴板，粘贴到任意支持 @引用 的输入框
7. **dsh 编辑后自动刷新**：实时监听 dsh 会话事件，文件写入工具成功后立即更新文件与已打开的编辑器
   （有未保存修改的文件不会被覆盖）；可关闭
8. **文件路径在 IDE 中打开**：点击 WebUI 里的文件路径、文件提及或「打开」按钮即在 IDE 编辑器中打开，
   可定位行号或写入的新内容；打不开时仍走 dsh 预览。可关闭
9. **侧边栏自动定位**：打开会话列表时自动滚动到当前会话并高亮其工作空间分组，切换工作空间/会话时持续跟随
10. **启动时机与预热**：可在 IDE 启动时（后台预热 dsh 与内置浏览器，首次打开几乎瞬时）、
    打开工具窗口时或手动启动；IDE 退出时 dsh 一并停止
11. **主题与语言**：WebUI 主题跟随 IDE；语言可跟随 IDE/浏览器或强制为简体中文/English；
    插件自身界面、菜单与通知跟随 IDE 语言
12. **控制面板与日志**：启动、停止、刷新、浏览器打开、运行状态一目了然，日志面板带毫秒级时间戳，
    齿轮按钮直达设置
13. **不留残留进程**：只停止由插件启动的 dsh；IDE 退出时三层清理保障，即使 IDE 崩溃也会清理
14. **内置浏览器兜底**：JCEF 不可用时提供回退面板，改用系统浏览器打开 WebUI（也可默认同时用系统浏览器打开）
15. **兼容版本**：支持 JetBrains 全系 IDE（IntelliJ IDEA / PyCharm / WebStorm / GoLand 等）2024.2 及之后版本，不设上限
16. **Windows 启动说明**：通过隐藏的 PowerShell 调用启动命令，如遇安全软件拦截请放行
17. **项目开源**：GitHub <https://github.com/Coco-king/dsh-dock> · Gitee 镜像
    <https://gitee.com/kkcoco/dsh-idea-plugin>
