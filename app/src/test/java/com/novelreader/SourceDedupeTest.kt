package com.novelreader

import com.novelreader.core.util.SourceDedupe
import com.novelreader.core.util.SourceDedupe.Candidate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 重复源识别测试（v1.6.6.0）。
 *
 * 素材取自模拟器实测：源管理页出现的两组真重复（`baseUrl` 只差结尾斜杠），
 * 以及必须**不能**被合并的同名不同站（5 个都叫「笔趣阁」的源）。
 */
class SourceDedupeTest {

    private fun c(
        id: String,
        name: String,
        url: String,
        type: String = "comic",
        enabled: Boolean = true,
        rawLength: Int = 1000,
        createdAt: Long = 1L,
    ) = Candidate(id, type, name, url, enabled, rawLength, createdAt)

    // ===== urlKey 归一化 =====

    @Test
    fun `urlKey 去协议 结尾斜杠 大小写 与 hash 片段`() {
        val a = SourceDedupe.urlKey("https://m.1kkk.com")
        val b = SourceDedupe.urlKey("https://m.1kkk.com/")
        val d = SourceDedupe.urlKey("http://M.1KKK.com//")
        val e = SourceDedupe.urlKey("  https://m.1kkk.com#🎃 ")
        assertEquals(a, b)
        assertEquals(a, d)
        assertEquals(a, e)
        assertEquals("m.1kkk.com", a)
    }

    @Test
    fun `urlKey 空串返回空`() {
        assertEquals("", SourceDedupe.urlKey(""))
        assertEquals("", SourceDedupe.urlKey("   "))
        assertEquals("", SourceDedupe.urlKey("https://"))
    }

    // ===== 真实重复：应合并 =====

    @Test
    fun `结尾斜杠不同的同名同站被合并 - 酸奶漫画`() {
        val rows = listOf(
            c("legado_1132337902_69", "酸奶漫画", "https://m.1kkk.com", rawLength = 1517),
            c("legado_1132337902_72", "酸奶漫画", "https://m.1kkk.com/", rawLength = 1518),
        )
        assertEquals(listOf("legado_1132337902_69"), SourceDedupe.redundantIds(rows))
    }

    @Test
    fun `结尾斜杠不同的同名同站被合并 - 漫画小屋`() {
        val rows = listOf(
            c("legado_17670893_74", "漫画小屋（优）", "https://m.dm5.com/", rawLength = 1340),
            c("legado_17670893_76", "漫画小屋（优）", "https://m.dm5.com", rawLength = 1338),
        )
        // 保留 rawContent 更长的那条（74，1340）
        assertEquals(listOf("legado_17670893_76"), SourceDedupe.redundantIds(rows))
    }

    @Test
    fun `启用中的优先保留`() {
        val rows = listOf(
            c("a", "X漫画", "https://x.com", enabled = false, rawLength = 9999),
            c("b", "X漫画", "https://x.com/", enabled = true, rawLength = 10),
        )
        assertEquals(listOf("a"), SourceDedupe.redundantIds(rows))
    }

    @Test
    fun `三胞胎只保留一条`() {
        val rows = listOf(
            c("a", "Y漫画", "https://y.com"),
            c("b", "Y漫画", "https://y.com/"),
            c("c", "Y漫画", "http://Y.com#x"),
        )
        assertEquals(2, SourceDedupe.redundantIds(rows).size)
    }

    // ===== 不该合并的 =====

    @Test
    fun `同名不同站不合并 - 五个笔趣阁`() {
        val urls = listOf(
            "http://www.qbiqus.com/",
            "https://www.hyuzhaiwu.com#🎃",
            "https://mm.xyuzhaiwu.xyz#🎃",
            "http://api.sxwlyhzlp.com",
            "https://www.biquge345.com#🎃",
        )
        val rows = urls.mapIndexed { i, u -> c("legado_31441618_$i", "笔趣阁", u, type = "novel") }
        assertTrue(SourceDedupe.redundantIds(rows).isEmpty())
    }

    @Test
    fun `同站不同名不合并 - 三个源共用 xmanhua`() {
        val rows = listOf(
            c("legado__1970467630_70", "酝酿漫画（魔法）", "https://www.xmanhua.com"),
            c("legado_759401722_75", "铅笔漫画（优）", "https://www.xmanhua.com"),
            c("legado__406213053_78", "星际漫画（优）", "https://www.xmanhua.com"),
        )
        // 名字不同 → 各自保留（可能是同一站点的不同规则变体，删了会丢功能）
        assertTrue(SourceDedupe.redundantIds(rows).isEmpty())
    }

    @Test
    fun `跨类型不合并`() {
        val rows = listOf(
            c("n1", "同名源", "https://z.com", type = "novel"),
            c("c1", "同名源", "https://z.com", type = "comic"),
        )
        assertTrue(SourceDedupe.redundantIds(rows).isEmpty())
    }

    @Test
    fun `baseUrl 为空的源不参与 - Venera 与内置源`() {
        val rows = listOf(
            c("venera_baozi", "包子漫画", ""),
            c("venera_copy_manga", "拷贝漫画", ""),
            c("builtin_x", "本地演示源", ""),
        )
        assertNull(SourceDedupe.groupKeyOf(rows[0]))
        assertTrue(SourceDedupe.redundantIds(rows).isEmpty())
    }

    @Test
    fun `无重复返回空`() {
        val rows = listOf(
            c("a", "甲", "https://a.com"),
            c("b", "乙", "https://b.com"),
        )
        assertTrue(SourceDedupe.redundantIds(rows).isEmpty())
    }

    @Test
    fun `结果确定 - 同档按 id 字典序`() {
        val rows = listOf(
            c("zzz", "Z漫画", "https://z.com"),
            c("aaa", "Z漫画", "https://z.com/"),
        )
        // 同启用同长度同时间 → 保留 id 字典序更小的 aaa
        assertEquals(listOf("zzz"), SourceDedupe.redundantIds(rows))
    }
}
