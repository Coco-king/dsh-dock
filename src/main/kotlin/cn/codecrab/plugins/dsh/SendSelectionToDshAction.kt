package cn.codecrab.plugins.dsh

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.DumbAware

/**
 * 编辑器右键菜单: 把选中代码的行范围引用发送到 Dsh 输入框。
 *
 * 生成 `@<path>#L<start>-<end>` (行号 1 起, 含端点; 单行时 `#L<start>`),
 * 例如选中 .gitignore 第 5-6 行 -> `@/mnt/c/.../.gitignore#L5-6` (WSL 模式)。
 * 路径按当前启动模式自动转换 (WSL: /mnt/...; Windows: C:/...)。
 */
class SendSelectionToDshAction : AnAction(), DumbAware {

    override fun update(e: AnActionEvent) {
        val editor = e.getData(CommonDataKeys.EDITOR)
        val file = e.getData(CommonDataKeys.VIRTUAL_FILE)
        val selection = editor?.selectionModel
        e.presentation.isEnabledAndVisible = e.project != null &&
            editor != null && file != null &&
            selection != null && selection.hasSelection()
        e.presentation.icon = ICON
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
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

        // 行号 1 起, 含端点
        val startLine = doc.getLineNumber(start) + 1
        // 选区结束于某行行首 (整行选择到下一行行首) 时, 该行不应计入
        val endLine0 = doc.getLineNumber(end)
        val endLine = if (doc.getLineStartOffset(endLine0) == end) endLine0 else endLine0 + 1

        val mode = DshSettingsState.getInstance().launchMode
        val ref = DshReference.selectionReference(file, mode, startLine, endLine)
        DshToolWindowRegistry.send(project, ref)
    }

    companion object {
        /** 主题自适应的鲸鱼菜单图标 (16x16, 暗色主题自动用白色版) */
        private val ICON: javax.swing.Icon by lazy { DshToolWindowFactory.menuWhaleIcon() }
    }
}
