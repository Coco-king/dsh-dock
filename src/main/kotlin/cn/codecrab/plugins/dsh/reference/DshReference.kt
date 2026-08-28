package cn.codecrab.plugins.dsh.reference

import cn.codecrab.plugins.dsh.settings.DshSettingsState
import cn.codecrab.plugins.dsh.util.WslSupport
import com.intellij.openapi.vfs.VirtualFile

/**
 * 构造 dsh 输入框中的 `@路径` 引用, 并按启动模式区分 WSL/Windows 路径。
 *
 * dsh 的引用约定: 用户消息中 `@<path>` 表示显式文件引用, 模型需要时用 read 工具读取
 * (与 dsh TUI 的 file-reference 约定一致)。`#L<start>-<end>` 表示行范围 (1 起, 含端点)。
 *
 * 路径差异 (启动模式不同, dsh 看到的文件系统不同):
 *  - WSL 模式:    `C:/foo/bar` -> `/mnt/c/foo/bar`  (dsh 运行在 WSL 内, 只认 WSL 路径)
 *  - Windows 模式: `C:/foo/bar` 保持原样 (正斜杠), dsh 直接运行在 Windows
 *  - `\\wsl$\<distro>\...` 远程项目: WSL 模式转成 `/...`, Windows 模式保留 `//wsl$/...`
 *
 * 含空格的路径按 dsh 约定加引号: `@"path with spaces"`。
 */
object DshReference {

    /** 把 IDEA 的 VirtualFile 转换为 dsh 进程可见的路径 */
    fun dshPath(file: VirtualFile, launchMode: String): String =
        dshPathFromString(file.path, launchMode) ?: ""

    /** 把任意路径字符串转换为 dsh 进程可见的路径 (与 [dshPath] 同一套转换规则) */
    fun dshPathFromString(path: String?, launchMode: String): String? {
        if (path.isNullOrBlank()) return null
        val raw = path.replace('\\', '/')
        return if (launchMode == DshSettingsState.MODE_WSL) WslSupport.toWslPath(raw) ?: raw else raw
    }

    /** 文件引用: `@<path>` */
    fun fileReference(file: VirtualFile, launchMode: String): String =
        mention(dshPath(file, launchMode))

    /** 选区引用: `@<path>#L<start>-<end>` (单行时 `@<path>#L<start>`) */
    fun selectionReference(file: VirtualFile, launchMode: String, startLine: Int, endLine: Int): String {
        val path = dshPath(file, launchMode)
        val suffix = if (startLine == endLine) "#L$startLine" else "#L$startLine-$endLine"
        return mention(path) + suffix
    }

    private fun mention(path: String): String =
        if (path.any { it.isWhitespace() }) "@\"$path\"" else "@$path"
}
