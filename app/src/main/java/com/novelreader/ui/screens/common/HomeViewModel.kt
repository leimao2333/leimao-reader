package com.novelreader.ui.screens.common

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.novelreader.domain.model.RecentReading
import com.novelreader.domain.model.SourceType
import com.novelreader.domain.repository.BookRepository
import com.novelreader.domain.repository.ContentRepository
import com.novelreader.domain.repository.ProgressRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 首页 ViewModel。
 *
 * 提供「最近阅读」列表（按更新时间倒序，**按当前 tab 类型过滤**），
 * 以及「继续阅读」跳转能力（定位到上次进度章节）。
 */
class HomeViewModel(
    private val progressRepository: ProgressRepository,
    private val contentRepository: ContentRepository,
) : ViewModel() {

    private val _recent = MutableStateFlow<List<RecentReading>>(emptyList())
    val recent: StateFlow<List<RecentReading>> = _recent.asStateFlow()

    /** 当前展示的类型（小说/漫画/视频），null = 全部 */
    private var currentType: SourceType? = null

    init {
        refresh()
    }

    /** 刷新最近阅读列表（按当前 tab 类型过滤） */
    fun refresh() {
        viewModelScope.launch {
            _recent.value = runCatching { progressRepository.recentReadings(currentType) }
                .getOrDefault(emptyList())
        }
    }

    /**
     * 切换内容类型（tab 切换时调用），最近阅读随之切换为该类型。
     */
    fun setType(type: SourceType?) {
        if (currentType == type) return
        currentType = type
        refresh()
    }

    /** 移除某条阅读记录（最近阅读长按管理，#15） */
    fun removeRecent(item: RecentReading) {
        viewModelScope.launch {
            runCatching { progressRepository.removeProgress(item.sourceId, item.itemId) }
            refresh()
        }
    }

    /**
     * 继续阅读某个最近阅读条目。
     *
     * 加载章节列表 + 定位进度章节后回调。
     */
    fun continueReading(
        item: RecentReading,
        onReady: (chapters: List<com.novelreader.domain.model.ChapterItem>, target: com.novelreader.domain.model.ChapterItem?) -> Unit,
    ) {
        viewModelScope.launch {
            val mediaItem = com.novelreader.domain.model.MediaItem(
                id = item.itemId,
                title = item.title,
                cover = item.cover,
                type = item.type,
                detailUrl = item.detailUrl,
                sourceId = item.sourceId,
            )
            val progress = progressRepository.getProgress(item.sourceId, item.itemId)
            val chapters = runCatching { contentRepository.chapterList(mediaItem) }
                .getOrDefault(emptyList())

            if (chapters.isEmpty()) {
                onReady(emptyList(), null)
                return@launch
            }

            val target = progress?.let { p ->
                chapters.firstOrNull { it.url == p.chapterUrl }
                    ?: chapters.firstOrNull { it.index == p.chapterIndex }
            } ?: chapters.getOrNull(item.chapterIndex) ?: chapters.first()

            onReady(chapters, target)
        }
    }
}
