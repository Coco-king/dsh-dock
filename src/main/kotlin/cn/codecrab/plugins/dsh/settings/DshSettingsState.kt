package cn.codecrab.plugins.dsh.settings

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage

/**
 * 插件持久化设置, 存于 app 级配置 (<userHome>/AppData/Roaming/JetBrains/.../options/dsh-plugin.xml).
 *
 * 设计原则: 尽量通用, 不绑定任何本机路径 (WSL 发行版 / NVM 路径 / ldsh.cmd 等
 * 都交给用户自己的环境, 插件只负责启动与展示)。
 *
 * 端口与附加参数按启动方式 (WSL / Windows) 分别配置: 每种启动方式可以监听不同的端口、
 * 追加不同的参数; 插件实际启动时只使用当前选中的启动方式 (见 [launchMode]) 对应的配置。
 */
@State(name = "CnCodecrabPluginsDshSettings", storages = [Storage("dsh-plugin.xml")])
class DshSettingsState : PersistentStateComponent<DshSettingsState> {

    /** 启动方式: "windows" = 直接在 Windows 启动 (默认); "wsl" = 在 WSL 中启动 */
    var launchMode: String = MODE_WINDOWS

    /** WSL 模式: dsh web 监听端口 (默认 3080, 与官方 dsh web 默认端口一致) */
    var wslPort: Int = WSL_DEFAULT_PORT

    /** WSL 模式: 追加到 `dsh web` 后的附加参数 */
    var wslExtraDshArgs: String = ""

    /** Windows 模式: dsh web 监听端口 */
    var windowsPort: Int = WINDOWS_DEFAULT_PORT

    /** Windows 模式: 追加到 `dsh web` 后的附加参数 */
    var windowsExtraDshArgs: String = ""

    /** 自动启动时机 (三选一, 见 companion 常量): 默认 IDEA 启动时 —— 升级安装的老用户配置中无此字段,
     * 读取时即落到该默认值, 等同于默认启用 */
    var startMode: String = START_MODE_IDE

    /** WebUI 就绪后同时用系统浏览器打开 */
    var openExternalBrowser: Boolean = false

    /** dsh 文件写入工具执行成功后, 自动刷新 IDEA 的 VFS 与已打开的编辑器 */
    var syncEditedFiles: Boolean = true

    /** dsh WebUI 主题跟随 IDE (暗色 IDE 时强制 WebUI 深色) */
    var themeFollowIde: Boolean = true

    /** dsh WebUI 语言: "" = 跟随浏览器/IDE; "zh-CN" = 中文; "en" = English */
    var forceLocale: String = ""

    /** 指定启动方式对应的端口 */
    fun portFor(mode: String): Int = if (mode == MODE_WSL) wslPort else windowsPort

    /** 指定启动方式对应的附加参数 */
    fun extraArgsFor(mode: String): String = if (mode == MODE_WSL) wslExtraDshArgs else windowsExtraDshArgs

    /** 当前选中的启动方式对应的端口 */
    fun currentPort(): Int = portFor(launchMode)

    /** 当前选中的启动方式对应的附加参数 */
    fun currentExtraArgs(): String = extraArgsFor(launchMode)

    override fun getState(): DshSettingsState = this

    override fun loadState(state: DshSettingsState) {
        launchMode = state.launchMode
        wslPort = state.wslPort
        wslExtraDshArgs = state.wslExtraDshArgs
        windowsPort = state.windowsPort
        windowsExtraDshArgs = state.windowsExtraDshArgs
        startMode = state.startMode
        openExternalBrowser = state.openExternalBrowser
        syncEditedFiles = state.syncEditedFiles
        themeFollowIde = state.themeFollowIde
        forceLocale = state.forceLocale
    }

    companion object {
        const val MODE_WSL: String = "wsl"
        const val WSL_DEFAULT_PORT: Int = 3090

        const val MODE_WINDOWS: String = "windows"
        const val WINDOWS_DEFAULT_PORT: Int = 3080

        /** 自动启动时机: IDE 启动 (本次会话首次打开项目) 时, 后台启动 dsh 并预热内嵌浏览器 */
        const val START_MODE_IDE: String = "ideStartup"

        /** 自动启动时机: 打开工具窗口时启动 (不做启动预热) */
        const val START_MODE_TOOL_WINDOW: String = "toolWindow"

        /** 自动启动时机: 不自动启动, 全手动 */
        const val START_MODE_MANUAL: String = "manual"

        @JvmStatic
        fun getInstance(): DshSettingsState =
            ApplicationManager.getApplication().getService(DshSettingsState::class.java)
    }
}