package cn.codecrab.plugins.dsh.server

import cn.codecrab.plugins.dsh.DshBundle
import cn.codecrab.plugins.dsh.settings.DshSettingsState
import cn.codecrab.plugins.dsh.toolwindow.DshWebUiWarmup
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.notification.Notifications
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity

/**
 * 「自动启动时机 = IDEA 启动时」(设置为该模式时生效)。
 *
 * 注册为项目级后台活动 (plugin.xml extensions 的 backgroundPostStartupActivity):
 * 每个项目打开完成时在后台执行一次, 拉起**本窗口自己的** dsh (每个窗口一个实例/端口,
 * 见 [DshServer]) 并预热本窗口的内嵌浏览器页面 ([DshWebUiWarmup]);
 * 用户首次打开 Dsh Dock 工具窗口时 WebUI 已就绪, 显著缩短首次等待时间。
 *
 * - **每个项目窗口各自启动自己的 dsh**: 各窗口端口不同 (页面 origin 不同), 工作空间/会话/
 *   页面状态互不影响 —— 这是"每个窗口互相独立"的关键 (共用一个 dsh 时窗口之间会互相刷新、
 *   最后串到同一个页面);
 * - 每个项目窗口各自预热自己的内嵌浏览器 (一个 JCEF 浏览器只能挂在一个窗口里);
 * - 端口已有 dsh (上次会话遗留 / 用户手动启动) 时**不复用**: [DshServer.start] 会顺延到下一个空闲
 *   端口, 拉起本窗口自己的实例 (复用别人的实例等于共用页面状态, 窗口之间又会互相影响);
 * - 生命周期: 关闭项目窗口时该窗口的 dsh 随之停止 ([DshServer.dispose]), IDE 退出时由
 *   [DshServers.stopAll] 统一清理。
 *
 * 说明: 平台已把 ProjectManagerListener.projectOpened 标记为废弃并计划移除 (官方推荐改用
 * ProjectActivity 后台活动), 本项目最低支持 2024.2, 故直接使用官方推荐的后台活动 API。
 */
class DshIdeStartupActivity : ProjectActivity {

    override suspend fun execute(project: Project) {
        val settings = DshSettingsState.getInstance()
        if (settings.startMode != DshSettingsState.START_MODE_IDE) return
        // 活动在后台执行, 项目可能已被关闭
        if (project.isDisposed) return
        // 每个项目窗口都后台预热自己的内嵌浏览器页面 (多窗口下各窗口首次打开都要快)
        DshWebUiWarmup.warmupForProject(project)
        val server = DshServer.getInstance(project)
        if (server.state != DshServer.State.IDLE) return // 本窗口已在启动/运行
        // 后台线程启动, 不阻塞项目打开; 启动全流程 (探测/拉起/端口轮询) 均在后台完成
        Thread({ autoStart(project, server) }, "dsh-plugin-ide-startup").apply { isDaemon = true }.start()
    }

    /** 在后台启动本窗口的 dsh, 启动失败时弹通知提示原因 (与面板内启动失败的提示保持一致) */
    private fun autoStart(project: Project, server: DshServer) {
        var missingDsh = false
        var nodeMissing = false
        var wslError = false
        val requested = server.start { line ->
            LOG.info("[ide-startup] " + line)
            // 与面板 (DshToolWindowPanel.appendLog) 相同的日志标记识别, 用于失败通知分类
            // (中英两种标记都匹配: 生成脚本里已按 IDE 语言输出对应语言的消息)
            if (line.contains("找不到 dsh") || line.contains("dsh not found")) {
                missingDsh = true
            }
            if (line.contains("未检测到 Node.js") || line.contains("no Node.js environment")) {
                nodeMissing = true
            }
            if (line.contains("Wsl/") || line.contains("E_UNEXPECTED") ||
                line.contains("0x80040326") || line.contains("LxssManager")
            ) {
                wslError = true
            }
        }
        // 未真正发起启动 (端口无效等): DshServer 已记日志, 不弹通知
        if (!requested) return
        // 等待启动结果 (上限覆盖 120s 端口就绪超时); 期间用户主动停止则视为取消, 不提示
        val deadline = System.currentTimeMillis() + 150_000
        while (System.currentTimeMillis() < deadline) {
            when (server.state) {
                DshServer.State.STARTING -> Thread.sleep(500)
                DshServer.State.STOPPING -> return
                else -> break
            }
        }
        if (server.state == DshServer.State.IDLE) {
            notifyStartFailed(project, server, missingDsh, nodeMissing, wslError)
        }
    }

    /** 启动失败时弹系统通知: 区分「无 Node.js 环境」/「未安装 dsh」/「WSL 子系统错误」/其他原因 */
    private fun notifyStartFailed(
        project: Project,
        server: DshServer,
        missingDsh: Boolean,
        nodeMissing: Boolean,
        wslError: Boolean,
    ) {
        try {
            if (project.isDisposed) return
            val settings = DshSettingsState.getInstance()
            val group = NotificationGroupManager.getInstance().getNotificationGroup("Dsh")
            val content = when {
                nodeMissing -> if (settings.launchMode == DshSettingsState.MODE_WSL) {
                    DshBundle.message("notify.fail.nodeMissing.wsl")
                } else {
                    DshBundle.message("notify.fail.nodeMissing.windows")
                }
                missingDsh -> if (settings.launchMode == DshSettingsState.MODE_WSL) {
                    DshBundle.message("notify.fail.dshMissing.wsl")
                } else {
                    DshBundle.message("notify.fail.dshMissing.windows")
                }
                wslError -> DshBundle.message("notify.fail.wslError")
                else -> if (settings.launchMode == DshSettingsState.MODE_WSL) {
                    DshBundle.message("notify.fail.generic.wsl", server.port.toString())
                } else {
                    DshBundle.message("notify.fail.generic.windows", server.port.toString())
                }
            }
            Notifications.Bus.notify(
                group.createNotification(DshBundle.message("notify.fail.title"), content, NotificationType.ERROR),
                project
            )
        } catch (t: Throwable) {
            LOG.warn("notify ide-startup failure failed", t)
        }
    }

    companion object {
        private val LOG = Logger.getInstance(DshIdeStartupActivity::class.java)
    }
}
