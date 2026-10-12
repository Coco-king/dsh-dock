package cn.codecrab.plugins.dsh.server

import com.intellij.openapi.diagnostic.Logger
import java.net.HttpURLConnection
import java.net.URI
import java.util.concurrent.ConcurrentHashMap

/**
 * dsh 浏览器会话认证 (dsh 0.1.2-rc.1 起)。
 *
 * 背景: 新版 dsh 的 WebUI 带进程级启动令牌认证 —— 启动时打印
 * `dsh web: http://127.0.0.1:<port>/?token=<token>`; 首次携带令牌访问首页会
 * 303 重定向到 `/` 并下发一个**按 authority (host:port) 绑定**的 HttpOnly 签名 cookie
 * (`dsh-auth-<hash(authority)>`, payload 里也校验 authority), 之后所有 /api 请求
 * (包括插件的工作空间同步、语言偏好、事件流) 都必须带这个 cookie, 否则一律 401
 * —— 插件之前"裸调 /api"在新版本上不再可行。
 *
 * **每个窗口一个 dsh 实例**: 令牌按**端口**分别记录 (tokens: port -> token),
 * cookie 仓也按 authority (`localhost:<port>`) 分开 —— 各窗口互不干扰, 一个窗口重启
 * 只会作废它自己的令牌与 cookie ([reset])。
 *
 * 本对象统一处理该认证链路:
 *  1. [captureFromLogOutput] — 各实例转发自己的 dsh 进程输出时调用, 解析出该端口的启动令牌;
 *  2. [cookieHeader] — 调用 /api 前取认证 cookie, 没有则先用令牌换取;
 *  3. [onUnauthorized] — /api 返回 401 时作废缓存, 下次调用自动重换。
 *
 * authority 说明 (重要): cookie 按 authority 绑定, `localhost:<port>` 与 `127.0.0.1:<port>`
 * 的 cookie 互不通用。内嵌浏览器加载的页面一直是 `http://localhost:<port>/`, 因此插件的
 * /api、事件流也必须用 [authority] 返回的 `localhost:<port>`, 才可能复用浏览器里的 cookie。
 *
 * 兼容性: 认证 cookie 由 dsh 持久化签名密钥签发 (跨进程重启有效), 插件内缓存复用即可;
 * 旧版 dsh (无认证) 换取失败时返回 null, 调用方按"无 cookie"原样请求, 行为不变。
 */
object DshWebAuth {

    private val LOG = Logger.getInstance(DshWebAuth::class.java)

    /** dsh 认证 cookie 名前缀 (`dsh-auth-<base64url(sha256(authority))>`) */
    const val COOKIE_PREFIX = "dsh-auth-"

    /**
     * 等启动令牌的默认上限。端口就绪与令牌随进程输出到达之间通常只差几百毫秒,
     * 冷启动/被杀软扫描时可能更久 —— 加载页面前必须等到令牌, 否则页面会停在认证页
     * (用户得手动刷新一次才能用)。
     */
    const val TOKEN_WAIT_MS = 8000L

    /** dsh 输出中的 WebUI 地址行: `dsh web: http://127.0.0.1:3080/?token=xxx` (token 为 base64url) */
    private val TOKEN_URL_PATTERN = Regex("(?i)https?://\\S+\\?[^\\s\"']*token=([A-Za-z0-9_-]+)")

    /** 各端口的启动令牌: key = port (每个 dsh 进程启动都会重新生成自己的令牌) */
    private val tokens = ConcurrentHashMap<Int, String>()

    /** 已换取的认证 cookie: key = authority (`localhost:<port>`), value = `name=value` */
    private val cookieByHost = ConcurrentHashMap<String, String>()


    /**
     * /api、事件流与 WebUI 页面统一使用的 host authority。
     *
     * 统一用 `localhost:<port>` 而不是 `127.0.0.1:<port>`: dsh 的认证 cookie 按 authority 绑定,
     * 内嵌浏览器加载的页面一直是 localhost —— 页面、/api 与事件流走同一个 authority,
     * 认证来源一致, 排查问题时也只有一个地址形态。
     */
    fun authority(port: Int): String = "localhost:" + port

    /** 从某个端口对应 dsh 进程的输出行解析启动令牌 (只取该端口第一次出现的令牌) */
    fun captureFromLogOutput(port: Int, line: String) {
        if (tokens.containsKey(port)) return
        val t = TOKEN_URL_PATTERN.find(line)?.groupValues?.get(1) ?: return
        if (tokens.putIfAbsent(port, t) == null) {
            LOG.info("captured dsh web launch token for port " + port)
        }
    }

    /** 指定端口的启动令牌 (该进程的输出尚未到达时为 null) */
    fun token(port: Int): String? = tokens[port]

    /** dsh 侧 WebUI 地址 (带启动令牌, 供浏览器加载完成认证); 该端口令牌未捕获时返回 null */
    fun webTokenUrl(port: Int): String? = token(port)?.let { "http://" + authority(port) + "/?token=" + it }

    /**
     * 等待某端口的启动令牌出现 (dsh 端口就绪时地址行可能还没输出, 通常晚几百毫秒), 最多等 [timeoutMs]。
     * @return true 表示已取得令牌
     */
    fun awaitToken(port: Int, timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (tokens.containsKey(port)) return true
            try {
                Thread.sleep(100)
            } catch (_: InterruptedException) {
                return false
            }
        }
        return tokens.containsKey(port)
    }

    /**
     * 取该端口 authority 的认证 cookie (不存在则先用令牌换取并缓存)。
     * 无令牌且浏览器 cookie 仓也没有可用 cookie 时返回 null, 调用方按无认证请求处理。
     */
    fun cookieHeader(port: Int): String? {
        val host = authority(port)
        cookieByHost[host]?.let { return it }
        return ensureCookie(port, host)
    }

    /** 确保指定端口的认证 cookie 可用 (仅用该端口的启动令牌换取); 失败 (含无令牌) 返回 null 且不破坏已有缓存 */
    private fun ensureCookie(port: Int, host: String): String? {
        cookieByHost[host]?.let { return it }
        val t = tokens[port] ?: return null
        val cookie = exchange(host, t) ?: return null
        cookieByHost[host] = cookie
        LOG.info("exchanged dsh web auth cookie for " + host)
        return cookie
    }

    /** /api 返回 401: 作废该端口的 cookie 缓存 (下次调用按启动令牌自动重换) */
    fun onUnauthorized(port: Int) {
        cookieByHost.remove(authority(port))
    }

    /**
     * 某个端口的 dsh 停止时清空该端口的令牌与 cookie 缓存 (新进程会打印新的启动令牌)。
     * 其他端口的窗口不受影响。
     */
    fun reset(port: Int) {
        tokens.remove(port)
        cookieByHost.remove(authority(port))
    }

    /** 用启动令牌访问首页换取认证 cookie (303 + Set-Cookie); 不跟随重定向以读取响应头 */
    private fun exchange(host: String, t: String): String? {
        var conn: HttpURLConnection? = null
        return try {
            val url = URI("http://" + host + "/?token=" + t).toURL()
            conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.connectTimeout = 2000
            conn.readTimeout = 4000
            conn.instanceFollowRedirects = false
            val code = conn.responseCode
            if (code !in 200..399) {
                LOG.warn("dsh web token exchange -> HTTP " + code)
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
