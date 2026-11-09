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
 * **一个 IDE 一个 dsh 进程, 每个窗口一个页面端口**: 令牌是进程级的 (tokens: port -> token),
 * 转发端口按关联表找到它背后的进程端口 ([linkTokenPort]); cookie 仓按 authority
 * (`<host>:<port>`) 分开 —— 各窗口的页面认证互不干扰, 进程重启只作废它相关的令牌与 cookie ([reset])。
 *
 * 本对象统一处理该认证链路:
 *  1. [captureFromLogOutput] — 转发共用 dsh 进程输出时调用, 解析出该进程端口的启动令牌;
 *  2. [cookieHeader] — 调用 /api 前取指定 authority 的认证 cookie, 没有则先用令牌换取;
 *  3. [onUnauthorized] — /api 返回 401 时作废**该 authority** 的 cookie, 下次调用自动重换。
 *
 * authority 说明 (重要): cookie 按 authority 绑定, 同一个端口下 `localhost:<port>` 与
 * `127.0.0.1:<port>` 的 cookie **互不通用** —— 换主机名必须同时取该 authority 自己的 cookie。
 * 内嵌浏览器加载的页面一直是 `http://localhost:<port>/`, 因此页面、/api 与事件流默认都用
 * [HOST_LOCALHOST]; 只有该主机名在本机解析/连接异常时才由调用方退到 [HOST_LOOPBACK_IP]。
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

    /** 默认 authority 主机名: 与内嵌浏览器加载的页面一致 (`localhost:<port>`) */
    const val HOST_LOCALHOST = "localhost"

    /** 备用 authority 主机名: 个别环境 `localhost` 解析/连接异常时退到它 */
    const val HOST_LOOPBACK_IP = "127.0.0.1"

    /** 日志里的启动令牌值 (dsh 自己会打印带令牌的地址行) */
    private val TOKEN_VALUE_PATTERN = Regex("(token=)[A-Za-z0-9_-]+")

    /** 把日志行里的启动令牌打码: 工具窗口日志可能被截图/贴出来, 令牌等同于该 dsh 的访问凭据 */
    fun maskToken(line: String): String = TOKEN_VALUE_PATTERN.replace(line, "$1***")

    /** dsh 输出中的 WebUI 地址行: `dsh web: http://127.0.0.1:3080/?token=xxx` (token 为 base64url) */
    private val TOKEN_URL_PATTERN = Regex("(?i)https?://\\S+\\?[^\\s\"']*token=([A-Za-z0-9_-]+)")

    /** 各端口的启动令牌: key = port (每个 dsh 进程启动都会重新生成自己的令牌) */
    private val tokens = ConcurrentHashMap<Int, String>()

    /**
     * 转发端口 -> 共用 dsh 进程端口。
     *
     * 多窗口架构下一个 IDE 只有一个 dsh 进程、但每个窗口有自己的转发端口 ([DshServer.port]):
     * 令牌是**进程级**的, 按进程端口记录; 页面/接口用的是转发端口, 取令牌时要按这张表找到
     * 它背后的进程端口。
     */
    private val tokenPorts = ConcurrentHashMap<Int, Int>()

    /** 已换取的认证 cookie: key = authority (`<host>:<port>`), value = `name=value` */
    private val cookieByAuthority = ConcurrentHashMap<String, String>()

    /**
     * /api、事件流与 WebUI 页面使用的 host authority (`<host>:<port>`)。
     *
     * 默认用 `localhost:<port>`: 与内嵌浏览器加载的页面同一 authority, 排查问题时也只有一个地址形态。
     * 主机名只影响 dsh 这一侧的 Host 围栏与 cookie 绑定 (两者都按 authority 判定), 不影响监听端口。
     */
    fun authority(port: Int, host: String = HOST_LOCALHOST): String = "$host:$port"

    /** 从某个端口对应 dsh 进程的输出行解析启动令牌 (只取该端口第一次出现的令牌) */
    fun captureFromLogOutput(port: Int, line: String) {
        if (tokens.containsKey(port)) return
        val t = TOKEN_URL_PATTERN.find(line)?.groupValues?.get(1) ?: return
        if (tokens.putIfAbsent(port, t) == null) {
            LOG.info("captured dsh web launch token for port " + port)
        }
    }

    /** 关联转发端口与进程端口 (转发启动时调用) */
    fun linkTokenPort(proxyPort: Int, processPort: Int) {
        tokenPorts[proxyPort] = processPort
    }

    /** 解除转发端口关联 (转发停止时调用) */
    fun unlinkTokenPort(proxyPort: Int) {
        tokenPorts.remove(proxyPort)
    }

    /** 指定端口的启动令牌 (传入转发端口时按关联的进程端口取; 输出尚未到达时为 null) */
    fun token(port: Int): String? = tokens[port] ?: tokenPorts[port]?.let { tokens[it] }

    /** dsh 侧 WebUI 地址 (带启动令牌, 供浏览器加载完成认证); 该端口令牌未捕获时返回 null */
    fun webTokenUrl(port: Int): String? = token(port)?.let { "http://" + authority(port) + "/?token=" + it }

    /**
     * 等待某端口的启动令牌出现 (dsh 端口就绪时地址行可能还没输出, 通常晚几百毫秒), 最多等 [timeoutMs]。
     * @return true 表示已取得令牌
     */
    fun awaitToken(port: Int, timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (token(port) != null) return true
            try {
                Thread.sleep(100)
            } catch (_: InterruptedException) {
                return false
            }
        }
        return token(port) != null
    }

    /**
     * 取指定 authority 的认证 cookie (不存在则用启动令牌换取并缓存)。
     * 令牌未到 (dsh 还在启动) 时返回 null, 调用方按无认证请求处理; 需要时要自己先等令牌 ([awaitToken])。
     */
    fun cookieHeader(port: Int, host: String = HOST_LOCALHOST): String? {
        val target = authority(port, host)
        cookieByAuthority[target]?.let { return it }
        return ensureCookie(port, target)
    }

    /** 确保指定 authority 的认证 cookie 可用 (仅用该端口的启动令牌换取); 失败 (含无令牌) 返回 null 且不破坏已有缓存 */
    private fun ensureCookie(port: Int, target: String): String? {
        cookieByAuthority[target]?.let { return it }
        val t = token(port) ?: return null
        val cookie = exchange(target, t) ?: return null
        cookieByAuthority[target] = cookie
        LOG.info("exchanged dsh web auth cookie for " + target)
        return cookie
    }

    /**
     * /api 返回 401: 只作废**该 authority** 的 cookie (下次调用按启动令牌自动重换)。
     * 不能连带作废别的主机名 —— `127.0.0.1:<port>` 上的 401 只说明那个 authority 没有 cookie,
     * 与 `localhost:<port>` 的 cookie 是否有效无关。
     */
    fun onUnauthorized(port: Int, host: String = HOST_LOCALHOST) {
        cookieByAuthority.remove(authority(port, host))
    }

    /**
     * 某个端口的 dsh 停止时清空该端口的令牌与所有 authority 的 cookie 缓存 (新进程会打印新的启动令牌)。
     * 其他端口的窗口不受影响。
     */
    fun reset(port: Int) {
        tokens.remove(port)
        // 关联到该进程的转发端口也一并清理: 新进程会打印新的启动令牌, 旧 cookie 不再可信
        val linked = tokenPorts.filterValues { it == port }.keys.toList()
        linked.forEach { tokenPorts.remove(it) }
        // 按端口号精确匹配 (authority 形如 `<host>:<port>`; 不能用 endsWith, "13085" 也会命中 "3085")
        for (p in linked + port) {
            cookieByAuthority.keys.filter { it.substringAfterLast(':') == p.toString() }
                .forEach { cookieByAuthority.remove(it) }
        }
    }

    /** 用启动令牌访问首页换取认证 cookie (303 + Set-Cookie); 不跟随重定向以读取响应头 */
    private fun exchange(authority: String, t: String): String? {
        var conn: HttpURLConnection? = null
        return try {
            val url = URI("http://" + authority + "/?token=" + t).toURL()
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
