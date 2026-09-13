package top.wkbin.taixu.runtime.webchat

import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidHttpServerTest {

    @Test
    fun `serves a fixed length response over a socket`() {
        val port = ServerSocket(0).use { it.localPort }
        val server = AndroidHttpServer.create(InetSocketAddress("127.0.0.1", port), 0)
        server.createContext("/api/test") { exchange ->
            val body = "{\"ok\":true}".toByteArray()
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.write(body)
            exchange.close()
        }

        try {
            server.start()
            val response = Socket("127.0.0.1", port).use { socket ->
                socket.soTimeout = 5_000
                socket.getOutputStream().apply {
                    write("GET /api/test HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n".toByteArray())
                    flush()
                }
                socket.getInputStream().bufferedReader().readText()
            }

            assertTrue(response.startsWith("HTTP/1.1 200 OK"))
            assertTrue(response.contains("Content-Length: 11"))
            assertTrue(response.endsWith("{\"ok\":true}"))
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `stop closes open ended connections and releases the listening socket`() {
        val port = ServerSocket(0).use { it.localPort }
        val server = AndroidHttpServer.create(InetSocketAddress("127.0.0.1", port), 0)
        val streaming = CountDownLatch(1)
        server.createContext("/api/events") { exchange ->
            exchange.responseHeaders.add("Content-Type", "text/event-stream")
            exchange.sendResponseHeaders(200, 0)
            exchange.responseBody.write("event: ping\ndata: {}\n\n".toByteArray())
            exchange.responseBody.flush()
            streaming.countDown()
            // 故意不关闭：模拟 WebChat 的 SSE 长连接，回收责任落在 stop() 上。
        }

        val client = Socket()
        try {
            server.start()
            client.connect(InetSocketAddress("127.0.0.1", port), 5_000)
            client.soTimeout = 5_000
            client.getOutputStream().apply {
                write("GET /api/events HTTP/1.1\r\nHost: localhost\r\n\r\n".toByteArray())
                flush()
            }
            val input = client.getInputStream()
            assertTrue("SSE 首字节应可读", input.read() >= 0)
            assertTrue("handler 应已接管连接", streaming.await(5, TimeUnit.SECONDS))

            server.stop(0)

            // 连接被服务端关闭时读到 EOF 或 RST；若仍挂着，soTimeout 会抛超时 —— 那就是泄漏。
            val outcome = runCatching {
                var value = input.read()
                while (value >= 0) value = input.read()
            }
            assertFalse(
                "stop() 必须关闭已 accept 的长连接",
                outcome.exceptionOrNull() is SocketTimeoutException,
            )

            // 监听 socket 已释放：同一端口可以立刻重新绑定。
            ServerSocket().use { probe ->
                probe.reuseAddress = true
                probe.bind(InetSocketAddress("127.0.0.1", port))
            }
        } finally {
            runCatching { client.close() }
            server.stop(0)
        }
    }

    @Test
    fun `server can be restarted after stop`() {
        // 端口竞态说明：ServerSocket(0) 取端口后立刻关闭，再交给 AndroidHttpServer 绑定，
        // 中间存在「端口被并行测试/其他进程抢占」以及「stop 后监听 socket 尚未被 OS 释放」
        // 两个窗口，会导致偶发 BindException。这里用「同一端口重试 + 换端口重试」消除 flaky，
        // 被测语义（stop 后可重新 start 并正常服务）完全不变。
        var lastError: Throwable? = null
        repeat(3) { attempt ->
            val port = ServerSocket(0).use { it.localPort }
            val server = AndroidHttpServer.create(InetSocketAddress("127.0.0.1", port), 0)
            server.createContext("/api/ping") { exchange ->
                val body = "pong".toByteArray()
                exchange.sendResponseHeaders(200, body.size.toLong())
                exchange.responseBody.write(body)
                exchange.close()
            }
            try {
                repeat(2) {
                    server.start()
                    val response = Socket("127.0.0.1", port).use { socket ->
                        socket.soTimeout = 5_000
                        socket.getOutputStream().apply {
                            write("GET /api/ping HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n".toByteArray())
                            flush()
                        }
                        socket.getInputStream().bufferedReader().readText()
                    }
                    assertTrue("第 ${it + 1} 次启动应正常响应", response.endsWith("pong"))
                    server.stop(0)
                }
                return // 成功即结束
            } catch (e: java.net.BindException) {
                // 端口被抢占/未释放：换端口重试，属环境噪声而非被测行为缺陷。
                lastError = e
            } finally {
                runCatching { server.stop(0) }
            }
            if (attempt < 2) Thread.sleep(200)
        }
        throw AssertionError("端口重试 3 次仍无法绑定（环境异常）", lastError)
    }

    @Test
    fun `injected executor is owned by the caller and survives stop`() {
        val port = ServerSocket(0).use { it.localPort }
        val server = AndroidHttpServer.create(InetSocketAddress("127.0.0.1", port), 0)
        val injected = Executors.newSingleThreadExecutor()
        server.executor = injected

        try {
            server.start()
            server.stop(0)
            assertFalse("外部注入的线程池不应被 stop() 关闭", injected.isShutdown)
        } finally {
            injected.shutdownNow()
            server.stop(0)
        }
    }
}
