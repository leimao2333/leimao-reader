package com.novelreader

import com.novelreader.core.search.RecallOutcome
import com.novelreader.core.util.TagResultPolicy
import com.novelreader.core.util.TagVerdict
import com.novelreader.core.util.TagVerdicts
import com.novelreader.domain.model.MediaItem
import com.novelreader.domain.model.SourceType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 标签三态判定测试（v1.8.9.1）。
 *
 * 这套判定的存在意义只有一个：**别把「不知道」当成「不匹配」丢掉**。
 * 以前只有命中 / 不命中两态，超时的源、不进标题的分级词都被判成不命中，
 * 于是「搜无码明明一堆、界面上只剩几条」。下面每个用例都在锁这条线。
 */
class TagVerdictTest {

    private fun item(id: String, title: String, source: String = "s1") =
        MediaItem(id = id, title = title, type = SourceType.COMIC, sourceId = source)

    @Test
    fun `分类页证据优先于标题`() {
        // 站方把它归进「全彩」分类，标题里没有这两个字也不该被否掉
        val verdict = TagVerdicts.of(
            title = "某部作品 第1话",
            tag = "全彩",
            provenByCategory = true,
            sourceOutcome = RecallOutcome.SUCCESS,
        )
        assertEquals(TagVerdict.MATCHED, verdict)
    }

    @Test
    fun `源超时算未知而不是不匹配`() {
        val verdict = TagVerdicts.of(
            title = "某部作品",
            tag = "全彩",
            provenByCategory = false,
            sourceOutcome = RecallOutcome.TIMEOUT,
        )
        assertEquals(TagVerdict.UNKNOWN, verdict)
    }

    @Test
    fun `源报错同样算未知`() {
        assertEquals(
            TagVerdict.UNKNOWN,
            TagVerdicts.of("某部作品", "全彩", false, RecallOutcome.ERROR),
        )
    }

    @Test
    fun `分级词不写进标题也算未知`() {
        // 没有哪部作品叫《无码××》，标题不含它说明不了任何事
        assertEquals(
            TagVerdict.UNKNOWN,
            TagVerdicts.of("某部作品", "无码", false, RecallOutcome.SUCCESS),
        )
        assertEquals(
            TagVerdict.UNKNOWN,
            TagVerdicts.of("某部作品", "里番", false, RecallOutcome.SUCCESS),
        )
    }

    @Test
    fun `题材词标题不含才是真的不匹配`() {
        assertEquals(
            TagVerdict.MISMATCHED,
            TagVerdicts.of("某部作品", "全彩", false, RecallOutcome.SUCCESS),
        )
    }

    @Test
    fun `标题命中即为匹配`() {
        assertEquals(
            TagVerdict.MATCHED,
            TagVerdicts.of("全彩 某部作品", "全彩", false, RecallOutcome.SUCCESS),
        )
    }

    @Test
    fun `匹配项排在未知项之前`() {
        val matched = TagResultPolicy.Candidate(item("a", "全彩 甲"), listOf(TagVerdict.MATCHED))
        val unknown = TagResultPolicy.Candidate(item("b", "乙"), listOf(TagVerdict.UNKNOWN))
        val ranked = TagResultPolicy.rank(listOf(unknown, matched))
        assertEquals(listOf("a", "b"), ranked.map { it.id })
    }

    @Test
    fun `未知项不会被丢掉`() {
        val unknown = TagResultPolicy.Candidate(item("b", "乙"), listOf(TagVerdict.UNKNOWN))
        val ranked = TagResultPolicy.rank(listOf(unknown))
        assertEquals(1, ranked.size)
    }

    @Test
    fun `结果不足下限时也不补明确不匹配的`() {
        // 真机反馈「下面还是无关的」就出在这条：以前条数不足 20 会把判定层
        // 已经否掉的条目补在末尾，半屏都是「和标签没关系的东西」。
        val kept = (1..5).map {
            TagResultPolicy.Candidate(item("k$it", "全彩 作品$it"), listOf(TagVerdict.MATCHED))
        }
        val rejected = (1..3).map {
            TagResultPolicy.Candidate(item("r$it", "无关$it"), listOf(TagVerdict.MISMATCHED))
        }
        val ranked = TagResultPolicy.rank(kept + rejected, minResults = 20)
        assertEquals(5, ranked.size)
        assertTrue(ranked.none { it.id.startsWith("r") })
    }

    @Test
    fun `一条都没有时才退回不匹配的一批`() {
        // 宁可给「确实没搜到」，也不甩空页 —— 但这只在完全没别的可给时才成立
        val rejected = (1..3).map {
            TagResultPolicy.Candidate(item("r$it", "无关$it"), listOf(TagVerdict.MISMATCHED))
        }
        val ranked = TagResultPolicy.rank(rejected, minResults = 20)
        assertEquals(3, ranked.size)
    }

    @Test
    fun `结果够多时不补不匹配项`() {
        val kept = (1..25).map {
            TagResultPolicy.Candidate(item("k$it", "全彩 作品$it"), listOf(TagVerdict.MATCHED))
        }
        val rejected = (1..3).map {
            TagResultPolicy.Candidate(item("r$it", "无关$it"), listOf(TagVerdict.MISMATCHED))
        }
        val ranked = TagResultPolicy.rank(kept + rejected, minResults = 20)
        assertEquals(25, ranked.size)
        assertTrue(ranked.none { it.id.startsWith("r") })
    }

    @Test
    fun `命中标签越多排越前`() {
        val two = TagResultPolicy.Candidate(
            item("two", "全彩 无码 作品"),
            listOf(TagVerdict.MATCHED, TagVerdict.MATCHED),
        )
        val one = TagResultPolicy.Candidate(
            item("one", "全彩 作品"),
            listOf(TagVerdict.MATCHED, TagVerdict.UNKNOWN),
        )
        assertEquals(listOf("two", "one"), TagResultPolicy.rank(listOf(one, two)).map { it.id })
    }

    @Test
    fun `确凿结果够多时不显示没证据的`() {
        // 「和标签没关系的东西」就是这一批：源超时 / 关键词检索宽松命中，
        // 判定层给不出证据。确凿结果够 20 条时整批不显示。
        val proven = (1..25).map {
            TagResultPolicy.Candidate(item("p$it", "全彩 作品$it"), listOf(TagVerdict.MATCHED))
        }
        val unproven = (1..3).map {
            TagResultPolicy.Candidate(item("u$it", "无关$it"), listOf(TagVerdict.UNKNOWN))
        }
        val ranked = TagResultPolicy.rank(proven + unproven)
        assertEquals(25, ranked.size)
        assertTrue(ranked.none { it.id.startsWith("u") })
    }

    @Test
    fun `确凿结果不够时没证据的照样补上`() {
        val proven = (1..3).map {
            TagResultPolicy.Candidate(item("p$it", "全彩 作品$it"), listOf(TagVerdict.MATCHED))
        }
        val unproven = (1..4).map {
            TagResultPolicy.Candidate(item("u$it", "未知$it"), listOf(TagVerdict.UNKNOWN))
        }
        val ranked = TagResultPolicy.rank(proven + unproven)
        assertEquals(7, ranked.size)
        assertTrue(ranked.take(3).all { it.id.startsWith("p") })
    }

    @Test
    fun `同一档内保持召回顺序`() {
        // 排序不再拿标题长度当第二键：那会把长标题的无关内容顶到前面
        val short = TagResultPolicy.Candidate(item("short", "全彩 A"), listOf(TagVerdict.MATCHED))
        val long = TagResultPolicy.Candidate(
            item("long", "全彩 一个特别特别长的标题用来验证不会被排到前面"),
            listOf(TagVerdict.MATCHED),
        )
        assertEquals(listOf("short", "long"), TagResultPolicy.rank(listOf(short, long)).map { it.id })
    }

    @Test
    fun `一般向源上的分级词搜索不会把无关作品留成未知`() {
        // 真机现场：搜「无码」时《夢幻西遊》混进结果，来源是个一般向漫画源。
        // 这类源根本不做这个题材，标题不含「无码」不是「不确定」，是「不匹配」——
        // 以前一律判 unknown，被当成「没被否证」保留，一屏全是无关内容。
        val ctx = TagVerdicts.contextOf("无码")
        assertEquals(
            TagVerdict.MISMATCHED,
            ctx.verdict(
                normalizedTitle = TagVerdicts.normalizeTitle("夢幻西遊"),
                provenByCategory = false,
                sourceOutcome = RecallOutcome.SUCCESS,
                adultSource = false,
            ),
        )
        // 同一个词在成人源上仍是「不确定」：标题不写不代表没有
        assertEquals(
            TagVerdict.UNKNOWN,
            ctx.verdict(
                normalizedTitle = TagVerdicts.normalizeTitle("某部作品 第1话"),
                provenByCategory = false,
                sourceOutcome = RecallOutcome.SUCCESS,
                adultSource = true,
            ),
        )
    }

    @Test
    fun `一般向源的分级词命中不给分类页证据`() {
        // 有些站会把整站目录挂在任意分类下，那种「分类页证据」不能采信
        val ctx = TagVerdicts.contextOf("无码")
        assertEquals(
            TagVerdict.MATCHED,
            ctx.verdict("某部作品", provenByCategory = true, sourceOutcome = RecallOutcome.SUCCESS, adultSource = true),
        )
    }
}
