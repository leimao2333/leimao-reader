package com.novelreader

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.novelreader.data.database.SourceDao
import com.novelreader.data.model.SourceEntity
import com.novelreader.data.repository.SourceRepositoryImpl
import com.novelreader.domain.model.SourceFileContent
import com.novelreader.domain.model.SourceFormat
import com.novelreader.domain.model.SourceType
import com.novelreader.source.manager.SourceManager
import com.novelreader.source.venera.VeneraEngine
import com.novelreader.ui.screens.source.SourceManageViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.net.ServerSocket

/**
 * 聚合源包「一条链接导入全部源」端到端测试（v1.5.6.0）。
 *
 * 起一个真的本地 HTTP 服务，喂给**真实的导入链路**
 * （`HttpClient` → `SourceManageViewModel.importFromUrl` → 清单解析 → 逐个下载子包
 * → `SourceRepositoryImpl.previewImportBatch` → 勾选 → `importSelected` / Venera .js 下载注册），
 * 断言最终入库的源数量、类型与 id 唯一性。
 *
 * 之所以要真的走 HTTP：清单的「相对路径解析 + 多包下载」是本次改动的核心，
 * 只在纯逻辑层测 `SourcePack.parse` 覆盖不到它。
 *
 * 夹具是**合成的小包**（3 legado + 2 MoonTV + 2 Venera），刻意不入库真实备份的快照，
 * 避免仓库里留一份会过期的 1.5MB 数据。
 */
@RunWith(RobolectricTestRunner::class)
class SourcePackImportTest {

    private lateinit var server: TinyHttpServer
    private lateinit var dao: FakeSourceDao
    private lateinit var repo: SourceRepositoryImpl
    private lateinit var vm: SourceManageViewModel

    private fun fixture(path: String): String =
        javaClass.classLoader!!.getResource("pack/$path")!!.readText()

    @Before
    fun setUp() {
        Dispatchers.setMain(Dispatchers.Unconfined)
        server = TinyHttpServer(
            mutableMapOf(
                "/all.json" to fixture("all.json"),
                "/legado-a.json" to fixture("legado-a.json"),
                "/video-b.json" to fixture("video-b.json"),
                "/venera/index.json" to fixture("venera/index.json"),
                "/venera/one.js" to fixture("venera/one.js"),
                "/venera/two.js" to fixture("venera/two.js"),
            )
        )
        val engine = VeneraEngine(ApplicationProvider.getApplicationContext<Context>())
        dao = FakeSourceDao()
        repo = SourceRepositoryImpl(SourceManager(engine), dao)
        vm = SourceManageViewModel(repo, repo, engine)
    }

    @After
    fun tearDown() {
        server.close()
        Dispatchers.resetMain()
    }

    // ==================== 主流程：一条链接 ====================

    @Test
    fun `一条链接下载聚合包把三类源合并成一份候选并全部入库`() {
        vm.importFromUrl("${server.baseUrl}/all.json")
        awaitTrue("聚合包候选未出现（未识别清单？）") { vm.importCandidates.value.isNotEmpty() }

        val candidates = vm.importCandidates.value
        assertEquals("3 legado + 2 MoonTV + 2 Venera", 7, candidates.size)
        assertTrue("提示应说明是聚合包：${vm.message.value}", vm.message.value!!.contains("聚合包"))
        assertEquals(3, candidates.count { it.format == SourceFormat.LEGADO })
        assertEquals(2, candidates.count { it.format == SourceFormat.MOONTV })
        assertEquals(2, candidates.count { it.format == SourceFormat.VENERA })
        // 候选 id 互不重复：重复会在勾选后互相覆盖（少导入）
        assertEquals(7, candidates.map { it.id }.toSet().size)

        // 勾选（默认全选）→ 确认
        vm.onImportCandidatesShown()
        assertEquals(7, vm.selectedImportIds.value.size)
        vm.confirmImportSelected()
        awaitTrue("注册未完成：已入库 ${dao.rows.size} 个") { dao.rows.size == 7 }

        // 三类源都按正确类型入库
        assertEquals(2, dao.rows.values.count { it.type == SourceType.NOVEL.name.lowercase() })
        assertEquals("漫画 1（legado）+ Venera 2", 3, dao.rows.values.count { it.type == SourceType.COMIC.name.lowercase() })
        assertEquals(2, dao.rows.values.count { it.type == SourceType.VIDEO.name.lowercase() })
        // Venera 的 .js 确实按索引目录下载过
        assertEquals(1, server.hits["/venera/one.js"])
        assertEquals(1, server.hits["/venera/two.js"])
        assertTrue("结果提示：${vm.message.value}", vm.message.value!!.contains("已添加 7 个源"))
    }

    @Test
    fun `单个源包下载失败不中断其余源包`() {
        server.routes.remove("/video-b.json")
        vm.importFromUrl("${server.baseUrl}/all.json")
        awaitTrue("聚合包候选未出现") { vm.importCandidates.value.isNotEmpty() }

        val candidates = vm.importCandidates.value
        assertEquals("legado 3 + Venera 2，影视包失败", 5, candidates.size)
        val msg = vm.message.value!!
        assertTrue("提示应报出失败的源包：$msg", msg.contains("video-b.json"))
        assertTrue("提示应说明有几个包失败：$msg", msg.contains("源包失败"))
    }

    /** 文件方式导入清单：没有清单 URL 就解析不了相对路径，必须给出明确指引而不是「无法识别格式」 */
    @Test
    fun `文件方式导入清单提示改用网址`() {
        vm.importSource(fixture("all.json"), "all.json")
        awaitTrue("未给出提示") { vm.message.value != null }
        assertTrue("实际提示：${vm.message.value}", vm.message.value!!.contains("聚合源包清单"))
        assertTrue("实际提示：${vm.message.value}", vm.message.value!!.contains("粘贴网址"))
        assertTrue(vm.importCandidates.value.isEmpty())
    }

    // ==================== 关键回归：跨文件候选缓存 ====================

    /**
     * 旧 `previewImport` 每次都 `previewCache.clear()`，连调两次只有最后一个文件的候选能注册
     * （勾选了前面的源，确认后「已添加 0 个源」）。本用例锁住「多文件累积」这个关键行为。
     */
    @Test
    fun `批量解析后两个文件的候选都能注册`() = runBlocking {
        val batch = repo.previewImportBatch(
            listOf(
                SourceFileContent("legado_x", fixture("legado-a.json")),
                SourceFileContent("video_y", fixture("video-b.json")),
            )
        )
        assertEquals(5, batch.sources.size)
        assertTrue(batch.failedFiles.isEmpty())
        assertEquals(0, batch.skippedEntries)

        val added = repo.importSelected(batch.sources.map { it.id }.toSet())
        assertEquals("两个文件的候选都必须能注册", 5, added)
        assertEquals(5, dao.rows.size)
    }

    @Test
    fun `批量解析时单个文件非法只记录不中断`() = runBlocking {
        val batch = repo.previewImportBatch(
            listOf(
                SourceFileContent("bad", "{ 这不是一个合法源 }"),
                SourceFileContent("ok", fixture("video-b.json")),
            )
        )
        assertEquals(2, batch.sources.size)
        assertEquals(listOf("bad"), batch.failedFiles)
    }
}

/** 等待异步流程（下载 / 解析 / 注册跑在 IO 线程）满足条件 */
private fun awaitTrue(
    message: String,
    timeoutMs: Long = 30_000,
    condition: () -> Boolean,
) {
    val deadline = System.currentTimeMillis() + timeoutMs
    while (System.currentTimeMillis() < deadline) {
        if (condition()) return
        Thread.sleep(50)
    }
    fail("等待超时：$message")
}

/**
 * 极简 HTTP 服务（JDK 自带 ServerSocket，不引第三方依赖）。
 *
 * `routes` 可随时增删，用于模拟「某个源包 404」；`hits` 记录每个路径被请求的次数。
 */
private class TinyHttpServer(val routes: MutableMap<String, String>) : AutoCloseable {

    private val socket = ServerSocket(0)
    val hits = java.util.concurrent.ConcurrentHashMap<String, Int>()

    val baseUrl: String get() = "http://127.0.0.1:${socket.localPort}"

    private val thread = Thread {
        while (!socket.isClosed) {
            val client = runCatching { socket.accept() }.getOrNull() ?: break
            runCatching { client.use { serve(it) } }
        }
    }.apply { isDaemon = true; start() }

    private fun serve(client: java.net.Socket) {
        val reader = client.getInputStream().bufferedReader()
        val requestLine = reader.readLine() ?: return
        while (true) {
            val line = reader.readLine() ?: break
            if (line.isEmpty()) break
        }
        val path = requestLine.split(' ').getOrNull(1)?.substringBefore('?') ?: "/"
        hits.merge(path, 1, Int::plus)

        val body = routes[path]
        val status = if (body == null) "404 Not Found" else "200 OK"
        val bytes = (body ?: "not found").toByteArray(Charsets.UTF_8)
        val head = "HTTP/1.1 $status\r\n" +
            "Content-Type: application/json; charset=utf-8\r\n" +
            "Content-Length: ${bytes.size}\r\n" +
            "Connection: close\r\n\r\n"
        val out = client.getOutputStream()
        out.write(head.toByteArray(Charsets.US_ASCII))
        out.write(bytes)
        out.flush()
    }

    override fun close() {
        runCatching { socket.close() }
    }
}

/** 内存版 SourceDao：单测里替掉 Room，断言最终入库内容 */
private class FakeSourceDao : SourceDao {

    val rows = java.util.concurrent.ConcurrentHashMap<String, SourceEntity>()
    private val flow = MutableStateFlow<List<SourceEntity>>(emptyList())

    private fun changed() {
        flow.value = rows.values.toList()
    }

    override fun observeAll(): Flow<List<SourceEntity>> = flow

    override suspend fun getAll(): List<SourceEntity> = rows.values.toList()

    override suspend fun getByType(type: String): List<SourceEntity> =
        rows.values.filter { it.type == type }

    override suspend fun getById(id: String): SourceEntity? = rows[id]

    override suspend fun insert(source: SourceEntity) {
        rows[source.id] = source
        changed()
    }

    override suspend fun setEnabled(id: String, enabled: Boolean) {
        rows[id]?.let { rows[id] = it.copy(enabled = enabled) }
        changed()
    }

    override suspend fun setSortOrder(id: String, sortOrder: Int) {
        rows[id]?.let { rows[id] = it.copy(sortOrder = sortOrder) }
        changed()
    }

    override suspend fun delete(source: SourceEntity) {
        rows.remove(source.id)
        changed()
    }

    override suspend fun deleteById(id: String) {
        rows.remove(id)
        changed()
    }

    override suspend fun deleteByIds(ids: List<String>) {
        ids.forEach { rows.remove(it) }
        changed()
    }
}
