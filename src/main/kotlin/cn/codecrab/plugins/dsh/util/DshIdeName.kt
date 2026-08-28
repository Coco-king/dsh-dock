package cn.codecrab.plugins.dsh.util

import com.intellij.openapi.application.ApplicationNamesInfo

/**
 * 当前 IDE 产品名 (如 "IDEA" / "PyCharm" / "WebStorm"):
 * 插件可安装到所有 JetBrains IDE, 界面文案中的产品名动态获取, 不写死 "IDEA"。
 * 注: 平台对 IDEA 报的就是简码 "IDEA" (展示名 "IntelliJ IDEA" 在 fullProductName), 有意用简码。
 */
object DshIdeName {

    /** 产品名 (平台原始值), 获取失败时兜底 "IDEA" */
    @JvmStatic
    fun productName(): String = try {
        ApplicationNamesInfo.getInstance().productName
    } catch (t: Throwable) {
        "IDEA"
    }
}
