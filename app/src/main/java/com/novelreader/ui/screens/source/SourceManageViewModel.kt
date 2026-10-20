package com.novelreader.ui.screens.source

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.novelreader.domain.model.SourceInfo
import com.novelreader.domain.model.SourceType
import com.novelreader.domain.repository.SourceRepository
import com.novelreader.data.repository.SourceRepositoryImpl
import com.novelreader.source.category.isAdult
import com.novelreader.source.loader.SourcePack
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

    // ===== 大文件导入（v1.8.3.0 待办 #21）=====

    /**
     * 已从文件读进来的源内容。
     *
     * 存在 ViewModel 而不是 Compose 输入框里：几百 KB 的 JSON 塞进 `OutlinedTextField`
     * 会让主线程每次都去测量整段文字，几 MB 的书源包直接卡死。超过 [EDITOR_CHAR_LIMIT]
     * 就只在页面上显示「已选择 xxx.json（12.3 MB）」，导入时仍用这份内容。
     */
    private var pickedContent: String? = null

    /** 已选文件信息（供导入页展示文件卡片） */
    data class PickedFile(
        val name: String,
        val sizeBytes: Long,
        val chars: Int,
        /** true = 内容已填进输入框可以编辑；false = 太大，只展示文件卡片 */
        val inEditor: Boolean,
    )

    private val _pickedFile = MutableStateFlow<PickedFile?>(null)
    val pickedFile: StateFlow<PickedFile?> = _pickedFile.asStateFlow()

    /** 从文件读到的正文（仅在内容不太大时非空，供输入框回填） */
    private val _pickedText = MutableStateFlow<String?>(null)
    val pickedText: StateFlow<String?> = _pickedText.asStateFlow()

    /** 导入阶段（非空即显示进度弹窗）：读取文件 / 解析源 */
    data class ImportStage(
        val label: String,
        val detail: String = "",
        /** null = 不确定进度（解析格式未知时只能转圈） */
        val fraction: Float? = null,
        val cancelable: Boolean = true,
    )

    private val _importStage = MutableStateFlow<ImportStage?>(null)
    val importStage: StateFlow<ImportStage?> = _importStage.asStateFlow()

    /** 取消标志：解析回调里检查，抛异常打断（协程 cancel 打不断 CPU 密集的解析循环） */
    @Volatile
    private var importCancelled = false

    private var importJob: kotlinx.coroutines.Job? = null

    private companion object {
        /** 超过这么多字符就不往输入框里塞（十万字级别的 TextField 已经在掉帧） */
        const val EDITOR_CHAR_LIMIT = 200_000

        /** 读取缓冲区 */
        const val READ_BUFFER = 64 * 1024
    }

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

    // ===== 重复源合并（v1.6.6.0）=====

    /** 可合并的重复源数量（0 = 无重复，按钮置灰） */
    private val _duplicateCount = MutableStateFlow(0)
    val duplicateCount: StateFlow<Int> = _duplicateCount.asStateFlow()

    /** 重新统计可合并重复源（进页面 / 合并后 / 导入后调用） */
    fun refreshDuplicateCount() {
        viewModelScope.launch {
            _duplicateCount.value = runCatching { sourceRepository.countDuplicateSources() }
                .getOrDefault(0)
        }
    }

    /**
     * 合并重复源：同类型 + 同名 + 同站（baseUrl 归一化后相同）只留一条。
     *
     * 保留启用中的、规则更完整的那条；Venera / 内置源（无 baseUrl）不参与，
     * 所以 5 个都叫「笔趣阁」但站点不同的源不会被误合。
     */
    fun mergeDuplicateSources() {
        viewModelScope.launch {
            val merged = runCatching { sourceRepository.dedupeDuplicateSources() }
                .getOrDefault(0)
            _message.value = if (merged > 0) {
                "已合并 $merged 个重复源"
            } else {
                "没有可合并的重复源"
            }
            refresh()
            refreshDuplicateCount()
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

    /**
     * 从 SAF 读取源文件（v1.8.3.0 待办 #21）。
     *
     * 原实现是在 Composable 的回调里直接 `readText()` —— 主线程同步读几十 MB 的
     * 书源包必然 ANR。现在整段搬到 IO：按块读取、回报已读字节，回来后按体积决定
     * 是回填输入框还是只留文件卡片。
     */
    fun loadSourceFile(
        context: android.content.Context,
        uri: android.net.Uri,
        displayName: String?,
    ) {
        if (_loading.value) return
        importCancelled = false
        importJob = viewModelScope.launch {
            _loading.value = true
            _message.value = null
            _importStage.value = ImportStage("读取文件", "正在读取…")
            try {
                val (name, bytes) = withContext(Dispatchers.IO) {
                    val stream = context.contentResolver.openInputStream(uri)
                        ?: error("无法读取所选文件")
                    stream.use { input ->
                        val buffer = ByteArray(READ_BUFFER)
                        val out = java.io.ByteArrayOutputStream()
                        var total = 0L
                        while (true) {
                            if (importCancelled) throw kotlinx.coroutines.CancellationException("已取消")
                            val read = input.read(buffer)
                            if (read <= 0) break
                            out.write(buffer, 0, read)
                            total += read
                            _importStage.value = ImportStage(
                                label = "读取文件",
                                detail = "已读取 ${formatSize(total)}",
                            )
                        }
                        (displayName ?: "source.json") to out.toByteArray()
                    }
                }
                val text = String(bytes, Charsets.UTF_8)
                val inEditor = text.length <= EDITOR_CHAR_LIMIT
                pickedContent = text
                _pickedFile.value = PickedFile(name, bytes.size.toLong(), text.length, inEditor)
                _pickedText.value = if (inEditor) text else null
                _message.value = if (inEditor) {
                    "已读取 $name（${formatSize(bytes.size.toLong())}）"
                } else {
                    "已选择 $name（${formatSize(bytes.size.toLong())}，${text.length} 字符）——" +
                        "文件过大，不再填进输入框，点「导入」直接解析"
                }
            } catch (c: kotlinx.coroutines.CancellationException) {
                _message.value = "已取消读取"
            } catch (e: Exception) {
                _message.value = "读取文件失败：${e.message ?: e.javaClass.simpleName}"
            } finally {
                _loading.value = false
                _importStage.value = null
            }
        }
    }

    /** 清掉已选文件（用户改用手动粘贴时） */
    fun clearPickedFile() {
        pickedContent = null
        _pickedFile.value = null
        _pickedText.value = null
        _message.value = null
    }

    /** 导入已选文件（大文件走这条，正文不经过输入框） */
    fun importPickedFile(fileName: String? = null) {
        val content = pickedContent
        if (content.isNullOrBlank()) {
            _message.value = "请先选择文件或粘贴源内容"
            return
        }
        doImport(content, fileName ?: _pickedFile.value?.name)
    }

    /** 取消导入 / 读取（仅在解析与读取阶段可取消） */
    fun cancelImport() {
        if (_importStage.value?.cancelable != true) return
        importCancelled = true
        importJob?.cancel()
        _importStage.value = null
        _loading.value = false
        _message.value = "已取消导入"
    }

    /** 导入源（原始内容 + 文件名） */
    fun importSource(rawContent: String, fileName: String? = null) {
        doImport(rawContent, fileName)
    }

    /**
     * 导入主流程（v1.8.3.0 待办 #21 重写）。
     *
     * 两个阶段分别上报：解析（按条目数，legado 列表可精确到第几个）与结束。
     * 解析跑在仓库的 IO 调度器里，进度回调在 IO 线程触发，这里只写 StateFlow。
     */
    private fun doImport(rawContent: String, fileName: String?) {
        if (_loading.value) return
        importCancelled = false
        importJob = viewModelScope.launch {
            _loading.value = true
            _message.value = null
            try {
                // 聚合源包清单（leimaoPack）：本身不是源，必须联网逐个下载子包，
                // 文件方式拿不到清单 URL 就无法解析相对路径（v1.5.6.0）
                if (SourcePack.parse(rawContent) != null) {
                    _message.value = "这是聚合源包清单（all.json），请改用「粘贴网址」方式导入" +
                        "（清单里的源包要按清单地址逐个下载）"
                    return@launch
                }
                _importStage.value = ImportStage("解析源", "正在识别源格式…")
                // v1.4.7.0「可选添加」：先解析出候选源列表；v1.5.3.0 起单源也弹框确认
                val candidates = sourceRepository.previewImport(rawContent, fileName) { done, total ->
                    if (importCancelled) throw kotlinx.coroutines.CancellationException("已取消")
                    _importStage.value = ImportStage(
                        label = "解析源",
                        detail = if (total > 0) "$done / $total 个条目" else "已解析 $done 个条目",
                        fraction = if (total > 0) done.toFloat() / total else null,
                    )
                }
                val skipped = sourceRepositoryImpl?.lastImportSkipped() ?: 0
                if (candidates.isNotEmpty()) {
                    _importCandidates.value = candidates
                    _message.value = if (candidates.size > 1) {
                        "解析出 ${candidates.size} 个源，请勾选要添加的" +
                            (if (skipped > 0) "（另有 $skipped 个解析失败已跳过）" else "")
                    } else {
                        "解析出 1 个源，请确认添加"
                    }
                } else {
                    _message.value = "未解析到有效源"
                }
            } catch (c: kotlinx.coroutines.CancellationException) {
                _message.value = "已取消导入"
            } catch (e: Exception) {
                _message.value = "导入失败：${e.message}"
            } finally {
                _loading.value = false
                _importStage.value = null
            }
        }
    }

    /** 字节 → 人类可读（读取进度用） */
    private fun formatSize(value: Long): String = when {
        value >= 1024L * 1024 -> String.format(java.util.Locale.US, "%.1f MB", value / 1024f / 1024f)
        value >= 1024 -> String.format(java.util.Locale.US, "%.0f KB", value / 1024f)
        else -> "$value B"
    }

    // ===== 可选添加（v1.4.7.0） =====

    /** 待勾选的候选源列表（非空即弹出「可选添加」对话框） */
    private val _importCandidates = MutableStateFlow<List<SourceInfo>>(emptyList())
    val importCandidates: StateFlow<List<SourceInfo>> = _importCandidates.asStateFlow()

    /**
     * 待下载 / 待注册的 Venera 索引批次（v1.5.3.0）。
     *
     * `index.json` 只是清单（不含 .js 源码），需要逐个下载 .js 才能注册。
     * 这里缓存「勾选对话框弹出前」的：(索引 URL, 全部条目)。
     * 空表示当前候选里没有 Venera 索引（普通预览缓存模式）；
     * 聚合包导入时可能同时含普通候选与 Venera 索引（两者在一次确认中一起注册）。
     */
    private var veneraPending: List<Pair<String, List<com.novelreader.source.venera.VeneraIndex.Entry>>> =
        emptyList()

    /** 聚合包里已下载的子包（相对路径 + 绝对地址 + 内容） */
    private data class PackFile(val rel: String, val url: String, val content: String)

    /** Venera 索引批量注册结果 */
    private data class VeneraImportResult(val ok: Int, val failures: List<String>)

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
            veneraPending = emptyList()
            return
        }
        val venera = veneraPending
        viewModelScope.launch {
            _importingSelected.value = true
            try {
                var added = 0
                val failures = mutableListOf<String>()

                // 已解析的候选（普通预览缓存）：按勾选 id 取回注册
                runCatching { sourceRepository.importSelected(ids) }
                    .onSuccess { added += it }
                    .onFailure { failures += it.message ?: "添加失败" }

                // Venera 索引：先按勾选下载 .js 再注册（与上面合并成一次「已添加 N 个源」）
                if (venera.isNotEmpty()) {
                    val result = downloadVeneraSelected(venera, ids)
                    added += result.ok
                    failures += result.failures
                }

                // v1.7.2.0：导入时就地查重，把「覆盖 / 跳过」如实告诉用户 ——
                // 否则同一份源导两次，第二次界面显示「已添加 N 个」，源列表却没变长，
                // 看起来像导入坏了。
                val (updated, dupes) = sourceRepositoryImpl?.lastImportDedupeStats() ?: (0 to 0)
                _message.value = buildString {
                    append("已添加 $added 个源")
                    if (updated > 0) append("；$updated 个已存在的源被更新为更完整版本")
                    if (dupes > 0) append("；跳过 $dupes 个重复源（库里已有）")
                    if (failures.isNotEmpty()) {
                        append("；失败 ${failures.size} 个：")
                        append(failures.take(3).joinToString("，"))
                        if (failures.size > 3) append(" 等")
                    }
                }
                _importCandidates.value = emptyList()
                refresh()
                // v1.6.6.0：新导入的源可能就是重复条目（同一份源在两个包里
                // 序号不同 → id 不同），导入后立刻重算可合并数
                refreshDuplicateCount()
            } finally {
                _importingSelected.value = false
                veneraPending = emptyList()
            }
        }
    }

    /** 取消可选添加 */
    fun cancelImportCandidates() {
        _importCandidates.value = emptyList()
        _selectedImportIds.value = emptySet()
        veneraPending = emptyList()
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
                // 聚合源包清单（leimaoPack）：一条链接导入全部源（v1.5.6.0）
                val manifest = SourcePack.parse(content)
                if (manifest != null) {
                    importPack(url, manifest)
                    return@launch
                }

                // Venera 源索引（index.json）：内容只是清单，需要逐个下载 .js 再导入
                val veneraEntries = com.novelreader.source.venera.VeneraIndex.parse(content)
                if (veneraEntries != null) {
                    importVeneraIndex(url, veneraEntries)
                    return@launch
                }

                // v1.4.7.0「可选添加」：先解析出候选；v1.5.3.0 起单源也弹框确认
                val candidates = sourceRepository.previewImport(content, remoteFileName(url))
                if (candidates.isNotEmpty()) {
                    _importCandidates.value = candidates
                    _message.value = if (candidates.size > 1) {
                        "解析出 ${candidates.size} 个源，请勾选要添加的"
                    } else {
                        "解析出 1 个源，请确认添加"
                    }
                } else {
                    _message.value = "未解析到有效源"
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
     * 聚合源包导入（v1.5.6.0）：**一条链接 → 全部源**。
     *
     * 清单（见 [SourcePack]）本身不是源，只是一串同目录下的源包文件名，所以这里：
     * 1. 逐个下载子包（带「第 i/n 个」进度），单个失败不中断（计入提示）
     * 2. 拆包 —— Venera 索引只是清单（不含 .js 源码）留到确认后再下载；
     *    其余文件（legado / MoonTV）交给 [SourceRepository.previewImportBatch] 一次解析
     * 3. 两类候选合并成**一份**勾选列表，用户确认一次全部入库
     *
     * @param packUrl 清单自身 URL（子包相对路径按它的目录解析）
     */
    private suspend fun importPack(packUrl: String, manifest: SourcePack.Manifest) {
        val total = manifest.files.size.coerceAtLeast(1)
        val files = mutableListOf<PackFile>()
        val failedPacks = mutableListOf<String>()

        manifest.files.forEachIndexed { i, rel ->
            _message.value = "正在下载源包 ${i + 1}/$total：$rel"
            _downloadProgress.value = i.toFloat() / total
            val fileUrl = SourcePack.resolveUrl(packUrl, rel)
            if (fileUrl == null) {
                failedPacks += "$rel（地址无法解析）"
                return@forEachIndexed
            }
            runCatching {
                withContext(Dispatchers.IO) {
                    com.novelreader.core.network.HttpClient.get(fileUrl, timeoutMs = PACK_FILE_TIMEOUT_MS)
                }
            }.onSuccess { files += PackFile(rel, fileUrl, it) }
                .onFailure { failedPacks += "$rel（${it.message ?: "下载失败"}）" }
        }
        _downloadProgress.value = 1f

        // 拆包：Venera 索引不含源码，其余文件直接解析
        val veneraBatches = mutableListOf<Pair<String, List<com.novelreader.source.venera.VeneraIndex.Entry>>>()
        val plainFiles = mutableListOf<com.novelreader.domain.model.SourceFileContent>()
        files.forEach { f ->
            val entries = com.novelreader.source.venera.VeneraIndex.parse(f.content)
            if (entries != null) {
                // 索引的相对目录 = 索引自身 URL 的目录（下载 .js 时按它拼接）
                if (entries.isNotEmpty()) veneraBatches += f.url to entries
            } else {
                plainFiles += com.novelreader.domain.model.SourceFileContent(
                    fileName = remoteFileName(f.url),
                    content = f.content,
                )
            }
        }

        val batch = sourceRepository.previewImportBatch(plainFiles)
        val veneraCandidates = veneraBatches.flatMap { (_, entries) ->
            entries.map { com.novelreader.source.venera.VeneraIndex.toSourceInfo(it) }
        }
        val candidates = batch.sources + veneraCandidates
        val problems = failedPacks + batch.failedFiles

        if (candidates.isEmpty()) {
            _message.value = buildString {
                append("聚合包未解析到有效源")
                if (problems.isNotEmpty()) {
                    append("：")
                    append(problems.take(3).joinToString("，"))
                }
            }
            return
        }

        veneraPending = veneraBatches
        _importCandidates.value = candidates
        _message.value = buildString {
            append("聚合包「${manifest.name.ifBlank { "未命名" }}」")
            append("共 ${candidates.size} 个源（${manifest.files.size} 个源包），请勾选要添加的")
            if (batch.skippedEntries > 0) {
                append("；另有 ${batch.skippedEntries} 个条目解析失败已跳过")
            }
            if (problems.isNotEmpty()) {
                append("；${problems.size} 个源包失败：")
                append(problems.take(3).joinToString("，"))
            }
        }
    }

    /**
     * 解析 Venera index.json → 弹「选择要添加的源」勾选框（v1.5.3.0）。
     *
     * index.json 只是清单（不含 .js 源码），先解析出全部候选条目让用户勾选，
     * 确认后再逐个下载 .js 注册（见 [downloadVeneraSelected]）。
     */
    private fun importVeneraIndex(
        indexUrl: String,
        entries: List<com.novelreader.source.venera.VeneraIndex.Entry>,
    ) {
        if (entries.isEmpty()) {
            _message.value = "未解析到有效的 Venera 源清单"
            return
        }
        // 候选 SourceInfo 的 id 由文件名推导，与下载注册时一致（见 VeneraIndex.toSourceInfo）
        val candidates = entries.map { com.novelreader.source.venera.VeneraIndex.toSourceInfo(it) }
        veneraPending = listOf(indexUrl to entries)
        _importCandidates.value = candidates
        _message.value = "解析出 ${candidates.size} 个 Venera 源，请勾选要添加的"
    }

    /**
     * 下载勾选的 Venera 源 .js 并注册（v1.5.3.0；v1.5.6.0 支持「多个索引批次」= 聚合包）。
     *
     * 只在用户于候选对话框勾选并确认后调用，只下载勾选的条目。
     * 不再自己设置提示消息 / 刷新列表 —— 由调用方与普通候选合并成一条结果消息。
     */
    private suspend fun downloadVeneraSelected(
        batches: List<Pair<String, List<com.novelreader.source.venera.VeneraIndex.Entry>>>,
        selectedIds: Set<String>,
    ): VeneraImportResult {
        val selected = batches.flatMap { (indexUrl, entries) ->
            val dir = indexUrl.substringBeforeLast('/', "")
            entries.filter { it.id != null && it.id in selectedIds }.map { dir to it }
        }
        if (selected.isEmpty()) return VeneraImportResult(0, emptyList())

        var ok = 0
        val failures = mutableListOf<String>()
        val batchIds = mutableSetOf<String>()

        selected.forEachIndexed { i, (dir, entry) ->
            val id = entry.id ?: return@forEachIndexed
            batchIds += id

            _message.value = "正在导入 Venera 源 ${i + 1}/${selected.size}：${entry.name}"
            _downloadProgress.value = i.toFloat() / selected.size

            runCatching {
                val code = withContext(Dispatchers.IO) {
                    com.novelreader.core.network.HttpClient.get("$dir/${entry.fileName}")
                }
                // 文件名用 "${id}.venera"，与启动恢复时构造的名字一致（见 VeneraSourceId）
                sourceRepository.importSource(code, "$id.venera")
            }.onSuccess {
                ok++
            }.onFailure {
                failures += "${entry.name}（${it.message}）"
            }
        }

        // 清理历史遗留 / 已从索引中移除的 Venera 源（本批次勾选之外的），避免重复与僵尸条目
        runCatching {
            sourceRepository.getAllSources()
                .filter {
                    it.format == com.novelreader.domain.model.SourceFormat.VENERA && it.id !in batchIds
                }
                .forEach { sourceRepository.deleteSource(it.id) }
        }

        return VeneraImportResult(ok, failures)
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
     * **搜得到但读不了目录**的源 id（v1.7.4.0，v1.7.6.5 起计入失效）。
     *
     * 典型是站点改过版 / 换了域名：`test()` 用「测试」关键词搜索还能返回结果
     * （于是判为「可用」），但 `ruleToc` 早就对不上新页面，点进去一本都读不了
     * （52PO小说：301 跳到 pokanwen.com 且页面重做，目录恒为 0 章；点外漫画：
     * 目录走 `admin-ajax.php`，被站点 WAF 一律 400）。
     *
     * 这类源**留着没用**（点进去没章节可读），所以 v1.7.6.5 起
     * [testResults] 里同步记为 `false`，`failed` 统计与「禁用失效 / 删除失效」
     * 一并覆盖；这里单独留一份只为 UI 能显示「为什么失效」。
     *
     * 只放**确认读不了**的：搜索压根没结果时无法判定（可能只是关键词不匹配），不放。
     */
    private val _unreadable = MutableStateFlow<Set<String>>(emptySet())
    val unreadable: StateFlow<Set<String>> = _unreadable.asStateFlow()

    /**
     * 批量检测源有效性。
     *
     * 逐个调用 [SourceAdapter.test]（内部以「测试」关键词搜索，返回结果非空即视为可用）。
     * 检测结果写入 [testResults]，UI 据此标记失效源。
     *
     * v1.7.4.0 追加一步：判定可用的源还会拿一本真实的书去要一次目录。
     * v1.7.6.5 起：取不到章节的源**直接记为失效**（`testResults[id] = false`），
     * 让 `failed` 统计与「禁用失效 / 删除失效」一并覆盖——能搜到书却一章都读不了
     * 的源留着没有意义。同时仍记进 [unreadable]，UI 用来交代失效原因。
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
            _unreadable.value = emptySet()
            // 必须在 IO 线程执行：test() 含网络请求与 Rhino JS，
            // 在主线程跑会卡死 UI 导致 ANR（实体机已复现）。
            val results = withContext(Dispatchers.IO) {
                val map = _testResults.value.toMutableMap()
                val unreadableSet = java.util.Collections.synchronizedSet(mutableSetOf<String>())
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
                            val adapter = sourceRepository.getAdapter(source.id)
                            val searchOk = kotlinx.coroutines.withTimeoutOrNull(20_000L) {
                                runCatching { adapter?.test() ?: true }.getOrDefault(false)
                            } ?: false
                            // 搜得到才值得再探一次目录（搜索就失败时无法判定，不能算「无目录」）
                            val readable: Boolean? = if (searchOk && adapter != null) {
                                probeReadable(adapter)
                            } else {
                                null
                            }
                            val ok = com.novelreader.core.util.SourceHealth.isUsable(searchOk, readable)
                            synchronized(map) { map[source.id] = ok }
                            if (com.novelreader.core.util.SourceHealth.isNoToc(searchOk, readable)) {
                                unreadableSet += source.id
                            }
                            _testing.value = _testing.value - source.id
                            val d = done.incrementAndGet()
                            _testProgress.value = d to total
                        }
                    }
                }.joinAll()
                _unreadable.value = unreadableSet.toSet()
                map
            }
            _testResults.value = results.toMap()
            _testProgress.value = null
            _testRunning.value = false
            val failed = results.count { !it.value }
            val noToc = _unreadable.value.size
            _message.value = buildString {
                append(
                    if (failed == 0) {
                        "检测完成：全部源可用"
                    } else {
                        "检测完成：${results.size} 个源中 $failed 个失效"
                    },
                )
                if (noToc > 0) {
                    append("（其中 $noToc 个能搜到书但读不了目录，已一并记为失效）")
                }
            }
        }
    }

    /**
     * 探测「这个源能不能真的读到目录」。
     *
     * @return true = 读到章节；false = 搜到了书却取不到目录（规则对不上新页面）；
     *   null = 无法判定（搜索就没结果，可能只是关键词不匹配）
     */
    private suspend fun probeReadable(adapter: com.novelreader.domain.model.SourceAdapter): Boolean? {
        val first = runCatching {
            kotlinx.coroutines.withTimeoutOrNull(15_000L) { adapter.search("测试", 1) }
        }.getOrNull()?.orEmpty()?.firstOrNull() ?: return null
        return runCatching {
            kotlinx.coroutines.withTimeoutOrNull(15_000L) { adapter.chapterList(first) }
                .orEmpty()
                .isNotEmpty()
        }.getOrDefault(false)
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

    /** 一键删除所有已检出失效的源（v1.5.5.0） */
    fun deleteFailedSources() {
        val failed = _testResults.value.filterValues { !it }.keys.toList()
        if (failed.isEmpty()) {
            _message.value = "尚未检测到失效源，请先「检测可用性」"
            return
        }
        // 删除前先解析出源名（删除后 sources 里就查不到了），供结果提示展示
        val namesById = _sources.value.associate { it.id to it.name }
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    // 单次批量删除（只触发一次 Room invalidation），避免循环 deleteSource
                    // 触发 N 次 invalidation 与 observeAll() Flow 重新查询并发、
                    // 导致 Cursor 读失效闪退（v1.5.5.2 修复「仍闪退」）。
                    sourceRepository.deleteSources(failed)
                }
            }
            if (result.isSuccess) {
                _testResults.value = _testResults.value.filterKeys { it !in failed }
                _message.value = buildString {
                    append("已删除 ${failed.size} 个失效源")
                    append("：")
                    append(failed.map { namesById[it] ?: it }.take(5).joinToString("、"))
                    if (failed.size > 5) append(" 等")
                }
                refresh()
            } else {
                // 批量删除失败：退化为逐个删除，隔离单个失败，尽量完成删除
                val okNames = mutableListOf<String>()
                val failNames = mutableListOf<String>()
                withContext(Dispatchers.IO) {
                    failed.forEach { id ->
                        runCatching { sourceRepository.deleteSource(id) }
                            .onSuccess { okNames += namesById[id] ?: id }
                            .onFailure { failNames += namesById[id] ?: id }
                    }
                }
                _testResults.value = _testResults.value.filterKeys { it !in failed }
                _message.value = buildString {
                    append("已删除 ${okNames.size} 个失效源")
                    if (okNames.isNotEmpty()) {
                        append("：")
                        append(okNames.take(5).joinToString("、"))
                        if (okNames.size > 5) append(" 等")
                    }
                    if (failNames.isNotEmpty()) {
                        append("；${failNames.size} 个删除失败：${failNames.take(3).joinToString("、")}")
                    }
                }
                refresh()
            }
        }
    }

    /** 清除检测结果 */
    fun clearTestResults() {
        _testResults.value = emptyMap()
        _unreadable.value = emptySet()
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
    fun sortSources(list: List<SourceInfo>, sortBy: SourceSortOrder): List<SourceInfo> {
        val base = when (sortBy) {
            SourceSortOrder.DEFAULT -> list
            SourceSortOrder.MANUAL -> list.sortedWith(
                compareBy<SourceInfo> { it.sortOrder == 0 }.thenBy { it.sortOrder }.thenBy { it.name }
            )
            SourceSortOrder.NAME -> list.sortedBy { it.name }
            SourceSortOrder.TYPE -> list.sortedWith(compareBy({ it.type.name }, { it.name }))
            SourceSortOrder.CATEGORY -> list.sortedWith(compareBy({ it.category }, { it.name }))
            SourceSortOrder.ENABLED -> list.sortedByDescending { it.enabled }
        }
        // 18+ 源沉底：手动模式下尊重用户显式顺序，其余自动排序一律把成人源排到最后
        return if (sortBy == SourceSortOrder.MANUAL) base else base.sortedBy { it.isAdult }
    }

    /**
     * 手动排序：把 [sourceId] 在 [list] 中上移一位（v1.5.8.0）。
     *
     * 手动排序按当前展示顺序重写整个列表的 sortOrder（1..n），
     * 并持久化到数据库，重启后顺序保留。
     */
    fun moveSourceUp(sourceId: String) {
        val list = _sources.value
        val idx = list.indexOfFirst { it.id == sourceId }
        if (idx <= 0) return
        val reordered = list.toMutableList().apply { add(idx - 1, removeAt(idx)) }
        applyManualOrder(reordered)
    }

    /** 手动排序：把 [sourceId] 下移一位（v1.5.8.0） */
    fun moveSourceDown(sourceId: String) {
        val list = _sources.value
        val idx = list.indexOfFirst { it.id == sourceId }
        if (idx < 0 || idx >= list.size - 1) return
        val reordered = list.toMutableList().apply { add(idx + 1, removeAt(idx)) }
        applyManualOrder(reordered)
    }

    /** 把当前列表顺序写入手动排序序号并持久化（不直接覆盖内存列表，走 Room Flow 刷新） */
    fun applyManualOrder(reordered: List<SourceInfo>) {
        viewModelScope.launch {
            sourceRepository.reorderSources(reordered.map { it.id })
        }
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
    MANUAL("手动"),
    NAME("名称"),
    TYPE("类型"),
    CATEGORY("分类"),
    ENABLED("启用状态"),
}

/**
 * 聚合包单个子包的下载超时。
 *
 * 聚合包里的 legado 包动辄 1MB+，默认 20s 读取超时在弱网下不够（v1.5.6.0）。
 */
private const val PACK_FILE_TIMEOUT_MS = 60_000L
