package com.novelreader

import com.novelreader.domain.model.ChapterItem
import com.novelreader.domain.model.MediaItem
import com.novelreader.domain.model.SourceType
import com.novelreader.source.search.FuzzyMatch
import com.novelreader.ui.navigation.NavArgs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 导航参数编码测试——覆盖「点击书源闪退」的根因修复。
 *
 * 修复前用 `Uri.encode(json)`，而 `Uri.encode` 默认**不转义 `/`**；
 * 书目的 detailUrl / cover 都是 `https://...` 形式，一旦 JSON 含 `/`，
 * 整串被 Navigation 当成多级路径 → 匹配失败抛 IllegalArgumentException → 闪退。
 *
 * 现在改为 Base64（URL-safe，无 '=' 补位），编码结果只含 `A-Za-z0-9-_`。
 */
class NavArgsTest {

    private fun sampleItem() = MediaItem(
        id = "https://www.example.com/book/123?from=search&x=1",
        title = "测试小说《第一部》",
        cover = "https://img.example.com/a/b/c.jpg",
        author = "测试作者",
        desc = "含 / 与 ? 和 & 的简介",
        type = SourceType.NOVEL,
        detailUrl = "https://www.example.com/book/123?from=search",
        sourceId = "src-1",
    )

    @Test
    fun `编码结果只含 URL 安全字符`() {
        val encoded = NavArgs.encodeItem(sampleItem())
        assertTrue("编码不应含 '/'", !encoded.contains('/'))
        assertTrue("编码不应含 '+'", !encoded.contains('+'))
        assertTrue("编码不应含 '='", !encoded.contains('='))
        assertTrue("编码不应含 '?'", !encoded.contains('?'))
        assertTrue("编码不应含 '&'", !encoded.contains('&'))
        assertTrue("编码不应含空格", !encoded.contains(' '))
        assertTrue("编码应匹配 URL-safe Base64 字符集", encoded.matches(Regex("[A-Za-z0-9_-]+")))
    }

    @Test
    fun `MediaItem 编解码往返一致`() {
        val item = sampleItem()
        assertEquals(item, NavArgs.decodeItem(NavArgs.encodeItem(item)))
    }

    @Test
    fun `含中文与特殊符号的字段往返不丢失`() {
        val item = sampleItem().copy(title = "书名：测试/含斜杠「引号」", author = "作者·A & B")
        val back = NavArgs.decodeItem(NavArgs.encodeItem(item))
        assertEquals("书名：测试/含斜杠「引号」", back.title)
        assertEquals("作者·A & B", back.author)
    }

    @Test
    fun `ChapterItem 与章节列表编解码往返一致`() {
        val chapter = ChapterItem(
            id = "https://www.example.com/chapter/1?t=1",
            title = "第1章 起始",
            url = "https://www.example.com/chapter/1",
            index = 0,
            sourceId = "src-1",
        )
        assertEquals(chapter, NavArgs.decodeChapter(NavArgs.encodeChapter(chapter)))

        val list = listOf(chapter, chapter.copy(id = "2", title = "第2章", url = "https://e.com/2", index = 1))
        assertEquals(list, NavArgs.decodeChapters(NavArgs.encodeChapters(list)))
    }

    @Test
    fun `模拟路由拼接不会被斜杠破坏`() {
        // Navigation 的 route 形如 "novel_detail/{item}"：编码值中若含 '/' 会被当成多级路径，导致匹配失败
        val encoded = NavArgs.encodeItem(sampleItem())
        val route = "novel_detail/$encoded"
        // 只应出现一处 '/'（route 模板自身的那一处）
        assertEquals("route 中应只有模板自带的一个 '/'", 1, route.count { it == '/' })
    }

    @Test
    fun `非法编码不会抛异常`() {
        val decoded = NavArgs.decodeItem("这不是合法的编码!!!")
        assertTrue(decoded.title.isEmpty())
        assertFalse(NavArgs.decodeChapters("坏数据").isNotEmpty())
    }
}

/**
 * 搜索结果去重 / 精准排序测试——覆盖「搜索结果重复」「无用源结果干扰」相关修复。
 */
class FuzzyMatchTest {

    private fun item(id: String, title: String, author: String? = null, sourceId: String = "s1") =
        MediaItem(
            id = id,
            title = title,
            author = author,
            type = SourceType.NOVEL,
            detailUrl = id,
            sourceId = sourceId,
        )

    @Test
    fun `同源同书去重，保留首次出现者`() {
        val items = listOf(
            item("a", "剑来"),
            item("a", "剑来"),          // 完全重复
            item("b", "剑来"),
        )
        val out = FuzzyMatch.dedupe(items)
        assertEquals(2, out.size)
        assertEquals("a", out[0].id)
        assertEquals("b", out[1].id)
    }

    @Test
    fun `不同源的同名书不被去重`() {
        val items = listOf(
            item("https://a.com/1", "剑来", sourceId = "s1"),
            item("https://b.com/1", "剑来", sourceId = "s2"),
        )
        assertEquals(2, FuzzyMatch.dedupe(items).size)
    }

    @Test
    fun `精准模式只保留同名结果`() {
        val items = listOf(
            item("1", "剑来"),
            item("2", "剑来同人"),
            item("3", "完全无关的书"),
            item("4", "《剑来》"),        // 归一化后同名
        )
        val out = FuzzyMatch.exactRank(items, "剑来")
        assertEquals(2, out.size)
        assertTrue(out.all { it.title.contains("剑来") })
        assertTrue("归一化同名的《剑来》应被保留", out.any { it.title == "《剑来》" })
    }

    @Test
    fun `模糊模式过滤完全不相关结果`() {
        val items = listOf(
            item("1", "剑来"),
            item("2", "完全无关的书名"),
        )
        val out = FuzzyMatch.rank(items, "剑来")
        assertEquals(1, out.size)
        assertEquals("剑来", out[0].title)
    }

    @Test
    fun `归一化忽略书名号与空白`() {
        assertEquals(FuzzyMatch.normalize("剑来"), FuzzyMatch.normalize("《剑 来》"))
    }

    @Test
    fun `精确模式搜索千面之龙过滤含千面的无关书`() {
        // 回归：源对「千面之龙」会返回大量仅含「千面」的无关书，精准模式必须全部过滤
        val items = listOf(
            item("1", "千面之龙"),
            item("2", "萌宝无敌：千面妈咪送上门"),
            item("3", "摄政王的千面宠妃"),
            item("4", "重生之千面影帝"),
            item("5", "综影视：与你终错终点"),
            item("6", "《千面之龙》"),   // 归一化后同名，应保留
        )
        val out = FuzzyMatch.exactRank(items, "千面之龙")
        assertEquals(2, out.size)
        assertTrue(out.all { FuzzyMatch.normalize(it.title) == "千面之龙" })
    }

    @Test
    fun `模糊模式搜索千面之龙过滤仅含千面的无关书`() {
        val items = listOf(
            item("1", "千面之龙"),
            item("2", "萌宝无敌：千面妈咪送上门"),
            item("3", "摄政王的千面宠妃"),
            item("4", "重生之千面影帝"),
        )
        val out = FuzzyMatch.rank(items, "千面之龙")
        assertEquals("模糊模式不应把仅含「千面」的无关书放进结果", 1, out.size)
        assertEquals("千面之龙", out[0].title)
    }

    @Test
    fun `作者带来源前缀的同名书应合并为一组`() {
        // 回归：「番茄小说·某某」与「某某」作者前缀不同，归一化去前缀后应判为同一作者
        val items = listOf(
            item("1", "剑来", author = "番茄小说·烽火戏诸侯", sourceId = "s1"),
            item("2", "剑来", author = "烽火戏诸侯", sourceId = "s2"),
        )
        val groups = FuzzyMatch.groupDuplicates(items)
        assertEquals("作者前缀不同的同名书应合并为一组", 1, groups.size)
        assertEquals(2, groups[0].size)
    }

    @Test
    fun `作者归一化去掉来源前缀`() {
        assertEquals("烽火戏诸侯", FuzzyMatch.normalizeAuthor("番茄小说·烽火戏诸侯"))
        assertEquals("烽火戏诸侯", FuzzyMatch.normalizeAuthor("烽火戏诸侯"))
        assertEquals("佚名", FuzzyMatch.normalizeAuthor("笔趣阁·佚名"))
    }

    @Test
    fun `模糊模式同名排最前后面是类似名字`() {
        // 回归：同名（归一化相等）必须绝对排最前，类似名字（含关键词/以关键词开头）排后面
        val items = listOf(
            item("1", "千面之龙同人", sourceId = "s1"),   // 类似名字（以关键词开头）
            item("2", "千面之龙", sourceId = "s2"),        // 同名
            item("3", "《千面之龙》", sourceId = "s3"),     // 同名（带书名号）
            item("4", "千面之龙之剑", sourceId = "s4"),    // 类似名字（包含关键词）
        )
        val out = FuzzyMatch.rank(items, "千面之龙")
        val titles = out.map { FuzzyMatch.normalize(it.title) }
        assertEquals(4, out.size)
        // 前两个必须是同名（归一化后 == 千面之龙）
        assertTrue("同名应排最前", titles[0] == "千面之龙" && titles[1] == "千面之龙")
    }
}
