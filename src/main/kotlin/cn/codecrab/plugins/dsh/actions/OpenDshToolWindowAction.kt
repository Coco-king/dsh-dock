package cn.codecrab.plugins.dsh.actions

import cn.codecrab.plugins.dsh.DshBundle
import cn.codecrab.plugins.dsh.toolwindow.DshToolWindowFactory
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
        // 文案在代码里用惰性资源指针设置: 平台对 plugin.xml 属性的 bundle 解析在个别
        // 环境不生效 (会显示原始 key), 这里直接按当前 IDE 语言解析, 每次渲染自动跟随
        e.presentation.setText(DshBundle.messagePointer("cn.codecrab.plugins.dsh.OpenDshToolWindowAction.action.text"))
        e.presentation.setDescription(DshBundle.messagePointer("cn.codecrab.plugins.dsh.OpenDshToolWindowAction.action.description"))
        e.presentation.icon = ICON
        e.presentation.isEnabled = e.project != null
    }

    companion object {
        /** 主题自适应的鲸鱼菜单图标 (16x16, 暗色主题自动用白色版) */
        private val ICON: javax.swing.Icon by lazy { DshToolWindowFactory.menuWhaleIcon() }
    }
}