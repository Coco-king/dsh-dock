package cn.codecrab.plugins.dsh.server

import cn.codecrab.plugins.dsh.settings.DshSettingsState
import cn.codecrab.plugins.dsh.util.WslSupport
import com.intellij.ide.AppLifecycleListener
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import java.util.concurrent.ConcurrentHashMap

/**
 * 「每个 IDE 窗口一个 dsh 实例」的注册表与端口分配器 (应用级单例)。
 *
 * 为什么每个窗口要各起一个 dsh: dsh 的"当前工作空间/当前会话"只存在于浏览器
 * localStorage 的 dsh.sessions.current 里, 而 localStorage 按 **origin (scheme+host+port)**
 * 隔离 —— 多个窗口共用一个端口时, 所有窗口共用同一份选择: 任一窗口刷新/切换都会改写它,
 * 其他窗口的页面脚本发现"自己不在该在的会话上"就跟着重载, 最后全落到同一个页面。
 * 每个窗口各用一个端口 (各自一个 dsh 进程) 后, origin 不同, localStorage 与认证 cookie
 * 天然隔离, 窗口之间彻底互不影响。
 *
 * 本对象负责三件事:
 *  1. 登记各窗口的 [DshServer] 实例 (端口分配时互相避让);
 *  2. 分配端口: 从起始端口 (设置项) 开始只挑**空闲**端口, 已有 dsh 在监听的端口一律跳过;
 *  3. IDE 退出时统一清理所有由本插件启动的 dsh (关窗清理由 [DshServer.dispose] 负责)。
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

    /** 项目 -> 实例 (应用级注册表: 各窗口各自的实例都登记在这里, 供端口分配与退出清理使用) */
    private val instances = ConcurrentHashMap<Project, DshServer>()

    /**
     * 项目路径 -> 上次分配的端口 (仅本次 IDE 会话内记忆)。
     * 关窗再开时优先复用同一个端口: 页面 origin 稳定, 该窗口的历史页面状态 (localStorage)
     * 仍然属于同一个项目, 不会出现"这个窗口的 origin 换成了别的项目"的错位。
     */
    private val lastPortByPath = ConcurrentHashMap<String, Int>()

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
        // 2) JVM shutdown hook 兜底 (各实例自己的退出看门狗还会兜最后一层)
        Runtime.getRuntime().addShutdownHook(Thread({ stopAll() }, "dsh-plugin-shutdown"))
    }

    /** 实例创建时登记 */
    fun register(project: Project, server: DshServer) {
        instances[project] = server
    }

    /** 项目关闭时注销 */
    fun unregister(project: Project) {
        instances.remove(project)
    }

    /**
     * 为一个窗口实例分配一个**空闲**端口 (从设置里的起始端口开始, 逐个跳过已被占用的端口)。
     *
     * 关键取舍: **端口上已有 dsh 时也不复用**, 一律另找一个空闲端口自己启动 ——
     * 复用别人的 dsh 就等于和那个实例共用页面状态 (工作空间/会话/浏览器本地存储),
     * 正是"窗口之间互相串、跟着一起刷新"的来源; 每个窗口都用自己启动的实例, 才谈得上互相独立。
     *
     * 规则:
     *  1. 本项目本次会话用过的端口, 若当前空闲则继续用 (页面 origin 稳定, 历史页面状态不串项目);
     *  2. 起始端口空闲就用它;
     *  3. 否则往上找第一个空闲端口 (探测次数有上限, 见 [PORT_PROBE_LIMIT]);
     *  4. 都不可用返回 -1, 由调用方提示用户换起始端口或先停掉占用端口的进程。
     */
    fun allocatePort(self: DshServer): Int {
        val base = DshSettingsState.getInstance().currentPort()
        if (base !in 1..65535) return -1 // 起始端口配置无效: 由启动流程提示用户检查设置
        val taken = reservedPorts(self)
        val path = self.projectPath
        // 1) 复用本项目上次的端口 (空闲时): 关窗重开后 origin 不变
        val prev = path?.let { lastPortByPath[it] }
        if (prev != null && prev in 1..65535 && prev !in taken && !WslSupport.isPortOpen(prev)) {
            return prev
        }
        // 2) 起始端口 (空闲才用; 已被监听 = 已有 dsh 在用, 不复用, 继续往下找)
        if (base in 1..65535 && base !in taken && !WslSupport.isPortOpen(base)) {
            return remember(path, base)
        }
        val limit = minOf(base + PORT_SCAN_LIMIT, 65535)
        // 3) 往上找第一个空闲端口, 自己拉起一个独立的 dsh (只挑没人监听的端口)
        var probed = 0
        for (p in (base + 1)..limit) {
            if (p in taken) continue
            if (probed++ >= PORT_PROBE_LIMIT) break
            if (!WslSupport.isPortOpen(p)) return remember(path, p)
        }
        LOG.warn("no free port from " + base + " within " + PORT_SCAN_LIMIT + " ports")
        return -1
    }

    private fun remember(path: String?, port: Int): Int {
        if (path != null) lastPortByPath[path] = port
        return port
    }

    /** 其他活动窗口已占用的端口 */
    private fun reservedPorts(self: DshServer): Set<Int> =
        instances.values.asSequence()
            .filter { it !== self }
            .map { it.port }
            .filter { it in 1..65535 }
            .toHashSet()

    /**
     * IDE 退出清理: 停止所有由本插件启动的 dsh 进程 (外部启动的同端口 dsh 不受影响)。
     * appClosing 与 JVM shutdown hook 都会调用; 各实例自己的独立看门狗是最后一层保障。
     */
    fun stopAll() {
        for (server in instances.values.toList()) {
            try {
                server.stopForExit()
            } catch (t: Throwable) {
                LOG.warn("stop dsh on exit failed", t)
            }
        }
    }
}
