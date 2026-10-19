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

    /** 下载并安装更新 */
    fun downloadUpdate() {
        val info = _updateInfo.value ?: return
        val ctx = appContext ?: return
        if (_updateBusy.value) return
        viewModelScope.launch {
            _updateBusy.value = true
            _updateProgress.value = 0f
            _updateError.value = null
            try {
                val file = withContext(Dispatchers.IO) {
                    val dir = ctx.cacheDir.resolve("update").apply { mkdirs() }
                    val target = dir.resolve("leimao-reader-v${info.version}.apk")
                    com.novelreader.core.update.UpdateChecker.downloadApk(
                        info.apkUrl, target,
                    ) { downloaded, total ->
                        val pct = if (total > 0) downloaded.toFloat() / total else -1f
                        _updateProgress.value = pct
                    }
                    target
                }
                _updateProgress.value = 1f
                // 下载完成：关闭弹框并拉起系统安装
                _updateInfo.value = null
                _updateError.value = null
                installApk(ctx, file)
            } catch (e: Exception) {
                // 下载失败：在弹框内展示错误，保留弹框让用户可重试
                _updateError.value = e.message ?: "未知错误"
            } finally {
                _updateBusy.value = false
            }
        }
    }

    /** 拉起系统安装 APK（需要应用有「安装未知来源应用」权限） */
    private fun installApk(context: android.content.Context, file: File) {
        val intent = android.content.Intent(android.content.Intent.ACTION_VIEW)
        val uri = androidx.core.content.FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file,
        )
        intent.setDataAndType(uri, "application/vnd.android.package-archive")
        intent.addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
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
     * 导入云端最新打标表（v1.8.2.0）。
     *
     * 有清单就用清单里的文件名（并加时间戳绕 CDN 缓存）；清单拿不到就退回默认整表地址，
     * 保证「云端更新了但清单接口抽风」时导入仍然可用。
     */
    fun importCloudLatest(manifestUrl: String = com.novelreader.data.repository.TagIndexRepository.DEFAULT_CLOUD_MANIFEST_URL) {
        val repo = tagIndexRepository ?: run {
            _backupMessage.value = "当前环境不支持标签导入"
            return
        }
        if (_tagImportBusy.value) return
        viewModelScope.launch {
            _tagImportBusy.value = true
            _backupMessage.value = runCatching {
                val manifest = _cloudManifest.value ?: repo.fetchCloudManifest(manifestUrl)
                    .also { _cloudManifest.value = it }
                val url = if (manifest != null) {
                    com.novelreader.data.repository.TagIndexRepository.tableUrlOf(manifestUrl, manifest.latest)
                } else {
                    com.novelreader.data.repository.TagIndexRepository.DEFAULT_CLOUD_TABLE_URL
                }
                // 十几 MB，超时要给够（慢网下 30 秒会被掐断）
                val json = withContext(Dispatchers.IO) {
                    com.novelreader.core.network.HttpClient.get(url, timeoutMs = 180_000)
                }
                val payload = com.novelreader.core.util.TagTable.parse(json)
                    ?: error("云端打标表解析失败（可能拿到的是错误页）")
                // 条数校验（v1.8.2.0）：CDN 可能还挂着上一版的文件（实测 jsDelivr 按 URL
                // 缓存最长 7 天），不校验的话会「导入成功 0 条」，用户以为导入过了其实没有。
                val expected = manifest?.count ?: 0
                if (expected > 0 && payload.items.size != expected) {
                    error(
                        "云端表条数与版本清单不符（清单 $expected 条，实际下载到 ${payload.items.size} 条），" +
                            "大概率是 CDN 缓存还没刷新，请稍后重试",
                    )
                }
                var imported = 0
                for (type in com.novelreader.domain.model.SourceType.entries) {
                    imported += repo.importTagTable(payload, type)
                }
                preferencesManager.setTagCloudImport(payload.generatedAt, payload.items.size)
                _cloudImported.value = payload.generatedAt to payload.items.size
                "标签导入完成：$imported 部作品已合并（云端 ${payload.items.size} 条）"
            }.getOrElse { "标签导入失败：${it.message ?: "未知错误"}" }
            _tagImportBusy.value = false
        }
    }

    /**
     * 从本地文件导入打标表（SAF Uri）。
     *
     * 表里条目按 `itemType` 分流，小说 / 漫画 / 视频各自合并进对应类型的标签层；
     * 合并口径与标签搜索页一致：同名标签取更高置信度，站方 100 分不会被顶掉。
     */
    fun importTagTableFromUri(uri: android.net.Uri) {
        val repo = tagIndexRepository ?: run {
            _backupMessage.value = "当前环境不支持标签导入"
            return
        }
        val ctx = appContext ?: return
        if (_tagImportBusy.value) return
        viewModelScope.launch {
            _tagImportBusy.value = true
            _backupMessage.value = runCatching {
                val json = withContext(Dispatchers.IO) {
                    ctx.contentResolver.openInputStream(uri)?.use { stream ->
                        stream.readBytes().toString(Charsets.UTF_8)
                    } ?: error("无法读取所选文件")
                }
                importPayload(repo, json)
            }.getOrElse { "标签导入失败：${it.message ?: "未知错误"}" }
            _tagImportBusy.value = false
        }
    }

    /** 从云端 URL 导入打标表（默认独立打标仓库 leimao2333/leimao-tagger 的 data/tag_table.json，经 jsDelivr 分发） */
    fun importTagTableFromUrl(url: String) {
        val repo = tagIndexRepository ?: run {
            _backupMessage.value = "当前环境不支持标签导入"
            return
        }
        val target = url.trim()
        if (target.isEmpty() || _tagImportBusy.value) return
        viewModelScope.launch {
            _tagImportBusy.value = true
            _backupMessage.value = runCatching {
                val json = withContext(Dispatchers.IO) {
                    com.novelreader.core.network.HttpClient.get(target, timeoutMs = 180_000)
                }
                importPayload(repo, json, rememberAsCloud = true)
            }.getOrElse { "标签导入失败：${it.message ?: "未知错误"}" }
            _tagImportBusy.value = false
        }
    }

    /** 解析打标表 JSON 并对全部类型执行幂等合并，返回结果消息 */
    private suspend fun importPayload(
        repo: com.novelreader.data.repository.TagIndexRepository,
        json: String,
        /**
         * 是否把这次导入记为「云端已导入版本」（v1.8.2.0）。
         *
         * 只有从网络来的才算 —— 本地文件很可能是自己导出备份的旧表，
         * 把它记成云端版本会让「有没有新版本」的判断失真。
         */
        rememberAsCloud: Boolean = false,
    ): String {
        val payload = com.novelreader.core.util.TagTable.parse(json)
            ?: error("打标表解析失败（不是有效的 tag_table.json）")
        var imported = 0
        for (type in com.novelreader.domain.model.SourceType.entries) {
            imported += repo.importTagTable(payload, type)
        }
        if (rememberAsCloud) {
            preferencesManager.setTagCloudImport(payload.generatedAt, payload.items.size)
            _cloudImported.value = payload.generatedAt to payload.items.size
        }
        return "标签导入完成：$imported 部作品已合并进本地标签层（云端 ${payload.items.size} 条）"
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
                val json = withContext(Dispatchers.IO) {
                    val types = com.novelreader.domain.model.SourceType.entries
                    val allItems = buildList {
                        types.forEachIndexed { index, type ->
                            val base = index.toFloat() / types.size
                            // v1.8.1.9：逐条回报进度，三个类型各占总进度的 1/3
                            addAll(
                                repo.buildTagTable(type) { done, total ->
                                    val frac = if (total > 0) done.toFloat() / total else 1f
                                    _tagExportProgress.value = base + frac / types.size
                                }.items,
                            )
                        }
                    }
                    val payload = com.novelreader.core.util.TagTable.Payload(
                        version = com.novelreader.core.util.TagTable.VERSION,
                        generatedAt = System.currentTimeMillis(),
                        source = com.novelreader.core.util.TagTable.SOURCE_APP,
                        items = allItems,
                    )
                    com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(payload)
                }
                val written = withContext(Dispatchers.IO) {
                    ctx.contentResolver.openOutputStream(uri, "wt")?.use { stream ->
                        stream.write(json.toByteArray(Charsets.UTF_8))
                        true
                    } ?: error("无法写入所选文件")
                }
                if (written) "标签导出完成：${json.length / 1024} KB（小说/漫画/视频全类型）" else "导出失败"
            }.getOrElse { "标签导出失败：${it.message ?: "未知错误"}" }
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

    // ===== 视频播放（v1.3.2.0） =====
    /** 进入播放页默认全屏 */
    private val _videoFullscreenDefault = MutableStateFlow(false)
    val videoFullscreenDefault: StateFlow<Boolean> = _videoFullscreenDefault.asStateFlow()

    /** 长按倍速档位（×10） */
    private val _videoLongPressSpeed = MutableStateFlow(PreferencesManager.DEFAULT_VIDEO_LONG_PRESS_SPEED)
    val videoLongPressSpeed: StateFlow<Int> = _videoLongPressSpeed.asStateFlow()

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
            preferencesManager.hideAdultFlow.collect { _hideAdult.value = it }
        }
    }

    /** 切换「隐藏成人内容」开关 */
    fun setHideAdult(value: Boolean) {
        viewModelScope.launch { preferencesManager.setHideAdult(value) }
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
}
