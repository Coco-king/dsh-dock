package cn.codecrab.plugins.dsh.util

/**
 * 跨版本探测内嵌浏览器 (JCEF) 是否可用的桥接:
 *
 * - 2026.2 起 JCEF 从平台核心拆分为独立模块 (依赖别名 `com.intellij.modules.jcef`,
 *   见 plugin.xml 的可选依赖)。未声明该依赖的插件直接调用 `JBCefApp.isSupported()`
 *   会抛 NoClassDefFoundError —— 从启动活动调用时会被平台记为插件启动异常;
 * - 该依赖别名自 2025.3.1 起才提供, 本插件同时支持 2023.1 起的旧版本 (旧版本 JCEF
 *   随平台核心提供, 无需声明依赖); 此外 JCEF 也可能因环境原因不可用 (IDE 所用 JDK
 *   不含 JCEF 等)。
 *
 * 因此这里按类名反射探测: 类不存在 / 初始化失败一律按"不可用"处理, 由工具窗口的
 * 系统浏览器回退 UI 承接 (与面板创建浏览器时的 try/catch 兜底一致)。
 */
object DshJcefSupport {

    /** 平台 JCEF 入口类 (各版本包名一致) */
    private const val JCEF_APP_CLASS = "com.intellij.ui.jcef.JBCefApp"

    /** JCEF 在当前 IDE 中是否可用 (只探测一次: 同一进程内不会变化) */
    val isAvailable: Boolean by lazy {
        try {
            // 反射查找并调用 JBCefApp.isSupported(): 全程不直接引用 JCEF 类, 类缺失时不会中断调用方
            val app = Class.forName(JCEF_APP_CLASS, false, DshJcefSupport::class.java.classLoader)
            app.getMethod("isSupported").invoke(null) as? Boolean ?: false
        } catch (_: Throwable) {
            false
        }
    }
}
