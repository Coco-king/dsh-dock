package cn.codecrab.plugins.dsh.util

import cn.codecrab.plugins.dsh.server.DshWebAuth
import com.intellij.openapi.diagnostic.Logger
import org.cef.callback.CefCookieVisitor
import org.cef.misc.BoolRef
import org.cef.network.CefCookie
import org.cef.network.CefCookieManager
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * 从内嵌浏览器 (JCEF) 的 cookie 仓里"借"dsh 的认证 cookie, 供 [DshWebAuth] 在**没有启动令牌**时
 * 兜底认证 (dsh 由上次 IDE 会话或外部方式启动时, 插件拿不到启动令牌, /api 一律 401)。
 *
 * 背景: dsh 的认证 cookie 由浏览器在带令牌访问首页时拿到并保存在 JCEF 的 cookie 仓里
 * (默认持久化在浏览器 profile 中, 跨 IDE 重启仍在), 因此即使插件丢失了令牌, 只要浏览器
 * 之前认证过, 仓里就有可用 cookie。插件与页面使用同一个 authority (`localhost:<port>`,
 * 见 [DshWebAuth.authority]) 时, 这枚 cookie 可以直接用于 /api 请求。
 *
 * 线程模型 (重要): [CefCookieManager.visitUrlCookies] 的访问回调在 CEF 线程上执行 ——
 * 在 CEF 回调 (如 onLoadEnd) 里同步等待会死锁, 因此读取一律放在后台线程:
 *  - [adoptAuthCookie] 同步读取, **只允许后台线程调用** (最多阻塞 [READ_TIMEOUT_MS]);
 *  - [adoptAuthCookieAsync] 自行起后台线程, 可在任意线程 (含 CEF 回调) 调用。
 *
 * 限制: JCEF 不可用 (2026.2 起为独立模块) / CEF 尚未初始化 / cookie 仓里没有该 authority
 * 的 dsh cookie 时, 读取返回 null, 调用方按"无认证"继续 (与之前行为一致)。
 */
object DshJcefCookies {

    private val LOG = Logger.getInstance(DshJcefCookies::class.java)

    /** 单次读取的最长等待 (超时按"没读到"处理, 不阻塞启动流程) */
    private const val READ_TIMEOUT_MS = 2000L

    /**
     * 异步读取并采纳一枚 dsh 认证 cookie (可在任意线程调用)。
     * 已有可用认证 cookie 时直接跳过 (不打扰浏览器)。
     *
     * @param onAdopted 本次确实新采纳了一枚 cookie 时回调 (在后台线程执行)
     */
    fun adoptAuthCookieAsync(port: Int, onAdopted: (() -> Unit)? = null) {
        if (!DshJcefSupport.isAvailable) return
        if (DshWebAuth.hasCookie(port)) return
        Thread({
            adoptAuthCookie(port, onAdopted)
        }, "dsh-plugin-browser-cookie").apply { isDaemon = true }.start()
    }

    /**
     * 同步读取并采纳一枚 dsh 认证 cookie (**只允许后台线程调用**)。
     * @return true = 本次确实新采纳
     */
    fun adoptAuthCookie(port: Int, onAdopted: (() -> Unit)? = null): Boolean {
        if (!DshJcefSupport.isAvailable) return false
        if (DshWebAuth.hasCookie(port)) return false
        val cookie = readAuthCookie(DshWebAuth.authority(port)) ?: return false
        if (!DshWebAuth.adoptCookie(port, cookie)) return false
        onAdopted?.invoke()
        return true
    }

    /**
     * 读取指定 authority 的 dsh 认证 cookie (`name=value`)。
     * 必须在后台线程调用 (见类注释): 内部会等待 CEF 线程上的访问回调。
     */
    private fun readAuthCookie(authority: String): String? = try {
        val manager = CefCookieManager.getGlobalManager()
        if (manager == null) {
            LOG.info("JCEF cookie manager unavailable; cannot adopt the dsh auth cookie")
            null
        } else {
            val found = AtomicReference<String?>(null)
            val latch = CountDownLatch(1)
            val visitor = object : CefCookieVisitor {
                override fun visit(cookie: CefCookie?, count: Int, total: Int, delete: BoolRef?): Boolean {
                    if (cookie == null) { // 遍历结束 (CEF 以此收尾)
                        latch.countDown()
                        return false
                    }
                    val name = cookie.name
                    val value = cookie.value
                    if (name != null && value != null && name.startsWith(DshWebAuth.COOKIE_PREFIX)) {
                        found.set("$name=$value")
                        latch.countDown()
                        return false // 命中一枚即可 (同一 authority 只会有一枚)
                    }
                    if (total <= 0 || count + 1 >= total) latch.countDown()
                    return true
                }
            }
            // includeHttpOnly = true: dsh 的认证 cookie 是 HttpOnly, 不带上就拿不到
            manager.visitUrlCookies("http://$authority/", true, visitor)
            latch.await(READ_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            found.get()
        }
    } catch (t: Throwable) {
        LOG.warn("read the dsh auth cookie from the embedded browser failed", t)
        null
    }
}
