package com.novelreader

import com.novelreader.core.network.HttpClient
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicInteger

class NetworkRetryInterceptorTest {

    @Test
    fun `最终 5xx 响应返回给调用方而不是改写成未知网络错误`() {
        ServerSocket(0).use { server ->
            val requests = AtomicInteger()
            val serverThread = Thread {
                repeat(4) {
                    server.accept().use { socket ->
                        requests.incrementAndGet()
                        val input = socket.getInputStream().bufferedReader()
                        while (input.readLine()?.isNotEmpty() == true) Unit
                        socket.getOutputStream().bufferedWriter().use { output ->
                            output.write(
                                "HTTP/1.1 503 Service Unavailable\r\n" +
                                    "Content-Length: 3\r\n" +
                                    "Connection: close\r\n\r\n503",
                            )
                            output.flush()
                        }
                    }
                }
            }.apply { isDaemon = true; start() }

            val client = OkHttpClient.Builder()
                .addInterceptor(HttpClient.RetryInterceptor(maxRetry = 3) { _, _ -> 0L })
                .build()
            val request = Request.Builder()
                .url("http://127.0.0.1:${server.localPort}/unavailable")
                .build()

            val response = client.newCall(request).execute()
            response.use {
                assertEquals(503, it.code)
                assertEquals(4, requests.get())
            }
            serverThread.join(2_000)
            assertTrue("测试 HTTP 服务应正常完成", !serverThread.isAlive)
        }
    }

    @Test
    fun `非服务端错误不会重试`() {
        ServerSocket(0).use { server ->
            val requests = AtomicInteger()
            val serverThread = Thread {
                server.accept().use { socket ->
                    requests.incrementAndGet()
                    val input = socket.getInputStream().bufferedReader()
                    while (input.readLine()?.isNotEmpty() == true) Unit
                    socket.getOutputStream().bufferedWriter().use { output ->
                        output.write(
                            "HTTP/1.1 404 Not Found\r\n" +
                                "Content-Length: 3\r\n" +
                                "Connection: close\r\n\r\n404",
                        )
                        output.flush()
                    }
                }
            }.apply { isDaemon = true; start() }

            val client = OkHttpClient.Builder()
                .addInterceptor(HttpClient.RetryInterceptor(maxRetry = 3) { _, _ -> 0L })
                .build()
            val request = Request.Builder()
                .url("http://127.0.0.1:${server.localPort}/missing")
                .build()

            client.newCall(request).execute().use { assertEquals(404, it.code) }
            serverThread.join(2_000)
            assertEquals(1, requests.get())
            assertTrue("测试 HTTP 服务应正常完成", !serverThread.isAlive)
        }
    }

    @Test
    fun `总请求超时能打断仍在读取响应的 socket`() {
        ServerSocket(0).use { server ->
            val serverThread = Thread {
                runCatching {
                    server.accept().use { socket ->
                        val input = socket.getInputStream().bufferedReader()
                        while (input.readLine()?.isNotEmpty() == true) Unit
                        Thread.sleep(1_500)
                        socket.getOutputStream().bufferedWriter().use { output ->
                            output.write(
                                "HTTP/1.1 200 OK\r\nContent-Length: 2\r\nConnection: close\r\n\r\nok",
                            )
                            output.flush()
                        }
                    }
                }
            }.apply { isDaemon = true; start() }

            val startedAt = System.nanoTime()
            val failure = runCatching {
                HttpClient.get(
                    "http://127.0.0.1:${server.localPort}/slow",
                    timeoutMs = 3_000,
                    callTimeoutMs = 200,
                )
            }.exceptionOrNull()
            val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000

            assertNotNull("请求应因总时限到达而失败", failure)
            assertTrue("call timeout 应早于 socket 的读取超时，实际 ${elapsedMs}ms", elapsedMs < 1_200)
            serverThread.join(2_000)
        }
    }
}
