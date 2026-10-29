# Dsh Dock - DeepSeek Harness JetBrains IDE 插件

<!-- Plugin description -->
DeepSeek Harness (dsh) for JetBrains IDEs (IntelliJ IDEA, PyCharm, WebStorm, ...): launch the
`dsh web` AI assistant with one click and chat with DeepSeek models in the built-in WebUI —
no terminal or separate browser needed.

**English**

**Version support**: dsh **0.1.0-rc.8 or later** (the latest **0.2.x** is fully supported)

1. **One-click start of dsh**: Click the whale icon in the right tool window bar to open the Dsh Dock
   tool window and start the DeepSeek Harness assistant (`dsh web`) with one click; the WebUI is shown
   directly in the built-in browser panel of the tool window, so you can chat with DeepSeek models
   without leaving the IDE
2. **Two launch modes (optional)**: Start in **WSL** or **directly in Windows**, with separately
   configurable ports and extra arguments for each mode
3. **Always starts its own dsh**: a dsh already listening on the port (one you started yourself, or a
   leftover from an earlier session) is **never reused** — the plugin shifts to the next free port and
   starts its own instance, so every IDE window runs on its own dsh and stays independent
4. **Workspace follows the project**: Uses the root path of the currently open project as the dsh workspace;
   by default each open starts a blank new session instead of the previous conversation (a setting restores
   the last session)
5. **One dsh per IDE window**: every project window starts its own dsh on its own port (taken from the
   configured port and shifted up when it is already in use), so refreshing the WebUI, workspaces, sessions
   and page state never leak between windows; closing a window stops the dsh that belongs to it
6. **Right-click references**: Send the selected code or file to the Dsh input box as a `@path`
   reference with one click, so the assistant can read the file when needed
7. **Auto-refresh after dsh edits**: Live-listens to dsh's session events, so every edit shows up —
   whether dsh writes the file directly or through a script it runs — and any open editors update to
   the new content immediately, with no stale code or reopening files. Git operations count too:
   once dsh commits, switches branches and so on, the IDE's commit page and history refresh as well
8. **File paths open in the IDE**: Clicking a file path in the dsh WebUI opens it in the IDE
   editor — jumping to the line or the edited content when dsh knows it — instead of the dsh sidebar preview
9. **Sidebar locate and highlight**: Opening the session list in the dsh sidebar — and switching to another
   workspace or session afterwards — scrolls straight to the current session and highlights the workspace
   group it belongs to, so long session lists stay navigable
10. **Windows launch note**: dsh is launched through a hidden PowerShell process — if your security
   software intercepts it, please allow it
11. **Multi-IDE support**: Works in JetBrains IDEs (IntelliJ IDEA, PyCharm, WebStorm, GoLand, etc.), 2024.2 and later
12. **Open source**: GitHub <https://github.com/Coco-king/dsh-dock> · Gitee mirror <https://gitee.com/kkcoco/dsh-idea-plugin>

**中文**

DeepSeek Harness（dsh）JetBrains IDE 插件（IntelliJ IDEA / PyCharm / WebStorm 等）：一键启动 `dsh web` AI 助手，在 IDE 内置浏览器中直接与
DeepSeek 模型对话，无需终端、无需另开浏览器。

**版本支持**：dsh **0.1.0-rc.8 及以上**（对最新 **0.2.x** 完整支持）

1. **一键启动 dsh**：点击右侧工具栏的鲸鱼图标，打开 Dsh Dock 工具窗口并一键启动 DeepSeek Harness（`dsh web`），
   WebUI 直接显示在工具窗口内置的浏览器面板中，无需离开 IDE 即可与 DeepSeek 模型对话
2. **双启动方式（设置可选）**：支持在 **WSL 中启动**或在 **Windows 中直接启动**，
   两种方式的端口与附加参数可分别配置
3. **始终启动自己的 dsh**：端口上已经有 dsh 时（你自己启动的、或上个会话遗留的进程）**不会复用**，
   而是顺延到下一个空闲端口、启动插件自己的实例 —— 每个窗口都跑在自己的 dsh 上，互不影响
4. **工作空间跟随项目**：以当前打开的项目根目录作为 dsh 工作空间；默认每次打开都停在当前项目的
   空白新会话（可在设置中改为恢复上次会话）
5. **每个 IDE 窗口一个独立 dsh**：每个项目窗口各自启动一个自己的 dsh（端口从设置里的端口开始、
   被占用时自动顺延），刷新页面、工作空间、会话与页面状态在窗口之间完全互不影响；
   关闭某个项目窗口时，属于它的 dsh 会一并停止
6. **右键发送代码/文件**：选中的代码或文件可一键以 `@路径` 引用发送到 Dsh 输入框，方便模型按需读取
7. **dsh 编辑后自动刷新**：实时监听 dsh 会话事件，无论 dsh 是直接写文件还是通过脚本批量改动，
   都会自动刷新对应文件并即时更新已打开的编辑器，不再显示旧代码、无需重新打开文件；
   dsh 完成 git 操作（提交、切换分支等）后，IDE 的提交页与提交记录也会自动刷新
8. **文件路径在 IDE 中打开**：点击 dsh WebUI 里的文件路径时在 IDE 编辑器中打开（能定位到行号或编辑内容），
   不再使用 dsh 侧边栏预览；打不开时仍走 dsh 预览
9. **侧边栏自动定位**：打开 dsh 侧边栏的会话列表时（以及之后切换工作空间/会话时），自动定位到当前会话，
   并高亮它所在的工作空间分组，会话很多时也能一眼看清"我在哪个工作空间、当前会话在哪"
10. **Windows 启动说明**：通过隐藏的 PowerShell 调用启动命令，如遇安全软件拦截请放行
11. **多 IDE 支持**：兼容 JetBrains 全系 IDE（IntelliJ IDEA / PyCharm / WebStorm / GoLand 等），2024.2 及之后版本
12. **项目开源**：GitHub <https://github.com/Coco-king/dsh-dock> · Gitee 镜像 <https://gitee.com/kkcoco/dsh-idea-plugin>
<!-- Plugin description end -->

## 开源地址

项目开源：GitHub <https://github.com/Coco-king/dsh-dock> · Gitee 镜像 <https://gitee.com/kkcoco/dsh-idea-plugin>，欢迎 Star、提交 Issue / PR。

## 功能

- **支持最新版 dsh（0.2.x）**：完整兼容 dsh 最新版的安全认证、重构后的接口、实时文件同步通道与
  提交页刷新，开箱即用；旧版 dsh（0.1.0-rc.8 及以上）同样兼容
- **右侧工具栏鲸鱼图标**：安装后在右侧工具栏出现黑色的 dsh 鲸鱼图标，点击即可打开 Dsh Dock 工具窗口
- **双启动方式（设置可选）**：
  - **WSL 中启动**：通过 `wsl.exe` 在 WSL 里运行 dsh（参考 `ldsh.cmd` 的启动思路），
    自动加载 WSL 用户的 `~/.bashrc`，因此 nvm/PATH 安装的 dsh 都能直接用，不绑定任何本机路径
  - **Windows 直接启动**：直接在 Windows 上隐藏后台运行 `dsh web`（未全局安装时自动改用
    `npx @deepseek-ai/dsh web` 启动）。启动通过**隐藏的 PowerShell** 调用执行，
    若安全软件（杀毒/防火墙）弹出拦截提示，请选择**允许/放行**，否则可能导致启动失败
  - 两种启动方式的**端口与附加参数分别配置**（互不影响），切换启动方式无需改回设置
- **内置浏览器窗口**：侧边栏内嵌 JCEF 浏览器，WebUI 直接显示在工具窗口中，无需打开系统浏览器
- **端口已有 dsh 也不复用**：如果端口已被监听（你自己启动的 dsh、或上个会话遗留的进程），插件
  **不会复用它**（复用别人的实例等于共用页面状态，窗口之间又会互相影响），而是顺延到下一个
  空闲端口、启动**本窗口自己的** dsh；外部启动的 dsh 也不会被插件误停（插件只停自己启动的进程）
- **启动时机**：「自动启动」默认为 IDEA 启动时（IDE 启动即在后台启动**本窗口自己的** dsh 并预热
  内嵌浏览器页面，首次打开 Dsh Dock 窗口几乎瞬时显示），可改为打开工具窗口时或手动启动；
  **关闭某个项目窗口时，属于它的 dsh 一并停止**，IDE 退出时统一清理。
  多窗口时**每个项目窗口各自预热自己的一份浏览器**（各自连自己的 dsh 端口）：哪个窗口首次打开
  工具窗口都是直接复用，不必现场冷启动浏览器；预热不依赖窗口是否可见/是否被激活：窗口还藏在后台时
  页面就已经开始加载（否则要等鼠标碰一下那个窗口才加载，侧边栏会一直空白）
- **右键发送代码/文件到 Dsh Dock**：在编辑器中选中代码或在项目视图中选中文件，右键即可把
  `@路径` 引用直接发送到 Dsh 输入框（自动打开工具窗口、必要时自动启动 dsh）：
  - 选中代码 → `@<path>#L<start>-<end>`（如 `@/mnt/c/.../.gitignore#L5-6`）
  - 选中文件 → `@<path>`（如 `@/mnt/c/.../gradlew`）
  - 路径按启动模式自动转换（WSL 模式用 `/mnt/...`，Windows 模式用 `C:/...`），
    与 dsh 的 `@路径` 文件引用约定一致，模型需要时会通过 read 工具读取
- **每个窗口一个独立 dsh（多窗口彻底互不干扰）**：每个 IDE 项目窗口各自启动一个 dsh，端口从设置里的
  端口开始、被占用时自动顺延（`3080` → `3081` → …）。端口不同即页面来源不同，各窗口的页面状态与
  认证天然隔离 —— **刷新某个窗口的 Dsh Dock 不会影响其他窗口**，也不会再出现"刷新后所有窗口都显示
  同一个页面"；**关闭某个项目窗口时，它自己的 dsh 会一并停止**。
  工作空间仍跟随各自项目：以当前打开的项目根路径作为 dsh 的工作目录，并自动把当前项目同步为 WebUI
  的会话工作空间（即使上次用过别的项目，也会切回当前项目）；页面的"落地会话"也钉在本项目上，
  加载后若发现落在别的项目上会自动切回（点开本项目会话，必要时才刷新）
- **主题/语言**：WebUI 主题跟随 IDE（暗色 IDE 自动深色）；语言可选跟随 IDE/浏览器、简体中文或 English
  （跟随模式下按 IDE 界面语言自动同步 dsh 语言偏好：英文 IDE 显示英文 WebUI，中文 IDE 显示中文 WebUI）
- **dsh 编辑后自动刷新**：dsh 改完文件就把已打开的编辑器更新为新代码（WSL 模式下 Windows 侧收不到
  文件变更通知的老问题也一并解决）—— 直接写文件、通过脚本批量改动、跑命令改文件都能同步；
  有未保存修改的文件不会被覆盖；默认开启，可在设置中关闭
- **Git 操作后刷新提交页**：dsh 完成提交、切换分支、拉取等 git 操作后，IDE 的提交页（Local Changes）
  与提交记录自动刷新，不必手动切窗口或点刷新
- **点击文件路径在 IDE 中打开**：点击 dsh WebUI 里的文件路径（含文件提及、「打开」按钮）时在 IDE 编辑器中打开，
  能定位行号或编辑新增内容；打不开时自动回退为 dsh 侧边栏预览。默认开启，可在设置中关闭
- **打开即空白新会话（默认）**：每次打开 Dsh Dock 都停在当前项目的一个空白新会话上，不复用上次的对话；
  需要"打开就恢复上次会话"时，在设置里打开「打开时恢复上次会话」即可（历史会话始终可以从侧边栏点开）
- **侧边栏会话列表自动定位**：打开 dsh 左侧边栏的会话列表（展开侧边栏、切回会话面板或页面加载完成）时，
  以及之后**切换工作空间/会话**时，自动把**当前会话**滚动到可视区中间，并**高亮当前工作空间分组**
  （分组标题行加底色与强调条）；当前会话所在分组被折叠、或会话被「展开更多」折叠时会先自动展开；
  已经可见时不打扰、不滚动
- **进程管理**：启动 / 停止 / 刷新 / 在系统浏览器中打开，状态一目了然
- **操作日志**：底部日志面板展示 dsh 进程输出，方便排查问题
- **宽版本支持**：支持 IntelliJ IDEA 2024.2 (242) 及以上的所有版本（不设上限），并打包 Kotlin stdlib 以兼容旧版 IDE

## 使用

1. 使用 IDEA 打开本项目，等待 Gradle 同步完成
2. 运行 `Run Plugin`（`gradlew runIde`），会启动一个带插件的测试 IDE 实例（IC 2024.2.5）
3. 在测试 IDE 右侧工具栏点击黑色的鲸鱼图标，打开 Dsh Dock 工具窗口
4. 首次打开会自动启动 dsh，并加载 `http://localhost:3080/`
5. 起始端口（Windows 默认 `3080`）已被占用时**不会复用**：插件顺延到下一个空闲端口（`3081`、`3082`…）
   启动本窗口自己的 dsh，各窗口互相独立；外部启动的 dsh 保持原样、不会被插件停止
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
# 产物: build/distributions/dsh-idea-plugin-1.3.0.zip
# 通过 IDEA: Settings -> Plugins -> Install Plugin from Disk... 安装
```

## 设置

工具窗口右上角齿轮按钮（或 `Settings -> Other Settings -> Dsh Dock`）可配置：

| 配置项 | 说明 | 默认值 |
| --- | --- | --- |
| 启动方式 | `在 Windows 中直接启动`（默认）或 `在 WSL 中启动`（Windows+WSL） | Windows |
| WSL 端口 | WSL 模式下的**起始端口**（第一个窗口使用；已被占用时后续窗口自动顺延） | `3090` |
| WSL 附加参数 | WSL 模式下追加到 `dsh web` 后的参数（如 `--host 0.0.0.0`） | 空 |
| Windows 端口 | Windows 模式下的**起始端口**（第一个窗口使用；已被占用时后续窗口自动顺延） | `3080` |
| Windows 附加参数 | Windows 模式下追加到 `dsh web` 后的参数（如 `--host 0.0.0.0`） | 空 |
| 自动启动 | 启动时机：IDEA 启动时（默认，含内嵌浏览器预热）/ 打开工具窗口时 / 手动启动 | IDEA 启动时 |
| 同时打开系统浏览器 | WebUI 就绪后额外用系统浏览器打开 | 关闭 |
| 自动刷新编辑器 | dsh 编辑文件后自动刷新 VFS 与已打开的编辑器（实时监听 dsh 会话事件，写入成功即刷新）；git 操作后同时刷新提交页与提交记录 | 开启 |
| 点击文件路径在 IDE 中打开 | 点击 WebUI 里的文件路径时在 IDE 编辑器中打开该文件 | 开启 |
| 打开时恢复上次会话 | 关闭：每次打开 Dsh Dock 都停在当前项目的一个**空白新会话**上，不复用上次的对话（历史会话仍可从侧边栏点开）；打开：恢复上次打开的会话（dsh 默认行为） | 关闭 |
| WebUI 主题跟随 IDE | 暗色 IDEA 时内嵌 WebUI 自动使用深色主题 | 开启 |
| WebUI 语言 | 单选：跟随 IDE 语言 / 简体中文 / English（跟随模式下按 IDE 界面语言自动同步 dsh 语言偏好，如英文 IDE → 英文 WebUI） | 跟随 |

> 端口与附加参数按启动方式分别配置，**实际启动时只使用当前选中的启动方式对应的那一组**；
> 切换到另一种启动方式无需改回端口/参数。
>
> 端口是**每个 IDE 窗口各自的 dsh 实例的起始端口**：第一个窗口用该端口，之后打开的窗口自动顺延
> （`3080` → `3081` → …），因此窗口之间完全独立（刷新、工作空间、会话、页面状态互不影响）。
> 端口上已有 dsh（你自己启动的、或遗留进程）时也不会复用，直接顺延到空闲端口。

> 兼容旧版本：插件设置是通用化的，WSL 发行版、NVM 路径、ldsh.cmd 等本机细节
> 一律不配置、不持久化，由用户自己的环境负责，换机器/换 IDE 无需重新配置。

## 原理

- **WSL 模式**：插件以**登录 + 交互 shell**（`wsl.exe bash -lic`，等价于你自己的终端环境）执行
  一个生成的 `dsh-run-wsl-*.sh` 脚本（切换项目工作目录、加载 `~/.profile` / `~/.bashrc`，
  并兜底从 `NVM_DIR` 或 `~/.nvm` 加载 nvm），再通过隐藏的 PowerShell 调用，避免弹出控制台窗口
- **Windows 模式**：生成 `dsh-run-win-*.cmd`（`cd /d` 项目目录 + `dsh web --port <port>`），
  通过隐藏的 PowerShell 执行（若安全软件拦截 PowerShell 请放行，否则启动可能失败）
- dsh 进程的标准输出/错误输出被捕获到工具窗口的日志面板
- 探测 `127.0.0.1:<port>` 端口就绪后，由内嵌 JCEF 浏览器加载 WebUI（新版 dsh 自动完成安全认证）
- **工作空间跟随项目**：dsh 的"工作空间"是持久化注册表（存于 `~/.dsh/storages`，跨进程重启保留），
  WebUI 启动时自动选中**最近活跃的工作空间**（按会话时间排序），与 dsh 进程的启动目录无关。
  因此插件在每次加载 WebUI 前，通过 dsh 的 `/api` 通道把当前项目注册为工作空间
  （自动适配新旧版 dsh 接口差异，新版含浏览器安全认证），并在它不是"最近"时新建一个会话提升其
  新鲜度——这样页面初始选中就会落在当前项目上，而不是上次用过的项目。
  **多窗口隔离（每个窗口一个 dsh）**：dsh 的"当前工作空间/当前会话"只存在于内嵌浏览器的本地存储里，
  而本地存储按**页面来源（协议+主机+端口）**隔离 —— 多个窗口共用一个端口时会共用同一份选择：
  任一窗口刷新/切换都会改写它，其他窗口的页面脚本发现"自己不在该在的会话上"就跟着重载，
  最终全部落到同一个页面。因此插件为**每个 IDE 项目窗口各起一个 dsh 实例**，端口从设置里的
  起始端口依次分配（被占用时顺延），各窗口的页面状态与认证完全隔离；关闭某个项目窗口时该窗口的
  dsh 随之停止。另外，工作空间注册表（`~/.dsh/storages`）是所有 dsh 进程共用的文件、写入是
  "整份覆盖"，因此插件把各窗口的"变更类"调用（新建工作空间/会话、归档空白会话）用应用级互斥锁
  串行化，避免两个窗口同时写注册表互相覆盖。
  同时还会：
  - **空白会话卫生**：归档项目工作空间里遗留的空白会话（空会话无内容损失）。dsh 的"新会话"
    会**复用**工作空间里已有的空白会话（它就是"新会话"槽），遗留的旧空白会让"新会话"点了没反应；
    归档后每次都会新建真正的新会话
  - **条件清除持久化会话选择**：dsh WebUI 会把"上次打开的会话"持久化到浏览器 localStorage
    （`dsh.sessions.current`），页面加载时恢复它、从而跳过工作空间初始选中。插件在加载前检查：
    持久化的会话不属于当前项目工作空间时清除它（让页面落到当前项目）；属于则保留（回到原会话）
- **防止 dsh 打开系统浏览器**：dsh 自 v0.1.0-rc.8 起 `dsh web` 默认会用系统浏览器打开 UI，
  与插件内嵌浏览器冲突。插件为 `dsh web` 自动附加 `--no-open`（`web --port <port> --no-open`），
  需 dsh 0.1.0-rc.8 及以上版本（更早版本不支持该参数）
- **右键发送引用**：`@路径` 引用由注入脚本写入 WebUI 输入框——新版 dsh（0.1.2+）的输入框是
  Lexical 受控编辑器，脚本聚焦后触发 `insertText` 使其接受草稿；旧版 React 受控文本框用原生
  value setter + `input` 事件。页面未就绪时引用先暂存，主框架 `onLoadEnd` 后自动补发
- **dsh 编辑后自动刷新**：WSL 模式（或其他外部进程）写文件时，Windows 侧的文件变更通知
  （IDEA 原生文件监听依赖它）经常不触发，导致 IDEA 一直显示旧代码。插件订阅 dsh 会话事件：
  新版 dsh（0.1.2+）走 **session/follow 实时监听通道**（remote.mux WebSocket 长连接，事件级即时推送），
  并且**只订阅本项目工作空间里的活跃会话**（dsh 为每条订阅都要加载并常驻保留该会话的日志，
  历史会话成百上千时全量订阅会把 dsh 拖慢、连累界面加载），
  旧版（0.1.0-rc.8+）用 events.mux 事件流。刷新分两条信号：
  - **顶层写入工具**：`tool/call` 登记、`tool/result` 确认成功后刷新该文件；同一文件短期内的
    多次写入合并为一次刷新，失败的工具调用不刷新
  - **每轮改动摘要**（dsh 0.2+）：模型改为在代码执行工具里调用写入工具，写操作不再产生顶层事件，
    因此改为在每轮结束时读取 dsh 给出的改动清单（直接写文件、脚本和命令改的文件都在内）逐个刷新；
    路径换算回 Windows 侧（`/mnt/c/...` -> `C:/...`），并重载编辑器中无未保存修改的文档
  - **对话进行中**：每轮结束才有完整清单，因此工具执行期间按 3 秒间隔重读**已打开的文件**，
    正在查看的编辑器无需等对话结束即可看到最新代码
- **Git 操作后刷新提交页**：dsh 在终端/脚本里跑 git 时，插件识别变更类命令（提交、切换分支、暂存、
  拉取等），命令结束后通知 IDE 的版本控制视图重算：提交页重扫脏文件，提交记录重新读取仓库状态
  （`.git` 目录一并刷新）；每轮对话结束也会兜底刷新一次，因此「只提交、没改文件」的操作同样即时可见
- **只停自己启动的 dsh**：WSL 模式启动时把 dsh 的真实 PID 写入标识文件（`dsh-owner-*.pid`），
  停止/退出清理时 `kill` 该 PID；Windows 模式 `taskkill` 本插件持有的进程树。
  **外部启动的同端口 dsh（如手动 `ldsh.cmd`）绝不会被误杀**
- **关窗/退出清理**：**关闭某个项目窗口时**立即停止属于该窗口的 dsh（只停本插件启动的进程，
  不影响其他窗口）；**IDE 退出时**统一停止所有由插件启动的 dsh —— 由 `AppLifecycleListener`
  早期回调（异步触发，不阻塞退出）+ JVM shutdown hook 两层保障，再叠加每个进程自己的独立
  PowerShell 看门狗（监视 IDE 进程，IDE 消失且端口仍开时自动执行停止脚本；即使 IDE 被强杀/崩溃
  也会清理）

## 常见问题

- **页面停在认证提示页（dsh 0.1.2-rc.1 起）**：新版 dsh 的 WebUI 需要**启动令牌**完成浏览器认证。
  插件自己启动的 dsh 会自动完成认证；只有在令牌没被捕获到（例如 dsh 被外部手段重启过）时才可能
  看到认证页，在工具窗口点一次「刷新」，或停止后重新启动 dsh 即可
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
  「自动刷新编辑器」已开启，且工具窗口日志出现过「已建立实时文件同步监听」；
  dsh 0.2 起，对话进行中会持续刷新**已打开的文件**，其余文件与提交页在每轮结束时统一同步
  （日志里会出现「已同步第 N 轮改动」），长对话进行中请稍候。旧版 dsh 走兼容的事件流通道；
  仍不同步时可手动刷新一次 WebUI 或重新打开文件；另外，文件在 IDEA 里有**未保存修改**时
  不会被自动覆盖（这是防丢改动的保护）
