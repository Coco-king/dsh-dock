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
 * 每个项目打开完成时在后台执行一次, 本次 IDE 会话首次打开项目时立即在后台拉起 dsh ——
 * 让 dsh 提前完成启动与端口监听, 并同步预热内嵌浏览器页面 ([DshWebUiWarmup]);
 * 用户首次打开 Dsh Dock 工具窗口时 WebUI 已就绪, 显著缩短首次等待时间。
 *
 * - 每个 IDE 会话只自动启动一次 (仅首次打开项目时触发): 之后用户手动停止 dsh、
 *   再打开其他项目窗口不会被意外重新拉起;
 * - 多项目窗口: 首个打开的项目发起启动, 后续项目因已在启动/运行而自然跳过;
 * - 端口已有外部 dsh: [DshServer.start] 识别端口占用后直接按运行处理, 不重复启动;
 * - 进程生命周期与 IDE 一致: 退出清理复用 [DshServer] 的三层保障, IDE 退出时 dsh 一并停止。
 *
 * 说明: 平台已把 ProjectManagerListener.projectOpened 标记为废弃并计划移除 (官方推荐改用
 * ProjectActivity 后台活动), 本项目最低支持 2023.1, 故直接使用官方推荐的后台活动 API。
 */
class DshIdeStartupActivity : ProjectActivity {

    override suspend fun execute(project: Project) {
        val settings = DshSettingsState.getInstance()
        if (settings.startMode != DshSettingsState.START_MODE_IDE) return
        if (autoStartedThisSession) return
        // 活动在后台执行, 项目可能已被关闭 (避免把本次会话标记为已启动而漏掉后续项目的自动启动)
        if (project.isDisposed) return
        autoStartedThisSession = true
        if (DshServer.state != DshServer.State.IDLE) {
            // 已在启动/运行 (含外部启动的同端口 dsh): 无需启动 dsh, 但仍预热内嵌浏览器页面
            DshWebUiWarmup.warmupForProject(project)
            return
        }
        // 后台启动 dsh 的同时预热内嵌浏览器 (等 dsh 就绪后自动加载 WebUI)
        DshWebUiWarmup.warmupForProject(project)
        // 后台线程启动, 不阻塞项目打开; 启动全流程 (探测/拉起/端口轮询) 均在后台完成
        Thread({ autoStart(project) }, "dsh-plugin-ide-startup").apply { isDaemon = true }.start()
    }

    /** 在后台启动 dsh, 启动失败时弹通知提示原因 (与面板内启动失败的提示保持一致) */
    private fun autoStart(project: Project) {
        var missingDsh = false
        var nodeMissing = false
        var wslError = false
        val requested = DshServer.start(project.basePath) { line ->
            LOG.info("[ide-startup] $line")
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
            when (DshServer.state) {
                DshServer.State.STARTING -> Thread.sleep(500)
                DshServer.State.STOPPING -> return
                else -> break
            }
        }
        if (DshServer.state == DshServer.State.IDLE) {
            notifyStartFailed(project, missingDsh, nodeMissing, wslError)
        }
    }

    /** 启动失败时弹系统通知: 区分「无 Node.js 环境」/「未安装 dsh」/「WSL 子系统错误」/其他原因 */
    private fun notifyStartFailed(project: Project, missingDsh: Boolean, nodeMissing: Boolean, wslError: Boolean) {
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
            LOG.warn("notify ide-startup failure failed", t)
        }
    }

    companion object {
        private val LOG = Logger.getInstance(DshIdeStartupActivity::class.java)

        /** 本次 IDE 会话是否已处理过自动启动 (仅第一次打开项目时启动, 之后的打开不再触发);
         *  活动实例按项目创建, 会话级标记必须放在伴生对象共享 */
        @Volatile
        private var autoStartedThisSession: Boolean = false
    }
}