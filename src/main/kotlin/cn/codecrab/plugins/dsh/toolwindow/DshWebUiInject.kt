package cn.codecrab.plugins.dsh.toolwindow

import cn.codecrab.plugins.dsh.DshBundle
import cn.codecrab.plugins.dsh.settings.DshSettingsState
import org.cef.browser.CefBrowser

/**
 * dsh WebUI 页面注入的公共实现: 主题/语言覆盖、持久化会话清除、JS 字符串转义。
 * 面板 (DshToolWindowPanel) 与浏览器预热 (DshWebUiWarmup) 共用, 保证两边加载的页面行为一致。
 */
object DshWebUiInject {

    /**
     * 注入主题/语言覆盖脚本。
     *
     * dsh WebUI 的主题跟随 `matchMedia("prefers-color-scheme: dark")`,
     * 语言跟随 `navigator.languages`（JCEF 的该值跟随系统/浏览器语言, 不一定与 IDE 一致）。
     *
     * 语言控制的**主路径**不是这里: 插件在加载页面前通过 dsh 设置 API 写入持久化语言偏好
     * (settings.locale.preference, 见 DshWorkspaceApi.syncLocalePreference)——
     * dsh 的语言优先级是「持久化偏好 > 浏览器」, 因此页面一加载就是正确的语言。
     * 这里注入的 JS 只作兜底 (覆盖 navigator 以防个别场景偏好未生效), 并负责主题跟随。
     */
    fun injectUiThemeLocaleOverride(browser: CefBrowser, settings: DshSettingsState, onLog: (String) -> Unit) {
        val dark = settings.themeFollowIde && DshToolWindowFactory.isDarkUi()
        val langs = if (settings.forceLocale.isBlank()) followIdeLanguages() else {
            val l = settings.forceLocale.trim()
            if (l.startsWith("zh", ignoreCase = true)) listOf(l, "zh", "en")
            else listOf(l, "en")
        }
        try {
            browser.executeJavaScript(uiOverrideScript(dark, langs), "dsh://ide-renderer-override.js", 0)
        } catch (t: Throwable) {
            onLog(DshBundle.message("log.injectFailed", t.message ?: "null"))
        }
    }

    /**
     * "跟随 IDE/浏览器"模式下推导 dsh 语言 (JS 注入兜底用): 始终显式注入, 不依赖 JCEF 默认语言
     * (JCEF 的 `navigator.languages` 跟随系统/浏览器, 英文 IDE + 中文系统时可能显示中文)。
     * IDE 是中文 (见 [ideLanguageTag], 含简/繁) 时注入中文; 其他语言注入英文。
     */
    private fun followIdeLanguages(): List<String> =
        if (ideLanguageTag().startsWith("zh")) listOf("zh", "en") else listOf("en")

    fun uiOverrideScript(dark: Boolean, langs: List<String>): String {
        val darkJs = if (dark) "true" else "false"
        val langsJs = langs.joinToString(prefix = "[", postfix = "]", separator = ", ") { "\"$it\"" }
        val langOverride = if (langs.isNotEmpty()) {
            """
            |        try {
            |          Object.defineProperty(Navigator.prototype, "languages", { configurable: true, get: function () { return L; } });
            |          Object.defineProperty(Navigator.prototype, "language", { configurable: true, get: function () { return L[0]; } });
            |        } catch (err) {}
            """.trimMargin()
        } else ""
        return """
            |(function () {
            |  var DARK = $darkJs;
            |  var L = $langsJs;
            |  try {
            |    var realMatchMedia = window.matchMedia.bind(window);
            |    window.matchMedia = function (query) {
            |      try {
            |        if (typeof query === "string" && query.indexOf("prefers-color-scheme") !== -1) {
            |          return { matches: DARK, media: query, onchange: null,
            |                   addListener: function () {}, removeListener: function () {},
            |                   addEventListener: function () {}, removeEventListener: function () {},
            |                   dispatchEvent: function () { return false; } };
            |        }
            |      } catch (err) {}
            |      return realMatchMedia(query);
            |    };
            |  } catch (err) {}
            |$langOverride
            |})();
        """.trimMargin()
    }

    /**
     * 清除"不属于当前项目工作空间"的持久化会话选择 (页面加载前调用)。
     * dsh WebUI 会把"上次打开的会话"持久化到浏览器 localStorage (dsh.sessions.current),
     * 页面加载时会恢复它, 从而跳过工作空间的初始选中逻辑, 导致页面停留在旧项目。
     * 持久化的会话不属于给定工作空间时才删, 属于 (用户本来就在当前项目里) 则保留。
     * [sessionIds] 为 null (尚未同步/同步失败) 时不动 localStorage, 按 dsh 原行为。
     */
    fun clearPersistedSessionIfForeign(browser: CefBrowser, sessionIds: List<String>?, onLog: (String) -> Unit) {
        if (sessionIds == null) return
        val idsJs = sessionIds.joinToString(prefix = "[", postfix = "]", separator = ",") { jsString(it) }
        val script = """
            |(function () {
            |  try {
            |    var raw = localStorage.getItem("dsh.sessions.current");
            |    if (raw) {
            |      var o = JSON.parse(raw);
            |      if (o && o.sessionId && $idsJs.indexOf(o.sessionId) === -1) {
            |        localStorage.removeItem("dsh.sessions.current");
            |      }
            |    }
            |  } catch (err) {}
            |})();
        """.trimMargin()
        try {
            browser.executeJavaScript(script, "dsh://ide-workspace-clear.js", 0)
        } catch (t: Throwable) {
            onLog(DshBundle.message("log.clearPersistFailed", t.message ?: "null"))
        }
    }

    /**
     * IDE 界面语言子标签 (小写, 如 "en" / "zh")。
     * 用平台解析 bundle 的 locale (DynamicBundle.getLocale) —— 这才是真正的 IDE 界面语言;
     * `user.language` 系统属性只是 JVM 默认语言 (英文 IDE + 中文系统时仍是 zh), 不能用。
     */
    fun ideLanguageTag(): String = try {
        com.intellij.DynamicBundle.getLocale().language.lowercase()
    } catch (t: Throwable) {
        // 旧版本平台兜底: 退到 JVM 默认语言
        java.util.Locale.getDefault().language.lowercase()
    }

    /**
     * 推导 dsh WebUI 应使用的语言 id ("zh" / "en", dsh 仅支持这两种):
     * 设置页强制指定 (settings.forceLocale) 优先; 跟随模式下按 IDE 界面语言
     * (见 [ideLanguageTag], 含简/繁中文) 推导, 其余语言一律英文。
     * 该 id 通过 dsh 的 settings.update RPC 写入持久化语言偏好。
     */
    fun effectiveDshLocale(settings: DshSettingsState): String {
        val forced = settings.forceLocale.trim()
        return when {
            forced.startsWith("zh", ignoreCase = true) -> "zh"
            forced.equals("en", ignoreCase = true) -> "en"
            else -> if (ideLanguageTag().startsWith("zh")) "zh" else "en"
        }
    }

    /** 转成 JS 双引号字符串字面量 (路径可能含引号/反斜杠等) */
    fun jsString(value: String): String {
        val sb = StringBuilder("\"")
        for (ch in value) {
            when (ch) {
                '\\' -> sb.append("\\\\")
                '"' -> sb.append("\\\"")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> if (ch.code < 0x20) sb.append("\\u%04x".format(ch.code)) else sb.append(ch)
            }
        }
        sb.append("\"")
        return sb.toString()
    }
}
