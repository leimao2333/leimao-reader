package com.novelreader.domain.model

/**
 * 搜索进度状态。
 *
 * 用于展示跨源搜索的实时进度：已搜源数 / 总源数、当前源名、已得结果数。
 * 结果在搜索过程中增量累加，边搜边显示。
 */
data class SearchState(
    /** 已累加的搜索结果 */
    val results: List<MediaItem> = emptyList(),
    /** 已完成的源数量 */
    val searchedCount: Int = 0,
    /** 待搜索的源总数 */
    val totalCount: Int = 0,
    /** 当前正在搜索的源名称 */
    val currentSource: String = "",
    /** 是否正在搜索 */
    val isSearching: Boolean = false,
    /** 搜索是否已发起过（用于区分"初始空态"与"无结果"） */
    val hasSearched: Boolean = false,
    /** 错误信息（整体失败时） */
    val error: String? = null,
    /** 是否启用模糊搜索（false 时不过滤不排序，保持源返回原序） */
    val fuzzyEnabled: Boolean = true,
) {
    /** 进度百分比（0~1），总数为 0 时返回 0 */
    val progress: Float
        get() = if (totalCount > 0) searchedCount.toFloat() / totalCount else 0f
}
