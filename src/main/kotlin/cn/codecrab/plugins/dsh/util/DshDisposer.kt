package cn.codecrab.plugins.dsh.util

import com.intellij.openapi.Disposable
import java.lang.reflect.Method

/**
 * 跨版本注册 Disposable 的桥接:
 * 2024.1 起 `Disposer` 从 `com.intellij.util.Disposer` 迁移到 `com.intellij.openapi.util.Disposer`,
 * 旧包名在 2024.2 已被删除 (恰为本插件的最低支持版本)。这里仍按 新包名 -> 旧包名 的顺序
 * 反射查找 `register(Disposable, Disposable)`, 两者都找不到时返回 false 交由调用方降级。
 */
object DshDisposer {

    private val registerMethod: Method? = findRegisterMethod()

    private fun findRegisterMethod(): Method? {
        val classNames = listOf(
            "com.intellij.openapi.util.Disposer",
            "com.intellij.util.Disposer",
        )
        for (name in classNames) {
            try {
                val cls = Class.forName(name)
                return cls.getMethod("register", Disposable::class.java, Disposable::class.java)
            } catch (_: Throwable) {
            }
        }
        return null
    }

    /** @return 注册是否成功 (失败时调用方应自行降级处理) */
    fun register(parent: Disposable, child: Disposable): Boolean {
        val m = registerMethod ?: return false
        return try {
            m.invoke(null, parent, child)
            true
        } catch (_: Throwable) {
            false
        }
    }
}