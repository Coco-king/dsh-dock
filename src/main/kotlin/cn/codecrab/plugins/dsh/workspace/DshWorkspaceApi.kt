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
 *  - REMOTE (dsh ≥ 0.1.2-rc.1): 端点改为 /api/<namespace>/<method>, payload 改为
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
     *  - REMOTE: dsh ≥ 0.1.2-rc.1 (斜杠端点 + args 包装, typert @Remote 体系; workspace.list 已移除)
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

    /** 风格探测锁: 同一端口只让一个线程去探测, 其余等它的结果 (见 [resolveStyle]) */
    private val styleLocks = java.util.concurrent.ConcurrentHashMap<Int, Any>()

    /**
     * 本端口偏好的 authority 主机名 (默认 localhost; 传输不通时固定退到 127.0.0.1, 见 [sendWithHostFallback])。
     * 换主机名等于换 cookie (dsh 的认证 cookie 按 authority 绑定), 所以不逐请求来回切。
     */
    private val hostByPort = java.util.concurrent.ConcurrentHashMap<Int, String>()

    /** 单次 /api 请求的默认读超时 (见 [readTimeout]) */
    private const val RPC_READ_TIMEOUT_MS = 8000L

    /** 读超时下限: 预算将尽时也要给 dsh 留一点应答时间, 但不至于把线程一直挂住 */
    private const val MIN_READ_TIMEOUT_MS = 500L


    /** 当前端口的 RPC 风格 (未探测过返回 null) */
    fun rpcStyle(port: Int): RpcStyle? = styleCache[port]


    /** dsh 停止时清空探测缓存与主机名偏好 (下次启动重新探测) */
    fun invalidateStyle(port: Int) {
        styleCache.remove(port)
        hostByPort.remove(port)
    }

    private class RpcResult(val ok: Boolean, val value: JsonObject?)

    /** 工作空间同步结果: workspaceId + 该工作空间当前的 sessionIds (供调用方判断持久化会话归属)。
     * bumped = 本次同步让"最近工作空间"切换到了本项目 (新建工作空间 / 提升既有空间的新鲜度):
     * 调用方若在同步前已加载页面, 页面选中的还是旧的最近工作空间, 需要补刷一次。
     * landingSessionId = 本项目工作空间里"应当落地的会话" (见 [pickLandingSession]):
     * 页面按它选中会话就不再依赖 dsh 那份全局的"最近工作空间"。
     * hasContentSession = 本项目工作空间里是否有非空白会话 (没有时页面脚本才允许"新建会话"切回,
     * 免得在有真实会话的项目里误开新会话) */
    data class WorkspaceSyncResult(
        val workspaceId: String,
        val sessionIds: List<String>,
        val bumped: Boolean = false,
        val landingSessionId: String? = null,
        val hasContentSession: Boolean = true,
    )

    /**
     * 选一个"落地会话": 页面加载后应当直接打开的会话。
     *
     * 为什么需要它: dsh 页面加载时若没有可恢复的会话, 就按**全局的"最近工作空间"**选
     * (各工作空间里最新会话的 updatedAt 最大者)。这份"最近"是按**所有工作空间**算出来的:
     * 工作空间注册表在磁盘上 (各 dsh 进程共用同一份), 只要别的项目里聊过天它就会变成"最近",
     * 于是本窗口的页面可能落到别的项目上。把本项目工作空间里的会话钉进页面, 落地就与它无关。
     *
     * 只取**非空白**会话: 空白会话会被归档 (见 [archiveBlankSessions]), 而 dsh 客户端会把
     * "当前会话已归档"的清掉并退回"最近工作空间"选择, 钉了也白钉; 没有可用会话时返回 null
     * (退回 dsh 原逻辑)。
     */
    private fun pickLandingSession(sessionItems: JsonArray?, workspaceSessionIds: List<String>): String? {
        if (sessionItems == null || workspaceSessionIds.isEmpty()) return null
        val own = workspaceSessionIds.toHashSet()
        var best: String? = null
        var bestAt = Long.MIN_VALUE
        for (el in sessionItems) {
            if (!el.isJsonObject) continue
            val o = el.asJsonObject
            val sid = o.get("sessionId")?.asString ?: continue
            if (sid !in own) continue
            if (o.get("blank")?.asBoolean == true) continue
            val ts = o.get("updatedAt")?.asLong ?: continue
            if (ts > bestAt) {
                bestAt = ts
                best = sid
            }
        }
        return best
    }

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
     * @param timeoutMs 整个同步过程的软超时 (避免 dsh API 异常时拖慢页面加载)。不能太小:
     *   dsh 启动期要等启动令牌 (实测可达十几秒), 接口在并发高峰也可能几秒才应答
     * @param restoreLastSession 打开时恢复上次会话 (设置项, 默认关闭): 关闭时落地会话固定取
     *   **空白会话** (没有就现建一个), 页面因此停在当前项目的空白新会话上, 不复用上次的对话
     */
    fun ensureProjectWorkspace(
        port: Int,
        projectDshPath: String?,
        timeoutMs: Long = 15000,
        restoreLastSession: Boolean = true,
    ): WorkspaceSyncResult? {
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
        var bumpSessionId: String? = null
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
                // 这个新会话也可能就是"落地会话" (工作空间里没有别的会话时), 先不归档, 见下
                bumpSessionId = bump.value?.get("sessionId")?.asString
                sessionItems = recent?.second
            }
        }
        // 落地会话 (页面加载后应当打开的会话): 优先最近的非空白会话; 没有非空白会话时用空白会话
        // (本次 bump 出来的, 或工作空间里已有的); 一个会话都没有就现建一个
        val nonBlankLanding = pickLandingSession(sessionItems, sessionIds)
        val existingBlank = pickBlankSession(sessionItems, sessionIds)
        // 落地会话: 恢复上次会话时取最近的非空白会话 (没有就用空白会话); 不恢复时固定用空白会话,
        // 页面就停在当前项目的空白新会话上 (复用已有空白, 用过的那枚已变成非空白 -> 下次现建一枚新的)
        val landing = if (restoreLastSession) {
            nonBlankLanding ?: bumpSessionId ?: existingBlank
        } else {
            existingBlank ?: bumpSessionId
        }
        // 空白会话卫生: 只在"落地会话是非空白会话"时清理空白会话 (复用上面 session.list 的结果)。
        // 落地会话本身是空白会话时**不清理**: 它 (以及其它空白) 可能正是页面当前的落点, 而 dsh
        // 客户端会把"当前会话已归档"的选择清掉、退回按全局"最近工作空间"选 —— 落点失效就会落到
        // 别的项目上; 也无法从 API 分辨哪一枚空白会话是当前会话。
        if (restoreLastSession && nonBlankLanding != null && landing == nonBlankLanding) {
            archiveBlankSessions(port, sessionIds, sessionItems, deadline)
        }
        val finalLanding = landing ?: createLandingSession(port, workspaceId, deadline)
        val allSessionIds = (sessionIds + listOfNotNull(bumpSessionId, finalLanding)).distinct()
        return WorkspaceSyncResult(
            workspaceId,
            allSessionIds,
            bumped,
            finalLanding,
            hasContentSession = nonBlankLanding != null,
        )
    }

    /**
     * 工作空间里一个会话都没有时 (项目从没聊过), 现建一个空白会话当落地会话:
     * 没有落点的话页面会按 dsh 那份全局的"最近工作空间"选, 就可能落到别的项目上。
     * dsh 的"新会话"会复用这个空白会话 (见 connectWorkspace), 不会越堆越多。
     */
    private fun createLandingSession(port: Int, workspaceId: String, deadline: Long): String? {
        val created = rpcWithin(
            port,
            "session.create",
            JsonObject().apply { addProperty("workspaceId", workspaceId) },
            deadline,
        )
        if (created?.ok != true) {
            LOG.warn("session.create (landing session) failed: ${created?.value}")
            return null
        }
        return created.value?.get("sessionId")?.asString
    }

    /** 工作空间里最近的空白会话 (落地用: 非空白会话都没有时, 拿它当落点) */
    private fun pickBlankSession(sessionItems: JsonArray?, workspaceSessionIds: List<String>): String? {
        if (sessionItems == null || workspaceSessionIds.isEmpty()) return null
        val own = workspaceSessionIds.toHashSet()
        var best: String? = null
        var bestAt = Long.MIN_VALUE
        for (el in sessionItems) {
            if (!el.isJsonObject) continue
            val o = el.asJsonObject
            val sid = o.get("sessionId")?.asString ?: continue
            if (sid !in own) continue
            if (o.get("blank")?.asBoolean != true) continue
            val ts = o.get("updatedAt")?.asLong ?: continue
            if (ts > bestAt) {
                bestAt = ts
                best = sid
            }
        }
        return best
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
            if (!el.isJsonObject) continue
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

    /**
     * 读取 dsh 某一轮 (turn) 的改动文件摘要: `GET /api/changes.summary?sessionId=<id>&seq=<seq>`。
     *
     * 为什么需要它: dsh 0.2 起模型主要在 `run_code` 里调用写入工具 (嵌套调用不产生顶层的
     * tool/call / tool/result 事件), 插件收不到逐条写入事件, 只能靠 dsh 每轮结束时追加的
     * `workspace/changes` 会话事件 + 这个只读接口拿到"这一轮改了哪些文件"。
     *
     * @param seq 该 `workspace/changes` 事件的序号 (摘要按事件序号索引)
     * @return 摘要对象 (turn / files / total / added / deleted);
     *         没有摘要 (旧版 dsh、会话已释放) 或请求失败返回 null
     */
    fun changeSummary(port: Int, sessionId: String, seq: Long, timeoutMs: Long = 3000): JsonObject? {
        if (sessionId.isBlank()) return null
        val encoded = java.net.URLEncoder.encode(sessionId, "UTF-8")
        val text = get(port, "changes.summary", "sessionId=$encoded&seq=$seq", timeoutMs) ?: return null
        return try {
            JsonParser.parseString(text).asJsonObject
        } catch (t: Throwable) {
            LOG.warn("dsh change summary parse failed", t)
            null
        }
    }

    /** 在截止时间前调用一次 dsh /api RPC; 传输层失败返回 null, 业务失败返回 ok=false 的结果 */
    private fun rpcWithin(port: Int, method: String, payload: JsonObject, deadline: Long): RpcResult? {
        if (remaining(deadline) <= 0) return null
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
        val text = post(port, endpoint, body, deadline) ?: return null
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
     *
     * 同一端口只探测一次 ([styleLocks]): 预热、面板与文件同步会并发调用, 首次探测还要等启动令牌
     * —— 各线程各自等一遍既白白吃掉同步预算, 又会把 401 日志刷屏。
     */
    private fun resolveStyle(port: Int, deadline: Long): RpcStyle? {
        styleCache[port]?.let { return it }
        val lock = styleLocks.computeIfAbsent(port) { Any() }
        synchronized(lock) {
            styleCache[port]?.let { return it }
            if (remaining(deadline) <= 0) return null
            // 对象构造探测报文 (固定结构)
            val remoteBody = JsonObject().apply {
                addProperty("type", "client-request")
                addProperty("rpcId", "style-probe")
                addProperty("method", "session/list")
                add("payload", JsonObject().apply {
                    add("args", JsonObject().apply { add("_request", JsonObject()) })
                })
            }.toString()
            val remoteRes = sendWithHostFallback(port, "session/list", remoteBody, deadline)
            val rpcStyle = when {
                remoteRes == null -> null // 传输失败 (dsh 未就绪): 下次再探
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
                    val legacyRes = sendWithHostFallback(port, "session.list", legacyBody, deadline)
                    if (legacyRes != null && legacyRes.status in 200..299) RpcStyle.LEGACY else null
                }
            }
            if (rpcStyle != null) {
                styleCache[port] = rpcStyle
                LOG.info("dsh rpc style on " + port + ": " + rpcStyle)
            }
            return rpcStyle
        }
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

    /** 距截止时间还剩多少毫秒 (已过期返回 <= 0) */
    private fun remaining(deadline: Long): Long = deadline - System.currentTimeMillis()

    /**
     * 单次请求的读超时: 默认 [RPC_READ_TIMEOUT_MS], 但不超过调用方的剩余预算。
     *
     * 不能像以前那样固定 4s: 多窗口同时开着 dsh、IDE 又在索引/编译时, dsh 应答可能慢到几秒,
     * 读超时会把"服务正忙"误判成"接口不可用", 整次工作空间同步跟着失败 (日志里的"同步不可用")。
     */
    private fun readTimeout(deadline: Long, budget: Long = RPC_READ_TIMEOUT_MS): Int =
        minOf(budget, remaining(deadline)).coerceAtLeast(MIN_READ_TIMEOUT_MS).toInt()

    /** 本端口偏好的 authority 主机名 (默认 localhost; localhost 传输不通后固定改用 127.0.0.1) */
    private fun preferredHost(port: Int): String = hostByPort[port] ?: DshWebAuth.HOST_LOCALHOST

    /** 按 authority 拼 /api 地址 */
    private fun apiUrl(port: Int, host: String, path: String): String =
        "http://" + DshWebAuth.authority(port, host) + path

    /**
     * 带认证地发一次请求: 先按本端口偏好的 authority, **传输层失败**时换 \`127.0.0.1\` 再试一次
     * (成功就固定下来)。
     *
     * 换 authority 必须用它自己的 cookie —— dsh 的认证 cookie 按 authority 绑定, 拿 localhost 的
     * cookie 去访问 127.0.0.1 只会得到 401。旧实现两处都错: 回退用的还是 localhost 的 cookie,
     * 拿到 401 后又当成"cookie 失效"把明明有效的 localhost cookie 作废重换, 最后仍然返回失败。
     */
    private fun sendWithHostFallback(port: Int, method: String, body: String, deadline: Long): PostResult? {
        val host = preferredHost(port)
        val primary = sendOnce(port, host, method, body, deadline)
        if (primary != null || host == DshWebAuth.HOST_LOOPBACK_IP) return primary
        val alt = DshWebAuth.HOST_LOOPBACK_IP
        val fallback = sendOnce(port, alt, method, body, deadline)
        if (fallback != null) {
            hostByPort[port] = alt
            LOG.info("dsh api on " + port + ": " + host + " unreachable, using " + alt)
        }
        return fallback
    }

    /**
     * 单次 /api 请求 (含 401 自愈): 401 时作废**该 authority** 的 cookie; 启动令牌还没到时在剩余
     * 预算内等令牌 (新版 dsh 启动中令牌往往晚于端口就绪, 实测可达十几秒), 换到新 cookie 后重试一次。
     * 传输层失败返回 null, 由调用方决定是否换 authority。
     */
    private fun sendOnce(port: Int, host: String, method: String, body: String, deadline: Long): PostResult? {
        val cookie = DshWebAuth.cookieHeader(port, host)
        val first = requestOnce(apiUrl(port, host, "/api/$method"), method, body, cookie, readTimeout(deadline))
        if (first == null || first.status != 401) return first
        // 401: 认证未就绪 (令牌未到 / 进程刚重启换了签名密钥) -> 作废该 authority 的 cookie 后重换一次
        DshWebAuth.onUnauthorized(port, host)
        if (DshWebAuth.token(port) == null) {
            DshWebAuth.awaitToken(port, minOf(DshWebAuth.TOKEN_WAIT_MS, remaining(deadline)).coerceAtLeast(0))
        }
        val fresh = DshWebAuth.cookieHeader(port, host) ?: return first
        if (fresh == cookie) return first
        LOG.info(
            "dsh api " + method + " -> HTTP 401, re-authenticated and retrying (" +
                DshWebAuth.authority(port, host) + ")"
        )
        return requestOnce(apiUrl(port, host, "/api/$method"), method, body, fresh, readTimeout(deadline))
    }

    /** 调用一次 /api POST 并返回响应体; 非 2xx 记一行日志后返回 null */
    private fun post(port: Int, method: String, body: String, deadline: Long): String? {
        val result = sendWithHostFallback(port, method, body, deadline)
        if (result == null || result.status !in 200..299) {
            LOG.warn(
                "dsh api " + method + " -> HTTP " + (result?.status ?: "transport error") +
                    " (" + DshWebAuth.authority(port, preferredHost(port)) + ")"
            )
            // 端点 404: 端口上的 dsh 可能换了版本 (探测缓存失效), 下次调用重新探测
            if (result?.status == 404) styleCache.remove(port)
            return null
        }
        return result.text
    }

    private class PostResult(val status: Int, val text: String?)

    /**
     * 带认证 cookie 的 GET 只读请求 (如 changes.summary); 401 时作废该 authority 的 cookie 重换一次,
     * 非 2xx 返回 null。404 属正常业务结果 (该轮没有摘要), 不据此重置 RPC 风格缓存。
     */
    private fun get(port: Int, path: String, query: String, timeoutMs: Long): String? {
        val deadline = System.currentTimeMillis() + timeoutMs
        val result = getWithHostFallback(port, path, query, deadline)
        if (result == null || result.status !in 200..299) {
            LOG.debug("dsh api GET " + path + " -> HTTP " + (result?.status ?: "transport error"))
            return null
        }
        return result.text
    }

    /** 与 [sendWithHostFallback] 同样的主机名回退策略 (GET 版) */
    private fun getWithHostFallback(port: Int, path: String, query: String, deadline: Long): PostResult? {
        val host = preferredHost(port)
        val primary = getOnce(port, host, path, query, deadline)
        if (primary != null || host == DshWebAuth.HOST_LOOPBACK_IP) return primary
        val alt = DshWebAuth.HOST_LOOPBACK_IP
        val fallback = getOnce(port, alt, path, query, deadline)
        if (fallback != null) hostByPort[port] = alt
        return fallback
    }

    /** 单次 GET (含 401 自愈, 与 [sendOnce] 同款) */
    private fun getOnce(port: Int, host: String, path: String, query: String, deadline: Long): PostResult? {
        val suffix = "/api/" + path + "?" + query
        val cookie = DshWebAuth.cookieHeader(port, host)
        val first = requestOnce(apiUrl(port, host, suffix), "GET", null, cookie, readTimeout(deadline))
        if (first == null || first.status != 401) return first
        DshWebAuth.onUnauthorized(port, host)
        val fresh = DshWebAuth.cookieHeader(port, host) ?: return first
        if (fresh == cookie) return first
        return requestOnce(apiUrl(port, host, suffix), "GET", null, fresh, readTimeout(deadline))
    }
    /** 对单个地址发起一次 HTTP 请求 (body 为 null 时发 GET, 否则 POST); 传输失败 (未拿到 HTTP 状态) 返回 null */
    private fun requestOnce(
        url: String,
        method: String,
        body: String?,
        cookie: String?,
        timeoutMs: Int,
    ): PostResult? {
        var conn: HttpURLConnection? = null
        return try {
            // URL(String) 构造器已废弃 (Java 20+), 改用 URI.toURL()
            val target = java.net.URI(url).toURL()
            conn = target.openConnection() as HttpURLConnection
            conn.requestMethod = if (body == null) "GET" else "POST"
            conn.connectTimeout = 2000
            conn.readTimeout = timeoutMs
            cookie?.let { conn.setRequestProperty("Cookie", it) }
            if (body != null) {
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json")
                conn.outputStream.use { it.write(body.toByteArray(StandardCharsets.UTF_8)) }
            }
            val code = conn.responseCode
            val text = try {
                conn.inputStream.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
            } catch (_: Throwable) {
                null
            }
            // 诊断: 非 2xx 时打印状态 (便于定位 HTTP 层失败原因)。401 是 dsh 启动期/认证未就绪的
            // 预期状态 —— 调用方会等启动令牌重换 cookie 再试, 不必刷整屏的请求/响应体
            if (code !in 200..299) {
                if (code == 401) {
                    LOG.info("dsh api " + method + " -> HTTP 401, url=" + url)
                } else {
                    val resp = text ?: try {
                        conn.errorStream?.bufferedReader(StandardCharsets.UTF_8)?.use { it.readText() }
                    } catch (_: Throwable) {
                        null
                    }
                    LOG.warn("dsh api " + method + " -> HTTP " + code + ", url=" + url + ", body=" + body?.take(240) + ", resp=" + resp?.take(160))
                }
            }
            PostResult(code, text)
        } catch (t: Throwable) {
            LOG.warn("dsh api $method failed (url=$url)", t)
            null
        } finally {
            try {
                conn?.disconnect()
            } catch (_: Throwable) {
            }
        }
    }
}
