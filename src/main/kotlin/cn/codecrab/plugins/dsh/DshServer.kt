package cn.codecrab.plugins.dsh

import com.intellij.ide.AppLifecycleListener
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import java.io.File

/**
 * dsh 进程管理器 (应用级单例)。
 *
 * 支持两种启动方式 (见设置):
 *  - WSL 模式: 通过 `wsl.exe bash` 在 WSL 中运行 dsh (通用化: 自动加载用户 ~/.bashrc,
 *    因此 nvm/常规 PATH 安装的 dsh 都能直接用, 不再绑定任何本机路径)
 *  - Windows 模式: 直接在 Windows 上以隐藏窗口运行 `dsh web`
 *
 * 进程隐藏后台运行 (PowerShell -WindowStyle Hidden 包裹, 不弹控制台窗口),
 * 输出捕获到工具窗口日志面板, 轮询端口等待 WebUI 就绪。
 *
 * 退出清理三层保障, 避免 "IDE 退出了 dsh 还在后台":
 *  1. AppLifecycleListener.appClosing — IDEA 退出流程早期回调, 应用服务仍可用
 *  2. JVM shutdown hook — 兜底 (即使 appClosing 没触发)
 *  3. 独立看门狗进程 — 监视 IDE 进程, IDE 消失且端口仍开时自动执行停止脚本
 *     (即使 IDE 被强杀/崩溃, dsh 也会被清理)
 */
object DshServer {

    private val LOG = Logger.getInstance(DshServer::class.java)

    enum class State { IDLE, STARTING, RUNNING, STOPPING }

    @Volatile
    var state: State = State.IDLE
        private set

    /** 状态监听器: 工具窗口面板注册后, 任意窗口启停 dsh 都会同步刷新所有面板的 UI */
    private val stateListeners = java.util.concurrent.CopyOnWriteArrayList<(State) -> Unit>()

    /**
     * 注册状态监听 (工具窗口面板创建时调用)。
     * 注册后立即以当前状态回调一次, 便于新面板初始化 UI —— 多窗口 (同一 IDE 进程内多个项目窗口)
     * 共用本单例, dsh 可能已在其他窗口启动, 而状态回调只在状态切换时触发,
     * 若不创建时同步, 会出现"按钮显示停止、状态文字却显示未启动"的不一致。
     */
    fun addStateListener(listener: (State) -> Unit) {
        stateListeners.add(listener)
        listener(state)
    }

    /** 注销状态监听 (面板销毁时调用) */
    fun removeStateListener(listener: (State) -> Unit) {
        stateListeners.remove(listener)
    }

    /** 统一的状态变更入口: 更新状态并广播给所有已注册监听器 (任意线程可调用) */
    private fun setState(newState: State) {
        state = newState
        for (listener in stateListeners) {
            try {
                listener(newState)
            } catch (t: Throwable) {
                LOG.warn("state listener failed", t)
            }
        }
    }

    @Volatile
    private var process: Process? = null

    /** 启动时预生成的停止脚本路径 (WSL: kill PID; Windows: taskkill), 退出清理复用 */
    @Volatile
    private var stopCmdPath: String? = null

    /** 本插件启动的 dsh 的 PID 标识文件 (WSL 路径); 为 null 表示没有由本插件启动的 dsh */
    @Volatile
    private var ownerPidFileWsl: String? = null

    private val lock = Any()

    init {
        // 1) IDEA 退出流程早期回调 (服务仍可用); 异步清理, 不阻塞退出
        try {
            ApplicationManager.getApplication().messageBus.connect().subscribe(
                AppLifecycleListener.TOPIC,
                object : AppLifecycleListener {
                    override fun appClosing() {
                        stopOnExit()
                    }
                }
            )
        } catch (_: Throwable) {
        }
        // 2) JVM shutdown hook 兜底 (异步触发, 真正的清理由独立看门狗兜底完成)
        Runtime.getRuntime().addShutdownHook(Thread({ stopOnExit() }, "dsh-plugin-shutdown"))
    }

    fun webUrl(port: Int): String = "http://localhost:$port/"

    fun isRunning(): Boolean = state == State.RUNNING

    /**
     * 启动 dsh。
     * 状态变化通过 [addStateListener] 注册的监听器广播给所有面板。
     *
     * @param projectPath 当前项目根路径
     * @param onLog       日志回调 (任意线程, 调用方负责切到 EDT)
     * @return true 表示真正发起了一次启动尝试 (可能随后同步失败, 调用方可据此标记"启动失败"通知);
     *         false 表示未发起 (端口无效 / 已在运行 / 端口已被占用直接复用)
     */
    fun start(projectPath: String?, onLog: (String) -> Unit): Boolean {
        val settings = DshSettingsState.getInstance()
        val port = settings.port
        if (port !in 1..65535) {
            onLog("端口配置无效: $port (应为 1-65535), 请检查设置")
            return false
        }
        synchronized(lock) {
            if (state == State.STARTING || state == State.RUNNING) {
                onLog("dsh 已在运行 (state=${state.name})")
                return false
            }
            if (WslSupport.isPortOpen(port)) {
                onLog("端口 $port 已有服务在监听, 直接打开 WebUI (不重复启动)")
                setState(State.RUNNING)
                return false
            }
            setState(State.STARTING)
            try {
                launch(projectPath, port, settings, onLog)
            } catch (t: Throwable) {
                LOG.warn("launch dsh failed", t)
                onLog("启动失败: ${t.message}")
                setState(State.IDLE)
            }
        }
        return true
    }

    private fun launch(
        projectPath: String?,
        port: Int,
        settings: DshSettingsState,
        onLog: (String) -> Unit,
    ) {
        val mode = settings.launchMode
        when (mode) {
            "windows" -> launchOnWindows(projectPath, port, settings, onLog)
            else -> {
                if (!WslSupport.isWindows) {
                    onLog("WSL 模式仅在 Windows + WSL 环境下可用, 请到设置中改用「Windows 直接启动」")
                    setState(State.IDLE)
                    return
                }
                launchOnWsl(projectPath, port, settings, onLog)
            }
        }
    }

    // ---------- WSL 启动 ----------

    private fun launchOnWsl(
        projectPath: String?,
        port: Int,
        settings: DshSettingsState,
        onLog: (String) -> Unit,
    ) {
        val wslProject = WslSupport.toWslPath(projectPath)
        val extra = settings.extraDshArgs.trim()

        // 1) WSL 侧启动脚本 (通用: 加载用户 ~/.bashrc, nvm 或 PATH 安装的 dsh 均可)
        val shFile = WslSupport.createTempFile("dsh-run-wsl-", ".sh")
        val shWslPath = WslSupport.toWslPath(shFile.absolutePath) ?: shFile.absolutePath
        // 进程标识文件: 记录本插件启动的 dsh 的真实 PID, 退出清理只 kill 这个 PID,
        // 外部启动的同端口 dsh 绝不会被误杀
        val pidFile = WslSupport.createTempFile("dsh-owner-", ".pid")
        val pidFileWsl = WslSupport.toWslPath(pidFile.absolutePath)
        ownerPidFileWsl = pidFileWsl
        val shContent = buildString {
            appendLine("#!/usr/bin/env bash")
            appendLine("# Generated by Dsh plugin. Do not edit.")
            if (wslProject != null) {
                appendLine("if [ -d ${WslSupport.shellSingleQuote(wslProject)} ]; then")
                appendLine("  cd ${WslSupport.shellSingleQuote(wslProject)} 2>/dev/null || cd \"\$HOME\" || exit 1")
                appendLine("else")
                appendLine("  echo \"[dsh] WARNING: 项目目录不存在: ${WslSupport.shellSingleQuote(wslProject)}\", 回退到 \$HOME")
                appendLine("  cd \"\$HOME\" || exit 1")
                appendLine("fi")
            } else {
                appendLine("cd \"\$HOME\" || exit 1")
            }
            // 通用环境初始化: 外层 bash 使用 -lic (登录+交互), 等价于用户自己的终端环境,
            // 完整加载 ~/.profile 与 ~/.bashrc (nvm 等放在 .bashrc 里也能生效);
            // 这里再兜底从 NVM_DIR 或 ~/.nvm 直接加载 nvm (不硬编码任何路径)
            appendLine("[ -s \"\$HOME/.profile\" ] && . \"\$HOME/.profile\" >/dev/null 2>&1 || true")
            appendLine("if ! command -v node >/dev/null 2>&1; then")
            appendLine("  if [ -s \"\${NVM_DIR:-\$HOME/.nvm}/nvm.sh\" ]; then")
            appendLine("    . \"\${NVM_DIR:-\$HOME/.nvm}/nvm.sh\" >/dev/null 2>&1 || true")
            appendLine("    nvm use default >/dev/null 2>&1 || true")
            appendLine("  fi")
            appendLine("fi")
            // 环境检测: 无 Node.js 环境 (node/npm/npx 均不可用) 时直接失败, 不启动
            // (脚本立即退出 -> 端口不会就绪 -> 插件不会加载内置浏览器, 并弹出安装提示)
            appendLine("if ! command -v node >/dev/null 2>&1 && ! command -v npx >/dev/null 2>&1; then")
            appendLine("  echo \"[dsh] ERROR: 未检测到 Node.js 环境 (node / npm / npx 均不可用), 无法启动 dsh web\"")
            appendLine("  echo \"[dsh] 请先在 WSL 安装 Node.js (建议使用 nvm): curl -o- https://raw.githubusercontent.com/nvm-sh/nvm/v0.40.1/install.sh | bash\"")
            appendLine("  exit 1")
            appendLine("fi")
            // 优先使用全局安装的 dsh; 未安装时兼容官方启动命令 npx @deepseek-ai/dsh web (首次运行自动下载)
            appendLine("if command -v dsh >/dev/null 2>&1; then")
            appendLine("  DSH_RUN=(dsh)")
            appendLine("  echo \"[dsh] 使用全局安装的 dsh: \$(command -v dsh)\"")
            appendLine("elif command -v npx >/dev/null 2>&1; then")
            appendLine("  DSH_RUN=(npx --yes @deepseek-ai/dsh)")
            appendLine("  echo \"[dsh] 未检测到全局 dsh, 使用官方启动命令: npx @deepseek-ai/dsh web (首次运行会自动下载)\"")
            appendLine("else")
            appendLine("  echo \"[dsh] ERROR: 找不到 dsh 命令且没有 npx 可用, 请先安装 @deepseek-ai/dsh\"")
            appendLine("  echo \"[dsh] 安装命令: npm i -g @deepseek-ai/dsh   (nvm 环境: nvm use default && npm i -g @deepseek-ai/dsh)\"")
            appendLine("  exit 1")
            appendLine("fi")
            appendLine("echo \"[dsh] starting dsh web  (project: ${wslProject ?: "\$HOME"}, port: $port)\"")
            // 后台运行并记录 PID: 全局 dsh 记录真实 PID 精确停止; npx 模式用 setsid 独立会话,
            // PID 文件写入 "-<pid>" 表示按进程组停止 (npx 内部还会派生 node/dsh 子进程)
            val launchArgs = if (extra.isNotEmpty()) "web --port $port $extra" else "web --port $port"
            val launchCmd = "\"\${DSH_RUN[@]}\" $launchArgs &"
            val setsidCmd = "setsid bash -c 'echo \"-\"\$\$ > ${WslSupport.shellSingleQuote(pidFileWsl!!)}; exec npx --yes @deepseek-ai/dsh $launchArgs' &"
            appendLine("if [ \"\${DSH_RUN[0]}\" = \"dsh\" ]; then")
            appendLine("  $launchCmd")
            appendLine("  DPID=\$!")
            appendLine("  echo \"\$DPID\" > ${WslSupport.shellSingleQuote(pidFileWsl!!)}")
            appendLine("  wait \$DPID")
            appendLine("  RC=\$?")
            appendLine("  rm -f ${WslSupport.shellSingleQuote(pidFileWsl!!)} 2>/dev/null || true")
            appendLine("else")
            appendLine("  if command -v setsid >/dev/null 2>&1; then")
            appendLine("    $setsidCmd")
            appendLine("    wait")
            appendLine("  else")
            appendLine("    $launchCmd")
            appendLine("    DPID=\$!")
            appendLine("    echo \"\$DPID\" > ${WslSupport.shellSingleQuote(pidFileWsl!!)}")
            appendLine("    wait \$DPID")
            appendLine("  fi")
            appendLine("  RC=\$?")
            appendLine("fi")
            appendLine("exit \$RC")
        }
        WslSupport.writeTextFile(shFile, shContent)
        onLog("WSL 模式: 已生成启动脚本 ${shFile.name}")

        val cmdFile = WslSupport.createTempFile("dsh-run-wsl-", ".cmd")
        // bash -lic: 登录 + 交互, 完整加载用户 shell 配置 (nvm 依赖交互式初始化)
        WslSupport.writeTextFile(
            cmdFile,
            "@echo off\r\nsetlocal\r\nwsl.exe bash -lic \"bash ${WslSupport.shellSingleQuote(shWslPath)}\"\r\n",
            crlf = true
        )
        onLog("wsl 工作目录: ${wslProject ?: "(WSL HOME)"}  (项目空间默认使用当前项目根路径)")
        startWrapper(cmdFile, onLog, port, "wsl")
    }

    // ---------- Windows 直接启动 ----------

    private fun launchOnWindows(
        projectPath: String?,
        port: Int,
        settings: DshSettingsState,
        onLog: (String) -> Unit,
    ) {
        val extra = settings.extraDshArgs.trim()
        if (!WslSupport.isWindows) {
            // macOS/Linux: 直接用 bash 启动
            val shFile = WslSupport.createTempFile("dsh-run-posix-", ".sh")
            val cwd = projectPath?.let { if (File(it).isDirectory) it else null }
            val content = buildString {
                appendLine("#!/usr/bin/env bash")
                if (cwd != null) appendLine("cd ${WslSupport.shellSingleQuote(cwd)} 2>/dev/null || exit 1")
                // 环境检测: 无 Node.js 环境时直接失败, 由插件提示安装
                appendLine("if ! command -v node >/dev/null 2>&1 && ! command -v npx >/dev/null 2>&1; then")
                appendLine("  echo \"[dsh] ERROR: 未检测到 Node.js 环境 (node / npm / npx 均不可用), 无法启动 dsh web\"")
                appendLine("  echo \"[dsh] 请先安装 Node.js: https://nodejs.org/\"")
                appendLine("  exit 1")
                appendLine("fi")
                // 优先全局 dsh; 未安装时兼容官方启动命令 npx @deepseek-ai/dsh web
                appendLine("if command -v dsh >/dev/null 2>&1; then")
                if (extra.isNotEmpty()) appendLine("  exec dsh web --port $port $extra") else appendLine("  exec dsh web --port $port")
                appendLine("elif command -v npx >/dev/null 2>&1; then")
                appendLine("  echo \"[dsh] 未检测到全局 dsh, 使用官方启动命令: npx @deepseek-ai/dsh web (首次运行会自动下载)\"")
                if (extra.isNotEmpty()) {
                    appendLine("  exec npx --yes @deepseek-ai/dsh web --port $port $extra")
                } else {
                    appendLine("  exec npx --yes @deepseek-ai/dsh web --port $port")
                }
                appendLine("else")
                appendLine("  echo \"[dsh] ERROR: 找不到 dsh 命令且没有 npx 可用, 请先安装 @deepseek-ai/dsh\"")
                appendLine("  echo \"[dsh] 安装命令: npm i -g @deepseek-ai/dsh\"")
                appendLine("  exit 1")
                appendLine("fi")
            }
            WslSupport.writeTextFile(shFile, content)
            val p = ProcessBuilder("bash", shFile.absolutePath).start()
            process = p
            // 非 Windows: exec 后该进程即 dsh (或 npx)。npx 会派生 node/dsh 子进程,
            // 停止时先杀子进程再杀自身 (pid 可能是 npx, 直接 kill 会遗留 node)
            val stopSh = WslSupport.createTempFile("dsh-stop-", ".sh")
            WslSupport.writeTextFile(
                stopSh,
                "pkill -P ${p.pid()} 2>/dev/null || true\n" +
                    "sleep 1\n" +
                    "kill ${p.pid()} 2>/dev/null || true\n" +
                    "sleep 1\n" +
                    "pkill -9 -P ${p.pid()} 2>/dev/null || true\n" +
                    "kill -9 ${p.pid()} 2>/dev/null || true\n"
            )
            stopCmdPath = stopSh.absolutePath
            drain(p, onLog)
            watch(p, port, onLog)
            waitForReadyInThread(port, p, onLog)
            return
        }

        val cmdFile = WslSupport.createTempFile("dsh-run-win-", ".cmd")
        val content = buildString {
            appendLine("@echo off")
            appendLine("setlocal")
            if (projectPath != null && File(projectPath).isDirectory) {
                appendLine("cd /d \"$projectPath\"")
            }
            appendLine("where npx >nul 2>&1")
            appendLine("if errorlevel 1 (")
            appendLine("  echo [dsh] ERROR: 未检测到 Node.js 环境 (找不到 npx / npm), 无法启动 dsh web")
            appendLine("  echo [dsh] 请先安装 Node.js: https://nodejs.org/  安装后重新打开终端")
            appendLine("  exit /b 1")
            appendLine(")")
            appendLine("where dsh >nul 2>&1")
            appendLine("if errorlevel 1 (")
            appendLine("  echo [dsh] 未检测到全局 dsh, 使用官方启动命令: npx @deepseek-ai/dsh web (首次运行会自动下载)")
            appendLine("  npx --yes @deepseek-ai/dsh web --port $port${if (extra.isNotEmpty()) " $extra" else ""}")
            appendLine("  exit /b %ERRORLEVEL%")
            appendLine(")")
            if (extra.isNotEmpty()) {
                appendLine("dsh web --port $port $extra")
            } else {
                appendLine("dsh web --port $port")
            }
        }
        WslSupport.writeTextFile(cmdFile, content, crlf = true)
        onLog("Windows 模式: 已生成启动命令 ${cmdFile.name}")
        onLog("工作目录: ${if (projectPath != null && File(projectPath).isDirectory) projectPath else "(缺省)"}  (项目空间默认使用当前项目根路径)")
        startWrapper(cmdFile, onLog, port, "windows")
    }

    /** 通过隐藏的 PowerShell 执行 .cmd, 并挂上输出/进程 watching 和端口轮询 */
    private fun startWrapper(cmdFile: File, onLog: (String) -> Unit, port: Int, mode: String) {
        val launchCmd = listOf(
            "powershell.exe",
            "-NoProfile",
            "-ExecutionPolicy", "Bypass",
            "-WindowStyle", "Hidden",
            "-Command", "& '${cmdFile.absolutePath}'",
        )
        onLog("启动命令: powershell.exe -WindowStyle Hidden -> ${cmdFile.name}")
        val p = ProcessBuilder(launchCmd).start()
        process = p
        // 预生成停止脚本 + 启动退出看门狗, 保证 IDE 无论怎么退出 dsh 都能被清理
        stopCmdPath = prepareStopScript(port, mode)
        startExitWatchdog(stopCmdPath!!, port)
        drain(p, onLog)
        watch(p, port, onLog)
        waitForReadyInThread(port, p, onLog)
    }

    // ---------- 输出 / 生命周期 ----------

    private fun drain(p: Process, onLog: (String) -> Unit) {
        fun reader(stream: java.io.InputStream, tag: String) {
            Thread({
                try {
                    stream.bufferedReader(Charsets.UTF_8).useLines { lines ->
                        for (line in lines) onLog("$tag $line")
                    }
                } catch (_: Exception) {
                }
            }, "dsh-plugin-$tag-reader").apply { isDaemon = true }.start()
        }
        reader(p.inputStream, "[dsh]")
        reader(p.errorStream, "[dsh!]")
    }

    private fun watch(p: Process, port: Int, onLog: (String) -> Unit) {
        Thread({
            try {
                val code = p.waitFor()
                LOG.info("dsh launcher exited with code $code")
                if (state == State.STARTING || state == State.RUNNING) {
                    if (WslSupport.isPortOpen(port)) {
                        onLog("启动包装进程已退出(exit=$code), 但端口 $port 仍在监听, 保持连接")
                    } else {
                        onLog("dsh 进程已退出 (exit=$code)")
                        synchronized(lock) {
                            if (state == State.STARTING || state == State.RUNNING) setState(State.IDLE)
                        }
                    }
                }
            } catch (_: InterruptedException) {
            }
        }, "dsh-plugin-watchdog").apply { isDaemon = true }.start()
    }

    private fun waitForReadyInThread(port: Int, p: Process, onLog: (String) -> Unit) {
        Thread({ waitForReady(port, p, onLog) }, "dsh-plugin-port-poll").apply { isDaemon = true }.start()
    }

    private fun waitForReady(port: Int, p: Process, onLog: (String) -> Unit) {
        val deadline = System.currentTimeMillis() + 120_000
        while (System.currentTimeMillis() < deadline) {
            if (Thread.currentThread().isInterrupted) return
            synchronized(lock) {
                if (state == State.IDLE || state == State.STOPPING) return
            }
            if (WslSupport.isPortOpen(port)) {
                synchronized(lock) {
                    if (state == State.STOPPING) return
                    setState(State.RUNNING)
                }
                onLog("WebUI 就绪: ${webUrl(port)}")
                return
            }
            if (!p.isAlive) {
                onLog("启动进程已退出, 端口 $port 未就绪, 请查看上方日志排查")
                synchronized(lock) {
                    if (state == State.STARTING) setState(State.IDLE)
                }
                return
            }
            try {
                Thread.sleep(700)
            } catch (_: InterruptedException) {
                return
            }
        }
        onLog("等待 $port 端口就绪超时 (120s), dsh 可能未启动成功")
        synchronized(lock) {
            if (state == State.STARTING) setState(State.IDLE)
        }
    }

    /**
     * 停止 dsh (只停本插件启动的进程, 外部同端口 dsh 不受影响)。
     * 状态变化通过 [addStateListener] 注册的监听器广播给所有面板。
     *  - WSL 模式: 按 PID 标识文件 kill
     *  - Windows 模式: taskkill 终止本插件持有的进程树
     */
    fun stop(onLog: (String) -> Unit) {
        synchronized(lock) {
            if (state == State.IDLE || state == State.STOPPING) {
                onLog("当前没有运行中的 dsh (state=${state.name})")
                return
            }
            setState(State.STOPPING)
            Thread({
                try {
                    val stopPath = stopCmdPath
                    when {
                        stopPath == null && process == null -> {
                            // 端口上的服务不是本插件启动的 (例如外部 ldsh.cmd 已占用), 不越界停止
                            onLog("该端口的 dsh 不是由本插件启动, 不执行停止 (如需停止请自行处理)")
                        }
                        stopPath != null -> {
                            runStopScript(stopPath, onLog)
                            Thread.sleep(500)
                            onLog("已发送停止信号 (仅本插件启动的 dsh)")
                        }
                        else -> {
                            onLog("无停止脚本, 尝试直接销毁包装进程")
                        }
                    }
                } catch (t: Throwable) {
                    LOG.warn("stop dsh failed", t)
                    onLog("停止过程出错: ${t.message}")
                } finally {
                    try {
                        process?.destroy()
                    } catch (_: Exception) {
                    }
                    process = null
                    stopCmdPath = null
                    ownerPidFileWsl = null
                    setState(State.IDLE)
                }
            }, "dsh-plugin-stop").apply { isDaemon = true }.start()
        }
    }

    /**
     * 预生成停止脚本并返回其路径 (按进程标识, 只停本插件启动的 dsh)。
     *  - WSL 模式: 读取 PID 标识文件, kill 该 PID (外部同端口 dsh 不受影响)
     *  - Windows 模式: taskkill 终止本插件持有的启动包装进程树
     */
    private fun prepareStopScript(port: Int, mode: String): String {
        return if (mode == "windows") {
            val pid = process?.pid() ?: 0L
            val cmd = WslSupport.createTempFile("dsh-stop-", ".cmd")
            WslSupport.writeTextFile(cmd, "@echo off\r\ntaskkill /PID $pid /T /F >nul 2>&1\r\n", crlf = true)
            cmd.absolutePath
        } else {
            val stopSh = WslSupport.createTempFile("dsh-stop-", ".sh")
            val stopShWsl = WslSupport.toWslPath(stopSh.absolutePath) ?: stopSh.absolutePath
            val pidFileWsl = ownerPidFileWsl
            val body = if (pidFileWsl != null) {
                // PID 文件内容: 普通 PID (全局 dsh 精确停止) 或 "-<pid>" (npx 会话, 按进程组停止)
                "RAW=\"\$(cat ${WslSupport.shellSingleQuote(pidFileWsl)} 2>/dev/null)\"\n" +
                    "if [ -n \"\$RAW\" ]; then\n" +
                    "  case \"\$RAW\" in\n" +
                    "    -*) DPID=\"\${RAW#-}\"; kill -- \"-\$DPID\" 2>/dev/null || true; sleep 1; kill -9 -- \"-\$DPID\" 2>/dev/null || true ;;\n" +
                    "    *)  DPID=\"\$RAW\"; kill \"\$DPID\" 2>/dev/null || true; sleep 1; kill -9 \"\$DPID\" 2>/dev/null || true ;;\n" +
                    "  esac\n" +
                    "fi\n"
            } else {
                "# 无本插件启动的 dsh 进程标识, 不做任何停止\n"
            }
            WslSupport.writeTextFile(stopSh, body)
            val cmd = WslSupport.createTempFile("dsh-stop-", ".cmd")
            WslSupport.writeTextFile(
                cmd,
                "@echo off\r\nsetlocal\r\nwsl.exe bash -lc \"bash '${stopShWsl}'\"\r\n",
                crlf = true
            )
            cmd.absolutePath
        }
    }

    /** 通过隐藏 PowerShell 执行停止脚本 (非 Windows 直接 bash) */
    private fun runStopScript(stopPath: String, onLog: (String) -> Unit) {
        try {
            val pb = if (WslSupport.isWindows) {
                ProcessBuilder(
                    "powershell.exe", "-NoProfile", "-ExecutionPolicy", "Bypass",
                    "-WindowStyle", "Hidden",
                    "-Command", "& '${stopPath}'",
                )
            } else {
                ProcessBuilder("bash", stopPath)
            }
            pb.start().waitFor(15, java.util.concurrent.TimeUnit.SECONDS)
        } catch (t: Throwable) {
            LOG.warn("run stop script failed", t)
            onLog("停止脚本执行失败: ${t.message}")
        }
    }

    /**
     * 退出看门狗: 独立的隐藏 PowerShell 进程。
     *  - 若 IDE 进程消失 (正常退出/强杀/崩溃) 且端口仍开 → 执行停止脚本
     *  - 若端口已关闭 (正常停止过) → 自行退出, 不残留
     * 停止脚本按进程标识执行, 只会停掉本插件启动的 dsh。
     */
    private fun startExitWatchdog(stopPath: String, port: Int) {
        try {
            val idePid = ProcessHandle.current().pid()
            val script = buildString {
                append("\$ppid=$idePid; \$stop='$stopPath'; \$port=$port; ")
                append("while(\$true){ ")
                append("if(-not (Get-Process -Id \$ppid -ErrorAction SilentlyContinue)){ & \$stop | Out-Null; exit }; ")
                append("\$ok=\$false; ")
                append("foreach(\$h in @('localhost','127.0.0.1','::1')){ ")
                append("try{ \$c=New-Object Net.Sockets.TcpClient; \$c.Connect(\$h,\$port); \$c.Close(); \$ok=\$true; break }catch{} ")
                append("}; ")
                append("if(-not \$ok){ exit }; ")
                append("Start-Sleep -Seconds 5 ")
                append("}")
            }
            ProcessBuilder(
                "powershell.exe", "-NoProfile", "-WindowStyle", "Hidden",
                "-Command", script,
            ).start()
        } catch (t: Throwable) {
            LOG.warn("start exit watchdog failed", t)
        }
    }

    /**
     * 异步的退出清理: 只停本插件启动的 dsh, 不阻塞 IDEA 退出。
     * appClosing 与 JVM shutdown hook 都会调用; 即使本线程在 JVM 停止时被中断,
     * 独立看门狗进程也会在 IDE 进程消失后完成清理。
     */
    private fun stopOnExit() {
        try {
            val stopPath = stopCmdPath ?: return // 没有本插件启动的进程标识 -> 外部 dsh, 不动
            Thread({
                try {
                    val pb = if (WslSupport.isWindows) {
                        ProcessBuilder(
                            "powershell.exe", "-NoProfile", "-WindowStyle", "Hidden",
                            "-Command", "& '${stopPath}'",
                        )
                    } else {
                        ProcessBuilder("bash", stopPath)
                    }
                    pb.start().waitFor(15, java.util.concurrent.TimeUnit.SECONDS)
                    try {
                        process?.destroy()
                    } catch (_: Throwable) {
                    }
                } catch (_: Throwable) {
                }
            }, "dsh-plugin-exit-cleanup").apply { isDaemon = true }.start()
        } catch (_: Throwable) {
        }
    }
}