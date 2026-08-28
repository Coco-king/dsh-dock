package cn.codecrab.plugins.dsh.toolwindow

import cn.codecrab.plugins.dsh.DshBundle
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.IconLoader
import com.intellij.openapi.util.ScalableIcon
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.components.JBLabel
import java.awt.Component
import java.awt.Graphics
import java.awt.Image
import java.awt.image.BufferedImage
import javax.swing.BorderFactory
import javax.swing.Icon
import javax.swing.ImageIcon
import javax.swing.JComponent
import javax.swing.SwingConstants
import javax.swing.UIManager

/**
 * Dsh 工具窗口工厂: 右侧工具栏的黑色鲸鱼图标 + 侧边栏内容。
 *
 * 图标取自 dsh WebUI 的 favicon (鲸鱼), 深色主题下自动切换为白色版本,
 * 与 dsh favicon 的 prefers-color-scheme 行为一致。
 * 主题明暗判断用纯 Swing 的 UIManager 色板亮度, 不依赖任何平台 API, 跨版本稳定。
 */
class DshToolWindowFactory : ToolWindowFactory {

    override fun init(toolWindow: ToolWindow) {
        toolWindow.setIcon(whaleIcon())
        // 工具窗口显示名: 默认取 id "Dsh", 悬浮提示/标签页/View 菜单读的是 title / stripeTitle,
        // 这里全部设为与插件名一致的 "Dsh Dock"
        toolWindow.setTitle("Dsh Dock")
        try {
            toolWindow.setStripeTitle("Dsh Dock")
        } catch (_: Throwable) {
            // 旧版 IDE 可能没有 setStripeTitle, 忽略 (title 兜底)
        }
    }

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val panel: JComponent = try {
            DshToolWindowPanel(project, toolWindow.disposable)
        } catch (t: Throwable) {
            // 面板创建失败绝不能留空窗口 (平台会一直显示"没有要显示的内容"): 兜底展示错误信息
            Logger.getInstance(DshToolWindowFactory::class.java).warn("Failed to create the Dsh Dock panel", t)
            JBLabel(DshBundle.message("panel.createFailed", t.message ?: "null")).apply {
                horizontalAlignment = SwingConstants.CENTER
                border = BorderFactory.createEmptyBorder(16, 16, 16, 16)
            }
        }
        if (panel is DshToolWindowPanel) {
            DshToolWindowRegistry.register(project, panel)
        }
        toolWindow.component.add(panel)
        // 标签页显示名也同步 (部分 IDE 版本标签/tooltip 取自 content display name)
        try {
            toolWindow.contentManager.getContent(0)?.setDisplayName("Dsh Dock")
        } catch (_: Throwable) {
        }
    }

    companion object {

        /** 工具窗口 id (与 plugin.xml 中 toolWindow 的 id 一致) */
        const val TOOL_WINDOW_ID = "Dsh Dock"

        /** 菜单项标准图标尺寸 (IDEA 右键菜单图标为 16x16) */
        private const val MENU_ICON_SIZE = 16

        /** 根据当前主题选择黑色/白色鲸鱼图标并栅格化 (兼容旧版 SVG 工具窗口图标渲染问题) */
        fun whaleIcon(): ScalableIcon {
            val resource = if (isDarkUi()) "icons/dshWhaleWhite.svg" else "icons/dshWhaleBlack.svg"
            return RasterizedScalableIcon(IconLoader.getIcon(resource, DshToolWindowFactory::class.java))
        }

        /**
         * 右键菜单用的小鲸鱼图标: 鲸鱼 SVG 原始 50x50, 工具窗口图标会被平台缩放,
         * 但菜单图标按原始尺寸渲染, 50px 在菜单里过大, 这里缩放到标准 16x16。
         */
        fun menuWhaleIcon(): Icon {
            val src = whaleIcon()
            return src.scale(MENU_ICON_SIZE.toFloat() / src.iconWidth)
        }

        /**
         * 通过 UIManager 面板背景色亮度判断当前是否为深色主题。
         * 不依赖 EditorColorsScheme.isDark 等在新旧版本间变动的 API。
         */
        fun isDarkUi(): Boolean {
            val bg = UIManager.getColor("Panel.background") ?: return false
            val luminance = 0.299 * bg.red + 0.587 * bg.green + 0.114 * bg.blue
            return luminance < 128
        }
    }

    private class RasterizedScalableIcon(source: Icon) : ScalableIcon {

        private val raster: ImageIcon

        init {
            val img = BufferedImage(source.iconWidth, source.iconHeight, BufferedImage.TYPE_INT_ARGB)
            val g = img.createGraphics()
            source.paintIcon(null, g, 0, 0)
            g.dispose()
            raster = ImageIcon(img)
        }

        override fun paintIcon(c: Component?, g: Graphics, x: Int, y: Int) {
            raster.paintIcon(c, g, x, y)
        }

        override fun getIconWidth() = raster.iconWidth
        override fun getIconHeight() = raster.iconHeight

        override fun getScale() = 1.0f

        override fun scale(scale: Float): Icon {
            val w = (iconWidth * scale).toInt()
            val h = (iconHeight * scale).toInt()
            return ImageIcon(raster.image.getScaledInstance(w, h, Image.SCALE_SMOOTH))
        }
    }
}