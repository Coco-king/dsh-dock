package cn.codecrab.plugins.dsh

import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindowManager
import java.util.concurrent.ConcurrentHashMap

/**
 * 工具窗口面板注册表: 右键菜单动作通过它把 `@路径` 引用送到当前项目的 Dsh 输入框。
 *
 * 工具窗口内容是按需创建的 (第一次打开工具窗口时才创建面板), 所以:
 *  - 面板已存在 -> 直接注入
 *  - 面板尚未创建 -> 先暂存引用, 同时激活工具窗口; 面板创建并注册后自动补发
 */
object DshToolWindowRegistry {

    private val panels = ConcurrentHashMap<Project, DshToolWindowPanel>()
    private val pending = ConcurrentHashMap<Project, String>()

    /** 面板创建时注册; 若有暂存引用则立即补发 */
    fun register(project: Project, panel: DshToolWindowPanel) {
        panels[project] = panel
        pending.remove(project)?.let { panel.injectReference(it) }
    }

    /** 面板销毁时注销 (工具窗口关闭/项目关闭) */
    fun unregister(project: Project) {
        panels.remove(project)
    }

    /** 发送引用到当前项目的 Dsh 输入框, 并激活工具窗口 */
    fun send(project: Project, reference: String) {
        if (project.isDisposed) return
        val panel = panels[project]
        if (panel != null) {
            panel.injectReference(reference)
        } else {
            pending[project] = reference
        }
        try {
            ToolWindowManager.getInstance(project)
                .getToolWindow(DshToolWindowFactory.TOOL_WINDOW_ID)
                ?.activate(null, true)
        } catch (_: Throwable) {
            // 工具窗口激活失败不影响后续 (面板存在时仍可注入)
        }
    }
}
