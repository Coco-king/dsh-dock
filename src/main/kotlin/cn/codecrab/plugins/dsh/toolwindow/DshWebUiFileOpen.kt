package cn.codecrab.plugins.dsh.toolwindow

import cn.codecrab.plugins.dsh.DshBundle
import cn.codecrab.plugins.dsh.reference.DshReference
import cn.codecrab.plugins.dsh.settings.DshSettingsState
import com.google.gson.JsonParser
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.editor.ScrollType
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.ui.jcef.JBCefBrowser
import com.intellij.ui.jcef.JBCefBrowserBase
import com.intellij.ui.jcef.JBCefJSQuery

/**
 * dsh WebUI 里「文件路径」与「打开」按钮的 IDE 联动: 点击后在 IDE 编辑器中打开该文件,
 * 而不是让 dsh 打开它自己的侧边栏预览 (设置 [DshSettingsState.openFileInIde] 可关掉)。
 *
 * dsh 的这两类按钮都是页面内行为 (React 的 onClick, 没有 URL/网络请求可拦), 因此:
 *  1. 页面加载完成后注入一小段 JS, 捕获阶段监听点击 —— 命中后 `preventDefault` +
 *     `stopPropagation`, dsh 自己的处理不会执行;
 *  2. 命中的目标经 [JBCefJSQuery] 回传本对象: 路径取按钮文本 / 交付卡片 aria-label
 *     (dsh 显示时已相对化, 插件按项目根还原); 行号优先从 dsh 界面内部 React 数据取
 *     read 行的「读取起始行」, 否则解析文本自带后缀 (`path:12` / `path#L12`);
 *     编辑/写入类工具还会带上 diff 里的新增内容 (new_string / new_str / content), 供 IDE 定位;
 *  3. 在 IDE 侧换算并打开: 相对路径按项目根目录展开, 绝对路径按启动模式换算
 *     (见 [DshReference.ideaPathFromDsh]); 编辑内容在文件中搜到则跳转并选中该段, 否则按
 *     行号定位 ([OpenFileDescriptor]), 都没有时打开文件开头;
 *  4. IDE 打不开 (文件不在本机、是目录、设置里关掉了该行为) 时走查询的失败回调, JS 会
 *     对同一个按钮补发一次带标记的点击, 让 dsh 照旧打开侧边栏预览 —— 不会"点了没反应"。
 *
 * 识别基于 dsh 客户端的 CSS Module 类名后缀 (`_fileLink` / `_open` / `_fileMention`) 与
 * title/aria-label 里的路径信息 (带斜杠与扩展名的路径形文本): dsh 改版后若识别失效,
 * 只是不再拦截 (退回 dsh 原行为), 不会有副作用。
 */
object DshWebUiFileOpen {

    private val LOG = Logger.getInstance(DshWebUiFileOpen::class.java)

    /** 查询失败回调里用的错误码 (非 0 即失败, 具体值无关紧要) */
    private const val ERR_CANNOT_OPEN = 1

    /** 行号后缀: `path#L12` / `path#L12-14`; 普通后缀: `path:12` / `path:12:3` */
    private val HASH_LINE = Regex("^(.+?)#L(\\d+)(?:-\\d+)?$")
    private val COLON_LINE = Regex("^(.+?):(\\d+)(?::\\d+)?$")
    private val WINDOWS_DRIVE = Regex("^[A-Za-z]:")

    /**
     * 页面 → IDE 的 JS 通道 (一个浏览器一个)。
     *
     * 必须在浏览器实体创建**之前**建立 (见 [createChannel]), 因此由创建浏览器的一方持有;
     * 预热浏览器被面板接管时随 [DshWebUiWarmup.Warmed] 一并交给面板。
     */
    class Channel internal constructor(
        private val query: JBCefJSQuery,
        @Volatile private var project: Project,
        private val onLog: (String) -> Unit,
    ) {

        /**
         * 注入点击拦截脚本 (主框架加载完成后调用; 同一文档重复调用幂等)。
         * [project] = 当前页面归属的项目: 面板接管预热页面时工作空间可能已切到本项目, 需要更新。
         */
        fun install(browser: JBCefBrowser, project: Project) {
            this.project = project
            try {
                // 注意: inject 的第一个参数是 JS 端的变量名, 必须与 bridgeScript 里
                // __dshIdeFileOpenSend 的函数参数名一致, 否则被调用时抛 ReferenceError,
                // 每次点击都会走兜底交给 dsh 侧边栏
                val call = query.inject(
                    "payload",
                    "function () {}",
                    "function () { if (window.__dshIdeFileOpenFallback) window.__dshIdeFileOpenFallback(); }",
                )
                browser.cefBrowser.executeJavaScript(bridgeScript(call), "dsh://ide-file-open.js", 0)
                onLog(DshBundle.message("log.fileOpenBridgeInjected"))
            } catch (t: Throwable) {
                onLog(DshBundle.message("log.injectFailed", t.message ?: "null"))
            }
        }

        /**
         * JS 回传的一次点击: 能打开就交给 IDE 打开, 否则以失败应答让 JS 走 dsh 原行为。
         * 返回 null 会让 [JBCefJSQuery] 按"成功但无内容"处理, 因此失败必须显式构造错误应答。
         */
        internal fun handle(rawPath: String): JBCefJSQuery.Response {
            val settings = DshSettingsState.getInstance()
            val enabled = settings.openFileInIde
            // 注入脚本的通道自检 (probe): 只回显「通道就绪」, 不做打开
            if (enabled && isProbe(rawPath)) {
                onLog(DshBundle.message("log.fileOpenBridgeReady"))
                return JBCefJSQuery.Response("ok")
            }
            val target = if (enabled) parseRequest(rawPath) else null
            val opened = target?.let { openInIde(project, it, settings.launchMode) } == true
            if (opened && target != null) {
                onLog(DshBundle.message("log.fileOpenIde", target.path))
            } else if (enabled) {
                onLog(DshBundle.message("log.fileOpenFallback", rawPath))
            }
            return if (opened) {
                JBCefJSQuery.Response("ok")
            } else {
                JBCefJSQuery.Response(null, ERR_CANNOT_OPEN, "cannot open in IDE")
            }
        }
    }

    /**
     * 建立 JS 通道。**必须在 JBCefBrowser 创建后、浏览器实体创建前调用**
     * (JBCefJSQuery 的注入函数只在浏览器创建前登记才有效, 否则 JS 侧调用会报
     * "window.cefQuery_... is not a function")。
     */
    fun createChannel(browser: JBCefBrowser, project: Project, onLog: (String) -> Unit): Channel? = try {
        // 使用非废弃的 create(JBCefBrowserBase): JBCefBrowser 是该基类的子类, 显式转型以避开
        // 已标"计划移除"的旧重载 (Marketplace 校验告警); 旧重载在 2023.1 上同样可用,
        // 若未来最低版本提升可去掉转型
        val query = JBCefJSQuery.create(browser as JBCefBrowserBase)
        val channel = Channel(query, project, onLog)
        query.addHandler { path -> channel.handle(path) }
        channel
    } catch (t: Throwable) {
        LOG.warn("create JCEF js query for file open failed", t)
        null
    }

    /** 注入脚本: 捕获阶段拦截两类按钮的点击, 把目标 (路径+行号) 交给 IDE; IDE 打不开时补发点击交回 dsh */
    private fun bridgeScript(sendCall: String): String = """
        |(function () {
        |  var BYPASS = "__dshIdeFileOpenBypass";
        |  window.__dshIdeFileOpenFallback = function () {
        |    var el = window.__dshIdeFileOpenLast;
        |    window.__dshIdeFileOpenLast = null;
        |    if (!el) return;
        |    try {
        |      var ev = new MouseEvent("click", { bubbles: true, cancelable: true, view: window });
        |      ev[BYPASS] = true;
        |      el.dispatchEvent(ev);
        |    } catch (err) {}
        |  };
        |  window.__dshIdeFileOpenSend = function (payload) { $sendCall };
        |  if (window.__dshIdeFileOpenBound) return;
        |  window.__dshIdeFileOpenBound = true;
        |  // 行号 / 编辑新增内容: 沿 React fiber 链尽力取 (dsh 的 read 行带读取起始行号,
        |  // edit/write/str_replace_editor 行带 diff 模型与工具参数), 取不到时行号 0、
        |  // 新增内容为空, 不影响基本打开。
        |  function fiberOf(el) {
        |    for (var k in el) {
        |      if (k.indexOf("__reactFiber$") === 0) return el[k];
        |    }
        |    return null;
        |  }
        |  function newTextOf(p) {
        |    try {
        |      // diff 模型: {card:{diffs:[{path,oldText,newText}]}}
        |      var diffs = p.diff && p.diff.card && p.diff.card.diffs;
        |      if (Array.isArray(diffs)) {
        |        for (var i = 0; i < diffs.length; i++) {
        |          var nt = diffs[i].newText;
        |          if (typeof nt === "string" && nt !== "") return nt;
        |        }
        |      }
        |      // 原始工具参数: new_string (edit) / new_str (str_replace_editor) / content (write) / file_text
        |      var call = p.block && typeof p.block === "object" ? p.block.call : null;
        |      var raw = call && typeof call.argsRaw === "string" ? call.argsRaw
        |        : p.block && typeof p.block.argsRaw === "string" ? p.block.argsRaw : null;
        |      if (raw) {
        |        var o = JSON.parse(raw);
        |        var keys = ["new_string", "new_str", "content", "file_text"];
        |        for (var j = 0; j < keys.length; j++) {
        |          var v = o[keys[j]];
        |          if (typeof v === "string" && v !== "") return v;
        |        }
        |      }
        |    } catch (err) {}
        |    return null;
        |  }
        |  // 路径来源: title / aria-label 里的完整路径优先 (新版 dsh 的文件提及按钮只显示文件名,
        |  // 完整路径在 title="<path>" 或 aria-label="打开 <path>" 里); 其次按钮文本
        |  // (_fileLink 显示相对路径时直接用; 只有文件名的文本无法还原路径, 跳过)。
        |  function pathToken(s) {
        |    if (!s) return "";
        |    var m = s.match(/[^\s"'`]*[\\\/][^\s"'`]*\.[^\s"'`]*/);
        |    return m ? m[0] : "";
        |  }
        |  function pathOf(el) {
        |    var t = pathToken(el.getAttribute("title")) || pathToken(el.getAttribute("aria-label"));
        |    if (t !== "") return t;
        |    var cls = typeof el.className === "string" ? el.className : "";
        |    if (cls.indexOf("_fileLink") !== -1) return (el.textContent || "").trim();
        |    return "";
        |  }
        |  function isFileButton(el) {
        |    var cls = typeof el.className === "string" ? el.className : "";
        |    if (cls.indexOf("_fileMention") !== -1 || cls.indexOf("_fileLink") !== -1 || cls.indexOf("_open") !== -1) {
        |      return true;
        |    }
        |    // 其他可能承载文件路径的按钮: title/aria-label 里含路径形 token (带斜杠与扩展名) 才拦截
        |    return pathToken(el.getAttribute("title")) !== "" || pathToken(el.getAttribute("aria-label")) !== "";
        |  }
        |  function targetOf(el) {
        |    var path = pathOf(el);
        |    var line = 0, newText = "";
        |    for (var f = fiberOf(el), depth = 0; f && depth < 12; f = f.return, depth++) {
        |      var p = f.memoizedProps;
        |      if (p && typeof p === "object") {
        |        if (line === 0 && typeof p.filePathLine === "number" && p.filePathLine > 0) line = p.filePathLine;
        |        if (newText === "") {
        |          var t = newTextOf(p);
        |          if (t !== null) newText = t;
        |        }
        |      }
        |    }
        |    if (newText.length > 100000) newText = newText.slice(0, 100000);
        |    return { path: path, line: line, newText: newText };
        |  }
        |  document.addEventListener("click", function (ev) {
        |    try {
        |      if (ev[BYPASS]) return;
        |      if (window.__dshIdeFileOpenSendBroken) return;
        |      var target = ev.target;
        |      if (!target || !target.closest) return;
        |      var el = target.closest("button");
        |      if (!el || !isFileButton(el)) return;
        |      var t = targetOf(el);
        |      if (!t.path) return;
        |      ev.preventDefault();
        |      ev.stopPropagation();
        |      window.__dshIdeFileOpenLast = el;
        |      window.__dshIdeFileOpenSend(JSON.stringify({ path: t.path, line: t.line, newText: t.newText }));
        |    } catch (err) {
        |      window.__dshIdeFileOpenFallback();
        |    }
        |  }, true);
        |  // 自检: 立即探测一次通道, 结果由 IDE 侧日志回显; 失败则标记, 后续点击直接放行给 dsh
        |  try {
        |    window.__dshIdeFileOpenSend(JSON.stringify({ probe: true }));
        |  } catch (err) {
        |    window.__dshIdeFileOpenSendBroken = true;
        |  }
        |})();
    """.trimMargin()

    /** 一次点击的目标: 路径 + 行号 (0 = 未知) + 编辑类工具写入的内容 (用于定位选中, 空 = 无) */
    private class Target(val path: String, val line: Int, val newText: String?)

    /** 编辑内容定位搜索的长度上限 (超长内容 (如整文件覆写) 不再做全文搜索, 按行号/开头打开) */
    private const val MAX_EDIT_LOCATE_LEN = 100_000

    /**
     * 在 IDE 中打开点到的文件。
     * 定位优先级: 编辑工具的「新增内容」在文件中搜到 -> 跳转并选中该段; 否则按 [Target.line]
     * 定位 (0 = 未知, 打开文件开头)。
     * @return 是否已交给 IDE 打开 (false = 路径解析不到或不是可打开的本地文件, 调用方应兜底)
     */
    private fun openInIde(project: Project, target: Target, launchMode: String): Boolean {
        val ideaPath = resolveIdeaPath(project, target.path, launchMode) ?: return false
        val file = try {
            LocalFileSystem.getInstance().refreshAndFindFileByPath(ideaPath)
        } catch (t: Throwable) {
            LOG.warn("resolve file failed: $ideaPath", t)
            null
        } ?: return false
        if (!file.isValid || file.isDirectory) return false
        ApplicationManager.getApplication().invokeLater {
            if (project.isDisposed || !file.isValid) return@invokeLater
            try {
                val descriptor = OpenFileDescriptor(project, file, 0)
                val editor = FileEditorManager.getInstance(project).openTextEditor(descriptor, true)
                if (editor == null) {
                    // 没有可用编辑器 (罕见): 退回导航式打开
                    try {
                        descriptor.navigate(true)
                    } catch (_: Throwable) {
                    }
                    return@invokeLater
                }
                val doc = editor.document
                var located = false
                val needle = target.newText
                if (!needle.isNullOrEmpty()) {
                    try {
                        val idx = doc.text.indexOf(needle)
                        if (idx >= 0) {
                            editor.caretModel.moveToOffset(idx)
                            editor.selectionModel.setSelection(idx, idx + needle.length)
                            editor.scrollingModel.scrollToCaret(ScrollType.CENTER)
                            located = true
                        }
                    } catch (t: Throwable) {
                        LOG.warn("locate edit content failed: ${file.path}", t)
                    }
                }
                if (!located) {
                    // 行号定位 (0 = 文件开头); 行号超出文档行数时同样落到结尾兜底
                    val offset = if (target.line > 0 && target.line <= doc.lineCount) {
                        try {
                            doc.getLineStartOffset(target.line - 1)
                        } catch (_: Throwable) {
                            0
                        }
                    } else {
                        0
                    }
                    editor.caretModel.moveToOffset(offset)
                    editor.selectionModel.removeSelection()
                    editor.scrollingModel.scrollToCaret(ScrollType.CENTER)
                }
            } catch (t: Throwable) {
                LOG.warn("open file in editor failed: ${file.path}", t)
            }
        }
        return true
    }

    /** JS 通道自检载荷 (`{"probe":true}`): 用于确认页面脚本与 IDE 的桥接通道可用 */
    private fun isProbe(raw: String): Boolean = try {
        val o = JsonParser.parseString(raw).asJsonObject
        o.get("probe")?.takeIf { it.isJsonPrimitive }?.asBoolean == true
    } catch (_: Throwable) {
        false
    }

    /**
     * 解析 JS 回传的载荷: `{path, line, newText}` JSON —— 行号优先取 dsh 界面内部数据
     * (read 行的读取起始行), 没拿到时兜底解析文本自带的行号后缀; `newText` 是编辑类工具
     * 写入的内容 (用于在文件中定位选中), 超长时截断丢弃搜索。
     */
    private fun parseRequest(raw: String): Target? {
        var path: String? = null
        var line = 0
        var newText: String? = null
        try {
            val o = JsonParser.parseString(raw).asJsonObject
            o.get("path")?.takeIf { it.isJsonPrimitive }?.let { path = it.asString }
            o.get("line")?.takeIf { it.isJsonPrimitive }?.let { line = it.asInt }
            o.get("newText")?.takeIf { it.isJsonPrimitive }?.let { newText = it.asString }
        } catch (_: Throwable) {
            path = raw // 载荷异常 (非 JSON): 按纯路径兜底
        }
        val text = path?.trim()?.trim('`', '"', '\'') ?: return null
        if (text.isEmpty()) return null
        val needle = newText?.takeIf { it.isNotEmpty() && it.length <= MAX_EDIT_LOCATE_LEN }
        if (line > 0) return Target(text, line, needle)
        val (p, l) = splitPathAndLine(text)
        return Target(p, l, needle)
    }

    /**
     * 拆出路径与文本自带的行号后缀 (如 `path:12` / `path:12:3` / `path#L12` / `path#L12-14`),
     * 并去掉包裹的引号/反引号; 无行号时行号为 0。
     */
    private fun splitPathAndLine(raw: String): Pair<String, Int> {
        val text = raw.trim().trim('`', '"', '\'')
        if (text.isEmpty()) return "" to 0
        HASH_LINE.matchEntire(text)?.let {
            return it.groupValues[1] to (it.groupValues[2].toIntOrNull() ?: 0)
        }
        COLON_LINE.matchEntire(text)?.let {
            return it.groupValues[1] to (it.groupValues[2].toIntOrNull() ?: 0)
        }
        return text to 0
    }

    /**
     * 把 dsh 界面上的路径换算成 IDEA 侧路径:
     *  - 相对路径 (最常见, dsh 按会话工作空间显示) -> 按项目根目录展开
     *  - 绝对路径 -> 按启动模式换算 (WSL: /mnt/<盘符>/... ; Windows: 同路径)
     *  - `~/...` -> Windows 模式下用用户目录, WSL 模式的家目录在 WSL 内无法可靠寻址
     *  - 界面上被截断的路径 (`...` 开头) 无法还原
     */
    private fun resolveIdeaPath(project: Project, path: String, launchMode: String): String? {
        val normalized = path.replace('\\', '/')
        if (normalized.startsWith("/") || WINDOWS_DRIVE.containsMatchIn(normalized)) {
            return DshReference.ideaPathFromDsh(normalized, launchMode, project.basePath)
        }
        if (normalized.startsWith("~/")) {
            if (launchMode == DshSettingsState.MODE_WSL) return null
            val home = System.getProperty("user.home")?.replace('\\', '/') ?: return null
            return home + normalized.substring(1)
        }
        if (normalized.startsWith("...") || normalized.startsWith("…")) return null
        val base = project.basePath?.replace('\\', '/') ?: return null
        return "$base/${normalized.removePrefix("./")}"
    }
}
