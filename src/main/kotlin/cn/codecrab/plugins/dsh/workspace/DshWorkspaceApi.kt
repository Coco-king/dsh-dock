package cn.codecrab.plugins.dsh.workspace

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
 * 传输格式 (与 dsh 客户端完全一致):
 *   POST /api/<method>
 *   body: {"type":"client-request","rpcId":"<uuid>","method":"<method>","payload":{...}}
 * 请求经 host 的 loopback 信任围栏放行 (Host 为 127.0.0.1/localhost 且不带 Origin 头)。
 *
 * 流程 (每次加载 WebUI 前调用一次, 幂等):
 *  1. workspace.create {path}            -> 注册项目工作空间 (已存在则幂等返回, created=false)
 *  2. 若刚创建 -> 完成 (新工作空间 createdAt 最新, 自动成为"最近")
 *  3. 若已存在但当前不是"最近工作空间" -> session.create {workspaceId}
 *     (新会话 updatedAt 最新, 使其所在工作空间成为"最近"), 页面加载后就会落在项目上
 */
object DshWorkspaceApi {

    private val LOG = Logger.getInstance(DshWorkspaceApi::class.java)

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
     * @param timeoutMs 整个同步过程的软超时 (避免 dsh API 异常时拖慢页面加载)
     */
    fun ensureProjectWorkspace(port: Int, projectDshPath: String?, timeoutMs: Long = 8000): WorkspaceSyncResult? {
        if (projectDshPath.isNullOrBlank()) return null
        val deadline = System.currentTimeMillis() + timeoutMs
        val create = rpcWithin(port, "workspace.create", "{\"path\":${jsonString(projectDshPath)}}", deadline)
            ?: return null
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
        val created = value.get("created").asBoolean
        var bumped = created
        var sessionItems: JsonArray? = null
        if (!created) {
            // 工作空间已存在: 只有当它当前不是"最近工作空间"时才新建会话提升其新鲜度
            val recent = computeRecentWorkspace(port, deadline)
            if (recent == null) {
                return WorkspaceSyncResult(workspaceId, sessionIds, bumped)
            }
            sessionItems = recent.second
            if (recent.first != workspaceId) {
                val bump = rpcWithin(port, "session.create", "{\"workspaceId\":${jsonString(workspaceId)}}", deadline)
                if (bump?.ok == true) {
                    bumped = true
                    // 新会话立即可归档: 归档不影响"最近"计算 (session.list 含已归档会话),
                    // 但 connectWorkspace 复用时跳过已归档, 避免它成为被反复复用的旧空白
                    bump.value?.get("sessionId")?.asString?.let { archiveSession(port, it, deadline) }
                } else {
                    LOG.warn("session.create (recency bump) failed: ${bump?.value}")
                }
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
        val r = rpcWithin(port, "workspace.archiveSession", "{\"sessionId\":${jsonString(sessionId)}}", deadline)
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
        val payload = "{\"ns\":${jsonString("locale")},\"patch\":{\"preference\":${jsonString(localeId)}}}"
        val r = rpcWithin(port, "settings.update", payload, deadline) ?: return false
        if (!r.ok) {
            LOG.warn("settings.update (locale) failed: ${r.value}")
            return false
        }
        return true
    }

    /**
     * 复刻 dsh 客户端的 recentWorkspace: 各工作空间取"最新会话 updatedAt" (无会话则取 createdAt), 取最大者。
     * 同时带回 session.list 的会话列表, 供空白会话清理复用 (省一次 RPC)。
     */
    private fun computeRecentWorkspace(port: Int, deadline: Long): Pair<String?, JsonArray?> {
        val ws = rpcWithin(port, "workspace.list", "{}", deadline) ?: return null to null
        val s = rpcWithin(port, "session.list", "{}", deadline) ?: return null to null
        val wsItems = ws.value?.getAsJsonArray("items") ?: return null to null
        val sessionItems = s.value?.getAsJsonArray("items") ?: return null to null

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
        return selected to sessionItems
    }

    /** 在截止时间前调用一次 dsh /api RPC; 传输层失败返回 null, 业务失败返回 ok=false 的结果 */
    private fun rpcWithin(port: Int, method: String, payload: String, deadline: Long): RpcResult? {
        if (System.currentTimeMillis() >= deadline) return null
        val body = buildString {
            append("{\"type\":\"client-request\",\"rpcId\":")
            append(jsonString(UUID.randomUUID().toString()))
            append(",\"method\":")
            append(jsonString(method))
            append(",\"payload\":")
            append(payload)
            append("}")
        }
        val text = post(port, method, body) ?: return null
        return try {
            // 静态 parseString (Gson 2.8.6+ 提供, 2022.3 起的 IDE 均满足);
            // 实例构造器 JsonParser() / parse(String) 已废弃, 不再使用
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

    private fun post(port: Int, method: String, body: String): String? {
        var conn: HttpURLConnection? = null
        try {
            // URL(String) 构造器已废弃 (Java 20+), 改用 URI.toURL()
            val url = java.net.URI("http://127.0.0.1:$port/api/$method").toURL()
            conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.connectTimeout = 2000
            conn.readTimeout = 4000
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.outputStream.use { it.write(body.toByteArray(StandardCharsets.UTF_8)) }
            val code = conn.responseCode
            if (code !in 200..299) {
                LOG.warn("dsh api $method -> HTTP $code")
                return null
            }
            return conn.inputStream.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
        } catch (t: Throwable) {
            LOG.warn("dsh api $method failed", t)
            return null
        } finally {
            try {
                conn?.disconnect()
            } catch (_: Throwable) {
            }
        }
    }

    /** JSON 字符串字面量转义 */
    private fun jsonString(value: String): String {
        val sb = StringBuilder("\"")
        for (ch in value) {
            when (ch) {
                '\\' -> sb.append("\\\\")
                '"' -> sb.append("\\\"")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> if (ch.code < 0x20) sb.append("\\u%04x".format(ch.code)) else sb.append(ch)
            }
        }
        sb.append("\"")
        return sb.toString()
    }
}
