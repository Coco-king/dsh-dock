package cn.codecrab.plugins.dsh

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindowManager

/**
 * Tools 菜单中的入口: 打开 Dsh 工具窗口。
 */
class OpenDshToolWindowAction : AnAction() {

    /** update() 仅检查 e.project (轻量上下文读取, 无 VFS/PSI 访问), 声明 EDT */
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

    override fun actionPerformed(e: AnActionEvent) {
        val project: Project = e.project ?: return
        val toolWindow = ToolWindowManager.getInstance(project).getToolWindow(DshToolWindowFactory.TOOL_WINDOW_ID)
        if (toolWindow != null) {
            toolWindow.activate(null)
        }
    }

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = e.project != null
    }
}