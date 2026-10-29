package cn.codecrab.plugins.dsh.sync

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.changes.VcsDirtyScopeManager
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile

/**
 * 把 dsh 侧的文件改动与 git 操作同步到 IDE 的版本控制视图 (提交页 / 提交记录)。
 *
 * 背景: dsh 在终端里跑 git (提交、切换分支、暂存等) 或改写工作区文件之后, IDE 的提交页
 * (Local Changes) 与提交记录不会自己更新 —— 这两处各自缓存了"脏文件范围"和仓库状态,
 * 只有明确通知才会重算。仅刷新文件内容也不够: 提交这类操作一个工作区文件都没改, 变的是
 * 仓库状态 (HEAD、索引、引用), 因此还需要让 IDE 重新读取 .git。
 *
 * 两档入口:
 *  - [filesChanged]: 只把 dsh 改过的文件标脏, 提交页里对应条目重算 (便宜, 用于单文件写入);
 *  - [refreshAll]: 全量重扫 + 刷新 .git 目录, 让提交页与提交记录重新读取仓库状态
 *    (用于 git 操作完成, 以及每轮改动同步之后)。
 *
 * 线程与容错: .git 的 VFS 刷新是磁盘 I/O, 由调用线程执行; 版本控制 API 一律切到 EDT。
 * 全过程吞掉异常 —— 项目没有启用版本控制时这些服务本就没有意义, 不能反过来影响文件同步。
 */
object DshVcsRefresh {

    private val LOG = Logger.getInstance(DshVcsRefresh::class.java)

    /** dsh 改过的这些文件: 只标脏这些文件, 让提交页刷新对应条目 (比全量重扫便宜) */
    fun filesChanged(project: Project, files: List<VirtualFile>) {
        if (project.isDisposed) return
        val valid = files.filter { it.isValid }
        if (valid.isEmpty()) return
        onEdt(project) {
            val scope = VcsDirtyScopeManager.getInstance(project)
            for (file in valid) scope.fileDirty(file)
        }
    }

    /**
     * 全量刷新版本控制视图: 提交页重扫, 提交记录重新读取仓库状态。
     * dsh 完成 git 操作 (提交 / 切分支 / 撤销) 后工作区文件可能没变, 只有这样才能看到结果。
     */
    fun refreshAll(project: Project) {
        if (project.isDisposed) return
        refreshGitDirectory(project)
        onEdt(project) {
            // markEverythingDirty 就会让提交页按"全部重算"处理;
            // 不再调 ChangeListManager.scheduleUpdate() —— 新版 IDE 已移除该方法 (二进制不兼容)
            VcsDirtyScopeManager.getInstance(project).markEverythingDirty()
        }
    }

    /**
     * 刷新 .git 目录本身: IDE 的版本控制组件通过它感知提交、切分支等引用变化
     * (工作区用 .git 文件指向别处的布局同样适用)。只刷直接子项, 不递归进 objects。
     */
    private fun refreshGitDirectory(project: Project) {
        val base = project.basePath ?: return
        try {
            val git = LocalFileSystem.getInstance()
                .refreshAndFindFileByPath(base.replace('\\', '/') + "/.git") ?: return
            VfsUtil.markDirtyAndRefresh(false, false, true, git)
        } catch (t: Throwable) {
            LOG.debug("refresh .git failed", t)
        }
    }

    /** 切到 EDT 执行版本控制操作 (项目已关闭或服务不可用时静默跳过) */
    private fun onEdt(project: Project, action: () -> Unit) {
        ApplicationManager.getApplication().invokeLater {
            if (project.isDisposed) return@invokeLater
            try {
                action()
            } catch (t: Throwable) {
                LOG.debug("vcs refresh failed", t)
            }
        }
    }
}
