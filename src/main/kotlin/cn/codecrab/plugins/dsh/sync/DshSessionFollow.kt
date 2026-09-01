package cn.codecrab.plugins.dsh.sync

import cn.codecrab.plugins.dsh.server.DshWebAuth
import cn.codecrab.plugins.dsh.workspace.DshWorkspaceApi
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.openapi.diagnostic.Logger
import java.net.URI
import java.net.http.HttpClient
import java.net.http.WebSocket
import java.time.Duration
import java.util.concurrent.CompletionStage
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * dsh 新版 (0.1.2+) 的会话事件实时订阅: `session/follow` 流。
 *
 * 背景: 新版 dsh 移除了 events.mux, 会话事件的官方实时通道是 Typert Remote 流 ——
 * WebSocket `/api/remote.mux` 上按 streamId 多路复用多个逻辑流, 每个流 open 一个端点
 * (如 `session/follow`), 服务端回推 item 帧: 开场 `snapshot` (cursor + 最近 records),
 * 之后逐条 `session event` 条目 (与 session/page 的 records 元素同构)。
 *
 * 本类一次连接管多个流 (每个会话一个 follow 流):
 *  - 开场 snapshot 只向调用方报告基线 cursor (event==null), 不处理历史 records (防回放);
 *  - 之后每条 `event` 按 (sessionId, seq, event) 回调, seq 过滤与 VFS 刷新由调用方负责;
 *  - 校准线程每 30s 用 session.list 对齐会话集合: 新会话开流, 消失的会话发 cancel;
 *  - 断线自动退避重连 (每次重连都是新快照, 场景内事件 gap-free)。
 *
 * 线程模型: 两个 daemon 线程 —— 读帧循环 (阻塞收帧 + 退避重连) 与 30s 校准线程;
 * 回调在对应线程执行, 解析与入队须轻量 (VFS 刷新由调用方保证线程安全)。
 */
class DshSessionFollow(
    private val port: Int,
    /** 事件回调: event == null 表示开场快照 (仅建立基线); 否则为一条会话事件 */
    private val onSessionEvent: (sessionId: String, event: JsonObject?) -> Unit,
) {

    private val LOG = Logger.getInstance(DshSessionFollow::class.java)

    /** 置 false 即停 (面板销毁 / dsh 停止) */
    private val running = AtomicBoolean(true)

    /** 当前打开的流: streamId -> sessionId */
    private val streams = HashMap<String, String>()

    /** 目标会话集合 (校准线程更新); null = 尚未校准 */
    @Volatile
    private var desiredSessions: Set<String>? = null

    @Volatile
    private var ws: WebSocket? = null

    private var thread: Thread? = null
    private var calibrateThread: Thread? = null

    fun start() {
        val t = Thread({ wsLoop() }, "dsh-plugin-follow").apply { isDaemon = true }
        thread = t
        t.start()
        val c = Thread({ calibrateLoop() }, "dsh-plugin-follow-calibrate").apply { isDaemon = true }
        calibrateThread = c
        c.start()
    }

    fun stop() {
        running.set(false)
        try {
            ws?.abort()
        } catch (_: Throwable) {
        }
        try {
            thread?.interrupt()
        } catch (_: Throwable) {
        }
        try {
            calibrateThread?.interrupt()
        } catch (_: Throwable) {
        }
    }

    // ---------- 读帧主循环 ----------

    private fun wsLoop() {
        var backoffMs = 1000L
        while (running.get()) {
            val latch = CountDownLatch(1)
            val w = connect(latch)
            if (w == null) {
                if (!sleepFor(backoffMs)) return
                backoffMs = minOf(backoffMs * 2, 30_000L)
                continue
            }
            backoffMs = 1000L
            ws = w
            // 连接建立: 立刻按当前目标开流 (尚未校准则先拉一次会话列表, 不必等 30s)
            val desired = desiredSessions ?: refreshNow()
            synchronized(this) {
                streams.clear()
                openStreams(w, desired)
            }
            // 阻塞读帧, 直到连接关闭
            try {
                latch.await()
            } catch (_: InterruptedException) {
                return
            }
            if (!running.get()) return
            synchronized(this) { streams.clear() }
            if (!sleepFor(backoffMs)) return
            backoffMs = minOf(backoffMs * 2, 30_000L)
        }
    }

    /** 建立 remote.mux WebSocket (带浏览器认证 cookie); 失败返回 null */
    private fun connect(latch: CountDownLatch): WebSocket? {
        val cookie = DshWebAuth.cookieHeader(port)
            ?: (if (DshWebAuth.awaitToken(2000)) DshWebAuth.cookieHeader(port) else null)
        return try {
            val builder = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build()
                .newWebSocketBuilder()
                .connectTimeout(Duration.ofSeconds(3))
            cookie?.let { builder.header("Cookie", it) }
            builder.buildAsync(URI("ws://127.0.0.1:$port/api/remote.mux"), listener(latch))
                .get(5, TimeUnit.SECONDS)
        } catch (t: Throwable) {
            LOG.warn("dsh follow connect failed", t)
            null
        }
    }

    private fun listener(latch: CountDownLatch): WebSocket.Listener = object : WebSocket.Listener {
        private var buffer: StringBuilder? = null

        override fun onOpen(webSocket: WebSocket) {
            webSocket.request(1)
        }

        override fun onText(webSocket: WebSocket, data: CharSequence, last: Boolean): CompletionStage<*>? {
            val buf = buffer ?: StringBuilder().also { buffer = it }
            buf.append(data)
            webSocket.request(1)
            if (last) {
                buffer = null
                handleFrame(buf.toString())
            }
            return null
        }

        override fun onClose(webSocket: WebSocket, statusCode: Int, reason: String): CompletionStage<*>? {
            latch.countDown()
            return null
        }

        override fun onError(webSocket: WebSocket, error: Throwable) {
            latch.countDown()
        }
    }

    /** 解析服务端帧: item / end / error; item 的 value 分发给对应会话 */
    private fun handleFrame(text: String) {
        try {
            val frame = JsonParser.parseString(text).asJsonObject ?: return
            val streamId = frame.get("streamId")?.asString ?: return
            val sid = synchronized(this) { streams[streamId] } ?: return
            when (frame.get("type")?.asString) {
                "item" -> {
                    val value = frame.get("value")?.takeIf { it.isJsonObject }?.asJsonObject ?: return
                    onItem(sid, value)
                }
                "error" -> {
                    LOG.warn("dsh follow stream $streamId ended: ${frame.get("error")}")
                    synchronized(this) { streams.remove(streamId) }
                }
                "end" -> synchronized(this) { streams.remove(streamId) }
            }
        } catch (t: Throwable) {
            LOG.warn("dsh follow frame parse failed", t)
        }
    }

    /** 一个 item 值: snapshot (开场基线) 或 event 条目 */
    private fun onItem(sessionId: String, value: JsonObject) {
        when (value.get("type")?.asString) {
            "snapshot" -> {
                // 开场快照 (event==null): 调用方仅记录基线, 不做处理 (避免场景内历史回放)
                onSessionEvent(sessionId, null)
            }
            "event" -> {
                val event = value.get("event")?.takeIf { it.isJsonObject }?.asJsonObject ?: return
                onSessionEvent(sessionId, event)
            }
        }
    }

    // ---------- 会话校准 (30s) ----------

    private fun calibrateLoop() {
        while (running.get()) {
            try {
                Thread.sleep(CALIBRATE_INTERVAL_MS)
            } catch (_: InterruptedException) {
                return
            }
            if (!running.get()) return
            refresh()
        }
    }

    /** 拉一次会话列表做一次校准 (连接建立前也调用, 让首轮开流不等待) */
    private fun refreshNow(): Set<String> {
        val s = mutableSetOf<String>()
        try {
            val list = DshWorkspaceApi.callJson(port, "session.list", JsonObject(), 4000) ?: return s
            val items = list.getAsJsonArray("items") ?: return s
            for (el in items) {
                val sid = el.asJsonObject?.get("sessionId")?.asString ?: continue
                s.add(sid)
            }
        } catch (_: Throwable) {
        }
        desiredSessions = s
        return s
    }

    private fun refresh() {
        val desired = refreshNow()
        val w = ws ?: return
        synchronized(this) {
            val stale = streams.filterValues { it !in desired }.keys
            for (streamId in stale) {
                streams.remove(streamId)
                try {
                    w.sendText("""{"type":"cancel","streamId":"${jsonEscape(streamId)}"}""", true)
                } catch (_: Throwable) {
                }
            }
            openStreams(w, desired)
        }
    }

    /** 为目标会话中尚未开流的会话发 open 帧 */
    private fun openStreams(w: WebSocket, desired: Set<String>) {
        for (sid in desired) {
            if (sid in streams.values) continue
            val streamId = "plugin-follow-$sid"
            streams[streamId] = sid
            val frame = JsonObject().apply {
                addProperty("type", "open")
                addProperty("streamId", streamId)
                addProperty("endpoint", "session/follow")
                add("payload", JsonObject().apply {
                    add("args", JsonObject().apply {
                        add("request", JsonObject().apply {
                            add("address", JsonObject().apply {
                                addProperty("kind", "session")
                                addProperty("sessionId", sid)
                            })
                            addProperty("maxMessages", 50)
                        })
                    })
                })
            }.toString()
            try {
                w.sendText(frame, true)
            } catch (t: Throwable) {
                LOG.warn("dsh follow open failed for $sid", t)
                streams.remove(streamId)
            }
        }
    }

    private fun sleepFor(ms: Long): Boolean {
        try {
            Thread.sleep(ms)
        } catch (_: InterruptedException) {
            return false
        }
        return running.get()
    }

    private fun jsonEscape(value: String): String = value.replace("\\", "\\\\").replace("\"", "\\\"")

    companion object {
        private const val CALIBRATE_INTERVAL_MS = 30_000L
    }
}