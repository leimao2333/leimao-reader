package com.novelreader.ui.screens.history

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.novelreader.domain.model.RecentReading
import com.novelreader.domain.model.SourceType
import com.novelreader.domain.repository.ProgressRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 阅读历史 ViewModel（v1.4.3.0）。
 *
 * 独立「历史」页：按类型（全部 / 小说 / 漫画 / 视频）筛选，展示所有阅读/播放记录，
 * 支持多选批量清除与「清空全部」。
 */
class HistoryViewModel(
    private val progressRepository: ProgressRepository,
) : ViewModel() {

    /** 当前类型筛选；null = 全部 */
    private val _selectedType = MutableStateFlow<SourceType?>(null)
    val selectedType: StateFlow<SourceType?> = _selectedType.asStateFlow()

    /** 历史记录（按更新时间倒序） */
    private val _items = MutableStateFlow<List<RecentReading>>(emptyList())
    val items: StateFlow<List<RecentReading>> = _items.asStateFlow()

    /** 是否正在加载 */
    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    /** 多选模式下的选中集合（key = sourceId|itemId） */
    private val _selectedKeys = MutableStateFlow<Set<String>>(emptySet())
    val selectedKeys: StateFlow<Set<String>> = _selectedKeys.asStateFlow()

    /** 是否处于多选模式 */
    private val _selecting = MutableStateFlow(false)
    val selecting: StateFlow<Boolean> = _selecting.asStateFlow()

    init {
        refresh()
    }

    /** 切换类型筛选并重新加载 */
    fun selectType(type: SourceType?) {
        if (_selectedType.value == type) return
        _selectedType.value = type
        refresh()
    }

    /** 重新加载历史（回前台 / 筛选切换时调用） */
    fun refresh() {
        viewModelScope.launch {
            _loading.value = true
            val list = runCatching {
                progressRepository.recentReadings(_selectedType.value)
            }.getOrDefault(emptyList())
            _items.value = list
            // 清掉已不存在的选中项
            val validKeys = list.map { keyOf(it) }.toSet()
            _selectedKeys.value = _selectedKeys.value.intersect(validKeys)
            _loading.value = false
        }
    }

    /** 进入 / 退出多选模式 */
    fun toggleSelecting() {
        _selecting.value = !_selecting.value
        if (!_selecting.value) _selectedKeys.value = emptySet()
    }

    fun exitSelecting() {
        _selecting.value = false
        _selectedKeys.value = emptySet()
    }

    /** 切换单条选中状态 */
    fun toggleSelect(key: String) {
        val cur = _selectedKeys.value
        _selectedKeys.value = if (key in cur) cur - key else cur + key
    }

    /** 全选 / 取消全选 */
    fun toggleSelectAll() {
        val all = _items.value.map { keyOf(it) }.toSet()
        _selectedKeys.value = if (_selectedKeys.value == all) emptySet() else all
    }

    /** 删除单条记录 */
    fun remove(item: RecentReading) {
        viewModelScope.launch {
            runCatching { progressRepository.removeProgress(item.sourceId, item.itemId) }
            refresh()
        }
    }

    /** 批量删除选中记录 */
    fun removeSelected() {
        val keys = _selectedKeys.value
        if (keys.isEmpty()) return
        viewModelScope.launch {
            _items.value.forEach { r ->
                if (keyOf(r) in keys) {
                    runCatching { progressRepository.removeProgress(r.sourceId, r.itemId) }
                }
            }
            exitSelecting()
            refresh()
        }
    }

    /** 清空全部历史 */
    fun clearAll() {
        viewModelScope.launch {
            runCatching { progressRepository.clearAllProgress() }
            exitSelecting()
            refresh()
        }
    }

    companion object {
        fun keyOf(r: RecentReading): String = "${r.sourceId}|${r.itemId}"
    }
}
