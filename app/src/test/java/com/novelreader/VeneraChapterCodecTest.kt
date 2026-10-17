package com.novelreader

import com.novelreader.source.venera.VeneraChapterCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Venera 章节地址编解码（v1.7.6.5）。
 *
 * 回归点：H-Comic 这类源的 comicId（`id|title`）和 epId（`source|media_id|pages`）
 * **都自带竖线**。旧实现用 `"$comicId|$epId"` 拼、`substringBefore/After('|')` 拆，
 * 结果 comicId 被截成 `11`、epId 变成 `Test Comic|abc|42|3`，
 * `loadInfo` 拼出 `/comics/view/1?id=11` → 404 → 「搜得到、点进去没内容」。
 */
class VeneraChapterCodecTest {

    @Test
    fun `普通源保持旧格式以兼容历史数据`() {
        val url = VeneraChapterCodec.encode("12345", "ep-1")
        assertEquals("12345|ep-1", url)
        assertEquals("12345" to "ep-1", VeneraChapterCodec.decode(url))
    }

    @Test
    fun `两段都含竖线时能原样往返`() {
        // H-Comic 的真实形态
        val comicId = "11|Test Comic"
        val epId = "abc|42|3"
        val url = VeneraChapterCodec.encode(comicId, epId)

        // 不能用裸拼接，否则拆回来必然错位
        assertFalse("含竖线的两段不能使用裸拼接：$url", url == "$comicId|$epId")
        assertTrue("应走 JSON 承载格式：$url", url.startsWith(VeneraChapterCodec.JSON_PREFIX))

        assertEquals(comicId to epId, VeneraChapterCodec.decode(url))
    }

    @Test
    fun `只有章节 id 含竖线时也不丢前缀`() {
        val url = VeneraChapterCodec.encode("97500", "a|b|c")
        assertEquals("97500" to "a|b|c", VeneraChapterCodec.decode(url))
    }

    @Test
    fun `历史裸拼接数据仍按旧规则解析`() {
        // 老版本写进阅读进度 / 缓存里的地址
        assertEquals("12345" to "ep-1", VeneraChapterCodec.decode("12345|ep-1"))
    }

    @Test
    fun `损坏的 JSON 承载格式回退到旧规则而不抛异常`() {
        val broken = VeneraChapterCodec.JSON_PREFIX + "{not json"
        val (comicId, epId) = VeneraChapterCodec.decode(broken, fallbackEpId = "fb")
        assertEquals(broken, comicId)
        assertEquals("fb", epId)
    }

    @Test
    fun `没有分隔符时用兜底章节 id`() {
        assertEquals("only" to "fb", VeneraChapterCodec.decode("only", "fb"))
    }

    @Test
    fun `空章节 id 走兜底而不是丢掉 comicId`() {
        assertEquals("11" to "fb", VeneraChapterCodec.decode("11|", "fb"))
    }
}
