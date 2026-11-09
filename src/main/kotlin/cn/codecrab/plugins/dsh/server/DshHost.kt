package cn.codecrab.plugins.dsh.server

import cn.codecrab.plugins.dsh.DshBundle
import cn.codecrab.plugins.dsh.settings.DshSettingsState
import cn.codecrab.plugins.dsh.util.WslSupport
import com.intellij.openapi.diagnostic.Logger
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

/** dsh 进程的生命周期状态 (进程级; 各窗口看到的是同一份) */
enum class DshState { IDLE, STARTING, RUNNING, STOPPING }

/**
 * IDE 内**共用**的 dsh 进程 (应用级单例): 一个 IDE 只起一个 `dsh web`, 各窗口通过
 * [DshPortRelay] 用各自端口访问它。
 *
 * ## 为什么不每个窗口一个进程
 *
 * dsh 的磁盘状态不是多进程安全的: 工作空间注册表 (`~/.dsh/storages/workspace.json`) 是
 * "进程启动时读入内存, 之后每次变更整份覆写", 且没有文件监听/合并 —— 它假定一个 home 只有
 * 一个写者。每个窗口一个进程时, 后写者用自己那份陈旧快照覆盖注册表, 会抹掉别的窗口刚建的
 * 工作空间/会话, 会话于是失去归属、显示到"未分组" (两个进程各注册一个项目即可稳定复现:
 * 后者的写入会把前者从注册表里整条删掉)。唯一彻底的解法是同一时刻只有一个进程在写。
 *
 * ## 为什么还要每窗口一个端口
 *
 * dsh 的"当前工作空间/当前会话"存在浏览器侧 (`dsh.sessions.current`), 而浏览器本地存储按
 * **origin (协议+主机+端口)** 隔离。所有窗口共用一个端口时, 它们读写同一份选择, 会互相把对方
 * 拉到自己的会话上、来回重载 (1.2.x 的"窗口打架")。
 *
 * 两者拆开就同时成立: **端口负责页面状态隔离, 进程负责注册表单写者** —— 各窗口用自己的端口
 * ([DshServer.port] -> [DshPortRelay]) 访问同一个进程 ([port])。
 *
 * ## 生命周期
 *
 *  - 任一窗口需要时 [start]; 进程已在运行则直接复用, 不重复拉起;
 *  - 任一窗口点"停止" = 停掉整个 IDE 的 dsh: 所有窗口一起停, 停之前广播一行日志说明;
 *  - 最后一个使用它的窗口关闭时停掉 (见 [DshServers.unregister]); IDE 退出时由
 *    [DshServers.stopAll] 兜底;
 *  - 每个进程带独立退出看门狗: 即使 IDE 被强杀/崩溃, dsh 也会被清理。
 *
 * 设置 (启动方式/端口/附加参数) 对所有窗口是同一份: 改动后需要先停止再启动 ([start] 遇到
 * "已在运行"只会记一行日志, 不会自动重启)。
 */
internal object DshHost {

    private val LOG = Logger.getInstance(DshHost::class.java)

    /** 进程状态 (各窗口的门面 [DshServer] 镜像它) */
    @Volatile
    var state: DshState = DshState.IDLE
        private set

    /**
     * 进程实际监听的端口: 页面**不**直接用 (页面走各窗口的转发端口), 只用于健康检查,
     * 以及让新窗口的转发端口避开它。
     */
    @Volatile
    var port: Int = 0
        private set

    /** 各窗口的状态监听 (窗口门面注册, 关窗时注销) */
    private val stateListeners = CopyOnWriteArrayList<(DshState) -> Unit>()

    /** 各窗口的日志接收者 (dsh 进程输出广播给所有打开的窗口) */
    private val logSinks = CopyOnWriteArrayList<(String) -> Unit>()

    private val lock = Any()

    @Volatile
    private var process: Process? = null

    /** 启动时预生成的停止脚本路径 (WSL: kill PID; Windows: taskkill), 退出清理复用 */
    @Volatile
    private var stopCmdPath: String? = null

    /** 本插件启动的 dsh 的 PID 标识文件 (WSL 路径); 为 null 表示没有由本插件启动的 dsh */
    @Volatile
    private var ownerPidFileWsl: String? = null

    /** 注册状态监听: 注册后立即用当前状态回调一次 (窗口门面据此接管已在运行的进程) */
    fun addStateListener(listener: (DshState) -> Unit) {
        stateListeners.add(listener)
        listener(state)
    }

    /** 注销状态监听 (窗口门面销毁时调用) */
    fun removeStateListener(listener: (DshState) -> Unit) {
        stateListeners.remove(listener)
    }

    /** 注册日志接收者: dsh 进程输出会广播给所有打开的窗口 */
    fun addLogSink(sink: (String) -> Unit) {
        logSinks.add(sink)
    }

    /** 注销日志接收者 */
    fun removeLogSink(sink: (String) -> Unit) {
        logSinks.remove(sink)
    }

    /** 向所有窗口广播一行日志 (任意线程可调用; 接收方负责切到 EDT) */
    fun log(line: String) {
        for (sink in logSinks) {
            try {
                sink.invoke(line)
            } catch (t: Throwable) {
                LOG.warn("dsh log sink failed", t)
            }
        }
    }

    /** 统一的状态变更入口: 更新状态并广播给所有窗口门面 */
    private fun setState(newState: DshState) {
        state = newState
        notifyState()
    }

    /** 广播当前状态 (调用方已在锁内改好 [state] 时用它) */
    private fun notifyState() {
        for (listener in stateListeners) {
            try {
                listener(state)
            } catch (t: Throwable) {
                LOG.warn("dsh state listener failed", t)
            }
        }
    }

    /**
     * 启动共用 dsh (已在运行/启动中则只记日志)。
     *
     * @param projectPath 进程工作目录 (第一个发起启动的窗口的项目根); 会话各自带 cwd, 这里只影响
     *   没有指定工作空间/目录的新会话
     * @return true 表示真正发起了一次启动尝试 (窗口可据此弹启动失败通知);
     *         false 表示未发起 (已在运行 / 找不到空闲端口)
     */
    fun start(projectPath: String?): Boolean {
        val settings = DshSettingsState.getInstance()
        val configured = settings.currentPort()
        if (configured !in 1..65535) {
            log(DshBundle.message("server.log.invalidPort", configured.toString(), settings.launchMode))
            return false
        }
        synchronized(lock) {
            // 只有空闲时才能真正启动 (启动中/运行中/停止中都不重复发起)
            if (state != DshState.IDLE) {
                log(DshBundle.message("server.log.alreadyRunning", state.name))
                return false
            }
            val allocated = DshServers.allocateProcessPort()
            if (allocated !in 1..65535) {
                log(DshBundle.message("server.log.noFreePort", configured.toString()))
                return false
            }
            port = allocated
            // 在锁内占位: 两个窗口同时点启动/同时自动启动时, 只有一个能真正拉起进程
            state = DshState.STARTING
        }
        val listeningPort = port
        notifyState()
        Thread({
            try {
                launch(projectPath, settings.launchMode, listeningPort, settings.currentExtraArgs().trim())
            } catch (t: Throwable) {
                LOG.warn("launch dsh failed", t)
                log(DshBundle.message("server.log.startFailed", t.message ?: "null"))
                setState(DshState.IDLE)
            }
        }, "dsh-plugin-launch").apply { isDaemon = true }.start()
        return true
    }

    /** 启动线程在真正干活前检查: 状态仍为 STARTING 才继续 (用户可能已点停止) */
    private fun ensureStillStarting(): Boolean = synchronized(lock) { state == DshState.STARTING }

    private fun launch(
        projectPath: String?,
        mode: String,
        port: Int,
        extraArgs: String,
    ) {
        if (!ensureStillStarting()) {
            log(DshBundle.message("server.log.startCancelled"))
            return
        }
        // 新进程会产生新的启动令牌: 清空上一进程的认证状态 (各窗口转发端口在 RUNNING 时重新关联)
        DshWebAuth.reset(port)
        // 同时作废按转发端口缓存的 RPC 风格与主机名偏好 (新进程重新探测)
        DshServers.invalidateRelayCaches()
        when (mode) {
            DshSettingsState.MODE_WINDOWS -> launchOnWindows(projectPath, port, extraArgs, ::log)
            else -> {
                if (!WslSupport.isWindows) {
                    log(DshBundle.message("server.log.wslModeUnavailable"))
                    setState(DshState.IDLE)
                    return
                }
                launchOnWsl(projectPath, port, extraArgs, ::log)
            }
        }
    }

    /**
     * 停止共用 dsh (只停本插件启动的进程, 外部同端口 dsh 不受影响)。
     * 所有窗口共用这一个进程, 因此停止是**整个 IDE** 的操作: 先广播一行说明再停。
     */
    fun stop() {
        synchronized(lock) {
            if (state == DshState.IDLE || state == DshState.STOPPING) {
                log(DshBundle.message("server.log.noRunningDsh", state.name))
                return
            }
            // 在锁内占位: 连点两次"停止"不会启动两条停止流程
            state = DshState.STOPPING
        }
        log(DshBundle.message("server.log.sharedStop"))
        notifyState()
        Thread({ runStop() }, "dsh-plugin-stop").apply { isDaemon = true }.start()
    }

    /** 实际停止 (后台线程): 按停止脚本/PID 标识停进程, 最后清空认证状态并回到 IDLE */
    private fun runStop() {
        try {
            val stopPath = stopCmdPath
            when {
                stopPath == null && process == null -> {
                    // 端口上的服务不是本插件启动的 (例如外部 ldsh.cmd 已占用), 不越界停止
                    log(DshBundle.message("server.log.externalNotStopped"))
                }
                stopPath != null -> {
                    runStopScript(stopPath, ::log)
                    Thread.sleep(500)
                    log(DshBundle.message("server.log.stopSignalSent"))
                }
                else -> {
                    log(DshBundle.message("server.log.noStopScript"))
                }
            }
        } catch (t: Throwable) {
            LOG.warn("stop dsh failed", t)
            log(DshBundle.message("server.log.stopError", t.message ?: "null"))
        } finally {
            try {
                process?.destroy()
            } catch (_: Exception) {
            }
            process = null
            stopCmdPath = null
            ownerPidFileWsl = null
            // dsh 已停止: 清空本进程端口的认证状态 (新进程会打印新的启动令牌)
            DshWebAuth.reset(port)
            setState(DshState.IDLE)
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
        stopCmdPath = prepareStopScript(mode)
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
                if (state == DshState.STARTING || state == DshState.RUNNING) {
                    if (WslSupport.isPortOpen(port)) {
                        onLog(DshBundle.message("server.log.wrapperExitedPortAlive", code.toString(), port.toString()))
                    } else {
                        onLog(DshBundle.message("server.log.dshExited", code.toString()))
                        synchronized(lock) {
                            if (state == DshState.STARTING || state == DshState.RUNNING) setState(DshState.IDLE)
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
                if (state == DshState.IDLE || state == DshState.STOPPING) return
            }
            if (WslSupport.isPortOpen(port)) {
                synchronized(lock) {
                    if (state == DshState.STOPPING) return
                    setState(DshState.RUNNING)
                }
                onLog(DshBundle.message("server.log.webuiReady", port.toString()))
                return
            }
            if (!p.isAlive) {
                onLog(DshBundle.message("server.log.exitPortNotReady", port.toString()))
                synchronized(lock) {
                    if (state == DshState.STARTING) setState(DshState.IDLE)
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
            if (state == DshState.STARTING) setState(DshState.IDLE)
        }
    }

    /**
     * 预生成停止脚本并返回其路径 (按进程标识, 只停本插件启动的 dsh)。
     *  - WSL 模式: 读取 PID 标识文件, kill 该 PID (外部同端口 dsh 不受影响)
     *  - Windows 模式: taskkill 终止本插件持有的启动包装进程树
     */
    private fun prepareStopScript(mode: String): String {
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
     * 所有窗口关闭 ([DshServer.dispose]) 与 IDE 退出 ([DshServers.stopAll]) 都会调用;
     * 即使本线程在 JVM 停止时被中断, 独立看门狗进程也会在 IDE 进程消失后完成清理。
     */
    fun stopForExit() {
        val stopPath = stopCmdPath ?: return // 没有本插件启动的进程标识 -> 外部 dsh, 不动
        stopCmdPath = null // 幂等: 关窗清理与 IDE 退出清理可能都会走到这里
        // 进程马上会被停掉: 各窗口先回到 IDLE, 免得之后新开的窗口看到过期的 RUNNING
        // (进程真的退出后 watch 还会再置一次 IDLE, 幂等)
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
        setState(DshState.IDLE)
    }
}
