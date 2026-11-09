package cn.codecrab.plugins.dsh.server

import cn.codecrab.plugins.dsh.settings.DshSettingsState
import cn.codecrab.plugins.dsh.util.WslSupport
import cn.codecrab.plugins.dsh.workspace.DshWorkspaceApi
import com.intellij.ide.AppLifecycleListener
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import java.util.concurrent.ConcurrentHashMap

/**
 * 窗口门面 [DshServer] 的注册表 + 端口分配器 (应用级单例)。
 *
 * 多窗口架构 (见 [DshHost] 与 [DshPortRelay]): 一个 IDE 只跑一个 dsh 进程,
 * **进程端口**由 [allocateProcessPort] 分配 (避开所有窗口的转发端口),
 * **转发端口**由 [allocateRelayPort] 按项目分配 (页面 origin, 决定页面状态隔离)。
 *
 * 本对象负责三件事:
 *  1. 登记各窗口的门面实例 (端口分配时互相避让);
 *  2. 分配端口: 转发端口从设置里的起始端口开始挑空闲的, 进程端口避开它们;
 *  3. 最后一个窗口关闭 / IDE 退出时停掉共用 dsh。
 */
object DshServers {

    private val LOG = Logger.getInstance(DshServers::class.java)

    /** 端口顺延的扫描上限 (基础端口之后最多再找这么多个) */
    private const val PORT_SCAN_LIMIT = 50

    /**
     * 分配端口时最多探测几个候选端口是否已被监听。
     * 端口分配可能发生在 EDT 上 (第一次打开工具窗口时创建实例), 每次探测都要等 TCP 连接结果
     * (端口没人听时通常立刻拒绝, 但被防火墙丢弃时要等几百毫秒), 因此探测次数要有上限。
     * 超过上限就不再往后探测 (返回 -1), 由启动流程提示用户换起始端口或先停掉占用端口的进程。
     */
    private const val PORT_PROBE_LIMIT = 8

    /** 项目 -> 窗口门面 (应用级注册表: 各窗口的实例都登记在这里, 供端口分配与退出清理使用) */
    private val instances = ConcurrentHashMap<Project, DshServer>()

    /**
     * 项目路径 -> 页面端口 (本次 IDE 会话内记忆; 关窗再开时优先复用同一个端口)。
     */
    private val lastRelayPortByPath = ConcurrentHashMap<String, Int>()

    /**
     * 项目路径 -> 页面端口 (**跨 IDE 会话**持久化), 见 [pickRelayPort]。
     *
     * 为什么必须持久化: 页面端口就是页面的 origin, 而浏览器的"当前工作空间/会话"存在按 origin
     * 隔离的本地存储里。端口每次都重新分配时, 同一个 origin 会在不同项目之间轮转 —— 某个项目的
     * 窗口会先恢复出**别人**项目存下的会话, 看起来就是"窗口显示成别的项目的工作空间"。
     * 记住每个项目自己的端口后, 它每次打开都回到自己那份页面状态。
     */
    private var pagePortByPath: MutableMap<String, Int>? = null

    /** 持久化页面端口的存储键 (一个 JSON 对象, 见 [loadPagePorts]) */
    private const val PAGE_PORTS_KEY = "dsh.pagePorts"

    /** 页面端口记忆的容量上限 (超过就丢最早的, 避免路径越攒越多) */
    private const val PAGE_PORTS_LIMIT = 40

    /** 端口分配互斥 (多个窗口可能同时创建门面) */
    private val allocationLock = Any()

    /** 本次 IDE 会话用过的进程端口 (重启时优先复用, 少一次扫描) */
    @Volatile
    private var lastProcessPort = 0

    init {
        // 1) IDEA 退出流程早期回调 (服务仍可用); 异步清理, 不阻塞退出
        try {
            ApplicationManager.getApplication().messageBus.connect().subscribe(
                AppLifecycleListener.TOPIC,
                object : AppLifecycleListener {
                    override fun appClosing() {
                        stopAll()
                    }
                }
            )
        } catch (_: Throwable) {
        }
        // 2) JVM shutdown hook 兜底 (进程自己的退出看门狗还会兜最后一层)
        Runtime.getRuntime().addShutdownHook(Thread({ stopAll() }, "dsh-plugin-shutdown"))
    }

    /** 窗口门面创建时登记 */
    fun register(project: Project, server: DshServer) {
        instances[project] = server
    }

    /**
     * 窗口关闭: 注销门面; 最后一个窗口走掉时停掉共用 dsh
     * (否则关掉所有窗口后还会留一个 node 进程)。
     */
    fun unregister(project: Project) {
        instances.remove(project)
        if (!instances.isEmpty()) return
        // 最后一个窗口走了: 正在启动/已运行则走正常停止流程 (会有日志), 否则清掉残留的进程标识
        when (DshHost.state) {
            DshState.STARTING, DshState.RUNNING -> DshHost.stop()
            else -> DshHost.stopForExit()
        }
    }

    /** 当前各窗口的转发端口 (进程端口分配时避让) */
    fun relayPorts(): Set<Int> = instances.values.asSequence()
        .map { it.port }
        .filter { it in 1..65535 }
        .toHashSet()

    /**
     * 给一个窗口分配转发端口 (页面 origin)。
     *
     * 规则:
     *  1. 本项目本次会话用过的端口, 若当前空闲则继续用 (页面 origin 稳定);
     *  2. 起始端口空闲就用它;
     *  3. 否则往上找第一个空闲端口 (跳过其他窗口的转发端口与 dsh 进程端口);
     *  4. 都不可用返回 -1, 由调用方提示用户换起始端口或先停掉占用端口的进程。
     */
    fun allocateRelayPort(self: DshServer): Int = synchronized(allocationLock) { pickRelayPort(self, 0) }

    /** 重新分配转发端口 (绑定失败时换一个, [excluded] 是刚失败的端口) */
    fun reallocateRelayPort(self: DshServer, excluded: Int): Int =
        synchronized(allocationLock) { pickRelayPort(self, excluded) }

    private fun pickRelayPort(self: DshServer, excluded: Int): Int {
        val base = DshSettingsState.getInstance().currentPort()
        if (base !in 1..65535) return -1
        val taken = instances.values.asSequence()
            .filter { it !== self }
            .map { it.port }
            .filter { it in 1..65535 }
            .toMutableSet()
        // 进程端口也不能拿来当转发端口 (否则浏览器的请求会打到 dsh 自身而不是转发器)
        if (DshHost.port in 1..65535) taken.add(DshHost.port)
        if (excluded in 1..65535) taken.add(excluded)
        val path = self.projectPath
        // 1) 复用本项目记下的页面端口 (跨 IDE 会话): origin 稳定, 页面历史状态始终属于本项目
        val prev = path?.let { pagePortsLocked()[it] ?: lastRelayPortByPath[it] }
        if (prev != null && prev in 1..65535 && prev !in taken && !WslSupport.isPortOpen(prev)) {
            return rememberRelay(path, prev)
        }
        // 2) 起始端口
        if (base !in taken && !WslSupport.isPortOpen(base)) return rememberRelay(path, base)
        // 3) 往上找第一个空闲端口 (先跳过被监听/被占用的, 探测次数有上限)
        val limit = minOf(base + PORT_SCAN_LIMIT, 65535)
        var probed = 0
        for (p in (base + 1)..limit) {
            if (p in taken) continue
            if (probed++ >= PORT_PROBE_LIMIT) break
            if (!WslSupport.isPortOpen(p)) return rememberRelay(path, p)
        }
        LOG.warn("no free relay port from " + base + " within " + PORT_SCAN_LIMIT + " ports")
        return -1
    }

    private fun rememberRelay(path: String?, port: Int): Int {
        if (path != null) {
            lastRelayPortByPath[path] = port
            val ports = pagePortsLocked()
            if (ports[path] != port) {
                ports[path] = port
                persistPagePorts()
            }
        }
        return port
    }

    /** 页面端口记忆 (惰性加载; 调用方需持有 [allocationLock]) */
    private fun pagePortsLocked(): MutableMap<String, Int> {
        pagePortByPath?.let { return it }
        val out = LinkedHashMap<String, Int>()
        val raw = try {
            com.intellij.ide.util.PropertiesComponent.getInstance().getValue(PAGE_PORTS_KEY)
        } catch (_: Throwable) {
            null
        }
        if (!raw.isNullOrBlank()) {
            try {
                val obj = com.google.gson.JsonParser.parseString(raw).asJsonObject
                for ((key, value) in obj.entrySet()) {
                    val port = try {
                        value.asInt
                    } catch (_: Throwable) {
                        continue
                    }
                    if (port in 1..65535) out[key] = port
                }
            } catch (_: Throwable) {
            }
        }
        pagePortByPath = out
        return out
    }

    /** 落盘页面端口记忆 (调用方需持有 [allocationLock]) */
    private fun persistPagePorts() {
        val ports = pagePortByPath ?: return
        // 超出上限丢最早的 (LinkedHashMap 保持插入顺序)
        while (ports.size > PAGE_PORTS_LIMIT) {
            val first = ports.keys.firstOrNull() ?: break
            ports.remove(first)
        }
        val obj = com.google.gson.JsonObject()
        for ((key, value) in ports) obj.addProperty(key, value)
        try {
            com.intellij.ide.util.PropertiesComponent.getInstance().setValue(PAGE_PORTS_KEY, obj.toString())
        } catch (_: Throwable) {
        }
    }

    /**
     * 分配 dsh 进程自己的监听端口 (页面不直接用): 避开所有窗口的转发端口与已监听的端口。
     * @return 端口; 找不到返回 -1
     */
    fun allocateProcessPort(): Int = synchronized(allocationLock) {
        val base = DshSettingsState.getInstance().currentPort()
        if (base !in 1..65535) return -1
        // 避开所有窗口的页面端口, 也包括还没打开的项目的 (否则它们下次打开时 origin 会被顶掉)
        val taken = relayPorts().toMutableSet()
        pagePortsLocked().values.filter { it in 1..65535 }.forEach { taken.add(it) }
        val prev = lastProcessPort
        if (prev in 1..65535 && prev !in taken && !WslSupport.isPortOpen(prev)) return prev
        var probed = 0
        val limit = minOf(base + PORT_SCAN_LIMIT, 65535)
        for (p in base..limit) {
            if (p in taken) continue
            if (probed++ >= PORT_PROBE_LIMIT) break
            if (!WslSupport.isPortOpen(p)) {
                lastProcessPort = p
                return p
            }
        }
        LOG.warn("no free dsh process port from " + base + " within " + PORT_SCAN_LIMIT + " ports")
        -1
    }

    /**
     * 共用 dsh 重启后作废各窗口转发端口的 RPC 探测缓存。
     * 接口调用走的是转发端口 (见 [DshServer.port]), 进程重启不会换转发端口, 缓存必须显式清掉。
     */
    fun invalidateRelayCaches() {
        for (port in relayPorts()) DshWorkspaceApi.invalidateStyle(port)
    }

    /** IDE 退出清理: 停止由本插件启动的 dsh (外部启动的同端口 dsh 不受影响) */
    fun stopAll() {
        try {
            DshHost.stopForExit()
        } catch (t: Throwable) {
            LOG.warn("stop dsh on exit failed", t)
        }
    }
}
