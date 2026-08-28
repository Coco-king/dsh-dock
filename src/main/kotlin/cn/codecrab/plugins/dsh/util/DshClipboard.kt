package cn.codecrab.plugins.dsh.util

import com.intellij.openapi.diagnostic.Logger
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection

/**
 * 把 AI 引用 (`@路径` / `@路径#L行号`) 静默复制到系统剪贴板 (失败只记日志, 不影响使用)。
 */
object DshClipboard {

    private val LOG = Logger.getInstance(DshClipboard::class.java)

    /** @return 是否复制成功 */
    fun copyReference(reference: String): Boolean {
        if (reference.isBlank()) return false
        return try {
            Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(reference), null)
            true
        } catch (t: Throwable) {
            LOG.warn("copy reference to clipboard failed", t)
            false
        }
    }
}