package com.novelreader.ui.screens.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.novelreader.core.preferences.PreferencesManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 设置中心 ViewModel。
 *
 * 集中管理全局设置：日夜间模式、小说字体/背景、漫画翻页方式等。
 */
class SettingsViewModel(
    private val preferencesManager: PreferencesManager,
    private val backupManager: com.novelreader.core.backup.BackupManager? = null,
    private val appContext: android.content.Context? = null,
    /** 本地标签索引（v1.8.1.6）：设置页直达「标签导入」，不必先找到标签搜索页的索引弹窗 */
    private val tagIndexRepository: com.novelreader.data.repository.TagIndexRepository? = null,
) : ViewModel() {

    /** 备份 / 恢复结果提示（UI 展示后自动消失） */
    private val _backupMessage = MutableStateFlow<String?>(null)
    val backupMessage: StateFlow<String?> = _backupMessage.asStateFlow()

    fun dismissBackupMessage() {
        _backupMessage.value = null
    }

    // ===== 应用更新（v1.5.4.0） =====

    /** 更新检查状态：0=空闲 1=检查中 2=下载中 */
    private val _updateBusy = MutableStateFlow(false)
    val updateBusy: StateFlow<Boolean> = _updateBusy.asStateFlow()

    /** 下载进度（0..1，未知总量时为 -1） */
    private val _updateProgress = MutableStateFlow(-1f)
    val updateProgress: StateFlow<Float> = _updateProgress.asStateFlow()

    /** 发现新版本（非空即弹确认框） */
    private val _updateInfo = MutableStateFlow<com.novelreader.core.update.UpdateChecker.UpdateInfo?>(null)
    val updateInfo: StateFlow<com.novelreader.core.update.UpdateChecker.UpdateInfo?> = _updateInfo.asStateFlow()

    /** 下载失败信息（非空时在更新弹框内展示，v1.5.5.4） */
    private val _updateError = MutableStateFlow<String?>(null)
    val updateError: StateFlow<String?> = _updateError.asStateFlow()

    /**
     * 进程级下载状态（v1.8.3.0 待办 #18 / #19）。
     *
     * 下载现在跑在 [com.novelreader.core.update.UpdateDownloadManager] 里，
     * 前台 Service 负责保活与通知栏进度；这里只是把状态镜像成 UI 用的几个流。
     * 好处：退出设置页再进来，进度还在，下载也没被掐掉。
     */
    val updateState: StateFlow<com.novelreader.core.update.UpdateDownloadManager.State> =
        com.novelreader.core.update.UpdateDownloadManager.state

    init {
        viewModelScope.launch {
            com.novelreader.core.update.UpdateDownloadManager.state.collect { st ->
                if (st.version.isBlank()) {
                    // 没任务（首次进入 / 已经安装完被 reset）：只复位进度，不动「检查中」的 busy
                    _updateProgress.value = -1f
                    return@collect
                }
                _updateProgress.value = st.progress
                _updateBusy.value = st.running
                st.error?.let {
                    _updateError.value = it
                    _updateBusy.value = false
                }
                if (st.finished) {
                    // 安装交给 Service（前台自动拉起，后台走通知点击），这里只收尾弹框
                    _updateProgress.value = 1f
                    _updateBusy.value = false
                    _updateInfo.value = null
                }
            }
        }
    }

    /** 手动检查更新 */
    fun checkUpdate() {
        if (_updateBusy.value) return
        viewModelScope.launch {
            _updateBusy.value = true
            _updateProgress.value = -1f
            _updateError.value = null
            val info = withContext(Dispatchers.IO) {
                com.novelreader.core.update.UpdateChecker.checkLatest(
                    com.novelreader.core.version.AppVersion.CURRENT_VERSION,
                )
            }
            if (info == null) {
                _backupMessage.value = "已是最新版本（v${com.novelreader.core.version.AppVersion.CURRENT_VERSION}）"
            } else {
                _updateInfo.value = info
            }
            _updateBusy.value = false
        }
    }

    /** 取消更新弹框 */
    fun dismissUpdateInfo() {
        _updateInfo.value = null
        _updateError.value = null
    }

    /**
     * 下载更新（v1.8.3.0 待办 #18 / #19）。
     *
     * 真正的下载不在这个 ViewModel 里跑，交给前台 [com.novelreader.core.update.UpdateDownloadService]
     * —— 原实现挂在 `viewModelScope` 上，用户一退出设置页下载就被 cancel，
     * 切后台还可能被系统回收进程。现在下载跟页面生命周期完全解耦：
     * 退出页面继续下、切后台有前台服务兜底、断网后从 `.part` 处续传。
     */
    fun downloadUpdate() {
        val info = _updateInfo.value ?: return
        val ctx = appContext ?: return
        if (_updateBusy.value) return
        _updateError.value = null
        _updateProgress.value = 0f
        _updateBusy.value = true
        com.novelreader.core.update.UpdateDownloadService.start(ctx, info)
    }

    /** 导出备份到 SAF Uri */
    fun exportBackup(uri: android.net.Uri) {
        val mgr = backupManager ?: return
        viewModelScope.launch {
            _backupMessage.value = runCatching { mgr.export(uri) }
                .getOrElse { "备份失败：${it.message ?: "未知错误"}" }
        }
    }

    /** 从 SAF Uri 恢复备份 */
    fun importBackup(uri: android.net.Uri) {
        val mgr = backupManager ?: return
        viewModelScope.launch {
            _backupMessage.value = runCatching { mgr.import(uri) }
                .getOrElse { "恢复失败：${it.message ?: "未知错误"}" }
        }
    }

    // ===== 标签导入（v1.8.1.6：与 PC 打标工具 / 标签搜索页同一套打标表）=====

    /** 标签导入是否进行中（云端下载 / 本地解析共用） */
    private val _tagImportBusy = MutableStateFlow(false)
    val tagImportBusy: StateFlow<Boolean> = _tagImportBusy.asStateFlow()

    /**
     * 标签导入实时进度（v1.8.3.0 待办 #17）。
     *
     * 三段：下载（按字节）→ 解析 → 写入标签库（按分块）。
     * 十几 MB 的表在老版本里是「点一下然后界面毫无反应几十秒」，
     * 有了这段进度至少能看出卡在哪一步。
     */
    private val _tagImportProgress = MutableStateFlow<TagImportProgress?>(null)
    val tagImportProgress: StateFlow<TagImportProgress?> = _tagImportProgress.asStateFlow()

    private var tagImportJob: kotlinx.coroutines.Job? = null

    /**
     * 取消标志。
     *
     * 下载走的是阻塞式流式读取，协程 `cancel()` 打断不了正在 `read()` 的线程，
     * 所以在进度回调里查这个标志主动抛 [CancellationException] 跳出来。
     */
    @Volatile
    private var tagImportCancelled = false

    /** 用户取消导入（仅在可取消阶段生效；写入阶段按钮是禁用的） */
    fun cancelTagImport() {
        if (_tagImportProgress.value?.cancelable != true) return
        tagImportCancelled = true
        tagImportJob?.cancel()
        _tagImportProgress.value = null
        _tagImportBusy.value = false
        _backupMessage.value = "已取消导入：已写入的部分保留，重新导入会按同名标签合并覆盖"
    }

    /** 标签导出进度（0..1；-1 = 未在导出），v1.8.1.9 */
    private val _tagExportProgress = MutableStateFlow(-1f)
    val tagExportProgress: StateFlow<Float> = _tagExportProgress.asStateFlow()

    // ===== 云端标签（v1.8.2.0：先读清单再决定要不要下整表）=====

    /** 云端版本清单；null = 还没查过或查不到 */
    private val _cloudManifest = MutableStateFlow<com.novelreader.core.util.TagTable.Manifest?>(null)
    val cloudManifest: StateFlow<com.novelreader.core.util.TagTable.Manifest?> = _cloudManifest.asStateFlow()

    /** 是否正在读云端清单 */
    private val _cloudChecking = MutableStateFlow(false)
    val cloudChecking: StateFlow<Boolean> = _cloudChecking.asStateFlow()

    /** 最近一次导入的云端版本：(generatedAt, 条目数)；从未导入为 (0, 0) */
    private val _cloudImported = MutableStateFlow(0L to 0)
    val cloudImported: StateFlow<Pair<Long, Int>> = _cloudImported.asStateFlow()

    /**
     * 读取云端版本清单（几百字节）。
     *
     * 只拉清单不拉整表 —— 用户打开弹窗就想知道「云端有多少条、比我这新不新」，
     * 为此下载十几 MB 不值当。
     */
    fun checkCloudTags(manifestUrl: String = com.novelreader.data.repository.TagIndexRepository.DEFAULT_CLOUD_MANIFEST_URL) {
        val repo = tagIndexRepository ?: run {
            _backupMessage.value = "当前环境不支持标签导入"
            return
        }
        if (_cloudChecking.value) return
        viewModelScope.launch {
            _cloudChecking.value = true
            _cloudManifest.value = repo.fetchCloudManifest(manifestUrl)
            _cloudImported.value = runCatching { preferencesManager.tagCloudImport() }
                .getOrDefault(0L to 0)
            _cloudChecking.value = false
        }
    }

    /**
     * 导入云端最新打标表（v1.8.2.0，v1.8.3.0 补进度条）。
     *
     * 有清单就用清单里的文件名（并加时间戳绕 CDN 缓存）；清单拿不到就退回默认整表地址，
     * 保证「云端更新了但清单接口抽风」时导入仍然可用。
     */
    fun importCloudLatest(manifestUrl: String = com.novelreader.data.repository.TagIndexRepository.DEFAULT_CLOUD_MANIFEST_URL) {
        startTagImport(TagImportSource.Cloud(manifestUrl))
    }

    /**
     * 从本地文件导入打标表（SAF Uri）。
     *
     * 表里条目按 `itemType` 分流，小说 / 漫画 / 视频各自合并进对应类型的标签层；
     * 合并口径与标签搜索页一致：同名标签取更高置信度，站方 100 分不会被顶掉。
     */
    fun importTagTableFromUri(uri: android.net.Uri) {
        val ctx = appContext ?: return
        startTagImport(TagImportSource.Local(uri, ctx))
    }

    /** 从云端 URL 导入打标表（默认独立打标仓库 leimao2333/leimao-tagger 的 data/tag_table.json，经 jsDelivr 分发） */
    fun importTagTableFromUrl(url: String) {
        val target = url.trim()
        if (target.isEmpty()) return
        startTagImport(TagImportSource.Url(target))
    }

    /**
     * 统一的导入入口：下载 → 解析 → 写入，全程上报进度（v1.8.3.0 待办 #17）。
     *
     * 取消语义：下载 / 解析阶段可随时取消；写入阶段锁定取消（按钮禁用），
     * 并且只在「类型边界」检查取消标志 —— 这样每个类型要么整批写完、要么完全没写，
     * 不会留下 local_tags 写了、tag_instances 没写的半截数据。
     */
    private fun startTagImport(source: TagImportSource) {
        val repo = tagIndexRepository ?: run {
            _backupMessage.value = "当前环境不支持标签导入"
            return
        }
        if (_tagImportBusy.value) return
        tagImportCancelled = false
        tagImportJob = viewModelScope.launch {
            _tagImportBusy.value = true
            _tagImportProgress.value = TagImportProgress(TagImportPhase.DOWNLOAD, 0f)
            val message = try {
                val loaded = loadTableText(repo, source)
                _tagImportProgress.value = TagImportProgress(
                    TagImportPhase.PARSE, TAG_IMPORT_DOWNLOAD_WEIGHT, "解析中…",
                )
                val payload = com.novelreader.core.util.TagTable.parse(loaded.json)
                    ?: error("打标表解析失败（不是有效的 tag_table.json）")
                // 条数校验（v1.8.2.0）：CDN 可能还挂着上一版的文件（jsDelivr 实测缓存最长 7 天），
                // 不校验的话会「导入成功 0 条」，用户以为导入过了其实没有。
                if (loaded.expectedCount > 0 && payload.items.size != loaded.expectedCount) {
                    error(
                        "云端表条数与版本清单不符（清单 ${loaded.expectedCount} 条，实际下载到 ${payload.items.size} 条），" +
                            "大概率是 CDN 缓存还没刷新，请稍后重试",
                    )
                }
                val rememberAsCloud = source !is TagImportSource.Local
                val types = com.novelreader.domain.model.SourceType.entries
                var imported = 0
                types.forEachIndexed { index, type ->
                    throwIfCancelled()
                    val typeBase = index.toFloat() / types.size
                    imported += repo.importTagTable(payload, type) { done, total ->
                        val frac = if (total > 0) done.toFloat() / total else 1f
                        _tagImportProgress.value = TagImportProgress(
                            phase = TagImportPhase.WRITE,
                            fraction = (TAG_IMPORT_WRITE_BASE +
                                (typeBase + frac / types.size) * TAG_IMPORT_WRITE_WEIGHT)
                                .coerceIn(0f, 1f),
                            detail = "${typeLabel(type)} $done / $total",
                            cancelable = false,
                        )
                    }
                }
                if (rememberAsCloud) {
                    preferencesManager.setTagCloudImport(payload.generatedAt, payload.items.size)
                    _cloudImported.value = payload.generatedAt to payload.items.size
                }
                "标签导入完成：$imported 部作品已合并进本地标签层（表内 ${payload.items.size} 条）"
            } catch (c: kotlinx.coroutines.CancellationException) {
                "已取消导入（已写入的部分保留）"
            } catch (t: Throwable) {
                "标签导入失败：${t.message ?: "未知错误"}"
            } finally {
                _tagImportBusy.value = false
                _tagImportProgress.value = null
            }
            _backupMessage.value = message
        }
    }

    /** 取表文本：本地直接读，网络走带进度的下载 */
    private suspend fun loadTableText(
        repo: com.novelreader.data.repository.TagIndexRepository,
        source: TagImportSource,
    ): LoadedTable = withContext(Dispatchers.IO) {
        when (source) {
            is TagImportSource.Local -> {
                val json = source.context.contentResolver.openInputStream(source.uri)?.use { stream ->
                    stream.readBytes().toString(Charsets.UTF_8)
                } ?: error("无法读取所选文件")
                LoadedTable(json, 0)
            }

            is TagImportSource.Cloud -> {
                _tagImportProgress.value = TagImportProgress(
                    TagImportPhase.DOWNLOAD, 0f, "获取版本清单…",
                )
                val manifest = _cloudManifest.value ?: repo.fetchCloudManifest(source.manifestUrl)
                    .also { _cloudManifest.value = it }
                val url = if (manifest != null) {
                    com.novelreader.data.repository.TagIndexRepository.tableUrlOf(
                        source.manifestUrl, manifest.latest,
                    )
                } else {
                    com.novelreader.data.repository.TagIndexRepository.DEFAULT_CLOUD_TABLE_URL
                }
                // 十几 MB，超时要给够（慢网下 30 秒会被掐断）
                LoadedTable(downloadWithProgress(url), manifest?.count ?: 0)
            }

            is TagImportSource.Url -> LoadedTable(downloadWithProgress(source.url), 0)
        }
    }

    /** 带字节进度的大文本下载；取消标志在回调里检查并抛异常跳出阻塞读 */
    private fun downloadWithProgress(url: String): String =
        com.novelreader.core.network.HttpClient.getTextWithProgress(url, timeoutMs = 180_000) { done, total ->
            if (tagImportCancelled) throw kotlinx.coroutines.CancellationException("已取消")
            val frac = if (total > 0) (done.toFloat() / total).coerceIn(0f, 1f) else 0f
            _tagImportProgress.value = TagImportProgress(
                phase = TagImportPhase.DOWNLOAD,
                fraction = frac * TAG_IMPORT_DOWNLOAD_WEIGHT,
                detail = if (total > 0) "${formatBytes(done)} / ${formatBytes(total)}"
                else "已下载 ${formatBytes(done)}",
                // jsDelivr 走分块传输时没有 Content-Length，这时百分比没有意义，
                // 不如显示「已下载 X MB」并把进度条切成不确定态
                indeterminate = total <= 0,
            )
        }

    private fun throwIfCancelled() {
        if (tagImportCancelled) throw kotlinx.coroutines.CancellationException("已取消")
    }

    private fun formatBytes(value: Long): String = when {
        value >= 1024L * 1024 -> String.format(java.util.Locale.US, "%.1f MB", value / 1024f / 1024f)
        value >= 1024 -> String.format(java.util.Locale.US, "%.0f KB", value / 1024f)
        else -> "$value B"
    }

    private fun typeLabel(type: com.novelreader.domain.model.SourceType): String = when (type) {
        com.novelreader.domain.model.SourceType.NOVEL -> "小说"
        com.novelreader.domain.model.SourceType.COMIC -> "漫画"
        com.novelreader.domain.model.SourceType.VIDEO -> "视频"
    }

    private data class LoadedTable(val json: String, val expectedCount: Int)

    /** 三种导入来源：云端清单 / 直接 URL / 本地文件 */
    private sealed interface TagImportSource {
        data class Cloud(val manifestUrl: String) : TagImportSource
        data class Url(val url: String) : TagImportSource
        data class Local(val uri: android.net.Uri, val context: android.content.Context) : TagImportSource
    }

    /**
     * 导出本地标签层为打标表 JSON（SAF Uri）。
     *
     * 与「导出收录清单」同一份 schema（tag_table.json），小说 / 漫画 / 视频三个类型的
     * 标签层合并导出，PC 端打标工具可直接当基底清单消费。
     */
    fun exportTagTableToUri(uri: android.net.Uri) {
        val repo = tagIndexRepository ?: run {
            _backupMessage.value = "当前环境不支持标签导出"
            return
        }
        val ctx = appContext ?: return
        if (_tagImportBusy.value) return
        viewModelScope.launch {
            _tagImportBusy.value = true
            _tagExportProgress.value = 0f
            _backupMessage.value = runCatching {
                // v1.8.3.0 待办 #20：流式写出 —— 边分页查库边写 SAF 输出流，
                // 不再「全量读进内存 → 拼 12MB 字符串 → 转 byte[]」那样三份数据同时驻留
                val bytes = withContext(Dispatchers.IO) {
                    ctx.contentResolver.openOutputStream(uri, "wt")?.use { raw ->
                        val counter = CountingOutputStream(raw)
                        counter.bufferedWriter(Charsets.UTF_8).use { writer ->
                            repo.exportTagTable(
                                types = com.novelreader.domain.model.SourceType.entries.toList(),
                                out = writer,
                            ) { done, total ->
                                _tagExportProgress.value =
                                    if (total > 0) (done.toFloat() / total).coerceIn(0f, 1f) else 0f
                            }
                                .also { writer.flush() }
                        }
                        counter.count
                    } ?: error("无法写入所选文件")
                }
                "标签导出完成：${(bytes / 1024f).toInt()} KB（小说/漫画/视频全类型，边查边写）"
            }.getOrElse {
                if (it is kotlinx.coroutines.CancellationException) {
                    "已取消导出（文件可能不完整）"
                } else {
                    "标签导出失败：${it.message ?: "未知错误"}"
                }
            }
            _tagExportProgress.value = -1f
            _tagImportBusy.value = false
        }
    }

    // ===== WebDav 云备份（v1.3.8.0） =====

    /** WebDav 配置（服务器 / 用户名 / 密码 / 路径） */
    data class WebDavConfig(
        val url: String = "",
        val username: String = "",
        val password: String = "",
        val path: String = "leimao-reader-backup.json",
    ) {
        val isConfigured: Boolean get() = url.isNotBlank()
    }

    private val _webdavConfig = MutableStateFlow(WebDavConfig())
    val webdavConfig: StateFlow<WebDavConfig> = _webdavConfig.asStateFlow()

    /** WebDav 操作是否进行中（上传 / 下载 / 测试连接共用一个开关，防止并发） */
    private val _webdavBusy = MutableStateFlow(false)
    val webdavBusy: StateFlow<Boolean> = _webdavBusy.asStateFlow()

    /** 保存 WebDav 配置 */
    fun saveWebDavConfig(config: WebDavConfig) {
        viewModelScope.launch {
            preferencesManager.setWebdavUrl(config.url.trim())
            preferencesManager.setWebdavUsername(config.username.trim())
            preferencesManager.setWebdavPassword(config.password)
            preferencesManager.setWebdavPath(config.path.trim().ifBlank { "leimao-reader-backup.json" })
            _webdavConfig.value = config.copy(
                url = config.url.trim(),
                username = config.username.trim(),
                path = config.path.trim().ifBlank { "leimao-reader-backup.json" },
            )
            _backupMessage.value = "WebDav 配置已保存"
        }
    }

    /** 测试 WebDav 连接 */
    fun testWebDavConnection() {
        val cfg = _webdavConfig.value
        if (!cfg.isConfigured) {
            _backupMessage.value = "请先填写 WebDav 服务器地址"
            return
        }
        if (_webdavBusy.value) return
        viewModelScope.launch {
            _webdavBusy.value = true
            _backupMessage.value = runCatching {
                val client = com.novelreader.core.backup.WebDavClient(
                    cfg.url, cfg.username.ifBlank { null }, cfg.password,
                )
                client.testConnection() ?: "连接成功"
            }.getOrElse { "连接失败：${it.message ?: "未知错误"}" }
            _webdavBusy.value = false
        }
    }

    /** 上传备份到 WebDav */
    fun uploadToWebDav() {
        val mgr = backupManager ?: return
        val cfg = _webdavConfig.value
        if (!cfg.isConfigured) {
            _backupMessage.value = "请先填写 WebDav 服务器地址"
            return
        }
        if (_webdavBusy.value) return
        viewModelScope.launch {
            _webdavBusy.value = true
            _backupMessage.value = runCatching {
                val (json, stats) = mgr.serialize()
                val client = com.novelreader.core.backup.WebDavClient(
                    cfg.url, cfg.username.ifBlank { null }, cfg.password,
                )
                withContext(kotlinx.coroutines.Dispatchers.IO) { client.put(cfg.path, json) }
                "已上传到 WebDav：${stats.summary}"
            }.getOrElse { "上传失败：${it.message ?: "未知错误"}" }
            _webdavBusy.value = false
        }
    }

    /** 从 WebDav 下载并恢复备份 */
    fun downloadFromWebDav() {
        val mgr = backupManager ?: return
        val cfg = _webdavConfig.value
        if (!cfg.isConfigured) {
            _backupMessage.value = "请先填写 WebDav 服务器地址"
            return
        }
        if (_webdavBusy.value) return
        viewModelScope.launch {
            _webdavBusy.value = true
            _backupMessage.value = runCatching {
                val client = com.novelreader.core.backup.WebDavClient(
                    cfg.url, cfg.username.ifBlank { null }, cfg.password,
                )
                val json = withContext(kotlinx.coroutines.Dispatchers.IO) { client.get(cfg.path) }
                mgr.deserialize(json)
            }.getOrElse { "下载恢复失败：${it.message ?: "未知错误"}" }
            _webdavBusy.value = false
        }
    }

    /** 日夜间模式 0=关闭 1=开启 2=跟随系统（#36） */
    private val _darkThemeMode = MutableStateFlow(PreferencesManager.THEME_MODE_LIGHT)
    val darkThemeMode: StateFlow<Int> = _darkThemeMode.asStateFlow()

    /** 兼容字段：当前是否处于深色 */
    private val _darkTheme = MutableStateFlow(false)
    val darkTheme: StateFlow<Boolean> = _darkTheme.asStateFlow()

    /** 小说字体大小 */
    private val _novelFontSize = MutableStateFlow(PreferencesManager.DEFAULT_FONT_SIZE)
    val novelFontSize: StateFlow<Float> = _novelFontSize.asStateFlow()

    /** 小说背景索引 */
    private val _novelBgIndex = MutableStateFlow(PreferencesManager.DEFAULT_BG_INDEX)
    val novelBgIndex: StateFlow<Int> = _novelBgIndex.asStateFlow()

    /** 漫画翻页方式：0=上下滚动，1=左右翻页 */
    private val _comicPageMode = MutableStateFlow(0)
    val comicPageMode: StateFlow<Int> = _comicPageMode.asStateFlow()

    /** 漫画预加载张数（1~8，默认 4；C5） */
    private val _comicPreloadCount = MutableStateFlow(PreferencesManager.DEFAULT_COMIC_PRELOAD_COUNT)
    val comicPreloadCount: StateFlow<Int> = _comicPreloadCount.asStateFlow()

    /** 漫画仅 WiFi 下预加载（C5） */
    private val _comicPreloadWifiOnly = MutableStateFlow(false)
    val comicPreloadWifiOnly: StateFlow<Boolean> = _comicPreloadWifiOnly.asStateFlow()

    /** 漫画阅读页「边缘竖滑调亮度 / 音量」手势（v1.7.6.0，默认关闭） */
    private val _comicEdgeGesture = MutableStateFlow(false)
    val comicEdgeGesture: StateFlow<Boolean> = _comicEdgeGesture.asStateFlow()

    /** 并行搜索线程数 */
    private val _searchConcurrency = MutableStateFlow(PreferencesManager.DEFAULT_SEARCH_CONCURRENCY)
    val searchConcurrency: StateFlow<Int> = _searchConcurrency.asStateFlow()

    /** 隐藏成人内容（v1.7.5.0，默认开启） */
    private val _hideAdult = MutableStateFlow(true)
    val hideAdult: StateFlow<Boolean> = _hideAdult.asStateFlow()

    /** 小说「未加入书架」提示开关（v1.8.3.0，默认开启） */
    private val _novelShelfPrompt = MutableStateFlow(true)
    val novelShelfPrompt: StateFlow<Boolean> = _novelShelfPrompt.asStateFlow()

    /** 漫画「未加入书架」提示开关（v1.8.3.0，默认开启） */
    private val _comicShelfPrompt = MutableStateFlow(true)
    val comicShelfPrompt: StateFlow<Boolean> = _comicShelfPrompt.asStateFlow()

    /** 搜索结果长按加入书架开关（v1.8.3.0 待办 #14，默认开启） */
    private val _searchLongPressShelf = MutableStateFlow(true)
    val searchLongPressShelf: StateFlow<Boolean> = _searchLongPressShelf.asStateFlow()

    // ===== 搜索结果展示（v1.8.6.0 待办 #26） =====
    /** 四个模块各自记忆搜索结果布局：false = 列表（默认），true = 宫格 */
    private val _searchGridNovel = MutableStateFlow(false)
    val searchGridNovel: StateFlow<Boolean> = _searchGridNovel.asStateFlow()

    private val _searchGridComic = MutableStateFlow(false)
    val searchGridComic: StateFlow<Boolean> = _searchGridComic.asStateFlow()

    private val _searchGridVideo = MutableStateFlow(false)
    val searchGridVideo: StateFlow<Boolean> = _searchGridVideo.asStateFlow()

    private val _searchGridRss = MutableStateFlow(false)
    val searchGridRss: StateFlow<Boolean> = _searchGridRss.asStateFlow()

    // ===== RSS 订阅（v1.8.6.0 待办 #24） =====
    /** RSS 订阅入口是否移到首页分类导航下方（默认关 = 顶栏图标） */
    private val _rssEntryBelow = MutableStateFlow(PreferencesManager.DEFAULT_RSS_ENTRY_BELOW)
    val rssEntryBelow: StateFlow<Boolean> = _rssEntryBelow.asStateFlow()

    // ===== 视频播放（v1.3.2.0） =====
    /** 进入播放页默认全屏 */
    private val _videoFullscreenDefault = MutableStateFlow(false)
    val videoFullscreenDefault: StateFlow<Boolean> = _videoFullscreenDefault.asStateFlow()

    /** 长按倍速档位（×10） */
    private val _videoLongPressSpeed = MutableStateFlow(PreferencesManager.DEFAULT_VIDEO_LONG_PRESS_SPEED)
    val videoLongPressSpeed: StateFlow<Int> = _videoLongPressSpeed.asStateFlow()

    /** 长按左侧快退开关（v1.8.3.0，默认开启） */
    private val _videoLongPressRewind = MutableStateFlow(PreferencesManager.DEFAULT_VIDEO_LONG_PRESS_REWIND)
    val videoLongPressRewind: StateFlow<Boolean> = _videoLongPressRewind.asStateFlow()

    /** 小说预加载章数（v1.8.3.0，0=关） */
    private val _novelPreloadChapters = MutableStateFlow(PreferencesManager.DEFAULT_NOVEL_PRELOAD_CHAPTERS)
    val novelPreloadChapters: StateFlow<Int> = _novelPreloadChapters.asStateFlow()

    /** 双击快进步长（秒） */
    private val _videoDoubleTapSeek = MutableStateFlow(PreferencesManager.DEFAULT_VIDEO_DOUBLE_TAP_SEEK)
    val videoDoubleTapSeek: StateFlow<Int> = _videoDoubleTapSeek.asStateFlow()

    /** 播完自动连播下一集 */
    private val _videoAutoNext = MutableStateFlow(PreferencesManager.DEFAULT_VIDEO_AUTO_NEXT)
    val videoAutoNext: StateFlow<Boolean> = _videoAutoNext.asStateFlow()

    /** 横屏全屏上下滑切换上下集（v1.4.7.0） */
    private val _videoVerticalSwipeSwitch = MutableStateFlow(PreferencesManager.DEFAULT_VIDEO_VERTICAL_SWIPE_SWITCH)
    val videoVerticalSwipeSwitch: StateFlow<Boolean> = _videoVerticalSwipeSwitch.asStateFlow()

    /** 小窗播放（PIP）开关（v1.5.1.0，默认关闭） */
    private val _videoPipEnabled = MutableStateFlow(PreferencesManager.DEFAULT_VIDEO_PIP_ENABLED)
    val videoPipEnabled: StateFlow<Boolean> = _videoPipEnabled.asStateFlow()

    /** 视频向后缓冲时长（秒，0 = 关闭；v1.8.4.0） */
    private val _videoBufferSeconds = MutableStateFlow(PreferencesManager.DEFAULT_VIDEO_BUFFER_SECONDS)
    val videoBufferSeconds: StateFlow<Int> = _videoBufferSeconds.asStateFlow()

    /** 默认清晰度偏好（0 = 自动；v1.8.4.0） */
    private val _videoQuality = MutableStateFlow(PreferencesManager.DEFAULT_VIDEO_QUALITY)
    val videoQuality: StateFlow<Int> = _videoQuality.asStateFlow()

    /** 尾段预缓冲仅在 WiFi 下（v1.8.4.4，默认开启） */
    private val _videoPrefetchWifiOnly =
        MutableStateFlow(PreferencesManager.DEFAULT_VIDEO_PREFETCH_WIFI_ONLY)
    val videoPrefetchWifiOnly: StateFlow<Boolean> = _videoPrefetchWifiOnly.asStateFlow()

    init {
        observePrefs()
        observeWebDav()
    }

    private fun observeWebDav() {
        viewModelScope.launch {
            // 先读一次当前配置
            val url = preferencesManager.webdavUrlFlow.first()
            val username = preferencesManager.webdavUsernameFlow.first()
            val password = preferencesManager.webdavPasswordFlow.first()
            val path = preferencesManager.webdavPathFlow.first()
            _webdavConfig.value = WebDavConfig(url, username, password, path)
        }
    }

    private fun observePrefs() {
        viewModelScope.launch {
            preferencesManager.darkThemeModeFlow.collect {
                _darkThemeMode.value = it
                _darkTheme.value = it == PreferencesManager.THEME_MODE_DARK
            }
        }
        viewModelScope.launch {
            preferencesManager.novelFontSizeFlow.collect { _novelFontSize.value = it }
        }
        viewModelScope.launch {
            preferencesManager.novelBgIndexFlow.collect { _novelBgIndex.value = it }
        }
        viewModelScope.launch {
            preferencesManager.comicPageModeFlow.collect { _comicPageMode.value = it }
        }
        viewModelScope.launch {
            preferencesManager.comicPreloadCountFlow.collect { _comicPreloadCount.value = it }
        }
        viewModelScope.launch {
            preferencesManager.comicPreloadWifiOnlyFlow.collect { _comicPreloadWifiOnly.value = it }
        }
        viewModelScope.launch {
            preferencesManager.comicEdgeGestureFlow.collect { _comicEdgeGesture.value = it }
        }
        viewModelScope.launch {
            preferencesManager.searchConcurrencyFlow.collect { _searchConcurrency.value = it }
        }
        viewModelScope.launch {
            preferencesManager.videoFullscreenDefaultFlow.collect { _videoFullscreenDefault.value = it }
        }
        viewModelScope.launch {
            preferencesManager.videoLongPressSpeedFlow.collect { _videoLongPressSpeed.value = it }
        }
        viewModelScope.launch {
            preferencesManager.videoLongPressRewindFlow.collect { _videoLongPressRewind.value = it }
        }
        viewModelScope.launch {
            preferencesManager.novelPreloadChaptersFlow.collect { _novelPreloadChapters.value = it }
        }
        viewModelScope.launch {
            preferencesManager.videoDoubleTapSeekFlow.collect { _videoDoubleTapSeek.value = it }
        }
        viewModelScope.launch {
            preferencesManager.videoAutoNextFlow.collect { _videoAutoNext.value = it }
        }
        viewModelScope.launch {
            preferencesManager.videoVerticalSwipeSwitchFlow.collect { _videoVerticalSwipeSwitch.value = it }
        }
        viewModelScope.launch {
            preferencesManager.videoPipEnabledFlow.collect { _videoPipEnabled.value = it }
        }
        viewModelScope.launch {
            preferencesManager.videoBufferSecondsFlow.collect { _videoBufferSeconds.value = it }
        }
        viewModelScope.launch {
            preferencesManager.videoQualityFlow.collect { _videoQuality.value = it }
        }
        viewModelScope.launch {
            preferencesManager.videoPrefetchWifiOnlyFlow.collect { _videoPrefetchWifiOnly.value = it }
        }
        viewModelScope.launch {
            preferencesManager.hideAdultFlow.collect { _hideAdult.value = it }
        }
        viewModelScope.launch {
            preferencesManager.novelShelfPromptFlow.collect { _novelShelfPrompt.value = it }
        }
        viewModelScope.launch {
            preferencesManager.comicShelfPromptFlow.collect { _comicShelfPrompt.value = it }
        }
        viewModelScope.launch {
            preferencesManager.searchLongPressShelfFlow.collect { _searchLongPressShelf.value = it }
        }
        viewModelScope.launch {
            preferencesManager.rssEntryBelowFlow.collect { _rssEntryBelow.value = it }
        }
        // 搜索结果展示（v1.8.6.0 待办 #26）：四个模块独立记忆
        viewModelScope.launch {
            preferencesManager.searchGridNovelFlow.collect { _searchGridNovel.value = it }
        }
        viewModelScope.launch {
            preferencesManager.searchGridComicFlow.collect { _searchGridComic.value = it }
        }
        viewModelScope.launch {
            preferencesManager.searchGridVideoFlow.collect { _searchGridVideo.value = it }
        }
        viewModelScope.launch {
            preferencesManager.searchGridRssFlow.collect { _searchGridRss.value = it }
        }
    }

    /** 切换「隐藏成人内容」开关 */
    fun setHideAdult(value: Boolean) {
        viewModelScope.launch { preferencesManager.setHideAdult(value) }
    }

    /** 切换 RSS 订阅入口位置（v1.8.6.0 待办 #24） */
    fun setRssEntryBelow(value: Boolean) {
        viewModelScope.launch { preferencesManager.setRssEntryBelow(value) }
    }

    /** 切换小说搜索结果宫格展示（v1.8.6.0 待办 #26） */
    fun setSearchGridNovel(value: Boolean) {
        viewModelScope.launch { preferencesManager.setSearchGridNovel(value) }
    }

    /** 切换漫画搜索结果宫格展示（v1.8.6.0 待办 #26） */
    fun setSearchGridComic(value: Boolean) {
        viewModelScope.launch { preferencesManager.setSearchGridComic(value) }
    }

    /** 切换视频搜索结果宫格展示（v1.8.6.0 待办 #26） */
    fun setSearchGridVideo(value: Boolean) {
        viewModelScope.launch { preferencesManager.setSearchGridVideo(value) }
    }

    /** 切换订阅（期刊检索）结果宫格展示（v1.8.6.0 待办 #26） */
    fun setSearchGridRss(value: Boolean) {
        viewModelScope.launch { preferencesManager.setSearchGridRss(value) }
    }

    fun setDarkTheme(value: Boolean) {
        viewModelScope.launch {
            preferencesManager.setDarkThemeMode(
                if (value) PreferencesManager.THEME_MODE_DARK else PreferencesManager.THEME_MODE_LIGHT
            )
        }
    }

    /** 设置深色模式：0=关闭 1=开启 2=跟随系统 */
    fun setDarkThemeMode(value: Int) {
        viewModelScope.launch { preferencesManager.setDarkThemeMode(value) }
    }

    fun setNovelFontSize(value: Float) {
        viewModelScope.launch { preferencesManager.setNovelFontSize(value) }
    }

    fun setNovelBgIndex(value: Int) {
        viewModelScope.launch { preferencesManager.setNovelBgIndex(value) }
    }

    fun setComicPageMode(value: Int) {
        viewModelScope.launch { preferencesManager.setComicPageMode(value) }
    }

    fun setComicPreloadCount(value: Int) {
        viewModelScope.launch { preferencesManager.setComicPreloadCount(value) }
    }

    fun setComicPreloadWifiOnly(value: Boolean) {
        viewModelScope.launch { preferencesManager.setComicPreloadWifiOnly(value) }
    }

    fun setComicEdgeGesture(value: Boolean) {
        viewModelScope.launch { preferencesManager.setComicEdgeGesture(value) }
    }

    fun setSearchConcurrency(value: Int) {
        viewModelScope.launch { preferencesManager.setSearchConcurrency(value) }
    }

    // ===== 视频播放（v1.3.2.0） =====
    fun setVideoFullscreenDefault(value: Boolean) {
        viewModelScope.launch { preferencesManager.setVideoFullscreenDefault(value) }
    }

    fun setVideoLongPressSpeed(value: Int) {
        viewModelScope.launch { preferencesManager.setVideoLongPressSpeed(value) }
    }

    /** 长按左侧快退开关（v1.8.3.0） */
    fun setVideoLongPressRewind(value: Boolean) {
        viewModelScope.launch { preferencesManager.setVideoLongPressRewind(value) }
    }

    /** 小说预加载章数（v1.8.3.0，0=关） */
    fun setNovelPreloadChapters(value: Int) {
        viewModelScope.launch { preferencesManager.setNovelPreloadChapters(value) }
    }

    /** 小说「未加入书架」提示开关（v1.8.3.0） */
    fun setNovelShelfPrompt(value: Boolean) {
        viewModelScope.launch { preferencesManager.setNovelShelfPrompt(value) }
    }

    /** 漫画「未加入书架」提示开关（v1.8.3.0） */
    fun setComicShelfPrompt(value: Boolean) {
        viewModelScope.launch { preferencesManager.setComicShelfPrompt(value) }
    }

    /** 搜索结果长按加入书架开关（v1.8.3.0 待办 #14） */
    fun setSearchLongPressShelf(value: Boolean) {
        viewModelScope.launch { preferencesManager.setSearchLongPressShelf(value) }
    }

    fun setVideoDoubleTapSeek(value: Int) {
        viewModelScope.launch { preferencesManager.setVideoDoubleTapSeek(value) }
    }

    fun setVideoAutoNext(value: Boolean) {
        viewModelScope.launch { preferencesManager.setVideoAutoNext(value) }
    }

    fun setVideoVerticalSwipeSwitch(value: Boolean) {
        viewModelScope.launch { preferencesManager.setVideoVerticalSwipeSwitch(value) }
    }

    fun setVideoPipEnabled(value: Boolean) {
        viewModelScope.launch { preferencesManager.setVideoPipEnabled(value) }
    }

    /** 视频预缓存时长（秒，0 = 关闭；v1.8.4.0） */
    fun setVideoBufferSeconds(value: Int) {
        viewModelScope.launch { preferencesManager.setVideoBufferSeconds(value) }
    }

    /** 默认清晰度偏好（0 = 自动；v1.8.4.0） */
    fun setVideoQuality(height: Int) {
        viewModelScope.launch { preferencesManager.setVideoQuality(height) }
    }

    /** 尾段预缓冲仅在 WiFi 下（v1.8.4.4） */
    fun setVideoPrefetchWifiOnly(value: Boolean) {
        viewModelScope.launch { preferencesManager.setVideoPrefetchWifiOnly(value) }
    }
}

/** 标签导入的三个阶段（v1.8.3.0 待办 #17） */
enum class TagImportPhase(val label: String) {
    DOWNLOAD("下载打标表"),
    PARSE("解析打标表"),
    WRITE("写入标签库"),
}

/**
 * 标签导入进度快照。
 *
 * @param fraction 总进度 0..1（三段加权：下载 55%、解析 5%、写库 40%）
 * @param detail 明细文案，如「3.2 MB / 12.4 MB」「漫画 12000 / 24000」
 * @param cancelable 当前是否允许取消（写库阶段不允许，避免留下半截数据）
 */
data class TagImportProgress(
    val phase: TagImportPhase,
    val fraction: Float,
    val detail: String = "",
    val cancelable: Boolean = true,
    /** 服务器没给 Content-Length（分块传输）时为 true：只能显示已下载量，算不出百分比 */
    val indeterminate: Boolean = false,
)

/** 下载段占总进度的比重 */
private const val TAG_IMPORT_DOWNLOAD_WEIGHT = 0.55f

/** 写库段的起点（下载 + 解析之后） */
private const val TAG_IMPORT_WRITE_BASE = 0.60f

/** 写库段占总进度的比重 */
private const val TAG_IMPORT_WRITE_WEIGHT = 0.40f

/**
 * 只数不缓的计数流（v1.8.3.0 待办 #20）。
 *
 * 流式导出完想告诉用户「写了多少 KB」，但写的是 SAF 的 OutputStream，
 * 没法事后问长度 —— 顺手在写出路径上记一笔。不覆盖 `write(byte[],off,len)` 的
 * 默认实现，让 BufferedOutputStream 的整块写也能被统计到。
 */
private class CountingOutputStream(
    private val delegate: java.io.OutputStream,
) : java.io.OutputStream() {

    var count: Long = 0L
        private set

    override fun write(b: Int) {
        delegate.write(b)
        count++
    }

    override fun write(b: ByteArray, off: Int, len: Int) {
        delegate.write(b, off, len)
        count += len
    }

    override fun flush() = delegate.flush()

    override fun close() = delegate.close()
}
