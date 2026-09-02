package cn.codecrab.plugins.dsh.workspace

import cn.codecrab.plugins.dsh.server.DshWebAuth
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.openapi.diagnostic.Logger
import java.net.HttpURLConnection
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.util.UUID

/**
 * dsh 工作空间同步: 通过 dsh WebUI 的 `/api` RPC 通道, 确保"当前项目"成为会话的工作空间。
 *
 * 背景: dsh 的工作空间是**持久化注册表** (存于 ~/.dsh/storages, 跨进程重启保留),
 * WebUI 启动时自动选中"最近活跃的工作空间" (按会话 updatedAt 排序, 见 client-runtime 的
 * `recentWorkspace`), **与 dsh 进程的启动目录无关**。因此插件只靠 `cd <项目>` 无法让会话
 * 落在当前项目上 —— 需要在页面加载前通过 API 把项目工作空间注册出来并让它成为最近的。
 *
 * RPC 传输按 dsh 版本自适应 ([RpcStyle] 探测):
 *  - LEGACY (dsh ≤ 0.1.1):  POST /api/<namespace>.<method>, 扁平 payload
 *  - REMOTE (dsh ≥ 0.1.2-alpha): 端点改为 /api/<namespace>/<method>, payload 改为
 *    {"args": ...} 包装 (typert @Remote 体系, 参数名随方法不同: request / _request / 直接字段);
 *    返回结构不变 (workspace/create -> {workspace, created} 等), 调用方解析逻辑通用。
 * 请求经 host 的 loopback 信任围栏放行 (Host 为 127.0.0.1/localhost 且不带 Origin 头),
 * 新版 dsh 还需浏览器认证 cookie (见 DshWebAuth)。
 *
 * 流程 (每次加载 WebUI 前调用一次, 幂等):
 *  1. workspace.create {path}            -> 注册项目工作空间 (已存在则幂等返回, created=false)
 *  2. 确认「项目是最近工作空间」: 已是最近 -> 完成; 判定失败/非最近 -> session.create {workspaceId}
 *     (新会话 updatedAt 最新, 使其所在工作空间成为"最近"), 页面加载后就会落在项目上
 *  3. 无法确认 (判定失败且 bump 也失败) -> 返回 null, 由调用方重试 —— 绝不静默宣称已就绪,
 *     否则页面会按 dsh 的「最近活跃工作空间/上次会话」落到旧项目 (重启 IDE 场景的高发问题)
 *
 * 注: REMOTE 版移除了 workspace.list, "最近工作空间"判定改用 session.list
 * (会话项带 cwd = 工作空间路径 + updatedAt), 见 [computeRecentWorkspace]。
 */
object DshWorkspaceApi {

    private val LOG = Logger.getInstance(DshWorkspaceApi::class.java)

    /**
     * RPC 传输风格:
     *  - LEGACY: dsh ≤ 0.1.1 (点号端点 + 扁平 payload)
     *  - REMOTE: dsh ≥ 0.1.2-alpha (斜杠端点 + args 包装, typert @Remote 体系; workspace.list 已移除)
     */
    enum class RpcStyle { LEGACY, REMOTE }

    /** REMOTE 风格下 payload 进 args 的包装方式 */
    private enum class ArgsKind { REQUEST, EMPTY, DIRECT }

    /** 点号方法名 -> (斜杠端点, args 包装方式); 未列出的方法按原样透传 */
    private val REMOTE_ENDPOINTS: Map<String, Pair<String, ArgsKind>> = mapOf(
        "workspace.create" to ("workspace/create" to ArgsKind.REQUEST),
        "session.create" to ("session/create" to ArgsKind.REQUEST),
        "workspace.archiveSession" to ("workspace/archiveSession" to ArgsKind.REQUEST),
        "session.page" to ("session/page" to ArgsKind.REQUEST),
        "session.list" to ("session/list" to ArgsKind.EMPTY),
        "settings.update" to ("settings/update" to ArgsKind.DIRECT),
    )

    /** 探测到的 RPC 风格 (按端口缓存; dsh 重启不换端口, 风格不变; 停止 dsh 时由 DshServer 清空) */
    private val styleCache = java.util.concurrent.ConcurrentHashMap<Int, RpcStyle>()

    /** 当前端口的 RPC 风格 (未探测过返回 null) */
    fun rpcStyle(port: Int): RpcStyle? = styleCache[port]

    /** dsh 停止时清空探测缓存 (下次启动重新探测) */
    fun invalidateStyle(port: Int) {
        styleCache.remove(port)
    }

    private class RpcResult(val ok: Boolean, val value: JsonObject?)

    /** 工作空间同步结果: workspaceId + 该工作空间当前的 sessionIds (供调用方判断持久化会话归属)。
     * bumped = 本次同步让"最近工作空间"切换到了本项目 (新建工作空间 / 提升既有空间的新鲜度):
     * 调用方若在同步前已加载页面, 页面选中的还是旧的最近工作空间, 需要补刷一次 */
    data class WorkspaceSyncResult(
        val workspaceId: String,
        val sessionIds: List<String>,
        val bumped: Boolean = false,
    )

    /**
     * 确保项目工作空间存在且为"最近", 返回同步结果; 失败返回 null (调用方按现状继续)。
     * 附带做"空白会话卫生": 归档项目工作空间里遗留的空白会话 (空会话, 无内容损失),
     * 避免 dsh 的 connectWorkspace 一直复用旧空白会话、导致"新会话"点了没反应。
     *
     * **成功语义 (重要)**: 返回非 null 表示已确认「项目是最近工作空间」—— 要么项目本就是最近,
     * 要么已通过新建会话把它提升为最近 (dsh 页面按会话 updatedAt 选落点, 新会话必为最近)。
     * 最近判定失败 (API 抖动/字段缺失) 时优先用新建会话自愈; 自愈也失败则返回 null, 调用方会
     * 重试或在页面加载后自动重试, 绝不把「无法确认」静默当作成功 —— 否则页面落在上一个项目,
     * 正是"重启 IDE 后工作空间没切到当前项目"的高发根因 (判定链路恰好赶上 dsh /api 未就绪窗口时)。
     * @param timeoutMs 整个同步过程的软超时 (避免 dsh API 异常时拖慢页面加载)
     */
    fun ensureProjectWorkspace(port: Int, projectDshPath: String?, timeoutMs: Long = 8000): WorkspaceSyncResult? {
        if (projectDshPath.isNullOrBlank()) return null
        val deadline = System.currentTimeMillis() + timeoutMs
        val create = rpcWithin(
            port,
            "workspace.create",
            JsonObject().apply { addProperty("path", projectDshPath) },
            deadline,
        ) ?: return null
        if (!create.ok) {
            LOG.warn("workspace.create failed: ${create.value}")
            return null
        }
        val value = create.value ?: return null
        val workspace = value.getAsJsonObject("workspace")
        val workspaceId = workspace.get("workspaceId").asString
        val sessionIds = workspace.getAsJsonArray("sessionIds").mapNotNull {
            it.asString.takeIf(String::isNotBlank)
        }
        // 确认「项目是最近工作空间」(新建与已存在统一处理):
        //  - 判定成功且项目已是最近 -> 无需动作;
        //  - 判定失败 (recent == null, API 抖动/字段缺失导致不可信) 或项目非最近
        //    -> 用新建会话 bump: 新会话 updatedAt 最新, 在 session 级 recency 下项目必然成为最近 (自愈);
        //  - bump 也失败 -> 整次同步不确认, 返回 null 交给调用方重试。
        var bumped = false
        var sessionItems: JsonArray? = null
        val recent = computeRecentWorkspace(port, workspaceId, projectDshPath, deadline)
        when {
            recent != null && recent.first -> {
                // 项目已是最近工作空间: 无需 bump
                sessionItems = recent.second
            }
            else -> {
                val bump = rpcWithin(
                    port,
                    "session.create",
                    JsonObject().apply { addProperty("workspaceId", workspaceId) },
                    deadline,
                )
                if (bump?.ok != true) {
                    LOG.warn("session.create (recency bump) failed: ${bump?.value}; workspace sync not confirmed")
                    return null
                }
                bumped = true
                // 新会话立即可归档: 归档不影响"最近"计算 (session.list 含已归档会话),
                // 但 connectWorkspace 复用时跳过已归档, 避免它成为被反复复用的旧空白
                bump.value?.get("sessionId")?.asString?.let { archiveSession(port, it, deadline) }
                sessionItems = recent?.second
            }
        }
        // 空白会话卫生: 归档项目工作空间里所有遗留空白会话
        // (复用上面 session.list 的结果, 省一次 RPC; 新建的工作空间没有会话, 无需清理)
        archiveBlankSessions(port, sessionIds, sessionItems, deadline)
        return WorkspaceSyncResult(workspaceId, sessionIds, bumped)
    }

    /** 归档项目工作空间里的空白会话 (空会话无内容, 归档只是从侧边栏隐藏); 会话列表由调用方传入复用 */
    private fun archiveBlankSessions(
        port: Int,
        workspaceSessionIds: List<String>,
        sessionItems: JsonArray?,
        deadline: Long,
    ) {
        if (workspaceSessionIds.isEmpty() || sessionItems == null) return
        for (el in sessionItems) {
            val o = el.asJsonObject
            val sid = o.get("sessionId")?.asString ?: continue
            if (sid in workspaceSessionIds && o.get("blank")?.asBoolean == true) {
                archiveSession(port, sid, deadline)
            }
        }
    }

    private fun archiveSession(port: Int, sessionId: String, deadline: Long) {
        if (System.currentTimeMillis() >= deadline) return
        val r = rpcWithin(
            port,
            "workspace.archiveSession",
            JsonObject().apply { addProperty("sessionId", sessionId) },
            deadline,
        )
        if (r?.ok != true) LOG.warn("workspace.archiveSession failed: ${r?.value}")
    }

    /**
     * 把 dsh WebUI 的持久化语言偏好 (settings.locale.preference) 同步为指定语言 id。
     *
     * 背景: dsh 界面语言的优先级是 **持久化偏好 > 浏览器 navigator.languages**
     * (见 dsh-client-locale 的 LocaleRuntime: `section.preference ?? provisional`)。
     * 只靠注入 JS 覆盖 navigator 不可靠 (onLoadStart 时脚本可能落进即将被替换的旧文档),
     * 因此这里在加载页面前通过 dsh 的 /api 设置通道写入语言偏好 —— 这是 dsh 官方设计的
     * 语言控制入口, 持久化、实时生效 (applies: live), 与 WebUI 设置页里的「语言」同一槽位。
     *
     * @param port     dsh 监听端口
     * @param localeId dsh 语言 id, 仅支持 "zh" / "en" (dsh-client-locale 的 LOCALE_IDS)
     * @return true 表示写入成功; 失败 (dsh 无设置服务/传输失败) 返回 false, 调用方按现状继续
     */
    fun syncLocalePreference(port: Int, localeId: String, timeoutMs: Long = 4000): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        val payload = JsonObject().apply {
            addProperty("ns", "locale")
            add("patch", JsonObject().apply { addProperty("preference", localeId) })
        }
        val r = rpcWithin(port, "settings.update", payload, deadline) ?: return false
        if (!r.ok) {
            LOG.warn("settings.update (locale) failed: ${r.value}")
            return false
        }
        return true
    }

    /**
     * 判断项目工作空间当前是否为"最近工作空间" (false = 需要 bump 提升新鲜度)。
     * 同时带回 session.list 的会话列表, 供空白会话清理复用 (省一次 RPC)。
     * 返回 null 表示判定失败 (API 不可用/字段缺失导致结果不可信), 调用方用新建会话 bump 自愈。
     *
     * 复刻 dsh 客户端的 recentWorkspace: 各工作空间取"最新会话 updatedAt", 取最大者。
     *  - LEGACY: 遍历 workspace.list + session.list (无会话时用 createdAt 兜底)
     *  - REMOTE: workspace.list 已移除, 改用 session.list 的 cwd (=工作空间路径) + updatedAt;
     *    全局无会话时视为"项目即最近" (无从比较, 不 bump); 有会话但字段缺失导致
     *    无法比较时返回 null (判定不可信, 交给调用方 bump, 不做静默放行)
     */
    private fun computeRecentWorkspace(
        port: Int,
        workspaceId: String,
        projectDshPath: String,
        deadline: Long,
    ): Pair<Boolean, JsonArray?>? {
        return when (resolveStyle(port, deadline)) {
            RpcStyle.REMOTE -> {
                val s = rpcWithin(port, "session.list", JsonObject(), deadline) ?: return null
                val sessionItems = s.value?.getAsJsonArray("items") ?: return null
                // 全局没有任何会话时 (如全新环境): 无从按会话比较, 视为"项目即最近", 不 bump
                if (sessionItems.isEmpty()) return true to sessionItems
                // cwd 为 Windows 反斜杠形态, projectDshPath 可能为正斜杠: 比较前统一斜杠
                val projectCwd = projectDshPath.replace('\\', '/')
                var projectLatest = Long.MIN_VALUE
                var globalLatest = Long.MIN_VALUE
                for (el in sessionItems) {
                    val o = el.asJsonObject
                    val ts = o.get("updatedAt")?.asLong ?: continue
                    if (ts > globalLatest) globalLatest = ts
                    if (o.get("cwd")?.asString?.replace('\\', '/') == projectCwd && ts > projectLatest) {
                        projectLatest = ts
                    }
                }
                // 有会话却拿不到任何 updatedAt (字段缺失/格式变化): 判定结果不可信,
                // 返回 null 交给调用方用新建会话 bump 自愈, 不能按"项目即最近"静默放行
                if (globalLatest == Long.MIN_VALUE) return null
                (projectLatest >= globalLatest) to sessionItems
            }
            RpcStyle.LEGACY -> {
                val ws = rpcWithin(port, "workspace.list", JsonObject(), deadline) ?: return null
                val s = rpcWithin(port, "session.list", JsonObject(), deadline) ?: return null
                val wsItems = ws.value?.getAsJsonArray("items") ?: return null
                val sessionItems = s.value?.getAsJsonArray("items") ?: return null

                val updatedAt = HashMap<String, Long>()
                for (el in sessionItems) {
                    val o = el.asJsonObject
                    val id = o.get("sessionId")?.asString ?: continue
                    val ts = o.get("updatedAt")?.asLong ?: continue
                    updatedAt[id] = ts
                }

                var selected: String? = null
                var selectedTime = Long.MIN_VALUE
                for (el in wsItems) {
                    val o = el.asJsonObject
                    val id = o.get("workspaceId")?.asString ?: continue
                    var latest = Long.MIN_VALUE
                    o.getAsJsonArray("sessionIds").forEach { sid ->
                        updatedAt[sid.asString]?.let { if (it > latest) latest = it }
                    }
                    if (latest == Long.MIN_VALUE) {
                        latest = try {
                            Instant.parse(o.get("createdAt").asString).toEpochMilli()
                        } catch (_: Throwable) {
                            Long.MIN_VALUE
                        }
                    }
                    if (selected == null || latest > selectedTime) {
                        selected = id
                        selectedTime = latest
                    }
                }
                (selected == workspaceId) to sessionItems
            }
            null -> {
                LOG.warn("cannot resolve dsh rpc style for $port; skip recency check")
                null
            }
        }
    }

    /**
     * 供文件同步等外部模块调用的通用 RPC: 返回 result.value (业务失败返回 null)。
     * 入参为对象化 payload (方法名点号即可, 传输层按当前风格自动适配)。
     */
    fun callJson(port: Int, method: String, payload: JsonObject, timeoutMs: Long = 4000): JsonObject? {
        val deadline = System.currentTimeMillis() + timeoutMs
        val r = rpcWithin(port, method, payload, deadline) ?: return null
        return if (r.ok) r.value else null
    }

    /** 在截止时间前调用一次 dsh /api RPC; 传输层失败返回 null, 业务失败返回 ok=false 的结果 */
    private fun rpcWithin(port: Int, method: String, payload: JsonObject, deadline: Long): RpcResult? {
        if (System.currentTimeMillis() >= deadline) return null
        val style = resolveStyle(port, deadline) ?: return null
        // REMOTE 风格: 端点斜杠化 + payload 转 args 包装 (body 里的 method 必须与端点一致)
        val (endpoint, wirePayload) = if (style == RpcStyle.REMOTE) {
            remoteTransform(method, payload)
        } else {
            method to payload
        }
        // 信封 body 用对象构造, 避免手拼 JSON 出错
        val body = JsonObject().apply {
            addProperty("type", "client-request")
            addProperty("rpcId", UUID.randomUUID().toString())
            addProperty("method", endpoint)
            add("payload", wirePayload)
        }.toString()
        val text = post(port, endpoint, body) ?: return null
        return try {
            val root = JsonParser.parseString(text).asJsonObject
            val result = root.getAsJsonObject("result")
            if (result.get("ok").asBoolean) {
                RpcResult(true, result.get("value")?.asJsonObject)
            } else {
                RpcResult(false, result.get("error")?.asJsonObject)
            }
        } catch (t: Throwable) {
            LOG.warn("dsh api parse failed for $method", t)
            null
        }
    }

    /**
     * 探测并缓存端口的 RPC 风格。
     * 探测请求: 新版格式 session/list -> 2xx 即 REMOTE; 404 再试旧版格式 session.list -> LEGACY。
     * 认证/传输未就绪时返回 null (调用方重试时再探)。
     */
    private fun resolveStyle(port: Int, deadline: Long): RpcStyle? {
        styleCache[port]?.let { return it }
        if (System.currentTimeMillis() >= deadline) return null
        val cookie = DshWebAuth.cookieHeader(port)
            ?: (if (DshWebAuth.awaitToken(2000)) DshWebAuth.cookieHeader(port) else null)
        // 对象构造探测报文 (固定结构)
        val remoteBody = JsonObject().apply {
            addProperty("type", "client-request")
            addProperty("rpcId", "style-probe")
            addProperty("method", "session/list")
            add("payload", JsonObject().apply {
                add("args", JsonObject().apply { add("_request", JsonObject()) })
            })
        }.toString()
        val remoteRes = postOnce(port, "session/list", remoteBody, cookie)
        val rpcStyle = when {
            remoteRes == null -> null // 传输失败 (dsh 未就绪/无认证): 下次再探
            remoteRes.status in 200..299 -> RpcStyle.REMOTE
            remoteRes.status != 404 -> null // 401 等: 认证未就绪, 下次再探
            else -> {
                // 新版端点 404: 试旧版
                val legacyBody = JsonObject().apply {
                    addProperty("type", "client-request")
                    addProperty("rpcId", "style-probe")
                    addProperty("method", "session.list")
                    add("payload", JsonObject())
                }.toString()
                val legacyRes = postOnce(port, "session.list", legacyBody, cookie)
                if (legacyRes != null && legacyRes.status in 200..299) RpcStyle.LEGACY else null
            }
        }
        if (rpcStyle != null) {
            styleCache[port] = rpcStyle
            LOG.info("dsh rpc style on $port: $rpcStyle")
        }
        return rpcStyle
    }

    /**
     * REMOTE 风格下: 点号方法名 -> 斜杠端点, 扁平 payload -> args 包装; 未知方法原样透传。
     * 全程对象构造 (wirePayload 为可嵌入信封的 JsonObject)。
     */
    private fun remoteTransform(method: String, payload: JsonObject): Pair<String, JsonObject> {
        val remote = REMOTE_ENDPOINTS[method] ?: return method to payload
        val inner = when (remote.second) {
            ArgsKind.REQUEST -> JsonObject().apply { add("request", payload) }
            ArgsKind.EMPTY -> JsonObject().apply { add("_request", JsonObject()) } // 无参方法 (如 session.list)
            ArgsKind.DIRECT -> payload // 参数直接是 args 内容 (如 settings.update 的 ns/patch)
        }
        val wire = JsonObject().apply { add("args", inner) }
        return remote.first to wire
    }

    /**
     * 调用一次 dsh /api RPC (带浏览器认证 cookie)。
     * 新版 dsh (0.1.2+) 的 /api 需要认证 cookie: 请求前先等启动令牌 (通常几百毫秒内
     * 随 dsh 输出到达) 并换取 cookie; 旧版 dsh (无认证) 换取失败则按原样请求。
     * HTTP 401 (认证失效, 如 dsh 重启换了签名密钥) 时作废缓存、重换一次再试。
     */
    private fun post(port: Int, method: String, body: String): String? {
        var cookie = DshWebAuth.cookieHeader(port)
        if (cookie == null && DshWebAuth.awaitToken(2000)) {
            cookie = DshWebAuth.cookieHeader(port)
        }
        var result = postOnce(port, method, body, cookie)
        if (result != null && result.status == 401) {
            DshWebAuth.onUnauthorized(port)
            val fresh = DshWebAuth.cookieHeader(port)
            if (fresh != null) {
                LOG.warn("dsh api $method -> HTTP 401, re-authenticated and retrying")
                result = postOnce(port, method, body, fresh)
            }
        }
        if (result == null || result.status !in 200..299) {
            LOG.warn("dsh api $method -> HTTP ${result?.status ?: "transport error"}")
            // 端点 404: 端口上的 dsh 可能换了版本 (探测缓存失效), 下次调用重新探测
            if (result?.status == 404) {
                styleCache.remove(port)
            }
            return null
        }
        return result.text
    }

    private class PostResult(val status: Int, val text: String?)

    /** 单次 HTTP POST; 传输失败返回 null, 成功/已知状态码返回 (status, body) */
    private fun postOnce(port: Int, method: String, body: String, cookie: String?): PostResult? {
        var conn: HttpURLConnection? = null
        return try {
            // URL(String) 构造器已废弃 (Java 20+), 改用 URI.toURL()
            val url = java.net.URI("http://127.0.0.1:$port/api/$method").toURL()
            conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.connectTimeout = 2000
            conn.readTimeout = 4000
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            cookie?.let { conn.setRequestProperty("Cookie", it) }
            conn.outputStream.use { it.write(body.toByteArray(StandardCharsets.UTF_8)) }
            val code = conn.responseCode
            val text = try {
                conn.inputStream.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
            } catch (_: Throwable) {
                null
            }
            // 诊断: 非 2xx 时打印状态 + 请求体 + 响应体 (便于定位 HTTP 层失败原因)
            if (code !in 200..299) {
                val resp = text ?: try {
                    conn.errorStream?.bufferedReader(StandardCharsets.UTF_8)?.use { it.readText() }
                } catch (_: Throwable) {
                    null
                }
                LOG.warn("dsh api $method -> HTTP $code, body=${body.take(240)}, resp=${resp?.take(160)}")
            }
            PostResult(code, text)
        } catch (t: Throwable) {
            LOG.warn("dsh api $method failed", t)
            null
        } finally {
            try {
                conn?.disconnect()
            } catch (_: Throwable) {
            }
        }
    }
}
