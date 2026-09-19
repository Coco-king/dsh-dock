package cn.codecrab.plugins.dsh.util

/**
 * 跨版本探测内嵌浏览器 (JCEF) 是否可用的桥接:
 *
 * - 2026.2 起 JCEF 从平台核心拆分为独立模块 (依赖别名 `com.intellij.modules.jcef`,
 *   见 plugin.xml 的可选依赖)。未声明该依赖的插件直接调用 `JBCefApp.isSupported()`
 *   会抛 NoClassDefFoundError —— 从启动活动调用时会被平台记为插件启动异常;
 * - 该依赖别名自 2025.3.1 起才提供, 而本插件最低支持 2024.2 —— 2024.2 至 2025.3.0 区间
 *   JCEF 仍随平台核心提供, 无需声明依赖; 此外 JCEF 也可能因环境原因不可用 (IDE 所用 JDK
 *   不含 JCEF 等)。
 *
 * 因此这里按类名反射探测: 类不存在 / 初始化失败一律按"不可用"处理, 由工具窗口的
 * 系统浏览器回退 UI 承接 (与面板创建浏览器时的 try/catch 兜底一致)。
 *
 * 注: 探活 ([isAvailable]) 全程不引用 JCEF 类; [forceStart] 直接引用 JBCefBrowser,
 * 只允许在"JCEF 已确认可用且已建出浏览器"的调用点使用。
 */
object DshJcefSupport {

    private val LOG = com.intellij.openapi.diagnostic.Logger.getInstance(DshJcefSupport::class.java)

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

    /**
     * 立刻把内嵌浏览器的原生实体建起来, 不等组件"可见/首次绘制"。
     *
     * 背景: JCEF 默认**懒创建**浏览器实体 —— 组件被加进可见窗口、第一次绘制时才真正创建,
     * 于是"窗口 restored 但还没被激活/还藏在别的窗口后面"时, loadURL 只是排队, 页面压根没开始
     * 加载: 用户看到插件侧边栏一直空白, 鼠标碰一下那个窗口(或悬浮任务栏预览)才"突然开始加载"
     * (实测日志里预热页面在 loadURL 之后 46 秒才等到 onLoadEnd)。
     *
     * 这里在创建浏览器后立刻 `createImmediately()` 强制建实体, 再通知 CEF"窗口可见"
     * (`setWindowVisibility(true)`), 让页面在后台就开始加载与运行 (预热的意义正在于此):
     * 用户之后打开/激活窗口时页面已经就绪, 只剩一次绘制。
     * 两端都包 try/catch: 旧版 JCEF 上没有这些方法时静默降级为原来的懒创建行为。
     */
    fun forceStart(browser: com.intellij.ui.jcef.JBCefBrowser) {
        try {
            browser.createImmediately()
        } catch (t: Throwable) {
            LOG.info("JCEF createImmediately unavailable: ${t.message}")
        }
        try {
            // CEF 的 wasHidden(false): 不通知的话隐藏页面会被节流 (定时器降到 1s 级、不渲染)
            browser.cefBrowser.setWindowVisibility(true)
        } catch (_: Throwable) {
        }
    }
}
