package cn.codecrab.plugins.dsh

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage

/**
 * 插件持久化设置, 存于 app 级配置 (<userHome>/AppData/Roaming/JetBrains/.../options/dsh-plugin.xml).
 *
 * 设计原则: 尽量通用, 不绑定任何本机路径 (WSL 发行版 / NVM 路径 / ldsh.cmd 等
 * 都交给用户自己的环境, 插件只负责启动与展示)。
 */
@State(name = "CnCodecrabPluginsDshSettings", storages = [Storage("dsh-plugin.xml")])
class DshSettingsState : PersistentStateComponent<DshSettingsState> {

    /** dsh web 监听端口 (默认 3080, 与官方 dsh web 默认端口一致) */
    var port: Int = 3080

    /** 启动方式: "windows" = 直接在 Windows 启动 (默认); "wsl" = 在 WSL 中启动 */
    var launchMode: String = "windows"

    /** 打开工具窗口时自动启动 */
    var autoStart: Boolean = true

    /** WebUI 就绪后同时用系统浏览器打开 */
    var openExternalBrowser: Boolean = false

    /** 追加到 `dsh web` 后的附加参数 */
    var extraDshArgs: String = ""

    /** dsh WebUI 主题跟随 IDE (暗色 IDE 时强制 WebUI 深色) */
    var themeFollowIde: Boolean = true

    /** dsh WebUI 语言: "" = 跟随浏览器/IDE; "zh-CN" = 中文; "en" = English */
    var forceLocale: String = ""

    override fun getState(): DshSettingsState = this

    override fun loadState(state: DshSettingsState) {
        port = state.port
        launchMode = state.launchMode
        autoStart = state.autoStart
        openExternalBrowser = state.openExternalBrowser
        extraDshArgs = state.extraDshArgs
        themeFollowIde = state.themeFollowIde
        forceLocale = state.forceLocale
    }

    companion object {
        @JvmStatic
        fun getInstance(): DshSettingsState =
            ApplicationManager.getApplication().getService(DshSettingsState::class.java)
    }
}