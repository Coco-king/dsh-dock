package cn.codecrab.plugins.dsh

import com.intellij.openapi.options.Configurable
import com.intellij.openapi.util.NlsContexts
import com.intellij.ui.JBColor
import com.intellij.ui.TitledSeparator
import com.intellij.ui.components.JBLabel
import java.awt.FlowLayout
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets
import javax.swing.ButtonGroup
import javax.swing.JCheckBox
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JRadioButton
import javax.swing.JTextField

/**
 * 插件设置页 (Settings -> Other Settings -> Dsh Dock)。
 *
 * 只包含通用配置, 不绑定任何本机路径。
 * 端口与附加参数按启动方式 (WSL / Windows) 分别配置: 每个启动选项是一组,
 * 其下的端口/附加参数缩进放置, 实际启动时只使用当前选中的启动方式对应的那一组配置。
 */
class DshSettingsConfigurable : Configurable {

    private val settings = DshSettingsState.getInstance()

    private var autoStartField: JCheckBox? = null
    private var openExternalField: JCheckBox? = null
    private var themeFollowField: JCheckBox? = null
    private var launchWslField: JRadioButton? = null
    private var launchWindowsField: JRadioButton? = null
    private var wslPortField: JTextField? = null
    private var wslExtraArgsField: JTextField? = null
    private var windowsPortField: JTextField? = null
    private var windowsExtraArgsField: JTextField? = null
    private var localeFollowField: JRadioButton? = null
    private var localeZhField: JRadioButton? = null
    private var localeEnField: JRadioButton? = null

    override fun getDisplayName(): @NlsContexts.ConfigurableName String = "Dsh Dock"

    override fun createComponent(): JComponent? {
        val panel = JPanel(GridBagLayout())
        val gbc = GridBagConstraints().apply {
            insets = Insets(3, 6, 3, 6)
            anchor = GridBagConstraints.WEST
            fill = GridBagConstraints.HORIZONTAL
        }

        fun fullWidth(y: Int, c: JComponent): Int {
            gbc.gridx = 0; gbc.gridy = y; gbc.gridwidth = GridBagConstraints.REMAINDER
            gbc.weightx = 1.0
            panel.add(c, gbc)
            gbc.gridwidth = 1
            return y + 1
        }

        autoStartField = JCheckBox("打开工具窗口时自动启动 dsh")
        openExternalField = JCheckBox("WebUI 就绪后同时用系统浏览器打开")
        themeFollowField = JCheckBox("WebUI 主题跟随 IDE (暗色 IDE 使用深色 WebUI)")

        launchWslField = JRadioButton("在 WSL 中启动 (Windows + WSL 环境)")
        launchWindowsField = JRadioButton("在 Windows 中直接启动")
        ButtonGroup().apply {
            add(launchWslField)
            add(launchWindowsField)
        }

        localeFollowField = JRadioButton("跟随 IDE/浏览器")
        localeZhField = JRadioButton("简体中文")
        localeEnField = JRadioButton("English")
        ButtonGroup().apply {
            add(localeFollowField)
            add(localeZhField)
            add(localeEnField)
        }
        val localePanel = radioPanel(localeFollowField!!, localeZhField!!, localeEnField!!)

        wslPortField = JTextField()
        wslExtraArgsField = JTextField()
        windowsPortField = JTextField()
        windowsExtraArgsField = JTextField()

        // 布局: 通用选项 -> 启动方式 (每个启动选项下缩进放置对应的端口/附加参数) -> 语言 -> 提示
        var y = 0
        y = fullWidth(y, autoStartField!!)
        y = fullWidth(y, openExternalField!!)
        y = fullWidth(y, themeFollowField!!)
        y = fullWidth(y, TitledSeparator("启动方式"))
        y = fullWidth(y, modePanel(launchWslField!!, wslPortField!!, wslExtraArgsField!!))
        y = fullWidth(y, modePanel(launchWindowsField!!, windowsPortField!!, windowsExtraArgsField!!))
        y = fullWidth(y, TitledSeparator("WebUI 语言"))
        y = fullWidth(y, localePanel)

        val hint = JBLabel("提示: 实际启动时仅使用当前选中的启动方式对应的「端口」与「附加参数」。")
        hint.foreground = JBColor.GRAY
        y = fullWidth(y, hint)

        reset()
        return panel
    }

    /** 把一组单选按钮横向排在同一行 (比逐行堆叠更紧凑) */
    private fun radioPanel(vararg radios: JRadioButton): JPanel =
        JPanel(FlowLayout(FlowLayout.LEFT, 12, 0)).apply {
            isOpaque = false
            for (radio in radios) add(radio)
        }

    /**
     * 单个启动方式的设置组: 单选按钮作为组的标题, 其下的「端口」「附加参数」
     * 整体再缩进一层放置, 让这些设置直观归属于对应的启动选项。
     */
    private fun modePanel(radio: JRadioButton, portField: JTextField, argsField: JTextField): JPanel {
        val panel = JPanel(GridBagLayout())
        val gbc = GridBagConstraints().apply {
            insets = Insets(2, INDENT_FORM_LEFT, 2, 6)
            anchor = GridBagConstraints.WEST
            fill = GridBagConstraints.HORIZONTAL
        }
        // 单选按钮: 从第一列开始占满整行, 与外层其他行左边距对齐
        gbc.gridx = 0; gbc.gridy = 0
        gbc.gridwidth = GridBagConstraints.REMAINDER; gbc.weightx = 1.0
        panel.add(radio, gbc)
        gbc.gridwidth = 1
        // 标签与输入框再缩进一层, 放在单选按钮下方
        gbc.insets = Insets(2, INDENT_RADIO + INDENT_CHILD, 2, 6)
        gbc.gridx = 0; gbc.gridy = 1; gbc.weightx = 0.0
        panel.add(JLabel("端口:"), gbc)
        gbc.gridx = 1; gbc.gridy = 1; gbc.weightx = 1.0
        panel.add(portField, gbc)
        gbc.gridx = 0; gbc.gridy = 2; gbc.weightx = 0.0
        panel.add(JLabel("附加参数 (追加到 dsh web 后):"), gbc)
        gbc.gridx = 1; gbc.gridy = 2; gbc.weightx = 1.0
        panel.add(argsField, gbc)
        return panel
    }

    override fun isModified(): Boolean {
        val s = settings
        return autoStartField?.isSelected != s.autoStart ||
            openExternalField?.isSelected != s.openExternalBrowser ||
            themeFollowField?.isSelected != s.themeFollowIde ||
            wslPortField?.text?.toIntOrNull() != s.wslPort ||
            wslExtraArgsField?.text?.trim() != s.wslExtraDshArgs ||
            windowsPortField?.text?.toIntOrNull() != s.windowsPort ||
            windowsExtraArgsField?.text?.trim() != s.windowsExtraDshArgs ||
            launchWslField?.isSelected != (s.launchMode != DshSettingsState.MODE_WINDOWS) ||
            localeSelected() != s.forceLocale
    }

    override fun apply() {
        val s = settings
        s.autoStart = autoStartField?.isSelected ?: s.autoStart
        s.openExternalBrowser = openExternalField?.isSelected ?: s.openExternalBrowser
        s.themeFollowIde = themeFollowField?.isSelected ?: s.themeFollowIde
        s.wslPort = wslPortField?.text?.toIntOrNull() ?: s.wslPort
        s.windowsPort = windowsPortField?.text?.toIntOrNull() ?: s.windowsPort
        // 附加参数统一去首尾空白, 并回写输入框, 保证面板与设置状态一致
        val wslArgs = wslExtraArgsField?.text?.trim() ?: s.wslExtraDshArgs
        s.wslExtraDshArgs = wslArgs
        wslExtraArgsField?.text = wslArgs
        val windowsArgs = windowsExtraArgsField?.text?.trim() ?: s.windowsExtraDshArgs
        s.windowsExtraDshArgs = windowsArgs
        windowsExtraArgsField?.text = windowsArgs
        s.launchMode = if (launchWindowsField?.isSelected == true) {
            DshSettingsState.MODE_WINDOWS
        } else {
            DshSettingsState.MODE_WSL
        }
        s.forceLocale = localeSelected()
    }

    override fun reset() {
        val s = settings
        autoStartField?.isSelected = s.autoStart
        openExternalField?.isSelected = s.openExternalBrowser
        themeFollowField?.isSelected = s.themeFollowIde
        wslPortField?.text = s.wslPort.toString()
        wslExtraArgsField?.text = s.wslExtraDshArgs
        windowsPortField?.text = s.windowsPort.toString()
        windowsExtraArgsField?.text = s.windowsExtraDshArgs
        launchWslField?.isSelected = s.launchMode != DshSettingsState.MODE_WINDOWS
        launchWindowsField?.isSelected = s.launchMode == DshSettingsState.MODE_WINDOWS
        when (s.forceLocale) {
            "zh-CN" -> localeZhField?.isSelected = true
            "en" -> localeEnField?.isSelected = true
            else -> localeFollowField?.isSelected = true
        }
    }

    private fun localeSelected(): String = when {
        localeZhField?.isSelected == true -> "zh-CN"
        localeEnField?.isSelected == true -> "en"
        else -> ""
    }

    override fun disposeUIResources() {
        autoStartField = null
        openExternalField = null
        themeFollowField = null
        launchWslField = null
        launchWindowsField = null
        wslPortField = null
        wslExtraArgsField = null
        windowsPortField = null
        windowsExtraArgsField = null
        localeFollowField = null
        localeZhField = null
        localeEnField = null
    }

    private companion object {
        /** 分组内单选按钮的左缩进 (与外层其他行的左边距一致) */
        private const val INDENT_RADIO = 6
        /** 单选按钮下方设置项相对单选按钮的额外缩进 */
        private const val INDENT_CHILD = 24
        /** 与外层行统一的最小左间距 (GridBagConstraints.insets.left) */
        private const val INDENT_FORM_LEFT = INDENT_RADIO
    }
}