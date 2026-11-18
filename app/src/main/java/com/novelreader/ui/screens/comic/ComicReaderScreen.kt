package com.novelreader.ui.screens.comic

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.BrokenImage
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.SubcomposeAsyncImage
import coil.imageLoader
import coil.request.ImageRequest
import com.novelreader.core.image.ComicImageScramble
import com.novelreader.domain.model.ChapterItem
import com.novelreader.domain.model.MediaItem
import com.novelreader.domain.repository.Bookmark
import com.novelreader.ui.theme.SystemBarIconsOn
import com.novelreader.ui.theme.LocalAppDarkTheme
import com.novelreader.ui.theme.applySystemBarIcons
import androidx.compose.runtime.DisposableEffect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/**
 * 漫画阅读页。
 *
 * 核心功能：
 * - 图片完美适配屏幕（宽度撑满、按比例缩放）
 * - 两种翻页方式：上下滚动 / 左右翻页
 * - 图片预加载：滚动 / 翻页时提前加载邻近图片，减少等待
 * - 加载中显示占位，加载失败可点击重试
 * - 点击屏幕呼出设置面板、上一话 / 下一话
 */
@Composable
fun ComicReaderScreen(
    viewModel: ComicReaderViewModel,
    item: MediaItem,
    chapters: List<ChapterItem>,
    chapter: ChapterItem,
    onBack: () -> Unit,
) {
    val images by viewModel.images.collectAsState()
    val loading by viewModel.loading.collectAsState()
    val error by viewModel.error.collectAsState()
    val pageMode by viewModel.pageMode.collectAsState()
    val currentPage by viewModel.currentPage.collectAsState()
    val initialPage by viewModel.initialPage.collectAsState()
    // D3 书签
    val bookmarks by viewModel.bookmarks.collectAsState()
    val currentPageBookmarked by viewModel.currentPageBookmarked.collectAsState()
    val scrollRequest by viewModel.scrollRequest.collectAsState()
    val chapterTitle by viewModel.chapterTitle.collectAsState()
    val imageHeaders by viewModel.imageHeaders.collectAsState()
    val imageScripts by viewModel.imageScripts.collectAsState()
    val chapterPosition by viewModel.chapterIndex.collectAsState()
    val chapterCount by viewModel.chapterCount.collectAsState()
    val hasPrevChapter by viewModel.hasPrevChapter.collectAsState()
    val hasNextChapter by viewModel.hasNextChapter.collectAsState()
    val preloadCount by viewModel.preloadCount.collectAsState()
    // D10：按屏幕数预加载（0 = 关闭，用上面的张数档位）
    val preloadScreens by viewModel.preloadScreens.collectAsState()
    val preloadWifiOnly by viewModel.preloadWifiOnly.collectAsState()
    val landscapeDoublePage by viewModel.landscapeDoublePage.collectAsState()
    val edgeGesture by viewModel.edgeGesture.collectAsState()
    val collected by viewModel.collected.collectAsState()
    val shelfStateReady by viewModel.shelfStateReady.collectAsState()
    val shelfPromptEnabled by viewModel.shelfPromptEnabled.collectAsState()
    // D7 / D9 / D11：每屏页数 + 画面设置 + 状态信息
    val pagesPerScreen by viewModel.pagesPerScreen.collectAsState()
    val readerBg by viewModel.readerBg.collectAsState()
    val pageGap by viewModel.pageGap.collectAsState()
    val imageScale by viewModel.imageScale.collectAsState()
    val readerBrightness by viewModel.readerBrightness.collectAsState()
    val showPageNumber by viewModel.showPageNumber.collectAsState()
    val showClock by viewModel.showClock.collectAsState()
    val showSystemBar by viewModel.showSystemBar.collectAsState()

    // D1：六档阅读模式
    val mode = ComicMode.of(pageMode)
    val bgColor = when (readerBg) {
        1 -> Color(0xFF1B1B1B)
        2 -> Color.White
        else -> Color.Black
    }
    // D9 适配方式：0=适应宽度（条漫） 1=适应屏幕 2=裁剪填满
    val contentScale = when (imageScale) {
        1 -> ContentScale.Fit
        2 -> ContentScale.Crop
        else -> ContentScale.FillWidth
    }

    LaunchedEffect(item, chapter) {
        viewModel.init(item, chapters, chapter)
    }

    // D2：退出阅读页时再落一次页内进度。滚动刚停 / 翻页刚落定时页面可能还没来得及写库，
    // 这里用脱离 viewModelScope 的写入兜底，避免「看到第 30 页直接返回」丢掉位置。
    DisposableEffect(Unit) {
        onDispose { viewModel.saveCurrentProgress() }
    }

    // v1.8.3.0：漫画未加入书架时提示一次（设置里可关；必须等 shelfStateReady 防误弹）
    var showShelfPrompt by remember { mutableStateOf(false) }
    var shelfPrompted by remember { mutableStateOf(false) }
    LaunchedEffect(shelfStateReady, collected, item) {
        if (shelfStateReady && !collected && !shelfPrompted && shelfPromptEnabled) {
            shelfPrompted = true
            showShelfPrompt = true
        }
    }
    if (showShelfPrompt) {
        AlertDialog(
            onDismissRequest = { showShelfPrompt = false },
            title = { Text("加入书架") },
            text = { Text("《${item.title}》还没加入书架，加入后可随时在书架继续阅读。") },
            confirmButton = {
                TextButton(onClick = {
                    showShelfPrompt = false
                    viewModel.toggleCollect()
                }) { Text("加入书架") }
            },
            dismissButton = {
                TextButton(onClick = { showShelfPrompt = false }) { Text("暂不") }
            },
        )
    }

    // 进入/切换章节后，先预热前几张图片
    val context = LocalContext.current
    // 是否允许预加载：未开启「仅 WiFi」时总是允许；开启时仅在 WiFi 下允许
    val canPreload = !preloadWifiOnly ||
        com.novelreader.core.util.NetworkStatus.isWifi(context)
    LaunchedEffect(images, imageHeaders, imageScripts, preloadCount, preloadScreens, canPreload) {
        if (!canPreload) return@LaunchedEffect
        // D10：按屏模式下用「2 屏」的保守量预热开篇（此时还不知道当屏能放几张）
        val warmup = if (preloadScreens > 0) (preloadScreens * 2).coerceIn(1, 16) else preloadCount
        images.take(warmup).forEach { preloadImage(context, it, imageHeaders, imageScripts[it]) }
    }

    // C3 横屏双页 + D7 单/双页自由切换：
    // 0=自适应（横屏画廊并排） 1=始终单页 2=始终双页
    val configuration = androidx.compose.ui.platform.LocalConfiguration.current
    val isLandscape = configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
    val doublePage = when (pagesPerScreen) {
        1 -> false
        2 -> true
        else -> landscapeDoublePage && isLandscape
    }
    // 双页只在「非上下方向」的画廊模式下有意义
    val effectiveDoublePage = doublePage && mode.isGallery && !mode.vertical

    // D6：长按图片菜单 + 外部缩放指令
    var longPressImage by remember { mutableStateOf<String?>(null) }
    var zoomToken by remember { mutableIntStateOf(0) }
    var zoomTarget by remember { mutableStateOf<ImageZoom?>(null) }
    val onImageLongPress: (String) -> Unit = { url -> longPressImage = url }
    // D4：目录抽屉
    var showToc by remember { mutableStateOf(false) }
    // D8：章末评论
    val comments by viewModel.comments.collectAsState()
    val commentsLoading by viewModel.commentsLoading.collectAsState()
    var showComments by remember { mutableStateOf(false) }
    // D6：保存原图 / 复制链接
    val readerScope = rememberCoroutineScope()
    fun saveImageToGallery(url: String) {
        if (url.isBlank()) return
        readerScope.launch {
            val ok = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                com.novelreader.core.image.ComicImageSaver.saveToGallery(context, url, imageHeaders)
            }
            android.widget.Toast.makeText(
                context,
                if (ok) "已保存到相册" else "保存失败，请重试",
                android.widget.Toast.LENGTH_SHORT,
            ).show()
        }
    }

    fun copyImageLink(url: String) {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE)
            as? android.content.ClipboardManager
        cm?.setPrimaryClip(android.content.ClipData.newPlainText("image_url", url))
        android.widget.Toast.makeText(context, "图片链接已复制", android.widget.Toast.LENGTH_SHORT).show()
    }

    // 统一菜单：顶部栏（返回 / 章节名 / 设置）+ 设置面板 + 底部章节条，
    // 三者由「点击画面」统一呼出与收起；默认隐藏，图片占满整屏。
    val menuVisible = remember { mutableStateOf(false) }
    // 设置面板是菜单的一部分：收起菜单时一并关闭，避免下次呼出时残留
    val showSettings = remember { mutableStateOf(false) }

    val toggleMenu: () -> Unit = {
        menuVisible.value = !menuVisible.value
        if (!menuVisible.value) showSettings.value = false
    }

    // 缩放态（是否有图片放大）自 v1.7.1.0 起由各阅读器内部按「具体图片 url」自行跟踪：
    // 共享的单一布尔会被相邻页 / 预加载页的回调互相覆盖，导致放大后翻页被永久锁死。

    // C3 方向锁定：锁定后设备旋转不改变屏幕方向，退出阅读页自动恢复
    val orientationLocked = remember { mutableStateOf(false) }
    val activity = remember(context) { (context as? android.content.ContextWrapper)?.let { ctx ->
        var c: android.content.Context? = ctx
        while (c is android.content.ContextWrapper) {
            if (c is android.app.Activity) return@let c
            c = c.baseContext
        }
        null
    } }
    androidx.compose.runtime.DisposableEffect(orientationLocked.value, activity) {
        val act = activity
        if (orientationLocked.value && act != null) {
            val current = act.resources.configuration.orientation
            act.requestedOrientation =
                if (current == android.content.res.Configuration.ORIENTATION_LANDSCAPE) {
                    android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                } else {
                    android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
                }
        }
        onDispose {
            act?.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }

    // 应用是 edge-to-edge（targetSdk 35）绘制：系统状态栏 / 手势条会盖在内容之上。
    // 顶部栏与底部章节条必须避开系统栏区域，否则：
    // - 顶部「设置」图标上半部落在状态栏里，点它没反应（实测 y=84 无效、y=140 生效）；
    // - 底部章节条被手势条遮挡。
    val navBottomInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    // 底部章节条总占位 = 条高 + 手势条高度（滚动列表的 contentPadding 要用这个值）
    val barTotalHeight = CHAPTER_BAR_HEIGHT + navBottomInset

    // 状态栏图标跟随阅读背景：深色底用白图标、白底用深色图标（v1.8.8.2 / D9）。
    SystemBarIconsOn(bgColor)
    // D11：系统栏显隐（默认隐藏让图片占满；菜单里可打开）
    val readerView = androidx.compose.ui.platform.LocalView.current
    DisposableEffect(showSystemBar) {
        val window = activity?.window
        val controller = window?.let {
            androidx.core.view.WindowCompat.getInsetsController(it, readerView)
        }
        if (controller != null) {
            val bars = androidx.core.view.WindowInsetsCompat.Type.systemBars()
            if (showSystemBar) controller.show(bars) else controller.hide(bars)
            controller.systemBarsBehavior =
                androidx.core.view.WindowInsetsControllerCompat
                    .BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        onDispose {
            controller?.show(androidx.core.view.WindowInsetsCompat.Type.systemBars())
        }
    }
    // 夜间模式优化：离开漫画页时把系统栏图标还原成应用主题，避免白色图标被带到浅色上一页。
    val comicAppDarkTheme by rememberUpdatedState(LocalAppDarkTheme.current)
    DisposableEffect(Unit) {
        onDispose {
            var ctx = context
            while (ctx is ContextWrapper && ctx !is Activity) ctx = ctx.baseContext
            (ctx as? Activity)?.applySystemBarIcons(darkIcons = !comicAppDarkTheme)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(bgColor),
    ) {
        when {
            loading -> {
                CircularProgressIndicator(
                    modifier = Modifier.align(Alignment.Center),
                    color = Color.White,
                )
            }
            error != null -> {
                Column(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Icon(
                        imageVector = Icons.Filled.BrokenImage,
                        contentDescription = null,
                        tint = Color(0xFF888888),
                        modifier = Modifier.size(48.dp),
                    )
                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = error ?: "加载失败",
                        color = Color.White,
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Spacer(Modifier.height(16.dp))
                    Row {
                        TextButton(onClick = { viewModel.retry() }) {
                            Text("重新加载", color = Color(0xFF6A9BD8))
                        }
                        Spacer(Modifier.width(12.dp))
                        TextButton(onClick = onBack) {
                            Text("返回", color = Color(0xFF6A9BD8))
                        }
                    }
                }
            }
            // D1 连续滚动族：上下 / 左→右 / 右→左
            !mode.isGallery -> {
                ContinuousReader(
                    images = images,
                    headers = imageHeaders,
                    scripts = imageScripts,
                    chapterIndex = chapterPosition,
                    onPrevChapter = { viewModel.prevChapter() },
                    onNextChapter = { viewModel.nextChapter() },
                    onTap = toggleMenu,
                    bottomContentPadding = barTotalHeight,
                    preloadCount = preloadCount,
                    preloadScreens = preloadScreens,
                    canPreload = canPreload,
                    onImageFailed = viewModel::onImageFailed,
                    initialPage = initialPage,
                    scrollRequest = scrollRequest,
                    // D2：滚动到第几页就上报第几页，由 VM 落库（重进同一章可回到这里）
                    onPageChange = viewModel::goToPage,
                    vertical = mode == ComicMode.CONTINUOUS_TTB,
                    reverse = mode.rtl,
                    gapDp = pageGap,
                    contentScale = contentScale,
                    onLongPress = { url -> onImageLongPress(url) },
                    zoomTarget = zoomTarget,
                )
            }
            // D1 画廊族：左右（可右→左）/ 上下
            else -> {
                GalleryReader(
                    images = images,
                    headers = imageHeaders,
                    scripts = imageScripts,
                    currentPage = currentPage,
                    chapterIndex = chapterPosition,
                    onPageChange = viewModel::goToPage,
                    onTap = toggleMenu,
                    onPrevChapter = { viewModel.prevChapter() },
                    onNextChapter = { viewModel.nextChapter() },
                    preloadCount = preloadCount,
                    preloadScreens = preloadScreens,
                    canPreload = canPreload,
                    doublePage = effectiveDoublePage,
                    vertical = mode == ComicMode.GALLERY_TTB,
                    reverse = mode.rtl,
                    gapDp = pageGap,
                    contentScale = contentScale,
                    zoomTarget = zoomTarget,
                    onLongPress = { url -> onImageLongPress(url) },
                    onImageFailed = viewModel::onImageFailed,
                )
            }
        }

        // ===== D9 常驻亮度（黑色遮罩，0 = 跟随系统）=====
        if (readerBrightness > 0f) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Color.Black.copy(alpha = (1f - readerBrightness).coerceIn(0f, 0.9f))
                    ),
            )
        }

        // ===== D11 页码 / 时钟电量 =====
        val pageTotal = if (effectiveDoublePage) (images.size + 1) / 2 else images.size
        val pageShown = if (effectiveDoublePage) currentPage / 2 + 1 else currentPage + 1
        if (showClock) {
            ReaderClock(
                dark = readerBg == 2,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 8.dp, end = 12.dp),
            )
        }
        if (showPageNumber && images.isNotEmpty()) {
            Text(
                text = "$pageShown/$pageTotal",
                color = if (readerBg == 2) Color(0xFF444444) else Color.White.copy(alpha = 0.85f),
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 12.dp, bottom = barTotalHeight + 4.dp),
            )
        }

        // ===== D8 章末评论 =====
        if (showComments) {
            CommentsDialog(
                title = "评论 · $chapterTitle",
                comments = comments,
                loading = commentsLoading,
                onDismiss = { showComments = false },
            )
        }

        // ===== D4 目录抽屉（分卷 + 当前话高亮）=====
        if (showToc) {
            val volumes = remember(chapters) { ComicChapterVolume.groupByVolume(chapters) }
            AlertDialog(
                onDismissRequest = { showToc = false },
                title = { Text("目录（${chapters.size}）") },
                text = {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 420.dp)
                            .verticalScroll(rememberScrollState()),
                    ) {
                        volumes.forEach { (volume, list) ->
                            Text(
                                text = "$volume（${list.size}）",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(vertical = 6.dp),
                            )
                            list.forEach { ch ->
                                val current = ch.url == chapter.url
                                Text(
                                    text = ch.title,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (current) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.onSurface,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            showToc = false
                                            val idx = chapters.indexOfFirst { it.url == ch.url }
                                            if (idx >= 0) viewModel.jumpToChapter(idx)
                                        }
                                        .padding(vertical = 8.dp),
                                )
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { showToc = false }) { Text("关闭") }
                },
            )
        }

        // ===== D6 长按图片菜单：放大 / 复制链接 / 保存原图 =====
        longPressImage?.let { url ->
            AlertDialog(
                onDismissRequest = { longPressImage = null },
                title = { Text("图片操作") },
                text = { Text("第 ${(images.indexOf(url) + 1).coerceAtLeast(1)} 页") },
                confirmButton = {
                    TextButton(onClick = {
                        longPressImage = null
                        zoomToken += 1
                        zoomTarget = ImageZoom(url, zoomToken, 1.75f)
                    }) { Text("放大查看") }
                },
                dismissButton = {
                    Column(horizontalAlignment = Alignment.End) {
                        Row {
                            TextButton(onClick = {
                                longPressImage = null
                                copyImageLink(url)
                            }) { Text("复制链接") }
                            TextButton(onClick = {
                                longPressImage = null
                                saveImageToGallery(url)
                            }) { Text("保存图片") }
                        }
                        Row {
                            // D12：长按即可收藏，不必退出阅读页回详情页
                            TextButton(onClick = {
                                longPressImage = null
                                viewModel.toggleCollect()
                            }) { Text(if (collected) "已在书架" else "加入书架") }
                            TextButton(onClick = { longPressImage = null }) { Text("取消") }
                        }
                    }
                },
            )
        }

        // ===== 边缘竖滑调亮度 / 音量（C2）=====
        // v1.7.6.0：默认关闭 —— 这是视频播放器的交互范式，在漫画里左右边缘各 1/6 的竖滑区
        // 容易与「上下滚动阅读 / 左右翻页」误触（正常翻页划到最左/最右就被吃掉且亮度被改）。
        // 需要时在「设置 → 漫画」里打开；关闭时这里完全不挂载，边缘的竖划如实交给内容。
        if (edgeGesture) {
            ComicReaderEdgeGestures()
        }

        // ===== 菜单 = 顶部栏 + 设置面板 + 底部章节条，由「点击画面」统一呼出 / 收起 =====
        // 此前顶部栏与底部章节条是常驻的，会一直压着图片；现在它们都属于菜单，
        // 不呼出时整屏都是图片。
        AnimatedVisibility(
            visible = menuVisible.value,
            modifier = Modifier.align(Alignment.TopStart),
            enter = fadeIn(),
            exit = fadeOut(),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .background(Color.Black.copy(alpha = 0.7f))
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "‹ 返回",
                    color = Color.White,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier
                        .clickable(onClick = onBack)
                        .padding(8.dp),
                )
                Spacer(Modifier.weight(1f))
                Text(
                    text = chapterTitle,
                    color = Color.White,
                    maxLines = 1,
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.weight(1f))
                // D4：目录抽屉（分卷 + 快速跳章）
                Text(
                    text = "目录",
                    color = Color.White,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier
                        .clickable { showToc = true }
                        .padding(horizontal = 8.dp, vertical = 10.dp),
                )
                // D8：本话评论（源不支持时显示空态）
                Text(
                    text = "评论",
                    color = Color.White,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier
                        .clickable {
                            viewModel.loadComments()
                            showComments = true
                        }
                        .padding(horizontal = 8.dp, vertical = 10.dp),
                )
                // D3：当前页书签开关（实心=已打点）
                IconButton(onClick = { viewModel.toggleBookmark() }) {
                    Icon(
                        imageVector = if (currentPageBookmarked) Icons.Filled.Bookmark
                        else Icons.Filled.BookmarkBorder,
                        contentDescription = if (currentPageBookmarked) "取消书签" else "加书签",
                        tint = if (currentPageBookmarked) Color(0xFFFFC107) else Color.White,
                    )
                }
                IconButton(onClick = { showSettings.value = !showSettings.value }) {
                    Icon(Icons.Filled.Settings, contentDescription = "设置", tint = Color.White)
                }
            }
        }

        // 设置面板（菜单的一部分；上移整个底部章节条的高度，避免被遮挡）
        AnimatedVisibility(
            visible = menuVisible.value && showSettings.value,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = barTotalHeight),
            enter = fadeIn(),
            exit = fadeOut(),
        ) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    // 吞掉面板内的点击，避免点空白处把菜单一起收起
                    .clickable(interactionSource = null, indication = null) { },
                color = Color(0xFF1E1E1E),
                shadowElevation = 8.dp,
            ) {
                Column(
                    modifier = Modifier
                        .padding(16.dp)
                        .heightIn(max = 440.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    // D1：六档阅读模式
                    Text("阅读模式", color = Color.White, style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(8.dp))
                    @OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
                    androidx.compose.foundation.layout.FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        ComicMode.values().forEach { m ->
                            PageModeButton(m.label, pageMode == m.value) {
                                viewModel.setPageMode(m.value)
                            }
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    // D7：每屏页数（自适应 / 单页 / 双页）
                    Text("每屏页数", color = Color.White, style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(8.dp))
                    Row {
                        PageModeButton("自适应", pagesPerScreen == 0) { viewModel.setPagesPerScreen(0) }
                        Spacer(Modifier.width(8.dp))
                        PageModeButton("单页", pagesPerScreen == 1) { viewModel.setPagesPerScreen(1) }
                        Spacer(Modifier.width(8.dp))
                        PageModeButton("双页", pagesPerScreen == 2) { viewModel.setPagesPerScreen(2) }
                    }
                    Spacer(Modifier.height(12.dp))
                    // D9：背景 / 间距 / 适配
                    Text("背景", color = Color.White, style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(8.dp))
                    Row {
                        PageModeButton("纯黑", readerBg == 0) { viewModel.setReaderBg(0) }
                        Spacer(Modifier.width(8.dp))
                        PageModeButton("深灰", readerBg == 1) { viewModel.setReaderBg(1) }
                        Spacer(Modifier.width(8.dp))
                        PageModeButton("纯白", readerBg == 2) { viewModel.setReaderBg(2) }
                    }
                    Spacer(Modifier.height(8.dp))
                    Text("图片间距", color = Color.White, style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(8.dp))
                    Row {
                        listOf(0, 2, 4, 8).forEachIndexed { i, g ->
                            if (i > 0) Spacer(Modifier.width(8.dp))
                            PageModeButton("${g}dp", pageGap == g) { viewModel.setPageGap(g) }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Text("图片适配", color = Color.White, style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(8.dp))
                    Row {
                        PageModeButton("适应宽度", imageScale == 0) { viewModel.setImageScale(0) }
                        Spacer(Modifier.width(8.dp))
                        PageModeButton("适应屏幕", imageScale == 1) { viewModel.setImageScale(1) }
                        Spacer(Modifier.width(8.dp))
                        PageModeButton("裁剪填满", imageScale == 2) { viewModel.setImageScale(2) }
                    }
                    Spacer(Modifier.height(8.dp))
                    // D9：亮度（0 = 跟随系统）
                    var brightnessDraft by remember(readerBrightness) {
                        mutableStateOf(readerBrightness)
                    }
                    Text(
                        text = if (readerBrightness <= 0f) "亮度：跟随系统"
                        else "亮度：${(readerBrightness * 100).toInt()}%",
                        color = Color.White,
                        style = MaterialTheme.typography.titleSmall,
                    )
                    androidx.compose.material3.Slider(
                        value = brightnessDraft,
                        onValueChange = { brightnessDraft = it },
                        onValueChangeFinished = { viewModel.setReaderBrightness(brightnessDraft) },
                        valueRange = 0f..1f,
                    )
                    // D11：状态信息开关
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("显示页码", color = Color.White, style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f))
                        Switch(
                            checked = showPageNumber,
                            onCheckedChange = { viewModel.setShowPageNumber(it) },
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("时钟 / 电量", color = Color.White, style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f))
                        Switch(
                            checked = showClock,
                            onCheckedChange = { viewModel.setShowClock(it) },
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("显示系统状态栏", color = Color.White,
                            style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                        Switch(
                            checked = showSystemBar,
                            onCheckedChange = { viewModel.setShowSystemBar(it) },
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                    // C3 横屏双页开关
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                "横屏双页",
                                color = Color.White,
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            Text(
                                "横屏时左右翻页并排展示两页",
                                color = Color(0xFF9E9E9E),
                                style = MaterialTheme.typography.labelSmall,
                            )
                        }
                        Switch(
                            checked = landscapeDoublePage,
                            onCheckedChange = { viewModel.setLandscapeDoublePage(it) },
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    // C3 方向锁定开关
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                "锁定方向",
                                color = Color.White,
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            Text(
                                "旋转设备不改变屏幕方向，退出阅读自动恢复",
                                color = Color(0xFF9E9E9E),
                                style = MaterialTheme.typography.labelSmall,
                            )
                        }
                        Switch(
                            checked = orientationLocked.value,
                            onCheckedChange = { orientationLocked.value = it },
                        )
                    }
                    // D3：本话书签列表（点击跳页 / × 删除）
                    if (bookmarks.isNotEmpty()) {
                        Spacer(Modifier.height(12.dp))
                        Text(
                            "本话书签（${bookmarks.size}）",
                            color = Color.White,
                            style = MaterialTheme.typography.titleSmall,
                        )
                        Spacer(Modifier.height(6.dp))
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 200.dp)
                                .verticalScroll(rememberScrollState()),
                        ) {
                            bookmarks.forEach { bm ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { viewModel.jumpToBookmark(bm) }
                                        .padding(vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        "第 ${bm.textPosition + 1} 页",
                                        color = Color.White,
                                        style = MaterialTheme.typography.bodyMedium,
                                        modifier = Modifier.weight(1f),
                                    )
                                    IconButton(
                                        onClick = { viewModel.removeBookmark(bm.id) },
                                        modifier = Modifier.size(28.dp),
                                    ) {
                                        Icon(
                                            Icons.Filled.Close,
                                            contentDescription = "删除书签",
                                            tint = Color(0xFF9E9E9E),
                                        )
                                    }
                                }
                            }
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "点击画面呼出 / 收起菜单；滚到本话结尾继续拖 → 下一话，滚到开头继续拖 → 上一话（左右翻页同理）",
                        color = Color(0xFF9E9E9E),
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
        }

        // 底部章节导航条（与小说阅读器一致的「上一话 / 下一话」机制，属菜单）
        AnimatedVisibility(
            visible = menuVisible.value,
            modifier = Modifier.align(Alignment.BottomCenter),
            enter = fadeIn(),
            exit = fadeOut(),
        ) {
            ComicChapterBar(
                chapterTitle = chapterTitle,
                chapterPosition = chapterPosition,
                chapterCount = chapterCount,
                hasPrevChapter = hasPrevChapter,
                hasNextChapter = hasNextChapter,
                onPrevChapter = { viewModel.prevChapter() },
                onNextChapter = { viewModel.nextChapter() },
            )
        }
    }
}

/**
 * 六档阅读模式（D1，对齐 venera 的 `ReaderMode`）。
 *
 * - **连续族**（continuous）：上下滚动 / 左右连续滑动，图片首尾相接；
 * - **画廊族**（gallery）：整页翻页并吸附，可上→下、左→右、右→左。
 */
private enum class ComicMode(val value: Int) {
    CONTINUOUS_TTB(0),
    GALLERY_LTR(1),
    GALLERY_RTL(2),
    CONTINUOUS_LTR(3),
    CONTINUOUS_RTL(4),
    GALLERY_TTB(5);

    /** 是否画廊（整页翻页）族 */
    val isGallery: Boolean
        get() = this == GALLERY_LTR || this == GALLERY_RTL || this == GALLERY_TTB

    /** 是否从右往左（日式漫画） */
    val rtl: Boolean
        get() = this == GALLERY_RTL || this == CONTINUOUS_RTL

    /** 是否上下方向 */
    val vertical: Boolean
        get() = this == CONTINUOUS_TTB || this == GALLERY_TTB

    /** 展示名（菜单 / 设置里用） */
    val label: String
        get() = when (this) {
            CONTINUOUS_TTB -> "连续上下"
            GALLERY_LTR -> "左→右翻页"
            GALLERY_RTL -> "右→左翻页"
            CONTINUOUS_LTR -> "连续左→右"
            CONTINUOUS_RTL -> "连续右→左"
            GALLERY_TTB -> "上下翻页"
        }

    companion object {
        fun of(value: Int): ComicMode = values().firstOrNull { it.value == value } ?: CONTINUOUS_TTB
    }
}

/** 长按菜单「放大查看」的目标：token 递增保证每次点击都生效 */
private data class ImageZoom(val url: String, val token: Int, val scale: Float)

/**
 * 底部常驻章节导航条：`‹ 上一话 ｜ 第 x/y 话 · 话名 ｜ 下一话 ›`。
 *
 * 漫画阅读器此前的章节切换只藏在设置面板里，且滚动模式读完本话没有任何去向；
 * 这里与小说阅读器的「上一章 / 下一章」对齐，任何时候都能直接切话。
 */
@Composable
private fun ComicChapterBar(
    chapterTitle: String,
    chapterPosition: Int,
    chapterCount: Int,
    hasPrevChapter: Boolean,
    hasNextChapter: Boolean,
    onPrevChapter: () -> Unit,
    onNextChapter: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val available = Color.White
    val unavailable = Color.White.copy(alpha = 0.3f)
    Row(
        modifier = modifier
            .fillMaxWidth()
            // 背景铺满整块（含手势条区域），内容再避开系统手势条：
            // 否则章节条被手势条压住，点击命中率低
            .background(Color.Black.copy(alpha = 0.72f))
            .navigationBarsPadding()
            .height(CHAPTER_BAR_HEIGHT)
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(
            onClick = onPrevChapter,
            enabled = hasPrevChapter,
            modifier = Modifier.size(40.dp),
        ) {
            Icon(
                imageVector = Icons.Filled.SkipPrevious,
                contentDescription = "上一话",
                tint = if (hasPrevChapter) available else unavailable,
            )
        }
        Text(
            text = buildString {
                if (chapterCount > 0) append("第 ").append(chapterPosition + 1)
                    .append("/").append(chapterCount).append(" 话")
                if (chapterTitle.isNotBlank()) {
                    if (chapterCount > 0) append(" · ")
                    append(chapterTitle)
                }
                if (chapterCount == 0 && chapterTitle.isBlank()) append("未知章节")
            },
            color = Color.White,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier.weight(1f),
        )
        IconButton(
            onClick = onNextChapter,
            enabled = hasNextChapter,
            modifier = Modifier.size(40.dp),
        ) {
            Icon(
                imageVector = Icons.Filled.SkipNext,
                contentDescription = "下一话",
                tint = if (hasNextChapter) available else unavailable,
            )
        }
    }
}

/**
 * 连续滚动阅读（D1 连续族）：`vertical=true` 上下滚动，否则左右连续滑动（可右→左）。
 *
 * 滚动时预加载后续图片；拖到底 / 顶继续拖切话。
 */
@Composable
private fun ContinuousReader(
    images: List<String>,
    headers: Map<String, String>,
    scripts: Map<String, String>,
    chapterIndex: Int,
    onPrevChapter: () -> Unit,
    onNextChapter: () -> Unit,
    onTap: () -> Unit,
    bottomContentPadding: Dp,
    preloadCount: Int,
    /** D10：>0 时按「屏」换算张数，覆盖 [preloadCount] */
    preloadScreens: Int = 0,
    canPreload: Boolean,
    onImageFailed: (String) -> Unit = {},
    initialPage: Int = 0,
    scrollRequest: Pair<Int, Int>? = null,
    onPageChange: (Int) -> Unit = {},
    vertical: Boolean = true,
    reverse: Boolean = false,
    gapDp: Int = 0,
    contentScale: ContentScale = ContentScale.FillWidth,
    onLongPress: (String) -> Unit = {},
    zoomTarget: ImageZoom? = null,
) {
    val gap = gapDp.dp
    val listState = rememberLazyListState()
    val context = LocalContext.current

    // v1.7.1.0：缩放态按「具体图片 url」判定，不再用外层共享布尔 ——
    // 相邻图片各自回调 true/false 会互相覆盖，导致放大后列表滚动 / 切话被永久锁死。
    var zoomedUrl by remember { mutableStateOf<String?>(null) }
    val anyZoomed = zoomedUrl != null
    LaunchedEffect(chapterIndex) { zoomedUrl = null }

    // 切话后回到顶部：LazyListState 在同一个 Composable 实例上会保留上一话的滚动位置，
    // 不重置的话点「下一话」会直接停在新一话的末尾。
    // v1.9.2.4：**只按 chapterIndex 触发**。此前把 `images` 也当 key，于是任何一次
    // 图片列表变动（单张换址回退、整章重解析）都会把读者弹回第一页 —— 表现为
    // 「读着读着跳回开头 / 一直停在顶部转圈」。
    LaunchedEffect(chapterIndex) {
        listState.scrollToItem(0)
    }

    // D2：首次进入同一章时恢复到上次阅读页（仅一次；换章 initialPage=0 不触发）。
    // 用 remember(chapterIndex) 复位标记，避免整章重解析导致 size 变化时再次跳页。
    var restoredInitial by remember(chapterIndex) { mutableStateOf(false) }
    LaunchedEffect(images.size, initialPage) {
        if (!restoredInitial && images.isNotEmpty() && initialPage > 0) {
            listState.scrollToItem(initialPage.coerceIn(0, images.size - 1))
            restoredInitial = true
        }
    }

    // D2：滚动读到的位置回写页内进度。
    // `firstVisibleItemIndex` 只在跨页时变化，天然比「每帧上报」稀疏；
    // 恢复完成前不上报 —— 否则 `scrollToItem(0)` 的过渡值会把上次存的页覆盖成 0。
    LaunchedEffect(chapterIndex, images.size) {
        snapshotFlow { listState.firstVisibleItemIndex }
            .distinctUntilChanged()
            .collect { idx ->
                if (restoredInitial || initialPage <= 0) onPageChange(idx)
            }
    }

    // D3：书签 / 外部跳页请求（token 变化即定位）
    LaunchedEffect(scrollRequest) {
        val req = scrollRequest ?: return@LaunchedEffect
        if (images.isNotEmpty()) {
            listState.scrollToItem(req.second.coerceIn(0, images.size - 1))
            restoredInitial = true
        }
    }

    // 预加载：当前可见项之后的 [preloadCount] 张
    // D10：开启「按屏幕数」时，用当屏实际可见张数 × 屏数换算 —— 长条图一屏 1 张、
    // 短图一屏 3 张，两种版式下都能保持「滑一屏，下一屏已就绪」。
    LaunchedEffect(
        listState.firstVisibleItemIndex,
        images,
        headers,
        scripts,
        preloadCount,
        preloadScreens,
        canPreload,
    ) {
        if (!canPreload) return@LaunchedEffect
        val perScreen = listState.layoutInfo.visibleItemsInfo.size.coerceAtLeast(1)
        val ahead = if (preloadScreens > 0) {
            (preloadScreens * perScreen).coerceIn(1, 16)
        } else {
            preloadCount
        }
        val start = listState.firstVisibleItemIndex + 1
        for (i in start until (start + ahead).coerceAtMost(images.size)) {
            if (i >= 0) preloadImage(context, images[i], headers, scripts[images[i]])
        }
    }

    val edgeModifier = Modifier
        .fillMaxSize()
        // 放大查看细节时锁住滚动切话（图片平移由缩放组件自己消费，列表不再滚）
        .edgeOverscroll(
            resetKey = chapterIndex,
            vertical = vertical,
            canScrollBackward = { listState.canScrollBackward && !anyZoomed },
            canScrollForward = { listState.canScrollForward && !anyZoomed },
            onOverscrollStart = onPrevChapter,
            onOverscrollEnd = onNextChapter,
        )
        .clickable(
            interactionSource = null,
            indication = null,
            onClick = onTap,
        )

    if (vertical) {
        LazyColumn(
            state = listState,
            modifier = edgeModifier,
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = if (gap > 0.dp) Arrangement.spacedBy(gap) else Arrangement.Top,
            contentPadding = PaddingValues(bottom = bottomContentPadding),
        ) {
            itemsIndexed(images) { _, url ->
                ZoomableComicImage(
                    url = url,
                    // 不再给每张图加 vertical padding：黑底会从缝里透出来，
                    // 在条漫 / 分镜图上表现为一条条横向黑线。间距由 gap 统一控制。
                    modifier = Modifier.fillMaxWidth(),
                    contentScale = contentScale,
                    placeholderModifier = Modifier
                        .fillMaxWidth()
                        .height(240.dp),
                    headers = headers,
                    script = scripts[url],
                    onZoomStateChanged = { z ->
                        zoomedUrl = if (z) url else if (zoomedUrl == url) null else zoomedUrl
                    },
                    // 点击呼出菜单必须走图片自己的手势（它 consume 了 down，父容器 clickable 收不到）
                    onTap = { _ -> onTap() },
                    onLongPress = { onLongPress(url) },
                    zoomRequest = zoomTarget?.takeIf { it.url == url }?.let { it.token to it.scale },
                    onLoadFailed = onImageFailed,
                )
            }
        }
    } else {
        // 横向连续：每项占满一屏宽，按 Fit 显示（右→左时 reverseLayout 反转排布）
        LazyRow(
            state = listState,
            modifier = edgeModifier,
            reverseLayout = reverse,
            horizontalArrangement = if (gap > 0.dp) Arrangement.spacedBy(gap) else Arrangement.Start,
            contentPadding = PaddingValues(end = bottomContentPadding),
        ) {
            itemsIndexed(images) { _, url ->
                ZoomableComicImage(
                    url = url,
                    modifier = Modifier
                        .fillParentMaxWidth()
                        .fillMaxHeight(),
                    contentScale = ContentScale.Fit,
                    placeholderModifier = Modifier.fillParentMaxSize(),
                    headers = headers,
                    script = scripts[url],
                    onZoomStateChanged = { z ->
                        zoomedUrl = if (z) url else if (zoomedUrl == url) null else zoomedUrl
                    },
                    onTap = { _ -> onTap() },
                    onLongPress = { onLongPress(url) },
                    zoomRequest = zoomTarget?.takeIf { it.url == url }?.let { it.token to it.scale },
                    onLoadFailed = onImageFailed,
                )
            }
        }
    }
}

// 章末「本话已读完 / 上一话 / 下一话」引导区已移除：
// 它和「翻到底继续拖 → 下一话」（edgeOverscroll）功能重复，且会占掉一整屏的最后一段，
// 让读者以为本话只有这些内容。现在读完本话继续往下拖即可切话，入口只保留菜单里的底部章节条。

/**
 * 画廊翻页阅读（D1 画廊族）：左右翻页（`reverse=true` 时右→左）或上下整页翻页。
 *
 * 三区点击：横向时左/右为上一页/下一页（右→左阅读时互换），纵向时上/下为上一页/下一页，
 * 中间呼出菜单。
 */
@Composable
private fun GalleryReader(
    images: List<String>,
    headers: Map<String, String>,
    scripts: Map<String, String>,
    currentPage: Int,
    chapterIndex: Int,
    onPageChange: (Int) -> Unit,
    onTap: () -> Unit,
    onPrevChapter: () -> Unit = {},
    onNextChapter: () -> Unit = {},
    preloadCount: Int,
    /** D10：>0 时按「屏」换算张数（双页一屏 2 张），覆盖 [preloadCount] */
    preloadScreens: Int = 0,
    canPreload: Boolean,
    doublePage: Boolean,
    vertical: Boolean = false,
    reverse: Boolean = false,
    gapDp: Int = 0,
    contentScale: ContentScale = ContentScale.FillWidth,
    zoomTarget: ImageZoom? = null,
    onLongPress: (String) -> Unit = {},
    onImageFailed: (String) -> Unit = {},
) {
    // C3 双页模式：每屏并排展示两张图（左图 = 偶数下标），页数 = ceil(n/2)。
    // currentPage 语义统一为「左侧图片下标」（单页模式即图片下标），进度/翻页逻辑不受影响。
    val pageCount = if (doublePage) (images.size + 1) / 2 else images.size
    val pageCountSafe = pageCount.coerceAtLeast(1)

    // 外部 currentPage（图片下标）→ pager 页下标
    val currentPagerPage = if (doublePage) {
        (currentPage.coerceIn(0, images.size - 1).coerceAtLeast(0)) / 2
    } else {
        currentPage.coerceIn(0, pageCountSafe - 1)
    }

    val pagerState = rememberPagerState(
        initialPage = currentPagerPage.coerceIn(0, pageCountSafe - 1),
        pageCount = { pageCountSafe },
    )
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // 同步外部 currentPage 与 pager 状态
    LaunchedEffect(currentPage, doublePage) {
        val target = if (doublePage) {
            (currentPage.coerceIn(0, images.size - 1).coerceAtLeast(0)) / 2
        } else {
            currentPage.coerceAtMost(images.size - 1).coerceAtLeast(0)
        }
        if (pagerState.currentPage != target) {
            pagerState.animateScrollToPage(target)
        }
    }

    // 用户滑动翻页时，回写 currentPage（双页模式回写左图下标）
    LaunchedEffect(pagerState.currentPage, doublePage) {
        val page = pagerState.currentPage
        val imgIdx = if (doublePage) page * 2 else page
        if (imgIdx < images.size) onPageChange(imgIdx)
    }

    val gap = gapDp.dp

    // 预加载相邻页（双页模式翻一页前进两图）
    LaunchedEffect(
        pagerState.currentPage,
        images,
        headers,
        scripts,
        preloadCount,
        preloadScreens,
        canPreload,
        doublePage,
    ) {
        if (!canPreload) return@LaunchedEffect
        val page = pagerState.currentPage
        val step = if (doublePage) 2 else 1
        val startImg = page * step
        // D10：按屏换算时，一屏 = step 张图
        val aheadCount = if (preloadScreens > 0) {
            (preloadScreens * step).coerceIn(1, 16)
        } else {
            preloadCount
        }
        val ahead = ((aheadCount - 1).coerceAtLeast(0)) * step
        for (i in (startImg - step)..(startImg + ahead)) {
            if (i in images.indices) preloadImage(context, images[i], headers, scripts[images[i]])
        }
    }

    // v1.7.1.0：缩放态按「具体图片 url」判定（同 VerticalScrollReader 的说明）
    var zoomedUrl by remember { mutableStateOf<String?>(null) }
    val anyZoomed = zoomedUrl != null
    LaunchedEffect(chapterIndex) { zoomedUrl = null }
    fun onImgZoom(url: String, z: Boolean) {
        zoomedUrl = if (z) url else if (zoomedUrl == url) null else zoomedUrl
    }

    fun goPrevPage() {
        if (pagerState.currentPage > 0) {
            scope.launch { pagerState.animateScrollToPage(pagerState.currentPage - 1) }
        } else {
            onPrevChapter()
        }
    }

    fun goNextPage() {
        if (pagerState.currentPage < pageCountSafe - 1) {
            scope.launch { pagerState.animateScrollToPage(pagerState.currentPage + 1) }
        } else {
            onNextChapter()
        }
    }

    // 三区点击：左 1/3 上一页 / 上一话，右 1/3 下一页 / 下一话，中间呼出菜单。
    // 右→左阅读（reverse）时左右语义互换：点左侧才是「往后读」。
    // 入参是「点击位置在**整屏**里的横向比例」—— 双页模式下由两张图各自换算。
    fun zoneTap(screenFraction: Float) {
        when {
            screenFraction < 1f / 3f -> if (reverse) goNextPage() else goPrevPage()
            screenFraction > 2f / 3f -> if (reverse) goPrevPage() else goNextPage()
            else -> onTap()
        }
    }

    // 纵向翻页：上 1/3 上一页、下 1/3 下一页
    fun zoneTapVertical(screenFraction: Float) {
        when {
            screenFraction < 1f / 3f -> goPrevPage()
            screenFraction > 2f / 3f -> goNextPage()
            else -> onTap()
        }
    }

    val edgeModifier = Modifier
        .fillMaxSize()
        // 末页继续拖 → 下一话；首页往前拖 → 上一话（右→左阅读时方向与回调互换）
        .edgeOverscroll(
            resetKey = chapterIndex,
            vertical = vertical,
            canScrollBackward = {
                (if (reverse) pagerState.currentPage < pageCountSafe - 1
                else pagerState.currentPage > 0) && !anyZoomed
            },
            canScrollForward = {
                (if (reverse) pagerState.currentPage > 0
                else pagerState.currentPage < pageCountSafe - 1) && !anyZoomed
            },
            onOverscrollStart = if (reverse) onNextChapter else onPrevChapter,
            onOverscrollEnd = if (reverse) onPrevChapter else onNextChapter,
        )

    @Composable
    fun PageContent(page: Int) {
        val leftImg = if (doublePage) page * 2 else page
        val rightImg = if (doublePage) leftImg + 1 else -1
        Box(
            modifier = Modifier
                .fillMaxSize()
                .then(
                    if (vertical) {
                        Modifier.comicPagerTapVertical(
                            onTop = { goPrevPage() },
                            onCenter = onTap,
                            onBottom = { goNextPage() },
                        )
                    } else {
                        Modifier.comicPagerTap(
                            onLeft = { if (reverse) goNextPage() else goPrevPage() },
                            onCenter = onTap,
                            onRight = { if (reverse) goPrevPage() else goNextPage() },
                        )
                    },
                ),
            contentAlignment = Alignment.Center,
        ) {
            if (doublePage) {
                // 双页并排：左右各占一半，各用 Fit 适配（保持图片原始比例）
                Row(modifier = Modifier.fillMaxSize()) {
                    // 双页并排时每张图只占半屏，把图内比例折算回整屏比例再判区
                    val lUrl = images.getOrNull(leftImg).orEmpty()
                    ZoomableComicImage(
                        url = lUrl,
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxSize()
                            .padding(horizontal = gap / 2),
                        contentScale = ContentScale.Fit,
                        placeholderModifier = Modifier.fillMaxSize(),
                        headers = headers,
                        script = scripts[lUrl],
                        onZoomStateChanged = { z -> onImgZoom(lUrl, z) },
                        onTap = { f -> zoneTap(f * 0.5f) },
                        onLongPress = { onLongPress(lUrl) },
                        zoomRequest = zoomTarget?.takeIf { it.url == lUrl }
                            ?.let { it.token to it.scale },
                        onLoadFailed = onImageFailed,
                    )
                    val rUrl = images.getOrNull(rightImg).orEmpty()
                    ZoomableComicImage(
                        url = rUrl,
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxSize()
                            .padding(horizontal = gap / 2),
                        contentScale = ContentScale.Fit,
                        placeholderModifier = Modifier.fillMaxSize(),
                        headers = headers,
                        script = scripts[rUrl],
                        onZoomStateChanged = { z -> onImgZoom(rUrl, z) },
                        onTap = { f -> zoneTap(0.5f + f * 0.5f) },
                        onLongPress = { onLongPress(rUrl) },
                        zoomRequest = zoomTarget?.takeIf { it.url == rUrl }
                            ?.let { it.token to it.scale },
                        onLoadFailed = onImageFailed,
                    )
                }
            } else {
                val url = images.getOrNull(page).orEmpty()
                ZoomableComicImage(
                    url = url,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(
                            horizontal = if (vertical) 0.dp else gap / 2,
                            vertical = if (vertical) gap / 2 else 0.dp,
                        ),
                    contentScale = contentScale,
                    placeholderModifier = Modifier.fillMaxSize(),
                    headers = headers,
                    script = scripts[url],
                    onZoomStateChanged = { z -> onImgZoom(url, z) },
                    onTap = { f -> if (vertical) zoneTapVertical(f) else zoneTap(f) },
                    onLongPress = { onLongPress(url) },
                    zoomRequest = zoomTarget?.takeIf { it.url == url }
                        ?.let { it.token to it.scale },
                    onLoadFailed = onImageFailed,
                )
            }
        }
    }

    if (vertical) {
        androidx.compose.foundation.pager.VerticalPager(
            state = pagerState,
            // 放大查看细节时禁止滑动翻页（缩放组件会自己吞掉拖动事件，这里双保险）
            userScrollEnabled = !anyZoomed,
            modifier = edgeModifier,
        ) { page -> PageContent(page) }
    } else {
        HorizontalPager(
            state = pagerState,
            userScrollEnabled = !anyZoomed,
            reverseLayout = reverse,
            modifier = edgeModifier,
        ) { page -> PageContent(page) }
    }
}

/**
 * 翻页模式三区点击：左 1/3 上一页（首页再点→上一话）、右 1/3 下一页（末页再点→下一话）、
 * 中间呼出设置面板。
 *
 * 用 `detectTapGestures` 而非 `awaitEachGesture`：它只响应「按下即抬起」的 tap，
 * 不消费拖动事件，因此不会与 Pager 的滑动翻页抢手势。
 */
private fun Modifier.comicPagerTap(
    onLeft: () -> Unit,
    onCenter: () -> Unit,
    onRight: () -> Unit,
): Modifier = this.pointerInput(Unit) {
    detectTapGestures(
        onTap = { offset ->
            val w = size.width.toFloat()
            if (w <= 0f) return@detectTapGestures
            when {
                offset.x < w / 3f -> onLeft()
                offset.x > w * 2f / 3f -> onRight()
                else -> onCenter()
            }
        },
    )
}

/** 纵向翻页三区点击（D1 画廊上→下）：上 1/3 上一页、下 1/3 下一页、中间菜单 */
private fun Modifier.comicPagerTapVertical(
    onTop: () -> Unit,
    onCenter: () -> Unit,
    onBottom: () -> Unit,
): Modifier = this.pointerInput(Unit) {
    detectTapGestures(
        onTap = { offset ->
            val h = size.height.toFloat()
            if (h <= 0f) return@detectTapGestures
            when {
                offset.y < h / 3f -> onTop()
                offset.y > h * 2f / 3f -> onBottom()
                else -> onCenter()
            }
        },
    )
}

/**
 * 「越过边界」检测：手指已经把内容推到顶 / 底之后仍然继续拖，就触发上一话 / 下一话。
 *
 * 设计要点：
 * - **只观测指针位移，从不 consume**，所以列表自身滚动、惯性、翻页都不受影响；
 * - 触发条件是「主动把内容拖过边界」，而不是「滚到末尾就切」——
 *   后者在内容不足一屏时会一加载就到底，导致连续跳章；
 * - `resetKey` 变化（切话）时清空累计位移，避免上一话的余量把新一话直接顶走。
 *
 * @param vertical true=上下滚动（看 y 轴），false=左右翻页（看 x 轴）
 * @param canScrollBackward 当前是否还能往前滚（false 表示已到顶/首屏）
 * @param canScrollForward 当前是否还能往后滚（false 表示已到底/末页）
 */
@Composable
private fun Modifier.edgeOverscroll(
    resetKey: Any?,
    vertical: Boolean,
    canScrollBackward: () -> Boolean,
    canScrollForward: () -> Boolean,
    onOverscrollStart: () -> Unit,
    onOverscrollEnd: () -> Unit,
): Modifier {
    // 用 rememberUpdatedState 保证回调/状态判断始终读到最新值，
    // 而 pointerInput 的协程只启动一次（否则每帧重启手势循环，永远等不到抬手）
    val backward by rememberUpdatedState(canScrollBackward)
    val forward by rememberUpdatedState(canScrollForward)
    val overStart by rememberUpdatedState(onOverscrollStart)
    val overEnd by rememberUpdatedState(onOverscrollEnd)

    return this.pointerInput(resetKey, vertical) {
        val threshold = EDGE_OVERSCROLL_THRESHOLD.toPx()
        awaitPointerEventScope {
            while (true) {
                val down = awaitFirstDown(requireUnconsumed = false)
                var accStart = 0f
                var accEnd = 0f
                while (true) {
                    val event = awaitPointerEvent()
                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                    if (!change.pressed) break
                    // 手指位移：< 0 → 往「后」拖（下滚 / 右翻），> 0 → 往「前」拖
                    val delta = if (vertical) {
                        change.position.y - change.previousPosition.y
                    } else {
                        change.position.x - change.previousPosition.x
                    }
                    if (delta == 0f) continue
                    if (delta < 0f) {
                        accStart = 0f
                        if (forward()) {
                            accEnd = 0f
                        } else {
                            accEnd += -delta
                            if (accEnd >= threshold) {
                                accEnd = 0f
                                overEnd()
                            }
                        }
                    } else {
                        accEnd = 0f
                        if (backward()) {
                            accStart = 0f
                        } else {
                            accStart += delta
                            if (accStart >= threshold) {
                                accStart = 0f
                                overStart()
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * 漫画图片（完美适配屏幕）。
 *
 * 宽度撑满、高度按图片原始比例缩放（ContentScale.FillWidth）。
 * 加载中显示占位，加载失败显示占位 + 点击重试。
 *
 * [script] 为源给出的逐图后处理脚本（「按 N 段纵向打乱」的还原方式），
 * 命中时挂一个 Coil transformation，让**解码后、进缓存前**就把图拼回正常顺序。
 */
@Composable
internal fun ComicImage(
    url: String,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.FillWidth,
    placeholderModifier: Modifier = Modifier,
    headers: Map<String, String> = emptyMap(),
    script: String? = null,
    /** 加载失败回调：交给上层向源要备用镜像地址（v1.7.1.0） */
    onLoadFailed: (String) -> Unit = {},
) {
    val context = LocalContext.current
    // 重试计数：并入缓存键，点击重试时产生全新请求
    var retry by remember(url) { mutableIntStateOf(0) }
    // 失败原因（v1.7.4.0）：域名不可达与「图挂了」要分开说 —— CCC 追漫台这类源的
    // 图片域名在当前网络就是连不上，用户看到一句「点击重试」会一直点下去，
    // 而重试永远不可能成功。说清是网络不可达，他才知道该换网络而不是怪 App。
    var unreachable by remember(url) { mutableStateOf(false) }
    val bands = remember(url, script) { ComicImageScramble.bandCountFromScript(script) }
    val request = remember(url, retry, headers, bands) {
        ImageRequest.Builder(context)
            .data(url)
            .apply { applyImageDefaults(url, headers) }
            .apply { applyBandUnscramble(this, bands) }
            .memoryCacheKey("$url#$retry")
            // 失败时把「原因」落到日志，便于区分 403 防盗链 / DNS / 解码失败。
            // 只记异常类名：异常消息里往往带图片地址，日志不出现具体站点。
            .listener(
                // D16：加载结束清掉进度，避免残留一个停在 99% 的旧值
                onSuccess = { _, _ ->
                    com.novelreader.core.image.ImageDownloadProgress.clear(url)
                },
                onError = { _, result ->
                    android.util.Log.w(
                        IMAGE_LOG_TAG,
                        "图片加载失败 err=${result.throwable?.javaClass?.simpleName}",
                    )
                    unreachable = isNetworkUnreachable(result.throwable)
                    // v1.7.1.0：先让上层试一次备用镜像（禁漫天堂等源有 5 条线路），
                    // 拿到新地址后 url 变化会让本组件以新地址重新加载
                    onLoadFailed(url)
                },
            )
            .build()
    }

    SubcomposeAsyncImage(
        model = request,
        contentDescription = null,
        modifier = modifier,
        contentScale = contentScale,
        loading = { ComicLoading(placeholderModifier, url) },
        error = {
            // 惰性占位地址（v1.9.2.3）：这张图还没解析过真实地址，Coil 必然打不开。
            // 这不是「加载失败」，而 onLoadFailed 已经在上面触发解析了 —— 此时应该
            // 继续显示加载占位，别让用户看到一闪而过的「图片加载失败」。
            if (com.novelreader.core.image.LazyImage.isLazy(url)) {
                ComicLoading(placeholderModifier, url)
            } else {
                ComicError(
                    placeholderModifier,
                    unreachable = unreachable,
                    onRetry = {
                        onLoadFailed(url)
                        retry++
                    },
                )
            }
        },
    )
}

/**
 * 判断图片加载失败是否属于**网络不可达**（v1.7.4.0）。
 *
 * 只看异常类名链：Coil 会把真实原因包几层，而且异常消息里常带图片地址，
 * 日志与界面都不该出现具体站点。命中这几类就说明「这个域名在当前网络连不上」，
 * 与 403 防盗链 / 图片本身损坏不是一回事，处理办法也完全不同。
 */
private fun isNetworkUnreachable(t: Throwable?): Boolean {
    val names = setOf(
        "UnknownHostException", "ConnectException", "SocketTimeoutException",
        "NoRouteToHostException", "PortUnreachableException", "SSLHandshakeException",
    )
    var e = t
    var depth = 0
    while (e != null && depth < 5) {
        if (e.javaClass.simpleName in names) return true
        e = e.cause
        depth++
    }
    return false
}

/** D11：右上角时钟 + 电量（每 30 秒刷新） */
@Composable
private fun ReaderClock(dark: Boolean, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var text by remember { mutableStateOf(clockText(context)) }
    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(30_000)
            text = clockText(context)
        }
    }
    Text(
        text = text,
        color = if (dark) Color(0xFF444444) else Color.White.copy(alpha = 0.85f),
        style = MaterialTheme.typography.labelSmall,
        modifier = modifier,
    )
}

/** `HH:mm  电量%` —— 电量取自 BATTERY_CHANGED 粘性广播，读不到就只显示时间 */
private fun clockText(context: Context): String {
    val cal = java.util.Calendar.getInstance()
    val time = String.format(
        "%02d:%02d",
        cal.get(java.util.Calendar.HOUR_OF_DAY),
        cal.get(java.util.Calendar.MINUTE),
    )
    val battery = runCatching {
        val intent = context.registerReceiver(
            null,
            android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED),
        )
        val level = intent?.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = intent?.getIntExtra(android.os.BatteryManager.EXTRA_SCALE, -1) ?: -1
        if (level >= 0 && scale > 0) level * 100 / scale else null
    }.getOrNull()
    return if (battery != null) "$time  $battery%" else time
}

/**
 * 加载中占位。
 *
 * D16：有字节进度时在转圈下方显示百分比。网络命中磁盘缓存时拦截器不跑，
 * 进度为 null，就退回原来的纯转圈（不算异常，是缓存命中）。
 */
@Composable
private fun ComicLoading(modifier: Modifier, url: String = "") {
    val progress: Float? by remember(url) {
        if (url.isBlank()) {
            kotlinx.coroutines.flow.MutableStateFlow<Float?>(null)
        } else {
            com.novelreader.core.image.ImageDownloadProgress.flow(url)
        }
    }.collectAsState()
    Box(modifier, contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator(
                color = Color(0xFF888888),
                strokeWidth = 2.dp,
            )
            val p = progress
            if (p != null) {
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "${(p * 100).toInt()}%",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color(0xFF888888),
                )
            }
        }
    }
}

/**
 * 加载失败占位（可点击重试）。
 *
 * [unreachable] 为 true 时说明是**网络连不上这个域名**（不是图坏了、也不是防盗链），
 * 这时直说「不可达」并给出「换网络 / 代理」的提示，而不是让用户对着
 * 「点击重试」反复点 —— 那种情况下重试永远不可能成功。
 */
@Composable
private fun ComicError(
    modifier: Modifier,
    unreachable: Boolean = false,
    onRetry: () -> Unit,
) {
    Box(modifier, contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                imageVector = Icons.Filled.BrokenImage,
                contentDescription = null,
                tint = Color(0xFF888888),
            )
            Spacer(Modifier.height(4.dp))
            if (unreachable) {
                Text(
                    "图片域名当前不可达",
                    color = Color(0xFFBBBBBB),
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    "该站点需要可用的网络环境",
                    color = Color(0xFF888888),
                    style = MaterialTheme.typography.labelSmall,
                )
            }
            Spacer(Modifier.height(4.dp))
            TextButton(onClick = onRetry) {
                Text(
                    if (unreachable) "重试" else "图片加载失败，点击重试",
                    color = Color(0xFF6A9BD8),
                )
            }
        }
    }
}

/**
 * 命中「分段乱序」还原脚本时挂上 transformation。
 *
 * 变换要读写像素，必须用软件位图（hardware bitmap 不支持变换），因此显式关掉。
 */
private fun applyBandUnscramble(builder: ImageRequest.Builder, bands: Int?) {
    if (bands == null || bands < 2) return
    builder.transformations(ReverseBandsTransformation(bands))
    builder.allowHardware(false)
}

/** 预加载单张图片（异步入队，失败静默） */
private fun preloadImage(
    context: Context,
    url: String,
    headers: Map<String, String> = emptyMap(),
    script: String? = null,
) {
    if (url.isBlank()) return
    // 惰性占位地址（v1.9.2.3）：真实地址还没解析，预取只会白跑一次失败请求。
    // 真正的解析由它进入视口后的 onLoadFailed → retryImage 触发。
    if (com.novelreader.core.image.LazyImage.isLazy(url)) return
    val request = ImageRequest.Builder(context)
        .data(url)
        .apply { applyImageDefaults(url, headers) }
        .apply { applyBandUnscramble(this, ComicImageScramble.bandCountFromScript(script)) }
        // 与 ComicImage 的缓存键保持同一套（它的 retry 从 0 起），预热才真正命中
        .memoryCacheKey("$url#0")
        .build()
    context.imageLoader.enqueue(request)
}

/**
 * 给漫画图片请求补齐「浏览器级」默认头。
 *
 * 源自己声明的请求头（legado 的 `header=` / Venera 的 `headers`）优先级最高，一律不覆盖；
 * 缺失时按下面三条补齐 —— 这是大量漫画 CDN 直接返回 403 / 空体的常见原因：
 * - `User-Agent`：Coil 默认发 `okhttp/x.y`，不少图床按 UA 判断是否放行；
 * - `Referer`：源没给时退化为图片自身站点（同站点图床常据此防盗链）；
 * - `Accept`：声明可接受的图片类型，避免部分 CDN 因 Accept 不匹配返回错误页。
 */
private fun ImageRequest.Builder.applyImageDefaults(url: String, headers: Map<String, String>) {
    headers.forEach { (k, v) -> setHeader(k, v) }
    val declared = headers.keys.map { it.lowercase() }
    if ("user-agent" !in declared) setHeader("User-Agent", IMAGE_USER_AGENT)
    if ("referer" !in declared) {
        // v1.9.2.7：兜底用**站点根域**（`images.xxx.com` → `xxx.com`），
        // 与 CoverDefaultsInterceptor 同一口径。图床的防盗链只认站点页面的 Referer。
        com.novelreader.core.image.ImageReferer.of(url)?.let { setHeader("Referer", it) }
    }
    if ("accept" !in declared) {
        setHeader("Accept", "image/avif,image/webp,image/apng,image/*,*/*;q=0.8")
    }
}

/** 翻页方式选择按钮 */
@Composable
private fun PageModeButton(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val bg = if (selected) Color(0xFF4A6FA5) else Color(0xFF333333)
    val fg = if (selected) Color.White else Color(0xFFAAAAAA)
    Text(
        text = text,
        color = fg,
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier
            .background(bg, RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

/** 图片加载日志标签（排查「图片加载失败」时用 logcat 过滤它） */
private const val IMAGE_LOG_TAG = "ComicImage"

/**
 * 图片请求默认 UA（Coil 默认的 okhttp/x.y 会被部分图床拒绝）。
 *
 * v1.9.2.3 起统一取 [com.novelreader.core.network.UserAgents.WEB]（桌面版 Chrome，
 * 对齐 venera 官方 `webUA`），不再各自硬编码一份移动版 UA。
 */
private const val IMAGE_USER_AGENT = com.novelreader.core.network.UserAgents.WEB

/** 底部章节导航条高度（正文/设置面板都据此让位） */
private val CHAPTER_BAR_HEIGHT = 48.dp

/** 越过边界触发翻话所需的累计拖拽距离（太小会误触，太大要拖很久） */
private val EDGE_OVERSCROLL_THRESHOLD = 90.dp
