package com.novelreader.ui.screens.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
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
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
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
    // v1.9.0.2 开发者选项：详细日志开关 + 导出结果提示
    val debugLogging by viewModel.debugLogging.collectAsState()
    val appFont by viewModel.appFont.collectAsState()
    // v1.9.1.0 自定义字体
    val customFontPath by viewModel.customFontPath.collectAsState()
    val fontImportResult by viewModel.fontImportResult.collectAsState()
    val fontPicker = rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument(),
    ) { uri -> uri?.let { viewModel.importFont(it) } }
    LaunchedEffect(fontImportResult) {
        val msg = fontImportResult ?: return@LaunchedEffect
        android.widget.Toast.makeText(context, msg, android.widget.Toast.LENGTH_LONG).show()
        viewModel.consumeFontImportResult()
    }
    val debugExportResult by viewModel.debugExportResult.collectAsState()
    LaunchedEffect(debugExportResult) {
        val r = debugExportResult ?: return@LaunchedEffect
        android.widget.Toast.makeText(
            context,
            if (r.startsWith("/")) "已导出：$r" else r,
            android.widget.Toast.LENGTH_LONG,
        ).show()
        viewModel.consumeDebugExportResult()
    }

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
    val novelPageType by viewModel.novelPageType.collectAsState()
    // 自动翻页倍速（A5）
    val autoPageSpeed by viewModel.autoPageSpeed.collectAsState()
    // 动态取色（C1）
    val themeSeedColor by viewModel.themeSeedColor.collectAsState()
    val themeDynamicColor by viewModel.themeDynamicColor.collectAsState()
    val themeSeedBusy by viewModel.themeSeedBusy.collectAsState()
    val comicPageMode by viewModel.comicPageMode.collectAsState()
    val comicPreloadCount by viewModel.comicPreloadCount.collectAsState()
    val comicPreloadWifiOnly by viewModel.comicPreloadWifiOnly.collectAsState()
    val comicEdgeGesture by viewModel.comicEdgeGesture.collectAsState()
    // D7 / D9 / D11：漫画画面设置
    val comicPagesPerScreen by viewModel.comicPagesPerScreen.collectAsState()
    val comicReaderBg by viewModel.comicReaderBg.collectAsState()
    val comicPageGap by viewModel.comicPageGap.collectAsState()
    val comicImageScale by viewModel.comicImageScale.collectAsState()
    val comicBrightness by viewModel.comicBrightness.collectAsState()
    val comicShowPageNumber by viewModel.comicShowPageNumber.collectAsState()
    val comicShowClock by viewModel.comicShowClock.collectAsState()
    val comicShowSystemBar by viewModel.comicShowSystemBar.collectAsState()
    val searchConcurrency by viewModel.searchConcurrency.collectAsState()
    val searchSiteLimit by viewModel.searchSiteLimit.collectAsState()
    val tagSearchConcurrency by viewModel.tagSearchConcurrency.collectAsState()
    val tagSingleSearchSources by viewModel.tagSingleSearchSources.collectAsState()
    val videoFullscreenDefault by viewModel.videoFullscreenDefault.collectAsState()
    val videoLongPressSpeed by viewModel.videoLongPressSpeed.collectAsState()
    val videoLongPressRewind by viewModel.videoLongPressRewind.collectAsState()
    val novelPreloadChapters by viewModel.novelPreloadChapters.collectAsState()
    val videoDoubleTapSeek by viewModel.videoDoubleTapSeek.collectAsState()
    val videoAutoNext by viewModel.videoAutoNext.collectAsState()
    val videoVerticalSwipeSwitch by viewModel.videoVerticalSwipeSwitch.collectAsState()
    val videoPipEnabled by viewModel.videoPipEnabled.collectAsState()
    val videoAdFilter by viewModel.videoAdFilter.collectAsState()
    val videoBufferSeconds by viewModel.videoBufferSeconds.collectAsState()
    val videoQuality by viewModel.videoQuality.collectAsState()
    val videoPrefetchWifiOnly by viewModel.videoPrefetchWifiOnly.collectAsState()
    val hideAdult by viewModel.hideAdult.collectAsState()
    val novelShelfPrompt by viewModel.novelShelfPrompt.collectAsState()
    val comicShelfPrompt by viewModel.comicShelfPrompt.collectAsState()
    val searchLongPressShelf by viewModel.searchLongPressShelf.collectAsState()
    val rssEntryBelow by viewModel.rssEntryBelow.collectAsState()
    // 视觉模型打标（v1.8.7.2，默认关）
    val visualTagging by viewModel.visualTagging.collectAsState()
    // 离线语音 / 字幕识别（v1.8.7.3，默认关）
    val offlineTts by viewModel.offlineTts.collectAsState()
    val subtitleAsr by viewModel.subtitleAsr.collectAsState()
    val modelStates by viewModel.modelStates.collectAsState()
    val meteredConfirm by viewModel.meteredConfirm.collectAsState()
    val modelMessage by viewModel.modelMessage.collectAsState()
    // 离线试听是否正在朗读（决定按钮显示「试听」还是「停止」）
    val ttsSpeaking by viewModel.ttsSpeaking.collectAsState()
    // 模型自选（v1.8.7.4）：可选项会随「下载完成 / 导入 / 删除」变化，
    // 所以这里订阅 customModels 与 modelStates 两个 flow，变化时重算列表。
    val customModels by viewModel.customModels.collectAsState()
    val modelSelection by viewModel.modelSelection.collectAsState()
    val libraryBusy by viewModel.libraryBusy.collectAsState()
    // 搜索结果展示（v1.8.6.0 待办 #26）
    val searchGridNovel by viewModel.searchGridNovel.collectAsState()
    val searchGridComic by viewModel.searchGridComic.collectAsState()
    val searchGridVideo by viewModel.searchGridVideo.collectAsState()
    val searchGridRss by viewModel.searchGridRss.collectAsState()
    // 标签搜索结果布局（v1.8.7.1）
    val searchGridTag by viewModel.searchGridTag.collectAsState()
    // 宫格列数（v1.8.7.5）
    val gridColumns by viewModel.gridColumns.collectAsState()

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

    // ===== 模型自选（v1.8.7.4）=====
    // 当前打开的模型选择框（null = 关闭）。存 kind 而不是布尔值：
    // 两个用途共用一个对话框，标题和列表按 kind 走。
    var pickerKind by remember { mutableStateOf<com.novelreader.core.model.ModelKind?>(null) }
    // 导入的目标用途。SAF 回调是异步的，回来时对话框的上下文已经没了，
    // 只能先把「给谁导入」记下来。
    var importKind by remember { mutableStateOf(com.novelreader.core.model.ModelKind.TTS) }
    // 「填仓库地址添加」输入框（非空 = 打开）；与选择框叠着显示，下载进度还在下面看得到
    var repoKind by remember { mutableStateOf<com.novelreader.core.model.ModelKind?>(null) }
    // 待确认删除的自定义模型。删除是不可逆的（自定义模型删了只能重新导入），
    // 而「删除」就挨着单选框，手指一滑就点上，所以必须二次确认
    var pendingDelete by remember {
        mutableStateOf<com.novelreader.core.model.InstalledModel?>(null)
    }
    val modelImportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris ->
        if (uris.isNullOrEmpty()) return@rememberLauncherForActivityResult
        // 标题默认取第一个文件名去掉扩展名：用户不改也能认出是哪个模型
        val first = uris.first().lastPathSegment.orEmpty()
        val title = first.substringAfterLast('/').substringBeforeLast('.')
            .takeIf { it.isNotBlank() } ?: "自定义模型"
        viewModel.importModel(importKind, title, uris)
    }
    // 可选项列表依赖「内置是否已下载」与「自定义模型清单」，两者都是 flow
    val ttsModels = remember(modelStates, customModels) {
        viewModel.availableModels(com.novelreader.core.model.ModelKind.TTS)
    }
    val asrModels = remember(modelStates, customModels) {
        viewModel.availableModels(com.novelreader.core.model.ModelKind.ASR)
    }

    Scaffold(
        snackbarHost = { androidx.compose.material3.SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                    // 顶栏避让状态栏：enableEdgeToEdge 下部分设备 Scaffold 默认 inset 失效
                    windowInsets = WindowInsets.statusBars,
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
            // 界面字体（v1.9.0.5）：整 App 的 UI 字体，阅读页正文另有自己的设置
            SettingRow(
                title = "界面字体",
                subtitle = "菜单、列表、设置这些界面文字的字体（阅读页正文字体在阅读菜单里单独调）",
            ) {
                Row {
                    listOf(
                        "系统" to PreferencesManager.APP_FONT_SYSTEM,
                        "衬线" to PreferencesManager.APP_FONT_SERIF,
                        "等宽" to PreferencesManager.APP_FONT_MONO,
                        "手写" to PreferencesManager.APP_FONT_CURSIVE,
                    ).forEach { (label, mode) ->
                        FilterChip(
                            selected = appFont == mode,
                            onClick = { viewModel.setAppFont(mode) },
                            label = { Text(label) },
                            modifier = Modifier.padding(start = 4.dp),
                        )
                    }
                }
            }
            // 自定义字体（v1.9.1.0）：从文件管理器选 ttf / otf，复制进 filesDir 后长期可用
            SettingRow(
                title = "自定义字体",
                subtitle = if (customFontPath.isBlank()) {
                    "从文件管理器选一个 ttf / otf 字体文件，导入后自动切换过去"
                } else {
                    "当前：" + customFontPath.substringAfterLast('/').substringAfter("custom_").substringAfter('_')
                },
            ) {
                Row {
                    TextButton(
                        onClick = {
                            // 有些文件管理器不给字体 MIME，放开到 */* 再靠 Typeface 校验
                            fontPicker.launch(arrayOf("*/*"))
                        },
                    ) { Text(if (customFontPath.isBlank()) "添加" else "更换") }
                    if (customFontPath.isNotBlank()) {
                        TextButton(onClick = { viewModel.removeCustomFont() }) { Text("移除") }
                    }
                }
            }
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

            // ===== 主题风格（C2）=====
            SectionTitle("主题风格")
            val themeStyle by viewModel.themeStyle.collectAsState()
            val styleNames = listOf("标准", "暖色", "冷色")
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                listOf(0, 1, 2).forEach { s ->
                    FilterChip(
                        selected = themeStyle == s,
                        onClick = { viewModel.setThemeStyle(s) },
                        label = { Text(styleNames[s]) },
                    )
                }
            }
            Text(
                text = if (themeDynamicColor) "动态取色已开启，风格选择暂不生效" else "整体配色风格，切换即时生效",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            // 夜间模式优化：PDF 阅读页夜间反色开关（此前只有阅读页顶栏图标、设置页无入口）
            val pdfNightInvert by viewModel.pdfNightInvert.collectAsState()
            SettingRow(
                title = "PDF 夜间反色",
                subtitle = "打开 PDF 时反转页面颜色并压暗亮度，与 PDF 阅读页顶栏开关同源",
            ) {
                Switch(
                    checked = pdfNightInvert,
                    onCheckedChange = { viewModel.setPdfNightInvert(it) },
                )
            }

            // ===== 追更提醒（D30）=====
            val followEnabled by viewModel.followEnabled.collectAsState()
            val followInterval by viewModel.followIntervalHours.collectAsState()
            val followChecking by viewModel.followChecking.collectAsState()
            SettingRow(
                title = "追更提醒",
                subtitle = "书架里的作品有新章节时发一条汇总通知。首次检查只记录基线，" +
                    "之后按间隔比对章节数。",
            ) {
                Switch(
                    checked = followEnabled,
                    onCheckedChange = { viewModel.setFollowEnabled(it) },
                )
            }
            if (followEnabled) {
                Text(
                    text = "检查间隔（应用运行期间按此间隔检查，重启后从打开应用时算起）",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(6, 12, 24).forEach { hours ->
                        FilterChip(
                            selected = followInterval == hours,
                            onClick = { viewModel.setFollowIntervalHours(hours) },
                            label = { Text("${hours} 小时") },
                        )
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(
                        enabled = !followChecking,
                        onClick = { viewModel.checkFollowUpdatesNow() },
                    ) { Text(if (followChecking) "检查中…" else "立即检查") }
                    Text(
                        text = "Android 13+ 需通知权限；系统清后台后需重开应用",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                val followMessage by viewModel.followMessage.collectAsState()
                followMessage?.let { msg ->
                    Text(
                        text = msg,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.clickable { viewModel.dismissFollowMessage() },
                    )
                }
            }

            // ===== 动态取色（C1）=====
            // 开关与种子色分开存：关掉只是不使用，取到的颜色还在，下次打开不必重取。
            SettingRow(
                title = "动态取色",
                subtitle = "用主题色生成整套 Material3 配色；关掉则回到默认的藏蓝 / 夜色",
            ) {
                Switch(
                    checked = themeDynamicColor,
                    onCheckedChange = { viewModel.setThemeDynamicColor(it) },
                )
            }
            SettingRow(
                title = "从封面取色",
                subtitle = if (themeSeedBusy) {
                    "正在读取最近阅读的封面…"
                } else if (themeSeedColor == 0) {
                    "从书架最近阅读的第一本书封面提取主色"
                } else {
                    "当前主题色 #%06X".format(0xFFFFFF and themeSeedColor)
                },
            ) {
                TextButton(
                    onClick = { viewModel.pickSeedFromRecentCover() },
                    enabled = !themeSeedBusy,
                ) { Text(if (themeSeedBusy) "取色中…" else "取色") }
            }
            // 兜底：书架为空 / 封面下载不到时，也能从 6 个预设色里挑一个
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                THEME_PRESET_SEEDS.forEach { (argb, name) ->
                    ColorSwatch(
                        color = Color(argb),
                        name = name,
                        selected = themeSeedColor == argb,
                        onClick = { viewModel.setThemeSeedColor(argb) },
                    )
                }
            }

            Spacer(Modifier.height(24.dp))

            // ===== 小说 =====
            SectionTitle("小说阅读")
            // 翻页方式（v1.8.7.9，待办 #31）：以前只能在阅读页菜单里改，设置页没有入口。
            // 六档与 `NovelReaderMenu` 完全一致，读写同一个偏好键，改哪边都同步。
            Text(
                text = "翻页方式",
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                text = "与阅读页菜单里的「翻页方式」是同一个设置；「滚动 / 上下」是连续滚动，其余四种是左右分页",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                listOf(
                    "覆盖" to PreferencesManager.PAGE_TYPE_COVER,
                    "仿真" to PreferencesManager.PAGE_TYPE_SIMULATE,
                    "滑动" to PreferencesManager.PAGE_TYPE_SLIDE,
                    "滚动" to PreferencesManager.PAGE_TYPE_SCROLL,
                    "上下" to PreferencesManager.PAGE_TYPE_VERTICAL,
                    "无动画" to PreferencesManager.PAGE_TYPE_NO_ANIM,
                ).forEach { (label, type) ->
                    PageModeChip(
                        label = label,
                        selected = novelPageType == type,
                        onClick = { viewModel.setNovelPageType(type) },
                    )
                }
            }

            Spacer(Modifier.height(16.dp))
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
            // v1.8.8.5：上限 5 → 10。预分页和预缓存共用这个档位，档位越大，
            // 往后连续翻章时越可能「正文已就绪 + 分页已算好」，代价是流量与内存。
            Spacer(Modifier.height(16.dp))
            Text(
                text = if (novelPreloadChapters == 0) "预加载章节：关闭"
                else "预加载章节：$novelPreloadChapters 章",
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                text = "阅读时向后预取接下来 N 章（并预先排好版），越大翻章越顺但更耗流量；设为 0 可完全关闭",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Slider(
                value = novelPreloadChapters.toFloat(),
                onValueChange = { viewModel.setNovelPreloadChapters(it.toInt()) },
                valueRange = 0f..10f,
                steps = 9,
            )
            // 自动翻页倍速（A5）：读写与阅读页菜单、阅读页翻页计时完全同一个偏好键。
            // 设置页本来没有「自动翻页间隔」入口（那个在阅读页菜单里），所以这里单独成项，
            // 给不想进阅读页调档的用户一个快捷入口。
            Spacer(Modifier.height(16.dp))
            Text(
                text = "自动翻页倍速",
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                text = "自动翻页的停留时长按倍速缩放：1x 最慢、4x 最快（实际间隔 = 设定间隔 ÷ 倍速）",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                com.novelreader.core.reader.ReaderAutoPagePolicy.SPEED_CHOICES.forEach { speed ->
                    PageModeChip(
                        label = speedLabel(speed),
                        selected = autoPageSpeed == speed,
                        onClick = { viewModel.setAutoPageSpeed(speed) },
                    )
                }
            }

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
                text = "阅读模式（D1 六档）",
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(8.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                listOf(
                    0 to "连续上下",
                    1 to "左→右翻页",
                    2 to "右→左翻页",
                    3 to "连续左→右",
                    4 to "连续右→左",
                    5 to "上下翻页",
                ).forEach { (value, label) ->
                    PageModeChip(
                        label = label,
                        selected = comicPageMode == value,
                        onClick = { viewModel.setComicPageMode(value) },
                    )
                }
            }

            Spacer(Modifier.height(16.dp))
            Text(
                text = "每屏页数（D7）",
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                PageModeChip("自适应", comicPagesPerScreen == 0) { viewModel.setComicPagesPerScreen(0) }
                PageModeChip("单页", comicPagesPerScreen == 1) { viewModel.setComicPagesPerScreen(1) }
                PageModeChip("双页", comicPagesPerScreen == 2) { viewModel.setComicPagesPerScreen(2) }
            }

            Spacer(Modifier.height(16.dp))
            Text(
                text = "背景（D9）",
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                PageModeChip("纯黑", comicReaderBg == 0) { viewModel.setComicReaderBg(0) }
                PageModeChip("深灰", comicReaderBg == 1) { viewModel.setComicReaderBg(1) }
                PageModeChip("纯白", comicReaderBg == 2) { viewModel.setComicReaderBg(2) }
            }

            Spacer(Modifier.height(16.dp))
            Text(
                text = "图片间距（D9）",
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                listOf(0, 2, 4, 8).forEach { g ->
                    PageModeChip("${g}dp", comicPageGap == g) { viewModel.setComicPageGap(g) }
                }
            }

            Spacer(Modifier.height(16.dp))
            Text(
                text = "图片适配（D9）",
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                PageModeChip("适应宽度", comicImageScale == 0) { viewModel.setComicImageScale(0) }
                PageModeChip("适应屏幕", comicImageScale == 1) { viewModel.setComicImageScale(1) }
                PageModeChip("裁剪填满", comicImageScale == 2) { viewModel.setComicImageScale(2) }
            }

            Spacer(Modifier.height(16.dp))
            Text(
                text = if (comicBrightness <= 0f) "阅读亮度：跟随系统"
                else "阅读亮度：${(comicBrightness * 100).toInt()}%",
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                text = "漫画阅读页常驻亮度（黑色遮罩）。0 表示跟随系统亮度",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Slider(
                value = comicBrightness,
                onValueChange = { viewModel.setComicBrightness(it) },
                valueRange = 0f..1f,
            )

            Spacer(Modifier.height(8.dp))
            SettingRow(title = "阅读页显示页码（D11）") {
                Switch(
                    checked = comicShowPageNumber,
                    onCheckedChange = { viewModel.setComicShowPageNumber(it) },
                )
            }
            SettingRow(
                title = "阅读页显示时钟 / 电量（D11）",
                subtitle = "右上角小字，每 30 秒刷新",
            ) {
                Switch(
                    checked = comicShowClock,
                    onCheckedChange = { viewModel.setComicShowClock(it) },
                )
            }
            SettingRow(
                title = "阅读页显示系统状态栏（D11）",
                subtitle = "关闭时全屏沉浸，从屏幕边缘滑动可临时呼出",
            ) {
                Switch(
                    checked = comicShowSystemBar,
                    onCheckedChange = { viewModel.setComicShowSystemBar(it) },
                )
            }

            // D10：按屏幕数预加载（>0 时覆盖下面的「张数」语义）
            val comicPreloadScreens by viewModel.comicPreloadScreens.collectAsState()
            Spacer(Modifier.height(16.dp))
            Text(
                text = if (comicPreloadScreens == 0) {
                    "按屏幕预加载：关闭（用下方张数）"
                } else {
                    "按屏幕预加载：$comicPreloadScreens 屏"
                },
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                text = "以「当前屏能放几张」为单位提前加载：长条图一屏 1 张、短图一屏 3 张都能对上。" +
                    "开启后忽略下方张数设置。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(0, 1, 2, 3).forEach { screens ->
                    FilterChip(
                        selected = comicPreloadScreens == screens,
                        onClick = { viewModel.setComicPreloadScreens(screens) },
                        label = { Text(if (screens == 0) "关闭" else "$screens 屏") },
                    )
                }
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
                valueRange = 1f..24f,
                steps = 22,
            )
            Spacer(Modifier.height(8.dp))
            // v1.9.4.0：同一站点常被导入几十个新旧变体，连续排在队列里会把 45 秒时限吃光，
            // 实测 2434 个源只来得及问 159 个。默认只按站点交错排序、一个源都不删。
            Text(
                text = if (searchSiteLimit == 0) "同站点搜索源数：不限（只交错排序）" else "同站点搜索源数：$searchSiteLimit",
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                text = "只影响「全部源」搜索：默认让不同站点轮流先搜，不删任何源。" +
                    "调成大于 0 才会把同站变体截断到指定个数（规则更完整者优先）。" +
                    "手动选单源、多选或按分组搜索时不受限制。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Slider(
                value = searchSiteLimit.toFloat(),
                onValueChange = { viewModel.setSearchSiteLimit(it.toInt()) },
                valueRange = 0f..8f,
                steps = 7,
            )
            Spacer(Modifier.height(8.dp))
            // v1.8.8.4：标签搜索并发单独一个滑块 —— 标签搜索的请求数是
            // 「标签 × 源 × 入口」的笛卡尔积，跟关键词搜索不是一个量级。
            Text(
                text = "标签搜索并发：$tagSearchConcurrency",
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                text = "标签搜索同时打多少个源。调大更快，但站点多、网络差时容易整批超时；" +
                    "Venera 漫画源固定按 4 路走（它们的引擎是单线程，给高了只是排队）",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Slider(
                value = tagSearchConcurrency.toFloat(),
                onValueChange = { viewModel.setTagSearchConcurrency(it.toInt()) },
                valueRange = 1f..40f,
                steps = 38,
            )
            Spacer(Modifier.height(8.dp))
            // v1.9.2.1：单标签搜索源数。
            // 组内成员（「彩绘」）与池外自动新建的标签没有源站分类页，只能按关键词搜；
            // v1.9.2.0 写死 8 个源，于是「整组搜全部源、单标签只搜 8 个」，
            // 同一组词两种口径，看着像丢结果。默认改成全部，怕慢的用户可以调小。
            Text(
                text = "单标签搜索源数：" +
                    if (tagSingleSearchSources <= 0) "全部" else "$tagSingleSearchSources 个",
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                text = "只影响「按关键词搜」的标签（组内写法、标签池里没有的新词）—— " +
                    "源站自带分类页的标签本来就有多少源问多少源。" +
                    "调小更快但覆盖变窄；改完下一次进标签页会自动重建标签池",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(
                    "全部" to PreferencesManager.TAG_SINGLE_SEARCH_SOURCES_ALL,
                    "32 个" to 32,
                    "24 个" to 24,
                    "16 个" to 16,
                    "8 个" to 8,
                ).forEach { (label, count) ->
                    FilterChip(
                        selected = tagSingleSearchSources == count,
                        onClick = { viewModel.setTagSingleSearchSources(count) },
                        label = { Text(label) },
                    )
                }
            }
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
            Text(
                text = "搜索结果展示方式（v1.8.6.0）：关闭 = 列表，开启 = 宫格；各模块独立记忆",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            SettingRow(
                title = "小说搜索默认宫格",
                subtitle = "宫格看封面，列表看作者 / 字数等详情",
            ) {
                Switch(
                    checked = searchGridNovel,
                    onCheckedChange = { viewModel.setSearchGridNovel(it) },
                )
            }
            SettingRow(title = "漫画搜索默认宫格") {
                Switch(
                    checked = searchGridComic,
                    onCheckedChange = { viewModel.setSearchGridComic(it) },
                )
            }
            SettingRow(title = "视频搜索默认宫格") {
                Switch(
                    checked = searchGridVideo,
                    onCheckedChange = { viewModel.setSearchGridVideo(it) },
                )
            }
            SettingRow(
                title = "订阅期刊检索默认宫格",
                subtitle = "期刊订阅面板里的检索结果布局",
            ) {
                Switch(
                    checked = searchGridRss,
                    onCheckedChange = { viewModel.setSearchGridRss(it) },
                )
            }
            SettingRow(
                title = "标签搜索默认宫格",
                subtitle = "标签搜索页的结果布局，页面顶栏也能随时切换",
            ) {
                Switch(
                    checked = searchGridTag,
                    onCheckedChange = { viewModel.setSearchGridTag(it) },
                )
            }

            // 宫格列数（v1.8.7.5）：一行放 2~5 列 + 自适应。做成一整块而不是 SettingRow，
            // 是因为 5 个 chip 横排放不进 SettingRow 右侧那点宽度。
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
            ) {
                Text(text = "宫格列数", style = MaterialTheme.typography.bodyLarge)
                Text(
                    text = "标签搜索与普通搜索共用；自适应 = 视频 3 列、小说 / 漫画 2 列",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(
                    modifier = Modifier.padding(top = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    listOf(0, 2, 3, 4, 5).forEach { value ->
                        FilterChip(
                            selected = (if (value == 0) gridColumns < 2 else gridColumns == value),
                            onClick = { viewModel.setGridColumns(value) },
                            label = { Text(if (value == 0) "自适应" else "$value") },
                        )
                    }
                }
            }

            Spacer(Modifier.height(24.dp))

            // ===== 订阅（RSS）（v1.8.6.0 待办 #24） =====
            SectionTitle("订阅（RSS）")
            SettingRow(
                title = "订阅入口移到导航栏下方",
                subtitle = "关闭：订阅入口在首页顶栏（RSS 图标）\n" +
                    "开启：顶栏图标隐藏，入口常驻在「小说 / 漫画 / 视频」导航下方的首行",
            ) {
                Switch(
                    checked = rssEntryBelow,
                    onCheckedChange = { viewModel.setRssEntryBelow(it) },
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
            SettingRow(
                title = "广告过滤",
                subtitle = "自动跳过视频里的贴片 / 中插广告（基于 HLS 播放列表里的广告分片，" +
                    "不改变画质；烧在正片画面里的广告无法过滤）",
            ) {
                Switch(
                    checked = videoAdFilter,
                    onCheckedChange = { viewModel.setVideoAdFilter(it) },
                )
            }

            // 预缓存时长（v1.8.4.0，待办 #8）：控制 ExoPlayer 向后缓冲多久
            // v1.8.7.9（待办 #32）：档位补到 10 分钟，8 个 chip 一行放不下，改 FlowRow 自动换行
            Spacer(Modifier.height(16.dp))
            Text(
                text = "预缓存时长：${com.novelreader.core.media.VideoBufferOptions.label(videoBufferSeconds)}",
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                text = "播放时向后缓冲多久：越大越抗卡顿（回退、快进都读本地），但更占内存与流量；" +
                    "5 分钟以上只建议 WiFi 下使用，10 分钟档一路能吃掉数百 MB；关闭后只留最小缓冲",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                com.novelreader.core.media.VideoBufferOptions.CHOICES.forEach { sec ->
                    PageModeChip(
                        label = if (sec == 0) "关" else com.novelreader.core.media.VideoBufferOptions.label(sec),
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
            // v1.8.7.2：视觉模型（WD14）总开关，默认关 —— 关着的时候标签页
            // 的「封面视觉打标」是禁用的，也不会去检查/下载那 356MB 模型
            SettingRow(
                title = "启用视觉模型打标",
                subtitle = "允许对已收录封面跑 WD14 模型（Danbooru 体系）补标签。\n" +
                    "关闭时不会加载模型、也不会下载（模型约 356MB，仅 Wi-Fi 按需下载）",
            ) {
                Switch(
                    checked = visualTagging,
                    onCheckedChange = { viewModel.setVisualTagging(it) },
                )
            }
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
                    "导入打标表（本地文件或云端地址），与 PC 打标工具的成果合并进本地标签层；本地文件支持大表流式导入，不再因文件过大而失败",
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

            // ===== 离线语音与字幕（v1.8.7.3）=====
            // 两个功能共用一套「模型按需下载」：开关默认关，打开才下载，
            // 关掉不会自动删模型（删是显式动作，见每行下面的「删除模型」）。
            SectionTitle("离线语音与字幕")
            ModelSettingRow(
                title = "听书（离线语音）",
                subtitle = "朗读改用内置离线语音模型，音色统一、断网也能读。\n" +
                    "关闭时用手机自带的语音引擎，无需下载",
                checked = offlineTts,
                onCheckedChange = { on ->
                    viewModel.setOfflineTts(on)
                    if (on) viewModel.ensureModelDownloaded(com.novelreader.core.model.LocalModels.OFFLINE_TTS)
                },
                spec = com.novelreader.core.model.LocalModels.OFFLINE_TTS,
                state = modelStates[com.novelreader.core.model.LocalModels.OFFLINE_TTS.id],
                onDownload = {
                    viewModel.downloadModel(com.novelreader.core.model.LocalModels.OFFLINE_TTS, false)
                },
                onDelete = { viewModel.deleteModel(com.novelreader.core.model.LocalModels.OFFLINE_TTS) },
                previewLabel = if (ttsSpeaking) "停止" else "试听",
                onPreview = { viewModel.previewOfflineTts() },
                currentModel = viewModel.currentModel(com.novelreader.core.model.ModelKind.TTS),
                optionCount = ttsModels.size,
                onPickModel = { pickerKind = com.novelreader.core.model.ModelKind.TTS },
            )
            ModelSettingRow(
                title = "字幕识别（离线）",
                subtitle = "为没有字幕的视频自动生成中文字幕。\n" +
                    "关闭时不会下载模型，播放器里也不出现「生成字幕」入口",
                checked = subtitleAsr,
                onCheckedChange = { on ->
                    viewModel.setSubtitleAsr(on)
                    if (on) viewModel.ensureModelDownloaded(com.novelreader.core.model.LocalModels.SUBTITLE_ASR)
                },
                spec = com.novelreader.core.model.LocalModels.SUBTITLE_ASR,
                state = modelStates[com.novelreader.core.model.LocalModels.SUBTITLE_ASR.id],
                onDownload = {
                    viewModel.downloadModel(com.novelreader.core.model.LocalModels.SUBTITLE_ASR, false)
                },
                onDelete = { viewModel.deleteModel(com.novelreader.core.model.LocalModels.SUBTITLE_ASR) },
                currentModel = viewModel.currentModel(com.novelreader.core.model.ModelKind.ASR),
                optionCount = asrModels.size,
                onPickModel = { pickerKind = com.novelreader.core.model.ModelKind.ASR },
            )

            // 下载失败的提示（对话框不影响布局，放在这里只为就近）
            LaunchedEffect(modelMessage) {
                val msg = modelMessage ?: return@LaunchedEffect
                android.widget.Toast.makeText(context, msg, android.widget.Toast.LENGTH_LONG).show()
                viewModel.dismissModelMessage()
            }
            meteredConfirm?.let { spec ->
                androidx.compose.material3.AlertDialog(
                    onDismissRequest = { viewModel.dismissMeteredConfirm() },
                    title = { Text("当前不是 Wi-Fi") },
                    text = {
                        Text(
                            "下载「${spec.title}」需要约 " +
                                com.novelreader.core.model.formatModelSize(spec.totalBytes) +
                                " 流量，确认继续？",
                        )
                    },
                    confirmButton = {
                        TextButton(onClick = { viewModel.confirmMeteredDownload() }) { Text("继续下载") }
                    },
                    dismissButton = {
                        TextButton(onClick = { viewModel.dismissMeteredConfirm() }) { Text("稍后") }
                    },
                )
            }

            // 模型选择框（v1.8.7.4）：两个用途共用，选项按 kind 取
            pickerKind?.let { kind ->
                val options = when (kind) {
                    com.novelreader.core.model.ModelKind.TTS -> ttsModels
                    com.novelreader.core.model.ModelKind.ASR -> asrModels
                }
                // 选择表里存空串表示「用内置默认」，这里要把它翻译成具体的内置模型 id，
                // 否则列表里一个都不打勾 —— 用户看到的是「当前使用：离线语音（听书）」，
                // 打开对话框却像什么都没选
                val checked = modelSelection[kind].orEmpty().ifBlank {
                    (options.firstOrNull { !it.isCustom } ?: options.firstOrNull())?.id.orEmpty()
                }
                ModelPickerDialog(
                    kind = kind,
                    options = options,
                    selectedId = checked,
                    busy = libraryBusy,
                    onSelect = { viewModel.selectModel(kind, it) },
                    onImport = {
                        importKind = kind
                        // 模型的 MIME 多半是 application/octet-stream，
                        // 按扩展名过滤会让 onnx 直接选不中，只能放开成 */*
                        modelImportLauncher.launch(arrayOf("*/*"))
                    },
                    onAddRepo = { repoKind = kind },
                    onDeleteCustom = { pendingDelete = it },
                    onDismiss = { pickerKind = null },
                )
            }
            pendingDelete?.let { model ->
                AlertDialog(
                    onDismissRequest = { pendingDelete = null },
                    title = { Text("删除模型") },
                    text = {
                        Text(
                            "「${model.title}」占 " +
                                com.novelreader.core.model.formatModelSize(model.sizeBytes()) +
                                "，删除后需要重新导入才能再用。确定删除？",
                        )
                    },
                    confirmButton = {
                        TextButton(
                            onClick = {
                                pendingDelete = null
                                viewModel.deleteCustomModel(model)
                            },
                        ) { Text("删除", color = MaterialTheme.colorScheme.error) }
                    },
                    dismissButton = {
                        TextButton(onClick = { pendingDelete = null }) { Text("取消") }
                    },
                )
            }
            repoKind?.let { kind ->
                RepoInputDialog(
                    onDismiss = { repoKind = null },
                    onConfirm = { repo, title ->
                        repoKind = null
                        viewModel.addModelFromRepo(kind, repo, title)
                    },
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
            Spacer(Modifier.height(24.dp))

            // ===== 开发者选项（v1.9.0.2）=====
            // 排查问题的常驻入口。以前每次都要「临时插日志 → 重新打包 → 复现 → 抓 logcat」，
            // 一圈十几分钟；现在打开开关、复现一次、导出文件，直接发过来就能定位。
            SectionTitle("开发者选项")
            SettingRow(
                title = "详细日志",
                subtitle = "记录关键路径到内存（最多 600 条，只含位移量 / 条数 / 耗时这类结构信息）。\n" +
                    "排查问题：打开 → 复现一次 → 导出诊断日志",
            ) {
                Switch(
                    checked = debugLogging,
                    onCheckedChange = { viewModel.setDebugLogging(it) },
                )
            }
            SettingRow(
                title = "导出诊断日志",
                subtitle = "写到 Android/data/com.novelreader/files/debug/，可直接分享",
            ) {
                TextButton(onClick = { viewModel.exportDebugLog() }) { Text("导出") }
            }
            SettingRow(
                title = "清空日志",
                subtitle = "清掉当前缓冲区（已经导出的文件不受影响）",
            ) {
                TextButton(onClick = { viewModel.clearDebugLog() }) { Text("清空") }
            }

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
