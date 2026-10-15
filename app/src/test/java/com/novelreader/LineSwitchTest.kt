package com.novelreader

import com.novelreader.core.util.LineSwitch
import com.novelreader.domain.model.ChapterItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 换源 / 测速择优的纯逻辑单测（v1.3.6.0）。
 *
 * 覆盖三条容易写错的规则：
 * 1. 换源要**保集**（切同一集序号），新线路集数不够才退回第 1 集；
 * 2. **不可达的线路不能被当成「最快的」**（null 是「没测出来」，不是「很快」）；
 * 3. 只有一条线路时不标「最快」。
 */
class LineSwitchTest {

    private fun ep(index: Int, url: String) = ChapterItem(
        id = "$index",
        title = "第${index + 1}集",
        url = url,
        index = index,
    )

    // ===== 换源保集 =====

    @Test
    fun `换源切到新线路的同一集序号`() {
        val target = listOf(ep(0, "a1"), ep(1, "a2"), ep(2, "a3"))
        val picked = LineSwitch.pickChapter(target, wantedIndex = 2)
        assertEquals("a3", picked?.url)
        assertEquals(2, picked?.index)
    }

    @Test
    fun `新线路集数不够时退回第一集`() {
        val target = listOf(ep(0, "b1"))
        val picked = LineSwitch.pickChapter(target, wantedIndex = 23)
        assertEquals("b1", picked?.url)
        assertEquals(0, picked?.index)
    }

    @Test
    fun `线路为空时返回 null`() {
        assertNull(LineSwitch.pickChapter(emptyList(), wantedIndex = 0))
    }

    @Test
    fun `集序号为 0 时取第一集`() {
        val target = listOf(ep(0, "c1"), ep(1, "c2"))
        assertEquals("c1", LineSwitch.pickChapter(target, wantedIndex = 0)?.url)
    }

    // ===== 测速择优 =====

    @Test
    fun `选延迟最小的线路`() {
        val speeds = mapOf(0 to 800L, 1 to 120L, 2 to 350L)
        assertEquals(1, LineSwitch.fastestIndex(speeds))
    }

    @Test
    fun `不可达的线路被忽略`() {
        // 0 号不可达（null），不能因为「值是 null」被当成最小而选中
        val speeds = mapOf(0 to null, 1 to 900L, 2 to 400L)
        assertEquals(2, LineSwitch.fastestIndex(speeds))
    }

    @Test
    fun `全部不可达时返回 null`() {
        assertNull(LineSwitch.fastestIndex(mapOf(0 to null, 1 to null)))
    }

    @Test
    fun `还没测速时返回 null`() {
        assertNull(LineSwitch.fastestIndex(emptyMap()))
    }

    @Test
    fun `只有一条可达线路时选它`() {
        assertEquals(3, LineSwitch.fastestIndex(mapOf(3 to 500L, 4 to null)))
    }

    @Test
    fun `延迟相同时取下标较小的`() {
        assertEquals(0, LineSwitch.fastestIndex(mapOf(0 to 200L, 1 to 200L)))
    }

    // ===== 最快角标 =====

    @Test
    fun `多线路时标记最快的那条`() {
        val speeds = mapOf(0 to 500L, 1 to 100L)
        assertTrue(LineSwitch.isFastest(speeds, 1, lineCount = 2))
        assertFalse(LineSwitch.isFastest(speeds, 0, lineCount = 2))
    }

    @Test
    fun `只有一条线路时不标最快`() {
        val speeds = mapOf(0 to 100L)
        assertFalse("单线路没有「最快」的意义", LineSwitch.isFastest(speeds, 0, lineCount = 1))
    }

    @Test
    fun `测速未完成时不标最快`() {
        assertFalse(LineSwitch.isFastest(mapOf(0 to null, 1 to null), 0, lineCount = 2))
    }

    // ===== 记忆线路优先 + 自动优先有效源（v1.6.9.0） =====

    @Test
    fun `记忆线路可达时优先用它`() {
        // 记忆是 2 号（延迟 900ms），但没有比它更快的可选？——不，0 号更快；
        // 「记住上次用的源」优先于「最快」，用户的选择优先
        val speeds = mapOf(0 to 100L, 2 to 900L)
        assertEquals(2, LineSwitch.pickValid(preferred = 2, speeds = speeds, lineCount = 3))
    }

    @Test
    fun `记忆线路不可达时退回最快可达的`() {
        // 记忆的 1 号已经不通了 —— 不能把用户每次带回死线路
        val speeds = mapOf(0 to 700L, 1 to null, 2 to 120L)
        assertEquals(2, LineSwitch.pickValid(preferred = 1, speeds = speeds, lineCount = 3))
    }

    @Test
    fun `没有记忆时选最快可达的`() {
        assertEquals(1, LineSwitch.pickValid(preferred = null, mapOf(0 to 600L, 1 to 80L), 2))
    }

    @Test
    fun `记忆下标越界时按可达性选`() {
        // 源少了一条线路，旧记忆的下标指到了外面
        assertEquals(0, LineSwitch.pickValid(preferred = 7, mapOf(0 to 300L), lineCount = 2))
    }

    @Test
    fun `一条可达的都没有时不瞎切`() {
        // 全不可达 / 都没测 → null，调用方保持现状（而不是切到某条没测过的线上）
        assertNull(LineSwitch.pickValid(preferred = 1, mapOf(0 to null, 1 to null), lineCount = 2))
        assertNull(LineSwitch.pickValid(preferred = null, emptyMap(), lineCount = 3))
    }

    @Test
    fun `播放出错自动切下一条没试过的线路`() {
        assertEquals(0, LineSwitch.nextUntried(lineCount = 3, tried = emptySet()))
        assertEquals(2, LineSwitch.nextUntried(lineCount = 3, tried = setOf(0, 1)))
    }

    @Test
    fun `所有线路都试过之后不再自动换`() {
        // 全试完 → null，调用方如实报错，而不是在坏线路之间来回弹
        assertNull(LineSwitch.nextUntried(lineCount = 3, tried = setOf(0, 1, 2)))
    }
}
