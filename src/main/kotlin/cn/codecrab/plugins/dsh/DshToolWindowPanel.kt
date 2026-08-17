package cn.codecrab.plugins.dsh

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.notification.Notifications
import com.intellij.openapi.Disposable
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.jcef.JBCefApp
import com.intellij.ui.jcef.JBCefBrowser
import com.intellij.ui.jcef.JBCefBrowserBuilder
import com.intellij.ui.jcef.JBCefClient
import org.cef.browser.CefBrowser
import org.cef.browser.CefFrame
import org.cef.handler.CefLoadHandlerAdapter
import org.cef.network.CefRequest
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Dimension
import java.awt.FlowLayout
import javax.swing.BorderFactory
import javax.swing.Box
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JTextArea
import javax.swing.JToggleButton
import javax.swing.SwingConstants
import javax.swing.SwingUtilities

/**
 * Dsh 工具窗口内容:
 * - 顶部工具栏: 启动/停止 / 刷新 / 系统浏览器打开 / 日志开关 / 设置
 * - 中部: 内嵌 JCEF 浏览器 (WebUI), JCEF 不可用时显示回退面板
 * - 底部: dsh 进程日志 (可折叠)
 */
class DshToolWindowPanel(
    private val project: Project,
    private val parentDisposable: Disposable,
) : JPanel(BorderLayout()) {

    private val settings = DshSettingsState.getInstance()

    /**
     * 状态监听器 (绑定到本面板实例, 注册与注销用的是同一实例)。
     * 注册后 DshServer 会立即用当前状态回调一次, 保证新面板创建时
     * 状态文字/按钮与"其他窗口已启动的 dsh"保持一致。
     */
    private val stateListener: (DshServer.State) -> Unit = ::onStateChanged

    private val statusLabel = JBLabel(STR_IDLE)
    private val startStopBtn = JButton("启动")
    private val refreshBtn = JButton("刷新")
    private val browserBtn = JButton("浏览器打开")
    private val logToggle = JToggleButton("日志")
    private val settingsBtn = JButton("设置")

    private val logArea = JTextArea().apply {
        isEditable = false
        tabSize = 2
        font = font.deriveFont(12f)
        foreground = JBColor(Color(0x444444), Color(0xBBBBBB))
        background = JBColor(Color(0xF5F5F5), Color(0x2B2B2B))
    }
    private val logScroll = JBScrollPane(logArea).apply {
        preferredSize = Dimension(400, 160)
        isVisible = false
    }

    private var browser: JBCefBrowser? = null
    private var jcefAvailable: Boolean = false
    private var externalBrowserOpenedForSession: Boolean = false
    private var webUiLoaded: Boolean = false

    /** 主框架页面是否已完成加载 (onLoadEnd 置位), 用于判断注入时机 */
    @Volatile
    private var pageLoaded: Boolean = false

    /** 待发送到 WebUI 输入框的引用 (页面就绪前暂存, 发送成功后清空) */
    @Volatile
    private var pendingReference: String? = null

    /** 最近一次工作空间同步得到的当前项目 sessionIds (供 onLoadStart 判断持久化会话归属) */
    @Volatile
    private var syncSessionIds: List<String>? = null

    /**
     * 本面板是否发起过尚未完成的启动尝试。
     * 状态变化现在会广播到所有窗口的面板, 启动失败通知只在"发起启动的那个面板"弹出,
     * 因此该标志只能由本面板自己的启动调用置位, 不能在 STARTING 回调里统一置位。
     */
    @Volatile
    private var startRequestedByThisPanel: Boolean = false

    /** 本次启动日志里是否出现过"找不到 dsh"警告 (决定失败通知的内容) */
    @Volatile
    private var dshMissingWarned: Boolean = false

    /** 本次启动日志里是否出现过"未检测到 Node.js 环境"错误 (决定失败通知的内容) */
    @Volatile
    private var nodeEnvMissingWarned: Boolean = false

    /** 本次启动日志里是否出现过 WSL 子系统错误 (如 Wsl/Service/E_UNEXPECTED) */
    @Volatile
    private var wslErrorSeen: Boolean = false

    init {
        isOpaque = false
        layout = BorderLayout()
        add(createToolbar(), BorderLayout.NORTH)
        add(createContentArea(), BorderLayout.CENTER)
        add(logScroll, BorderLayout.SOUTH)

        // 面板销毁时从注册表移除、注销状态监听 (工具窗口关闭/项目关闭)
        DshDisposer.register(parentDisposable, com.intellij.openapi.Disposable {
            DshToolWindowRegistry.unregister(project)
            DshServer.removeStateListener(stateListener)
        })

        // 注册状态监听: 立即以当前状态回调一次 (dsh 可能已在其他窗口启动), 后续所有窗口的
        // 启停状态变化都会广播到这里, 保证每个窗口的状态文字/按钮始终一致
        DshServer.addStateListener(stateListener)
        if (settings.autoStart) {
            // 稍后自动启动, 先渲染 UI
            SwingUtilities.invokeLater {
                if (!project.isDisposed) ensureRunning()
            }
        }
        startReadyWatchdog()
    }

    /**
     * 就绪看门狗: 每隔 3s 检查 dsh 是否就绪, 一旦就绪就自动在内置浏览器加载 WebUI。
     * 即使状态回调因故丢失 (例如端口只绑定 IPv6 而早期探测失败), 也能自动显示。
     */
    private fun startReadyWatchdog() {
        val timer = javax.swing.Timer(3000, null)
        timer.addActionListener {
            if (project.isDisposed) {
                timer.stop()
                return@addActionListener
            }
            if (jcefAvailable && !webUiLoaded) {
                val port = settings.port
                if (DshServer.state == DshServer.State.RUNNING || WslSupport.isPortOpen(port)) {
                    webUiLoaded = true
                    appendLog("端口 $port 已就绪, 加载 WebUI")
                    loadWebUi()
                }
            }
        }
        timer.isRepeats = true
        timer.start()
        DshDisposer.register(parentDisposable, com.intellij.openapi.Disposable { timer.stop() })
    }

    // ---------- UI 构建 ----------

    private fun createToolbar(): JComponent {
        statusLabel.border = BorderFactory.createEmptyBorder(0, 6, 0, 6)
        statusLabel.font = statusLabel.font.deriveFont(12f)

        val toolbar = JPanel(FlowLayout(FlowLayout.LEFT, 4, 4)).apply {
            isOpaque = false
        }

        startStopBtn.addActionListener {
            if (DshServer.state == DshServer.State.RUNNING || DshServer.state == DshServer.State.STARTING) {
                DshServer.stop(::appendLog)
            } else {
                ensureRunning()
            }
        }
        refreshBtn.addActionListener { reloadWebUi() }
        browserBtn.addActionListener {
            openInSystemBrowser()
        }
        settingsBtn.addActionListener {
            openSettings()
        }
        logToggle.addActionListener {
            logScroll.isVisible = logToggle.isSelected
            revalidate()
        }

        toolbar.add(statusLabel)
        toolbar.add(Box.createHorizontalStrut(4))
        toolbar.add(startStopBtn)
        toolbar.add(refreshBtn)
        toolbar.add(browserBtn)
        toolbar.add(logToggle)
        toolbar.add(settingsBtn)
        return toolbar
    }

    private fun createContentArea(): JComponent {
        val content = JPanel(BorderLayout())
        content.isOpaque = false
        val holder = try {
            // 官方推荐的组合方式: 先创建 JBCefClient, 再通过 builder 装配浏览器,
            // 最后把 LoadHandler 挂到该 client 的这个浏览器上
            // (addLoadHandler 第二参为 @NotNull CefBrowser, 不能传 null)
            val jbClient: JBCefClient = JBCefApp.getInstance().createClient()
            val b: JBCefBrowser = JBCefBrowserBuilder().setClient(jbClient).build()
            jbClient.addLoadHandler(createInjectLoadHandler(), b.cefBrowser)
            jcefAvailable = true
            browser = b
            // 跨版本注册浏览器销毁钩子 (旧版 IDE 没有新包名的 Disposer)
            if (!DshDisposer.register(parentDisposable, b)) {
                appendLog("警告: 未能注册 JCEF 浏览器销毁钩子, 关闭窗口时可能无法释放浏览器资源")
            }
            browserComponent(b)
        } catch (t: Throwable) {
            jcefAvailable = false
            appendLog("JCEF 浏览器不可用: ${t.message}")
            createFallbackPanel()
        }
        content.add(holder, BorderLayout.CENTER)
        return content
    }

    /**
     * 获取 JBCefBrowser 对应的 Swing 组件。
     * 新版 (2023+) 中 JBCefBrowser 不再继承 JComponent, 需通过 getComponent() 获取;
     * 旧版 (2022.x) 中 JBCefBrowser 本身就是 JComponent, 直接用即可。
     */
    @Suppress("USELESS_CAST")
    private fun browserComponent(b: JBCefBrowser): JComponent =
        (b as? JComponent) ?: b.component

    // ---------- WebUI 主题/语言覆盖 ----------

    /**
     * dsh WebUI 的主题跟随 `matchMedia("prefers-color-scheme: dark")`,
     * 语言跟随 `navigator.languages` (JCEF 默认 en-US, 导致英文界面)。
     * 这里在每次主框架加载开始时注入一段 JS, 让 WebUI 跟随 IDE 主题并使用中文。
     * (是否注入由 [injectUiThemeLocaleOverride] 根据设置决定)
     */
    private fun createInjectLoadHandler(): CefLoadHandlerAdapter = object : CefLoadHandlerAdapter() {
        override fun onLoadStart(browser: CefBrowser, frame: CefFrame, transitionType: CefRequest.TransitionType) {
            if (frame.isMain) {
                pageLoaded = false
                injectUiThemeLocaleOverride(browser)
                // 在应用脚本执行前清除"不属于当前项目"的持久化会话选择 (见 [clearPersistedSessionIfForeign])
                clearPersistedSessionIfForeign(browser)
            }
        }

        override fun onLoadEnd(browser: CefBrowser, frame: CefFrame, httpStatusCode: Int) {
            if (frame.isMain) {
                pageLoaded = true
                flushPendingReference()
            }
        }
    }

    private fun injectUiThemeLocaleOverride(browser: CefBrowser) {
        if (!settings.themeFollowIde && settings.forceLocale.isBlank()) return
        val dark = settings.themeFollowIde && DshToolWindowFactory.isDarkUi()
        val langs = if (settings.forceLocale.isBlank()) followIdeLanguages() else {
            val l = settings.forceLocale.trim()
            if (l.startsWith("zh", ignoreCase = true)) listOf(l, "zh", "en")
            else listOf(l, "en")
        }
        try {
            browser.executeJavaScript(uiOverrideScript(dark, langs), "dsh://ide-renderer-override.js", 0)
        } catch (t: Throwable) {
            appendLog("WebUI 主题/语言注入失败: ${t.message}")
        }
    }

    /**
     * "跟随 IDE/浏览器"模式下推导 dsh 语言: JCEF 的 `navigator.languages` 默认是 en-US,
     * 与 IDE 界面语言无关, 必须显式注入。IDE 是中文 (user.language 以 zh 开头) 时注入中文;
     * 其他语言保持 dsh 默认 (英文)。
     */
    private fun followIdeLanguages(): List<String> {
        val lang = System.getProperty("user.language")?.trim()?.lowercase() ?: return emptyList()
        return if (lang.startsWith("zh")) listOf("zh", "en") else emptyList()
    }

    private fun uiOverrideScript(dark: Boolean, langs: List<String>): String {
        val darkJs = if (dark) "true" else "false"
        val langsJs = langs.joinToString(prefix = "[", postfix = "]", separator = ", ") { "\"$it\"" }
        val langOverride = if (langs.isNotEmpty()) {
            """
            |        try {
            |          Object.defineProperty(Navigator.prototype, "languages", { configurable: true, get: function () { return L; } });
            |          Object.defineProperty(Navigator.prototype, "language", { configurable: true, get: function () { return L[0]; } });
            |        } catch (err) {}
            """.trimMargin()
        } else ""
        return """
            |(function () {
            |  var DARK = $darkJs;
            |  var L = $langsJs;
            |  try {
            |    var realMatchMedia = window.matchMedia.bind(window);
            |    window.matchMedia = function (query) {
            |      try {
            |        if (typeof query === "string" && query.indexOf("prefers-color-scheme") !== -1) {
            |          return { matches: DARK, media: query, onchange: null,
            |                   addListener: function () {}, removeListener: function () {},
            |                   addEventListener: function () {}, removeEventListener: function () {},
            |                   dispatchEvent: function () { return false; } };
            |        }
            |      } catch (err) {}
            |      return realMatchMedia(query);
            |    };
            |  } catch (err) {}
            |$langOverride
            |})();
        """.trimMargin()
    }

    // ---------- 引用注入 (右键菜单发送) ----------

    /**
     * 把 `@路径` 引用送入 WebUI 输入框 (追加到已有草稿后, 自动聚焦)。
     * 页面未就绪时先暂存, 待主框架加载完成后自动补发; dsh 未启动则先启动。
     */
    fun injectReference(reference: String) {
        if (reference.isBlank()) return
        pendingReference = reference
        if (!jcefAvailable) {
            appendLog("内嵌浏览器不可用, 无法自动输入引用: $reference (请在 WebUI 输入框中手动粘贴)")
            return
        }
        if (DshServer.state == DshServer.State.IDLE) {
            appendLog("dsh 未启动, 将先启动再发送引用...")
            ensureRunning()
        }
        flushPendingReference()
    }

    /** 页面就绪后把暂存引用注入输入框; 仅当当前页面已是 dsh WebUI 时才发送 */
    private fun flushPendingReference() {
        val ref = pendingReference ?: return
        if (!jcefAvailable || !pageLoaded) return
        val b = browser ?: return
        val expectedPrefix = DshServer.webUrl(settings.port)
        val current = try {
            b.cefBrowser.url
        } catch (_: Throwable) {
            null
        }
        // 页面还没到 dsh WebUI (如 about:blank / 加载失败), 保留待发
        if (current == null || !current.startsWith(expectedPrefix)) return
        pendingReference = null
        try {
            b.cefBrowser.executeJavaScript(buildInjectScript(ref), "dsh://ide-inject-reference.js", 0)
            appendLog("已发送引用到 WebUI 输入框: $ref")
        } catch (t: Throwable) {
            appendLog("发送引用失败: ${t.message}")
        }
    }

    /**
     * 注入脚本: 在 WebUI 的 React 受控 <textarea> 上追加引用。
     * dsh 的输入框是 React 受控组件, 直接改 value 会被 React 覆盖, 必须用
     * 原生 value setter + 派发 input 事件让 React onChange 更新草稿。
     * 元素用 `textarea[data-phase]` 定位 (dsh 输入框的稳定标记), 页面尚未挂载
     * 完成时自动重试 (最多 20 次 x 250ms)。
     * 注意: 每次都追加, 不做去重 —— 用户可能对同一段代码多次发送。
     */
    private fun buildInjectScript(reference: String): String {
        val refJs = jsString(reference)
        return """
            |(function () {
            |  var REF = $refJs;
            |  var MAX_TRIES = 20;
            |  var tries = 0;
            |  function inject() {
            |    var all = document.querySelectorAll("textarea[data-phase]");
            |    var ta = null;
            |    for (var i = all.length - 1; i >= 0; i--) {
            |      var c = all[i];
            |      if (!c.disabled && !c.readOnly) { ta = c; break; }
            |    }
            |    if (!ta) {
            |      if (tries++ < MAX_TRIES) { setTimeout(inject, 250); }
            |      return;
            |    }
            |    var cur = ta.value || "";
            |    var sep = cur.length === 0 ? "" : (/\s/.test(cur.charAt(cur.length - 1)) ? "" : " ");
            |    var next = cur + sep + REF;
            |    var setter = Object.getOwnPropertyDescriptor(HTMLTextAreaElement.prototype, "value").set;
            |    setter.call(ta, next);
            |    ta.dispatchEvent(new Event("input", { bubbles: true }));
            |    ta.focus();
            |    try { ta.setSelectionRange(next.length, next.length); } catch (err) {}
            |  }
            |  inject();
            |})();
        """.trimMargin()
    }

    /** 转成 JS 双引号字符串字面量 (路径可能含引号/反斜杠等) */
    private fun jsString(value: String): String {
        val sb = StringBuilder("\"")
        for (ch in value) {
            when (ch) {
                '\\' -> sb.append("\\\\")
                '"' -> sb.append("\\\"")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> if (ch.code < 0x20) sb.append("\\u%04x".format(ch.code)) else sb.append(ch)
            }
        }
        sb.append("\"")
        return sb.toString()
    }

    private fun createFallbackPanel(): JComponent {
        val panel = JPanel(BorderLayout())
        panel.isOpaque = false

        val tip = JBLabel(
            "<html><center><b>无法启动内嵌浏览器 (JCEF)</b><br>" +
                "请在 <code>Settings -&gt; Tools -&gt; Web Browsers and Preview</code> 中启用 JCEF<br>" +
                "或使用下方的按钮在系统浏览器中打开 WebUI</center></html>"
        )
        tip.horizontalAlignment = SwingConstants.CENTER

        val buttons = JPanel(FlowLayout(FlowLayout.CENTER, 8, 4)).apply { isOpaque = false }
        val openBtn = JButton("在系统浏览器中打开")
        openBtn.addActionListener { openInSystemBrowser() }
        val retryBtn = JButton("重试加载")
        retryBtn.addActionListener { reloadWebUi() }
        buttons.add(openBtn)
        buttons.add(retryBtn)

        panel.add(tip, BorderLayout.CENTER)
        panel.add(buttons, BorderLayout.SOUTH)
        return panel
    }

    // ---------- 行为 ----------

    /** 启动 dsh (供工具窗口按钮与引用注入共用) */
    fun ensureRunning() {
        appendLog("准备启动 dsh...")
        // 仅当真正发起启动尝试 (非"已在运行/端口被占用") 时才标记本面板, 供失败通知使用
        startRequestedByThisPanel = DshServer.start(project.basePath, ::appendLog)
    }

    private fun reloadWebUi() {
        if (jcefAvailable) {
            loadWebUi()
        } else {
            // JCEF 不可用时, 刷新改为在系统浏览器中打开
            openInSystemBrowser()
        }
    }

    /**
     * 加载 WebUI。加载前先通过 dsh 的 /api 同步工作空间 (确保当前项目是会话工作空间,
     * 详见 [DshWorkspaceApi]), 完成后才加载页面 —— 这样页面初始选中就会落在当前项目上。
     * 同步失败不影响使用 (按现状直接加载)。
     */
    private fun loadWebUi() {
        val port = settings.port
        val url = DshServer.webUrl(port)
        val projectPath = project.basePath
        if (projectPath == null) {
            appendLog("已加载: $url")
            browser?.loadURL(url)
            return
        }
        appendLog("同步工作空间到当前项目: $projectPath")
        Thread({
            val dshPath = DshReference.dshPathFromString(projectPath, settings.launchMode)
            val result = DshWorkspaceApi.ensureProjectWorkspace(port, dshPath)
            if (result != null) {
                appendLog("工作空间已就绪: ${dshPath ?: projectPath}")
                // 记录当前项目的 sessionIds, 供 onLoadStart 清除不属于本项目的持久化会话选择
                syncSessionIds = result.sessionIds
            } else {
                appendLog("工作空间同步不可用(不影响使用), 如需切换请在 WebUI 侧边栏手动选择")
            }
            SwingUtilities.invokeLater {
                if (project.isDisposed) return@invokeLater
                appendLog("已加载: $url")
                browser?.loadURL(url)
            }
        }, "dsh-plugin-workspace-ensure").apply { isDaemon = true }.start()
    }

    /**
     * dsh WebUI 会把"上次打开的会话"持久化到浏览器 localStorage (dsh.sessions.current),
     * 页面加载时会恢复它, 从而跳过工作空间的初始选中逻辑, 导致页面停留在旧项目。
     * 在 onLoadStart (应用脚本执行前) 条件清除: 持久化的会话不属于当前项目工作空间时才删,
     * 属于 (用户本来就在当前项目里) 则保留, 页面恢复其会话。
     * [syncSessionIds] 为 null (尚未同步/同步失败) 时不动 localStorage, 按 dsh 原行为。
     */
    private fun clearPersistedSessionIfForeign(browser: CefBrowser) {
        val sessionIds = syncSessionIds ?: return
        val idsJs = sessionIds.joinToString(prefix = "[", postfix = "]", separator = ",") { jsString(it) }
        val script = """
            |(function () {
            |  try {
            |    var raw = localStorage.getItem("dsh.sessions.current");
            |    if (raw) {
            |      var o = JSON.parse(raw);
            |      if (o && o.sessionId && $idsJs.indexOf(o.sessionId) === -1) {
            |        localStorage.removeItem("dsh.sessions.current");
            |      }
            |    }
            |  } catch (err) {}
            |})();
        """.trimMargin()
        try {
            browser.executeJavaScript(script, "dsh://ide-workspace-clear.js", 0)
        } catch (t: Throwable) {
            appendLog("清除持久化会话选择失败(不影响使用): ${t.message}")
        }
    }

    private fun openInSystemBrowser() {
        val url = DshServer.webUrl(settings.port)
        if (WslSupport.isPortOpen(settings.port)) {
            desktopBrowse(url)
            appendLog("已在系统浏览器打开: $url")
        } else {
            appendLog("端口 ${settings.port} 未就绪, 请先启动 dsh")
            Messages.showWarningDialog(project, "dsh 尚未启动, 端口 ${settings.port} 未就绪。\n请先点击「启动」。", "Dsh Dock")
        }
    }

    /** 跨版本安全: 不依赖平台 BrowserUtil (2024.2 已移除), 直接用 java.awt.Desktop */
    private fun desktopBrowse(url: String) {
        try {
            if (java.awt.Desktop.isDesktopSupported()) {
                java.awt.Desktop.getDesktop().browse(java.net.URI(url))
            } else {
                appendLog("无法打开系统浏览器 (Desktop API 不可用): $url")
            }
        } catch (t: Throwable) {
            appendLog("打开系统浏览器失败: ${t.message}")
        }
    }

    private fun openSettings() {
        // 新版包名 com.intellij.openapi.options.ShowSettingsUtil (2023.3+);
        // 旧版 IDE (2022.x) 上没有这个类, 捕获后提示手动打开设置
        try {
            ShowSettingsUtil.getInstance().showSettingsDialog(project, DshSettingsConfigurable::class.java)
        } catch (t: Throwable) {
            appendLog("当前 IDE 版本不支持程序化打开设置, 请通过 Settings -> Tools -> Dsh 手动配置 (${t.message})")
        }
    }

    private fun onStateChanged(state: DshServer.State) {
        SwingUtilities.invokeLater {
            if (project.isDisposed) return@invokeLater
            updateButtons(state)
            when (state) {
                DshServer.State.RUNNING -> {
                    startRequestedByThisPanel = false
                    dshMissingWarned = false
                    nodeEnvMissingWarned = false
                    wslErrorSeen = false
                    statusLabel.text = STR_RUNNING.format(settings.port)
                    statusLabel.foreground = JBColor(Color(0x1B8A1B), Color(0x6FCF6F))
                    if (jcefAvailable) {
                        webUiLoaded = true
                        loadWebUi()
                    }
                    // JCEF 可用时按设置决定是否也在系统浏览器打开;
                    // JCEF 不可用时自动改用系统浏览器打开 WebUI
                    if ((settings.openExternalBrowser || !jcefAvailable) && !externalBrowserOpenedForSession) {
                        externalBrowserOpenedForSession = true
                        desktopBrowse(DshServer.webUrl(settings.port))
                        if (!jcefAvailable) {
                            appendLog("JCEF 内嵌浏览器不可用, 已自动改用系统浏览器打开 WebUI")
                        }
                    }
                }
                DshServer.State.STARTING -> {
                    statusLabel.text = STR_STARTING
                    statusLabel.foreground = JBColor(Color(0xB8860B), Color(0xE6C560))
                }
                DshServer.State.STOPPING -> {
                    // 用户主动停止 (含启动过程中点停止), 不算启动失败
                    startRequestedByThisPanel = false
                    statusLabel.text = STR_STOPPING
                    statusLabel.foreground = JBColor(Color(0xB8860B), Color(0xE6C560))
                }
                DshServer.State.IDLE -> {
                    val failed = startRequestedByThisPanel
                    val missingDsh = dshMissingWarned
                    val nodeMissing = nodeEnvMissingWarned
                    val wslError = wslErrorSeen
                    startRequestedByThisPanel = false
                    statusLabel.text = STR_IDLE
                    statusLabel.foreground = JBColor.GRAY
                    externalBrowserOpenedForSession = false
                    webUiLoaded = false
                    // 页面视为过期 (可能还停留在旧 WebUI / 已失效页面), 重启后需重新加载再注入
                    pageLoaded = false
                    // 启动尝试失败 (而非主动停止): 弹通知提示原因
                    if (failed) {
                        notifyStartFailed(missingDsh, nodeMissing, wslError)
                    }
                    dshMissingWarned = false
                    nodeEnvMissingWarned = false
                    wslErrorSeen = false
                }
            }
        }
    }

    /** 启动失败时弹系统通知: 区分"无 Node.js 环境" / "未安装 dsh" / "WSL 子系统错误" / 其他失败原因 */
    private fun notifyStartFailed(missingDsh: Boolean, nodeMissing: Boolean, wslError: Boolean) {
        try {
            val group = NotificationGroupManager.getInstance().getNotificationGroup("Dsh")
            val content = when {
                nodeMissing -> buildString {
                    append("未检测到 Node.js 环境（node / npm / npx 均不可用），无法启动 dsh。\n")
                    append(if (settings.launchMode == "wsl") {
                        "请在 WSL 中安装 Node.js（建议使用 nvm），安装完成后重新点击「启动」。\n"
                    } else {
                        "请先安装 Node.js（https://nodejs.org/）并重新打开终端，然后重新点击「启动」。\n"
                    })
                    append("详细日志见工具窗口底部日志面板。")
                }
                missingDsh -> buildString {
                    append("未检测到 dsh 命令, 插件会自动改用官方启动命令 npx @deepseek-ai/dsh web。\n")
                    append(if (settings.launchMode == "wsl") {
                        "如果 npx 也无法使用, 请在 WSL 终端执行: npm i -g @deepseek-ai/dsh"
                    } else {
                        "如果 npx 也无法使用, 请在 Windows 执行: npm i -g @deepseek-ai/dsh"
                    })
                    append("\n安装完成后请重启IDEA。详细日志见工具窗口底部日志面板。")
                }
                wslError -> buildString {
                    append("WSL 启动失败 (Wsl/Service/E_UNEXPECTED 等子系统错误)。\n")
                    append("请先在 Windows 运行 wsl --shutdown 后重试;\n")
                    append("仍失败可重启 WSL 服务 (管理员 PowerShell: net stop LxssManager && net start LxssManager)。")
                }
                else -> buildString {
                    append("dsh 启动失败, 端口 ${settings.port} 未就绪。\n")
                    if (settings.launchMode == "wsl") {
                        append("若日志含 WSL 错误 (如 Wsl/Service/E_UNEXPECTED), 请先在 Windows 运行 wsl --shutdown 后重试;\n")
                        append("仍失败可重启 WSL 服务 (管理员 PowerShell: net stop LxssManager && net start LxssManager)。\n")
                    }
                    append("详细日志见工具窗口底部日志面板。")
                }
            }
            Notifications.Bus.notify(group.createNotification("Dsh 启动失败", content, NotificationType.ERROR), project)
        } catch (t: Throwable) {
            appendLog("无法弹出启动失败通知: ${t.message}")
        }
    }

    private fun updateButtons(state: DshServer.State) {
        val busy = state == DshServer.State.STARTING || state == DshServer.State.STOPPING
        startStopBtn.text = when (state) {
            DshServer.State.RUNNING -> "停止"
            DshServer.State.STARTING -> "启动中..."
            DshServer.State.STOPPING -> "停止中..."
            DshServer.State.IDLE -> "启动"
        }
        startStopBtn.isEnabled = !busy || state == DshServer.State.STOPPING
        refreshBtn.isEnabled = !busy
    }

    private fun appendLog(line: String) {
        // 日志里出现"找不到 dsh"警告 -> 标记, 启动失败通知里给出对应提示
        if (line.contains("找不到 dsh")) {
            dshMissingWarned = true
        }
        // 日志里出现"未检测到 Node.js 环境" -> 标记, 通知里提示安装 Node.js
        if (line.contains("未检测到 Node.js")) {
            nodeEnvMissingWarned = true
        }
        // 出现 WSL 子系统错误标识 (如 Wsl/Service/E_UNEXPECTED) -> 标记, 通知里给 WSL 修复建议
        if (line.contains("Wsl/") || line.contains("E_UNEXPECTED") ||
            line.contains("0x80040326") || line.contains("LxssManager")
        ) {
            wslErrorSeen = true
        }
        SwingUtilities.invokeLater {
            if (project.isDisposed) return@invokeLater
            logArea.append(line)
            logArea.append("\n")
            trimLog()
            logArea.caretPosition = logArea.document.length
        }
    }

    private fun trimLog(maxLines: Int = 5000) {
        try {
            if (logArea.lineCount > maxLines) {
                val end = logArea.getLineEndOffset(maxLines / 2)
                logArea.document.remove(0, end)
            }
        } catch (_: Exception) {
        }
    }

    companion object {
        private const val STR_IDLE = "● 未启动"
        private const val STR_STARTING = "● 启动中..."
        private const val STR_STOPPING = "● 停止中..."
        private const val STR_RUNNING = "● 运行中 (端口 %d)"
    }
}