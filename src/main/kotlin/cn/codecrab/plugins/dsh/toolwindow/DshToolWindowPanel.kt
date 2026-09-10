package cn.codecrab.plugins.dsh.toolwindow

import cn.codecrab.plugins.dsh.DshBundle
import cn.codecrab.plugins.dsh.reference.DshReference
import cn.codecrab.plugins.dsh.server.DshServer
import cn.codecrab.plugins.dsh.server.DshWebAuth
import cn.codecrab.plugins.dsh.settings.DshSettingsConfigurable
import cn.codecrab.plugins.dsh.settings.DshSettingsState
import cn.codecrab.plugins.dsh.sync.DshEditorSync
import cn.codecrab.plugins.dsh.util.DshDisposer
import cn.codecrab.plugins.dsh.util.DshIdeName
import cn.codecrab.plugins.dsh.util.WslSupport
import cn.codecrab.plugins.dsh.workspace.DshWorkspaceApi
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.notification.Notifications
import com.intellij.openapi.Disposable
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.wm.WindowManager
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

    /** 日志时间戳格式 (DateTimeFormatter 不可变、线程安全, 可复用)。
     * 必须声明在 init 块之前: 构造面板期间 (预热日志回放/时间戳) 就会用到,
     * Kotlin 按声明顺序初始化, 声明在 init 之后时构造期间该字段尚未赋值 (NPE)。 */
    private val timeFormatter = java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss.SSS")

    /**
     * 状态监听器 (绑定到本面板实例, 注册与注销用的是同一实例)。
     * 注册后 DshServer 会立即用当前状态回调一次, 保证新面板创建时
     * 状态文字/按钮与"其他窗口已启动的 dsh"保持一致。
     */
    private val stateListener: (DshServer.State) -> Unit = ::onStateChanged

    private val statusLabel = JBLabel(DshBundle.message("panel.status.idle"))
    private val startStopBtn = JButton(DshBundle.message("panel.btn.start"))
    private val refreshBtn = JButton(DshBundle.message("panel.btn.refresh"))
    private val browserBtn = JButton(DshBundle.message("panel.btn.openBrowser"))
    private val logToggle = JToggleButton(DshBundle.message("panel.btn.log"))
    private val settingsBtn = JButton(DshBundle.message("panel.btn.settings"))

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

    /** 「点击文件路径在 IDE 中打开」的页面→IDE JS 通道 (随浏览器创建; 接管预热页面时沿用预热建立的) */
    private var fileOpenChannel: DshWebUiFileOpen.Channel? = null
    private var externalBrowserOpenedForSession: Boolean = false
    private var webUiLoaded: Boolean = false

    /**
     * dsh 文件同步监听: 监听 dsh 会话事件流, 写入类工具成功执行后把文件同步到 IDEA
     * (VFS 刷新 + 重载打开的编辑器)。仅在设置开启时创建; dsh 运行时连接, 停止/销毁时断开。
     */
    private var editorSync: DshEditorSync? = null

    /** 单飞标记: 一次"同步+加载"进行中时, 忽略并发的重复加载请求 (见 [loadWebUi]) */
    private val loadInFlight = java.util.concurrent.atomic.AtomicBoolean(false)

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
     * 本次工作空间同步是否失败。dsh API 偶尔在端口就绪后仍有一小段未就绪窗口,
     * 同步失败时页面仍会加载, 但工作空间不会切到当前项目 (手动刷新可恢复)。
     * 置位后, 页面加载完成 (onLoadEnd) 时自动刷新一次重试同步。
     */
    @Volatile
    private var workspaceSyncFailed: Boolean = false

    /** 当前失败周期是否已自动刷新过 (每个失败周期最多一次, 避免无限循环) */
    @Volatile
    private var syncAutoReloaded: Boolean = false

    /**
     * 本窗口最近一次成功同步为共享 dsh 工作空间的项目 dsh 路径 (null = 尚未成功同步)。
     * 多个窗口共用同一个 dsh 实例: 只有"活动窗口"才同步自己的工作空间。
     * 该值**跨 dsh 重启保留** (dsh 页面在服务重启后会自动重连自愈, 不需要整页刷新),
     * 用于判断窗口重新激活时: 若最近一次同步的正是本项目, 则工作空间已经是本项目的, 不整页刷新。
     */
    @Volatile
    private var lastSyncedPath: String? = null

    /** 看门狗上次观测到的本窗口是否为活动窗口 (用于判断是否发生了真正的"非活动 -> 活动"切换) */
    @Volatile
    private var lastObservedActive: Boolean? = null

    /** 当前激活周期是否已做过一次"激活恢复"同步 (每个激活周期最多一次, 避免反复重载) */
    @Volatile
    private var activationResynced: Boolean = false

    /**
     * 本面板是否发起过尚未完成的启动尝试。
     * 状态变化现在会广播到所有窗口的面板, 启动失败通知只在"发起启动的那个面板"弹出,
     * 因此该标志只能由本面板自己的启动调用置位, 不能在 STARTING 回调里统一置位。
     */
    @Volatile
    private var startRequestedByThisPanel: Boolean = false

    /** 正在等待预热完成 (占位状态): 此时 JCEF 尚不可用是预期, 不得触发"系统浏览器兜底" */
    @Volatile
    private var waitingForWarmup: Boolean = false

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

        // 文件同步监听: 必须在注册状态监听**之前**创建 —— addStateListener 会立即用当前
        // 状态回调一次, 若 dsh 已在运行 (打开面板时最常见的场景), 那次回调就要能启动连接,
        // 否则监听永远不会启动
        if (settings.syncEditedFiles) {
            val sync = DshEditorSync(project, settings.currentPort(), settings.launchMode, ::appendLog)
            editorSync = sync
            DshDisposer.register(parentDisposable, com.intellij.openapi.Disposable { sync.stop() })
        }
        // 注册状态监听: 立即以当前状态回调一次 (dsh 可能已在其他窗口启动), 后续所有窗口的
        // 启停状态变化都会广播到这里, 保证每个窗口的状态文字/按钮始终一致
        DshServer.addStateListener(stateListener)
        if (settings.startMode != DshSettingsState.START_MODE_MANUAL) {
            // 非手动模式: 打开窗口时若 dsh 未运行则兜底启动 (IDE 启动模式下它通常已在运行, 此为重试),
            // 稍后执行先渲染 UI
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
                val port = settings.currentPort()
                if (DshServer.state == DshServer.State.RUNNING || WslSupport.isPortOpen(port)) {
                    webUiLoaded = true
                    appendLog(DshBundle.message("log.portReady", port.toString()))
                    loadWebUi()
                }
            } else if (jcefAvailable && webUiLoaded) {
                // 页面已加载时: 窗口重新获得焦点后, 必要时把共享工作空间切回本窗口的项目
                checkActivationResync()
            }
            // 状态卡在 RUNNING 但端口已真实关闭 (如外部启动的 dsh 已被清理): 复位为未启动,
            // 让状态显示与「启动」按钮恢复可用 (仅复位外部 dsh 的状态, 不会触碰本插件启动的进程)
            if (DshServer.state == DshServer.State.RUNNING && !WslSupport.isPortOpen(settings.currentPort())) {
                DshServer.resetIfExternal()
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
        // 回放预热日志 (已按事件时刻打好时间戳, 原样追加) 并挂上实时转发接收预热后续日志
        for (line in DshWebUiWarmup.attachLiveSink { appendEdt(it) }) appendEdt(line)
        val warmed = DshWebUiWarmup.take()
        if (warmed == null && DshWebUiWarmup.warmupInFlight()) {
            // 窗口随 IDE 启动恢复而预热仍在进行: 不再并行自建浏览器/同步 (与预热重复劳动),
            // 先显示占位提示, 预热完成后直接复用其浏览器; 超时/放弃才回退自建
            appendLog(DshBundle.message("log.warmup.notReady"))
            waitForWarmupThenAdopt(content)
            return content
        }
        val holder = try {
            adoptWarmedBrowser(warmed)
                // 预热结果不可复用 (原因已记入日志): 释放后按原流程创建
                ?: run {
                    warmed?.dispose()
                    createBrowserHolder()
                }
        } catch (t: Throwable) {
            jcefAvailable = false
            appendLog(DshBundle.message("log.jcefUnavailable", t.message ?: "null"))
            createFallbackPanel()
        }
        content.add(holder, BorderLayout.CENTER)
        return content
    }

    /**
     * 预热进行中: 显示占位提示, 后台等待预热完成 (最长 30s) 后复用其浏览器。
     * 等待期间 jcefAvailable 保持 false, RUNNING 回调/看门狗不会触发重复的同步加载;
     * 预热放弃/失败/超时则回退自建浏览器, 由看门狗走正常"同步 -> 加载"流程。
     */
    private fun waitForWarmupThenAdopt(content: JPanel) {
        waitingForWarmup = true
        content.add(
            JBLabel(DshBundle.message("panel.warmupWaiting")).apply {
                horizontalAlignment = SwingConstants.CENTER
                foreground = JBColor.GRAY
                border = BorderFactory.createEmptyBorder(24, 16, 24, 16)
            },
            BorderLayout.CENTER
        )
        Thread({
            val deadline = System.currentTimeMillis() + 30_000
            var warmed: DshWebUiWarmup.Warmed? = null
            while (System.currentTimeMillis() < deadline) {
                warmed = DshWebUiWarmup.take()
                if (warmed != null) break
                if (!DshWebUiWarmup.warmupInFlight()) break // 预热已结束 (放弃/失败): 立即回退
                if (project.isDisposed) return@Thread
                try {
                    Thread.sleep(300)
                } catch (_: InterruptedException) {
                    return@Thread
                }
            }
            SwingUtilities.invokeLater {
                waitingForWarmup = false
                if (project.isDisposed) {
                    warmed?.dispose()
                    return@invokeLater
                }
                content.removeAll()
                val comp = if (warmed != null) {
                    try {
                        adoptWarmedBrowser(warmed)
                            ?: run {
                                warmed.dispose()
                                createBrowserHolder()
                            }
                    } catch (t: Throwable) {
                        jcefAvailable = false
                        appendLog(DshBundle.message("log.jcefUnavailable", t.message ?: "null"))
                        createFallbackPanel()
                    }
                } else {
                    appendLog(DshBundle.message("log.warmup.fallback"))
                    createBrowserHolder() // jcefAvailable=true, webUiLoaded=false: 看门狗稍后触发加载
                }
                content.add(comp, BorderLayout.CENTER)
                content.revalidate()
                content.repaint()
            }
        }, "dsh-plugin-warmup-wait").apply { isDaemon = true }.start()
    }

    /** 创建内嵌浏览器 (官方推荐组合: 先建 JBCefClient, 再经 builder 装配浏览器并挂 LoadHandler) */
    private fun createBrowserHolder(): JComponent {
        val jbClient: JBCefClient = JBCefApp.getInstance().createClient()
        val b: JBCefBrowser = JBCefBrowserBuilder().setClient(jbClient).build()
        // JS 通道必须在浏览器实体创建 (下方 browserComponent) 之前建立
        fileOpenChannel = DshWebUiFileOpen.createChannel(b, project, ::appendLog)
        jbClient.addLoadHandler(createInjectLoadHandler(), b.cefBrowser)
        jcefAvailable = true
        browser = b
        // 跨版本注册浏览器销毁钩子 (旧版 IDE 没有新包名的 Disposer)
        if (!DshDisposer.register(parentDisposable, b)) {
            appendLog(DshBundle.message("log.browserDisposeWarn"))
        }
        return browserComponent(b)
    }

    /**
     * 复用预热好的内嵌浏览器 ([DshWebUiWarmup]); 不可复用返回 null (由调用方释放并新建, 原因记入日志)。
     * 复用条件: 端口与当前设置一致、dsh 在运行; 页面未加载完也可先接管 (见下)。
     * 复用时把加载注入 (主题/语言/会话清除/引用暂存) 切换为本面板接管。
     *  - 页面已加载: 直接显示, **不再重新同步** (预热时已同步过; 工作空间不一致才补一次同步刷新);
     *  - 页面尚未加载完: 接管后短暂宽限等待预热加载完成, 超时才交回看门狗走"同步 -> 加载"流程。
     */
    private fun adoptWarmedBrowser(warmed: DshWebUiWarmup.Warmed?): JComponent? {
        if (warmed == null) return null
        val portOk = warmed.port == settings.currentPort()
        val dshAlive = DshServer.state == DshServer.State.RUNNING || WslSupport.isPortOpen(warmed.port)
        if (!portOk || !dshAlive) {
            appendLog(
                DshBundle.message(
                    "log.warmup.notAdopted",
                    DshBundle.message(if (!portOk) "log.warmup.reason.port" else "log.warmup.reason.dsh")
                )
            )
            return null
        }
        val workspaceMismatch = warmed.syncedDshPath != currentProjectDshPath()
        if (!warmed.pageLoaded && workspaceMismatch) {
            // 页面未加载完且预热的工作空间也不是本项目: 复用无意义, 走正常"同步 -> 加载"
            appendLog(
                DshBundle.message("log.warmup.notAdopted", DshBundle.message("log.warmup.reason.workspace"))
            )
            return null
        }
        val b = warmed.browser
        val jbClient = warmed.client
        try {
            // 加载注入接管: 移除预热 handler, 换成本面板的 (后续刷新由面板注入)
            try {
                jbClient.removeLoadHandler(warmed.warmHandler, b.cefBrowser)
            } catch (_: Throwable) {
            }
            jbClient.addLoadHandler(createInjectLoadHandler(), b.cefBrowser)
        } catch (_: Throwable) {
            // 注入 handler 挂载失败 (罕见): 不复用, 交给调用方释放新建
            appendLog(DshBundle.message("log.warmup.notAdopted", DshBundle.message("log.warmup.reason.handler")))
            return null
        }
        browser = b
        jcefAvailable = true
        // 沿用预热时建立的 JS 通道 (浏览器实体已创建, 此时无法再新建可用的通道)
        fileOpenChannel = warmed.fileOpenChannel
        syncSessionIds = warmed.sessionIds
        lastSyncedPath = warmed.syncedDshPath
        // 跨版本注册浏览器销毁钩子 (旧版 IDE 没有新包名的 Disposer)
        if (!DshDisposer.register(parentDisposable, b)) {
            appendLog(DshBundle.message("log.browserDisposeWarn"))
        }
        if (warmed.pageLoaded) {
            // 页面已就绪: 直接显示, 不重新同步 (预热时已同步过本项目工作空间)
            webUiLoaded = true
            pageLoaded = true
            appendLog(DshBundle.message("log.warmup.adopted"))
            // 预热页面不会再触发 onLoadEnd, 这里补注入一次点击拦截脚本
            installFileOpenBridge()
            if (workspaceMismatch) {
                SwingUtilities.invokeLater {
                    if (!project.isDisposed) loadWebUi()
                }
            }
        } else {
            // 页面尚未加载完: 先接管并短暂宽限等待其加载完成 (onLoadEnd 置位 pageLoaded);
            // 超时未完成则放行 (webUiLoaded=false), 由看门狗走"同步 -> 加载"流程
            webUiLoaded = true
            pageLoaded = false
            appendLog(DshBundle.message("log.warmup.adoptedLoading"))
            val grace = javax.swing.Timer(4000, null)
            grace.addActionListener {
                grace.stop()
                if (project.isDisposed) return@addActionListener
                if (!pageLoaded) {
                    webUiLoaded = false // 预热页未如期加载完: 交回看门狗重新同步加载
                }
            }
            grace.isRepeats = false
            grace.start()
        }
        return browserComponent(b)
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
     * 加载注入: 主题/语言覆盖与持久化会话清除 (公共实现在 [DshWebUiInject], 与浏览器预热共用)。
     * 语言控制的主路径是加载页面前写入 dsh 持久化语言偏好 (loadWebUi 中), 注入只作兜底并负责主题跟随。
     */
    private fun createInjectLoadHandler(): CefLoadHandlerAdapter = object : CefLoadHandlerAdapter() {
        override fun onLoadStart(browser: CefBrowser, frame: CefFrame, transitionType: CefRequest.TransitionType) {
            if (frame.isMain) {
                pageLoaded = false
                DshWebUiInject.injectUiThemeLocaleOverride(browser, settings, ::appendLog)
                // 在应用脚本执行前清除"不属于当前项目"的持久化会话选择
                DshWebUiInject.clearPersistedSessionIfForeign(browser, syncSessionIds, ::appendLog)
            }
        }

        override fun onLoadEnd(browser: CefBrowser, frame: CefFrame, httpStatusCode: Int) {
            if (frame.isMain) {
                // 新版 dsh 的 WebUI 需带启动令牌访问: 页面停在 401 认证页说明令牌不可用
                // (如外部启动的 dsh), 提示用户用带 token 的地址打开或经插件重启
                if (httpStatusCode == 401) appendLog(DshBundle.message("log.authRequired"))
                pageLoaded = true
                flushPendingReference()
                // 工作空间同步失败时, 页面加载完成后自动刷新一次重试 (与手动刷新等效)
                autoReloadAfterSyncFailure()
                installFileOpenBridge()
            }
        }
    }

    /** 注入「点击文件路径在 IDE 中打开」的拦截脚本 (页面加载完成 / 接管已加载的预热页面时调用) */
    private fun installFileOpenBridge() {
        val b = browser ?: return
        fileOpenChannel?.install(b, project)
    }

    // ---------- 引用注入 (右键菜单发送) ----------

    /**
     * 把 `@路径` 引用送入 WebUI 输入框光标处 (路径后自动带一个空格; 无有效光标时追加到末尾, 自动聚焦)。
     * 页面未就绪时先暂存, 待主框架加载完成后自动补发; dsh 未启动则先启动。
     */
    fun injectReference(reference: String) {
        if (reference.isBlank()) return
        pendingReference = reference
        if (!jcefAvailable) {
            appendLog(DshBundle.message("log.refNoEmbedded", reference))
            return
        }
        if (DshServer.state == DshServer.State.IDLE) {
            appendLog(DshBundle.message("log.refStartDsh"))
            ensureRunning()
        }
        flushPendingReference()
    }

    /** 页面就绪后把暂存引用注入输入框; 仅当当前页面已是 dsh WebUI 时才发送 */
    private fun flushPendingReference() {
        val ref = pendingReference ?: return
        if (!jcefAvailable || !pageLoaded) return
        val b = browser ?: return
        val expectedPrefix = DshServer.webUrl(settings.currentPort())
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
            appendLog(DshBundle.message("log.refSent", ref))
        } catch (t: Throwable) {
            appendLog(DshBundle.message("log.refSendFailed", t.message ?: "null"))
        }
    }

    /**
     * 注入脚本: 把引用插入到 WebUI 输入框的**光标处**, 并在路径后自动带一个空格
     * (插入点后一位已是空白时不再叠, 避免双空格); 输入框里没有有效光标时回退为末尾追加。
     * 兼容两代 dsh 输入框:
     *  - 新版 (0.1.2+, Lexical contenteditable): 输入组件带 `data-phase`, 是 contenteditable 而非
     *    textarea。受控状态只能通过触发 beforeinput 更新 —— 聚焦后 `document.execCommand('insertText')`
     *    会触发 Lexical 的 beforeinput 处理, 草稿随之更新 (直接改 DOM 文本不会进 Lexical 状态);
     *    插入前先检测选区是否仍在输入框内: 在框内保持光标位置原地插入 (不再强制移到末尾)。
     *  - 旧版 (≤0.1.1, React 受控 textarea): `textarea[data-phase]`, 用原生 value setter + input 事件,
     *    按 selectionStart/End 在真实光标处拼接插入 (textarea 失焦后光标位置仍保留)。
     * 两种都找不到时自动重试 (最多 20 次 x 250ms)。注意: 每次都插入, 不做去重。
     */
    private fun buildInjectScript(reference: String): String {
        val refJs = DshWebUiInject.jsString(reference)
        return """
            |(function () {
            |  var REF = $refJs;
            |  var MAX_TRIES = 20;
            |  var tries = 0;
            |  // 旧版 textarea: 在真实光标处拼接插入 (React 受控, 原生 value setter + input 事件)。
            |  // 文本框失焦后 selectionStart/End 仍保留; 从未聚焦过 (activeElement 不是它) 时回退末尾追加。
            |  function insertIntoTextarea(ta, text) {
            |    var cur = ta.value || "";
            |    var hasCaret = document.activeElement === ta;
            |    var start = hasCaret ? ta.selectionStart : cur.length;
            |    var end = hasCaret ? ta.selectionEnd : cur.length;
            |    var before = cur.slice(0, start);
            |    var after = cur.slice(end);
            |    // 光标前一字符非空白 -> 补前置空格, 保证 @引用 独立成词
            |    var prepend = before.length === 0 ? "" : (/\s/.test(before.charAt(before.length - 1)) ? "" : " ");
            |    // 路径后自动带一个空格; 插入点后已是空白则不再叠 (避免双空格)
            |    var trailing = /\s/.test(after.charAt(0)) ? "" : " ";
            |    var next = before + prepend + text + trailing + after;
            |    var caret = start + prepend.length + text.length + trailing.length;
            |    var setter = Object.getOwnPropertyDescriptor(HTMLTextAreaElement.prototype, "value").set;
            |    setter.call(ta, next);
            |    ta.dispatchEvent(new Event("input", { bubbles: true }));
            |    ta.focus();
            |    try { ta.setSelectionRange(caret, caret); } catch (err) {}
            |  }
            |  // 新版 contenteditable (Lexical): 插入到光标处, 无有效光标时末尾追加。
            |  // 插入前先记录选区是否在输入框内并推导前置/尾部空格, 聚焦后保持选区,
            |  // execCommand 触发 beforeinput -> Lexical 更新草稿, 光标自动落到插入内容后。
            |  function insertIntoContentEditable(ce, text) {
            |    var sel = window.getSelection();
            |    var hasCaret = !!sel && sel.rangeCount > 0 &&
            |        ce.contains(sel.anchorNode) && ce.contains(sel.focusNode);
            |    var prepend = "";
            |    var trailing = " ";
            |    try {
            |      if (hasCaret) {
            |        var range = sel.getRangeAt(0);
            |        var n = range.startContainer;
            |        // 文本节点上才能精确取到光标前后的字符; 元素节点 (行首/块边界) 视为已有换行分隔
            |        if (n && n.nodeType === 3) {
            |          if (range.startOffset > 0 && !/\s/.test(n.data.charAt(range.startOffset - 1))) prepend = " ";
            |          if (range.startOffset < n.data.length && /\s/.test(n.data.charAt(range.startOffset))) trailing = "";
            |        }
            |      } else {
            |        // 无光标: 末尾追加, 前一个字符非空白则补前置空格
            |        var lastText = ce.textContent || "";
            |        if (lastText.length > 0 && !/\s/.test(lastText.charAt(lastText.length - 1))) prepend = " ";
            |      }
            |    } catch (err) {}
            |    ce.focus();
            |    if (hasCaret) {
            |      // 光标在框内: 保持选区原地插入
            |      try {
            |        var inserted = document.execCommand("insertText", false, prepend + text + trailing);
            |        if (inserted) return;
            |      } catch (err) {}
            |    } else {
            |      // 回退末尾追加: 先把光标定位到末尾 (旧逻辑)
            |      try {
            |        var s2 = window.getSelection();
            |        if (s2) { s2.selectAllChildren(ce); s2.collapseToEnd(); }
            |        var inserted2 = document.execCommand("insertText", false, prepend + text + trailing);
            |        if (inserted2) return;
            |      } catch (err) {}
            |    }
            |    // 兜底: 直接改文本 + input 事件 (普通 contenteditable 可生效; 光标位置未知, 按末尾追加)
            |    var curText = ce.textContent || "";
            |    var lastCh = curText.length > 0 ? curText.charAt(curText.length - 1) : "";
            |    var endPrepend = lastCh.length > 0 && !/\s/.test(lastCh) ? " " : "";
            |    ce.textContent = curText + endPrepend + text + " ";
            |    ce.dispatchEvent(new Event("input", { bubbles: true }));
            |  }
            |  function pickEditable() {
            |    var all = document.querySelectorAll("[data-phase]");
            |    for (var i = all.length - 1; i >= 0; i--) {
            |      var el = all[i];
            |      if (!el || el.nodeType !== 1) continue;
            |      if (el.getAttribute && el.getAttribute("aria-disabled") === "true") continue;
            |      if (el.isContentEditable || el.tabIndex >= 0) return el;
            |    }
            |    return null;
            |  }
            |  function inject() {
            |    // 旧版 textarea 优先 (显式 textarea)
            |    var ta = null;
            |    var textareas = document.querySelectorAll("textarea[data-phase]");
            |    for (var i = textareas.length - 1; i >= 0; i--) {
            |      if (!textareas[i].disabled && !textareas[i].readOnly) { ta = textareas[i]; break; }
            |    }
            |    if (ta) { insertIntoTextarea(ta, REF); return; }
            |    // 新版 contenteditable 输入框
            |    var ce = pickEditable();
            |    if (ce) { insertIntoContentEditable(ce, REF); return; }
            |    if (tries++ < MAX_TRIES) { setTimeout(inject, 250); }
            |  }
            |  inject();
            |})();
        """.trimMargin()
    }

    private fun createFallbackPanel(): JComponent {
        val panel = JPanel(BorderLayout())
        panel.isOpaque = false

        val tip = JBLabel(
            "<html><center><b>${DshBundle.message("panel.fallback.title")}</b><br>" +
                DshBundle.message("panel.fallback.message") +
                "</center></html>"
        )
        tip.horizontalAlignment = SwingConstants.CENTER

        val buttons = JPanel(FlowLayout(FlowLayout.CENTER, 8, 4)).apply { isOpaque = false }
        val openBtn = JButton(DshBundle.message("panel.fallback.openBrowser"))
        openBtn.addActionListener { openInSystemBrowser() }
        val retryBtn = JButton(DshBundle.message("panel.fallback.retry"))
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
        appendLog(DshBundle.message("log.startingDsh"))
        // 仅当真正发起启动尝试 (非"已在运行/端口被占用") 时才标记本面板, 供失败通知使用
        startRequestedByThisPanel = DshServer.start(project.basePath, ::appendLog)
    }

    private fun reloadWebUi() {
        if (jcefAvailable) {
            // 手动刷新: 立即重载页面 (同步转后台), 不再等同步链路走完才动
            loadWebUi(loadFirst = true)
        } else {
            // JCEF 不可用时, 刷新改为在系统浏览器中打开
            openInSystemBrowser()
        }
    }

    /**
     * 加载 WebUI。
     *  - [loadFirst]=false (默认, 启动/激活恢复/自动重试): 先同步工作空间 (带重试) 再加载页面,
     *    保证首屏就落在当前项目上;
     *  - [loadFirst]=true (手动刷新): 立即重载页面, 同步转后台 —— 工作空间绝大多数情况本就是
     *    当前项目, 页面直接可用; 仅当同步发现需要切换工作空间 (bumped) 或同步失败时才补刷一次。
     *
     * 多窗口共用同一个 dsh 实例 (同一进程的多个项目窗口、或复用同一端口的多个 IDE 实例):
     * **只有当前活动窗口才同步工作空间, 非活动窗口不刷新页面、不抢占**共享 dsh 的"项目空间"。
     * 非活动窗口已显示的页面保持原样 (不会因其他窗口重启 dsh 而被整个刷掉):
     * 窗口重新获得焦点后由 [checkActivationResync] 再同步并切回自己的项目。
     *
     * 地址说明: 新版 dsh (0.1.2+) 的 WebUI 首页需带启动令牌访问 (换取认证 cookie),
     * 页面加载优先用带令牌的地址 (令牌随 dsh 进程输出打印, 插件自动捕获);
     * 令牌未捕获 (如外部启动的 dsh) 时退回普通地址, 页面会停在 dsh 的认证提示页。
     */
    private fun loadWebUi(loadFirst: Boolean = false) {
        val port = settings.currentPort()
        val projectPath = project.basePath
        if (projectPath == null) {
            val url = DshServer.webTokenUrl(port) ?: DshServer.webUrl(port)
            appendLog(DshBundle.message("log.loaded", url))
            browser?.loadURL(url)
            return
        }
        if (!isActiveWindow()) {
            // 后台窗口: 不刷新页面、也不推送工作空间 —— 避免 dsh 重启或其他窗口操作时,
            // 把正在显示对话的页面整个刷掉, 或把共享 dsh 的工作空间抢成别的项目。
            // 已加载页面保持原样, 窗口重新获得焦点后由 [checkActivationResync] 恢复。
            appendLog(DshBundle.message("log.inactiveSkip"))
            return
        }
        // 单飞: 已有一次"同步+加载"在进行时, 忽略并发的重复请求 (RUNNING 回调/看门狗/手动刷新),
        // 避免"先加载一次、工作空间同步完又刷新一次"的体验问题
        if (!loadInFlight.compareAndSet(false, true)) return
        if (loadFirst) {
            // 先立即重载页面, 让刷新按钮即时生效
            val url = DshServer.webTokenUrl(port) ?: DshServer.webUrl(port)
            appendLog(DshBundle.message("log.loaded", url))
            browser?.loadURL(url)
        }
        appendLog(DshBundle.message("log.syncingWorkspace", projectPath))
        Thread({
            var settled = false
            try {
                // 新版 dsh 的启动令牌随进程输出到达, 通常比端口就绪晚几百毫秒:
                // 等令牌后再决定加载地址, 避免页面首次加载就落在 401 认证页
                if (DshServer.webTokenUrl(port) == null) DshWebAuth.awaitToken(3000)
                val url = DshServer.webTokenUrl(port) ?: DshServer.webUrl(port)
                val dshPath = DshReference.dshPathFromString(projectPath, settings.launchMode)
                // 端口刚就绪时 dsh 的 /api 可能还有一小段未就绪窗口: 小间隔重试同步 (约 8s 上限)
                var result: DshWorkspaceApi.WorkspaceSyncResult? = null
                val deadline = System.currentTimeMillis() + 8000
                while (result == null && System.currentTimeMillis() < deadline) {
                    result = DshWorkspaceApi.ensureProjectWorkspace(port, dshPath)
                    if (result == null && System.currentTimeMillis() < deadline) {
                        try {
                            Thread.sleep(700)
                        } catch (_: InterruptedException) {
                            break
                        }
                    }
                }
                if (result != null) {
                    appendLog(DshBundle.message("log.workspaceReady", dshPath ?: projectPath))
                    // 记录当前项目的 sessionIds, 供 onLoadStart 清除不属于本项目的持久化会话选择
                    syncSessionIds = result.sessionIds
                    workspaceSyncFailed = false
                    syncAutoReloaded = false
                    // 记录本面板最近一次成功同步的项目路径 (供窗口激活恢复 [checkActivationResync] 判断)
                    lastSyncedPath = dshPath
                } else {
                    appendLog(DshBundle.message("log.workspaceSyncUnavailable"))
                    // 页面加载完成后自动刷新一次重试 (见 [autoReloadAfterSyncFailure])
                    workspaceSyncFailed = true
                }
                // dsh 语言跟随: 写入持久化语言偏好 (settings.locale.preference),
                // 页面加载后即生效 (dsh 的语言优先级: 偏好 > 浏览器); 失败不阻断加载
                val dshLocale = DshWebUiInject.effectiveDshLocale(settings)
                if (DshWorkspaceApi.syncLocalePreference(port, dshLocale)) {
                    appendLog(DshBundle.message("log.localeSet", dshLocale))
                }
                settled = true
                SwingUtilities.invokeLater {
                    // 先释放单飞标记再发起加载 (同一 EDT 任务内不会插入新的加载请求)
                    loadInFlight.set(false)
                    if (project.isDisposed) return@invokeLater
                    val r = result
                    when {
                        // 同步前已加载过页面: 仅当需要切换工作空间或同步失败时才自动补刷一次
                        loadFirst && (r == null || r.bumped) -> {
                            appendLog(
                                DshBundle.message(
                                    if (r == null) "log.syncFailedAutoReload" else "log.workspaceSwitchedReload"
                                )
                            )
                            browser?.loadURL(url)
                        }
                        // 同步前已加载且工作空间无需切换: 页面已正确, 无需再刷
                        !loadFirst -> {
                            appendLog(DshBundle.message("log.loaded", url))
                            browser?.loadURL(url)
                        }
                    }
                }
            } finally {
                // 兜底: 若同步/加载流程异常中断 (invokeLater 未安排), 也要释放单飞标记
                if (!settled) loadInFlight.set(false)
            }
        }, "dsh-plugin-workspace-ensure").apply { isDaemon = true }.start()
    }

    /**
     * 本面板所在窗口是否为当前活动(聚焦)窗口。
     * 用 `WindowManager.getFrame(project)` 取本窗口的 JFrame, 与 AWT 键盘焦点窗口比较:
     * 相等则本窗口是活动窗口; 本 JVM 内没有键盘焦点窗口 (单窗口 / 焦点在内嵌浏览器等
     * 原生组件上) 时视为活动, 保证单窗口用户行为与之前完全一致。
     * 仅用稳定 API (WindowManager.getFrame + AWT KeyboardFocusManager), 跨 IntelliJ 2023.1 起全版本安全。
     */
    private fun isActiveWindow(): Boolean {
        return try {
            val wm = WindowManager.getInstance()
            val frame = wm.getFrame(project) ?: return true
            val focused = java.awt.KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusedWindow()
            if (focused == null) return true
            frame == focused
        } catch (_: Throwable) {
            true
        }
    }

    /** 本项目在 dsh 侧的工作空间路径 (按启动方式转换, 见 [DshReference]) */
    private fun currentProjectDshPath(): String? =
        DshReference.dshPathFromString(project.basePath, settings.launchMode)

    /**
     * 激活恢复: 窗口从**非活动真正变为活动**时, 若本窗口项目尚未成功同步为共享 dsh 的工作空间
     * (页面从未加载 / 上次同步失败), 就同步一次并刷新页面把它切回自己的项目。
     * 已同步过自己项目的窗口切回时**不整页刷新** —— dsh 页面在服务重启后由客户端自动重连自愈,
     * 且窗口本就在显示自己的工作空间, 无需重载。
     * 启动/首次加载时窗口本就处于活动状态 (非"切换"而来), 不在这里触发 —— 启动时的
     * 同步已由 [loadWebUi] 的加载前重试负责, 避免启动时多一次整页刷新。
     * 每个激活周期最多触发一次 ([activationResynced])。
     */
    private fun checkActivationResync() {
        if (project.isDisposed) return
        val active = isActiveWindow()
        val prev = lastObservedActive
        lastObservedActive = active
        if (!active) {
            // 窗口失去活动状态: 重置本激活周期的恢复标记, 下次激活可再次恢复
            activationResynced = false
            return
        }
        // 仅在窗口从"非活动"变为"活动"时恢复; 首次观测(prev==null, 启动时即活动)不触发
        if (prev != false) return
        if (activationResynced) return
        activationResynced = true
        if (lastSyncedPath == currentProjectDshPath()) return
        appendLog(DshBundle.message("log.activationResync"))
        loadWebUi()
    }

    /**
     * 工作空间同步偶尔会因 dsh API 尚未完全就绪而失败 (页面本身能正常加载,
     * 但工作空间没有切到当前项目, 用户手动刷新即可恢复)。
     * 这里在页面加载完成后自动刷新一次重新同步 —— 与手动刷新等效;
     * 每个失败周期最多自动刷新一次 (由 [syncAutoReloaded] 保证), 避免循环。
     */
    private fun autoReloadAfterSyncFailure() {
        if (!workspaceSyncFailed || syncAutoReloaded) return
        syncAutoReloaded = true
        workspaceSyncFailed = false
        SwingUtilities.invokeLater {
            if (project.isDisposed) return@invokeLater
            val timer = javax.swing.Timer(1200, null)
            timer.addActionListener {
                if (project.isDisposed) return@addActionListener
                appendLog(DshBundle.message("log.syncFailedAutoReload"))
                loadWebUi()
            }
            timer.isRepeats = false
            timer.start()
        }
    }

    private fun openInSystemBrowser() {
        if (!WslSupport.isPortOpen(settings.currentPort())) {
            appendLog(DshBundle.message("log.notReadyStart", settings.currentPort().toString()))
            Messages.showWarningDialog(
                project,
                DshBundle.message("warn.dshNotStarted", settings.currentPort().toString()),
                "Dsh Dock"
            )
            return
        }
        browseWebUiAsync()
    }

    /**
     * 在系统浏览器打开 WebUI: 优先带启动令牌的地址 (新版 dsh 认证需要);
     * 令牌尚未捕获时后台稍等再开, 避免系统浏览器停在认证页。
     */
    private fun browseWebUiAsync() {
        val port = settings.currentPort()
        val tokenUrl = DshServer.webTokenUrl(port)
        if (tokenUrl != null) {
            desktopBrowse(tokenUrl)
            appendLog(DshBundle.message("log.openedExternal", tokenUrl))
            return
        }
        Thread({
            DshWebAuth.awaitToken(3000)
            SwingUtilities.invokeLater {
                if (project.isDisposed) return@invokeLater
                val url = DshServer.webTokenUrl(port) ?: DshServer.webUrl(port)
                desktopBrowse(url)
                appendLog(DshBundle.message("log.openedExternal", url))
            }
        }, "dsh-plugin-browse-token-wait").apply { isDaemon = true }.start()
    }

    /** 跨版本安全: 不依赖平台 BrowserUtil (2024.2 已移除), 直接用 java.awt.Desktop */
    private fun desktopBrowse(url: String) {
        try {
            if (java.awt.Desktop.isDesktopSupported()) {
                java.awt.Desktop.getDesktop().browse(java.net.URI(url))
            } else {
                appendLog(DshBundle.message("log.desktopUnavailable", url))
            }
        } catch (t: Throwable) {
            appendLog(DshBundle.message("log.openBrowserFailed", t.message ?: "null"))
        }
    }

    private fun openSettings() {
        // 新版包名 com.intellij.openapi.options.ShowSettingsUtil (2023.3+);
        // 旧版 IDE (2022.x) 上没有这个类, 捕获后提示手动打开设置
        try {
            ShowSettingsUtil.getInstance().showSettingsDialog(project, DshSettingsConfigurable::class.java)
        } catch (t: Throwable) {
            appendLog(DshBundle.message("log.openSettingsUnsupported", t.message ?: "null"))
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
                    statusLabel.text = DshBundle.message("panel.status.running", settings.currentPort().toString())
                    statusLabel.foreground = JBColor(Color(0x1B8A1B), Color(0x6FCF6F))
                    // dsh 就绪: 启动文件同步监听 (dsh 编辑文件后自动刷新 IDEA 编辑器)
                    editorSync?.start()
                    // 与看门狗同一门控 (!webUiLoaded): 已发起过加载就不再重复触发,
                    // 避免 RUNNING 回调与看门狗并发各发起一次"同步+加载"导致页面加载两次
                    if (jcefAvailable && !webUiLoaded) {
                        webUiLoaded = true
                        loadWebUi()
                    }
                    // JCEF 可用时按设置决定是否也在系统浏览器打开;
                    // JCEF 不可用时自动改用系统浏览器打开 WebUI。
                    // 等待预热期间 jcefAvailable=false 是占位状态的预期, 不算"不可用", 不触发兜底
                    if ((settings.openExternalBrowser || !jcefAvailable) &&
                        !externalBrowserOpenedForSession && !waitingForWarmup
                    ) {
                        externalBrowserOpenedForSession = true
                        browseWebUiAsync()
                        if (!jcefAvailable) {
                            appendLog(DshBundle.message("log.externalBrowserFallback"))
                        }
                    }
                }
                DshServer.State.STARTING -> {
                    statusLabel.text = DshBundle.message("panel.status.starting")
                    statusLabel.foreground = JBColor(Color(0xB8860B), Color(0xE6C560))
                }
                DshServer.State.STOPPING -> {
                    // 用户主动停止 (含启动过程中点停止), 不算启动失败
                    startRequestedByThisPanel = false
                    statusLabel.text = DshBundle.message("panel.status.stopping")
                    statusLabel.foreground = JBColor(Color(0xB8860B), Color(0xE6C560))
                    editorSync?.stop()
                }
                DshServer.State.IDLE -> {
                    val failed = startRequestedByThisPanel
                    val missingDsh = dshMissingWarned
                    val nodeMissing = nodeEnvMissingWarned
                    val wslError = wslErrorSeen
                    startRequestedByThisPanel = false
                    statusLabel.text = DshBundle.message("panel.status.idle")
                    statusLabel.foreground = JBColor.GRAY
                    externalBrowserOpenedForSession = false
                    webUiLoaded = false
                    // 页面视为过期 (可能还停留在旧 WebUI / 已失效页面), 重启后需重新加载再注入
                    pageLoaded = false
                    // dsh 已停止: 断开文件同步监听 (重启后 RUNNING 会重新连接)
                    editorSync?.stop()
                    // 激活恢复标记复位。不在此清空 lastSyncedPath: dsh 页面在服务重启后会自动重连
                    // 自愈 (客户端连接循环指数退避重连), 切回本窗口时若工作空间已是本项目就不再整页刷新
                    activationResynced = false
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
                nodeMissing -> if (settings.launchMode == DshSettingsState.MODE_WSL) {
                    DshBundle.message("notify.fail.nodeMissing.wsl")
                } else {
                    DshBundle.message("notify.fail.nodeMissing.windows")
                }
                missingDsh -> if (settings.launchMode == DshSettingsState.MODE_WSL) {
                    DshBundle.message("notify.fail.dshMissing.wsl", DshIdeName.productName())
                } else {
                    DshBundle.message("notify.fail.dshMissing.windows", DshIdeName.productName())
                }
                wslError -> DshBundle.message("notify.fail.wslError")
                else -> if (settings.launchMode == DshSettingsState.MODE_WSL) {
                    DshBundle.message("notify.fail.generic.wsl", settings.currentPort().toString())
                } else {
                    DshBundle.message("notify.fail.generic.windows", settings.currentPort().toString())
                }
            }
            Notifications.Bus.notify(
                group.createNotification(DshBundle.message("notify.fail.title"), content, NotificationType.ERROR),
                project
            )
        } catch (t: Throwable) {
            appendLog(DshBundle.message("log.notifyFailed", t.message ?: "null"))
        }
    }

    private fun updateButtons(state: DshServer.State) {
        val busy = state == DshServer.State.STARTING || state == DshServer.State.STOPPING
        startStopBtn.text = when (state) {
            DshServer.State.RUNNING -> DshBundle.message("panel.btn.stop")
            DshServer.State.STARTING -> DshBundle.message("panel.btn.starting")
            DshServer.State.STOPPING -> DshBundle.message("panel.btn.stopping")
            DshServer.State.IDLE -> DshBundle.message("panel.btn.start")
        }
        startStopBtn.isEnabled = !busy || state == DshServer.State.STOPPING
        refreshBtn.isEnabled = !busy
    }

    /** 给日志行加时间戳 (多行消息只在首行加, 换行后的内容原样保留) */
    private fun stamp(line: String): String {
        val ts = java.time.LocalTime.now().format(timeFormatter)
        val nl = line.indexOf('\n')
        return if (nl >= 0) "[$ts] ${line.substring(0, nl)}${line.substring(nl)}" else "[$ts] $line"
    }

    private fun appendLog(line: String) {
        // 日志里出现"找不到 dsh"警告 -> 标记, 启动失败通知里给出对应提示
        // (中英两种标记都匹配: 生成脚本里已按 IDE 语言输出对应语言的消息)
        if (line.contains("找不到 dsh") || line.contains("dsh not found")) {
            dshMissingWarned = true
        }
        // 日志里出现"未检测到 Node.js 环境" -> 标记, 通知里提示安装 Node.js
        if (line.contains("未检测到 Node.js") || line.contains("no Node.js environment")) {
            nodeEnvMissingWarned = true
        }
        // 出现 WSL 子系统错误标识 (如 Wsl/Service/E_UNEXPECTED) -> 标记, 通知里给 WSL 修复建议
        if (line.contains("Wsl/") || line.contains("E_UNEXPECTED") ||
            line.contains("0x80040326") || line.contains("LxssManager")
        ) {
            wslErrorSeen = true
        }
        // 时间戳在日志产生时 (调用线程) 立即打上, 而非 EDT 实际渲染时 ——
        // EDT 繁忙 (如 JCEF 冷启动) 会延迟渲染, 按渲染时间计时会失真
        appendEdt(stamp(line))
    }

    /** 把已带时间戳的文本追加到日志面板 (预热日志回放用: 时间戳已在事件产生时打好) */
    private fun appendEdt(text: String) {
        SwingUtilities.invokeLater {
            if (project.isDisposed) return@invokeLater
            logArea.append(text)
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
}