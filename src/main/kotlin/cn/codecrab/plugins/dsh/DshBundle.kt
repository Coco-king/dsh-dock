package cn.codecrab.plugins.dsh

import com.intellij.DynamicBundle
import org.jetbrains.annotations.NonNls
import org.jetbrains.annotations.PropertyKey

@NonNls
private const val BUNDLE = "messages.DshBundle"

/**
 * i18n 资源束入口 (对应 resources/messages/DshBundle*.properties)。
 *
 * 采用官方推荐写法: 持有 [DynamicBundle] 实例并委托取消息, 而不是继承 DynamicBundle ——
 * 供子类使用的 `DynamicBundle(String)` 构造器已被标记废弃并计划移除 (插件市场校验会报
 * "Deprecated constructor usage"), 而实例方式与其语义完全一致: 仍按 IDE 当前语言解析
 * 翻译文件, 且在所有受支持版本 (2023.1+) 上均可用。
 */
object DshBundle {

    private val bundle = DynamicBundle(DshBundle::class.java, BUNDLE)

    @JvmStatic
    fun message(@PropertyKey(resourceBundle = BUNDLE) key: String, vararg params: Any) =
        bundle.getMessage(key, *params)

    @Suppress("unused")
    @JvmStatic
    fun messagePointer(@PropertyKey(resourceBundle = BUNDLE) key: String, vararg params: Any) =
        bundle.getLazyMessage(key, *params)
}
