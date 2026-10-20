package com.novelreader.ui.screens.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.novelreader.core.preferences.PreferencesManager
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.novelreader.ui.theme.ReaderBackgrounds

/**
 * 设置中心。
 *
 * 集中管理全局设置：日夜间模式、小说字体/背景、漫画翻页方式。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    onBack: () -> Unit,
    onOpenChangelog: () -> Unit = {},
    onOpenCacheManage: () -> Unit = {},
    onOpenSourceImport: () -> Unit = {},
    onOpenSourceManage: () -> Unit = {},
) {
    val darkTheme by viewModel.darkTheme.collectAsState()
    val backupMessage by viewModel.backupMessage.collectAsState()
    val snackbarHostState = remember { androidx.compose.material3.SnackbarHostState() }
    val context = androidx.compose.ui.platform.LocalContext.current

    // 备份导出（SAF CREATE_DOCUMENT）
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { uri -> uri?.let { viewModel.exportBackup(it) } }

    // 备份导入（SAF OPEN_DOCUMENT）
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri -> uri?.let { viewModel.importBackup(it) } }

    LaunchedEffect(backupMessage) {
        backupMessage?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.dismissBackupMessage()
        }
    }
    val darkThemeMode by viewModel.darkThemeMode.collectAsState()
    val novelFontSize by viewModel.novelFontSize.collectAsState()
    val novelBgIndex by viewModel.novelBgIndex.collectAsState()
    val comicPageMode by viewModel.comicPageMode.collectAsState()
    val comicPreloadCount by viewModel.comicPreloadCount.collectAsState()
    val comicPreloadWifiOnly by viewModel.comicPreloadWifiOnly.collectAsState()
    val comicEdgeGesture by viewModel.comicEdgeGesture.collectAsState()
    val searchConcurrency by viewModel.searchConcurrency.collectAsState()
    val videoFullscreenDefault by viewModel.videoFullscreenDefault.collectAsState()
    val videoLongPressSpeed by viewModel.videoLongPressSpeed.collectAsState()
    val videoLongPressRewind by viewModel.videoLongPressRewind.collectAsState()
    val novelPreloadChapters by viewModel.novelPreloadChapters.collectAsState()
    val videoDoubleTapSeek by viewModel.videoDoubleTapSeek.collectAsState()
    val videoAutoNext by viewModel.videoAutoNext.collectAsState()
    val videoVerticalSwipeSwitch by viewModel.videoVerticalSwipeSwitch.collectAsState()
    val videoPipEnabled by viewModel.videoPipEnabled.collectAsState()
    val videoBufferSeconds by viewModel.videoBufferSeconds.collectAsState()
    val videoQuality by viewModel.videoQuality.collectAsState()
    val videoPrefetchWifiOnly by viewModel.videoPrefetchWifiOnly.collectAsState()
    val hideAdult by viewModel.hideAdult.collectAsState()
    val novelShelfPrompt by viewModel.novelShelfPrompt.collectAsState()
    val comicShelfPrompt by viewModel.comicShelfPrompt.collectAsState()
    val searchLongPressShelf by viewModel.searchLongPressShelf.collectAsState()

    // 应用更新（v1.5.4.0）
    val updateBusy by viewModel.updateBusy.collectAsState()
    val updateProgress by viewModel.updateProgress.collectAsState()
    val updateInfo by viewModel.updateInfo.collectAsState()
    val updateError by viewModel.updateError.collectAsState()
    val updateState by viewModel.updateState.collectAsState()

    // Android 13+ 通知权限：前台下载服务没有通知也能跑，但用户看不到进度，开始下载前问一次
    val notifyPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { viewModel.downloadUpdate() }
    val requestDownload: () -> Unit = {
        val permission = android.Manifest.permission.POST_NOTIFICATIONS
        val granted = android.os.Build.VERSION.SDK_INT < 33 ||
            androidx.core.content.ContextCompat.checkSelfPermission(context, permission) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
        if (granted) viewModel.downloadUpdate() else notifyPermissionLauncher.launch(permission)
    }

    Scaffold(
        snackbarHost = { androidx.compose.material3.SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text("设置") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
        ) {
            // ===== 全局 =====
            SectionTitle("全局")
            // 深色模式：关闭 / 开启 / 跟随系统（#36，三选一，跟随系统可随日夜自动切换）
            SettingRow(
                title = "夜间模式",
                subtitle = "关闭 / 开启 / 跟随系统（自动随系统昼夜切换）",
            ) {
                Row {
                    listOf(
                        "关闭" to PreferencesManager.THEME_MODE_LIGHT,
                        "开启" to PreferencesManager.THEME_MODE_DARK,
                        "跟随系统" to PreferencesManager.THEME_MODE_SYSTEM,
                    ).forEach { (label, mode) ->
                        FilterChip(
                            selected = darkThemeMode == mode,
                            onClick = { viewModel.setDarkThemeMode(mode) },
                            label = { Text(label) },
                            modifier = Modifier.padding(start = 4.dp),
                        )
                    }
                }
            }

            Spacer(Modifier.height(24.dp))

            // ===== 小说 =====
            SectionTitle("小说阅读")
            Text(
                text = "字体大小：${novelFontSize.toInt()}",
                style = MaterialTheme.typography.bodyMedium,
            )
            Slider(
                value = novelFontSize,
                onValueChange = { viewModel.setNovelFontSize(it) },
                valueRange = 14f..26f,
                steps = 5,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = "背景颜色",
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                ReaderBackgrounds.dayColors.forEachIndexed { index, rc ->
                    ColorSwatch(
                        color = rc.background,
                        name = rc.name,
                        selected = novelBgIndex == index,
                        onClick = { viewModel.setNovelBgIndex(index) },
                    )
                }
            }

            // 预加载章数（v1.8.3.0）：0=关闭，避免流量/内存紧张时仍强制预取
            Spacer(Modifier.height(16.dp))
            Text(
                text = if (novelPreloadChapters == 0) "预加载章节：关闭"
                else "预加载章节：$novelPreloadChapters 章",
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                text = "阅读时向后预取接下来 N 章，越大翻章越顺但更耗流量；设为 0 可完全关闭",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Slider(
                value = novelPreloadChapters.toFloat(),
                onValueChange = { viewModel.setNovelPreloadChapters(it.toInt()) },
                valueRange = 0f..5f,
                steps = 4,
            )
            SettingRow(
                title = "未加入书架时提示",
                subtitle = "打开未收藏的小说时询问是否加入书架",
            ) {
                Switch(
                    checked = novelShelfPrompt,
                    onCheckedChange = { viewModel.setNovelShelfPrompt(it) },
                )
            }

            Spacer(Modifier.height(24.dp))

            // ===== 漫画 =====
            SectionTitle("漫画阅读")
            Text(
                text = "翻页方式",
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                PageModeChip(
                    label = "上下滚动",
                    selected = comicPageMode == 0,
                    onClick = { viewModel.setComicPageMode(0) },
                )
                PageModeChip(
                    label = "左右翻页",
                    selected = comicPageMode == 1,
                    onClick = { viewModel.setComicPageMode(1) },
                )
            }

            Spacer(Modifier.height(16.dp))
            Text(
                text = "预加载张数：$comicPreloadCount",
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                text = "阅读时提前加载后续图片的张数，越大越流畅但更耗流量",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Slider(
                value = comicPreloadCount.toFloat(),
                onValueChange = { viewModel.setComicPreloadCount(it.toInt()) },
                valueRange = 1f..8f,
                steps = 6,
            )
            SettingRow(
                title = "仅在 WiFi 下预加载",
                subtitle = "移动网络下不提前加载，节省流量",
            ) {
                Switch(
                    checked = comicPreloadWifiOnly,
                    onCheckedChange = { viewModel.setComicPreloadWifiOnly(it) },
                )
            }

            Spacer(Modifier.height(8.dp))
            SettingRow(
                title = "边缘滑动调亮度 / 音量",
                subtitle = "阅读时在屏幕左 / 右边缘竖滑调亮度或音量。可能与上下滚动、左右翻页误触，默认关闭",
            ) {
                Switch(
                    checked = comicEdgeGesture,
                    onCheckedChange = { viewModel.setComicEdgeGesture(it) },
                )
            }
            SettingRow(
                title = "未加入书架时提示",
                subtitle = "打开未收藏的漫画时询问是否加入书架",
            ) {
                Switch(
                    checked = comicShelfPrompt,
                    onCheckedChange = { viewModel.setComicShelfPrompt(it) },
                )
            }

            Spacer(Modifier.height(24.dp))

            // ===== 搜索 =====
            SectionTitle("搜索")
            Text(
                text = "并行搜索线程数：$searchConcurrency",
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                text = "同时搜索的源数量，数值越大越快但更耗流量/内存",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Slider(
                value = searchConcurrency.toFloat(),
                onValueChange = { viewModel.setSearchConcurrency(it.toInt()) },
                valueRange = 1f..16f,
                steps = 15,
            )
            Spacer(Modifier.height(8.dp))
            SettingRow(
                title = "长按搜索结果加入书架",
                subtitle = "长按结果弹出菜单，可直接收藏 / 阅读 / 查看详情；关闭后只保留点击与收藏按钮（v1.8.3.0）",
            ) {
                Switch(
                    checked = searchLongPressShelf,
                    onCheckedChange = { viewModel.setSearchLongPressShelf(it) },
                )
            }

            Spacer(Modifier.height(24.dp))

            // ===== 内容过滤（v1.7.5.0） =====
            SectionTitle("内容过滤")
            SettingRow(
                title = "隐藏成人内容",
                subtitle = "首页推荐、搜索、标签结果里不再出现 18+ 成人源的内容（默认开启）",
            ) {
                Switch(
                    checked = hideAdult,
                    onCheckedChange = { viewModel.setHideAdult(it) },
                )
            }

            Spacer(Modifier.height(24.dp))

            // ===== 视频播放（v1.3.2.0） =====
            SectionTitle("视频播放")
            SettingRow(
                title = "进入即全屏",
                subtitle = "打开视频时直接横屏沉浸式播放",
            ) {
                Switch(
                    checked = videoFullscreenDefault,
                    onCheckedChange = { viewModel.setVideoFullscreenDefault(it) },
                )
            }
            SettingRow(
                title = "长按倍速",
                subtitle = if (videoLongPressRewind) "长按右半屏以该倍速快进，左半屏连续快退（松手恢复）"
                else "长按左右半屏均以该倍速快进（长按快退已关闭）",
            ) {
                Row {
                    listOf(15 to "1.5x", 20 to "2.0x", 30 to "3.0x").forEach { (value, label) ->
                        PageModeChip(
                            label = label,
                            selected = videoLongPressSpeed == value,
                            onClick = { viewModel.setVideoLongPressSpeed(value) },
                        )
                        Spacer(Modifier.width(8.dp))
                    }
                }
            }
            SettingRow(
                title = "长按左侧快退",
                subtitle = "关闭后长按左半屏也变成快进，左右两侧行为一致（不影响双击快退）",
            ) {
                Switch(
                    checked = videoLongPressRewind,
                    onCheckedChange = { viewModel.setVideoLongPressRewind(it) },
                )
            }
            SettingRow(
                title = "双击快进步长",
                subtitle = "双击左 / 右半屏快退 / 快进的秒数",
            ) {
                Row {
                    listOf(5, 10, 15, 30).forEach { sec ->
                        PageModeChip(
                            label = "${sec}s",
                            selected = videoDoubleTapSeek == sec,
                            onClick = { viewModel.setVideoDoubleTapSeek(sec) },
                        )
                        Spacer(Modifier.width(6.dp))
                    }
                }
            }
            SettingRow(
                title = "播完连播下一集",
                subtitle = "本集播放结束后自动播放下一集",
            ) {
                Switch(
                    checked = videoAutoNext,
                    onCheckedChange = { viewModel.setVideoAutoNext(it) },
                )
            }
            SettingRow(
                title = "横屏上下滑切集",
                subtitle = "横屏全屏时上下滑切换上一集 / 下一集（关闭后恢复亮度 / 音量手势）",
            ) {
                Switch(
                    checked = videoVerticalSwipeSwitch,
                    onCheckedChange = { viewModel.setVideoVerticalSwipeSwitch(it) },
                )
            }
            SettingRow(
                title = "小窗播放",
                subtitle = "播放中切到后台时自动进入画中画小窗（默认关闭）",
            ) {
                Switch(
                    checked = videoPipEnabled,
                    onCheckedChange = { viewModel.setVideoPipEnabled(it) },
                )
            }

            // 预缓存时长（v1.8.4.0，待办 #8）：控制 ExoPlayer 向后缓冲多久
            Spacer(Modifier.height(16.dp))
            Text(
                text = if (videoBufferSeconds == 0) "预缓存时长：关闭"
                else "预缓存时长：${videoBufferSeconds} 秒",
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                text = "播放时向后缓冲多久：越大越抗卡顿（回退、快进都读本地），但更占内存与流量；关闭后只留最小缓冲",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                com.novelreader.core.media.VideoBufferOptions.CHOICES.forEach { sec ->
                    PageModeChip(
                        label = if (sec == 0) "关" else "${sec}s",
                        selected = videoBufferSeconds == sec,
                        onClick = { viewModel.setVideoBufferSeconds(sec) },
                    )
                }
            }

            // 尾段预缓冲仅在 WiFi（v1.8.4.4）：关掉预缓冲的省流量开关在这里兜第二道闸
            SettingRow(
                title = "尾段预缓冲仅 WiFi",
                subtitle = "一集快播完时提前把下一集开头几 MB 拉进本地缓存（v1.8.4.3）。开启时只在 WiFi 下预缓冲；关闭后用流量也预缓冲，切集秒开",
            ) {
                Switch(
                    checked = videoPrefetchWifiOnly,
                    onCheckedChange = { viewModel.setVideoPrefetchWifiOnly(it) },
                )
            }

            // 默认清晰度（v1.8.4.0，待办 #13）
            Spacer(Modifier.height(16.dp))
            Text(
                text = "默认清晰度：${com.novelreader.core.util.VideoQualityTag.label(videoQuality)}",
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                text = "多码率源起播时自动锁到最接近的档位；「换源」列表按此偏好排序并标出清晰度角标（播放器里手动选择会记成新的默认）",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                com.novelreader.core.util.VideoQualityTag.CHOICES.forEach { height ->
                    PageModeChip(
                        label = com.novelreader.core.util.VideoQualityTag.label(height),
                        selected = videoQuality == height,
                        onClick = { viewModel.setVideoQuality(height) },
                    )
                }
            }

            Spacer(Modifier.height(24.dp))

            // ===== 书源（v1.8.0.0）=====
            // 导入 / 管理原先只在源管理页里，设置页没有入口 —— 用户要加源得先找到
            // 首页的来源管理入口。这里补上直达入口，导入与后续管理都能从设置走。
            SectionTitle("书源")
            SettingRow(
                title = "导入书源",
                subtitle = "从本地文件导入 JSON / TXT / JS 源，也可直接粘贴内容或填网址批量下载",
            ) {
                TextButton(onClick = onOpenSourceImport) { Text("导入") }
            }
            SettingRow(
                title = "书源管理",
                subtitle = "启用 / 停用 / 删除已导入的源，查看每个源的可用状态",
            ) {
                TextButton(onClick = onOpenSourceManage) { Text("管理") }
            }

            Spacer(Modifier.height(24.dp))

            // ===== 标签（v1.8.1.6）=====
            // 打标表导入原先只在标签搜索页的索引弹窗里，入口太深 —— 设置页补一个直达入口，
            // 与 PC 打标工具共用同一份 tag_table.json（同名标签取更高置信度，幂等合并）。
            SectionTitle("标签")
            val tagImportBusy by viewModel.tagImportBusy.collectAsState()
            val tagImportProgress by viewModel.tagImportProgress.collectAsState()
            val tagExportProgress by viewModel.tagExportProgress.collectAsState()
            var showTagUrlDialog by remember { mutableStateOf(false) }
            val tagImportLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.OpenDocument(),
            ) { uri -> uri?.let { viewModel.importTagTableFromUri(it) } }
            SettingRow(
                title = "标签导入",
                subtitle = if (tagImportBusy) "导入中…" else
                    "导入打标表（本地文件或云端地址），与 PC 打标工具的成果合并进本地标签层",
            ) {
                Row {
                    TextButton(
                        onClick = { showTagUrlDialog = true },
                        enabled = !tagImportBusy,
                    ) { Text("云端") }
                    TextButton(
                        onClick = {
                            tagImportLauncher.launch(
                                arrayOf("application/json", "text/*", "application/octet-stream"),
                            )
                        },
                        enabled = !tagImportBusy,
                    ) { Text("本地") }
                }
            }
            if (showTagUrlDialog) {
                // v1.8.2.0：先把云端**清单**（几百字节）拉下来，告诉用户「有多少条、比我这新不新」，
                // 确认要更新了才去下十几 MB 的整表 —— 不然每次点进来都白跑十几 MB
                val cloudManifest by viewModel.cloudManifest.collectAsState()
                val cloudChecking by viewModel.cloudChecking.collectAsState()
                val cloudImported by viewModel.cloudImported.collectAsState()
                var tagUrl by remember {
                    mutableStateOf(
                        com.novelreader.data.repository.TagIndexRepository.DEFAULT_CLOUD_MANIFEST_URL,
                    )
                }
                var showAdvanced by remember { mutableStateOf(false) }
                LaunchedEffect(Unit) { viewModel.checkCloudTags(tagUrl) }

                androidx.compose.material3.AlertDialog(
                    onDismissRequest = { showTagUrlDialog = false },
                    title = { Text("云端标签导入") },
                    text = {
                        Column {
                            if (cloudChecking) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    androidx.compose.material3.CircularProgressIndicator(
                                        modifier = Modifier.size(16.dp),
                                        strokeWidth = 2.dp,
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Text(
                                        "正在读取云端版本…",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            } else {
                                val m = cloudManifest
                                if (m == null) {
                                    Text(
                                        "读不到云端版本信息（网络或 CDN 缓存）。仍可直接点「导入」，按默认地址下载整表。",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                } else {
                                    Text(
                                        "云端 ${m.count} 条作品 · ${m.sources} 个源",
                                        style = MaterialTheme.typography.bodyMedium,
                                    )
                                    Text(
                                        "其中有标签 ${m.taggedCount} 条 · " +
                                            m.types.entries.joinToString(" · ") { (k, v) -> "${typeName(k)} $v" },
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    Text(
                                        "生成于 ${formatCloudTime(m.generatedAt)}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    Spacer(Modifier.height(6.dp))
                                    if (cloudImported.second > 0) {
                                        val newer = m.generatedAt > cloudImported.first
                                        Text(
                                            if (newer) {
                                                "本地上次导入 ${cloudImported.second} 条（${formatCloudTime(cloudImported.first)}），发现新版本"
                                            } else {
                                                "本地上次导入 ${cloudImported.second} 条（${formatCloudTime(cloudImported.first)}），已是最新"
                                            },
                                            style = MaterialTheme.typography.bodySmall,
                                            color = if (newer) {
                                                MaterialTheme.colorScheme.primary
                                            } else {
                                                MaterialTheme.colorScheme.onSurfaceVariant
                                            },
                                        )
                                    } else {
                                        Text(
                                            "本地还没有导入过云端标签表",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.primary,
                                        )
                                    }
                                }
                            }

                            Spacer(Modifier.height(8.dp))
                            Text(
                                "导入按「同名标签取更高置信度」合并，站方证据不会被覆盖。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
                            )

                            // 高级：换成别的清单地址（自建镜像 / 别的仓库）
                            Text(
                                if (showAdvanced) "收起地址设置" else "自定义地址…",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier
                                    .padding(top = 6.dp)
                                    .clickable { showAdvanced = !showAdvanced },
                            )
                            if (showAdvanced) {
                                androidx.compose.material3.OutlinedTextField(
                                    value = tagUrl,
                                    onValueChange = { tagUrl = it },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(top = 4.dp),
                                    singleLine = false,
                                    maxLines = 3,
                                    textStyle = MaterialTheme.typography.bodySmall,
                                    label = { Text("版本清单地址（manifest.json）") },
                                )
                            }
                        }
                    },
                    confirmButton = {
                        TextButton(
                            onClick = {
                                showTagUrlDialog = false
                                viewModel.importCloudLatest(tagUrl)
                            },
                            enabled = tagUrl.isNotBlank() && !tagImportBusy,
                        ) { Text(if (tagImportBusy) "导入中…" else "导入") }
                    },
                    dismissButton = {
                        Row {
                            TextButton(
                                onClick = { viewModel.checkCloudTags(tagUrl) },
                                enabled = !cloudChecking && !tagImportBusy,
                            ) { Text("刷新") }
                            TextButton(onClick = { showTagUrlDialog = false }) { Text("取消") }
                        }
                    },
                )
            }
            // 导入进度弹窗（v1.8.3.0 待办 #17）：下载按字节、写库按分块，取消按钮只在可取消阶段可点
            tagImportProgress?.let { progress ->
                TagImportProgressDialog(
                    progress = progress,
                    onCancel = { viewModel.cancelTagImport() },
                )
            }

            // v1.8.1.7：标签导出 —— 本地标签层整体导出为 tag_table.json，可直接给 PC 打标工具当基底
            val tagExportLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.CreateDocument("application/json"),
            ) { uri -> uri?.let { viewModel.exportTagTableToUri(it) } }
            SettingRow(
                title = "标签导出",
                subtitle = if (tagImportBusy) "导出中…" else
                    "把本地标签层（小说/漫画/视频全类型）导出为 tag_table.json，可交给 PC 打标工具",
            ) {
                TextButton(
                    onClick = {
                        tagExportLauncher.launch(
                            "leimao-tag-table-" +
                                java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.CHINA)
                                    .format(java.util.Date()) + ".json",
                        )
                    },
                    enabled = !tagImportBusy,
                ) { Text("导出") }
            }
            // v1.8.1.9：导出进度条（小说/漫画/视频逐条构建，完成后自动隐藏）
            if (tagImportBusy && tagExportProgress >= 0f) {
                androidx.compose.material3.LinearProgressIndicator(
                    progress = { tagExportProgress },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                        .padding(bottom = 8.dp),
                )
            }

            Spacer(Modifier.height(24.dp))

            // ===== 存储 =====
            SectionTitle("存储")
            SettingRow(
                title = "缓存管理",
                subtitle = "查看与清理本地已缓存章节",
            ) {
                TextButton(onClick = onOpenCacheManage) { Text("管理") }
            }
            SettingRow(
                title = "备份与恢复",
                subtitle = "书源 / 书架 / 进度 / 书签 / 替换规则导出为 JSON，可跨设备恢复",
            ) {
                Row {
                    TextButton(onClick = {
                        val name = "leimao-backup-" +
                            java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.CHINA)
                                .format(java.util.Date()) + ".json"
                        exportLauncher.launch(name)
                    }) { Text("导出") }
                    TextButton(onClick = {
                        importLauncher.launch(arrayOf("application/json", "text/*", "application/octet-stream"))
                    }) { Text("导入") }
                }
            }

            // ===== WebDav 云备份（v1.3.8.0） =====
            val webdavConfig by viewModel.webdavConfig.collectAsState()
            val webdavBusy by viewModel.webdavBusy.collectAsState()
            var showWebDavDialog by remember { mutableStateOf(false) }

            SettingRow(
                title = "WebDav 云备份",
                subtitle = if (webdavConfig.isConfigured) webdavConfig.url else "配置后可将备份上传 / 下载到 WebDav 服务器，实现多设备同步",
            ) {
                Row {
                    TextButton(onClick = { showWebDavDialog = true }) { Text("配置") }
                    TextButton(
                        onClick = { viewModel.testWebDavConnection() },
                        enabled = webdavConfig.isConfigured && !webdavBusy,
                    ) { Text("测试") }
                }
            }

            if (webdavConfig.isConfigured) {
                SettingRow(
                    title = "   · 同步操作",
                    subtitle = if (webdavBusy) "操作进行中…" else "上传备份到服务器 / 从服务器下载并恢复",
                ) {
                    Row {
                        TextButton(
                            onClick = { viewModel.uploadToWebDav() },
                            enabled = !webdavBusy,
                        ) { Text("上传") }
                        TextButton(
                            onClick = { viewModel.downloadFromWebDav() },
                            enabled = !webdavBusy,
                        ) { Text("下载") }
                    }
                }
            }

            if (showWebDavDialog) {
                WebDavConfigDialog(
                    initial = webdavConfig,
                    onDismiss = { showWebDavDialog = false },
                    onSave = { cfg ->
                        viewModel.saveWebDavConfig(cfg)
                        showWebDavDialog = false
                    },
                )
            }

            Spacer(Modifier.height(24.dp))

            // ===== 关于 =====
            SectionTitle("关于")
            SettingRow(
                title = "当前版本",
                subtitle = "v${com.novelreader.core.version.AppVersion.CURRENT_VERSION}",
            ) {
                TextButton(onClick = onOpenChangelog) { Text("更新日志") }
            }
            SettingRow(
                title = "检查更新",
                subtitle = "从 GitHub 检测最新版本",
            ) {
                TextButton(
                    onClick = { viewModel.checkUpdate() },
                    enabled = !updateBusy,
                ) {
                    if (updateBusy && updateProgress < 0f) {
                        androidx.compose.material3.CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                        )
                    } else {
                        Text("检查更新")
                    }
                }
            }
            // 下载进度（下载 APK 时显示；退出本页再回来仍在——状态在进程级 Manager 里）
            if (updateState.running) {
                val pct = updateState.progress
                Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                    Text(
                        text = if (pct >= 0f) {
                            "正在后台下载 v${updateState.version}… ${(pct * 100).toInt()}%" +
                                if (updateState.attempt > 0) "（第 ${updateState.attempt} 次续传）" else ""
                        } else {
                            "正在后台下载 v${updateState.version}…"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(6.dp))
                    if (pct < 0f) {
                        androidx.compose.material3.LinearProgressIndicator(
                            modifier = Modifier.fillMaxWidth(),
                        )
                    } else {
                        androidx.compose.material3.LinearProgressIndicator(
                            progress = { pct.coerceIn(0f, 1f) },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
        }

        // ===== 更新确认弹框（v1.5.4.0；v1.5.5.4 增加下载进度与失败反馈） =====
        // v1.8.3.0：下载改为前台服务 + 断点续传，弹框随时可关（关掉 = 转后台继续下）
        updateInfo?.let { info ->
            val downloading = updateBusy
            val pct = updateState.progress
            androidx.compose.material3.AlertDialog(
                onDismissRequest = { viewModel.dismissUpdateInfo() },
                title = {
                    Text(
                        when {
                            downloading -> "正在下载 v${info.version}"
                            else -> "发现新版本 v${info.version}"
                        }
                    )
                },
                text = {
                    Column {
                        if (downloading) {
                            // 下载中：显示进度条 + 百分比
                            Text(
                                text = when {
                                    pct < 0f -> "正在连接下载服务器…"
                                    pct >= 1f -> "下载完成，正在校验安装包…"
                                    else -> "正在下载… ${(pct * 100).toInt()}%"
                                },
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            Spacer(Modifier.height(12.dp))
                            if (pct < 0f) {
                                androidx.compose.material3.LinearProgressIndicator(
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            } else {
                                androidx.compose.material3.LinearProgressIndicator(
                                    progress = { pct.coerceIn(0f, 1f) },
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            }
                            Spacer(Modifier.height(4.dp))
                            if (updateState.total > 0) {
                                Text(
                                    text = "已下载 ${formatMb(updateState.downloaded)} / " +
                                        "${formatMb(updateState.total)} MB" +
                                        if (updateState.attempt > 0) " · 第 ${updateState.attempt} 次续传" else "",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Spacer(Modifier.height(8.dp))
                            Text(
                                text = "下载在后台进行，关掉本页或切到其它应用都不会中断，通知栏可看进度",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        } else {
                            // 未下载：显示更新说明 + 大小
                            val body = info.body.trim()
                            if (body.isNotEmpty()) {
                                val brief = if (body.length > 300) body.take(300) + "…" else body
                                Text(
                                    text = brief,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            if (info.apkSize > 0) {
                                Spacer(Modifier.height(8.dp))
                                Text(
                                    text = "安装包大小：${"%.1f".format(info.apkSize / 1024f / 1024f)} MB",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            // 下载失败提示（重试时显示在弹框内）
                            if (updateError != null) {
                                Spacer(Modifier.height(12.dp))
                                Text(
                                    text = "下载失败：$updateError",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error,
                                )
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(
                        onClick = { requestDownload() },
                        enabled = !downloading,
                    ) {
                        Text(
                            when {
                                downloading -> "下载中…"
                                updateError != null -> "重试"
                                else -> "下载更新"
                            }
                        )
                    }
                },
                dismissButton = {
                    TextButton(onClick = { viewModel.dismissUpdateInfo() }) {
                        // 下载中不叫「取消」——点了只是收起弹框，下载继续
                        Text(if (downloading) "后台下载" else "取消")
                    }
                },
            )
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.primary,
    )
    Spacer(Modifier.height(12.dp))
}

@Composable
private fun SettingRow(
    title: String,
    subtitle: String? = null,
    trailing: @Composable () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.bodyLarge)
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        trailing()
    }
}

@Composable
private fun ColorSwatch(
    color: Color,
    name: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(color)
                .clickable(onClick = onClick),
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = if (selected) "$name ✓" else name,
            style = MaterialTheme.typography.labelSmall,
            color = if (selected) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun PageModeChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val bg = if (selected) MaterialTheme.colorScheme.primary
    else MaterialTheme.colorScheme.surfaceVariant
    val fg = if (selected) MaterialTheme.colorScheme.onPrimary
    else MaterialTheme.colorScheme.onSurfaceVariant
    Text(
        text = label,
        modifier = Modifier
            .clip(CircleShape)
            .background(bg)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        color = fg,
        style = MaterialTheme.typography.bodyMedium,
    )
}

/** 云端清单里的类型键 → 中文（`NOVEL` → 小说；v1.8.2.0） */
private fun typeName(key: String): String = when (key.uppercase()) {
    "NOVEL" -> "小说"
    "COMIC" -> "漫画"
    "VIDEO" -> "视频"
    else -> key
}

/** 毫秒时间戳 → `yyyy-MM-dd HH:mm`；0 或非法值显示「未知」 */
private fun formatCloudTime(ms: Long): String =
    if (ms <= 0L) {
        "未知"
    } else {
        java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.CHINA)
            .format(java.util.Date(ms))
    }

/**
 * 标签导入进度弹窗（v1.8.3.0 待办 #17）。
 *
 * 三段式：下载（字节）→ 解析 → 写库（分块条数）。
 * 写库阶段不给取消 —— 那一步已经在往 Room 里落数据，
 * 允许中途退出只会留下「local_tags 写了、tag_instances 没写」的半截状态。
 */
@Composable
private fun TagImportProgressDialog(
    progress: TagImportProgress,
    onCancel: () -> Unit,
) {
    val percent = (progress.fraction * 100f).toInt().coerceIn(0, 100)
    androidx.compose.material3.AlertDialog(
        onDismissRequest = { if (progress.cancelable) onCancel() },
        title = { Text("正在导入标签") },
        text = {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = progress.phase.label,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = if (progress.indeterminate) "--" else "$percent%",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                Spacer(Modifier.height(8.dp))
                if (progress.indeterminate) {
                    androidx.compose.material3.LinearProgressIndicator(
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    androidx.compose.material3.LinearProgressIndicator(
                        progress = { progress.fraction.coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                if (progress.detail.isNotBlank()) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = progress.detail,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (!progress.cancelable) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = "正在写入本地标签库，请勿退出应用",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = onCancel,
                enabled = progress.cancelable,
            ) { Text("取消") }
        },
    )
}

/** 字节 → MB 文本（更新下载进度用） */
private fun formatMb(bytes: Long): String =
    String.format(java.util.Locale.US, "%.1f", bytes / 1024f / 1024f)
