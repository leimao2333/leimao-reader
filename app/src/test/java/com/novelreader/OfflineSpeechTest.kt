package com.novelreader

import com.novelreader.core.asr.PcmUtils
import com.novelreader.core.asr.SubtitleBuilder
import com.novelreader.core.asr.SubtitleCue
import com.novelreader.core.model.LocalModels
import com.novelreader.core.model.formatModelSize
import com.novelreader.core.tts.normalizeForTts
import com.novelreader.core.tts.splitSentences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 离线语音相关的纯逻辑单测（v1.8.7.3）。
 *
 * 这几块都「错了也不会崩、但用户一定能感觉到」：
 * 切句影响第一声要等多久，字幕时间轴错了会越看越偏，
 * PCM 解码错一个字节整段识别就是乱码。所以逐条锁住。
 */
class OfflineSpeechTest {

    // ===== 朗读切句 =====

    @Test
    fun `按句末标点切句`() {
        val out = splitSentences("第一句。第二句！第三句？")
        assertEquals(listOf("第一句。", "第二句！", "第三句？"), out)
    }

    @Test
    fun `换行也切`() {
        val out = splitSentences("第一段\n第二段")
        assertEquals(listOf("第一段", "第二段"), out)
    }

    @Test
    fun `纯标点与空白不产生句子`() {
        assertTrue(splitSentences("   ").isEmpty())
        assertTrue(splitSentences("。。。\n\n！").isEmpty())
    }

    // ===== 朗读前的字符归一 =====

    /**
     * 词典外的字符会被 native 侧静默丢弃，所以必须在进模型前换掉。
     *
     * 真机上踩到的第一个：「二〇二四年」念成「二四年」。这条断言看着琐碎，
     * 但丢字是**没有任何可见报错**的那类问题，只能靠测试锁。
     */
    @Test
    fun `圆圈零归一为汉字零`() {
        assertEquals("二零二四年十月", normalizeForTts("二〇二四年十月"))
        assertEquals("二零二四年", normalizeForTts("二○二四年"))
    }

    @Test
    fun `全角数字归一为半角`() {
        assertEquals("第2024章", normalizeForTts("第２０２４章"))
    }

    @Test
    fun `词典外的间隔号换成停顿`() {
        assertEquals("甲，乙", normalizeForTts("甲·乙"))
    }

    @Test
    fun `超长句在逗号处补切`() {
        // 每段 10 字、带逗号：攒过阈值后遇到的第一个逗号就是切点
        val text = "这是一个很长的句子，".repeat(5) + "后半段"
        val out = splitSentences(text, maxSentenceChars = 20)
        assertTrue("应该被切成多句", out.size >= 2)
        // 软切点落在「超过阈值后的第一个逗号」，所以会略微超出阈值（这里 30 字），
        // 但绝不该一路攒到 2× 阈值的硬切上限（40 字）—— 那等于软切完全失效
        assertTrue("首句不该攒到硬切上限", out.first().length < 20 * 2)
    }

    // ===== 字幕切分与时间轴 =====

    @Test
    fun `字幕按标点切条`() {
        val cues = SubtitleBuilder.build("你好。今天天气不错！", 0L, 4000L)
        assertEquals(2, cues.size)
        assertEquals("你好。", cues[0].text)
        assertEquals("今天天气不错！", cues[1].text)
    }

    @Test
    fun `时间轴首尾对齐且按字数分配`() {
        val cues = SubtitleBuilder.build("一二三四。五六。", 1000L, 5000L)
        assertEquals(2, cues.size)
        assertEquals(1000L, cues[0].startMs)
        // 最后一条必须正好收在 endMs，否则字幕会越来越偏
        assertEquals(5000L, cues.last().endMs)
        // 条与条之间不能有重叠或空洞
        assertEquals(cues[0].endMs, cues[1].startMs)
        // 字数多的那条应该占更长的时间
        assertTrue(cues[0].endMs - cues[0].startMs > cues[1].endMs - cues[1].startMs)
    }

    @Test
    fun `空文本或零长度不产生字幕`() {
        assertTrue(SubtitleBuilder.build("", 0L, 1000L).isEmpty())
        assertTrue(SubtitleBuilder.build("有字", 1000L, 1000L).isEmpty())
    }

    @Test
    fun `超长句按最大字数硬切`() {
        val cues = SubtitleBuilder.build("字".repeat(60), 0L, 6000L)
        assertTrue(cues.size >= 3)
        assertTrue(cues.all { it.text.length <= SubtitleBuilder.MAX_CHARS })
    }

    @Test
    fun `导出 SRT 格式正确`() {
        val cues = listOf(
            com.novelreader.core.asr.SubtitleCue(0L, 1500L, "第一句"),
            com.novelreader.core.asr.SubtitleCue(1500L, 3661_500L, "第二句"),
        )
        val srt = SubtitleBuilder.toSrt(cues)
        assertTrue(srt.contains("1\n00:00:00,000 --> 00:00:01,500\n第一句"))
        assertTrue(srt.contains("2\n00:00:01,500 --> 01:01:01,500\n第二句"))
    }

    // ===== PCM 处理 =====

    @Test
    fun `16bit 小端解码为 float`() {
        // 0x7FFF = 32767 → 约 0.99997；0x8000 = -32768 → -1.0
        val pcm = byteArrayOf(
            0xFF.toByte(), 0x7F, // 32767
            0x00, 0x80.toByte(), // -32768
            0x00, 0x00, // 0
        )
        val out = PcmUtils.toMonoFloat(pcm, channelCount = 1)
        assertEquals(3, out.size)
        assertTrue(out[0] > 0.99f)
        assertEquals(-1f, out[1], 0.0001f)
        assertEquals(0f, out[2], 0.0001f)
    }

    @Test
    fun `立体声取平均`() {
        // 左 32767，右 0 → 平均约 0.5
        val pcm = byteArrayOf(0xFF.toByte(), 0x7F, 0x00, 0x00)
        val out = PcmUtils.toMonoFloat(pcm, channelCount = 2)
        assertEquals(1, out.size)
        assertEquals(0.5f, out[0], 0.001f)
    }

    @Test
    fun `重采样长度按比例变化`() {
        val input = FloatArray(4800) { 0.5f }
        val out = PcmUtils.resample(input, fromRate = 48000, toRate = 16000)
        assertEquals(1600, out.size)
        // 常量信号重采样后仍是常量
        assertTrue(out.all { kotlin.math.abs(it - 0.5f) < 0.001f })
    }

    @Test
    fun `同采样率原样返回`() {
        val input = floatArrayOf(0.1f, 0.2f, 0.3f)
        assertTrue(PcmUtils.resample(input, 16000, 16000) === input)
    }

    @Test
    fun `拼接保持顺序`() {
        val out = PcmUtils.concat(floatArrayOf(1f, 2f), floatArrayOf(3f))
        assertEquals(3, out.size)
        assertEquals(1f, out[0], 0f)
        assertEquals(3f, out[2], 0f)
    }

    // ===== 静音判定 =====

    /**
     * 纯静音必须被判成静音。
     *
     * 这条不是理论洁癖：实测把 1 秒全零采样喂给模型，它会「听」出一句
     * 「我的知道」。片子里的过场、纯音乐、片尾都是这种输入，
     * 不挡就会在安静段落凭空冒出几句无关的话。
     */
    @Test
    fun `全零采样判为静音`() {
        assertTrue(PcmUtils.isSilent(FloatArray(16000)))
        assertTrue(PcmUtils.isSilent(FloatArray(0)))
    }

    @Test
    fun `正常说话音量不算静音`() {
        // RMS ≈ 0.07，接近正常对白
        val speech = FloatArray(16000) { i -> if (i % 2 == 0) 0.1f else -0.1f }
        assertTrue("RMS 应在人声区间", PcmUtils.rms(speech) > 0.05f)
        assertFalse(PcmUtils.isSilent(speech))
    }

    @Test
    fun `极小声但非零的输入不被误杀`() {
        // RMS ≈ 0.02：小声说话 / 气声，必须保留，否则整段对白会消失
        val quiet = FloatArray(16000) { i -> if (i % 2 == 0) 0.028f else -0.028f }
        assertFalse(PcmUtils.isSilent(quiet))
    }

    // ===== 模型清单 =====

    @Test
    fun `模型清单没有重复路径且体积合理`() {
        LocalModels.ALL.forEach { spec ->
            val paths = spec.files.map { it.path }
            assertEquals("${spec.id} 有重复文件", paths.size, paths.toSet().size)
            assertTrue("${spec.id} 体积异常", spec.totalBytes > 1024L * 1024L)
            // 下载地址必须指向镜像站，且带上仓库路径
            assertTrue(spec.url("x").startsWith("https://hf-mirror.com/"))
            assertTrue(spec.url("x").contains(spec.repo))
        }
    }

    @Test
    fun `模型体积文案`() {
        assertEquals("131 MB", formatModelSize(137_707_136L))
        assertEquals("78 MB", formatModelSize(81_904_027L))
    }

    /**
     * 识别引擎里写死的文件名必须真的在下载清单里。
     *
     * 这条看着啰嗦，但它是「点了生成字幕只报一句失败」的唯一防线：
     * 引擎按 `model.int8.onnx` / `tokens.txt` 去拼路径，清单里改了名
     * 或换了量化版本，编译期一切正常、运行期只表现为加载失败。
     */
    @Test
    fun `字幕模型清单包含引擎写死的文件名`() {
        val paths = LocalModels.SUBTITLE_ASR.files.map { it.path }.toSet()
        assertTrue("缺少 model.int8.onnx", "model.int8.onnx" in paths)
        assertTrue("缺少 tokens.txt", "tokens.txt" in paths)
    }

    // ===== 分块识别的时间轴 =====

    /**
     * 逐块识别再拼接，时间轴必须首尾相接、单调递增。
     *
     * 这是 AI 字幕最容易出错也最难在真机上发现的地方：每块各算各的时间轴，
     * 一旦 offset 算错，后半集的字幕会整体偏移，而人眼看着只是「字幕有点不准」。
     */
    @Test
    fun `逐块识别拼接后时间轴连续`() {
        val chunkMs = 20_000L
        val cues = ArrayList<SubtitleCue>()
        // 模拟 3 块，每块识别出一句
        for (i in 0 until 3) {
            val start = i * chunkMs
            cues.addAll(SubtitleBuilder.build("第${i + 1}块的内容。", start, start + chunkMs))
        }
        assertEquals(3, cues.size)
        assertEquals(0L, cues[0].startMs)
        assertEquals(3 * chunkMs, cues[cues.size - 1].endMs)
        for (i in 1 until cues.size) {
            assertTrue("相邻条目不该重叠或留空洞", cues[i - 1].endMs <= cues[i].startMs)
        }
    }
}
