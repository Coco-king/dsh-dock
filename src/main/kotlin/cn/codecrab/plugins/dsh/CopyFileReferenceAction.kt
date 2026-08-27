package cn.codecrab.plugins.dsh

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.project.DumbAware

/**
 * 项目视图右键菜单: 把选中文件/目录的路径 AI 引用 (`@<path>`) 复制到剪贴板。 
 */
class CopyFileReferenceAction : AnAction(), DumbAware {

    /** update() 只读取 VFS / 项目数据 (VIRTUAL_FILE), 声明 BGT (与 SendFileToDshAction 一致) */
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        // 文案在代码里用惰性资源指针设置 (与其余动作一致), 每次渲染按当前 IDE 语言解析
        e.presentation.setText(DshBundle.messagePointer("cn.codecrab.plugins.dsh.CopyFileReferenceAction.action.text"))
        e.presentation.setDescription(DshBundle.messagePointer("cn.codecrab.plugins.dsh.CopyFileReferenceAction.action.description"))
        val file = e.getData(CommonDataKeys.VIRTUAL_FILE)
        e.presentation.isEnabledAndVisible = e.project != null && file != null
        e.presentation.icon = ICON
    }

    override fun actionPerformed(e: AnActionEvent) {
        val file = e.getData(CommonDataKeys.VIRTUAL_FILE) ?: return
        val mode = DshSettingsState.getInstance().launchMode
        val ref = DshReference.fileReference(file, mode)
        DshClipboard.copyReference(ref)
    }

    companion object {
        /** 主题自适应的鲸鱼菜单图标 (16x16, 暗色主题自动用白色版) */
        private val ICON: javax.swing.Icon by lazy { DshToolWindowFactory.menuWhaleIcon() }
    }
}