package com.novelreader.ui.screens.novel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 正文搜索范围。
 *
 * 为什么不用 Boolean：调用点和 ViewModel 里「true 到底是本章还是全书」极易读反，
 * 枚举让 `setScope(CURRENT_CHAPTER)` 自解释，也便于单测里穷举。
 */
enum class ContentSearchScope {
    /** 只搜当前章（对齐旧面板的「本章」） */
    CURRENT_CHAPTER,

    /** 搜传入的全部章节（对齐旧面板的「全书」） */
    WHOLE_BOOK,
}

/**
 * 独立页接收的单章输入：标题 + 已加载好的正文。
 *
 * 为什么由调用方传入正文而不是页内自己去读：阅读页的正文是按章懒加载的，
 * NovelReaderViewModel 只暴露了「当前章正文」(content) 与「目录」(chapters)，
 * 全书各章正文并不在一个 StateFlow 里。让页面依赖一个纯数据入参，
 * 既不碰阅读页内部状态，又能让搜索逻辑在无 Android 环境里单测。
 *
 * @param listIndex 该章在目录列表中的下标，供调用方跳章（旧 BookSearchResult.listIndex）
 * @param chapterIndex 章节自身的 index 字段，供按 index 定位的 API 使用
 * @param title 章节标题，展示在结果列表
 * @param content 已经过 TextCleaner 净化的正文（净化由调用方保证，与旧面板一致）
 */
data class ContentSearchChapter(
    val listIndex: Int,
    val chapterIndex: Int,
    val title: String,
    val content: String,
)

/**
 * 一条正文搜索命中结果。
 *
 * @param position 命中在该章正文中的起始下标（章内比例跳转用 position / chapterLength）
 * @param excerpt 带上下文的摘录文本
 * @param excerptMatchStart 关键词在 [excerpt] 中的起始下标（高亮用）
 * @param excerptMatchEnd 关键词在 [excerpt] 中的结束下标（高亮用）
 */
data class ContentSearchMatch(
    val chapter: ContentSearchChapter,
    val position: Int,
    val excerpt: String,
    val excerptMatchStart: Int,
    val excerptMatchEnd: Int,
    val chapterLength: Int,
)

/**
 * 正文搜索的纯逻辑：多章匹配、上下文片段、命中位置计算。
 *
 * 为什么抽成不依赖 Android / Compose 的 object：这三件事是旧面板里最容易出错的部分
 * （下标偏移、省略号导致高亮错位、重叠匹配重复计数），独立成纯函数后可被 JVM 单测直接覆盖，
 * 而不必起 Robolectric / Compose 测试。
 */
object ContentSearchEngine {

    /** 摘录结果：[text] 展示用，[matchStart]/[matchEnd] 是关键词在 text 内的高亮区间。 */
    data class Excerpt(
        val text: String,
        val matchStart: Int,
        val matchEnd: Int,
    )

    /**
     * 找出 [query] 在 [text] 中所有非重叠出现位置，与旧面板 recomputeMatches / searchWholeBook
     * 的 `indexOf + from = idx + query.length` 保持一致：重叠命中不重复计数。
     */
    fun findOccurrences(text: String, query: String): List<Int> {
        if (query.isEmpty() || text.isEmpty()) return emptyList()
        val out = ArrayList<Int>()
        var from = 0
        while (true) {
            val idx = text.indexOf(query, from)
            if (idx < 0) break
            out.add(idx)
            from = idx + query.length
            if (from >= text.length) break
        }
        return out
    }

    /**
     * 截取命中处前后上下文，供结果列表展示与高亮。
     *
     * 为什么用「相对下标 + 前缀长度」而不是在拼接后的字符串上再 indexOf：
     * 正文里关键词可能出现多次，再 indexOf 会命中错位置；命中位置在截取时就是已知的，
     * 直接算偏移最稳。换行统一替换为空格且不改变长度，所以正文内偏移在 body 里仍然成立。
     */
    fun buildExcerpt(
        text: String,
        position: Int,
        keywordLength: Int,
        before: Int = 20,
        after: Int = 30,
    ): Excerpt {
        val start = (position - before).coerceAtLeast(0)
        val end = (position + keywordLength + after).coerceAtMost(text.length)
        val body = text.substring(start, end).replace("\n", " ")
        // 前后省略号是额外拼接的，高亮下标要把前缀长度算进去。
        val prefix = if (start > 0) "…" else ""
        val suffix = if (end < text.length) "…" else ""
        val relative = position - start
        return Excerpt(
            text = prefix + body + suffix,
            matchStart = prefix.length + relative,
            matchEnd = prefix.length + relative + keywordLength,
        )
    }

    /**
     * 按范围搜索所有章节。
     *
     * @param currentChapterIndex 当前章在 [chapters] 中的下标（0-based），仅 CURRENT_CHAPTER 用
     * @param maxResults 结果上限，防止大书把列表撑爆（旧面板全书搜索同样封顶 500）
     */
    fun search(
        chapters: List<ContentSearchChapter>,
        query: String,
        scope: ContentSearchScope,
        currentChapterIndex: Int,
        maxResults: Int = 500,
    ): List<ContentSearchMatch> {
        val keyword = query.trim()
        if (keyword.isEmpty()) return emptyList()

        // 本章范围只在传入列表里取当前下标对应的一章；越界时返回空，不偷偷退化成全书。
        val targets: List<ContentSearchChapter> = when (scope) {
            ContentSearchScope.CURRENT_CHAPTER ->
                chapters.getOrNull(currentChapterIndex)?.let { listOf(it) } ?: emptyList()
            ContentSearchScope.WHOLE_BOOK -> chapters
        }

        val out = ArrayList<ContentSearchMatch>()
        for (chapter in targets) {
            val text = chapter.content
            for (position in findOccurrences(text, keyword)) {
                if (out.size >= maxResults) return out
                val excerpt = buildExcerpt(text, position, keyword.length)
                out.add(
                    ContentSearchMatch(
                        chapter = chapter,
                        position = position,
                        excerpt = excerpt.text,
                        excerptMatchStart = excerpt.matchStart,
                        excerptMatchEnd = excerpt.matchEnd,
                        chapterLength = text.length,
                    )
                )
            }
        }
        return out
    }
}

/**
 * 正文搜索独立页的状态容器：把 [ContentSearchEngine] 的纯结果包装成 UI 可收集的 StateFlow。
 *
 * 为什么仍保留 ViewModel：输入会连续变化、搜索范围覆盖上千章，需要一个存活于重组之外的
 * 状态持有者，并在离开页面时取消在后台算了一半的搜索任务。
 */
class ContentSearchViewModel(
    private val chapters: List<ContentSearchChapter>,
    initialQuery: String = "",
    initialScope: ContentSearchScope = ContentSearchScope.WHOLE_BOOK,
    private val currentChapterIndex: Int = 0,
) : ViewModel() {

    private val _query = MutableStateFlow(initialQuery)
    val query: StateFlow<String> = _query.asStateFlow()

    private val _scope = MutableStateFlow(initialScope)
    val scope: StateFlow<ContentSearchScope> = _scope.asStateFlow()

    private val _results = MutableStateFlow<List<ContentSearchMatch>>(emptyList())
    val results: StateFlow<List<ContentSearchMatch>> = _results.asStateFlow()

    /** 当前高亮的命中项下标，-1 = 无 */
    private val _currentIndex = MutableStateFlow(-1)
    val currentIndex: StateFlow<Int> = _currentIndex.asStateFlow()

    private val _isSearching = MutableStateFlow(false)
    val isSearching: StateFlow<Boolean> = _isSearching.asStateFlow()

    private var searchJob: Job? = null

    init {
        // 从别处（如阅读页选中文本）带关键词进来时，进页即搜，省一次点击。
        if (_query.value.isNotBlank()) search()
    }

    fun onQueryChange(value: String) {
        _query.value = value
        search()
    }

    fun setScope(value: ContentSearchScope) {
        if (_scope.value == value) return
        _scope.value = value
        search()
    }

    /** 输入即搜：输入连续变化时取消上一次，避免旧结果覆盖新结果。 */
    fun search() {
        searchJob?.cancel()
        val keyword = _query.value.trim()
        if (keyword.isEmpty()) {
            _results.value = emptyList()
            _currentIndex.value = -1
            _isSearching.value = false
            return
        }
        _isSearching.value = true
        searchJob = viewModelScope.launch {
            // 为什么切 Default：全书遍历上千章文本是纯 CPU 活，放主线程会卡住输入与滚动。
            val found = withContext(Dispatchers.Default) {
                ContentSearchEngine.search(chapters, keyword, _scope.value, currentChapterIndex)
            }
            _results.value = found
            _currentIndex.value = if (found.isEmpty()) -1 else 0
            _isSearching.value = false
        }
    }

    fun selectResult(index: Int) {
        val size = _results.value.size
        if (size == 0) return
        _currentIndex.value = index.coerceIn(0, size - 1)
    }

    /** 下一个命中（循环），对齐旧面板的「下一个」。 */
    fun nextResult() {
        val size = _results.value.size
        if (size == 0) return
        _currentIndex.value = (_currentIndex.value + 1) % size
    }

    /** 上一个命中（循环），对齐旧面板的「上一个」。 */
    fun prevResult() {
        val size = _results.value.size
        if (size == 0) return
        _currentIndex.value = if (_currentIndex.value <= 0) size - 1 else _currentIndex.value - 1
    }

    override fun onCleared() {
        searchJob?.cancel()
        super.onCleared()
    }
}
