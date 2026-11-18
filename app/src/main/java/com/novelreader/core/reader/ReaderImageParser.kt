package com.novelreader.core.reader

/**
 * 正文图片段落识别（A10 正文插图）。
 *
 * 只做**识别与抽取**，不碰解码 / 加载（那是 UI 层的事），因此本文件是纯 Kotlin，
 * 不依赖 Android / Coil，可直接 JUnit 单测。
 *
 * ## 识别规则
 *
 * 按「行」识别：一行去掉首尾空白后，若**整行**是下面两种形态之一，就认定为图片段落：
 *
 * 1. Markdown 图片语法 `![alt](path)`，`path` 可以是网络地址或本地路径；
 * 2. 以 `http(s)://…png|jpg|jpeg|webp` 结尾的独立成行 URL（允许带 `?query` / `#fragment`）。
 *
 * 为什么不识别「嵌在正文中间」的图片语法：小说正文里的插图基本都是**独立成段**的，
 * 整行匹配既简单又不会把普通文字里的 URL 误当成图片；一旦支持行内匹配，`strip` 的
 * 语义（删掉图片段）就变成「删掉半行」，既破坏排版又容易误删正文。
 */
object ReaderImageParser {

    /** 图片段落的来源形态。 */
    enum class Kind {
        /** `![alt](path)` Markdown 语法。 */
        MARKDOWN,

        /** 独立成行的 `http(s)://…图片扩展名` 裸链接。 */
        BARE_URL,
    }

    /**
     * 一个被识别出来的图片段落。
     *
     * @param alt 备用文案（Markdown 才有；裸链接为空串）。加载失败 / 加载中时作为占位文案。
     * @param source 图片地址：网络地址或本地路径。
     * @param kind 来源形态。
     * @param raw 去掉首尾空白后的原始整行文本（便于日志与调试）。
     */
    data class ImageParagraph(
        val alt: String,
        val source: String,
        val kind: Kind,
        val raw: String,
    ) {
        /** 是否网络地址（决定走 Coil 还是本地 BitmapFactory 加载）。 */
        val isNetwork: Boolean
            get() = source.startsWith("http://", ignoreCase = true) ||
                source.startsWith("https://", ignoreCase = true)
    }

    /** Markdown 图片语法：整行 `![alt](path)`。 */
    private val MARKDOWN = Regex("^!\\[([^\\]]*)]\\(([^)]+)\\)$")

    /** 独立成行的图片 URL：`http(s)://…png|jpg|jpeg|webp`，允许带查询串 / 锚点。 */
    private val BARE_URL = Regex(
        "^https?://[^\\s]+\\.(png|jpg|jpeg|webp)([?#]\\S*)?$",
        RegexOption.IGNORE_CASE,
    )

    /**
     * 抽取正文里的所有图片段落，保持出现顺序。
     *
     * @return 没有图片（或空文本）时返回空列表。
     */
    fun parse(text: String): List<ImageParagraph> {
        if (text.isBlank()) return emptyList()
        return text.lineSequence()
            .map { it.trim() }
            .mapNotNull { matchLine(it) }
            .toList()
    }

    /**
     * 把图片段落从正文里剔除，返回剩下的纯文本。
     *
     * 用途：分页 / 朗读时不再把 `![alt](url)` 或裸 URL 当正文显示、当正文念出来。
     * 逐行剔除、按 `\n` 重新拼接；与 [parse] 用同一套识别规则，保证「识别到几张」
     * 和「剔除掉几行」严格一致。
     */
    fun strip(text: String): String {
        if (text.isBlank()) return text
        return text.lineSequence()
            .filter { matchLine(it.trim()) == null }
            .joinToString("\n")
    }

    /** 单行识别：命中返回段落，未命中返回 null。 */
    private fun matchLine(trimmed: String): ImageParagraph? {
        if (trimmed.isEmpty()) return null
        MARKDOWN.matchEntire(trimmed)?.let { m ->
            val alt = m.groupValues[1].trim()
            val source = m.groupValues[2].trim()
            if (source.isEmpty()) return null
            return ImageParagraph(alt = alt, source = source, kind = Kind.MARKDOWN, raw = trimmed)
        }
        BARE_URL.matchEntire(trimmed)?.let {
            return ImageParagraph(alt = "", source = trimmed, kind = Kind.BARE_URL, raw = trimmed)
        }
        return null
    }
}
