package cn.codecrab.plugins.dsh.server

import com.intellij.openapi.diagnostic.Logger
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.Collections

/**
 * 本窗口的本地端口转发: 把 `127.0.0.1:<listenPort>` 上的连接原样转给共用 dsh 的 `127.0.0.1:<targetPort>`。
 *
 * 为什么需要它 (多窗口架构的根):
 *  - dsh 的"当前工作空间/当前会话"存在**浏览器侧** (`dsh.sessions.current`), 而浏览器本地存储按
 *    **origin (协议+主机+端口)** 隔离 —— 多个窗口共用一个端口时, 它们读写的是同一份选择,
 *    会互相把对方拉到自己的会话上, 于是来回重载 (1.2.x 的"窗口打架");
 *  - dsh 的工作空间注册表 (`~/.dsh/storages/workspace.json`) 是"进程启动读入内存 + 整份覆写",
 *    假定一个 home 只有一个写者 —— 多个窗口各起一个 dsh 进程时, 后写者会用陈旧快照抹掉别人的
 *    改动, 会话因此掉进"未分组"。
 *
 * 两件事可以拆开: **端口负责页面状态隔离, 进程负责注册表单写者**。每个窗口用自己的端口
 * (本类) 访问同一个 dsh 进程, 两个需求同时成立。
 *
 * 实现选择纯字节中继 (不解析 HTTP): RPC、SSE、WebSocket 全部透明通过; 请求里的 Host 头保持
 * `localhost:<listenPort>` 不变 —— dsh 的 Host 围栏接受任意 loopback 端口, 认证 cookie 也按
 * 这个 authority 绑定, 因此页面与该窗口的接口调用天然一致。只监听 `127.0.0.1`, 不对局域网暴露。
 */
internal class DshPortRelay(
    /** 本窗口对页面/接口暴露的端口 (origin 的一部分) */
    private val listenPort: Int,
    /** 共用 dsh 进程实际监听的端口 */
    private val targetPort: Int,
) {

    private val LOG = Logger.getInstance(DshPortRelay::class.java)

    @Volatile
    private var server: ServerSocket? = null

    /** 正在转发中的连接 (停止时一并关闭, 否则页面上的长连接会挂着) */
    private val connections: MutableSet<Socket> = Collections.synchronizedSet(HashSet())

    @Volatile
    private var acceptThread: Thread? = null

    val isRunning: Boolean
        get() = server?.isClosed == false

    /**
     * 开始监听并转发。
     * @return true 表示已监听; false 表示端口被占用等 (调用方应换端口重试)
     */
    fun start(): Boolean {
        if (isRunning) return true
        if (listenPort !in 1..65535 || targetPort !in 1..65535) return false
        val socket = try {
            ServerSocket()
        } catch (t: Throwable) {
            LOG.warn("create relay socket failed", t)
            return false
        }
        try {
            // 只绑 loopback: 转发端口只给本机的内嵌浏览器用
            socket.bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), listenPort), 128)
        } catch (t: Throwable) {
            try {
                socket.close()
            } catch (_: Throwable) {
            }
            LOG.info("relay port " + listenPort + " is not available: " + (t.message ?: t.javaClass.simpleName))
            return false
        }
        server = socket
        acceptThread = Thread({ acceptLoop(socket) }, "dsh-plugin-relay-" + listenPort).apply {
            isDaemon = true
            start()
        }
        LOG.info("relay " + listenPort + " -> 127.0.0.1:" + targetPort)
        return true
    }

    /** 停止监听并断开所有在转发的连接 (页面会显示连接断开) */
    fun stop() {
        val socket = server ?: return
        server = null
        try {
            socket.close()
        } catch (_: Throwable) {
        }
        acceptThread?.interrupt()
        acceptThread = null
        synchronized(connections) {
            for (c in connections.toList()) closeQuietly(c)
            connections.clear()
        }
    }

    private fun acceptLoop(socket: ServerSocket) {
        while (!socket.isClosed) {
            val client = try {
                socket.accept()
            } catch (_: Throwable) {
                break // 停止监听 (close) 时 accept 抛异常, 正常退出
            }
            try {
                bridge(client)
            } catch (_: Throwable) {
                closeQuietly(client)
            }
        }
    }

    /** 一条连接: 连上共用 dsh 后双向泵字节, 任意一侧结束就同时关闭 */
    private fun bridge(client: Socket) {
        val upstream = Socket()
        try {
            upstream.tcpNoDelay = true
            upstream.connect(InetSocketAddress(InetAddress.getByName("127.0.0.1"), targetPort), 3000)
        } catch (_: Throwable) {
            closeQuietly(upstream)
            closeQuietly(client)
            return
        }
        try {
            client.tcpNoDelay = true
        } catch (_: Throwable) {
        }
        connections.add(client)
        connections.add(upstream)
        // 两个方向各一个守护线程: 简单可靠, 与协议无关 (HTTP 长连接/SSE/WebSocket 都适用)
        pump(client, upstream)
        pump(upstream, client)
    }

    private fun pump(from: Socket, to: Socket) {
        Thread({
            try {
                val input = from.getInputStream()
                val output = to.getOutputStream()
                val buffer = ByteArray(16 * 1024)
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    output.write(buffer, 0, n)
                    output.flush()
                }
            } catch (_: IOException) {
            } catch (_: Throwable) {
            } finally {
                // 任一方向结束: 两侧一起关 (HTTP keep-alive 下 EOF 即"用完了", 不存在半关需求)
                closeQuietly(from)
                closeQuietly(to)
                connections.remove(from)
                connections.remove(to)
            }
        }, "dsh-plugin-relay-pump").apply { isDaemon = true }.start()
    }

    private fun closeQuietly(socket: Socket) {
        try {
            socket.close()
        } catch (_: Throwable) {
        }
    }
}
