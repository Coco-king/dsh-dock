package cn.codecrab.plugins.dsh.toolwindow

import cn.codecrab.plugins.dsh.DshBundle
import cn.codecrab.plugins.dsh.reference.DshReference
import cn.codecrab.plugins.dsh.server.DshServer
import cn.codecrab.plugins.dsh.server.DshWebAuth
import cn.codecrab.plugins.dsh.settings.DshSettingsState
import cn.codecrab.plugins.dsh.util.DshJcefCookies
import cn.codecrab.plugins.dsh.util.DshJcefSupport
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
import java.util.concurrent.locks.ReentrantLock
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
 * **按窗口预热**: 一个 JCEF 浏览器只能挂在一个窗口里, 多项目窗口下每个窗口都
 * 需要自己的浏览器, 因此预热以**项目**为单位各做一份 (每个项目一次): 哪个窗口
 * 首次打开工具窗口都能直接复用, 不必现场冷启动 CEF。
 *
 * 复用与降级 (面板创建时调 [takeOwn] / [takeForeign]):
 *  - 优先取本项目自己的预热结果 (工作空间就是本项目, 接管即用, 无需重新加载);
 *  - 本项目没有可用结果时**借用其他项目的预热浏览器** —— 预热的工作空间与当前项目
 *    不一致也没关系, 面板接管后重新同步并切回本项目 ([DshToolWindowPanel.loadWebUi]);
 *  - 页面尚未加载完 / 端口已变 / dsh 不在运行 -> 面板释放预热浏览器按原流程创建
 *    (此时 CEF 已由预热启动, 创建成本也已大幅降低);
 *  - 面板已存在 (如工具窗口随项目自动恢复) 的项目不再预热 (页面由面板自己管理)。
 *
 * 多窗口共用一个 dsh 实例时, 工作空间只有一份 ("最近工作空间"决定页面落在哪个项目):
 * 预热页面必须**在同步完本项目工作空间之后立刻加载**, 否则可能落到别的项目上。
 * 因此 [sequenceLock] 把 "同步工作空间 -> 加载页面 -> 页面完成首次选择" 串行化,
 * 让每个窗口的预热页面都落在自己的工作空间上。
 */
object DshWebUiWarmup {

    private val LOG = Logger.getInstance(DshWebUiWarmup::class.java)

    /** 预热结果: 面板可整体复用 (client + 已加载页面), 或仅作为"CEF 已启动"的降级事实 */
    class Warmed internal constructor(
        /** 本预热所属项目 (预热时同步的工作空间 = 该项目的) */
        val project: Project,
        val client: JBCefClient,
        val browser: JBCefBrowser,
        internal val warmHandler: CefLoadHandler,
        /** 预热时加载的端口 (面板按当前设置端口比对) */
        val port: Int,
        /** 预热时同步成功的工作空间 dsh 路径 (null = 同步失败, 面板复用后会重新同步) */
        val syncedDshPath: String?,
        /** 同步得到的会话 id (供面板接管后把页面固定在本项目工作空间) */
        val sessionIds: List<String>?,
        /** 同步得到的"落地会话": 页面应打开的会话 (见 DshWorkspaceApi.WorkspaceSyncResult) */
        val landingSessionId: String?,
        /** 同步得到的本项目工作空间 id (页面落到别处时按它切回本项目) */
        val workspaceId: String?,
        /** 本项目工作空间里是否有非空白会话 (没有时页面脚本才允许"新建会话"切回) */
        val hasContentSession: Boolean,
        private val pageLoadedFlag: AtomicBoolean,
        /** 页面→IDE 的 JS 通道 (「点击文件路径在 IDE 中打开」用; 必须在浏览器创建前建立, 只能随浏览器一起移交) */
        val fileOpenChannel: DshWebUiFileOpen.Channel?,
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

    /** 单个项目的预热状态 (每个项目一份) */
    private class Slot {
        /** 预热流程是否已结束 (无论成败; 由 run 的 finally 置位) */
        @Volatile
        var finished: Boolean = false

        /** 预热结果是否已被面板取走 (取走后加载职责归面板) */
        @Volatile
        var taken: Boolean = false

        /** 预热产出的浏览器 (未被取走时) */
        @Volatile
        var warmed: Warmed? = null
    }

    /** 保护 [slots] 的锁 (预热线程、面板 EDT 线程、等待线程都会访问) */
    private val lock = Any()

    /** 项目 -> 预热状态 (每个项目只预热一次) */
    private val slots = HashMap<Project, Slot>()

    /**
     * 串行化 "同步工作空间 -> 加载页面 -> 页面完成首次选择" (见类注释):
     * 多窗口同时预热时, 各窗口的同步会互相把 dsh 的"最近工作空间"抢来抢去,
     * 不加锁时先同步的窗口页面后加载、可能落在后同步的窗口项目上。
     */
    private val sequenceLock = ReentrantLock()

    /** 等页面加载完成的上限 (超时后放行, 面板接管后自行处理) */
    private const val PAGE_LOAD_WAIT_MS = 20_000L

    /** 加载观察宽限期: loadURL 之后要过这么久, "当前没在加载"才可信 (导航还没开始的空档) */
    private const val PAGE_NAVIGATION_GRACE_MS = 700L

    /** 页面加载完成后再稳定这么久才放行下一个窗口的预热 (留给 dsh 客户端完成工作空间/会话选择) */
    private const val PAGE_SETTLE_MS = 500L

    /** 预热进行中时, 后续日志实时转发给已打开的面板 (面板打开早于预热完成时仍能看到进度) */
    @Volatile
    private var liveSink: ((String) -> Unit)? = null

    /** 预热期间产生的日志 (面板创建后回放到工具窗口日志面板) */
    private val logBuffer: MutableCollection<String> = Collections.synchronizedList(ArrayList())

    /**
     * 发起预热 (IDE 启动自动启动流程调用): 等 dsh 就绪后同步工作空间并加载 WebUI。
     * 幂等: 每个项目只预热一次; 该项目的面板已存在 / 设置未开启 / JCEF 不可用时跳过。
     */
    fun warmupForProject(project: Project) {
        val settings = DshSettingsState.getInstance()
        if (settings.startMode != DshSettingsState.START_MODE_IDE) return
        if (project.isDisposed) return
        // 面板已存在 (如工具窗口随项目自动恢复): 页面由面板自己管理, 不重复预热
        if (DshToolWindowRegistry.hasPanel(project)) return
        // JCEF 可用性必须经 DshJcefSupport 反射探测: 直接调用 JBCefApp 在未提供 JCEF 的
        // IDE (2026.2 起 JCEF 为独立模块) 上会抛 NoClassDefFoundError, 中断插件启动活动
        if (!DshJcefSupport.isAvailable) return // JCEF 不可用: 交由面板的回退 UI 处理
        val slot = synchronized(lock) {
            pruneDisposedLocked()
            if (slots.containsKey(project)) return // 该项目已发起过预热
            Slot().also { slots[project] = it }
        }
        Thread({ run(project, slot) }, "dsh-plugin-webui-warmup").apply { isDaemon = true }.start()
    }

    /**
     * 取走**本项目**的预热结果 (面板创建时优先调用), 取走后不再保留。
     * @return 预热结果; 本项目未预热 / 尚未创建出浏览器时返回 null
     */
    fun takeOwn(project: Project): Warmed? = synchronized(lock) {
        pruneDisposedLocked()
        val slot = slots[project] ?: return null
        val w = slot.warmed ?: return null
        slot.warmed = null
        slot.taken = true
        // 结果已被取走: 该项目不会再产出预热结果 (仍等着的面板按"预热已结束"立即回退)
        slot.finished = true
        w
    }

    /**
     * 借用**其他项目**的预热结果 (本项目没有自己的预热可用时调用)。
     * 预热的工作空间不是本项目也没关系: 面板接管后会重新同步并切回本项目。
     * 已加载完页面的结果优先 (接管即显示, 无需等页面加载)。
     */
    fun takeForeign(project: Project): Warmed? = synchronized(lock) {
        pruneDisposedLocked()
        val entry = slots.entries
            .filter { it.key !== project && it.value.warmed != null }
            .sortedByDescending { it.value.warmed?.pageLoaded == true }
            .firstOrNull() ?: return null
        val w = entry.value.warmed
        entry.value.warmed = null
        entry.value.taken = true
        entry.value.finished = true
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

    /** 本项目的预热是否进行中 (已发起、未结束; 供面板在预热未完成时给出提示) */
    fun warmupInFlight(project: Project): Boolean = synchronized(lock) {
        val slot = slots[project] ?: return false
        !slot.finished
    }

    /** 清理已关闭项目的预热结果 (浏览器无人接管, 否则会一直留到 IDE 退出) */
    private fun pruneDisposedLocked() {
        val it = slots.entries.iterator()
        while (it.hasNext()) {
            val entry = it.next()
            if (!entry.key.isDisposed) continue
            entry.value.warmed?.dispose()
            entry.value.warmed = null
            it.remove()
        }
    }

    /**
     * 从内嵌浏览器的 cookie 仓借 dsh 认证 cookie (dsh 不是本次插件启动的、插件没有启动令牌时,
     * 见 [DshWebAuth.adoptCookie]): 借到则后续同步/语言/事件流都能正常认证。
     * 只允许后台线程调用 (内部会等 CEF 线程上的 cookie 访问回调, 最多 2s)。
     */
    private fun adoptBrowserAuthCookie(port: Int) {
        try {
            if (DshWebAuth.cookieHeader(port) != null) return // 已有可用认证, 无需借
            if (DshJcefCookies.adoptAuthCookie(port)) {
                log(DshBundle.message("log.authCookieAdopted"))
            }
        } catch (t: Throwable) {
            LOG.warn("adopt the browser auth cookie failed", t)
        }
    }

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

    private fun run(project: Project, slot: Slot) {
        val settings = DshSettingsState.getInstance()
        val port = settings.currentPort()
        val label = project.name
        try {
            log(DshBundle.message("log.warmup.start", label))
            // 1) 等 dsh 就绪 (启动流程可能仍在进行): 与 DshServer.waitForReady 同款端口轮询
            val readyDeadline = System.currentTimeMillis() + 90_000
            while (System.currentTimeMillis() < readyDeadline) {
                if (DshServer.state == DshServer.State.RUNNING || WslSupport.isPortOpen(port)) break
                if (project.isDisposed) return
                try {
                    Thread.sleep(700)
                } catch (_: InterruptedException) {
                    return
                }
            }
            if (project.isDisposed) return
            if (DshServer.state != DshServer.State.RUNNING && !WslSupport.isPortOpen(port)) {
                log(DshBundle.message("log.warmup.giveUp", port.toString()))
                return
            }
            // 2) 串行执行 "同步本项目工作空间 -> 加载页面 -> 等页面完成首次选择" (见类注释)
            sequenceLock.lock()
            try {
                if (project.isDisposed) return
                // dsh 不是本次插件启动的 (上次 IDE 会话/外部启动) 时插件没有启动令牌:
                // 先尝试从内嵌浏览器的 cookie 仓借认证 cookie —— 借到则本次同步就能正常进行
                // (工作空间与语言都正确), 借不到只保持原样 (页面本身仍能用浏览器自带的 cookie)
                adoptBrowserAuthCookie(port)
                // 工作空间同步 + 语言偏好 (与面板 loadWebUi 同序), 让预热加载的页面直接落在本项目上
                val dshPath = project.basePath?.let { DshReference.dshPathFromString(it, settings.launchMode) }
                var sessionIds: List<String>? = null
                var landingSessionId: String? = null
                var workspaceId: String? = null
                var hasContentSession = true
                var syncedPath: String? = null
                if (dshPath != null) {
                    var result: DshWorkspaceApi.WorkspaceSyncResult? = null
                    val syncDeadline = System.currentTimeMillis() + 8000
                    while (result == null && System.currentTimeMillis() < syncDeadline) {
                        result = DshWorkspaceApi.ensureProjectWorkspace(
                            port, dshPath, restoreLastSession = settings.restoreLastSession,
                        )
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
                        landingSessionId = result.landingSessionId
                        workspaceId = result.workspaceId
                        hasContentSession = result.hasContentSession
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
                val warmed = createBrowserAndLoad(
                    project, slot, settings, port, syncedPath, sessionIds, landingSessionId, workspaceId,
                    hasContentSession, label,
                )
                // 4) 等这个页面加载完成并稳定后再放行下一个窗口的预热
                if (warmed != null) awaitPageReady(warmed, project)
            } finally {
                sequenceLock.unlock()
            }
        } catch (t: Throwable) {
            LOG.warn("webui warmup failed", t)
            log(DshBundle.message("log.warmup.failed", t.message ?: "null"))
        } finally {
            // 浏览器已创建 (或创建失败): 面板的等待循环可以据此判定"预热已结束"
            slot.finished = true
        }
    }

    /**
     * 在 EDT 上创建浏览器、发布预热结果并加载页面 (阻塞到浏览器创建完成)。
     * 页面加载完成后由 [startLoadRetry] 兜底重试。
     */
    private fun createBrowserAndLoad(
        project: Project,
        slot: Slot,
        settings: DshSettingsState,
        port: Int,
        syncedPath: String?,
        sessionIds: List<String>?,
        landingSessionId: String?,
        workspaceId: String?,
        hasContentSession: Boolean,
        label: String,
    ): Warmed? {
        val created = java.util.concurrent.atomic.AtomicReference<Warmed?>(null)
        val task = Runnable {
            try {
                val pageLoadedFlag = AtomicBoolean(false)
                val jbClient = JBCefApp.getInstance().createClient()
                val b = JBCefBrowserBuilder().setClient(jbClient).build()
                // JS 通道必须在浏览器实体创建前建立 (见 DshWebUiFileOpen.createChannel);
                // 拦截脚本由接管该页面的面板注入
                val fileOpenChannel = DshWebUiFileOpen.createChannel(b, project, ::log)
                // JS 通道建好后立刻强制创建浏览器实体: 窗口还没显示/没被激活时页面也要开始加载
                // (JCEF 默认懒创建, 否则要等用户把窗口切到前台才加载, 侧边栏会一直空白)
                DshJcefSupport.forceStart(b)
                val handler = object : CefLoadHandlerAdapter() {
                    override fun onLoadStart(
                        browser: CefBrowser,
                        frame: CefFrame,
                        transitionType: CefRequest.TransitionType,
                    ) {
                        if (frame.isMain) {
                            pageLoadedFlag.set(false)
                            DshWebUiInject.injectUiThemeLocaleOverride(browser, settings) { log(it) }
                            DshWebUiInject.pinSessionSelection(browser, landingSessionId, sessionIds) { log(it) }
                        }
                    }

                    override fun onLoadEnd(browser: CefBrowser, frame: CefFrame, httpStatusCode: Int) {
                        if (frame.isMain) {
                            pageLoadedFlag.set(true)
                            // 页面加载完成: 浏览器可能刚拿到/刷新了认证 cookie (无启动令牌时的
                            // 兜底认证来源), 异步借过来供面板同步使用 (CEF 回调里不能同步等待)
                            DshJcefCookies.adoptAuthCookieAsync(port) {
                                log(DshBundle.message("log.authCookieAdopted"))
                            }
                        }
                    }
                }
                val w = Warmed(
                    project, jbClient, b, handler, port, syncedPath, sessionIds, landingSessionId, workspaceId,
                    hasContentSession, pageLoadedFlag, fileOpenChannel,
                )
                jbClient.addLoadHandler(handler, b.cefBrowser)
                // 先把页面加载起来再发布预热结果: 面板一旦取走就由它接管 (可能不再发起加载),
                // 先加载可保证"接管到的页面一定在加载中/已加载", 不会停在空白页
                // 新版 dsh 的 WebUI 首页需带启动令牌访问 (换取认证 cookie)
                val url = DshServer.webTokenUrl(port) ?: DshServer.webUrl(port)
                log(DshBundle.message("log.warmup.loading", label, url))
                b.loadURL(url)
                synchronized(lock) {
                    // 期间面板已自行创建/已取走过/项目已关闭: 释放刚创建的浏览器
                    if (slot.taken || slot.warmed != null || project.isDisposed) {
                        w.dispose()
                        return@Runnable
                    }
                    slot.warmed = w
                }
                startLoadRetry(slot, b, port, pageLoadedFlag)
                created.set(w)
            } catch (t: Throwable) {
                LOG.warn("webui warmup failed", t)
                log(DshBundle.message("log.warmup.failed", t.message ?: "null"))
            }
        }
        if (SwingUtilities.isEventDispatchThread()) {
            task.run()
        } else {
            SwingUtilities.invokeAndWait(task)
        }
        return created.get()
    }

    /**
     * 等页面加载完成并稳定 ([PAGE_SETTLE_MS]) 再放行下一个窗口的预热。
     *
     * **面板提前接管 (taken) 也要等**: 共享 dsh 的"最近工作空间"只有一份, 本窗口页面还没完成
     * 工作空间/会话选择时, 别的窗口一旦同步工作空间, 本窗口的页面就会落到别的项目上
     * —— 这正是"多窗口同时打开时工作空间互相串"的根因之一。
     * 面板接管后预热的 load handler 已被摘除, 改用浏览器自身的加载状态判断。
     */
    private fun awaitPageReady(w: Warmed, project: Project) {
        val start = System.currentTimeMillis()
        val deadline = start + PAGE_LOAD_WAIT_MS
        var settledAt = 0L
        while (System.currentTimeMillis() < deadline && !project.isDisposed) {
            val now = System.currentTimeMillis()
            val loading = try {
                w.browser.cefBrowser.isLoading
            } catch (_: Throwable) {
                false
            }
            // loadURL 之后导航可能还没开始 (此时 isLoading 也是 false, 不可信): 过了宽限期才算数
            val navigationObserved = w.pageLoaded || now - start >= PAGE_NAVIGATION_GRACE_MS
            if (navigationObserved && !loading) {
                if (settledAt == 0L) settledAt = now
                if (now - settledAt >= PAGE_SETTLE_MS) return
            } else {
                settledAt = 0L
            }
            try {
                Thread.sleep(100)
            } catch (_: InterruptedException) {
                return
            }
        }
    }

    /**
     * 保险: 首次 loadURL 偶发未生效 (JCEF 初始化竞态) 时页面停在 about:blank,
     * onLoadEnd 永远不会置位加载完成标记 —— 8s 后仍未完成则重试一次。
     * 浏览器已被面板接管 (taken) 时加载职责归面板, 且预热 handler 已被摘除、
     * 加载标记不会再更新, 此时绝不能代为刷新 (否则页面会无端重载一次)。
     */
    private fun startLoadRetry(slot: Slot, b: JBCefBrowser, port: Int, pageLoadedFlag: AtomicBoolean) {
        val loadRetry = javax.swing.Timer(8000, null)
        loadRetry.addActionListener {
            loadRetry.stop()
            if (!slot.taken && !pageLoadedFlag.get()) {
                try {
                    val retryUrl = DshServer.webTokenUrl(port) ?: DshServer.webUrl(port)
                    b.loadURL(retryUrl)
                } catch (_: Throwable) {
                }
            }
        }
        loadRetry.isRepeats = false
        loadRetry.start()
    }
}
