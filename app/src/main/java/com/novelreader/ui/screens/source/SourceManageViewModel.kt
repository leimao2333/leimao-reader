package com.novelreader.ui.screens.source

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.novelreader.domain.model.SourceInfo
import com.novelreader.domain.model.SourceType
import com.novelreader.domain.repository.SourceRepository
import com.novelreader.data.repository.SourceRepositoryImpl
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

/**
 * 源管理 ViewModel。
 *
 * 负责源的列表展示、导入、删除、启停。
 */
class SourceManageViewModel(
    private val sourceRepository: SourceRepository,
    private val sourceRepositoryImpl: SourceRepositoryImpl? = null,
) : ViewModel() {

    /** 源列表 */
    private val _sources = MutableStateFlow<List<SourceInfo>>(emptyList())
    val sources: StateFlow<List<SourceInfo>> = _sources.asStateFlow()

    /** 加载状态 */
    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    /** 导入结果消息（成功/失败） */
    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    /** 下载进度（0~1，-1 表示未知） */
    private val _downloadProgress = MutableStateFlow<Float?>(null)
    val downloadProgress: StateFlow<Float?> = _downloadProgress.asStateFlow()

    /** 是否正在下载（用于区分导入中的下载阶段） */
    private val _downloading = MutableStateFlow(false)
    val downloading: StateFlow<Boolean> = _downloading.asStateFlow()

    init {
        refresh()
    }

    /** 刷新源列表 */
    fun refresh() {
        viewModelScope.launch {
            _sources.value = sourceRepository.getAllSources()
        }
    }

    /** 响应式观察源列表（若仓库实现支持 Flow） */
    fun observe() {
        sourceRepositoryImpl?.let { impl ->
            viewModelScope.launch {
                impl.observeSources().collect { list ->
                    _sources.value = list
                }
            }
        } ?: refresh()
    }

    /** 导入源（原始内容 + 文件名） */
    fun importSource(rawContent: String, fileName: String? = null) {
        viewModelScope.launch {
            _loading.value = true
            _message.value = null
            try {
                val imported = sourceRepository.importSource(rawContent, fileName)
                _message.value = if (imported.size == 1) {
                    "导入成功：${imported[0].name}（${imported[0].type}）"
                } else {
                    "导入成功：${imported.size} 个源"
                }
                refresh()
            } catch (e: Exception) {
                _message.value = "导入失败：${e.message}"
            } finally {
                _loading.value = false
            }
        }
    }

    /**
     * 从网址导入书源（txt 内容为 URL 时）。
     * 联网下载书源列表 JSON 后批量导入，带实时下载进度。
     */
    fun importFromUrl(url: String) {
        viewModelScope.launch {
            _loading.value = true
            _downloading.value = true
            _downloadProgress.value = 0f
            _message.value = "正在下载书源…"
            try {
                // 网络请求必须离开主线程；用带进度的流式下载
                val content = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    val tmpFile = java.io.File.createTempFile("booksource", ".json")
                    try {
                        com.novelreader.core.network.HttpClient.downloadFile(url, tmpFile) { downloaded, total ->
                            val p = if (total > 0) {
                                (downloaded.toFloat() / total).coerceIn(0f, 1f)
                            } else {
                                // 未知总大小：用已下载字节数估算（最多显示到 99%）
                                (downloaded.toFloat() / (downloaded + 1024 * 1024)).coerceIn(0f, 0.99f)
                            }
                            _downloadProgress.value = p
                        }
                        tmpFile.readText()
                    } finally {
                        tmpFile.delete()
                    }
                }
                val imported = sourceRepository.importSource(content, "legado_remote")
                _message.value = "从网址导入成功：${imported.size} 个源"
                refresh()
            } catch (e: Exception) {
                val reason = e.message ?: e.javaClass.simpleName
                _message.value = if (reason.contains("timeout", true) || reason.contains("超时")) {
                    "下载失败：网络超时（书源文件较大），请检查网络后重试"
                } else {
                    "导入失败：$reason"
                }
            } finally {
                _loading.value = false
                _downloading.value = false
                _downloadProgress.value = null
            }
        }
    }

    /** 删除源 */
    fun deleteSource(source: SourceInfo) {
        viewModelScope.launch {
            sourceRepository.deleteSource(source.id)
            refresh()
        }
    }

    /** 启用/禁用源 */
    fun toggleEnabled(source: SourceInfo) {
        viewModelScope.launch {
            sourceRepository.setSourceEnabled(source.id, !source.enabled)
            refresh()
        }
    }

    /** 批量设置启用状态 */
    fun setEnabledBatch(sources: List<SourceInfo>, enabled: Boolean) {
        viewModelScope.launch {
            sources.forEach { sourceRepository.setSourceEnabled(it.id, enabled) }
            refresh()
        }
    }

    /** 一键启用 / 禁用全部（可按当前筛选结果） */
    fun setAllEnabled(list: List<SourceInfo>, enabled: Boolean) {
        setEnabledBatch(list, enabled)
    }

    /** 批量删除源 */
    fun deleteBatch(sources: List<SourceInfo>) {
        viewModelScope.launch {
            sources.forEach { sourceRepository.deleteSource(it.id) }
            _message.value = "已删除 ${sources.size} 个源"
            refresh()
        }
    }

    // ===== 源有效性检测（自动筛选无用源） =====

    /** 正在检测中的源 id 集合（UI 显示转圈） */
    private val _testing = MutableStateFlow<Set<String>>(emptySet())
    val testing: StateFlow<Set<String>> = _testing.asStateFlow()

    /** 检测结果：sourceId -> 是否可用 */
    private val _testResults = MutableStateFlow<Map<String, Boolean>>(emptyMap())
    val testResults: StateFlow<Map<String, Boolean>> = _testResults.asStateFlow()

    /**
     * 批量检测源有效性。
     *
     * 逐个调用 [SourceAdapter.test]（内部以「测试」关键词搜索，返回结果非空即视为可用）。
     * 检测结果写入 [testResults]，UI 据此标记失效源。
     *
     * @param sources 待检测的源；默认检测当前全部源
     */
    fun testSources(sources: List<SourceInfo> = _sources.value) {
        if (sources.isEmpty()) return
        viewModelScope.launch {
            // 必须在 IO 线程执行：test() 含网络请求与 Rhino JS，
            // 在主线程跑会卡死 UI 导致 ANR（实体机已复现）。
            val results = withContext(Dispatchers.IO) {
                val map = _testResults.value.toMutableMap()
                // 限流并发：全串行太慢（280 源 × 20s 上限），全并发耗尽内存；
                // 用信号量限制最多 8 个源同时检测。
                val semaphore = kotlinx.coroutines.sync.Semaphore(8)
                sources.map { source ->
                    launch {
                        semaphore.withPermit {
                            _testing.value = _testing.value + source.id
                            // 用 withTimeoutOrNull 包住整个 test()，防止单个慢源卡死整轮检测；
                            // 用 runCatching 捕获 Throwable（Rhino 等可能抛 Error，非 Exception），
                            // 任何异常都视为「不可用」，绝不因此崩溃。
                            val ok = kotlinx.coroutines.withTimeoutOrNull(20_000L) {
                                runCatching {
                                    sourceRepository.getAdapter(source.id)?.test() ?: true
                                }.getOrDefault(false)
                            } ?: false
                            synchronized(map) { map[source.id] = ok }
                            _testing.value = _testing.value - source.id
                        }
                    }
                }.joinAll()
                map
            }
            _testResults.value = results.toMap()
            val failed = results.count { !it.value }
            _message.value = if (failed == 0) "检测完成：全部源可用"
            else "检测完成：${results.size} 个源中 $failed 个疑似失效"
        }
    }

    /** 一键禁用所有已检出失效的源（自动筛选无用源） */
    fun disableFailedSources() {
        val failed = _testResults.value.filterValues { !it }.keys
        if (failed.isEmpty()) {
            _message.value = "尚未检测到失效源，请先「检测可用性」"
            return
        }
        viewModelScope.launch {
            failed.forEach { sourceRepository.setSourceEnabled(it, false) }
            _message.value = "已禁用 ${failed.size} 个失效源"
            refresh()
        }
    }

    /** 清除检测结果 */
    fun clearTestResults() {
        _testResults.value = emptyMap()
    }

    /** 清除提示消息 */
    fun consumeMessage() {
        _message.value = null
    }

    // ===== 源规则编辑 =====

    /** 正在编辑的源（非空即弹出编辑面板） */
    private val _editingSource = MutableStateFlow<SourceInfo?>(null)
    val editingSource: StateFlow<SourceInfo?> = _editingSource.asStateFlow()

    /** 编辑框里的原始规则文本 */
    private val _editingContent = MutableStateFlow("")
    val editingContent: StateFlow<String> = _editingContent.asStateFlow()

    /** 编辑框文本是否正在加载 */
    private val _editingLoading = MutableStateFlow(false)
    val editingLoading: StateFlow<Boolean> = _editingLoading.asStateFlow()

    /** 编辑保存状态 */
    private val _editingSaving = MutableStateFlow(false)
    val editingSaving: StateFlow<Boolean> = _editingSaving.asStateFlow()

    /** 打开编辑面板：加载源的原始规则 */
    fun openEditSource(source: SourceInfo) {
        viewModelScope.launch {
            _editingSource.value = source
            _editingLoading.value = true
            _editingContent.value = sourceRepository.getRawContent(source.id) ?: ""
            _editingLoading.value = false
        }
    }

    /** 关闭编辑面板 */
    fun closeEditSource() {
        _editingSource.value = null
        _editingContent.value = ""
        _editingLoading.value = false
    }

    /** 编辑框内容变化 */
    fun setEditingContent(value: String) {
        _editingContent.value = value
    }

    /** 保存编辑：重新解析并注册，成功后刷新列表 */
    fun saveEditingSource() {
        val source = _editingSource.value ?: return
        val content = _editingContent.value
        if (content.isBlank()) {
            _message.value = "规则内容不能为空"
            return
        }
        viewModelScope.launch {
            _editingSaving.value = true
            try {
                sourceRepository.updateSource(source.id, content)
                _message.value = "已保存「${source.name}」的规则修改"
                closeEditSource()
                refresh()
            } catch (e: Exception) {
                _message.value = "保存失败：${e.message ?: e.javaClass.simpleName}"
            } finally {
                _editingSaving.value = false
            }
        }
    }

    /** 按类型分组计数 */
    fun countByType(list: List<SourceInfo>, type: SourceType): Int =
        list.count { it.type == type }

    /** 源智能分类：返回 分类名 -> 源列表 */
    fun groupByCategory(list: List<SourceInfo>): Map<String, List<SourceInfo>> =
        com.novelreader.source.category.SourceCategory.groupByCategory(list)

    /** 所有分类名（按源数量降序） */
    fun sortedCategories(list: List<SourceInfo>): List<String> =
        groupByCategory(list).entries
            .sortedByDescending { it.value.size }
            .map { it.key }

    /** 按指定方式排序 */
    fun sortSources(list: List<SourceInfo>, sortBy: SourceSortOrder): List<SourceInfo> =
        when (sortBy) {
            SourceSortOrder.DEFAULT -> list
            SourceSortOrder.NAME -> list.sortedBy { it.name }
            SourceSortOrder.TYPE -> list.sortedWith(compareBy({ it.type.name }, { it.name }))
            SourceSortOrder.CATEGORY -> list.sortedWith(compareBy({ it.category }, { it.name }))
            SourceSortOrder.ENABLED -> list.sortedByDescending { it.enabled }
        }
}

/** 源排序方式 */
enum class SourceSortOrder(val label: String) {
    DEFAULT("默认"),
    NAME("名称"),
    TYPE("类型"),
    CATEGORY("分类"),
    ENABLED("启用状态"),
}
