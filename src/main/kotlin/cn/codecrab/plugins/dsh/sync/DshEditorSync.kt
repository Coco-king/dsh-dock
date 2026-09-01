package cn.codecrab.plugins.dsh.sync

import cn.codecrab.plugins.dsh.DshBundle
import cn.codecrab.plugins.dsh.server.DshWebAuth
import cn.codecrab.plugins.dsh.settings.DshSettingsState
import cn.codecrab.plugins.dsh.util.DshIdeName
import cn.codecrab.plugins.dsh.workspace.DshWorkspaceApi
import cn.codecrab.plugins.dsh.workspace.DshWorkspaceApi.RpcStyle
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import java.net.HttpURLConnection
import java.net.URI
import java.net.http.HttpClient
import java.net.http.WebSocket
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.util.LinkedHashMap
import java.util.concurrent.CompletionStage
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 监听 dsh 的会话事件, 把 dsh 的文件写入同步到 IDEA。
 *
 * 背景: dsh (尤其 WSL 模式) 编辑项目文件后, Windows 侧的文件变更通知经常不触发,
 * IDEA 的 VFS 不知道文件变了, 打开着的编辑器标签一直显示旧代码 (重新打开文件才更新)。
 * 本类监听 dsh 的**写入类工具事件** (`tool/call` / `tool/result`):
 * 当 dsh 的**文件写入类工具** (str_replace_editor / edit / write 等) 成功执行完时,
 * 定向刷新该文件的 VFS, 并重载打开的、无未保存修改的文档 —— 编辑器立即显示新代码。
 *
 * 传输按 dsh 版本自适应:
 *  1. 旧版事件流 (dsh ≤ 0.1.1):
 *     - SSE: `GET /api/events.mux`; WebSocket: `ws://127.0.0.1:<port>/api/events.mux`,
 *       不带 Origin 头 (经 loopback 信任围栏放行; 新版 dsh 的 /api 还需认证 cookie)
 *  2. 新版 follow 流 (dsh ≥ 0.1.2-alpha, events.mux 已移除): WebSocket `/api/remote.mux`
 *     上订阅 `session/follow` (每会话一流), 工具事件实时逐条到达 (见 [DshSessionFollow])。
 * 两类方式都不可用时静默降级 (只记一行日志), 绝不影响 WebUI 正常使用。
 *
 * 线程模型: 每个面板一个监听线程 (阻塞读流 / 轮询循环), 断线按退避自动重连;
 * 事件回调里只做解析与入队, 涉 VFS 的 I/O 在监听线程执行, 文档重载切到 EDT。
 */
class DshEditorSync(
    private val project: Project,
    private val port: Int,
    private val launchMode: String,
    private val onLog: (String) -> Unit,
) {

    private val LOG = Logger.getInstance(DshEditorSync::class.java)

    /** 设置为 false 即停 (面板销毁 / dsh 停止时调用) */
    private val running = AtomicBoolean(false)

    /** 当前 SSE 连接 (用于 stop 时断开读阻塞) */
    @Volatile
    private var sseConn: HttpURLConnection? = null

    /** 当前 WebSocket (用于 stop 时关闭) */
    @Volatile
    private var ws: WebSocket? = null

    /** 监听线程 */
    @Volatile
    private var thread: Thread? = null

    /** 本项目 windows 侧的路径 (正斜杠形态), WSL 远程项目 \\wsl$\<distro>\... 时用于推导 distro */
    private val ideaPathBase = project.basePath?.replace('\\', '/')

    /**
     * 进行中的写入工具调用: key = "$sessionId/$callId" -> dsh 侧文件路径。
     * 只记录文件写入类工具; `tool/result` 到达且成功后消费。上限 512, 自动淘汰最旧。
     */
    private val pendingCalls = object : LinkedHashMap<String, String>(64, 0.75f, false) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>): Boolean = size > 512
    }

    /** 连续连接失败次数 (达到上限则判定"当前 dsh 不支持事件流", 停止重试) */
    private var consecutiveFailures = 0

    /** follow 实时流 (新版 dsh 主通道); null = 尚未创建 */
    private var follow: DshSessionFollow? = null

    /** 最近一次刷新某路径的时刻 (ms), 用于同路径短窗口去重 */
    private val lastSyncAt = HashMap<String, Long>()

    /** 已在本周期输出过"连接成功"日志 (避免每次重连都刷) */
    private var connectedLogged = false

    /** 已输出过"消息流已建立"日志 (证明事件帧到达本插件) */
    private var subscribedLogged = false

    /** 断线重连的退避间隔 (毫秒) */
    private var reconnectBackoffMs = 0L

    fun start() {
        if (!running.compareAndSet(false, true)) return
        LOG.info("start editor sync for $port")
        // 重置上一周期的状态 (dsh 停止后再启动, start 会被再次调用)
        connectedLogged = false
        subscribedLogged = false
        consecutiveFailures = 0
        reconnectBackoffMs = 0
        follow = null
        pendingCalls.clear()
        val t = Thread({ runLoop() }, "dsh-plugin-editor-sync").apply { isDaemon = true }
        thread = t
        t.start()
    }

    fun stop() {
        running.set(false)
        reconnectBackoffMs = 0
        // 断开当前流, 让监听线程退出 (WS: sendClose 触发 onClose; SSE: disconnect 使读抛异常)
        try {
            ws?.sendClose(WebSocket.NORMAL_CLOSURE, "plugin stopped")
        } catch (_: Throwable) {
        }
        try {
            follow?.stop()
            follow = null
        } catch (_: Throwable) {
        }
        try {
            sseConn?.disconnect()
        } catch (_: Throwable) {
        }
        // 打断退避等待
        try {
            thread?.interrupt()
        } catch (_: Throwable) {
        }
    }

    /** 监听主循环: 先探测 dsh 通道风格 (新版 follow 实时流 / 旧版 events.mux), 有确定结果才定型 */
    private fun runLoop() {
        val style = probeRemoteStyleWithRetry()
        if (style == RpcStyle.REMOTE) {
            // 新版 dsh (0.1.2+): 无 events.mux, 会话事件唯一实时通道是 session/follow 流
            startFollow()
            while (running.get() && !project.isDisposed) {
                sleepQuietly(5000) // follow 内部自带断线退避重连, 本线程只维持存活
            }
            return
        }
        // 旧版 dsh: events.mux 事件流, 断线退避重连
        while (running.get() && !project.isDisposed) {
            if (reconnectBackoffMs > 0) {
                try {
                    Thread.sleep(reconnectBackoffMs)
                } catch (_: InterruptedException) {
                    return
                }
            }
            reconnectBackoffMs = 1000
            val ok = tryConnectAndStream()
            if (!running.get()) return
            consecutiveFailures = if (ok) 0 else consecutiveFailures + 1
            if (consecutiveFailures >= MAX_CONSECUTIVE_FAILURES) {
                throttledLog(DshBundle.message("sync.log.unsupported"))
                running.set(false)
                return
            }
            reconnectBackoffMs = minOf(reconnectBackoffMs * 2, 15_000L)
        }
    }

    /**
     * 探测 RPC 风格直到有确定结果。
     * 背景: dsh 刚启动时浏览器认证令牌可能晚几百毫秒才输出, 一次探测失败就把新版误判为旧版,
     * 会走 events.mux 空转并最终提示"不支持"停止 —— 更新后首次打开 IDE 即复现。这里退避重试:
     * 旧版 dsh 首轮即返回 LEGACY; 新版等令牌就绪后返回 REMOTE; 超时仍未定按旧版处理。
     */
    private fun probeRemoteStyleWithRetry(): RpcStyle? {
        var waited = 0L
        while (running.get() && !project.isDisposed) {
            val style = DshWorkspaceApi.rpcStyle(port) ?: run {
                // 触发一次探测 (探测内部会等待认证令牌并换取 cookie)
                DshWorkspaceApi.callJson(port, "session.list", JsonObject(), 3000)
                DshWorkspaceApi.rpcStyle(port)
            }
            if (style != null) return style
            waited += REMOTE_PROBE_RETRY_MS
            if (waited >= REMOTE_PROBE_MAX_WAIT_MS) return null
            if (!sleepQuietly(REMOTE_PROBE_RETRY_MS)) return null // 被 stop 打断
        }
        return null
    }

    /** 启动 follow 实时流 (内部自带断线重连与会话校准) */
    private fun startFollow() {
        if (follow != null) return
        follow = DshSessionFollow(port, ::handleFollowEvent)
        follow!!.start()
        onLog(DshBundle.message("sync.log.followMode"))
    }

    /** follow 事件回调: 快照 (event==null) 只建基线; 事件逐条处理 */
    private fun handleFollowEvent(sessionId: String, event: JsonObject?) {
        if (!running.get()) return
        if (event == null) return // 开场快照: follow 保证 gap-free, 无需历史处理
        handleSessionEvent(sessionId, event)
    }

    /** 可被 stop 打断的短睡; 返回 false 表示被打断 (应退出) */
    private fun sleepQuietly(ms: Long): Boolean {
        return try {
            Thread.sleep(ms)
            running.get()
        } catch (_: InterruptedException) {
            false
        }
    }

    /** 分发一条会话事件给写入工具识别 (follow 流回调) */
    private fun handleSessionEvent(sessionId: String, event: JsonObject) {
        val data = event.get("data")?.takeIf { it.isJsonObject }?.asJsonObject ?: return
        when (event.get("type")?.asString) {
            "tool/call" -> onToolCall(sessionId, data)
            "tool/result" -> onToolResult(sessionId, data)
        }
    }

    /** 尝试连接并阻塞读流; 返回是否成功建立过连接 (失败时已尽力而为) */
    private fun tryConnectAndStream(): Boolean {
        // 1) 先试 SSE (旧版 dsh): GET /api/events.mux
        if (trySseStream()) return true
        if (!running.get()) return false
        // 2) 再试 WebSocket (新版 dsh): ws://127.0.0.1:<port>/api/events.mux
        return tryWsStream()
    }

    /**
     * 新版 dsh (0.1.2+) 的 /api 需要浏览器认证 cookie: 先等启动令牌 (通常端口就绪后
     * 几百毫秒随 dsh 输出到达) 再换取; 旧版 dsh (无认证) 返回 null, 按原样连接。
     */
    private fun authCookie(): String? {
        DshWebAuth.cookieHeader(port)?.let { return it }
        if (DshWebAuth.awaitToken(2000)) return DshWebAuth.cookieHeader(port)
        return null
    }

    /** 挂起线程直到新帧到达 */
    private fun trySseStream(): Boolean {
        var conn: HttpURLConnection? = null
        return try {
            val url = URI("http://127.0.0.1:$port/api/events.mux").toURL()
            conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 3000
            conn.readTimeout = 0
            // 新版 dsh (0.1.2+) 需要浏览器认证 cookie, 否则 401
            authCookie()?.let { conn.setRequestProperty("Cookie", it) }
            sseConn = conn
            val code = conn.responseCode
            if (code != 200) return false
            logConnected("SSE")
            conn.inputStream.bufferedReader(StandardCharsets.UTF_8).use { reader ->
                for (line in reader.lineSequence()) {
                    if (!running.get()) break
                    if (line.startsWith(SSE_DATA_PREFIX)) {
                        handleFrame(line.substring(SSE_DATA_PREFIX.length).trim())
                    }
                }
            }
            true
        } catch (t: Throwable) {
            if (running.get()) throttledLog(DshBundle.message("sync.log.connInterrupted", t.message ?: "null"))
            false
        } finally {
            try {
                conn?.disconnect()
            } catch (_: Throwable) {
            }
            sseConn = null
        }
    }

    private fun tryWsStream(): Boolean {
        val closed = CountDownLatch(1)
        val listener = object : WebSocket.Listener {
            /** 大帧会被拆分成多段 onText (last=false), 先累积再解析 */
            private var wsBuffer: StringBuilder? = null

            override fun onOpen(webSocket: WebSocket) {
                // java.net.http 的 WebSocket 消息是"请求制": 不显式 request(1),
                // 除 onOpen 外一帧都不会投递 —— 之前漏了这里, 导致连接成功但收不到任何事件
                webSocket.request(1)
            }

            override fun onText(webSocket: WebSocket, data: CharSequence, last: Boolean): CompletionStage<*>? {
                val buf = wsBuffer ?: StringBuilder().also { wsBuffer = it }
                buf.append(data)
                webSocket.request(1)
                if (last) {
                    wsBuffer = null
                    handleFrame(buf.toString())
                }
                return null
            }

            override fun onBinary(webSocket: WebSocket, data: java.nio.ByteBuffer, last: Boolean): CompletionStage<*>? {
                webSocket.request(1)
                return null
            }

            override fun onClose(webSocket: WebSocket, statusCode: Int, reason: String): CompletionStage<*>? {
                closed.countDown()
                return null
            }

            override fun onError(webSocket: WebSocket, error: Throwable) {
                closed.countDown()
            }
        }
        return try {
            val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build()
            // 注意: java.net.http 的 WebSocket.Builder 没有单独 uri() 方法,
            // URI 直接传给 buildAsync(uri, listener)
            val builder = client.newWebSocketBuilder()
            // 新版 dsh (0.1.2+) 握手需带浏览器认证 cookie, 否则 401 拒绝
            authCookie()?.let { builder.header("Cookie", it) }
            val w = builder
                .buildAsync(URI("ws://127.0.0.1:$port/api/events.mux"), listener)
                .get(4, TimeUnit.SECONDS)
            if (!running.get()) {
                w.abort()
                return false
            }
            ws = w
            logConnected("WebSocket")
            closed.await()
            true
        } catch (t: Throwable) {
            if (running.get()) throttledLog(DshBundle.message("sync.log.connFailed", t.message ?: "null"))
            false
        } finally {
            try {
                ws?.abort()
            } catch (_: Throwable) {
            }
            ws = null
        }
    }

    private fun logConnected(transport: String) {
        if (connectedLogged) return
        connectedLogged = true
        consecutiveFailures = 0
        onLog(DshBundle.message("sync.log.connected", DshIdeName.productName(), transport))
    }

    /** 节流日志: 每条消息各自 60s 内最多输出一次 (防重连风暴刷屏; 不同消息互不吞并) */
    private val lastFailPerMessage = java.util.concurrent.ConcurrentHashMap<String, Long>()

    private fun throttledLog(message: String) {
        val now = System.currentTimeMillis()
        val prev = lastFailPerMessage.putIfAbsent(message, now)
        if (prev != null) {
            if (now - prev < 60_000) return
            lastFailPerMessage[message] = now
        }
        LOG.warn(message)
        onLog(message)
    }

    /** 调试日志: 只写 IDE 日志 (idea.log, 带 [同步] 前缀可 grep), 不刷工具窗口日志面板 */
    private fun debugLog(message: String) {
        LOG.info(message)
    }

    /** 解析一帧 server-request JSON, 识别文件写入工具的调用/结果 */
    private fun handleFrame(text: String) {
        try {
            val root = JsonParser.parseString(text).asJsonObject ?: return
            val payload = root.get("payload")?.takeIf { it.isJsonObject }?.asJsonObject ?: return
            val frameType = payload.get("type")?.asString ?: return
            // 第一个 session/subscribed 帧 = 消息流已建立 (证明事件真的在到达本插件)
            if (frameType == "session/subscribed" && !subscribedLogged) {
                subscribedLogged = true
                debugLog("事件消息流已建立 (收到 session/subscribed 帧)")
            }
            if (frameType != "session/event") return
            val event = payload.get("event")?.takeIf { it.isJsonObject }?.asJsonObject ?: return
            val eventType = event.get("type")?.asString ?: return
            val data = event.get("data")?.takeIf { it.isJsonObject }?.asJsonObject ?: return
            val sessionId = payload.get("sessionId")?.asString ?: return
            when (eventType) {
                "tool/call" -> onToolCall(sessionId, data)
                "tool/result" -> onToolResult(sessionId, data)
            }
        } catch (t: Throwable) {
            if (running.get()) LOG.warn("parse event frame failed", t)
        }
    }

    /** 文件写入类工具调用: 记录 callId -> 文件路径, 等 tool/result 成功后刷新 */
    private fun onToolCall(sessionId: String, data: JsonObject) {
        val name = data.get("name")?.asString ?: return
        val callId = data.get("callId")?.asString ?: return
        if (name !in WRITING_TOOLS) {
            debugLog("工具调用 name=$name (非写入工具, 忽略)")
            return
        }
        val argsText = data.get("arguments")?.let { argsElement ->
            when {
                argsElement.isJsonPrimitive -> argsElement.asString
                argsElement.isJsonObject -> argsElement.toString() // 新版可能直接给对象, 序列化为 JSON 字符串再解析
                else -> null
            }
        }
        if (argsText == null) {
            debugLog("工具调用 name=$name callId=$callId 参数不是字符串: ${data.get("arguments")}")
            return
        }
        val path = try {
            val args = JsonParser.parseString(argsText).asJsonObject
            args.get("path")?.asString ?: args.get("file_path")?.asString
        } catch (t: Throwable) {
            debugLog("工具调用参数解析失败: $t")
            null
        }
        if (path == null) {
            debugLog("工具调用 name=$name callId=$callId 参数中无 path/file_path: $argsText")
            return
        }
        synchronized(pendingCalls) {
            pendingCalls["$sessionId/$callId"] = path
        }
        debugLog("记录写入工具调用 name=$name callId=$callId path=$path")
    }

    /** 工具执行结果: 配对本项目的文件写入调用, 成功 (非 isError) 则同步到 IDEA */
    private fun onToolResult(sessionId: String, data: JsonObject) {
        val message = data.get("message")?.takeIf { it.isJsonObject }?.asJsonObject ?: return
        val source = message.get("source")?.takeIf { it.isJsonObject }?.asJsonObject ?: return
        val callId = source.get("callId")?.asString ?: return
        val key = "$sessionId/$callId"
        val dshPath = synchronized(pendingCalls) { pendingCalls.remove(key) }
        if (dshPath == null) {
            debugLog("工具结果 callId=$callId 无配对调用 (非写入工具或事件丢失), 忽略")
            return
        }
        // 失败的工具调用不刷新 (文件可能根本没变)。isError 位置随 dsh 版本变化:
        // 部分版本在 message.isError, 当前版本在 message.content[0].isError, 两处都查
        val failed = message.get("isError")?.asBoolean == true || toolResultFailed(message)
        if (failed) {
            debugLog("工具结果 callId=$callId 失败 (isError=true), 不刷新 $dshPath")
            return
        }
        debugLog("工具结果 callId=$callId 成功, 开始同步 $dshPath")
        syncFileToIde(dshPath)
    }

    /** 当前版本 dsh 的 tool/result 失败标记: message.content[0].isError == true */
    private fun toolResultFailed(message: JsonObject): Boolean {
        val first = message.get("content")
            ?.takeIf { it.isJsonArray }?.asJsonArray
            ?.firstOrNull()
            ?.takeIf { it.isJsonObject }?.asJsonObject ?: return false
        return first.get("isError")?.asBoolean == true
    }

    // ---------- 同步到 IDEA ----------

    /**
     * 把 dsh 侧路径换算成 Windows/IDEA 侧路径; 无法可靠换算 (不在本项目可寻址范围) 返回 null。
     *
     * 不做"必须是当前项目内文件"的过滤: dsh 的工作空间/会话 cwd 可能与 IDEA 当前项目目录
     * 不一致 (如 BaiCaiERP 与 BaiCaiERP3 是两个不同目录), 但 dsh 编辑的文件只要在本机
     * 文件系统上, IDEA 里打开着它的编辑器就应该同步 —— 换算成功即可刷新。
     */
    private fun ideaPathOf(dshPath: String): String? {
        return if (launchMode == DshSettingsState.MODE_WSL) {
            // 常规 /mnt/<盘符>/... 形态 -> C:/...
            val m = Regex("^/mnt/([A-Za-z])/(.*)$").matchEntire(dshPath)
            if (m != null) {
                "${m.groupValues[1].uppercase()}:/${m.groupValues[2]}"
            } else {
                // \\wsl$\<distro>\... 远程项目: dsh 路径为 /..., 用本窗口项目推导 distro
                val ideaBase = ideaPathBase
                if (ideaBase != null && ideaBase.startsWith(WSL_NETWORK_PREFIX)) {
                    val distro = ideaBase.removePrefix(WSL_NETWORK_PREFIX).substringBefore('/')
                    "$WSL_NETWORK_PREFIX$distro$dshPath"
                } else {
                    null // 如 /home/... 等无法确定 Windows 挂载的路径, 跳过
                }
            }
        } else {
            // Windows 直启: dsh 与 IDEA 同路径
            dshPath
        }
    }

    /** 刷新文件 VFS 并在编辑器打开且无未保存修改时重载文档 (均有兜底, 失败只记日志) */
    /** 同一路径在窗口内 (2s) 已刷新过则跳过: dsh 的一次编辑可能拆成多个写入事件,
     * 只对文件做一次 VFS 刷新即可 (避免同路径连续多条"已同步"刷屏) */
    private fun shouldRefresh(path: String): Boolean {
        val now = System.currentTimeMillis()
        val last = lastSyncAt[path]
        if (last != null && now - last < LAST_SYNC_MIN_INTERVAL_MS) return false
        lastSyncAt[path] = now
        return true
    }

    private fun syncFileToIde(dshPath: String) {
        val ideaPath = ideaPathOf(dshPath)
        if (ideaPath == null) {
            debugLog("路径无法换算为 Windows 路径, 跳过: $dshPath")
            return
        }
        // 同路径短窗口内只刷一次 (同一批写入事件合并) —— 既不刷屏, 也减少 VFS 压力
        if (!shouldRefresh(ideaPath)) {
            debugLog("同路径 $ideaPath 短期已刷新, 合并跳过")
            return
        }
        debugLog("换算为 IDEA 路径: $ideaPath")
        try {
            val parentPath = ideaPath.substringBeforeLast('/')
            val parent = LocalFileSystem.getInstance().refreshAndFindFileByPath(parentPath)
            var file = LocalFileSystem.getInstance().findFileByPath(ideaPath)
            // WSL2 写入 /mnt/c 等场景可能不更新 Windows 侧文件时间戳, 普通 refresh 会因
            // "时间戳未变"而跳过重读磁盘内容 (文档仍是旧内容, 重开文件也没用)。
            // 必须 markDirty + reloadChildren=true 强制重读, 不信任时间戳。
            if (parent != null) VfsUtil.markDirtyAndRefresh(false, false, true, parent)
            if (file != null) VfsUtil.markDirtyAndRefresh(false, false, true, file)
            file = LocalFileSystem.getInstance().findFileByPath(ideaPath)
            if (file == null) {
                debugLog("VFS 中未找到该文件 (可能不在本机或尚未可寻址): $ideaPath (parent=${parent?.path})")
                return
            }
            if (file.isDirectory) return // 工具参数指向目录 (如 view) 不刷新
            debugLog("VFS 强制刷新完成: ${file.path} (modificationStamp=${file.modificationStamp})")
            reloadOpenDocument(file)
        } catch (t: Throwable) {
            if (running.get()) {
                debugLog("刷新失败: $ideaPath -> ${t.message}")
                LOG.warn("sync file to ide failed: $ideaPath", t)
            }
        }
    }

    /** 打开着的文档: 无未保存修改则确保内容为磁盘新版 (强制刷新已会触发 IDEA 原生自动重载, 这里兜底) */
    private fun reloadOpenDocument(file: VirtualFile) {
        ApplicationManager.getApplication().invokeLater {
            if (project.isDisposed || !running.get()) return@invokeLater
            try {
                val doc = FileDocumentManager.getInstance().getDocument(file) ?: return@invokeLater
                // 用户有未保存修改: 不覆盖, 交由 IDEA 原生冲突处理 (跨版本用 isFileModified)
                if (FileDocumentManager.getInstance().isFileModified(file)) return@invokeLater
                if (file.modificationStamp != doc.modificationStamp) {
                    // 戳不同: 平台没来得及自动重载, 显式重载
                    FileDocumentManager.getInstance().reloadFromDisk(doc)
                }
                // 戳相同 = 已由 VFS 变更事件触发 IDEA 原生自动重载 (或内容本就一致), 均视为已同步
                onLog("已同步 dsh 编辑: ${file.path}")
            } catch (t: Throwable) {
                debugLog("重载文档异常: ${t.message}")
            }
        }
    }

    companion object {
        private const val SSE_DATA_PREFIX = "data: "

        /** \\wsl$\<distro>\ 前缀 (反斜杠形式, 与 IDEA 路径一致) */
        private const val WSL_NETWORK_PREFIX = "//wsl$/"

        /** 连续失败多少次后判定"当前 dsh 不支持事件流", 停止重试 */
        private const val MAX_CONSECUTIVE_FAILURES = 5

        /** 同一路径两次刷新的最小间隔 (ms): dsh 一次编辑可能拆多个写入事件, 窗口内合并为一次刷新 */
        private const val LAST_SYNC_MIN_INTERVAL_MS = 2000L

        /** 通道风格探测重试间隔 (ms) */
        private const val REMOTE_PROBE_RETRY_MS = 1000L

        /** 通道风格探测总时长上限 (ms): 60s 内 dsh 认证未就绪则按旧版处理 */
        private const val REMOTE_PROBE_MAX_WAIT_MS = 60_000L

        /** dsh 的文件写入类工具 (tool/call 的 data.name), 其余工具不触发同步 */
        private val WRITING_TOOLS = setOf(
            "str_replace_editor", // 经典 Edit 工具 (参数 path)
            "edit", "tool:edit", // 新版 edit 工具 (参数 file_path)
            "write", "tool:write", // 新建/覆写文件 (参数 file_path)
        )
    }
}