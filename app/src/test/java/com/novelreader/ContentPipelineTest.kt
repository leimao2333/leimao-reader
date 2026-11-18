package com.novelreader

import com.novelreader.core.text.ContentPipeline
import com.novelreader.core.text.ContentPipelineConfig
import com.novelreader.core.text.HighlightHit
import com.novelreader.core.text.PipelineResult
import com.novelreader.core.text.PipelineStage
import com.novelreader.core.text.ReplaceRule
import com.novelreader.core.text.StageSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * B8 正文处理流水线 单测。
 *
 * 覆盖：空/异常输入、净化、替换、缩进、高亮、阶段顺序约束、幂等性、
 * 质量检查标记、变更摘要计数、各开关独立生效。
 */
class ContentPipelineTest {

    /** 一段够长的正常正文（能通过质量检查的阈值） */
    private val normalText = buildString {
        repeat(10) {
            append("他沿着河岸慢慢走着，风把书页吹得哗哗响，远处传来几声狗叫。\n")
        }
    }

    // ===== 空输入 / 异常输入 =====

    @Test
    fun `空字符串原样返回且不抛异常`() {
        val result = ContentPipeline.process("", ContentPipelineConfig.reading())
        assertEquals("", result.text)
        assertEquals(emptyList<HighlightHit>(), result.highlights)
        assertNull("空正文不该做质量扫描", result.quality)
        assertFalse(result.lowQuality)
    }

    @Test
    fun `null 语义按空串处理`() {
        val result = ContentPipeline.process(null, ContentPipelineConfig.reading())
        assertEquals("", result.text)
        assertNotNull(result.summaries)
    }

    @Test
    fun `纯空白正文不抛异常且净化后为空`() {
        val result = ContentPipeline.process("   \n \t \n  ", ContentPipelineConfig.reading())
        assertEquals("", result.text)
        assertFalse(result.lowQuality)
    }

    @Test
    fun `异常输入不崩溃`() {
        // 控制字符、超长串、只有换行、只有 BOM 等都不应抛出
        val cases = listOf(
            "\u0000\u0001\u0002",
            "\uFEFF",
            "\n".repeat(5000),
            "a".repeat(100000),
            "«未闭合的标记",
            "\uD800", // 孤立代理项
        )
        for (c in cases) {
            val result = ContentPipeline.process(c, ContentPipelineConfig.reading())
            assertNotNull("输入 ${c.take(6)} 应有结果", result.text)
        }
    }

    // ===== 净化 =====

    @Test
    fun `净化折叠多余空行`() {
        val config = ContentPipelineConfig(sanitize = true, maxBlankLines = 1)
        val result = ContentPipeline.process("第一段\n\n\n\n\n第二段", config)
        assertEquals("第一段\n\n第二段", result.text)
    }

    @Test
    fun `净化按配置保留多个空行`() {
        val config = ContentPipelineConfig(sanitize = true, maxBlankLines = 2)
        val result = ContentPipeline.process("第一段\n\n\n\n\n第二段", config)
        assertEquals("第一段\n\n\n第二段", result.text)
    }

    @Test
    fun `净化清理控制字符与 BOM`() {
        val config = ContentPipelineConfig(sanitize = true)
        val result = ContentPipeline.process("正文\u0007开始\uFEFF", config)
        assertEquals("正文开始", result.text)
    }

    @Test
    fun `净化统一换行符`() {
        val config = ContentPipelineConfig(sanitize = true)
        val result = ContentPipeline.process("第一段\r\n第二段\r第三段", config)
        assertEquals("第一段\n第二段\n第三段", result.text)
    }

    @Test
    fun `净化关闭时正文原样返回`() {
        val raw = "第一段\n\n\n\n\n第二段"
        val result = ContentPipeline.process(raw, ContentPipelineConfig(sanitize = false))
        assertEquals(raw, result.text)
    }

    // ===== 替换 =====

    @Test
    fun `替换规则生效`() {
        val config = ContentPipelineConfig(replaceRules = listOf(ReplaceRule("广告内容", "")))
        val result = ContentPipeline.process("正文广告内容结束", config)
        assertEquals("正文结束", result.text)
    }

    @Test
    fun `替换规则支持正则`() {
        val config = ContentPipelineConfig(
            replaceRules = listOf(ReplaceRule("第\\d+章", "【章节】", isRegex = true)),
        )
        val result = ContentPipeline.process("第123章 标题", config)
        assertEquals("【章节】 标题", result.text)
    }

    @Test
    fun `非法正则不影响其余规则`() {
        val config = ContentPipelineConfig(
            replaceRules = listOf(
                ReplaceRule("(未闭合", "", isRegex = true),
                ReplaceRule("X", "Y"),
            ),
        )
        val result = ContentPipeline.process("X", config)
        assertEquals("Y", result.text)
    }

    // ===== 段首缩进 =====

    @Test
    fun `段首缩进开启时每段加前缀`() {
        val config = ContentPipelineConfig(indentEnabled = true, indentChar = "　", indentWidth = 2)
        val result = ContentPipeline.process("第一段\n\n第二段", config)
        assertEquals("　　第一段\n\n　　第二段", result.text)
    }

    @Test
    fun `段首缩进关闭时不加前缀`() {
        val result = ContentPipeline.process("第一段\n\n第二段", ContentPipelineConfig())
        assertEquals("第一段\n\n第二段", result.text)
    }

    @Test
    fun `段首缩进宽度可配`() {
        val config = ContentPipelineConfig(indentEnabled = true, indentChar = "\t", indentWidth = 1)
        val result = ContentPipeline.process("第一段", config)
        assertEquals("\t第一段", result.text)
    }

    // ===== 高亮 =====

    @Test
    fun `高亮标记命中词并给出位置`() {
        val config = ContentPipelineConfig(highlightTerms = listOf("狗叫"))
        val result = ContentPipeline.process("远处传来几声狗叫。", config)
        assertEquals("远处传来几声«狗叫»。", result.text)
        assertEquals(1, result.highlights.size)
        val hit = result.highlights.first()
        assertEquals("狗叫", hit.term)
        assertEquals("远处传来几声«狗叫»。".indexOf("狗叫"), hit.start)
        assertEquals(hit.start + 2, hit.end)
    }

    @Test
    fun `高亮关闭时不插标记`() {
        val result = ContentPipeline.process("远处传来几声狗叫。", ContentPipelineConfig())
        assertEquals("远处传来几声狗叫。", result.text)
        assertTrue(result.highlights.isEmpty())
    }

    @Test
    fun `高亮不会重复标记已处理过的正文`() {
        val config = ContentPipelineConfig(highlightTerms = listOf("狗叫"))
        val once = ContentPipeline.process("远处传来几声狗叫。", config)
        val twice = ContentPipeline.process(once.text, config)
        assertEquals("处理两次不能多出标记", once.text, twice.text)
        assertEquals(once.highlights, twice.highlights)
    }

    // ===== 阶段顺序 =====

    @Test
    fun `阶段顺序可配置`() {
        // 把缩进放到净化前面
        val config = ContentPipelineConfig(
            sanitize = true,
            indentEnabled = true,
            stageOrder = listOf(
                PipelineStage.INDENT,
                PipelineStage.SANITIZE,
                PipelineStage.REPLACE,
                PipelineStage.HIGHLIGHT,
                PipelineStage.QUALITY,
            ),
        )
        val result = ContentPipeline.process("第一段\n\n第二段", config)
        assertEquals(
            listOf(
                PipelineStage.INDENT,
                PipelineStage.SANITIZE,
                PipelineStage.REPLACE,
                PipelineStage.HIGHLIGHT,
                PipelineStage.QUALITY,
            ),
            result.stageOrder,
        )
        assertEquals("　　第一段\n\n　　第二段", result.text)
    }

    @Test
    fun `高亮被强制排在替换之后`() {
        // 调用方故意把高亮排在替换前面
        val config = ContentPipelineConfig(
            replaceRules = listOf(ReplaceRule("«", "")),
            highlightTerms = listOf("狗叫"),
            stageOrder = listOf(
                PipelineStage.HIGHLIGHT,
                PipelineStage.REPLACE,
                PipelineStage.SANITIZE,
            ),
        )
        val result = ContentPipeline.process("远处传来几声狗叫。", config)
        val replaceIdx = result.stageOrder.indexOf(PipelineStage.REPLACE)
        val highlightIdx = result.stageOrder.indexOf(PipelineStage.HIGHLIGHT)
        assertTrue("高亮必须排在替换之后", highlightIdx > replaceIdx)
        assertTrue("顺序被调整过应被标记", result.orderAdjusted)
    }

    @Test
    fun `高亮在替换之前时替换规则会破坏标记 因此顺序必须被纠正`() {
        // 规则会吃掉高亮标记字符「«」：若替换真的跑在高亮之后，
        // 标记会被抹掉、高亮命中信息全丢。
        val rule = ReplaceRule("«", "")
        val config = ContentPipelineConfig(
            replaceRules = listOf(rule),
            highlightTerms = listOf("狗叫"),
            stageOrder = listOf(PipelineStage.HIGHLIGHT, PipelineStage.REPLACE),
        )
        val result = ContentPipeline.process("远处传来几声狗叫。", config)
        // 顺序被纠正成「替换 → 高亮」：先替换（正文里还没有标记，规则无命中），
        // 再高亮，标记得以保留。
        assertEquals("远处传来几声«狗叫»。", result.text)
        assertEquals(1, result.highlights.size)
        assertEquals("狗叫", result.highlights.first().term)
    }

    @Test
    fun `默认顺序下不需要调整`() {
        val config = ContentPipelineConfig(
            replaceRules = listOf(ReplaceRule("a", "b")),
            highlightTerms = listOf("b"),
        )
        val result = ContentPipeline.process("a", config)
        assertFalse(result.orderAdjusted)
        assertEquals(
            listOf(
                PipelineStage.SANITIZE,
                PipelineStage.REPLACE,
                PipelineStage.HIGHLIGHT,
                PipelineStage.INDENT,
                PipelineStage.QUALITY,
            ),
            result.stageOrder,
        )
    }

    // ===== 幂等性 =====

    @Test
    fun `处理两次等于处理一次`() {
        val config = ContentPipelineConfig(
            sanitize = true,
            replaceRules = listOf(ReplaceRule("广告", "")),
            highlightTerms = listOf("狗叫"),
            indentEnabled = true,
            qualityCheck = true,
        )
        val once = ContentPipeline.process(normalText, config)
        val twice = ContentPipeline.process(once.text, config)
        assertEquals(once.text, twice.text)
        assertEquals(once.highlights, twice.highlights)
        assertEquals(once.quality, twice.quality)
        assertEquals(once.lowQuality, twice.lowQuality)
    }

    @Test
    fun `段首缩进处理两次不会叠成四格`() {
        val config = ContentPipelineConfig(indentEnabled = true, indentChar = "　", indentWidth = 2)
        val once = ContentPipeline.process("第一段\n\n第二段", config)
        val twice = ContentPipeline.process(once.text, config)
        assertEquals("　　第一段\n\n　　第二段", twice.text)
    }

    @Test
    fun `净化处理两次结果相同`() {
        val config = ContentPipelineConfig(sanitize = true)
        val once = ContentPipeline.process("  第一段  \n\n\n\n第二段  ", config)
        val twice = ContentPipeline.process(once.text, config)
        assertEquals(once.text, twice.text)
    }

    @Test
    fun `结果对象整体可比较`() {
        val config = ContentPipelineConfig.reading(highlightTerms = listOf("狗叫"))
        val once = ContentPipeline.process(normalText, config)
        val twice = ContentPipeline.process(once.text, config)
        assertEquals(once as PipelineResult, twice)
    }

    // ===== 质量检查 =====

    @Test
    fun `质量检查触发时结果带低质量标记`() {
        val config = ContentPipelineConfig(qualityCheck = true)
        val result = ContentPipeline.process("短", config)
        assertNotNull(result.quality)
        assertTrue("极短正文应判为低质量", result.lowQuality)
    }

    @Test
    fun `正常正文质量检查不判低质量`() {
        val config = ContentPipelineConfig(qualityCheck = true)
        val result = ContentPipeline.process(normalText, config)
        assertNotNull(result.quality)
        assertFalse(result.lowQuality)
        assertTrue("正常正文分数应偏高", result.quality!!.score >= 70)
    }

    @Test
    fun `质量检查关闭时结果里没有质量快照`() {
        val result = ContentPipeline.process(normalText, ContentPipelineConfig())
        assertNull(result.quality)
        assertFalse(result.lowQuality)
    }

    // ===== 变更摘要 =====

    @Test
    fun `变更摘要计数正确`() {
        val config = ContentPipelineConfig(
            sanitize = true,
            replaceRules = listOf(ReplaceRule("广告", ""), ReplaceRule("正文", "内容")),
            highlightTerms = listOf("狗叫"),
            indentEnabled = true,
            qualityCheck = true,
        )
        // 正文要够长（≥20 字），否则质量检查会判成低质量，测不到「正常」这一档
        val result = ContentPipeline.process(
            "正文段落里写满了很长很长的叙述文字足够撑起二十个字的门槛，" +
                "广告\n\n\n\n远处传来几声狗叫。",
            config,
        )

        val sanitize = result.summaries.filterIsInstance<StageSummary.Sanitize>().single()
        assertEquals("净化后只剩一处空行组", 1, sanitize.blankLineGroups)
        assertEquals(0, sanitize.controlChars)

        val replace = result.summaries.filterIsInstance<StageSummary.Replace>().single()
        assertEquals("两条规则都已处理干净", 2, replace.appliedRules)
        assertEquals(0, replace.remainingMatches)

        val highlight = result.summaries.filterIsInstance<StageSummary.Highlight>().single()
        assertEquals(1, highlight.hits)
        assertEquals(1, highlight.terms)

        val indent = result.summaries.filterIsInstance<StageSummary.Indent>().single()
        assertEquals(2, indent.indentedParagraphs)

        val quality = result.summaries.filterIsInstance<StageSummary.Quality>().single()
        assertFalse("正文够长不应判为低质量", quality.lowQuality)
    }

    @Test
    fun `摘要覆盖每个阶段且顺序与阶段表一致`() {
        val config = ContentPipelineConfig.reading(highlightTerms = listOf("狗叫"))
        val result = ContentPipeline.process(normalText, config)
        assertEquals(result.stageOrder.size, result.summaries.size)
        for ((i, stage) in result.stageOrder.withIndex()) {
            assertEquals("第 $i 个摘要应属于 ${stage.name}", stage, result.summaries[i].stage)
        }
    }

    @Test
    fun `未启用的阶段摘要标记为跳过`() {
        val result = ContentPipeline.process("正文", ContentPipelineConfig())
        val skipped = result.summaries.filterIsInstance<StageSummary.Skipped>()
        assertEquals("默认配置下所有阶段都跳过", 5, skipped.size)
    }

    // ===== 开关独立性 =====

    @Test
    fun `只开净化不影响替换与高亮`() {
        val config = ContentPipelineConfig(sanitize = true)
        val result = ContentPipeline.process("第一段\n\n\n\n第二段", config)
        assertEquals("第一段\n\n第二段", result.text)
        assertTrue(result.highlights.isEmpty())
        assertNull(result.quality)
    }

    @Test
    fun `只开替换不影响净化`() {
        val config = ContentPipelineConfig(replaceRules = listOf(ReplaceRule("A", "B")))
        val result = ContentPipeline.process("A\n\n\n\nB", config)
        // 净化没开，多余空行应保留
        assertEquals("B\n\n\n\nB", result.text)
    }

    @Test
    fun `只开高亮不改动其它内容`() {
        val config = ContentPipelineConfig(highlightTerms = listOf("狗叫"))
        val result = ContentPipeline.process("远处传来几声狗叫。", config)
        assertEquals("远处传来几声«狗叫»。", result.text)
        assertNull(result.quality)
    }

    @Test
    fun `只开缩进不改动其它内容`() {
        val config = ContentPipelineConfig(indentEnabled = true)
        val result = ContentPipeline.process("第一段", config)
        assertEquals("　　第一段", result.text)
        assertTrue(result.highlights.isEmpty())
    }

    @Test
    fun `空配置的纯正文原样返回`() {
        val raw = "第一章\n\n他抬起头，看着窗外的雨。\n\n第二章"
        val result = ContentPipeline.process(raw, ContentPipelineConfig())
        assertEquals(raw, result.text)
    }
}
