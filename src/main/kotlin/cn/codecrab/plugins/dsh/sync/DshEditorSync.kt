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
 *  2. 新版轮询 (dsh ≥ 0.1.2-alpha, events.mux 已移除): 旧事件流连续失败若干次后自动切换
 *     —— 周期性 `session/list` 对比各会话 seq 游标, 有前进时用 `session/page` 增量拉取
 *     工具事件 (RPC 走 DshWorkspaceApi 的 Remote 风格适配)。
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

    /** 已切换到轮询模式 (新版 dsh: 无 events.mux 事件流, 改用 session/page RPC 轮询) */
    private var polling = false

    /** follow 实时流 (新版 dsh 主通道); null = 尚未创建 */
    private var follow: DshSessionFollow? = null

    /** 上次轮询兜底执行时刻 (follow 失联时用) */
    private var lastBackstopAt = 0L

    /** 轮询当前间隔 (ms): 活动时短间隔, 连续空闲逐步退避到上限 */
    private var pollIntervalMs = POLL_ACTIVE_MS

    /** 连续空闲轮数 (无任何会话 seq 前进) */
    private var idleRounds = 0

    /** 轮询模式下的会话 seq 游标: sessionId -> 已消费到的 projections.asOfSeq */
    private val pollCursors = HashMap<String, Long>()

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
        polling = false
        pollIntervalMs = POLL_ACTIVE_MS
        idleRounds = 0
        lastBackstopAt = 0L
        follow = null
        pendingCalls.clear()
        onceNotified.clear()
        // 轮询游标跨 dsh/IDE 重启从设置恢复: 只同步 seq 前进的新事件, 不回放历史
        pollCursors.clear()
        val saved = DshSettingsState.getInstance().syncCursors
        for ((sid, seq) in saved) {
            pollCursors[sid] = seq.toLongOrNull() ?: 0L
        }
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

    /** 监听主循环: 旧版事件流 -> 新版轮询模式; 断线按退避自动重连 */
    private fun runLoop() {
        // 新版 dsh (0.1.2+) 没有 events.mux: 进主循环前先判定一次, 是则直接走轮询
        if (isRemoteDsh()) {
            applyOnce("poll-mode-notified") {
                onLog(DshBundle.message("sync.log.pollMode"))
            }
            polling = true
        }
        while (running.get() && !project.isDisposed) {
            // 轮询模式: follow 流为主 (实时), 失联时用低频轮询兜底, 空闲自动退避
            if (polling) {
                if (follow == null) {
                    follow = DshSessionFollow(port, ::onFollowSessionEvent)
                    follow!!.start()
                    lastBackstopAt = System.currentTimeMillis()
                }
                if (follow!!.isAlive) {
                    // follow 托管 (事件实时推送): 每 5s 醒来确认一次, 空闲开销为零
                    sleepQuietly(5000)
                    continue
                }
                // follow 失联 (重连中/不可用): 用轮询兜底, 不丢事件
                val now = System.currentTimeMillis()
                if (now - lastBackstopAt >= pollIntervalMs) {
                    lastBackstopAt = now
                    val hadProgress = pollCycle()
                    if (hadProgress) {
                        idleRounds = 0
                        pollIntervalMs = POLL_ACTIVE_MS
                    } else {
                        idleRounds++
                        pollIntervalMs = minOf(POLL_ACTIVE_MS shl minOf(idleRounds, 5), POLL_MAX_INTERVAL_MS)
                    }
                } else {
                    sleepQuietly(1000)
                }
                continue
            }
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
            // 旧版事件流连不上且 dsh 是新版 (0.1.2+, /api 为 Remote 风格, events.mux 已移除):
            // 切换到轮询模式, 用 session/page RPC 增量拉取工具事件
            if (consecutiveFailures >= LEGACY_FAILURES_BEFORE_POLL && isRemoteDsh()) {
                applyOnce("poll-mode-notified") {
                    onLog(DshBundle.message("sync.log.pollMode"))
                }
                polling = true
                connectedLogged = false
                continue
            }
            if (consecutiveFailures >= MAX_CONSECUTIVE_FAILURES) {
                throttledLog(DshBundle.message("sync.log.unsupported"))
                running.set(false)
                return
            }
            reconnectBackoffMs = minOf(reconnectBackoffMs * 2, 15_000L)
        }
    }

    /** dsh 是否为新版 Remote RPC 风格 (探测失败即视为不是, 继续旧路径重试) */
    private fun isRemoteDsh(): Boolean {
        // 主动触发一次探测 (session/list 走 Remote 格式)
        DshWorkspaceApi.callJson(port, "session.list", JsonObject(), 3000)
        return DshWorkspaceApi.rpcStyle(port) == RpcStyle.REMOTE
    }

    /** 可被 stop 打断的短睡 */
    private fun sleepQuietly(ms: Long) {
        try {
            Thread.sleep(ms)
        } catch (_: InterruptedException) {
        }
    }

    /**
     * 轮询模式一轮: session.list 找 seq 前进的会话, 用 session/page 拉取新增的
     * 工具调用/结果事件 (新版 dsh 移除了 events.mux 事件流, 会话事件只能按 seq 增量拉)。
     * @return 本轮是否有会话 seq 前进 (供调用方做空闲退避)
     */
    private fun pollCycle(): Boolean {
        var hadProgress = false
        try {
            val list = DshWorkspaceApi.callJson(port, "session.list", JsonObject(), 4000) ?: return false
            val items = list.getAsJsonArray("items") ?: return false
            for (el in items) {
                if (!running.get()) return hadProgress
                val o = el.asJsonObject ?: continue
                val sid = o.get("sessionId")?.asString ?: continue
                val cursor = o.getAsJsonObject("projections")?.get("asOfSeq")?.asLong ?: continue
                val last = pollCursors[sid] ?: -1L
                if (cursor <= last) continue
                hadProgress = true
                if (last == -1L) {
                    // 首次见该会话 (进程刚启动 / dsh 重启出现新会话): 只建立基线游标,
                    // 不回放历史事件, 之后只同步 seq 前进的新增事件 (避免启动时刷屏"已同步")
                    debugLog("轮询: 会话 $sid 建立基线 seq $cursor (不回放历史)")
                    updateCursor(sid, cursor)
                    continue
                }
                updateCursor(sid, cursor)
                // 传入旧基线做 seq 过滤: page 返回的是窗口(可能重叠), 只处理 seq 超过基线的新事件
                debugLog("轮询: 会话 $sid seq $last -> $cursor")
                try {
                    fetchSessionEvents(sid, cursor, last)
                } catch (t: Throwable) {
                    LOG.warn("session.page failed for $sid", t)
                }
            }
            return hadProgress
        } catch (t: Throwable) {
            if (running.get()) throttledLog(DshBundle.message("sync.log.pollFailed", t.message ?: "null"))
            return false
        }
    }

    /** 更新会话游标并持久化到设置 (轮询线程调用; 写 settings 内存 map, IDEA 定时落盘) */
    private fun updateCursor(sessionId: String, cursor: Long) {
        pollCursors[sessionId] = cursor
        try {
            DshSettingsState.getInstance().syncCursors[sessionId] = cursor.toString()
        } catch (_: Throwable) {
        }
    }


    /** 拉取会话新增事件 (seq 超过 [lastSeen] 的), 识别文件写入工具调用/结果。
     * page 返回的是"throughSeq 前最近一段"窗口, 可能与本会话已处理的区间重叠,
     * 因此用 [lastSeen] 过滤 seq, 保证每个事件只消费一次 —— 避免同批事件在连续轮询中被重复刷新。
     */
    private fun fetchSessionEvents(sessionId: String, throughSeq: Long, lastSeen: Long) {
        // 用 Gson 对象构造 payload, 避免手拼 JSON 出错 (remoteTransform 的 REQUEST 分支会再包一层 request)
        val address = JsonObject().apply {
            addProperty("kind", "session")
            addProperty("sessionId", sessionId)
        }
        val request = JsonObject().apply {
            add("address", address)
            addProperty("throughSeq", throughSeq)
            addProperty("maxMessages", 100)
        }
        val page = DshWorkspaceApi.callJson(port, "session.page", request, 4000) ?: return
        val records = page.getAsJsonArray("records") ?: return
        for (rec in records) {
            if (!running.get()) return
            val r = rec.asJsonObject ?: continue
            if (r.get("type")?.asString != "event") continue // chunks 等记录跳过
            val event = r.getAsJsonObject("event") ?: continue
            val seq = event.get("seq")?.asLong ?: continue
            if (seq <= lastSeen) continue // 已消费过的旧事件 (窗口重叠), 跳过
            handleSessionEvent(sessionId, event)
        }
    }

    /** 分发一条会话事件给写入工具识别 (follow 流与轮询 page 共用) */
    private fun handleSessionEvent(sessionId: String, event: JsonObject) {
        val data = event.get("data")?.takeIf { it.isJsonObject }?.asJsonObject ?: return
        when (event.get("type")?.asString) {
            "tool/call" -> onToolCall(sessionId, data)
            "tool/result" -> onToolResult(sessionId, data)
        }
    }

    /** follow 流事件回调: event == null 为开场快照 (只记基线, 防历史回放); 否则按 seq 增量处理 */
    private fun onFollowSessionEvent(sessionId: String, seq: Long, event: JsonObject?) {
        if (!running.get()) return
        if (event == null) {
            debugLog("轮询: 会话 $sessionId follow 基线 seq $seq")
            updateCursor(sessionId, seq)
            return
        }
        val last = pollCursors[sessionId] ?: -1L
        if (seq <= last) return // 快照窗口/重连重叠, 跳过
        updateCursor(sessionId, seq)
        handleSessionEvent(sessionId, event)
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

    /** 每个生命周期内只执行一次 (start 时重置) 的通知标记 */
    private val onceNotified = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    private inline fun applyOnce(key: String, block: () -> Unit) {
        if (onceNotified.add(key)) block()
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

        /** 事件流连续失败达到该次数且 dsh 是新版 Remote 风格时, 切换到轮询模式 */
        private const val LEGACY_FAILURES_BEFORE_POLL = 1

        /** 轮询活动态间隔 (ms) */
        private const val POLL_ACTIVE_MS = 2000L

        /** 轮询空闲退避上限 (ms): 一小时不改东西时, 60s 才问一次 (一小时 60 次而非 1800 次) */
        private const val POLL_MAX_INTERVAL_MS = 60_000L

        /** 同一路径两次刷新的最小间隔 (ms): dsh 一次编辑可能拆多个写入事件, 窗口内合并为一次刷新 */
        private const val LAST_SYNC_MIN_INTERVAL_MS = 2000L

        /** dsh 的文件写入类工具 (tool/call 的 data.name), 其余工具不触发同步 */
        private val WRITING_TOOLS = setOf(
            "str_replace_editor", // 经典 Edit 工具 (参数 path)
            "edit", "tool:edit", // 新版 edit 工具 (参数 file_path)
            "write", "tool:write", // 新建/覆写文件 (参数 file_path)
        )
    }
}