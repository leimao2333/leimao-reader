package com.novelreader

import com.novelreader.source.legado.LegadoBookSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 书源列表解析的进度回调（v1.8.3.0 待办 #21）。
 *
 * 大书源包导入时要能看到「解析到第几个」，回调必须：
 * - 逐条报进度，最终值等于总条数；
 * - 回调里抛异常能中断整个解析（用户点取消）。
 */
class SourceParseProgressTest {

    private val listJson = """
        [
          {"bookSourceName":"源A","bookSourceUrl":"https://a.example"},
          {"bookSourceName":"源B","bookSourceUrl":"https://b.example"},
          {"bookSourceName":"源C","bookSourceUrl":"https://c.example"}
        ]
    """.trimIndent()

    @Test
    fun `逐条回调进度并以总条数收尾`() {
        val events = mutableListOf<Pair<Int, Int>>()
        val detailed = LegadoBookSource.parseListWithRawDetailed(listJson) { done, total ->
            events += done to total
        }

        assertEquals(3, detailed.pairs.size)
        assertEquals(0, detailed.skipped)
        // 每条都报一次 + 开头结尾各一次
        assertEquals(5, events.size)
        assertTrue("总条数应始终为 3：$events", events.all { it.second == 3 })
        assertEquals(3 to 3, events.last())
        // 单调不减，且真的走到了 2（第三条报的是已完成 2 条）
        assertTrue("进度应单调不减：$events", events.map { it.first }.zipWithNext().all { it.first <= it.second })
        assertTrue("应覆盖到最后一条之前的进度：$events", events.any { it.first == 2 })
    }

    @Test
    fun `单个书源也报一次进度`() {
        val events = mutableListOf<Pair<Int, Int>>()
        LegadoBookSource.parseListWithRawDetailed(
            """{"bookSourceName":"单源","bookSourceUrl":"https://x.example"}""",
        ) { done, total -> events += done to total }

        assertEquals(listOf(1 to 1), events)
    }

    @Test
    fun `回调抛异常可中断解析`() {
        var reported = 0
        val thrown = runCatching {
            LegadoBookSource.parseListWithRawDetailed(listJson) { _, _ ->
                reported++
                if (reported > 1) throw kotlinx.coroutines.CancellationException("已取消")
            }
        }.exceptionOrNull()

        assertTrue("应抛出取消异常", thrown is kotlinx.coroutines.CancellationException)
        // 抛异常前只报了两条（开头一次 + 第一条）
        assertEquals(2, reported)
    }
}
