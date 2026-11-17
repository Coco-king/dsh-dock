package cn.codecrab.plugins.dsh.toolwindow

import cn.codecrab.plugins.dsh.DshBundle
import com.intellij.openapi.diagnostic.Logger
import com.intellij.ui.jcef.JBCefClient
import org.cef.browser.CefBrowser
import org.cef.callback.CefMediaAccessCallback
import org.cef.handler.CefPermissionHandler
import java.net.URI

/**
 * 内嵌浏览器的麦克风授权 (dsh 语音输入 / 听写)。
 *
 * 背景: dsh 的听写由页面用 getUserMedia 采集麦克风, 再交给 dsh 转写。JCEF 没有系统浏览器那种
 * 站点授权弹窗, CEF 默认直接拒绝媒体设备请求 —— 页面只会收到 NotAllowedError, 听写按钮看起来
 * 点了没反应。这里在建立浏览器时挂一个权限处理器, 代替浏览器完成授权。
 *
 * 放行范围 (最小权限):
 *  - 只放行**与当前页面同源、且主机是本机回环地址**的请求: 内嵌浏览器只加载本窗口的 dsh 页面,
 *    页面里的外链若把浏览器导航到公网站点, 来源主机不再是回环地址, 仍然拒绝;
 *  - 只放行麦克风 (音频采集), 相机等视频采集一律拒绝 —— dsh 听写只需要麦克风。
 *
 * 注意: 必须在浏览器实体创建之后 (createImmediately 之后) 挂载, 与加载 handler 的时机一致。
 */
object DshWebUiMicrophone {

    private val LOG = Logger.getInstance(DshWebUiMicrophone::class.java)

    /** CEF 媒体权限位 (cef_media_access_callback.h: DEVICE_AUDIO_CAPTURE = 1, DEVICE_VIDEO_CAPTURE = 2) */
    private const val AUDIO_CAPTURE = CefMediaAccessCallback.MediaPermissionFlags.DEVICE_AUDIO_CAPTURE

    /**
     * 给 [browser] 挂上麦克风授权处理器 (每个浏览器一份, 预热浏览器与面板新建的浏览器都要挂)。
     *
     * @param onLog 工具窗口日志 (与面板/预热共用的那份日志面板)
     */
    fun install(client: JBCefClient, browser: CefBrowser, onLog: (String) -> Unit) {
        try {
            // 单选接口直接用 SAM 转换; 出错时返回 false (CEF 按默认策略拒绝), 不能让页面的 getUserMedia 永远挂起
            val handler = CefPermissionHandler { cefBrowser, _, requestingOrigin, requestedPermissions, callback ->
                try {
                    // 只取请求里的音频位: 音频+视频同时请求时只放行麦克风, 纯视频请求得 0 即拒绝
                    val allowed = requestedPermissions and AUDIO_CAPTURE
                    if (allowed != 0 && isSamePageOrigin(cefBrowser, requestingOrigin)) {
                        callback.Continue(allowed)
                        // 每次听写都会请求一次权限: 留一行日志, 听写没声音时能对照是没请求还是被系统挡住
                        onLog(DshBundle.message("log.mic.granted", requestingOrigin))
                    } else {
                        callback.Cancel()
                        onLog(DshBundle.message("log.mic.denied", requestingOrigin))
                    }
                    true
                } catch (t: Throwable) {
                    LOG.info("microphone permission request failed", t)
                    onLog(DshBundle.message("log.mic.failed", t.message ?: "null"))
                    false
                }
            }
            client.addPermissionHandler(handler, browser)
        } catch (t: Throwable) {
            // 老版本 JCEF 没有权限处理器 (或已被别的处理器占用): 浏览器照常可用, 只是听写录不了音
            LOG.info("microphone permission handler unavailable", t)
            onLog(DshBundle.message("log.mic.installFailed", t.message ?: "null"))
        }
    }

    /**
     * 请求来源是否就是浏览器里当前加载的页面 (同协议 + 主机 + 端口), 且主机是本机回环地址。
     * 访问本机 dsh 页面时两者恒等; 页面被导航到公网站点后, 来源主机不再是回环地址, 直接拒绝。
     */
    private fun isSamePageOrigin(browser: CefBrowser, requestingOrigin: String): Boolean {
        val request = originOf(requestingOrigin) ?: return false
        if (!isLoopback(request.host)) return false
        val page = try {
            originOf(browser.url)
        } catch (_: Throwable) {
            null
        } ?: return false
        return request == page
    }

    private data class Origin(val scheme: String, val host: String, val port: Int)

    /** 解析 `协议://主机:端口` 形式的基础地址 (忽略路径/查询/锚点), 解析失败返回 null */
    private fun originOf(url: String?): Origin? {
        if (url.isNullOrBlank()) return null
        val uri = try {
            URI(url)
        } catch (_: Exception) {
            return null
        }
        val scheme = uri.scheme?.lowercase() ?: return null
        val host = uri.host?.lowercase() ?: return null
        val port = if (uri.port >= 0) uri.port else when (scheme) {
            "http" -> 80
            "https" -> 443
            else -> return null
        }
        return Origin(scheme, host, port)
    }

    /** 本机回环主机: dsh 页面固定由 localhost 提供, 这里兼容 127.0.0.1 与 IPv6 写法 */
    private fun isLoopback(host: String): Boolean =
        host == "localhost" || host == "::1" || host == "[::1]" ||
            host == "0:0:0:0:0:0:0:1" || host.startsWith("127.")
}
