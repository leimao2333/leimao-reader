package com.novelreader

import com.novelreader.core.util.SourceHealth
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 源可用性判定（v1.7.6.5）。
 *
 * 规则：能搜到 **且** 目录读得出来才算可用。搜得到但一章都取不到的源
 * 点进去没东西可读，必须算失效，否则「禁用失效 / 删除失效」会漏掉它们。
 * 但搜索本身没结果时无法判定（可能只是关键词不匹配），不能误杀。
 */
class SourceHealthTest {

    @Test
    fun `搜索成功且目录可读才算可用`() {
        assertTrue(SourceHealth.isUsable(searchOk = true, readable = true))
    }

    @Test
    fun `搜得到书但读不了目录算失效`() {
        assertFalse(SourceHealth.isUsable(searchOk = true, readable = false))
        assertTrue(SourceHealth.isNoToc(searchOk = true, readable = false))
    }

    @Test
    fun `搜索失败即为失效`() {
        assertFalse(SourceHealth.isUsable(searchOk = false, readable = null))
        assertFalse(SourceHealth.isUsable(searchOk = false, readable = false))
        // 搜索失败不属于「无目录」这一类，UI 不该说它没有目录
        assertFalse(SourceHealth.isNoToc(searchOk = false, readable = false))
    }

    @Test
    fun `目录无法判定时不误杀`() {
        assertTrue(SourceHealth.isUsable(searchOk = true, readable = null))
        assertFalse(SourceHealth.isNoToc(searchOk = true, readable = null))
    }
}
