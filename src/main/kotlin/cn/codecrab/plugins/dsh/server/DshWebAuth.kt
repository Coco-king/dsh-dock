package cn.codecrab.plugins.dsh.server

import com.intellij.openapi.diagnostic.Logger
import java.net.HttpURLConnection
import java.net.URI
import java.util.concurrent.ConcurrentHashMap

/**
 * dsh 浏览器会话认证 (dsh 0.1.2-rc.1 起)。
 *
 * 背景: 新版 dsh 的 WebUI 带进程级启动令牌认证 —— `dsh web` 启动时打印
 * `dsh web: http://127.0.0.1:<port>/?token=<token>`; 首次携带令牌访问首页会
 * 303 重定向到 `/` 并下发一个**按 authority (host:port) 绑定**的 HttpOnly 签名 cookie
 * (`dsh-auth-<hash(authority)>`, payload 里也校验 authority), 之后所有 /api 请求
 * (包括插件的工作空间同步、语言偏好、事件流) 都必须带这个 cookie, 否则一律 401
 * —— 插件之前"裸调 /api"在新版本上不再可行。
 *
 * 本对象统一处理该认证链路:
 *  1. [captureFromLogOutput] — DshServer 转发 dsh 进程输出时调用, 解析出启动令牌;
 *  2. [cookieHeader] — 调用 `/api` 前取认证 cookie, 没有则先用令牌换取;
 *  3. [adoptCookie] — **兜底来源**: dsh 不是本插件启动的 (上次 IDE 会话 / 外部启动) 时
 *     插件没有启动令牌, 改为从内嵌浏览器的 cookie 仓借用浏览器已经拿到的 cookie
 *     (见 DshJcefCookies) —— 前提是插件与页面使用**同一个 authority** ([authority]);
 *  4. [onUnauthorized] — `/api` 返回 401 时作废缓存, 下次调用自动重换。
 *
 * authority 说明 (重要): cookie 按 authority 绑定, `localhost:<port>` 与 `127.0.0.1:<port>`
 * 的 cookie 互不通用。内嵌浏览器加载的页面一直是 `http://localhost:<port>/`, 因此插件的
 * /api、事件流也必须用 [authority] 返回的 `localhost:<port>`, 才可能复用浏览器里的 cookie。
 *
 * 兼容性: 认证 cookie 由 dsh 持久化签名密钥签发 (跨进程重启有效), 插件内缓存复用即可;
 * 旧版 dsh (无认证) 换取失败时返回 null, 调用方按"无 cookie"原样请求, 行为不变。
 * 每次 dsh 停止/新进程启动时 [reset] 清空令牌与缓存 (新进程有新的启动令牌)。
 */
object DshWebAuth {

    private val LOG = Logger.getInstance(DshWebAuth::class.java)

    /** dsh 认证 cookie 名前缀 (`dsh-auth-<base64url(sha256(authority))>`) */
    const val COOKIE_PREFIX = "dsh-auth-"

    /** dsh 输出中的 WebUI 地址行: `dsh web: http://127.0.0.1:3080/?token=xxx` (token 为 base64url) */
    private val TOKEN_URL_PATTERN = Regex("(?i)https?://\\S+\\?[^\\s\"']*token=([A-Za-z0-9_-]+)")

    /** 每次 dsh 进程启动都会重新生成的启动令牌 */
    private val lock = Any()

    @Volatile
    private var token: String? = null

    /** 已换取的认证 cookie: key = authority (`localhost:<port>`), value = `name=value` */
    private val cookieByHost = ConcurrentHashMap<String, String>()

    /** 被 401 拒绝过的 cookie 值: 不再从浏览器 cookie 仓重复采纳同一个失效值 */
    private val rejectedCookies = ConcurrentHashMap.newKeySet<String>()

    /**
     * /api、事件流与 WebUI 页面统一使用的 host authority。
     *
     * 必须是 `localhost:<port>` (而不是 `127.0.0.1:<port>`): dsh 的认证 cookie 按 authority
     * 绑定, 内嵌浏览器加载的页面用的是 localhost, 只有插件也用同一个 authority,
     * 才能复用浏览器 cookie 仓里的认证 cookie (见 DshJcefCookies)。
     */
    fun authority(port: Int): String = "localhost:$port"

    /** 从 dsh 进程输出行解析启动令牌 (只取第一次出现的令牌, 之后该进程内保持不变) */
    fun captureFromLogOutput(line: String) {
        if (token != null) return
        val t = TOKEN_URL_PATTERN.find(line)?.groupValues?.get(1) ?: return
        synchronized(lock) {
            if (token == null) {
                token = t
                LOG.info("captured dsh web launch token")
            }
        }
    }

    /** 当前启动令牌 (外部启动的 dsh / dsh 输出未到达时为 null) */
    fun token(): String? = token

    /** dsh 侧 WebUI 地址 (带启动令牌, 供浏览器加载完成认证); 令牌未捕获时返回 null */
    fun webTokenUrl(port: Int): String? = token()?.let { "http://${authority(port)}/?token=$it" }

    /**
     * 等待启动令牌出现 (dsh 端口就绪时地址行可能还没输出, 通常晚几百毫秒), 最多等 [timeoutMs]。
     * @return true 表示已取得令牌
     */
    fun awaitToken(timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (token != null) return true
            try {
                Thread.sleep(100)
            } catch (_: InterruptedException) {
                return false
            }
        }
        return token != null
    }

    /**
     * 取 [authority] 的认证 cookie (不存在则先用令牌换取并缓存)。
     * 无令牌且浏览器 cookie 仓也没有可用 cookie 时返回 null, 调用方按无认证请求处理。
     */
    fun cookieHeader(port: Int): String? {
        val host = authority(port)
        cookieByHost[host]?.let { return it }
        return ensureCookie(host)
    }

    /** 当前是否已有可用的认证 cookie (令牌换取或从浏览器 cookie 仓采纳) */
    fun hasCookie(port: Int): Boolean = cookieByHost.containsKey(authority(port))

    /**
     * 采纳一枚从内嵌浏览器 cookie 仓读到的认证 cookie (dsh 由上次会话/外部启动, 插件没有启动令牌)。
     * 只接受 dsh 认证 cookie; 已被 401 拒绝过的值不再采纳 (避免在失效 cookie 上反复重试)。
     *
     * @return true = 本次确实新采纳 (调用方可以据此补一次工作空间核对)
     */
    fun adoptCookie(port: Int, cookie: String): Boolean {
        if (cookie.isBlank() || !cookie.startsWith(COOKIE_PREFIX)) return false
        if (rejectedCookies.contains(cookie)) return false
        val host = authority(port)
        if (cookieByHost[host] == cookie) return false
        cookieByHost[host] = cookie
        LOG.info("adopted dsh web auth cookie from the embedded browser for $host")
        return true
    }

    /** 确保指定 host 的认证 cookie 可用 (仅用启动令牌换取); 失败 (含无令牌) 返回 null 且不破坏已有缓存 */
    private fun ensureCookie(host: String): String? {
        cookieByHost[host]?.let { return it }
        val t = token ?: return null
        val cookie = exchange(host, t) ?: return null
        cookieByHost[host] = cookie
        LOG.info("exchanged dsh web auth cookie for $host")
        return cookie
    }

    /** `/api` 返回 401: 作废该 host 的 cookie 缓存并记住该值已失效 (下次调用自动重换) */
    fun onUnauthorized(port: Int) {
        val host = authority(port)
        cookieByHost.remove(host)?.let { rejectedCookies.add(it) }
    }

    /** dsh 停止时清空令牌与缓存 (新进程会打印新的启动令牌) */
    fun reset() {
        synchronized(lock) { token = null }
        cookieByHost.clear()
        rejectedCookies.clear()
    }

    /** 用启动令牌访问首页换取认证 cookie (303 + Set-Cookie); 不跟随重定向以读取响应头 */
    private fun exchange(host: String, t: String): String? {
        var conn: HttpURLConnection? = null
        return try {
            val url = URI("http://$host/?token=$t").toURL()
            conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.connectTimeout = 2000
            conn.readTimeout = 4000
            conn.instanceFollowRedirects = false
            val code = conn.responseCode
            if (code !in 200..399) {
                LOG.warn("dsh web token exchange -> HTTP $code")
                return null
            }
            // 取 dsh-auth-* 开头的 Set-Cookie, 去掉属性只留 name=value
            conn.getHeaderFields()
                .filterKeys { it != null && it.equals("set-cookie", ignoreCase = true) }
                .values.flatten()
                .firstOrNull { it.startsWith(COOKIE_PREFIX) }
                ?.substringBefore(';')
                ?.takeIf { it.isNotBlank() }
        } catch (t: Throwable) {
            LOG.warn("dsh web token exchange failed", t)
            null
        } finally {
            try {
                conn?.disconnect()
            } catch (_: Throwable) {
            }
        }
    }
}
