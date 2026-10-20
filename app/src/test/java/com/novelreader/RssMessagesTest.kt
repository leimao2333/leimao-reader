package com.novelreader

import com.novelreader.core.rss.RssMessages
import com.novelreader.domain.model.RssFeedResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 订阅抓取结果的提示文案（v1.8.4.2）。
 *
 * 线上原型：PubMed 的 `rss/search/<id>/` 返回 200 + 合法 `<rss><channel>`，
 * 但一个 `<item>` 都没有（E-utilities 查同一个检索式也是 `count: 0`）。
 * 以前的提示是「已订阅 1 个源，新增 0 篇」—— 和「抓取失败」看起来没区别，
 * 用户只会反复重订阅。这里锁住「源侧为空」和「没有新内容」两种 0 的区别。
 */
class RssMessagesTest {

    private fun ok(title: String, new: Int, fetched: Int) =
        RssFeedResult(1, title, "https://x/$title", newArticles = new, fetchedArticles = fetched)

    private fun fail(title: String, reason: String) =
        RssFeedResult(1, title, "https://x/$title", error = reason)

    @Test
    fun `源返回 0 篇时明确说是源侧为空`() {
        val msg = RssMessages.summarize(
            listOf(ok("Intrinsic physicochemical activity", 0, 0)),
        )
        assertTrue("应点名这个源：$msg", msg.contains("《Intrinsic physicochemical activity》已订阅，但源里返回 0 篇"))
        assertTrue("应给出下一步建议：$msg", msg.contains("稍后刷新或换一个订阅地址"))
        // 不能出现「已订阅 1 个源，新增 0 篇」这种看着像成功又像失败的句子
        assertFalse("不该出现「已订阅 N 个源，新增 0 篇」：$msg", msg.contains("个源，新增"))
    }

    @Test
    fun `源有内容但都是旧文章时按新增 0 篇报`() {
        // fetchedArticles > 0 表示源里确实有 15 条，只是之前都入库了
        val msg = RssMessages.summarize(listOf(ok("博客A", 0, 15)))
        assertTrue(msg.contains("已订阅 1 个源，新增 0 篇"))
        assertFalse("这不是空源，不该提示源侧为空：$msg", msg.contains("源里返回 0 篇"))
    }

    @Test
    fun `混合场景分别报成功与空源`() {
        val msg = RssMessages.summarize(
            listOf(
                ok("博客A", 3, 15),
                ok("PubMed空源", 0, 0),
            ),
        )
        assertTrue(msg.contains("已订阅 1 个源，新增 3 篇"))
        assertTrue(msg.contains("《PubMed空源》已订阅，但源里返回 0 篇"))
    }

    @Test
    fun `失败仍然报失败而不是空源`() {
        val msg = RssMessages.summarize(listOf(fail("坏源", "请求超时")))
        assertTrue(msg.contains("订阅失败：请求超时"))
        assertFalse("失败不该被说成空源：$msg", msg.contains("返回 0 篇"))
    }

    @Test
    fun `没有可用地址时给固定文案`() {
        assertEquals("没有识别到可用的订阅地址", RssMessages.summarize(emptyList()))
    }

    @Test
    fun `超过三个失败只列前三个`() {
        val msg = RssMessages.summarize(
            (1..5).map { fail("源$it", "错误$it") },
        )
        assertTrue(msg.contains("订阅失败：错误1"))
        assertFalse("第 4 个失败不该列出来：$msg", msg.contains("错误4"))
        assertTrue(msg.contains("另有 2 个失败"))
    }

    @Test
    fun `单源刷新区分空源与新增`() {
        assertTrue(
            RssMessages.refreshOne(ok("空源", 0, 0)).contains("刷新完成，但源里返回 0 篇"),
        )
        assertTrue(
            RssMessages.refreshOne(ok("博客A", 5, 15)).contains("已更新《博客A》，新增 5 篇"),
        )
        assertTrue(
            RssMessages.refreshOne(fail("坏源", "请求超时")).contains("刷新失败：请求超时"),
        )
    }

    @Test
    fun `批量刷新标出没有内容的源数`() {
        val msg = RssMessages.refreshAll(
            listOf(ok("博客A", 2, 15), ok("空源", 0, 0), fail("坏源", "超时")),
        )
        assertTrue(msg.contains("已更新 2/3 个源，新增 2 篇"))
        assertTrue(msg.contains("1 个源没有内容"))
        assertTrue(msg.contains("1 个失败"))
    }

    @Test
    fun `批量刷新没有空源时不提空源`() {
        val msg = RssMessages.refreshAll(listOf(ok("博客A", 2, 15)))
        assertEquals("已更新 1/1 个源，新增 2 篇", msg)
    }
}
