package cn.codecrab.plugins.dsh

import com.intellij.openapi.options.Configurable
import com.intellij.openapi.util.NlsContexts
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
 */
class DshSettingsConfigurable : Configurable {

    private val settings = DshSettingsState.getInstance()

    private var autoStartField: JCheckBox? = null
    private var openExternalField: JCheckBox? = null
    private var themeFollowField: JCheckBox? = null
    private var portField: JTextField? = null
    private var extraArgsField: JTextField? = null
    private var launchWslField: JRadioButton? = null
    private var launchWindowsField: JRadioButton? = null
    private var localeFollowField: JRadioButton? = null
    private var localeZhField: JRadioButton? = null
    private var localeEnField: JRadioButton? = null

    override fun getDisplayName(): @NlsContexts.ConfigurableName String = "Dsh Dock"

    override fun createComponent(): JComponent? {
        val panel = JPanel(GridBagLayout())
        val gbc = GridBagConstraints().apply {
            insets = Insets(4, 6, 4, 6)
            anchor = GridBagConstraints.WEST
            fill = GridBagConstraints.HORIZONTAL
        }

        fun fullWidth(y: Int, c: JComponent): Int {
            gbc.gridx = 0; gbc.gridy = y; gbc.gridwidth = GridBagConstraints.REMAINDER
            panel.add(c, gbc)
            gbc.gridwidth = 1
            return y + 1
        }

        fun row(y: Int, label: String, c: JComponent): Int {
            gbc.gridx = 0; gbc.gridy = y; gbc.weightx = 0.0
            panel.add(JLabel(label), gbc)
            gbc.gridx = 1; gbc.gridy = y; gbc.weightx = 1.0
            panel.add(c, gbc)
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

        portField = JTextField()
        extraArgsField = JTextField()

        var y = 0
        y = fullWidth(y, autoStartField!!)
        y = fullWidth(y, openExternalField!!)
        y = fullWidth(y, JLabel("启动方式:"))
        y = fullWidth(y, launchWslField!!)
        y = fullWidth(y, launchWindowsField!!)
        y = fullWidth(y, JLabel("WebUI 语言:"))
        y = fullWidth(y, localeFollowField!!)
        y = fullWidth(y, localeZhField!!)
        y = fullWidth(y, localeEnField!!)
        y = fullWidth(y, themeFollowField!!)
        y = row(y, "端口:", portField!!)
        y = row(y, "附加参数 (追加到 dsh web 后):", extraArgsField!!)

        reset()
        return panel
    }

    override fun isModified(): Boolean {
        val s = settings
        return autoStartField?.isSelected != s.autoStart ||
            openExternalField?.isSelected != s.openExternalBrowser ||
            themeFollowField?.isSelected != s.themeFollowIde ||
            portField?.text?.toIntOrNull() != s.port ||
            extraArgsField?.text != s.extraDshArgs ||
            launchWslField?.isSelected != (s.launchMode != "windows") ||
            localeSelected() != s.forceLocale
    }

    override fun apply() {
        val s = settings
        s.autoStart = autoStartField?.isSelected ?: s.autoStart
        s.openExternalBrowser = openExternalField?.isSelected ?: s.openExternalBrowser
        s.themeFollowIde = themeFollowField?.isSelected ?: s.themeFollowIde
        s.port = portField?.text?.toIntOrNull() ?: s.port
        s.extraDshArgs = extraArgsField?.text?.trim() ?: s.extraDshArgs
        s.launchMode = if (launchWindowsField?.isSelected == true) "windows" else "wsl"
        s.forceLocale = localeSelected()
    }

    override fun reset() {
        val s = settings
        autoStartField?.isSelected = s.autoStart
        openExternalField?.isSelected = s.openExternalBrowser
        themeFollowField?.isSelected = s.themeFollowIde
        portField?.text = s.port.toString()
        extraArgsField?.text = s.extraDshArgs
        launchWslField?.isSelected = s.launchMode != "windows"
        launchWindowsField?.isSelected = s.launchMode == "windows"
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
        portField = null
        extraArgsField = null
        launchWslField = null
        launchWindowsField = null
        localeFollowField = null
        localeZhField = null
        localeEnField = null
    }
}