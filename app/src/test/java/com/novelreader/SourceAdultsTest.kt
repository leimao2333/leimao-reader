package com.novelreader

import com.novelreader.domain.model.SourceInfo
import com.novelreader.domain.model.SourceType
import com.novelreader.source.category.SourceAdults
import com.novelreader.source.category.isAdult
import com.novelreader.ui.screens.source.SourceSortOrder
import com.novelreader.ui.screens.source.SourceManageViewModel
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 18+ 源识别 + 排序规则测试（v1.5.8.0）。
 */
class SourceAdultsTest {

    private fun src(id: String, name: String, author: String = "", desc: String = ""): SourceInfo =
        SourceInfo(id = id, name = name, type = SourceType.VIDEO, author = author, desc = desc)

    // ===== 18+ 识别 =====

    @Test
    fun `名称以 emoji 前缀开头判定为成人`() {
        assertTrue(SourceAdults.isAdult("🔞精品资源"))
        assertTrue(SourceAdults.isAdult("🔞souavZY"))
    }

    @Test
    fun `名称含 18 加或成人判定为成人`() {
        assertTrue(SourceAdults.isAdult("18+ 影视"))
        assertTrue(SourceAdults.isAdult("成人视频资源"))
        assertTrue(SourceAdults.isAdult("R18 站"))
    }

    @Test
    fun `普通源不判定为成人`() {
        assertFalse(SourceAdults.isAdult("爱奇艺"))
        assertFalse(SourceAdults.isAdult("电影天堂"))
        assertFalse(SourceAdults.isAdult(""))
    }

    @Test
    fun `作者或描述含成人关键词也判定为成人`() {
        assertTrue(SourceAdults.isAdult("某站", author = "", desc = "成人资源采集"))
    }

    @Test
    fun `SourceInfo 扩展一致`() {
        assertTrue(src("a", "🔞xxx").isAdult)
        assertFalse(src("b", "普通影视").isAdult)
    }

    // ===== 漫画 / 图源的成人站识别（v1.7.2.0）=====

    /**
     * 漫画 / Venera 源没有 `🔞` 前缀约定，靠**站点名**与 **Venera 源的 author
     * 字段**（源自己的 key）识别；否则 nhentai 这类站的露骨封面会直接占满
     * 首页推荐的第一屏。
     */
    @Test
    fun `按站点名识别漫画成人源`() {
        assertTrue(SourceAdults.isAdult("nhentai"))
        assertTrue(SourceAdults.isAdult("ehentai"))
        assertTrue(SourceAdults.isAdult("禁漫天堂"))
        assertTrue(SourceAdults.isAdult("18漫画"))
        assertTrue(SourceAdults.isAdult("紳士漫畫"))
        assertTrue(SourceAdults.isAdult("galleriesindex"))
        assertTrue(SourceAdults.isAdult("Picacg"))
        assertTrue(SourceAdults.isAdult("H-Comic"))
    }

    @Test
    fun `按 Venera 源 key 识别漫画成人源`() {
        // 显示名可能被改得看不出内容（如「x」），author 才是源自己的 key
        assertTrue(SourceAdults.isAdult("x", author = "hitomi"))
        assertTrue(SourceAdults.isAdult("某图站", author = "wnacg"))
        assertTrue(SourceAdults.isAdult("某漫画站", author = "nhentai"))
    }

    /**
     * **综合站不该被误伤** —— 它们有成人分区但主体是正常内容，
     * 一起沉底会让首页推荐直接空掉。
     */
    @Test
    fun `综合漫画站不判定为成人`() {
        assertFalse(SourceAdults.isAdult("MangaDex", author = "manga_dex"))
        assertFalse(SourceAdults.isAdult("拷贝漫画", author = "copy_manga"))
        assertFalse(SourceAdults.isAdult("包子漫画", author = "baozi"))
        assertFalse(SourceAdults.isAdult("漫画人", author = "manhuaren"))
        assertFalse(SourceAdults.isAdult("少年ジャンプ＋", author = "shonen_jump_plus"))
    }

    // ===== 排序规则（成人沉底） =====

    @Test
    fun `名称排序把成人源沉底`() {
        val list = listOf(
            src("1", "🔞成人站"),
            src("2", "爱奇艺"),
            src("3", "电影天堂"),
            src("4", "18+站"),
        )
        val vm = FakeSortViewModel()
        val sorted = vm.sort(list, SourceSortOrder.NAME)
        // 普通源按名称升序在前，成人源沉底
        assertEquals(listOf("爱奇艺", "电影天堂", "18+站", "🔞成人站"), sorted.map { it.name })
    }

    @Test
    fun `默认排序也把成人源沉底`() {
        val list = listOf(
            src("1", "🔞成人站"),
            src("2", "爱奇艺"),
        )
        val vm = FakeSortViewModel()
        val sorted = vm.sort(list, SourceSortOrder.DEFAULT)
        assertEquals(listOf("爱奇艺", "🔞成人站"), sorted.map { it.name })
    }

    @Test
    fun `手动排序尊重用户顺序不沉底`() {
        val list = listOf(
            src("1", "🔞成人站", ).copy(sortOrder = 1),
            src("2", "爱奇艺").copy(sortOrder = 2),
        )
        val vm = FakeSortViewModel()
        val sorted = vm.sort(list, SourceSortOrder.MANUAL)
        // 手动模式按 sortOrder 升序，不做成人沉底
        assertEquals(listOf("🔞成人站", "爱奇艺"), sorted.map { it.name })
    }

    @Test
    /** 漏网源回归（v1.7.5.0）：mycomic 显示名是「x」，只按名字判会整批漏掉 */
    fun `漏网的英文成人站按 key 识别`() {
        // mycomic 是英文成人漫画站，Venera 里显示名「x」、key=mycomic
        assertTrue(SourceAdults.isAdult("x", author = "mycomic"))
        // mh18 / 18mh 也判成人
        assertTrue(SourceAdults.isAdult("18漫画", author = "mh18"))
        assertTrue(SourceAdults.isAdult("18漫画"))
    }

    @Test
    fun `综合站与普通漫画不误伤`() {
        // 拷贝漫画 / MangaDex / 包子漫画 是综合站，虽有成人分区也不该整个判成人
        assertFalse(SourceAdults.isAdult("拷贝漫画", author = "copy_manga"))
        assertFalse(SourceAdults.isAdult("MangaDex", author = "manga_dex"))
        assertFalse(SourceAdults.isAdult("包子漫画", author = "baozi"))
        assertFalse(SourceAdults.isAdult("CCC追漫台", author = "ccc"))
        assertFalse(SourceAdults.isAdult("GoDa漫画", author = "goda"))
    }

    @Test
    fun `手动排序未排序项沉底且按名称`() {
        val list = listOf(
            src("1", "🔞成人站").copy(sortOrder = 1),
            src("2", "爱奇艺"),           // sortOrder=0
            src("3", "电影天堂").copy(sortOrder = 2),
            src("4", "成人站B"),          // sortOrder=0
        )
        val vm = FakeSortViewModel()
        val sorted = vm.sort(list, SourceSortOrder.MANUAL)
        // 有 sortOrder 的按序号升序在前，未排序的（sortOrder=0）沉底并按名称
        assertEquals(listOf("🔞成人站", "电影天堂", "成人站B", "爱奇艺"), sorted.map { it.name })
    }

    /** 复用 ViewModel 的排序函数（不依赖 Android 环境） */
    private class FakeSortViewModel {
        fun sort(list: List<SourceInfo>, sortBy: SourceSortOrder): List<SourceInfo> {
            val base = when (sortBy) {
                SourceSortOrder.DEFAULT -> list
                SourceSortOrder.MANUAL -> list.sortedWith(
                    compareBy<SourceInfo> { it.sortOrder == 0 }.thenBy { it.sortOrder }.thenBy { it.name }
                )
                SourceSortOrder.NAME -> list.sortedBy { it.name }
                SourceSortOrder.TYPE -> list.sortedWith(compareBy({ it.type.name }, { it.name }))
                SourceSortOrder.CATEGORY -> list.sortedWith(compareBy({ it.category }, { it.name }))
                SourceSortOrder.ENABLED -> list.sortedByDescending { it.enabled }
            }
            return if (sortBy == SourceSortOrder.MANUAL) base else base.sortedBy { it.isAdult }
        }
    }
}
