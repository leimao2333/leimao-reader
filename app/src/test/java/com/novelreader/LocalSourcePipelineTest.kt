package com.novelreader

import com.novelreader.domain.model.SourceFormat
import com.novelreader.domain.model.SourceInfo
import com.novelreader.domain.model.SourceType
import com.novelreader.source.legado.LegadoBookSource
import com.novelreader.source.legado.LegadoSourceAdapter
import kotlinx.coroutines.runBlocking
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

/**
 * 本机端到端测试：在测试进程内启动一个「模拟书源」HTTP 服务（纯 JDK ServerSocket，
 * 不依赖 Android 运行时 / 外部网络），直接驱动 [LegadoSourceAdapter] 走完
 * **搜索 → 详情 → 目录 → 正文** 全链路。
 *
 * 用于在没有实体设备时，验证 v1.0.1.2 修复的行为：
 * - 搜索能正确解析列表 / 字段 / 链接（对应「点击源闪退」的功能侧）
 * - 详情与目录地址被正确补全为绝对地址
 * - 正文清洗**保留段落首行缩进**、且**不产生大片空白**（连续空行被折叠）
 */
class LocalSourcePipelineTest {

    companion object {
        private lateinit var serverSocket: ServerSocket
        private lateinit var serverThread: Thread
        private var port: Int = 0

        /** 与真实书源一致的分段：奇数段带全角缩进，偶数段不带；第 10/20 段后插入空行 */
        private fun paragraphs(): String {
            val sb = StringBuilder()
            for (i in 1..30) {
                val body = "这是第${i}段的测试正文内容，用于验证分页、首行缩进与段落间距是否符合预期。" +
                    "当一页内容排满之后，应当自动切到下一页继续显示，最后一行不能被裁掉一半。" +
                    "段落与段落之间应当保持稳定的行距，而不是出现过大的空白区域。"
                sb.append(if (i % 2 == 1) "\u3000\u3000$body" else body)
                sb.append('\n')
                if (i % 10 == 0 && i != 30) sb.append('\n')
            }
            return sb.toString()
        }

        private val searchHtml = """
            <!DOCTYPE html><html><head><meta charset="utf-8"></head><body>
            <div class="book-list">
              <div class="book-item">
                <a class="book-name" href="/book/1">测试小说</a>
                <span class="book-author">测试作者</span>
                <span class="book-intro">本地模拟源 · 用于装机实测</span>
                <span class="book-status">连载中</span>
              </div>
            </div>
            </body></html>
        """.trimIndent()

        private val bookHtml = """
            <!DOCTYPE html><html><head><meta charset="utf-8"></head><body>
            <h1 class="book-title">测试小说</h1>
            <p class="book-author">测试作者</p>
            <p class="book-intro">这是一本用于「装机实测」的测试书籍。</p>
            <span class="book-kind">玄幻</span>
            <div class="chapter-list">
              <div class="chapter-item"><a class="chapter-name" href="/chapter/1">第1章 测试章节</a></div>
              <div class="chapter-item"><a class="chapter-name" href="/chapter/2">第2章 测试章节</a></div>
              <div class="chapter-item"><a class="chapter-name" href="/chapter/3">第3章 测试章节</a></div>
            </div>
            </body></html>
        """.trimIndent()

        private fun chapterHtml(ci: Int): String {
            val inner = paragraphs().trimEnd('\n').split("\n")
                .joinToString("\n") { line -> if (line.isEmpty()) "<p><br/></p>" else "<p>$line</p>" }
            return """
                <!DOCTYPE html><html><head><meta charset="utf-8"></head><body>
                <h1>第${ci}章 测试章节</h1>
                <div class="content">$inner</div>
                </body></html>
            """.trimIndent()
        }

        /**
         * 与「从文件导入」完全一致的书源 JSON（字段名为 ASCII，规则用 legado 短式）。
         *
         * 刻意让 **`kind` 只出现在详情规则里**（搜索规则只给 `status`）：
         * 这正是「小说详情页标签栏整行不见」的那类源，用来守住 v1.7.0.0 的修复。
         */
        private fun sourceJson(base: String): String = """
            {
              "bookSourceName": "本地测试源",
              "bookSourceUrl": "$base",
              "bookSourceGroup": "测试",
              "bookSourceType": 0,
              "searchUrl": "/search?key={{key}}",
              "ruleSearch": {
                "bookList": "@css:.book-item",
                "name": "@css:.book-name",
                "author": "@css:.book-author",
                "bookUrl": ".book-name@href",
                "intro": "@css:.book-intro",
                "status": "@css:.book-status"
              },
              "ruleBookInfo": {
                "name": "@css:.book-title",
                "author": "@css:.book-author",
                "intro": "@css:.book-intro",
                "kind": "@css:.book-kind"
              },
              "ruleToc": {
                "chapterList": "@css:.chapter-item",
                "chapterName": "@css:.chapter-name",
                "chapterUrl": ".chapter-name@href"
              },
              "ruleContent": { "content": ".content@html" }
            }
        """.trimIndent()

        private fun bodyFor(path: String): String = when {
            path.startsWith("/search") -> searchHtml
            path.startsWith("/book/") -> bookHtml
            path.startsWith("/chapter/") -> chapterHtml(path.substringAfterLast('/').toIntOrNull() ?: 1)
            else -> "<html><body>404</body></html>"
        }

        private fun handle(socket: Socket) {
            socket.use { s ->
                val input = s.getInputStream()
                val out = s.getOutputStream()
                val buf = ByteArray(4096)
                val sb = StringBuilder()
                // 读到请求头结束（GET 无实体，读到空行即可）
                while (!sb.contains("\r\n\r\n")) {
                    val n = input.read(buf)
                    if (n <= 0) break
                    sb.append(String(buf, 0, n, Charsets.ISO_8859_1))
                }
                val requestLine = sb.toString().substringBefore("\r\n")
                val path = requestLine.split(" ").getOrNull(1) ?: "/"
                val bytes = bodyFor(path).toByteArray(Charsets.UTF_8)
                val header = "HTTP/1.1 200 OK\r\n" +
                    "Content-Type: text/html; charset=utf-8\r\n" +
                    "Content-Length: ${bytes.size}\r\n" +
                    "Connection: close\r\n\r\n"
                out.write(header.toByteArray(Charsets.ISO_8859_1))
                out.write(bytes)
                out.flush()
            }
        }

        @BeforeClass
        @JvmStatic
        fun startServer() {
            serverSocket = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
            port = serverSocket.localPort
            serverThread = thread(isDaemon = true, name = "mock-source-server") {
                while (!serverSocket.isClosed) {
                    val socket = try {
                        serverSocket.accept()
                    } catch (e: Exception) {
                        break
                    }
                    thread(isDaemon = true) { runCatching { handle(socket) } }
                }
            }
        }

        @AfterClass
        @JvmStatic
        fun stopServer() {
            runCatching { serverSocket.close() }
            serverThread.interrupt()
        }

        private fun adapter(): LegadoSourceAdapter {
            val base = "http://127.0.0.1:$port"
            val src = LegadoBookSource.parseOne(sourceJson(base))
            val info = SourceInfo(
                id = "local-test",
                name = src.name,
                type = SourceType.NOVEL,
                version = "1.0.0",
                baseUrl = base,
                format = SourceFormat.LEGADO,
            )
            return LegadoSourceAdapter(info, src)
        }
    }

    @Test
    fun `搜索能解析出条目且链接补全为绝对地址`() = runBlocking {
        val items = adapter().search("test", 1)
        assertEquals(1, items.size)
        val item = items[0]
        assertEquals("测试小说", item.title)
        assertEquals("测试作者", item.author)
        assertTrue(
            "detailUrl 应为绝对地址, 实际=${item.detailUrl}",
            item.detailUrl.startsWith("http://127.0.0.1:$port/book/1"),
        )
        assertEquals(SourceType.NOVEL, item.type)
    }

    @Test
    fun `详情页能补齐作者与简介`() = runBlocking {
        val a = adapter()
        val item = a.search("test", 1)[0]
        val detail = a.detail(item)
        assertEquals("测试小说", detail.title)
        assertEquals("测试作者", detail.author)
        assertTrue("简介应含关键字, 实际=${detail.desc}", detail.desc?.contains("装机实测") == true)
    }

    /**
     * v1.7.0.0：`ruleBookInfo.kind` 以前只写进 extras["status"]，标签栏读的是
     * extras["kind"]，于是「分类只由详情规则给出」的源在小说 / 漫画详情页
     * 整行标签都不显示。同时 kind 不能把搜索阶段拿到的真实状态覆盖掉。
     */
    @Test
    fun `详情 kind 进分类字段 - 标签栏派生得出且不覆盖状态`() = runBlocking {
        val a = adapter()
        val item = a.search("test", 1)[0]
        assertEquals("搜索阶段应有状态", "连载中", item.extras["status"])

        val detail = a.detail(item)
        assertEquals("详情 kind 应落到 extras[\"kind\"]", "玄幻", detail.extras["kind"])
        assertEquals("真实状态不应被分类名覆盖", "连载中", detail.extras["status"])

        // 标签栏据此派生：分类进标签；状态词即使混进 kind 也会被过滤掉
        val tags = com.novelreader.core.util.ItemTags.of(detail)
        assertTrue("标签应含详情 kind, 实际=$tags", tags.contains("玄幻"))
        assertEquals("连载中", com.novelreader.ui.components.BookMeta.statusText(detail))
    }

    @Test
    fun `目录能解析出全部章节且链接为绝对地址`() = runBlocking {
        val a = adapter()
        val item = a.search("test", 1)[0]
        val chapters = a.chapterList(item)
        assertEquals(3, chapters.size)
        assertEquals("第1章 测试章节", chapters[0].title)
        assertEquals(0, chapters[0].index)
        assertEquals(2, chapters[2].index)
        assertTrue(
            "章节链接应为绝对地址, 实际=${chapters[2].url}",
            chapters[2].url.startsWith("http://127.0.0.1:$port/chapter/3"),
        )
    }

    @Test
    fun `正文保留段落首行缩进且不产生大片空白`() = runBlocking {
        val a = adapter()
        val chapters = a.chapterList(a.search("test", 1)[0])
        val text = a.content(chapters[0]).novelText ?: ""

        assertTrue("正文不应为空", text.isNotBlank())
        val nonEmpty = text.split("\n").filter { it.isNotBlank() }
        assertTrue("段落数应 >= 25, 实际=${nonEmpty.size}", nonEmpty.size >= 25)

        // 首行缩进保留：源里奇数段带全角缩进，清洗后必须还在
        assertTrue("应保留全角首行缩进", text.contains("\u3000\u3000"))
        assertTrue(
            "至少 10 个段落应保留缩进, 实际=${nonEmpty.count { it.startsWith("\u3000\u3000") }}",
            nonEmpty.count { it.startsWith("\u3000\u3000") } >= 10,
        )

        // 不产生大片空白：不允许出现 3 个以上连续换行（= 2 行以上连续空白）
        assertFalse("不应出现大片连续空行", text.contains("\n\n\n"))
        assertFalse("缩进不应被转成半角空格", nonEmpty.any { it.startsWith("    ") })
    }

    @Test
    fun `各章节正文均可正常读取`() = runBlocking {
        val a = adapter()
        val chapters = a.chapterList(a.search("test", 1)[0])
        assertTrue((a.content(chapters[1]).novelText ?: "").isNotBlank())
        assertTrue((a.content(chapters[2]).novelText ?: "").isNotBlank())
    }
}
