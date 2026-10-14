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
    /** Venera 运行时：Venera 漫画源的登录 / 退出登录走这里（v1.3.0.0） */
    private val veneraEngine: com.novelreader.source.venera.VeneraEngine? = null,
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

    // ===== Venera 源登录（v1.3.0.0）=====

    /** Venera 源登录态：sourceId -> 是否已登录（读本地标记，不启动 WebView） */
    private val _loginStates = MutableStateFlow<Map<String, Boolean>>(emptyMap())
    val loginStates: StateFlow<Map<String, Boolean>> = _loginStates.asStateFlow()

    /** 是否正在登录（sourceId 集合） */
    private val _loggingIn = MutableStateFlow<Set<String>>(emptySet())
    val loggingIn: StateFlow<Set<String>> = _loggingIn.asStateFlow()

    private fun refreshLoginStates() {
        if (veneraEngine == null) return
        _loginStates.value = _sources.value
            .filter { it.format == com.novelreader.domain.model.SourceFormat.VENERA }
            .associate { it.id to veneraEngine.isLoginMarked(it.id) }
            .filterValues { it }
    }

    // 注意：init 必须放在所有 StateFlow 属性声明之后——
    // Kotlin 按声明顺序初始化属性，init 提前会拿到 null 的 _loginStates（v1.3.0.0 实测崩溃）
    init {
        refresh()
    }

    /** 刷新源列表 */
    fun refresh() {
        viewModelScope.launch {
            _sources.value = sourceRepository.getAllSources()
            refreshLoginStates()
        }
    }

    /** 响应式观察源列表（若仓库实现支持 Flow） */
    fun observe() {
        sourceRepositoryImpl?.let { impl ->
            viewModelScope.launch {
                impl.observeSources().collect { list ->
                    _sources.value = list
                    refreshLoginStates()
                }
            }
        } ?: refresh()
    }

    /**
     * 账号密码登录 Venera 源。
     *
     * 调用源自己实现的 account.login（在隐藏 WebView 的 V8 里执行），
     * 会话 Cookie 由引擎自动持久化，重启后仍有效。
     */
    fun login(source: SourceInfo, account: String, password: String) {
        if (account.isBlank() || password.isBlank()) {
            _message.value = "账号和密码不能为空"
            return
        }
        viewModelScope.launch {
            _loggingIn.value = _loggingIn.value + source.id
            try {
                val adapter = sourceRepository.getAdapter(source.id)
                    as? com.novelreader.source.venera.VeneraSourceAdapter
                if (adapter == null) {
                    _message.value = "登录失败：源不可用"
                    return@launch
                }
                adapter.login(account.trim(), password)
                _message.value = "「${source.name}」登录成功"
                refreshLoginStates()
            } catch (e: Exception) {
                _message.value = "登录失败：${e.message ?: e.javaClass.simpleName}"
            } finally {
                _loggingIn.value = _loggingIn.value - source.id
            }
        }
    }

    /** 退出登录：调用源的 account.logout 并清 Cookie / 本地标记 */
    fun logout(source: SourceInfo) {
        viewModelScope.launch {
            try {
                (sourceRepository.getAdapter(source.id)
                    as? com.novelreader.source.venera.VeneraSourceAdapter)?.logout()
                _message.value = "「${source.name}」已退出登录"
                refreshLoginStates()
            } catch (e: Exception) {
                _message.value = "退出失败：${e.message ?: e.javaClass.simpleName}"
            }
        }
    }

    /** 导入源（原始内容 + 文件名） */
    fun importSource(rawContent: String, fileName: String? = null) {
        viewModelScope.launch {
            _loading.value = true
            _message.value = null
            try {
                // v1.4.7.0「可选添加」：先解析出候选源列表；多个源时弹勾选对话框，单源直接导入
                val candidates = sourceRepository.previewImport(rawContent, fileName)
                val skipped = sourceRepositoryImpl?.lastImportSkipped() ?: 0
                if (candidates.size > 1) {
                    _importCandidates.value = candidates
                    _message.value = "解析出 ${candidates.size} 个源，请勾选要添加的" +
                        (if (skipped > 0) "（另有 $skipped 个解析失败已跳过）" else "")
                } else {
                    val imported = sourceRepository.importSource(rawContent, fileName)
                    _message.value = buildString {
                        append("导入成功：${imported[0].name}（${imported[0].type}）")
                        if (skipped > 0) append("；另有 $skipped 个条目解析失败已跳过")
                    }
                    refresh()
                }
            } catch (e: Exception) {
                _message.value = "导入失败：${e.message}"
            } finally {
                _loading.value = false
            }
        }
    }

    // ===== 可选添加（v1.4.7.0） =====

    /** 待勾选的候选源列表（非空即弹出「可选添加」对话框） */
    private val _importCandidates = MutableStateFlow<List<SourceInfo>>(emptyList())
    val importCandidates: StateFlow<List<SourceInfo>> = _importCandidates.asStateFlow()

    /** 用户在对话框中的勾选状态（sourceId 集合） */
    private val _selectedImportIds = MutableStateFlow<Set<String>>(emptySet())
    val selectedImportIds: StateFlow<Set<String>> = _selectedImportIds.asStateFlow()

    /** 是否正在注册勾选的源 */
    private val _importingSelected = MutableStateFlow(false)
    val importingSelected: StateFlow<Boolean> = _importingSelected.asStateFlow()

    /** 打开可选添加对话框时初始化勾选（默认全选） */
    fun onImportCandidatesShown() {
        _selectedImportIds.value = _importCandidates.value.map { it.id }.toSet()
    }

    /** 切换单个候选源的勾选状态 */
    fun toggleImportCandidate(id: String) {
        _selectedImportIds.value = if (id in _selectedImportIds.value) {
            _selectedImportIds.value - id
        } else {
            _selectedImportIds.value + id
        }
    }

    /** 全选 / 取消全选候选源 */
    fun toggleSelectAllImportCandidates() {
        val all = _importCandidates.value.map { it.id }.toSet()
        _selectedImportIds.value = if (_selectedImportIds.value == all) emptySet() else all
    }

    /** 确认注册勾选的源 */
    fun confirmImportSelected() {
        val ids = _selectedImportIds.value
        if (ids.isEmpty()) {
            _importCandidates.value = emptyList()
            return
        }
        viewModelScope.launch {
            _importingSelected.value = true
            try {
                val n = sourceRepository.importSelected(ids)
                _message.value = "已添加 $n 个源"
                _importCandidates.value = emptyList()
                refresh()
            } catch (e: Exception) {
                _message.value = "添加失败：${e.message}"
            } finally {
                _importingSelected.value = false
            }
        }
    }

    /** 取消可选添加 */
    fun cancelImportCandidates() {
        _importCandidates.value = emptyList()
        _selectedImportIds.value = emptySet()
    }

    /**
     * 网址导入的文件名（参与源 id 生成）。
     *
     * v1.3.0.0 修复：旧实现固定传 "legado_remote" → 不同网址导入的**第一个源 id 相同**，
     * 后一次导入会静默覆盖前一次的第一个源（「部分小说源不加载/消失」根因之一）。
     * 现按 URL 哈希生成稳定文件名：同一网址重复导入仍命中同一批 id（原地更新），
     * 不同网址互不冲突。
     */
    private fun remoteFileName(url: String): String =
        "legado_" + (url.trim().hashCode().toString().replace("-", "n"))

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
                // Venera 源索引（index.json）：内容只是清单，需要逐个下载 .js 再导入
                val veneraIndex = parseVeneraIndex(content)
                if (veneraIndex != null) {
                    importVeneraIndex(url, veneraIndex)
                    return@launch
                }

                // v1.4.7.0「可选添加」：先解析出候选；多个源时弹勾选对话框，单源直接导入
                val candidates = sourceRepository.previewImport(content, remoteFileName(url))
                if (candidates.size > 1) {
                    _importCandidates.value = candidates
                    _message.value = "解析出 ${candidates.size} 个源，请勾选要添加的"
                } else {
                    val imported = sourceRepository.importSource(content, remoteFileName(url))
                    _message.value = "从网址导入成功：${imported.size} 个源"
                    refresh()
                }
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

    /**
     * 解析 Venera 源索引（`[{name, fileName, key, version}]`）。
     *
     * 这是 Venera 源集合的 index.json 格式：它不是源本身，
     * 每个条目对应同目录下的一个 .js 源文件，需要逐个下载后再导入。
     */
    private fun parseVeneraIndex(content: String): com.google.gson.JsonArray? {
        val trimmed = content.trim()
        if (!trimmed.startsWith("[")) return null
        return runCatching {
            val arr = com.google.gson.JsonParser.parseString(trimmed).asJsonArray
            val first = arr.firstOrNull()?.takeIf { it.isJsonObject }?.asJsonObject ?: return null
            if (first.has("fileName") && first.has("key")) arr else null
        }.getOrNull()
    }

    /** 批量导入 Venera 源：逐个下载同目录下的 .js 并注册 */
    private suspend fun importVeneraIndex(indexUrl: String, arr: com.google.gson.JsonArray) {
        val dir = indexUrl.substringBeforeLast('/', "")
        var ok = 0
        val failures = mutableListOf<String>()
        // 本批次会注册的 id 集合（id 由 .js 文件名决定，同目录内文件名唯一）
        val batchIds = mutableSetOf<String>()

        arr.forEachIndexed { i, el ->
            val obj = el.takeIf { it.isJsonObject }?.asJsonObject ?: return@forEachIndexed
            val fileName = obj.get("fileName")?.asString ?: return@forEachIndexed
            val key = obj.get("key")?.asString ?: fileName.substringBeforeLast(".")
            val name = obj.get("name")?.asString ?: key
            // 注意：index.json 里存在 key 重复的条目（同一站点的普通版与多账号版），
            // 因此 id 必须取文件名而不是 key，否则后者会覆盖前者导致少导入。
            val id = com.novelreader.source.venera.VeneraSourceId.fromFileName(fileName)
                ?: return@forEachIndexed
            val sourceFileName = "$id.venera"
            batchIds += id

            _message.value = "正在导入 Venera 源 ${i + 1}/${arr.size()}：$name"
            _downloadProgress.value = i.toFloat() / arr.size().coerceAtLeast(1)

            runCatching {
                val code = withContext(Dispatchers.IO) {
                    com.novelreader.core.network.HttpClient.get("$dir/$fileName")
                }
                sourceRepository.importSource(code, sourceFileName)
            }.onSuccess {
                ok++
            }.onFailure {
                failures += "$name（${it.message}）"
            }
        }

        // 清理历史遗留 / 已从索引中移除的 Venera 源（本批次之外的），避免重复与僵尸条目
        runCatching {
            sourceRepository.getAllSources()
                .filter {
                    it.format == com.novelreader.domain.model.SourceFormat.VENERA && it.id !in batchIds
                }
                .forEach { sourceRepository.deleteSource(it.id) }
        }

        _message.value = buildString {
            append("Venera 源导入完成：成功 $ok/${arr.size()}")
            if (failures.isNotEmpty()) {
                append("；失败 ${failures.size} 个：")
                append(failures.take(3).joinToString("，"))
                if (failures.size > 3) append(" 等")
            }
        }
        refresh()
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

    /** 检测进度：已完成数 / 总数（v1.4.7.0 进度条） */
    private val _testProgress = MutableStateFlow<Pair<Int, Int>?>(null)
    val testProgress: StateFlow<Pair<Int, Int>?> = _testProgress.asStateFlow()

    /** 是否正在检测（整体） */
    private val _testRunning = MutableStateFlow(false)
    val testRunning: StateFlow<Boolean> = _testRunning.asStateFlow()

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
        if (_testRunning.value) return
        viewModelScope.launch {
            _testRunning.value = true
            val total = sources.size
            _testProgress.value = 0 to total
            // 必须在 IO 线程执行：test() 含网络请求与 Rhino JS，
            // 在主线程跑会卡死 UI 导致 ANR（实体机已复现）。
            val results = withContext(Dispatchers.IO) {
                val map = _testResults.value.toMutableMap()
                // 限流并发：全串行太慢（280 源 × 20s 上限），全并发耗尽内存；
                // 用信号量限制最多 8 个源同时检测。
                val semaphore = kotlinx.coroutines.sync.Semaphore(8)
                val done = java.util.concurrent.atomic.AtomicInteger(0)
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
                            val d = done.incrementAndGet()
                            _testProgress.value = d to total
                        }
                    }
                }.joinAll()
                map
            }
            _testResults.value = results.toMap()
            _testProgress.value = null
            _testRunning.value = false
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

    // ===== 书源 URL 一键更新（v1.3.0.0 对标 legado） =====

    /** 是否正在联网更新书源 */
    private val _updating = MutableStateFlow(false)
    val updating: StateFlow<Boolean> = _updating.asStateFlow()

    /** 更新进度提示 */
    private val _updateProgress = MutableStateFlow<String?>(null)
    val updateProgress: StateFlow<String?> = _updateProgress.asStateFlow()

    /**
     * 从网络一键更新书源。
     *
     * 对每个启用的书源：读取其 JSON 内的 sourceUrl（即源的原始网络地址），
     * 重新拉取该地址内容，按 sourceUrl 匹配出对应条目后更新本地源。
     * 4 路并发 + 实时进度；无 sourceUrl / 拉取失败 / 内容未变的源分别计数。
     */
    fun updateFromNetwork(targets: List<SourceInfo>? = null) {
        if (_updating.value) return
        val list = (targets ?: _sources.value).filter { it.enabled }
        if (list.isEmpty()) {
            _message.value = "没有可更新的书源（仅启用的源会更新）"
            return
        }
        viewModelScope.launch {
            _updating.value = true
            _updateProgress.value = "准备更新…"
            var updated = 0
            var unchanged = 0
            var failed = 0
            var skipped = 0
            val done = java.util.concurrent.atomic.AtomicInteger(0)
            val total = list.size
            val semaphore = kotlinx.coroutines.sync.Semaphore(4)
            kotlinx.coroutines.coroutineScope {
                list.forEach { src ->
                    launch {
                        semaphore.withPermit {
                            try {
                                val raw = sourceRepository.getRawContent(src.id)
                                if (raw.isNullOrBlank()) { skipped++; return@withPermit }
                                val sourceUrl = extractSourceUrl(raw)
                                if (!sourceUrl.startsWith("http")) { skipped++; return@withPermit }
                                val content = withContext(Dispatchers.IO) {
                                    runCatching { com.novelreader.core.network.HttpClient.get(sourceUrl) }.getOrNull()
                                }
                                if (content.isNullOrBlank()) { failed++; return@withPermit }
                                val newRaw = matchSourceJson(content, sourceUrl)
                                if (newRaw == null) { failed++; return@withPermit }
                                if (newRaw == raw.trim()) { unchanged++; return@withPermit }
                                sourceRepository.updateSource(src.id, newRaw)
                                updated++
                            } catch (e: Exception) {
                                failed++
                            } finally {
                                val d = done.incrementAndGet()
                                if (d % 5 == 0 || d == total) {
                                    _updateProgress.value = "正在更新 $d/$total"
                                }
                            }
                        }
                    }
                }
            }
            refresh()
            _updateProgress.value = null
            _updating.value = false
            _message.value = "书源更新完成：更新 $updated，无变化 $unchanged，失败 $failed" +
                (if (skipped > 0) "，跳过 $skipped（无来源 URL）" else "")
        }
    }

    /** 从源 JSON（数组或单对象）中提取 sourceUrl 字段 */
    private fun extractSourceUrl(raw: String): String = runCatching {
        val trimmed = raw.trim()
        val obj = if (trimmed.startsWith("[")) {
            org.json.JSONArray(trimmed).optJSONObject(0)
        } else {
            org.json.JSONObject(trimmed)
        }
        obj?.optString("sourceUrl")?.trim().orEmpty()
    }.getOrDefault("")

    /** 在网络返回的书源 JSON（数组或单对象）中按 sourceUrl 匹配，返回该条目的 JSON 字符串 */
    private fun matchSourceJson(content: String, sourceUrl: String): String? = runCatching {
        val trimmed = content.trim()
        if (trimmed.startsWith("[")) {
            val arr = org.json.JSONArray(trimmed)
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                if (obj.optString("sourceUrl").trim() == sourceUrl) return obj.toString()
            }
            null
        } else {
            val obj = org.json.JSONObject(trimmed)
            if (obj.optString("sourceUrl").trim() == sourceUrl) obj.toString() else null
        }
    }.getOrNull()
}

/** 源排序方式 */
enum class SourceSortOrder(val label: String) {
    DEFAULT("默认"),
    NAME("名称"),
    TYPE("类型"),
    CATEGORY("分类"),
    ENABLED("启用状态"),
}
