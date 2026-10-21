package com.novelreader.core.asr

/**
 * 字幕条目（v1.8.7.3）。
 *
 * 只保留播放器真正需要的三个字段：起止时间与文本。样式、位置一律交给渲染端，
 * 因为同一份数据既要喂给 CC 字幕轨，也要能导出成 SRT 文件。
 */
data class SubtitleCue(
    val startMs: Long,
    val endMs: Long,
    val text: String,
)

/**
 * 把识别出来的整段文本切成字幕条目（v1.8.7.3）。
 *
 * 为什么要切：Paraformer 对一段 30 秒音频只给一句长文本，
 * 整段显示在屏幕上既看不完也对不上口型。这里按标点切句，
 * 再**按字数比例**把这段时间分给每个句子 —— 没有强制对齐（forced alignment）
 * 的前提下，这是最稳的近似：中文语速在一句话内基本均匀。
 *
 * 抽成纯函数是为了能单测：时间轴算错的表现是「字幕越来越偏」，
 * 真机上看半分钟才发现，单测里一行断言就能锁住。
 */
object SubtitleBuilder {

    /** 单条字幕最多多少字（超过就再切，屏幕上放不下） */
    const val MAX_CHARS = 24

    /**
     * @param text 识别文本（可能含标点、空格）
     * @param startMs 这段音频的起点（整段音轨里的绝对时间）
     * @param endMs 终点
     */
    fun build(text: String, startMs: Long, endMs: Long): List<SubtitleCue> {
        val pieces = split(text)
        if (pieces.isEmpty()) return emptyList()
        val total = (endMs - startMs).coerceAtLeast(0L)
        if (total == 0L) return emptyList()

        val totalChars = pieces.sumOf { it.length }.coerceAtLeast(1)
        var cursor = startMs
        val cues = ArrayList<SubtitleCue>(pieces.size)
        for ((index, piece) in pieces.withIndex()) {
            // 最后一条直接对齐到 endMs，避免逐条四舍五入累积误差
            val span = if (index == pieces.lastIndex) {
                endMs - cursor
            } else {
                (total * piece.length.toLong() / totalChars).coerceAtLeast(1L)
            }
            val end = (cursor + span).coerceAtMost(endMs)
            if (end > cursor) cues.add(SubtitleCue(cursor, end, piece))
            cursor = end
        }
        return cues
    }

    /**
     * 按句末标点切句，过长的再按逗号补切，最后按 [MAX_CHARS] 硬切。
     *
     * 与朗读切句（`splitSentences`）口径不同：字幕要更短、更贴屏幕，
     * 所以阈值独立设置，不复用。
     */
    fun split(text: String): List<String> {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return emptyList()
        val sentences = ArrayList<String>()
        val buffer = StringBuilder()

        fun flush() {
            val piece = buffer.toString().trim()
            buffer.setLength(0)
            if (piece.isNotEmpty() && piece.any { it.isLetterOrDigit() }) sentences.add(piece)
        }

        for (ch in trimmed) {
            when {
                ch == '\n' || ch == '\r' -> flush()
                ch in SENTENCE_ENDS -> {
                    buffer.append(ch)
                    flush()
                }
                ch in SOFT_BREAKS && buffer.length >= MAX_CHARS -> {
                    buffer.append(ch)
                    flush()
                }
                else -> {
                    buffer.append(ch)
                    if (buffer.length >= MAX_CHARS * 2) flush()
                }
            }
        }
        flush()

        // 硬切：单句仍超长的按 MAX_CHARS 均分，保证每条都能一屏放下
        return sentences.flatMap { sentence ->
            if (sentence.length <= MAX_CHARS) {
                listOf(sentence)
            } else {
                sentence.chunked(MAX_CHARS).filter { it.isNotBlank() }
            }
        }
    }

    /** 导出成 SRT（用户可以把生成的字幕存下来，或喂给别的播放器） */
    fun toSrt(cues: List<SubtitleCue>): String = buildString {
        cues.forEachIndexed { index, cue ->
            append(index + 1).append('\n')
            append(formatTime(cue.startMs)).append(" --> ").append(formatTime(cue.endMs)).append('\n')
            append(cue.text).append("\n\n")
        }
    }

    /** SRT 时间格式：`00:01:23,456` */
    fun formatTime(ms: Long): String {
        val total = ms.coerceAtLeast(0L)
        val hours = total / 3_600_000
        val minutes = (total % 3_600_000) / 60_000
        val seconds = (total % 60_000) / 1000
        val millis = total % 1000
        return "%02d:%02d:%02d,%03d".format(hours, minutes, seconds, millis)
    }

    private val SENTENCE_ENDS = charArrayOf('。', '！', '？', '；', '…', '!', '?', ';')
    private val SOFT_BREAKS = charArrayOf('，', '、', ',')
}
