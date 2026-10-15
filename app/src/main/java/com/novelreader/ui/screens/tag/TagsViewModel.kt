package com.novelreader.ui.screens.tag

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.novelreader.data.cache.TagPlan
import com.novelreader.data.repository.TagRepository
import com.novelreader.domain.model.SourceType
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 首页搜索框旁「标签」下拉菜单的 ViewModel（v1.5.10.0）。
 *
 * 首页三个 Tab 各有一套标签（小说 / 漫画 / 视频），切 Tab 时按类型取：
 * 内存 → 磁盘缓存（毫秒级）→ 现场收集（首次使用要逐源探测，视频源较慢，
 * 期间下拉菜单显示「正在收集标签」，收集完成自动出现）。
 */
class TagsViewModel(
    private val tagRepository: TagRepository,
) : ViewModel() {

    /** 当前类型的标签（已按收集顺序排好） */
    private val _tags = MutableStateFlow<List<String>>(emptyList())
    val tags: StateFlow<List<String>> = _tags.asStateFlow()

    /** 是否正在收集 */
    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    private val cache = HashMap<SourceType, List<String>>()
    private var currentType: SourceType? = null

    /** 确保指定类型的标签已加载（切 Tab / 打开菜单时调用，幂等） */
    fun ensure(type: SourceType) {
        if (currentType == type && _tags.value.isNotEmpty()) return
        currentType = type
        cache[type]?.let {
            _tags.value = it
            return
        }
        if (_loading.value) return
        viewModelScope.launch {
            _loading.value = true
            val plan = runCatching { tagRepository.tags(type) }.getOrDefault(TagPlan.EMPTY)
            cache[type] = plan.tags
            if (currentType == type) _tags.value = plan.tags
            _loading.value = false
        }
    }

    /** 强制重新收集（源刚导入 / 菜单里没刷出想要的标签时用） */
    fun refresh(type: SourceType) {
        if (_loading.value) return
        viewModelScope.launch {
            _loading.value = true
            val plan = runCatching { tagRepository.refresh(type) }.getOrDefault(TagPlan.EMPTY)
            cache[type] = plan.tags
            if (currentType == type) _tags.value = plan.tags
            _loading.value = false
        }
    }
}
