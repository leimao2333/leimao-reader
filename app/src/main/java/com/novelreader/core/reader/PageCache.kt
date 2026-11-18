package com.novelreader.core.reader

/**
 * 章节分页结果 LRU 缓存（A16 长章排版绘制性能）。
 *
 * ## 为什么需要它
 *
 * 长章排版要用 TextMeasurer 逐段测量，一节几千字就是几十次二分、几十到几百毫秒。
 * 读者来回翻章 / 切回已读章节时重复排同一份内容是纯浪费。这里把「排版结果」按
 * **完整输入指纹**缓存起来，命中即零成本返回。
 *
 * ## key 的构成（失效策略的核心）
 *
 * key 包含书 id、章号、正文 hash、章名 hash，以及所有影响断行的参数（宽 / 高 / 字号 /
 * 行高 / 段距 / 标题留白 / 字体 / 加粗 / 字距 / 首行缩进 / 是否显示章名）。任一参数变化
 * 都会产生不同 key —— 绝不会「改了字号却返回旧断行」，从而避免末行被裁。
 * 正文 / 章名用 `hashCode()` 而不是整串文本：内容变了要失效，但又不让整章文本长期挂在
 * key 上占内存（hash 碰撞概率可忽略，且最坏只是多排一次）。章名参与是因为首屏高度要扣
 * 标题块，不同长度的标题会改变首页断行。
 *
 * ## 线程安全
 *
 * 预分页在 Dispatchers.Default 上并发跑，读写必须同步。所有公开操作都加锁；
 * 容量上限用 [LinkedHashMap] 的 accessOrder 实现 LRU，get 也算一次「访问」。
 */
class PageCache(private val maxSize: Int = DEFAULT_MAX_SIZE) {

    /** 一次排版的完整输入指纹。字段全参与 equals/hashCode，参数变化必产生不同 key。 */
    data class Key(
        val bookId: String = "",
        val chapterIndex: Int = 0,
        val contentHash: Int = 0,
        val titleHash: Int = 0,
        val widthPx: Int = 0,
        val heightPx: Float = 0f,
        val fontSize: Float = 0f,
        val lineHeightMultiplier: Float = 0f,
        val paragraphSpacingPx: Float = 0f,
        val titleSpacerPx: Float = 0f,
        val fontFamily: String = "",
        val bold: Boolean = false,
        val letterSpacing: Float = 0f,
        val indent: Boolean = false,
        val hideChapterTitle: Boolean = false,
    )

    private val lock = Any()

    /** accessOrder = true：get 会把条目移到队尾，队首即最久未用。 */
    private val map: LinkedHashMap<Key, List<String>> =
        LinkedHashMap(if (maxSize > 0) maxSize else 1, 1f, true)

    /** 命中返回缓存分页结果，并把该条目标记为最近使用；未命中（或已禁用）返回 null。 */
    fun get(key: Key): List<String>? = synchronized(lock) {
        if (maxSize <= 0) null else map[key]
    }

    /** 写入结果；超出容量淘汰最久未用条目。容量为 0 时完全禁用缓存。 */
    fun put(key: Key, value: List<String>) = synchronized(lock) {
        if (maxSize <= 0) return@synchronized
        map[key] = value
        // accessOrder 模式下 put 不会淘汰，需手动移除溢出的最旧条目
        while (map.size > maxSize) {
            val oldest = map.keys.firstOrNull() ?: break
            map.remove(oldest)
        }
    }

    /** 是否存在可用缓存（同样刷新最近使用时间）。 */
    fun contains(key: Key): Boolean = synchronized(lock) {
        maxSize > 0 && map.containsKey(key)
    }

    /** 清空全部缓存。排版参数整体变化时调用（也可不调用——新参数会产生新 key）。 */
    fun clear() = synchronized(lock) { map.clear() }

    /** 当前条目数。 */
    fun size(): Int = synchronized(lock) { map.size }

    /** 当前容量上限（0 = 禁用）。 */
    fun maxSize(): Int = maxSize

    companion object {
        /** 默认缓存 12 章——覆盖常见的预加载窗口（前后各几章）且内存占用可控。 */
        const val DEFAULT_MAX_SIZE: Int = 12

        /** 便捷构造 key，避免调用方漏掉某个排版参数导致缓存串味。 */
        fun keyOf(
            bookId: String = "",
            chapterIndex: Int = 0,
            contentHash: Int = 0,
            titleHash: Int = 0,
            widthPx: Int = 0,
            heightPx: Float = 0f,
            fontSize: Float = 0f,
            lineHeightMultiplier: Float = 0f,
            paragraphSpacingPx: Float = 0f,
            titleSpacerPx: Float = 0f,
            fontFamily: String = "",
            bold: Boolean = false,
            letterSpacing: Float = 0f,
            indent: Boolean = false,
            hideChapterTitle: Boolean = false,
        ): Key = Key(
            bookId = bookId,
            chapterIndex = chapterIndex,
            contentHash = contentHash,
            titleHash = titleHash,
            widthPx = widthPx,
            heightPx = heightPx,
            fontSize = fontSize,
            lineHeightMultiplier = lineHeightMultiplier,
            paragraphSpacingPx = paragraphSpacingPx,
            titleSpacerPx = titleSpacerPx,
            fontFamily = fontFamily,
            bold = bold,
            letterSpacing = letterSpacing,
            indent = indent,
            hideChapterTitle = hideChapterTitle,
        )
    }
}
