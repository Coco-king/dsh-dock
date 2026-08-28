package cn.codecrab.plugins.dsh.util

import com.intellij.openapi.util.SystemInfo
import com.intellij.openapi.util.io.FileUtil
import java.io.File
import java.nio.charset.StandardCharsets

/**
 * WSL 路径与命令辅助工具。
 *
 * 插件运行在 Windows IDE 中, 通过 `wsl.exe` 调用 WSL 内的 dsh。
 * Windows 路径 (如 `C:\foo\bar`) 需要转换为 WSL 路径 (`/mnt/c/foo/bar`)。
 */
object WslSupport {

    val isWindows: Boolean
        get() = SystemInfo.isWindows

    /**
     * 将 Windows 路径转换为 WSL 内路径。
     * - `C:\foo`          -> `/mnt/c/foo`
     * - `\\wsl$\Ubuntu\..` -> `/..` (WSL 远程项目)
     * - 已经是 POSIX 风格的路径原样返回
     */
    fun toWslPath(path: String?): String? {
        if (path.isNullOrBlank()) return null
        val norm = path.replace('\\', '/')
        // \\wsl$\<distro>\home\user\proj -> /home/user/proj
        val wslRegex = Regex("^//wsl\\$/([^/]+)/(.*)$", RegexOption.IGNORE_CASE)
        val wslMatch = wslRegex.matchEntire(norm)
        if (wslMatch != null) {
            val rest = wslMatch.groupValues[2]
            return if (rest.isEmpty()) "/" else "/$rest"
        }
        val driveRegex = Regex("^([A-Za-z]):(/.*)?$")
        val driveMatch = driveRegex.matchEntire(norm)
        if (driveMatch != null) {
            val drive = driveMatch.groupValues[1].lowercase()
            val rest = driveMatch.groupValues[2].ifEmpty { "" }
            return "/mnt/$drive$rest"
        }
        // 可能是 UNC \\server\share 或其他, 无法可靠转换
        return norm.ifEmpty { null }
    }

    /**
     * POSIX shell 单引号转义, 用于把任意字符串安全地拼进 bash 脚本。
     */
    fun shellSingleQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

    /**
     * 在插件的临时目录生成一个唯一文件名 (前缀+随机后缀), 如 dsh-run-123456.cmd。
     */
    fun createTempFile(prefix: String, suffix: String): File {
        return FileUtil.createTempFile(prefix, suffix, true)
    }

    /**
     * 以 UTF-8 写入文本文件 (Windows 批处理用 CRLF 行尾)。
     */
    fun writeTextFile(file: File, content: String, crlf: Boolean = false) {
        val text = if (crlf) content.replace("\n", "\r\n") else content
        FileUtil.writeToFile(file, text.toByteArray(StandardCharsets.UTF_8))
    }

    /**
     * 判断端口是否已在监听。
     * dsh web 可能只绑定 IPv4 (127.0.0.1) 或只绑定 IPv6 (::1, 例如 localhost 优先解析到 v6),
     * 因此逐个尝试, 任一可连即视为就绪。
     */
    fun isPortOpen(port: Int): Boolean {
        if (port <= 0 || port > 65535) return false
        for (host in arrayOf("127.0.0.1", "::1", "localhost")) {
            try {
                java.net.Socket().use { socket ->
                    socket.connect(java.net.InetSocketAddress(host, port), 400)
                }
                return true
            } catch (_: Exception) {
            }
        }
        return false
    }
}