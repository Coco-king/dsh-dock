package cn.codecrab.plugins.dsh.toolwindow

import cn.codecrab.plugins.dsh.DshBundle
import cn.codecrab.plugins.dsh.reference.DshReference
import cn.codecrab.plugins.dsh.server.DshServer
import cn.codecrab.plugins.dsh.settings.DshSettingsState
import cn.codecrab.plugins.dsh.util.WslSupport
import cn.codecrab.plugins.dsh.workspace.DshWorkspaceApi
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.ui.jcef.JBCefApp
import com.intellij.ui.jcef.JBCefBrowser
import com.intellij.ui.jcef.JBCefBrowserBuilder
import com.intellij.ui.jcef.JBCefClient
import org.cef.browser.CefBrowser
import org.cef.browser.CefFrame
import org.cef.handler.CefLoadHandler
import org.cef.handler.CefLoadHandlerAdapter
import org.cef.network.CefRequest
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean
import javax.swing.SwingUtilities

/**
 * 内嵌浏览器预热: 自动启动时机为「IDEA 启动时」时, dsh 就绪后即在后台提前创建
 * JCEF 浏览器并加载 WebUI 页面 (含工作空间同步与主题/语言注入),
 * 用户首次打开 Dsh Dock 工具窗口时直接复用已加载好的页面, 几乎瞬时显示。
 *
 * 背景: dsh 进程预热只覆盖了服务端; 工具窗口内容是首次打开时才懒创建的,
 * 首次打开还要付出 JCEF/CEF 冷启动 (加载 native 库、拉起浏览器子进程) 与
 * 页面加载两段等待 —— 预热把这两段也提前到 IDE 启动时完成。
 *
 * 复用与降级 (面板创建时调 [take]):
 *  - 预热完成 (页面已加载) 且端口一致、dsh 在运行 -> 面板直接复用浏览器与已加载页面,
 *    加载注入 (主题/语言/会话清除) 由面板接管;
 *  - 预热页面的工作空间与面板项目不一致 -> 面板复用后立即为本项目重新同步并刷新;
 *  - 页面尚未加载完 / 端口已变 / dsh 不在运行 -> 面板释放预热浏览器按原流程创建
 *    (此时 CEF 已由预热启动, 创建成本也已大幅降低);
 *  - 本会话只预热一次; 面板已存在 (如工具窗口随项目自动恢复) 时不再预热。
 */
object DshWebUiWarmup {

    private val LOG = Logger.getInstance(DshWebUiWarmup::class.java)

    /** 预热结果: 面板可整体复用 (client + 已加载页面), 或仅作为"CEF 已启动"的降级事实 */
    class Warmed internal constructor(
        val client: JBCefClient,
        val browser: JBCefBrowser,
        internal val warmHandler: CefLoadHandler,
        /** 预热时加载的端口 (面板按当前设置端口比对) */
        val port: Int,
        /** 预热时同步成功的工作空间 dsh 路径 (null = 同步失败, 面板复用后会重新同步) */
        val syncedDshPath: String?,
        /** 同步得到的会话 id (供面板接管后清除"不属于本项目"的持久化会话) */
        val sessionIds: List<String>?,
        private val pageLoadedFlag: AtomicBoolean,
    ) {
        /** 主框架是否已加载完成 (预热 onLoadEnd 置位; 面板按此决定能否整页复用) */
        val pageLoaded: Boolean
            get() = pageLoadedFlag.get()

        /** 释放预热的浏览器与 client (面板不复用整页时调用) */
        fun dispose() {
            try {
                browser.dispose()
            } catch (_: Throwable) {
            }
            try {
                client.dispose()
            } catch (_: Throwable) {
            }
        }
    }

    /** 本会话是否已发起过预热 (只预热一次) */
    private val lock = Any()

    @Volatile
    private var started = false

    /** 预热流程是否已结束 (无论成败) */
    @Volatile
    private var finished = false

    /** 预热结果是否已被面板取走 */
    @Volatile
    private var taken = false

    @Volatile
    private var warmed: Warmed? = null

    /** 预热进行中时, 后续日志实时转发给已打开的面板 (面板打开早于预热完成时仍能看到进度) */
    @Volatile
    private var liveSink: ((String) -> Unit)? = null

    /** 预热期间产生的日志 (面板创建后回放到工具窗口日志面板) */
    private val logBuffer: MutableCollection<String> = Collections.synchronizedList(ArrayList())

    /**
     * 发起预热 (IDE 启动自动启动流程调用): 等 dsh 就绪后同步工作空间并加载 WebUI。
     * 幂等: 本会话只预热一次; 已有面板 / 设置未开启 / JCEF 不可用时跳过。
     */
    fun warmupForProject(project: Project) {
        val settings = DshSettingsState.getInstance()
        if (settings.startMode != DshSettingsState.START_MODE_IDE) return
        // 面板已存在 (如工具窗口随项目自动恢复): 页面由面板自己管理, 不重复预热
        if (DshToolWindowRegistry.hasAnyPanel()) return
        if (!JBCefApp.isSupported()) return // JCEF 不可用: 交由面板的回退 UI 处理
        synchronized(lock) {
            if (started) return
            started = true
        }
        Thread({ run(project) }, "dsh-plugin-webui-warmup").apply { isDaemon = true }.start()
    }

    /**
     * 取走预热结果 (面板创建时调用), 取走后本会话不再保留。
     * @return 预热结果; 未预热 / 尚未创建出浏览器时返回 null
     */
    fun take(): Warmed? = synchronized(lock) {
        val w = warmed
        warmed = null
        if (w != null) taken = true
        w
    }

    /**
     * 取走并清空预热日志, 同时挂上实时转发 (后续预热日志实时送达 [sink])。
     * 面板创建时调用: 缓存的历史日志原样回放 (已按事件时刻打好时间戳), 之后的日志实时送达。
     */
    fun attachLiveSink(sink: (String) -> Unit): List<String> = synchronized(logBuffer) {
        liveSink = sink
        val copy = ArrayList(logBuffer)
        logBuffer.clear()
        copy
    }

    /** 预热是否进行中 (已发起、未结束; 供面板在预热未完成时给出提示) */
    fun warmupInFlight(): Boolean = synchronized(lock) { started && !finished }

    /** 日志时间戳格式 (DateTimeFormatter 不可变、线程安全, 可复用) */
    private val timeFormatter = java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss.SSS")

    private fun log(line: String) {
        // 日志产生时即打上时间戳: 面板回放时保留原事件时刻, 便于对照启动/预热各阶段耗时
        val stamped = "[${java.time.LocalTime.now().format(timeFormatter)}] $line"
        LOG.info("[webui-warmup] $stamped")
        // 有面板在听则实时转发 (不再进缓冲, 避免后续面板回放重复); 否则先缓存等面板创建时回放
        val sink = liveSink
        if (sink != null) sink.invoke(stamped) else logBuffer.add(stamped)
    }

    private fun run(project: Project) {
        try {
            val settings = DshSettingsState.getInstance()
            val port = settings.currentPort()
            log(DshBundle.message("log.warmup.start"))
            // 1) 等 dsh 就绪 (启动流程可能仍在进行): 与 DshServer.waitForReady 同款端口轮询
            val readyDeadline = System.currentTimeMillis() + 90_000
            while (System.currentTimeMillis() < readyDeadline) {
                if (DshServer.state == DshServer.State.RUNNING || WslSupport.isPortOpen(port)) break
                try {
                    Thread.sleep(700)
                } catch (_: InterruptedException) {
                    return
                }
            }
            if (DshServer.state != DshServer.State.RUNNING && !WslSupport.isPortOpen(port)) {
                log(DshBundle.message("log.warmup.giveUp", port.toString()))
                return
            }
            // 2) 工作空间同步 + 语言偏好 (与面板 loadWebUi 同序), 让预热加载的页面直接落在当前项目上
            val dshPath = project.basePath?.let { DshReference.dshPathFromString(it, settings.launchMode) }
            var sessionIds: List<String>? = null
            var syncedPath: String? = null
            if (dshPath != null) {
                var result: DshWorkspaceApi.WorkspaceSyncResult? = null
                val syncDeadline = System.currentTimeMillis() + 8000
                while (result == null && System.currentTimeMillis() < syncDeadline) {
                    result = DshWorkspaceApi.ensureProjectWorkspace(port, dshPath)
                    if (result == null && System.currentTimeMillis() < syncDeadline) {
                        try {
                            Thread.sleep(700)
                        } catch (_: InterruptedException) {
                            break
                        }
                    }
                }
                if (result != null) {
                    sessionIds = result.sessionIds
                    syncedPath = dshPath
                    log(DshBundle.message("log.workspaceReady", dshPath))
                } else {
                    log(DshBundle.message("log.workspaceSyncUnavailable"))
                }
                val dshLocale = DshWebUiInject.effectiveDshLocale(settings)
                if (DshWorkspaceApi.syncLocalePreference(port, dshLocale)) {
                    log(DshBundle.message("log.localeSet", dshLocale))
                }
            }
            // 3) EDT 创建浏览器 (Swing 组件安全), 挂预热注入 handler 后加载页面
            SwingUtilities.invokeLater {
                try {
                    val pageLoadedFlag = AtomicBoolean(false)
                    val jbClient = JBCefApp.getInstance().createClient()
                    val b = JBCefBrowserBuilder().setClient(jbClient).build()
                    val handler = object : CefLoadHandlerAdapter() {
                        override fun onLoadStart(
                            browser: CefBrowser,
                            frame: CefFrame,
                            transitionType: CefRequest.TransitionType,
                        ) {
                            if (frame.isMain) {
                                pageLoadedFlag.set(false)
                                DshWebUiInject.injectUiThemeLocaleOverride(browser, settings) { log(it) }
                                DshWebUiInject.clearPersistedSessionIfForeign(browser, sessionIds) { log(it) }
                            }
                        }

                        override fun onLoadEnd(browser: CefBrowser, frame: CefFrame, httpStatusCode: Int) {
                            if (frame.isMain) pageLoadedFlag.set(true)
                        }
                    }
                    val w = Warmed(jbClient, b, handler, port, syncedPath, sessionIds, pageLoadedFlag)
                    synchronized(lock) {
                        // 期间面板已自行创建 (取走过/本就无预热可复用): 释放刚创建的浏览器
                        if (taken || warmed != null || project.isDisposed) {
                            w.dispose()
                            return@invokeLater
                        }
                        warmed = w
                    }
                    jbClient.addLoadHandler(handler, b.cefBrowser)
                    // 新版 dsh 的 WebUI 首页需带启动令牌访问 (换取认证 cookie)
                    val url = DshServer.webTokenUrl(port) ?: DshServer.webUrl(port)
                    log(DshBundle.message("log.warmup.loading", url))
                    b.loadURL(url)
                    // 保险: 首次 loadURL 偶发未生效 (JCEF 初始化竞态) 时页面停在 about:blank,
                    // onLoadEnd 永远不会置位加载完成标记 —— 8s 后仍未完成则重试一次。
                    // 浏览器已被面板接管 (taken) 时加载职责归面板, 且预热 handler 已被摘除、
                    // 加载标记不会再更新, 此时绝不能代为刷新 (否则页面会无端重载一次)
                    val loadRetry = javax.swing.Timer(8000, null)
                    loadRetry.addActionListener {
                        loadRetry.stop()
                        if (!taken && !pageLoadedFlag.get()) {
                            try {
                                val retryUrl = DshServer.webTokenUrl(port) ?: DshServer.webUrl(port)
                                b.loadURL(retryUrl)
                            } catch (_: Throwable) {
                            }
                        }
                    }
                    loadRetry.isRepeats = false
                    loadRetry.start()
                } catch (t: Throwable) {
                    LOG.warn("webui warmup failed", t)
                    log(DshBundle.message("log.warmup.failed", t.message ?: "null"))
                }
            }
        } catch (t: Throwable) {
            LOG.warn("webui warmup failed", t)
        } finally {
            finished = true
        }
    }
}
