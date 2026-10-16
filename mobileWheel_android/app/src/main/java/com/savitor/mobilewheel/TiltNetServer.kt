package com.savitor.mobilewheel

import android.os.Handler
import android.os.Looper
import android.util.Log
import org.json.JSONObject
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingQueue

/**
 * 倾斜角局域网服务端（TCP）：
 * 手机监听 [PORT] 端口，Windows 客户端连接后即可持续收到每行一条的 JSON 数据。
 *
 * 数据示例：{"angle":-3.3,"direction":"向右倾斜","rotation":270,"lt":0,"rt":128,"x":0,"a":1,"ts":1790314159000}
 *
 * 同一条连接是双向的：电脑端会把虚拟手柄收到的震动强度回传过来，
 * 形如 {"large":128,"small":64}（0~255），由 [onRumbleReceived] 交给界面去震动。
 */
class TiltNetServer {

    /** 状态回调（已切回主线程），用于把监听/连接状态显示到界面 */
    var onStatusChanged: ((String) -> Unit)? = null

    /** 收到电脑端回传的震动强度（大马达、小马达，0~255），已切回主线程 */
    var onRumbleReceived: ((Int, Int) -> Unit)? = null

    private val mainHandler = Handler(Looper.getMainLooper())
    private val clients = CopyOnWriteArrayList<ClientWriter>()

    private var serverSocket: ServerSocket? = null
    private var acceptThread: Thread? = null
    private var writerThread: Thread? = null
    private var running = false

    /**
     * 待发送数据的信箱（容量 1）。socket 写入不能在主线程做，而传感器回调正好在主线程，
     * 因此数据先投递到这里由 [writerThread] 发送；客户端跟不上时只保留最新一帧。
     */
    private val outbox = LinkedBlockingQueue<String>(1)

    /** 最近一次广播的数据行，新客户端接入时立刻补发，避免等下一个周期 */
    private var lastLine: String? = null

    /** 已有客户端接入，可以发送数据 */
    val isReady: Boolean get() = clients.isNotEmpty()

    /**
     * 开始监听端口。
     * @return 是否成功启动
     */
    fun start(): Boolean {
        if (running) return true

        return try {
            val socket = ServerSocket()
            socket.reuseAddress = true
            socket.bind(InetSocketAddress(PORT))
            serverSocket = socket
            running = true
            acceptThread = Thread({ acceptLoop(socket) }, "TiltNetServer-Accept").apply {
                isDaemon = true
                start()
            }
            writerThread = Thread({ writerLoop() }, "TiltNetServer-Writer").apply {
                isDaemon = true
                start()
            }
            Log.i(TAG, "listening on $PORT")
            // 界面上这一行只显示“未连接”或已接入的客户端 IP，不再显示端口等其它信息
            postStatus(LAN_IDLE)
            true
        } catch (e: Exception) {
            Log.w(TAG, "start failed: ${e.message}")
            postStatus(LAN_IDLE)
            false
        }
    }

    /** 关闭监听并断开所有客户端 */
    fun stop() {
        running = false
        outbox.clear()
        writerThread?.interrupt()
        writerThread = null

        clients.forEach { it.close() }
        clients.clear()

        try {
            serverSocket?.close()
        } catch (e: IOException) {
            Log.w(TAG, "close server socket failed: ${e.message}")
        }
        serverSocket = null
        acceptThread = null
        lastLine = null
        postStatus(LAN_IDLE)
    }

    /** 发送一行数据（按行分隔，仅在已有客户端接入时真正发出；本方法可在主线程调用） */
    fun send(line: String) {
        lastLine = line
        if (!running || clients.isEmpty()) return

        // 队列满说明客户端还没读完上一帧，丢弃旧帧只发最新值
        if (!outbox.offer(line)) {
            outbox.poll()
            outbox.offer(line)
        }
    }

    /** 本机局域网 IPv4 地址，取不到时返回 0.0.0.0 */
    fun localIpAddress(): String {
        return try {
            NetworkInterface.getNetworkInterfaces().toList()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { it.inetAddresses.toList() }
                .filterIsInstance<Inet4Address>()
                .firstOrNull { it.isSiteLocalAddress }
                ?.hostAddress
                ?: "0.0.0.0"
        } catch (e: Exception) {
            Log.w(TAG, "resolve local ip failed: ${e.message}")
            "0.0.0.0"
        }
    }

    private fun acceptLoop(socket: ServerSocket) {
        while (running) {
            val client = try {
                socket.accept()
            } catch (e: Exception) {
                if (running) Log.w(TAG, "accept failed: ${e.message}")
                break
            }

            // 开启 TCP keep-alive，客户端异常掉线时能更快被发现并清理
            try {
                client.keepAlive = true
                client.tcpNoDelay = true
            } catch (e: Exception) {
                Log.w(TAG, "set socket option failed: ${e.message}")
            }

            val writer = try {
                ClientWriter(client, ::dispatchRumble)
            } catch (e: Exception) {
                Log.w(TAG, "open client stream failed: ${e.message}")
                client.closeQuietly()
                continue
            }

            clients.add(writer)
            val label = "${client.inetAddress?.hostAddress}:${client.port}"
            Log.i(TAG, "client connected: $label")
            // 界面上只显示客户端 IP，不带端口
            postStatus(client.inetAddress?.hostAddress ?: LAN_IDLE)
            // 新客户端接入时立刻补发一次最近读数（同样交给写入线程）
            lastLine?.let { send(it) }
        }
    }

    /** 串行执行 socket 写入，避免在主线程做网络 I/O */
    private fun writerLoop() {
        while (running) {
            val line = try {
                outbox.take()
            } catch (e: InterruptedException) {
                break
            }

            val dead = clients.filter { !it.writeLine(line) }
            if (dead.isEmpty()) continue

            clients.removeAll(dead)
            if (clients.isEmpty()) {
                postStatus(LAN_IDLE)
            }
        }
    }

    private fun postStatus(text: String) {
        mainHandler.post { onStatusChanged?.invoke(text) }
    }

    /** 读取线程收到震动帧后切回主线程再回调界面 */
    private fun dispatchRumble(large: Int, small: Int) {
        mainHandler.post { onRumbleReceived?.invoke(large, small) }
    }

    /**
     * 单个客户端的读写句柄：写入可能同时来自传感器线程与回弹动画，需要串行化；
     * 读取则在独立的守护线程里跑，socket 关闭时自然退出。
     */
    private class ClientWriter(
        private val socket: Socket,
        private val onRumble: (Int, Int) -> Unit
    ) {

        private val writer = BufferedWriter(OutputStreamWriter(socket.getOutputStream(), Charsets.UTF_8))

        private val tag = "TiltNetServer"

        init {
            Thread({ readLoop() }, "TiltNetServer-Reader").apply {
                isDaemon = true
                start()
            }
        }

        /** 读电脑端回传的震动强度：整行 JSON，形如 {"large":128,"small":64} */
        private fun readLoop() {
            try {
                val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8))
                while (true) {
                    val line = reader.readLine() ?: break
                    if (line.isBlank()) continue

                    val json = try {
                        JSONObject(line)
                    } catch (e: Exception) {
                        Log.w(tag, "ignore unparsable line from client: ${e.message}")
                        continue
                    }
                    onRumble(json.optInt("large", 0), json.optInt("small", 0))
                }
            } catch (e: Exception) {
                // 客户端断开或 socket 被关闭，属于正常收尾
                Log.i(tag, "client read loop ended: ${e.message}")
            }
        }

        @Synchronized
        fun writeLine(line: String): Boolean = try {
            writer.write(line)
            writer.write("\n")
            writer.flush()
            true
        } catch (e: IOException) {
            Log.w(tag, "write to client failed: ${e.message}")
            close()
            false
        }

        @Synchronized
        fun close() {
            socket.closeQuietly()
        }
    }

    companion object {
        private const val TAG = "TiltNetServer"

        /** 局域网监听端口 */
        const val PORT = 11223

        /** 界面上的“未接入客户端”文案（此时也包含未开启局域网的情况） */
        const val LAN_IDLE = "未连接"
    }
}

private fun Socket.closeQuietly() {
    try {
        close()
    } catch (_: IOException) {
        // 关闭失败无需处理
    }
}