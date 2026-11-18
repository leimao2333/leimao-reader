package com.novelreader.ui.screens.comic

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.novelreader.core.image.ComicImageSaver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.novelreader.domain.model.ChapterItem
import com.novelreader.domain.model.MediaItem

/**
 * 漫画详情与章节列表页。
 */
@Composable
fun ComicDetailScreen(
    viewModel: ComicDetailViewModel,
    item: MediaItem,
    onChapterClick: (ChapterItem) -> Unit,
    onRead: () -> Unit,
    onBack: () -> Unit,
    onTagClick: (String) -> Unit = {},
    /** 打开下载管理页（D32） */
    onOpenCache: () -> Unit = {},
) {
    val detail by viewModel.detail.collectAsState()
    val chapters by viewModel.chapters.collectAsState()
    val loading by viewModel.loading.collectAsState()
    val chapterError by viewModel.chapterError.collectAsState()
    val collected by viewModel.collected.collectAsState()
    val download by viewModel.download.collectAsState()
    val chapterDesc by viewModel.chapterDesc.collectAsState()
    // 批量离线缓存（v1.2.11.0）
    val cacheProgress by viewModel.cacheProgress.collectAsState()
    var showCacheDialog by remember { mutableStateOf(false) }
    // D22 / D21：大图查看（封面与样张共用一个查看器）
    var viewerUrl by remember { mutableStateOf<String?>(null) }
    val coverContext = LocalContext.current
    val coverScope = rememberCoroutineScope()
    // D21 / D23 / D24：样张画廊 + 章节预览
    val previews by viewModel.previews.collectAsState()
    val previewLoading by viewModel.previewLoading.collectAsState()
    val chapterPreview by viewModel.chapterPreview.collectAsState()
    // 样张请求头：图床有防盗链，不带 Referer/Cookie 会整片灰块
    val previewHeaders by viewModel.previewHeaders.collectAsState()
    // 「加载全部」状态与进度
    val previewLoadingAll by viewModel.previewLoadingAll.collectAsState()
    val previewLoadAllProgress by viewModel.previewLoadAllProgress.collectAsState()
    val previewModel: (String) -> Any = remember(previewHeaders) {
        { url ->
            if (previewHeaders.isEmpty() || !url.startsWith("http")) {
                url
            } else {
                coil.request.ImageRequest.Builder(coverContext)
                    .data(url)
                    .apply { previewHeaders.forEach { (k, v) -> addHeader(k, v) } }
                    .build()
            }
        }
    }
    // D33：评论预览
    val comments by viewModel.comments.collectAsState()
    val commentsLoading by viewModel.commentsLoading.collectAsState()
    val commentsTitle by viewModel.commentsTitle.collectAsState()
    var showComments by remember { mutableStateOf(false) }

    LaunchedEffect(item) {
        viewModel.load(item)
    }

    val display = detail ?: item
    // 倒序仅改变展示顺序：点击仍用章节自身的 index，阅读器的「上一话/下一话」按源顺序走
    val visibleChapters = remember(chapters, chapterDesc) {
        if (chapterDesc) chapters.asReversed().toList() else chapters
    }
    // C4 目录分卷：从章节标题解析卷号分组；解析不出卷结构时退回扁平列表
    val groupedChapters = remember(visibleChapters) {
        ComicChapterVolume.groupByVolume(visibleChapters)
    }
    val volumeStructured = remember(visibleChapters) {
        ComicChapterVolume.isVolumeStructured(visibleChapters)
    }

    val listState = rememberLazyListState()
    // 切换正/倒序后滚到章节标题处，让顺序变化立即可见
    var lastDesc by remember { mutableStateOf(chapterDesc) }
    LaunchedEffect(chapterDesc) {
        if (lastDesc != chapterDesc) {
            lastDesc = chapterDesc
            listState.animateScrollToItem(1)
        }
    }

    // enableEdgeToEdge 下自绘顶栏必须自行避让系统栏
    Column(
        modifier = Modifier
            .fillMaxSize()
            // 夜间模式跟随：本页是裸 Column（不是 Scaffold），不铺背景就会透出
            // window 的白色背景，导致全局夜间开启时这里仍是刺眼白底。
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "‹ 返回",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .clickable(onClick = onBack)
                    .padding(8.dp),
            )
        }

        if (loading && chapters.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            return@Column
        }

        if (!loading && chapters.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = if (chapterError != null) "目录拉取失败" else "没有获取到章节",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (chapterError != null) {
                        Spacer(Modifier.height(6.dp))
                        // v1.7.1.0：区分「源报错」与「确实没有目录」，不再一律说「源已失效」
                        Text(
                            text = "$chapterError\n可尝试换源，或检查网络 / 代理后重试",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(horizontal = 32.dp),
                        )
                    } else {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = "该源没有返回任何章节，可能源已失效",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                    OutlinedButton(onClick = { viewModel.load(item) }) { Text("重新加载") }
                }
            }
            return@Column
        }

        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 24.dp),
        ) {
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                ) {
                    // D22：封面点击查看大图 + 保存
                    Box(
                        modifier = Modifier
                            .size(width = 96.dp, height = 128.dp)
                            .clickable(enabled = !display.cover.isNullOrBlank()) {
                                viewerUrl = display.cover
                            },
                    ) {
                        AsyncImage(
                            model = display.cover,
                            contentDescription = display.title,
                            modifier = Modifier.matchParentSize(),
                            contentScale = ContentScale.Crop,
                        )
                    }
                    Spacer(Modifier.width(16.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = display.title,
                            style = MaterialTheme.typography.titleLarge,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Spacer(Modifier.height(8.dp))
                        display.author?.let {
                            Text(
                                text = it,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (display.sourceName.isNotBlank()) {
                            Text(
                                text = "来源：${display.sourceName}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                        Spacer(Modifier.height(12.dp))
                        // FlowRow 自动换行：窄屏下 4 个按钮放不进一行时不再溢出被裁
                        @OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
                        androidx.compose.foundation.layout.FlowRow(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Button(
                                onClick = onRead,
                                enabled = chapters.isNotEmpty(),
                            ) {
                                Text(if (chapters.isEmpty()) "暂无章节" else "开始阅读")
                            }
                            // 文案收紧到「书架」，四个按钮才能在 360dp 宽的手机上排成一行；
                            // 之前「加入书架」会把「缓存」挤到第二行居中，看着像布局错位
                            if (collected) {
                                OutlinedButton(onClick = viewModel::toggleCollect) { Text("已收藏") }
                            } else {
                                OutlinedButton(onClick = viewModel::toggleCollect) { Text("书架") }
                            }
                            OutlinedButton(onClick = viewModel::downloadComic) { Text("下载") }
                            OutlinedButton(onClick = { showCacheDialog = true }) { Text("缓存") }
                        }
                    }
                }
                // 下载进度
                download?.let { state ->
                    Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                        if (state.isDownloading) {
                            androidx.compose.material3.LinearProgressIndicator(
                                progress = { state.progress },
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                text = "下载中… ${(state.progress * 100).toInt()}%",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        state.message?.let {
                            Text(
                                text = it,
                                style = MaterialTheme.typography.bodySmall,
                                color = if (state.success) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.error,
                            )
                        }
                        Spacer(Modifier.height(8.dp))
                    }
                }
            }

            // 标签小栏（v1.6.5.0）：浏览上下文 + extras["kind"] 派生，点击跳标签搜索
            item(key = "tag_bar") {
                com.novelreader.ui.components.DetailTagBar(
                    tags = com.novelreader.core.util.ItemTags.of(display),
                    onTagClick = onTagClick,
                )
            }

            // D21：样张预览（列表式，整页上下滑动看全部；「加载更多」就地追加）
            if (previews.isNotEmpty() || previewLoading) {
                item(key = "preview_header") {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "样张预览（${previews.size}）",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(1f),
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            // 「加载全部」：串行拉完剩余所有章节，进度回显成「已处理/总数」
                            when {
                                previewLoadingAll -> {
                                    val progress = previewLoadAllProgress
                                    Text(
                                        text = progress?.let { "${it.first}/${it.second}" } ?: "加载中…",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.padding(horizontal = 8.dp),
                                    )
                                }
                                else -> Text(
                                    text = "加载全部",
                                    color = MaterialTheme.colorScheme.primary,
                                    style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier
                                        .clickable(enabled = !previewLoading) {
                                            viewModel.loadAllPreviews()
                                        }
                                        .padding(8.dp),
                                )
                            }
                            Text(
                                text = if (previewLoading) "加载中…" else "加载更多",
                                color = MaterialTheme.colorScheme.primary,
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier
                                    .clickable(enabled = !previewLoading && !previewLoadingAll) {
                                        viewModel.loadMorePreviews()
                                    }
                                    .padding(8.dp),
                            )
                        }
                    }
                }
                // 每行 3 张，作为外层 LazyColumn 的 item —— 天然可上下滚动、能看全部，
                // 且不与父级 LazyColumn 抢滚动（LazyRow 改 LazyVerticalGrid 会直接崩）
                items(
                    previews.chunked(3),
                    key = { row -> "preview_row_${row.first()}" },
                ) { row ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 3.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        row.forEach { url ->
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .aspectRatio(0.72f)
                                    .clip(RoundedCornerShape(6.dp))
                                    // 夜间占位压暗：整块 surfaceVariant 在深色页面上像一块块灰板，
                                    // 加载期间观感很脏，这里降到 40% 不透明度当作图片底座
                                    .background(
                                        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                                    )
                                    .clickable { viewerUrl = url },
                            ) {
                                AsyncImage(
                                    model = previewModel(url),
                                    contentDescription = null,
                                    modifier = Modifier.matchParentSize(),
                                    contentScale = ContentScale.Crop,
                                )
                            }
                        }
                        // 末行不足 3 张时补空位，保持每格宽高比一致
                        repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
                if (previewLoading) {
                    item(key = "preview_loading") {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp,
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = "正在加载更多样张…",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }

            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "章节（${chapters.size}）",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f),
                    )
                    // D33：第一话评论预览（源不支持时显示空态）
                    Text(
                        text = "评论",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .clickable {
                                viewModel.loadCommentsPreview()
                                showComments = true
                            }
                            .padding(horizontal = 8.dp, vertical = 6.dp),
                    )
                    Spacer(Modifier.width(4.dp))
                    OrderChip(
                        text = if (chapterDesc) "倒序" else "正序",
                        selected = chapterDesc,
                        onClick = { viewModel.toggleChapterOrder() },
                    )
                }
            }

            // C4 目录分卷：按卷分组展示（卷头 + 卷内章节），解析不出卷结构时退回扁平列表。
            for ((volumeName, volumeChapters) in groupedChapters) {
                if (volumeStructured) {
                    item(key = "volume_$volumeName") {
                        Text(
                            text = "$volumeName（${volumeChapters.size}）",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier
                                .background(
                                    MaterialTheme.colorScheme.surfaceVariant,
                                    RoundedCornerShape(4.dp),
                                )
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                        )
                    }
                }
                items(volumeChapters, key = { "${it.index}_${it.url}" }) { chapter ->
                    @OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            // D24：点击进章节，长按先看几张（预览卡）
                            .combinedClickable(
                                onClick = { onChapterClick(chapter) },
                                onLongClick = { viewModel.previewChapter(chapter) },
                            )
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = chapter.title,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        // D23：先看几张再决定读
                        Text(
                            text = "预览",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier
                                .clickable { viewModel.previewChapter(chapter) }
                                .padding(start = 12.dp, top = 4.dp, bottom = 4.dp),
                        )
                    }
                    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                }
            }
        }

            // ===== 批量离线缓存弹窗（v1.2.11.0）：进行中显示进度，否则范围选择/完成提示 =====
            if (cacheProgress?.running == true) {
                val p = cacheProgress!!
                androidx.compose.material3.AlertDialog(
                    onDismissRequest = {},
                    title = { Text("离线缓存") },
                    text = {
                        Column {
                            androidx.compose.material3.LinearProgressIndicator(
                                progress = { if (p.total > 0) p.done.toFloat() / p.total else 0f },
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Spacer(Modifier.height(8.dp))
                            Text(text = "正在缓存 ${p.done}/${p.total}（新缓存 ${p.newCached}）")
                        }
                    },
                    confirmButton = {},
                    dismissButton = {
                        androidx.compose.material3.TextButton(onClick = { viewModel.cancelCache() }) { Text("取消") }
                    },
                )
            } else if (showCacheDialog) {
                androidx.compose.material3.AlertDialog(
                    onDismissRequest = { showCacheDialog = false },
                    title = { Text("离线缓存") },
                    text = { Text("选择要缓存的章节范围，缓存完成后无网也能阅读") },
                    confirmButton = {
                        androidx.compose.material3.TextButton(onClick = {
                            showCacheDialog = false
                            viewModel.cacheAll()
                        }) { Text("缓存全书") }
                    },
                    dismissButton = {
                        Row {
                            androidx.compose.material3.TextButton(onClick = {
                                showCacheDialog = false
                                viewModel.cacheFirst50()
                            }) { Text("前50话") }
                            androidx.compose.material3.TextButton(onClick = {
                                showCacheDialog = false
                                viewModel.cacheNext50()
                            }) { Text("后50话") }
                            // D32：已缓存章节的下载管理页
                            androidx.compose.material3.TextButton(onClick = {
                                showCacheDialog = false
                                onOpenCache()
                            }) { Text("管理") }
                            androidx.compose.material3.TextButton(onClick = { showCacheDialog = false }) { Text("取消") }
                        }
                    },
                )
            }
            cacheProgress?.takeIf { !it.running && it.message != null }?.let { p ->
                androidx.compose.material3.AlertDialog(
                    onDismissRequest = { viewModel.clearCacheProgress() },
                    title = { Text("离线缓存") },
                    text = { Text(p.message ?: "") },
                    confirmButton = {
                        androidx.compose.material3.TextButton(onClick = { viewModel.clearCacheProgress() }) { Text("确定") }
                    },
                )
            }

        // ===== D33 评论预览 =====
        if (showComments) {
            CommentsDialog(
                title = "评论 · " + commentsTitle.ifBlank { display.title },
                comments = comments,
                loading = commentsLoading,
                onDismiss = { showComments = false },
            )
        }

        // ===== D23 / D24 章节预览卡（长按章节或点「预览」，先看几张再决定读） =====
        chapterPreview?.let { p ->
            androidx.compose.material3.AlertDialog(
                onDismissRequest = { viewModel.dismissChapterPreview() },
                title = { Text(p.title) },
                text = {
                    when {
                        p.loading -> Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                strokeWidth = 2.dp,
                            )
                            Spacer(Modifier.width(10.dp))
                            Text("正在加载样张…")
                        }
                        p.images.isEmpty() -> Text("该章没有可预览的图片")
                        // 列表式预览（上下滑动看全部），与详情页样张区保持一致的观感
                        else -> androidx.compose.foundation.lazy.LazyColumn(
                            modifier = Modifier.heightIn(max = 420.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            items(p.images.chunked(3), key = { row -> row.first() }) { row ->
                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    row.forEach { url ->
                                        Box(
                                            modifier = Modifier
                                                .weight(1f)
                                                .aspectRatio(0.72f)
                                                .clip(RoundedCornerShape(6.dp))
                                                .background(
                                                    MaterialTheme.colorScheme.surfaceVariant
                                                        .copy(alpha = 0.4f),
                                                )
                                                .clickable { viewerUrl = url },
                                        ) {
                                            AsyncImage(
                                                model = previewModel(url),
                                                contentDescription = null,
                                                modifier = Modifier.matchParentSize(),
                                                contentScale = ContentScale.Crop,
                                            )
                                        }
                                    }
                                    repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                                }
                            }
                        }
                    }
                },
                confirmButton = {
                    androidx.compose.material3.TextButton(
                        onClick = { viewModel.dismissChapterPreview() },
                    ) { Text("关闭") }
                },
            )
        }

        // ===== D22 / D21 大图查看（封面、样张共用）+ 保存到相册 =====
        viewerUrl?.let { viewer ->
            var saving by remember(viewer) { mutableStateOf(false) }
            var coverHint by remember(viewer) { mutableStateOf<String?>(null) }

            // 下载原图并写入相册（阻塞 IO 放到 Dispatchers.IO）
            fun startSave() {
                if (saving || viewer.isBlank()) return
                saving = true
                coverHint = null
                coverScope.launch {
                    val saved = withContext(Dispatchers.IO) {
                        // 带上防盗链头，否则保存下来的是 403 页面
                        ComicImageSaver.saveToGallery(coverContext, viewer, previewHeaders)
                    }
                    saving = false
                    coverHint = if (saved) "已保存到相册" else "保存失败，请重试"
                }
            }

            // API 28 及以下写相册公共目录需要存储权限；29+ 走 MediaStore 分区存储免权限
            val storagePermission = android.Manifest.permission.WRITE_EXTERNAL_STORAGE
            val needStoragePermission =
                android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.Q &&
                    androidx.core.content.ContextCompat.checkSelfPermission(
                        coverContext,
                        storagePermission,
                    ) != android.content.pm.PackageManager.PERMISSION_GRANTED
            val permissionLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
                androidx.activity.result.contract.ActivityResultContracts.RequestPermission(),
            ) { granted ->
                if (granted) startSave() else coverHint = "未授予存储权限，无法保存到相册"
            }

            Dialog(
                onDismissRequest = { if (!saving) viewerUrl = null },
                // 关掉默认宽度限制，让大图铺满屏幕
                properties = DialogProperties(usePlatformDefaultWidth = false),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black),
                ) {
                    AsyncImage(
                        // 大图与缩略图共用同一套请求头（防盗链站点不带就是 403）
                        model = previewModel(viewer),
                        contentDescription = display.title,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Fit,
                    )
                    Column(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        coverHint?.let {
                            Text(
                                text = it,
                                style = MaterialTheme.typography.bodyMedium,
                                color = Color.White,
                                modifier = Modifier.padding(bottom = 12.dp),
                            )
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Button(
                                onClick = {
                                    if (needStoragePermission) {
                                        permissionLauncher.launch(storagePermission)
                                    } else {
                                        startSave()
                                    }
                                },
                                enabled = !saving,
                            ) {
                                Text(if (saving) "保存中…" else "保存到相册")
                            }
                            OutlinedButton(
                                onClick = { viewerUrl = null },
                                enabled = !saving,
                            ) { Text("关闭") }
                        }
                    }
                }
            }
        }
    }
}

/**
 * 目录排序切换按钮（正序 / 倒序）。
 *
 * 与小说阅读器目录面板的「正序/倒序」对齐：漫画站有的从第 1 话排到最新话，
 * 有的相反，给用户一个直接切换的入口。
 */
@Composable
private fun OrderChip(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val bg = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant
    val fg = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
    Text(
        text = text,
        color = fg,
        style = MaterialTheme.typography.labelLarge,
        modifier = Modifier
            .background(bg, RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 6.dp),
    )
}
