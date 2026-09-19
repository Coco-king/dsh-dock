package cn.codecrab.plugins.dsh.toolwindow

import cn.codecrab.plugins.dsh.DshBundle
import com.intellij.openapi.diagnostic.Logger
import com.intellij.ui.jcef.JBCefBrowser

/**
 * dsh WebUI 左侧边栏「会话列表」的自动定位与当前工作空间高亮:
 *
 *  - **自动定位**: 打开侧边栏会话列表 (展开侧边栏 / 切回会话面板 / 页面加载完成) 时,
 *    以及之后**切换工作空间/会话**时, 把当前会话那一行滚动到列表可视区域
 *    (已在可视区内则不动, 不打扰用户);
 *  - **高亮当前工作空间分组**: 当前会话所在的工作空间分组标题行加高亮底色 + 左侧强调条,
 *    长列表下能一眼看出"我现在在哪个工作空间";
 *  - 当前会话所在分组被折叠 / 会话被"展开更多"折叠时, 先把它们展开再定位;
 *  - 只在"刚打开"时滚动一次; 打开期间高亮跟随当前会话 (用户点了别的会话就跟着移动)。
 *
 * 实现方式与 [DshWebUiFileOpen] 一致: 页面加载完成后注入一小段 JS, 用
 * dsh 客户端 CSS Module 类名后缀 (`_groupSection` / `_projectRow` / `_sessionRow` /
 * `_sessionOverflowButton`) 与 `role=treeitem` / `aria-selected` / `aria-expanded` 这些
 * 稳定语义属性识别元素 —— dsh 改版后若识别失效, 只是不再定位/高亮 (退回 dsh 原行为),
 * 不会有副作用。仅"分组被折叠导致当前会话行不在 DOM 里"这一种情形需要读 React fiber
 * 里的分组事实 (与文件路径桥接同款做法), 取不到时只跳过高亮。
 */
object DshWebUiSidebarLocate {

    private val LOG = Logger.getInstance(DshWebUiSidebarLocate::class.java)

    /**
     * 注入定位脚本 (主框架加载完成后调用; 同一文档重复调用幂等)。
     * 页面每次重新加载都会重新注入 (上一个文档的脚本随文档一起销毁)。
     */
    fun install(browser: JBCefBrowser, onLog: (String) -> Unit) {
        try {
            browser.cefBrowser.executeJavaScript(locateScript(), "dsh://ide-sidebar-locate.js", 0)
            onLog(DshBundle.message("log.sidebarLocateInjected"))
        } catch (t: Throwable) {
            LOG.warn("inject sidebar locate script failed", t)
            onLog(DshBundle.message("log.injectFailed", t.message ?: "null"))
        }
    }

    /** 注入脚本 (逐行 trimMargin; JS 里不使用 `$`, 避免与 Kotlin 模板冲突) */
    private fun locateScript(): String = """
        |(function () {
        |  if (window.__dshIdeSidebarLocateBound) return;
        |  window.__dshIdeSidebarLocateBound = true;
        |
        |  var MARK = "data-dsh-ide-current-workspace";
        |  var STYLE_ID = "dsh-ide-sidebar-locate-style";
        |  var TICK_MS = 1200;
        |  var DEBOUNCE_MS = 400;
        |
        |  // 高亮样式: 复用 dsh 自己的主题变量 (明暗主题都自然)。标记用自定义 data 属性而不是加 class ——
        |  // React 重渲染会重写 className 把 class 抹掉, 但它不管这个属性, 标记能稳定留着。
        |  if (!document.getElementById(STYLE_ID)) {
        |    var style = document.createElement("style");
        |    style.id = STYLE_ID;
        |    style.textContent = [
        |      "div[" + MARK + "]{",
        |      "background:var(--dsw-alias-interactive-bg-active, rgba(127,140,170,.20))!important;",
        |      "box-shadow:inset 2px 0 0 0 var(--dsw-alias-state-business-primary, #4a7dff);",
        |      "}",
        |      "div[" + MARK + "] [class*='_title']{",
        |      "color:var(--dsw-alias-state-business-primary, #4a7dff);font-weight:600;",
        |      "}"
        |    ].join("");
        |    (document.head || document.documentElement).appendChild(style);
        |  }
        |
        |  function q1(sel, root) { try { return (root || document).querySelector(sel); } catch (e) { return null; } }
        |  function qa(sel, root) { try { return (root || document).querySelectorAll(sel); } catch (e) { return []; } }
        |
        |  // CSS Module 类名形如 "bhn1Oq_groupSection" (哈希前缀 + 语义后缀), 按后缀识别
        |  function classEnds(el, suffix) {
        |    if (!el || el.nodeType !== 1) return false;
        |    var cls = el.className;
        |    if (typeof cls !== "string") return false;
        |    var parts = cls.split(/\s+/);
        |    for (var i = 0; i < parts.length; i++) {
        |      var p = parts[i];
        |      if (p.length >= suffix.length && p.slice(p.length - suffix.length) === suffix) return true;
        |    }
        |    return false;
        |  }
        |
        |  function visible(el) {
        |    if (!el || !el.getBoundingClientRect) return false;
        |    var r = el.getBoundingClientRect();
        |    return r.width > 0 && r.height > 0;
        |  }
        |
        |  // 会话列表是否"打开着": 列表可见且不是搜索结果列表 (搜索时列表被搜索结果替换, 不介入)
        |  function sessionList() {
        |    var lists = qa('div[role="tree"]');
        |    for (var i = 0; i < lists.length; i++) {
        |      if (!visible(lists[i])) continue;
        |      if (classEnds(lists[i], "_searchTree")) continue;
        |      return lists[i];
        |    }
        |    return null;
        |  }
        |
        |  // 侧边栏里有其他全局面板处于激活态时, 会话列表不是当前内容, 不介入
        |  function panelActive(list) {
        |    var scope = list;
        |    for (var el = list; el && el !== document.body; el = el.parentElement) {
        |      if (classEnds(el, "_regionArea")) { scope = el.parentElement || el; break; }
        |    }
        |    return q1('button[class*="_panelRow"][class*="_panelActive"]', scope) !== null;
        |  }
        |
        |  // 当前会话行 (dsh 用 aria-selected 标记当前会话)
        |  function currentRow(list) {
        |    var rows = qa('div[role="treeitem"][aria-selected="true"]', list);
        |    for (var i = 0; i < rows.length; i++) if (classEnds(rows[i], "_sessionRow")) return rows[i];
        |    return rows.length > 0 ? rows[0] : null;
        |  }
        |
        |  function groupSectionOf(row) {
        |    for (var el = row; el && el !== document.body; el = el.parentElement) {
        |      if (classEnds(el, "_groupSection")) return el;
        |    }
        |    return null;
        |  }
        |
        |  // React fiber 里读"该分组的当前会话是否是它" (dsh 内部事实; 取不到返回 false)
        |  function fiberOf(el) {
        |    for (var k in el) if (k.indexOf("__reactFiber${'$'}") === 0) return el[k];
        |    return null;
        |  }
        |  function groupContainsCurrent(rowEl) {
        |    for (var f = fiberOf(rowEl), depth = 0; f && depth < 10; f = f.return, depth++) {
        |      var p = f.memoizedProps;
        |      if (p && p.group && p.group.containsCurrent === true) return true;
        |    }
        |    return false;
        |  }
        |
        |  // 当前会话所在的分组 (当前会话行没渲染出来时也能定位到分组)
        |  function currentSection(list) {
        |    var sections = qa('div[class*="_groupSection"]', list);
        |    for (var i = 0; i < sections.length; i++) {
        |      // 展开且含当前会话时, dsh 会把文件夹图标标成活动色
        |      var row = q1('div[class*="_projectRow"]', sections[i]);
        |      if (row && q1('[class*="_folderActive"]', row) !== null) return sections[i];
        |    }
        |    for (var j = 0; j < sections.length; j++) {
        |      var r2 = q1('div[class*="_projectRow"]', sections[j]);
        |      if (r2 && groupContainsCurrent(r2)) return sections[j];
        |    }
        |    return null;
        |  }
        |
        |  // 当前会话行没渲染出来 (分组被折叠 / 会话被"展开更多"折叠): 展开它 (已展开则不会再点)
        |  function expandCurrent(list) {
        |    var section = currentSection(list);
        |    if (!section) return false;
        |    var changed = false;
        |    var row = q1('div[class*="_projectRow"]', section);
        |    if (row && row.getAttribute("aria-expanded") === "false") { row.click(); changed = true; }
        |    var more = q1('button[class*="_sessionOverflowButton"]', section);
        |    if (more && more.getAttribute("aria-expanded") === "false") { more.click(); changed = true; }
        |    return changed;
        |  }
        |
        |  function scrollerOf(el) {
        |    for (var p = el.parentElement; p && p !== document.body; p = p.parentElement) {
        |      var s = window.getComputedStyle(p);
        |      if (!s) continue;
        |      if ((s.overflowY === "auto" || s.overflowY === "scroll") && p.scrollHeight > p.clientHeight + 1) return p;
        |    }
        |    return null;
        |  }
        |
        |  // 把当前会话行滚到列表可视区中间 (已完整可见则不动)
        |  function reveal(row) {
        |    var scroller = scrollerOf(row);
        |    if (!scroller) { try { row.scrollIntoView({ block: "center" }); } catch (e) {} return; }
        |    var rr = row.getBoundingClientRect();
        |    var sr = scroller.getBoundingClientRect();
        |    if (rr.top >= sr.top - 1 && rr.bottom <= sr.bottom + 1) return;
        |    var top = scroller.scrollTop + (rr.top - sr.top) - (scroller.clientHeight - rr.height) / 2;
        |    try {
        |      scroller.scrollTo({ top: Math.max(0, top), behavior: "smooth" });
        |    } catch (e) {
        |      scroller.scrollTop = Math.max(0, top);
        |    }
        |  }
        |
        |  // 高亮当前工作空间分组标题行 (同时清掉旧标记, 只保留一个)
        |  function mark(section) {
        |    var target = section ? q1('div[class*="_projectRow"]', section) : null;
        |    var marked = qa("[" + MARK + "]");
        |    for (var i = 0; i < marked.length; i++) {
        |      if (marked[i] !== target) marked[i].removeAttribute(MARK);
        |    }
        |    if (target && !target.hasAttribute(MARK)) target.setAttribute(MARK, "1");
        |  }
        |
        |  var wasOpen = false;
        |  var pending = false;
        |  // 上一次定位到的当前会话行 / 其所在分组: 用于识别"当前会话变了" (切换了工作空间或会话),
        |  // 这时也要重新定位 (已完整可见时 reveal 不会滚动, 不打扰用户)
        |  var lastRow = null;
        |  var lastSection = null;
        |
        |  function tick() {
        |    var list = sessionList();
        |    if (!list || panelActive(list)) {
        |      wasOpen = false;
        |      pending = false;
        |      lastRow = null;
        |      lastSection = null;
        |      mark(null);
        |      return;
        |    }
        |    if (!wasOpen) { wasOpen = true; pending = true; }
        |    var row = currentRow(list);
        |    if (row) {
        |      if (pending || row !== lastRow) { pending = false; reveal(row); }
        |      lastRow = row;
        |      lastSection = groupSectionOf(row);
        |      mark(lastSection);
        |      return;
        |    }
        |    lastRow = null;
        |    // 当前会话行还没渲染出来 (数据未到 / 分组被折叠): 展开后下个 tick 再定位
        |    var section = currentSection(list);
        |    var switched = section !== lastSection;
        |    lastSection = section;
        |    if ((pending || switched) && expandCurrent(list)) return;
        |    mark(section);
        |  }
        |
        |  var scheduled = null;
        |  function schedule(delay) {
        |    if (scheduled !== null) return;
        |    scheduled = setTimeout(function () { scheduled = null; tick(); }, delay);
        |  }
        |
        |  try {
        |    new MutationObserver(function () { schedule(DEBOUNCE_MS); })
        |      .observe(document.documentElement, {
        |        childList: true, subtree: true, attributes: true,
        |        attributeFilter: ["class", "aria-selected", "aria-expanded", "style"]
        |      });
        |  } catch (e) {}
        |  // 兜底轮询: 观察器漏掉的场景 (如组件原地重渲染) 也能补上
        |  setInterval(tick, TICK_MS);
        |  // 工具窗口/页面从隐藏变可见 (CEF 切换可见性) 时按"重新打开"处理
        |  document.addEventListener("visibilitychange", function () {
        |    if (document.visibilityState === "visible") { wasOpen = false; schedule(DEBOUNCE_MS); }
        |  });
        |  tick();
        |})();
    """.trimMargin()
}
