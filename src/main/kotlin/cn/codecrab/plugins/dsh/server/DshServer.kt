package cn.codecrab.plugins.dsh.server

import cn.codecrab.plugins.dsh.DshBundle
import cn.codecrab.plugins.dsh.settings.DshSettingsState
import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 某个 IDE 窗口的 dsh 门面 (项目级服务): 面板、预热与文件同步都通过它取端口与状态。
 *
 * 一轮多窗口架构调整后, 它**不再拥有进程**:
 *  - 进程是 IDE 内共用的一个 ([DshHost]) —— dsh 的工作空间注册表只允许一个写者, 多进程会互相
 *    覆盖, 会话因此掉进"未分组";
 *  - 端口是本窗口自己的**转发端口** ([port], 由 [DshPortRelay] 转到进程) —— 页面因此仍有自己的
 *    origin, 各窗口的"当前工作空间/会话"(浏览器本地存储) 互不影响。
 *
 * 对外行为尽量与旧版一致: [state] 镜像进程状态, [port] 仍是页面与接口用的端口,
 * [webUrl]/[webTokenUrl] 仍然返回本窗口端口对应的地址。
 */
@Service(Service.Level.PROJECT)
class DshServer(private val project: Project) : Disposable {

    private val LOG = Logger.getInstance(DshServer::class.java)

    /** 本窗口看到的 dsh 状态 (进程级状态; 一个窗口启动/停止, 所有窗口都跟着变) */
    @Volatile
    var state: DshState = DshState.IDLE
        private set

    /**
     * 本窗口的转发端口: 页面地址、接口调用与事件流都用它 (origin 由此决定)。
     * 端口在窗口创建时分配, 分配失败或绑定失败时会顺延 (见 [ensureRelay])。
     */
    @Volatile
    var port: Int = 0
        private set

    /**
     * 共用 dsh 进程的监听端口: 页面**不**用它 (页面用本窗口的 [port]), 只在状态栏/日志里展示,
     * 免得用户把进程端口与页面端口混为一谈。进程未启动时为 0。
     */
    val processPort: Int
        get() = DshHost.port

    /** 本项目根路径 (作为第一个启动进程的工作目录) */
    val projectPath: String?
        get() = project.basePath

    /** 本窗口的状态监听器 (面板注册) */
    private val stateListeners = CopyOnWriteArrayList<(DshState) -> Unit>()

    /** 本窗口的日志接收者: 共用进程的输出会广播到每个打开的窗口 */
    private val logSinks = CopyOnWriteArrayList<(String) -> Unit>()

    private val relayLock = Any()

    @Volatile
    private var relay: DshPortRelay? = null

    /** 进程状态回调: 先起/停本窗口的转发, 再通知面板 (面板收到 RUNNING 会立刻加载页面) */
    private val hostStateListener: (DshState) -> Unit = { onHostState(it) }

    /** 进程日志回调: 转发给本窗口的日志接收者 */
    private val hostLogSink: (String) -> Unit = { line -> dispatchLog(line) }

    init {
        // 端口先分配好: 预热/面板/文件同步都直接读它
        val allocated = DshServers.allocateRelayPort(this)
        port = if (allocated in 1..65535) allocated else DshSettingsState.getInstance().currentPort()
        DshServers.register(project, this)
        // 注册后立即用当前状态回调一次: 别的窗口已经把共用 dsh 拉起来时, 本窗口直接接管
        DshHost.addStateListener(hostStateListener)
        DshHost.addLogSink(hostLogSink)
    }

    /**
     * 注册状态监听: 注册后立即用当前状态回调一次, 便于新面板初始化 UI。
     * 之后的状态变化只广播给本窗口的面板。
     */
    fun addStateListener(listener: (DshState) -> Unit) {
        stateListeners.add(listener)
        listener(state)
    }

    /** 注销状态监听 (面板销毁时调用) */
    fun removeStateListener(listener: (DshState) -> Unit) {
        stateListeners.remove(listener)
    }

    /** 注册日志接收者 (面板创建时调用): 共用 dsh 的输出会送到每个打开的窗口 */
    fun addLogSink(sink: (String) -> Unit) {
        logSinks.add(sink)
    }

    /** 注销日志接收者 (面板销毁时调用) */
    fun removeLogSink(sink: (String) -> Unit) {
        logSinks.remove(sink)
    }

    /**
     * 启动共用 dsh (已在启动/运行则不重复发起)。
     * @return true 表示真正发起了一次启动尝试 (面板可据此弹启动失败通知)
     */
    fun start(): Boolean {
        if (state != DshState.IDLE) return false
        return DshHost.start(projectPath)
    }

    /**
     * 停止 dsh。所有窗口共用同一个进程, 因此这是**整个 IDE** 的操作:
     * 先广播一行日志说明, 各窗口随后一起回到 IDLE。
     */
    fun stop() {
        DshHost.stop()
    }

    /** 状态变化入口 (来自 [DshHost]) */
    private fun onHostState(newState: DshState) {
        if (newState == DshState.RUNNING) {
            ensureRelay()
        } else {
            stopRelay()
        }
        state = newState
        for (listener in stateListeners) {
            try {
                listener(newState)
            } catch (t: Throwable) {
                LOG.warn("state listener failed", t)
            }
        }
    }

    /** 日志广播入口 (来自 [DshHost]) */
    private fun dispatchLog(line: String) {
        for (sink in logSinks) {
            try {
                sink.invoke(line)
            } catch (t: Throwable) {
                LOG.warn("log sink failed", t)
            }
        }
    }

    /**
     * 起本窗口的转发 (幂等): 绑定转发端口 -> 共用进程端口。
     * 端口被占用时换一个再试 (会更新 [port], 面板随后按新端口加载页面)。
     */
    private fun ensureRelay() {
        synchronized(relayLock) {
            if (relay?.isRunning == true) {
                DshWebAuth.linkTokenPort(port, DshHost.port)
                return
            }
            var attempts = 0
            while (attempts < RELAY_ATTEMPTS) {
                attempts++
                val candidate = DshPortRelay(port, DshHost.port)
                if (candidate.start()) {
                    relay = candidate
                    // 命令牌按进程端口记录, 页面/接口用的是本窗口的转发端口
                    DshWebAuth.linkTokenPort(port, DshHost.port)
                    return
                }
                val next = DshServers.reallocateRelayPort(this, port)
                if (next !in 1..65535) break
                port = next
            }
            relay = null
            DshHost.log(DshBundle.message("log.relayFailed", port.toString()))
        }
    }

    /** 停本窗口的转发 (幂等): 页面上的连接会断开 */
    private fun stopRelay() {
        synchronized(relayLock) {
            relay?.stop()
            relay = null
            DshWebAuth.unlinkTokenPort(port)
        }
    }

    /**
     * 窗口关闭: 注销监听与转发; 最后一个窗口走掉时停掉共用 dsh
     * (IDE 退出时还有 [DshServers.stopAll] 兜底)。
     */
    override fun dispose() {
        DshHost.removeStateListener(hostStateListener)
        DshHost.removeLogSink(hostLogSink)
        stopRelay()
        DshServers.unregister(project)
    }

    companion object {

        /** 转发端口绑定失败时的重试次数 (换端口再试) */
        private const val RELAY_ATTEMPTS = 3

        /** 本窗口的门面 (项目级服务) */
        fun getInstance(project: Project): DshServer = project.service()

        /** 页面的普通地址 (转发端口) */
        fun webUrl(port: Int): String = "http://localhost:" + port + "/"

        /** WebUI 地址 (带该进程的启动令牌, 用于通过新版 dsh 的浏览器认证); 令牌未捕获时返回 null */
        fun webTokenUrl(port: Int): String? = DshWebAuth.webTokenUrl(port)
    }
}
