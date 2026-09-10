package cn.codecrab.plugins.dsh.settings

import cn.codecrab.plugins.dsh.DshBundle
import cn.codecrab.plugins.dsh.util.DshIdeName
import com.intellij.icons.AllIcons
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

    private var startIdeField: JRadioButton? = null
    private var startToolWindowField: JRadioButton? = null
    private var startManualField: JRadioButton? = null
    private var openExternalField: JCheckBox? = null
    private var syncEditedFilesField: JCheckBox? = null
    private var openFileInIdeField: JCheckBox? = null
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

        // 产品名动态获取: 插件可装在 IDEA / PyCharm / WebStorm 等, 文案不写死 "IDEA"
        val ideName = DshIdeName.productName()
        startIdeField = JRadioButton(DshBundle.message("settings.autoStartMode.ideStartup", ideName))
        startToolWindowField = JRadioButton(DshBundle.message("settings.autoStartMode.toolWindow"))
        startManualField = JRadioButton(DshBundle.message("settings.autoStartMode.manual"))
        ButtonGroup().apply {
            add(startIdeField)
            add(startToolWindowField)
            add(startManualField)
        }
        openExternalField = JCheckBox(DshBundle.message("settings.openExternalBrowser"))
        themeFollowField = JCheckBox(DshBundle.message("settings.themeFollow"))
        syncEditedFilesField = JCheckBox(DshBundle.message("settings.syncEditedFiles", ideName))
        openFileInIdeField = JCheckBox(DshBundle.message("settings.openFileInIde", ideName))

        launchWslField = JRadioButton(DshBundle.message("settings.mode.wsl"))
        launchWindowsField = JRadioButton(DshBundle.message("settings.mode.windows"))
        ButtonGroup().apply {
            add(launchWslField)
            add(launchWindowsField)
        }

        localeFollowField = JRadioButton(DshBundle.message("settings.locale.follow"))
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

        // 布局: 常规选项 (按 启动时机 -> WebUI 展示 -> 编辑器联动 排列)
        //       -> 启动方式 (每个启动选项下缩进放置对应的端口/附加参数) -> 语言 -> 提示
        var y = 0
        y = fullWidth(y, TitledSeparator(DshBundle.message("settings.generalSeparator")))
        y = fullWidth(y, startModePanel())
        y = fullWidth(y, openExternalField!!)
        y = fullWidth(y, themeFollowField!!)
        y = fullWidth(y, syncEditedFilesField!!)
        y = fullWidth(y, openFileInIdeField!!)
        y = fullWidth(y, TitledSeparator(DshBundle.message("settings.modeSeparator")))
        y = fullWidth(y, modePanel(launchWslField!!, wslPortField!!, wslExtraArgsField!!))
        y = fullWidth(y, modePanel(launchWindowsField!!, windowsPortField!!, windowsExtraArgsField!!))
        y = fullWidth(y, TitledSeparator(DshBundle.message("settings.localeSeparator")))
        y = fullWidth(y, localePanel)

        val hint = JBLabel(DshBundle.message("settings.hint"))
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

    /** 「自动启动」一行: 标签 + 三个时机单选, 每个单选后跟问号图标 (悬浮提示该选项含义) */
    private fun startModePanel(): JComponent = JPanel(FlowLayout(FlowLayout.LEFT, 12, 0)).apply {
        isOpaque = false
        add(JLabel(DshBundle.message("settings.autoStartMode.label")))
        add(radioWithHelp(startIdeField!!, DshBundle.message("settings.autoStartMode.tooltip.ideStartup")))
        add(radioWithHelp(startToolWindowField!!, DshBundle.message("settings.autoStartMode.tooltip.toolWindow")))
        add(radioWithHelp(startManualField!!, DshBundle.message("settings.autoStartMode.tooltip.manual")))
    }

    /** 单选按钮 + 问号帮助图标 (悬浮提示该选项的含义) */
    private fun radioWithHelp(radio: JRadioButton, tooltip: String): JComponent =
        JPanel(FlowLayout(FlowLayout.LEFT, 4, 0)).apply {
            isOpaque = false
            add(radio)
            add(JBLabel(AllIcons.General.ContextHelp).apply { toolTipText = tooltip })
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
        panel.add(JLabel(DshBundle.message("settings.port.label")), gbc)
        gbc.gridx = 1; gbc.gridy = 1; gbc.weightx = 1.0
        panel.add(portField, gbc)
        gbc.gridx = 0; gbc.gridy = 2; gbc.weightx = 0.0
        panel.add(JLabel(DshBundle.message("settings.extraArgs.label")), gbc)
        gbc.gridx = 1; gbc.gridy = 2; gbc.weightx = 1.0
        panel.add(argsField, gbc)
        return panel
    }

    override fun isModified(): Boolean {
        val s = settings
        return startModeSelected() != s.startMode ||
            openExternalField?.isSelected != s.openExternalBrowser ||
            syncEditedFilesField?.isSelected != s.syncEditedFiles ||
            openFileInIdeField?.isSelected != s.openFileInIde ||
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
        s.startMode = startModeSelected()
        s.openExternalBrowser = openExternalField?.isSelected ?: s.openExternalBrowser
        s.syncEditedFiles = syncEditedFilesField?.isSelected ?: s.syncEditedFiles
        s.openFileInIde = openFileInIdeField?.isSelected ?: s.openFileInIde
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
        startIdeField?.isSelected = s.startMode == DshSettingsState.START_MODE_IDE
        startToolWindowField?.isSelected = s.startMode == DshSettingsState.START_MODE_TOOL_WINDOW
        startManualField?.isSelected = s.startMode == DshSettingsState.START_MODE_MANUAL
        openExternalField?.isSelected = s.openExternalBrowser
        syncEditedFilesField?.isSelected = s.syncEditedFiles
        openFileInIdeField?.isSelected = s.openFileInIde
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

    /** 当前选中的自动启动时机 */
    private fun startModeSelected(): String = when {
        startToolWindowField?.isSelected == true -> DshSettingsState.START_MODE_TOOL_WINDOW
        startManualField?.isSelected == true -> DshSettingsState.START_MODE_MANUAL
        else -> DshSettingsState.START_MODE_IDE
    }

    override fun disposeUIResources() {
        startIdeField = null
        startToolWindowField = null
        startManualField = null
        openExternalField = null
        syncEditedFilesField = null
        openFileInIdeField = null
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