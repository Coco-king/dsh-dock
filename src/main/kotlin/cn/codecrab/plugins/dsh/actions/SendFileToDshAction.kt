package cn.codecrab.plugins.dsh.actions

import cn.codecrab.plugins.dsh.DshBundle
import cn.codecrab.plugins.dsh.reference.DshReference
import cn.codecrab.plugins.dsh.settings.DshSettingsState
import cn.codecrab.plugins.dsh.toolwindow.DshToolWindowFactory
import cn.codecrab.plugins.dsh.toolwindow.DshToolWindowRegistry
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.project.DumbAware

/**
 * 项目视图右键菜单: 把选中文件/目录的路径引用发送到 Dsh 输入框。
 *
 * 生成 `@<path>`, 例如 `@/mnt/c/Workspace/.../gradlew` (WSL 模式)
 * 或 `@C:/Workspace/.../gradlew` (Windows 模式)。
 */
class SendFileToDshAction : AnAction(), DumbAware {

    /** update() 只读取 VFS / 项目数据 (VIRTUAL_FILE), 不碰 Swing 组件, 声明 BGT */
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        // 文案在代码里用惰性资源指针设置: 平台对 plugin.xml 属性的 bundle 解析在个别
        // 环境不生效 (会显示原始 key), 这里直接按当前 IDE 语言解析, 每次渲染自动跟随
        e.presentation.setText(DshBundle.messagePointer("cn.codecrab.plugins.dsh.SendFileToDshAction.action.text"))
        e.presentation.setDescription(DshBundle.messagePointer("cn.codecrab.plugins.dsh.SendFileToDshAction.action.description"))
        val file = e.getData(CommonDataKeys.VIRTUAL_FILE)
        e.presentation.isEnabledAndVisible = e.project != null && file != null
        e.presentation.icon = ICON
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val file = e.getData(CommonDataKeys.VIRTUAL_FILE) ?: return
        val mode = DshSettingsState.getInstance().launchMode
        val ref = DshReference.fileReference(file, mode)
        DshToolWindowRegistry.send(project, ref)
    }

    companion object {
        /** 主题自适应的鲸鱼菜单图标 (16x16, 暗色主题自动用白色版) */
        private val ICON: javax.swing.Icon by lazy { DshToolWindowFactory.menuWhaleIcon() }
    }
}
