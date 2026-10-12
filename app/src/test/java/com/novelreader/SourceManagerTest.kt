package com.novelreader

import com.novelreader.source.manager.SourceManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 源管理器（编辑 / 重建）回归测试。
 *
 * 覆盖「源规则可编辑」的核心契约：
 * - updateSource 成功后保留原 id 与启停状态、替换规则并重建适配器；
 * - 非法内容抛异常且**原源保持不变**（不会因编辑错误把源改坏）。
 */
class SourceManagerTest {

    private val validJson = """
        {
          "name": "测试源",
          "type": "novel",
          "version": "1.0.0",
          "baseUrl": "https://example.com",
          "search": {
            "url": "https://example.com/search?keyword={{keyword}}",
            "list": "div.book-item",
            "fields": { "id": "a@href", "title": ".title@text" }
          },
          "content": { "url": "{{chapterUrl}}", "text": "#content@html" }
        }
    """.trimIndent()

    @Test
    fun `导入后 getRawContent 可取得原始内容`() {
        val manager = SourceManager()
        val imported = manager.importSource(validJson, "test.json")
        assertEquals(1, imported.size)
        val id = imported[0].info.id
        assertNotNull(manager.getRawContent(id))
        assertTrue(manager.getRawContent(id)!!.contains("测试源"))
    }

    @Test
    fun `updateSource 成功后保留 id 与启用状态`() {
        val manager = SourceManager()
        val imported = manager.importSource(validJson, "test.json")
        val info = imported[0].info
        val id = info.id
        // 先禁用
        manager.setSourceEnabled(id, false)

        val updatedJson = validJson.replace("测试源", "改名源")
        val updated = manager.updateSource(id, updatedJson)

        assertEquals(id, updated.id)
        assertEquals(false, updated.enabled)
        assertEquals("改名源", updated.name)
        // 适配器已重建，原始内容也更新
        assertEquals(updatedJson, manager.getRawContent(id))
        assertNotNull(manager.getAdapter(id))
    }

    @Test
    fun `updateSource 非法内容抛异常且原源不变`() {
        val manager = SourceManager()
        val imported = manager.importSource(validJson, "test.json")
        val id = imported[0].info.id

        val before = manager.getAdapter(id)
        val beforeRaw = manager.getRawContent(id)

        // 非法 JSON 内容 → 抛异常
        val threw = try {
            manager.updateSource(id, "{{{ 非法内容")
            false
        } catch (e: Exception) {
            true
        }
        assertTrue("非法内容应抛异常", threw)

        // 原源未被破坏
        assertEquals(beforeRaw, manager.getRawContent(id))
        assertEquals(before, manager.getAdapter(id))
    }

    @Test
    fun `updateSource 不存在源抛异常`() {
        val manager = SourceManager()
        val threw = try {
            manager.updateSource("no_such_source", validJson)
            false
        } catch (e: Exception) {
            true
        }
        assertTrue(threw)
    }

    @Test
    fun `updateSource 缺少搜索规则抛异常`() {
        val manager = SourceManager()
        val imported = manager.importSource(validJson, "test.json")
        val id = imported[0].info.id

        val noSearch = validJson.replace("\"search\": {", "\"search2\": {")
        val threw = try {
            manager.updateSource(id, noSearch)
            false
        } catch (e: Exception) {
            true
        }
        assertTrue(threw)
    }
}
