package cn.codecrab.plugins.dsh.server

import com.intellij.openapi.diagnostic.Logger
import java.net.HttpURLConnection
import java.net.URI
import java.util.concurrent.ConcurrentHashMap

/**
 * dsh 浏览器会话认证 (dsh 0.1.2-alpha 起)。
 *
 * 背景: 新版 dsh 的 WebUI 带进程级启动令牌认证 —— `dsh web` 启动时打印
 * `dsh web: http://127.0.0.1:<port>/?token=<token>`; 首次携带令牌访问首页会
 * 303 重定向到 `/` 并下发一个**绑定 host 的 HttpOnly 签名 cookie** (dsh-auth-*),
 * 之后所有 /api 请求 (包括插件的工作空间同步、语言偏好、事件流) 都必须带
 * 这个 cookie, 否则一律 401 —— 插件之前"裸调 /api"在新版本上不再可行。
 *
 * 本对象统一处理该认证链路:
 *  1. [captureFromLogOutput] — DshServer 转发 dsh 进程输出时调用, 解析出启动令牌;
 *  2. [cookieHeader] — 调用 `/api` 前取 host 的认证 cookie, 没有则先用令牌换取;
 *  3. [onUnauthorized] — `/api` 返回 401 时作废缓存, 下次调用自动重换。
 *
 * 兼容性: 认证 cookie 由 dsh 持久化签名密钥签发 (跨进程重启有效), 插件内缓存复用即可;
 * 旧版 dsh (无认证) 换取失败时返回 null, 调用方按"无 cookie"原样请求, 行为不变。
 * 每次 dsh 停止时 [reset] 清空令牌与缓存 (新进程有新的启动令牌)。
 */
object DshWebAuth {

    private val LOG = Logger.getInstance(DshWebAuth::class.java)

    /** dsh 输出中的 WebUI 地址行: `dsh web: http://127.0.0.1:3080/?token=xxx` (token 为 base64url) */
    private val TOKEN_URL_PATTERN = Regex("(?i)https?://\\S+\\?[^\\s\"']*token=([A-Za-z0-9_-]+)")

    /** 每次 dsh 进程启动都会重新生成的启动令牌 */
    private val lock = Any()

    @Volatile
    private var token: String? = null

    /** 已换取的认证 cookie: key = host (`127.0.0.1:<port>`), value = `name=value` */
    private val cookieByHost = ConcurrentHashMap<String, String>()

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
    fun webTokenUrl(port: Int): String? = token()?.let { "http://localhost:$port/?token=$it" }

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
     * 取 `127.0.0.1:[port]` 的认证 cookie (不存在则先用令牌换取并缓存)。
     * 旧版 dsh (无认证) / 令牌无效 / 传输失败返回 null, 调用方按无认证请求处理。
     */
    fun cookieHeader(port: Int): String? {
        val host = "127.0.0.1:$port"
        cookieByHost[host]?.let { return it }
        return ensureCookie(host)
    }

    /** 确保指定 host 的认证 cookie 可用; 失败 (含无令牌) 返回 null 且不破坏已有缓存 */
    private fun ensureCookie(host: String): String? {
        cookieByHost[host]?.let { return it }
        val t = token ?: return null
        val cookie = exchange(host, t) ?: return null
        cookieByHost[host] = cookie
        LOG.info("exchanged dsh web auth cookie for $host")
        return cookie
    }

    /** `/api` 返回 401: 作废该 host 的 cookie 缓存 (下次调用自动重换) */
    fun onUnauthorized(port: Int) {
        cookieByHost.remove("127.0.0.1:$port")
    }

    /** dsh 停止时清空令牌与缓存 (新进程会打印新的启动令牌) */
    fun reset() {
        synchronized(lock) { token = null }
        cookieByHost.clear()
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
                .firstOrNull { it.startsWith("dsh-auth-") }
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