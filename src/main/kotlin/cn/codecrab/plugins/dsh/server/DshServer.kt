package cn.codecrab.plugins.dsh.server

import cn.codecrab.plugins.dsh.DshBundle
import cn.codecrab.plugins.dsh.settings.DshSettingsState
import cn.codecrab.plugins.dsh.util.WslSupport
import cn.codecrab.plugins.dsh.workspace.DshWorkspaceApi
import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import java.io.File

/**
 * dsh 进程管理器 (**每个 IDE 窗口一个实例**: 项目级服务)。
 *
 * 为什么一个窗口一个进程: dsh 的"当前工作空间/当前会话"只存在于浏览器 localStorage
 * (`dsh.sessions.current`, 按 origin 隔离), 多个窗口共用一个端口时这份状态是共享的 ——
 * 刷新一个窗口, 其他窗口的页面脚本会判定"自己不在该在的会话上"而跟着重载,
 * 最后全落到同一个页面。每个窗口各用一个端口 (各自一个 dsh 进程) 后, 各窗口的页面状态
 * 与认证完全隔离, 互不影响。
 *
 * 支持两种启动方式 (见设置):
 *  - WSL 模式: 通过 `wsl.exe bash` 在 WSL 中运行 dsh (通用化: 自动加载用户 ~/.bashrc,
 *    因此 nvm/常规 PATH 安装的 dsh 都能直接用, 不再绑定任何本机路径)
 *  - Windows 模式: 直接在 Windows 上以隐藏窗口运行 `dsh web`
 *
 * 进程隐藏后台运行 (PowerShell -WindowStyle Hidden 包裹, 不弹控制台窗口),
 * 输出捕获到工具窗口日志面板, 轮询端口等待 WebUI 就绪。
 *
 * 生命周期:
 *  - **关闭项目窗口**: [dispose] 立即停止本窗口自己的 dsh (释放进程, 不影响其他窗口);
 *  - **IDE 退出**: [DshServers.stopAll] 统一停止所有由本插件启动的 dsh
 *    (AppLifecycleListener.appClosing + JVM shutdown hook 两层);
 *  - **兜底**: 每个进程启动时都会拉起独立看门狗 —— 监视 IDE 进程, IDE 消失且端口仍开时
 *    自动执行停止脚本 (即使 IDE 被强杀/崩溃, dsh 也会被清理)。
 */
@Service(Service.Level.PROJECT)
class DshServer(private val project: Project) : Disposable {

    private val LOG = Logger.getInstance(DshServer::class.java)

    enum class State { IDLE, STARTING, RUNNING, STOPPING }

    /** 本窗口 dsh 的状态 (每个窗口各自独立: 一个窗口启停不影响其他窗口) */
    @Volatile
    var state: State = State.IDLE
        private set

    /**
     * 本窗口 dsh 的端口 (由 [DshServers] 分配: 设置里的基础端口优先, 已被其他窗口占用时自动顺延)。
     * 端口同时是页面的 origin, 决定本窗口的 localStorage/cookie 与其他窗口隔离 ——
     * 这是"每个窗口互相独立"的根: 换端口就是换 origin, 页面状态不再与他人共用一份。
     */
    @Volatile
    var port: Int = 0
        private set

    /** 本窗口的项目根路径 (作为 dsh 的工作目录与工作空间) */
    val projectPath: String?
        get() = project.basePath

    /** 状态监听器: 本窗口的面板注册后, 只广播本窗口的状态变化 */
    private val stateListeners = java.util.concurrent.CopyOnWriteArrayList<(State) -> Unit>()

    /**
     * 注册状态监听 (本窗口的工具窗口面板创建时调用)。
     * 注册后立即以当前状态回调一次, 便于新面板初始化 UI (dsh 可能已在本窗口启动过);
     * 之后的状态变化只广播给本窗口的面板。
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
        // 端口在实例创建时先分配好 (面板/预热/文件同步都直接读它);
        // 真正启动时还会再确认一次空闲 (见 [assignFreePort])
        val allocated = DshServers.allocatePort(this)
        port = if (allocated in 1..65535) allocated else DshSettingsState.getInstance().currentPort()
        DshServers.register(project, this)
    }


    /**
     * 启动本窗口自己的 dsh。
     * 状态变化通过 [addStateListener] 注册的监听器广播给本窗口的面板。
     * 端口上已有 dsh (上次会话遗留 / 用户手动启动) 时**不复用**, 直接换一个空闲端口自己启动 ——
     * 借用别人的实例就等于共用页面状态, 窗口之间必然互相影响。
     *
     * @param onLog 日志回调 (任意线程, 调用方负责切到 EDT)
     * @return true 表示真正发起了一次启动尝试 (可能随后同步失败, 调用方可据此标记"启动失败"通知);
     *         false 表示未发起 (已在运行 / 找不到空闲端口)
     */
    fun start(onLog: (String) -> Unit): Boolean {
        val settings = DshSettingsState.getInstance()
        val configured = settings.currentPort()
        if (configured !in 1..65535) {
            onLog(DshBundle.message("server.log.invalidPort", configured.toString(), settings.launchMode))
            return false
        }
        var port = 0
        synchronized(lock) {
            if (state == State.STARTING || state == State.RUNNING) {
                onLog(DshBundle.message("server.log.alreadyRunning", state.name))
                return false
            }
            if (!assignFreePort(onLog)) return false
            port = this.port
            setState(State.STARTING)
        }
        // 启动放到后台线程: 生成脚本/拉起进程/端口轮询等都可能阻塞较久,
        // 不能卡在 EDT; 真正拉起进程前会再检查 state 是否仍是 STARTING (用户可能已点停止)。
        Thread({
            try {
                launch(projectPath, settings.launchMode, port, settings.currentExtraArgs().trim(), onLog)
            } catch (t: Throwable) {
                LOG.warn("launch dsh failed", t)
                onLog(DshBundle.message("server.log.startFailed", t.message ?: "null"))
                setState(State.IDLE)
            }
        }, "dsh-plugin-launch").apply { isDaemon = true }.start()
        return true
    }

    /** 启动线程在真正干活前检查: 状态仍为 STARTING 才继续 (用户可能已点停止) */
    private fun ensureStillStarting(): Boolean = synchronized(lock) { state == State.STARTING }

    private fun launch(
        projectPath: String?,
        mode: String,
        port: Int,
        extraArgs: String,
        onLog: (String) -> Unit,
    ) {
        if (!ensureStillStarting()) {
            onLog(DshBundle.message("server.log.startCancelled"))
            return
        }
        // 新进程会产生新的启动令牌: 清空上一进程的认证状态
        DshWebAuth.reset(port)
        // 同时作废按端口缓存的 RPC 风格与"认证被拒"标记 (新进程重新探测)
        DshWorkspaceApi.invalidateStyle(port)
        when (mode) {
            DshSettingsState.MODE_WINDOWS -> launchOnWindows(projectPath, port, extraArgs, onLog)
            else -> {
                if (!WslSupport.isWindows) {
                    onLog(DshBundle.message("server.log.wslModeUnavailable"))
                    setState(State.IDLE)
                    return
                }
                launchOnWsl(projectPath, port, extraArgs, onLog)
            }
        }
    }

    // ---------- WSL 启动 ----------

    private fun launchOnWsl(
        projectPath: String?,
        port: Int,
        extra: String,
        onLog: (String) -> Unit,
    ) {
        val wslProject = WslSupport.toWslPath(projectPath)

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
                appendLine("  echo \"${DshBundle.message("script.projectMissing", WslSupport.shellSingleQuote(wslProject))}\"")
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
            appendLine("  echo \"${DshBundle.message("script.noNodeEnv")}\"")
            appendLine("  echo \"${DshBundle.message("script.nodeInstallWsl")}\"")
            appendLine("  exit 1")
            appendLine("fi")
            // 优先使用全局安装的 dsh; 未安装时兼容官方启动命令 npx @deepseek-ai/dsh web (首次运行自动下载)
            appendLine("if command -v dsh >/dev/null 2>&1; then")
            appendLine("  DSH_RUN=(dsh)")
            appendLine("  echo \"${DshBundle.message("script.globalDshFound")}\"")
            appendLine("elif command -v npx >/dev/null 2>&1; then")
            appendLine("  DSH_RUN=(npx --yes @deepseek-ai/dsh)")
            appendLine("  echo \"${DshBundle.message("script.npxFallback")}\"")
            appendLine("else")
            appendLine("  echo \"${DshBundle.message("script.dshMissing")}\"")
            appendLine("  echo \"${DshBundle.message("script.installCommandWsl")}\"")
            appendLine("  exit 1")
            appendLine("fi")
            appendLine("echo \"${DshBundle.message("script.startingWeb", wslProject ?: "\$HOME", port.toString())}\"")
            // 后台运行并记录 PID: 全局 dsh 记录真实 PID 精确停止; npx 模式用 setsid 独立会话,
            // PID 文件写入 "-<pid>" 表示按进程组停止 (npx 内部还会派生 node/dsh 子进程)
            // --no-open: dsh web 默认会用系统浏览器打开 UI (与插件内嵌浏览器冲突), 固定附加
            val launchArgs = buildString {
                append("web --port $port --no-open")
                if (extra.isNotEmpty()) append(" $extra")
            }.toString()
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
        onLog(DshBundle.message("server.log.scriptGenerated", shFile.name))

        val cmdFile = WslSupport.createTempFile("dsh-run-wsl-", ".cmd")
        // bash -lic: 登录 + 交互, 完整加载用户 shell 配置 (nvm 依赖交互式初始化)
        WslSupport.writeTextFile(
            cmdFile,
            "@echo off\r\nsetlocal\r\nwsl.exe bash -lic \"bash ${WslSupport.shellSingleQuote(shWslPath)}\"\r\n",
            crlf = true
        )
        onLog(DshBundle.message("server.log.wslWorkdir", wslProject ?: DshBundle.message("server.param.wslHome")))
        startWrapper(cmdFile, onLog, port, DshSettingsState.MODE_WSL)
    }

    // ---------- Windows 直接启动 ----------

    private fun launchOnWindows(
        projectPath: String?,
        port: Int,
        extra: String,
        onLog: (String) -> Unit,
    ) {
        // --no-open: dsh web 默认会用系统浏览器打开 UI, 与插件内嵌浏览器冲突, 固定附加
        if (!WslSupport.isWindows) {
            // macOS/Linux: 直接用 bash 启动
            val shFile = WslSupport.createTempFile("dsh-run-posix-", ".sh")
            val cwd = projectPath?.let { if (File(it).isDirectory) it else null }
            val content = buildString {
                appendLine("#!/usr/bin/env bash")
                if (cwd != null) appendLine("cd ${WslSupport.shellSingleQuote(cwd)} 2>/dev/null || exit 1")
                // 环境检测: 无 Node.js 环境时直接失败, 由插件提示安装
                appendLine("if ! command -v node >/dev/null 2>&1 && ! command -v npx >/dev/null 2>&1; then")
                appendLine("  echo \"${DshBundle.message("script.noNodeEnv")}\"")
                appendLine("  echo \"${DshBundle.message("script.nodeInstallPosix")}\"")
                appendLine("  exit 1")
                appendLine("fi")
                // 优先全局 dsh; 未安装时兼容官方启动命令 npx @deepseek-ai/dsh web
                appendLine("if command -v dsh >/dev/null 2>&1; then")
                if (extra.isNotEmpty()) appendLine("  exec dsh web --port $port --no-open $extra") else appendLine("  exec dsh web --port $port --no-open")
                appendLine("elif command -v npx >/dev/null 2>&1; then")
                appendLine("  echo \"${DshBundle.message("script.npxFallback")}\"")
                if (extra.isNotEmpty()) {
                    appendLine("  exec npx --yes @deepseek-ai/dsh web --port $port --no-open $extra")
                } else {
                    appendLine("  exec npx --yes @deepseek-ai/dsh web --port $port --no-open")
                }
                appendLine("else")
                appendLine("  echo \"${DshBundle.message("script.dshMissing")}\"")
                appendLine("  echo \"${DshBundle.message("script.installCommandPlain")}\"")
                appendLine("  exit 1")
                appendLine("fi")
            }
            WslSupport.writeTextFile(shFile, content)
            if (!ensureStillStarting()) {
                onLog(DshBundle.message("server.log.startCancelled"))
                return
            }
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
            drain(p, port, onLog)
            watch(p, port, onLog)
            waitForReadyInThread(port, p, onLog)
            return
        }

        val cmdFile = WslSupport.createTempFile("dsh-run-win-", ".cmd")
        val content = buildString {
            appendLine("@echo off")
            appendLine("setlocal")
            // 统一子进程输出编码为 UTF-8: 不加此行时 cmd.exe 自身消息 (含本地化错误)
            // 按系统 OEM 代码页 (如中文系统 GBK) 输出, 插件按 UTF-8 读取即乱码
            appendLine("chcp 65001 >nul 2>&1")
            if (projectPath != null && File(projectPath).isDirectory) {
                appendLine("cd /d \"$projectPath\"")
            }
            // 注意: 用 goto 结构而非括号块, echo 消息里也不含圆括号 ——
            // cmd 解析括号块时会把消息里的 ')' 当作块结束, 导致 "xxx was unexpected" 解析错误
            // 检查顺序: 先 dsh (有全局 dsh 就不需要 npx), 再 npx (官方命令兼容), 两者皆无才报错
            appendLine("where dsh >nul 2>&1")
            appendLine("if not errorlevel 1 goto :dsh_ok")
            appendLine("where npx >nul 2>&1")
            appendLine("if not errorlevel 1 goto :npx_ok")
            appendLine("echo ${DshBundle.message("script.cmdErrNoNode")}")
            appendLine("echo ${DshBundle.message("script.cmdInstallHint")}")
            appendLine("exit /b 1")
            appendLine(":npx_ok")
            appendLine("echo ${DshBundle.message("script.cmdNpxFallback")}")
            appendLine("npx --yes @deepseek-ai/dsh web --port $port --no-open${if (extra.isNotEmpty()) " $extra" else ""}")
            appendLine("exit /b %ERRORLEVEL%")
            appendLine(":dsh_ok")
            if (extra.isNotEmpty()) {
                appendLine("dsh web --port $port --no-open $extra")
            } else {
                appendLine("dsh web --port $port --no-open")
            }
        }
        WslSupport.writeTextFile(cmdFile, content, crlf = true)
        onLog(DshBundle.message("server.log.cmdGenerated", cmdFile.name))
        onLog(
            DshBundle.message(
                "server.log.workdir",
                if (projectPath != null && File(projectPath).isDirectory) projectPath
                else DshBundle.message("server.param.default")
            )
        )
        startWrapper(cmdFile, onLog, port, DshSettingsState.MODE_WINDOWS)
    }

    /** 通过隐藏的 PowerShell 执行 .cmd, 并挂上输出/进程 watching 和端口轮询 */
    private fun startWrapper(cmdFile: File, onLog: (String) -> Unit, port: Int, mode: String) {
        val launchCmd = listOf(
            "powershell.exe",
            "-NoProfile",
            "-ExecutionPolicy", "Bypass",
            "-WindowStyle", "Hidden",
            // 同样把 PowerShell 自身的输出 (如本地化错误消息) 统一为 UTF-8, 避免乱码
            "-Command", "[Console]::OutputEncoding = [System.Text.Encoding]::UTF8; & '${cmdFile.absolutePath}'",
        )
        onLog(DshBundle.message("server.log.startCommand", cmdFile.name))
        if (!ensureStillStarting()) {
            onLog(DshBundle.message("server.log.startCancelled"))
            return
        }
        val p = ProcessBuilder(launchCmd).start()
        process = p
        // 预生成停止脚本 + 启动退出看门狗, 保证 IDE 无论怎么退出 dsh 都能被清理
        stopCmdPath = prepareStopScript(port, mode)
        startExitWatchdog(stopCmdPath!!, port)
        drain(p, port, onLog)
        watch(p, port, onLog)
        waitForReadyInThread(port, p, onLog)
    }

    // ---------- 输出 / 生命周期 ----------

    private fun drain(p: Process, port: Int, onLog: (String) -> Unit) {
        fun reader(stream: java.io.InputStream, tag: String) {
            Thread({
                try {
                    stream.bufferedReader(Charsets.UTF_8).useLines { lines ->
                        for (line in lines) {
                            // 新版 dsh 启动时打印带 token 的 WebUI 地址, 捕获令牌供浏览器/API 认证
                            DshWebAuth.captureFromLogOutput(port, line)
                            onLog("$tag $line")
                        }
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
                        onLog(DshBundle.message("server.log.wrapperExitedPortAlive", code.toString(), port.toString()))
                    } else {
                        onLog(DshBundle.message("server.log.dshExited", code.toString()))
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
                onLog(DshBundle.message("server.log.webuiReady", webUrl(port)))
                return
            }
            if (!p.isAlive) {
                onLog(DshBundle.message("server.log.exitPortNotReady", port.toString()))
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
        onLog(DshBundle.message("server.log.readyTimeout", port.toString()))
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
                onLog(DshBundle.message("server.log.noRunningDsh", state.name))
                return
            }
            setState(State.STOPPING)
            Thread({
                try {
                    val stopPath = stopCmdPath
                    when {
                        stopPath == null && process == null -> {
                            // 端口上的服务不是本插件启动的 (例如外部 ldsh.cmd 已占用), 不越界停止
                            onLog(DshBundle.message("server.log.externalNotStopped"))
                        }
                        stopPath != null -> {
                            runStopScript(stopPath, onLog)
                            Thread.sleep(500)
                            onLog(DshBundle.message("server.log.stopSignalSent"))
                        }
                        else -> {
                            onLog(DshBundle.message("server.log.noStopScript"))
                        }
                    }
                } catch (t: Throwable) {
                    LOG.warn("stop dsh failed", t)
                    onLog(DshBundle.message("server.log.stopError", t.message ?: "null"))
                } finally {
                    try {
                        process?.destroy()
                    } catch (_: Exception) {
                    }
                    process = null
                    stopCmdPath = null
                    ownerPidFileWsl = null
                    // dsh 已停止: 清空浏览器认证状态 (新进程会打印新的启动令牌)
                    DshWebAuth.reset(port)
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
        return if (mode == DshSettingsState.MODE_WINDOWS) {
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
                DshBundle.message("script.stopNoOwner") + "\n"
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
            onLog(DshBundle.message("server.log.stopScriptFailed", t.message ?: "null"))
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
     * 异步清理: 只停本插件启动的 dsh (不是本插件启动的进程一律不动), 不阻塞调用方。
     * 关闭本项目窗口 ([dispose]) 与 IDE 退出 ([DshServers.stopAll]) 都会调用;
     * 即使本线程在 JVM 停止时被中断, 独立看门狗进程也会在 IDE 进程消失后完成清理。
     */
    fun stopForExit() {
        val stopPath = stopCmdPath ?: return // 没有本插件启动的进程标识 -> 外部 dsh, 不动
        stopCmdPath = null // 幂等: 关窗清理与 IDE 退出清理可能都会走到这里
        try {
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
                    DshWebAuth.reset(port)
                } catch (_: Throwable) {
                }
            }, "dsh-plugin-exit-cleanup").apply { isDaemon = true }.start()
        } catch (_: Throwable) {
        }
    }

    /**
     * 项目窗口关闭: 注销实例并停止本窗口自己的 dsh —— 每个窗口一个实例, 关窗即释放,
     * 否则关掉的窗口会一直留着一个 node 进程 (IDE 退出时还有 [DshServers.stopAll] 兜底)。
     */
    override fun dispose() {
        DshServers.unregister(project)
        stopForExit()
    }

    /**
     * 落实这次启动用的端口: 分配一个**空闲**端口, 端口已被监听时不复用、直接换下一个。
     * 分配本身就会跳过被监听的端口, 这里再确认一次是为了堵住"分配后到启动前被别人抢占"的极小窗口。
     *
     * @return false = 找不到空闲端口 (已记日志提示换起始端口或先停掉占用者), 不发起启动
     */
    private fun assignFreePort(onLog: (String) -> Unit): Boolean {
        repeat(PORT_ASSIGN_ATTEMPTS) {
            val allocated = DshServers.allocatePort(this)
            if (allocated in 1..65535 && !WslSupport.isPortOpen(allocated)) {
                port = allocated
                return true
            }
            if (allocated in 1..65535) {
                onLog(DshBundle.message("server.log.portBusyRetry", allocated.toString()))
            }
        }
        onLog(DshBundle.message("server.log.noFreePort", DshSettingsState.getInstance().currentPort().toString()))
        return false
    }

    companion object {

        /** 端口分配的重试次数 (分配后到启动前的抢占窗口极小, 重试几次足够) */
        private const val PORT_ASSIGN_ATTEMPTS = 2

        /** 本窗口的实例 (项目级服务) */
        fun getInstance(project: Project): DshServer = project.service()

        fun webUrl(port: Int): String = "http://localhost:" + port + "/"

        /** WebUI 地址 (带该端口 dsh 进程的启动令牌, 用于通过新版 dsh 的浏览器认证); 令牌未捕获时返回 null */
        fun webTokenUrl(port: Int): String? = DshWebAuth.webTokenUrl(port)
    }
}
