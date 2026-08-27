package cn.codecrab.plugins.dsh

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.DumbAware

/**
 * 编辑器右键菜单: 把选中代码的行范围 AI 引用复制到剪贴板。
 *
 * 与「发送选中代码到 Dsh Dock」同一套引用格式: `@<path>#L<start>-<end>`
 * (行号 1 起, 含端点; 单行时 `#L<start>`), 供粘贴到任意支持 @引用 的输入框使用。
 */
class CopySelectionReferenceAction : AnAction(), DumbAware {

    /** update() 依赖 Editor / 选区 (EDT 数据), 声明 EDT (与 SendSelectionToDshAction 一致) */
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

    override fun update(e: AnActionEvent) {
        // 文案在代码里用惰性资源指针设置 (与其余动作一致), 每次渲染按当前 IDE 语言解析
        e.presentation.setText(DshBundle.messagePointer("cn.codecrab.plugins.dsh.CopySelectionReferenceAction.action.text"))
        e.presentation.setDescription(DshBundle.messagePointer("cn.codecrab.plugins.dsh.CopySelectionReferenceAction.action.description"))
        val editor = e.getData(CommonDataKeys.EDITOR)
        val file = editor?.let { FileDocumentManager.getInstance().getFile(it.document) }
        val selection = editor?.selectionModel
        e.presentation.isEnabledAndVisible = e.project != null &&
            editor != null && file != null &&
            selection != null && selection.hasSelection()
        e.presentation.icon = ICON
    }

    override fun actionPerformed(e: AnActionEvent) {
        val editor = e.getData(CommonDataKeys.EDITOR) ?: return
        val file = FileDocumentManager.getInstance().getFile(editor.document)
            ?: e.getData(CommonDataKeys.VIRTUAL_FILE)
            ?: return
        val sel = editor.selectionModel
        if (!sel.hasSelection()) return

        val doc = editor.document
        val start = sel.selectionStart
        val end = sel.selectionEnd
        if (end <= start) return

        // 行号 1 起, 含端点 (与 SendSelectionToDshAction 完全一致)
        val startLine = doc.getLineNumber(start) + 1
        // 选区结束于某行行首 (整行选择到下一行行首) 时, 该行不应计入
        val endLine0 = doc.getLineNumber(end)
        val endLine = if (doc.getLineStartOffset(endLine0) == end) endLine0 else endLine0 + 1

        val mode = DshSettingsState.getInstance().launchMode
        val ref = DshReference.selectionReference(file, mode, startLine, endLine)
        DshClipboard.copyReference(ref)
    }

    companion object {
        /** 主题自适应的鲸鱼菜单图标 (16x16, 暗色主题自动用白色版) */
        private val ICON: javax.swing.Icon by lazy { DshToolWindowFactory.menuWhaleIcon() }
    }
}