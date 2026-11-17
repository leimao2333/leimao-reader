package com.novelreader.ui.screens.novel

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImagePainter
import coil.compose.SubcomposeAsyncImage
import coil.compose.SubcomposeAsyncImageContent
import com.novelreader.domain.model.ChapterItem
import com.novelreader.domain.model.MediaItem
import com.novelreader.ui.components.BookMeta
import com.novelreader.ui.components.CoverPlaceholderLetter
import com.novelreader.ui.components.DetailTagBar

/**
 * 小说详情与目录页。
 *
 * 展示封面、标题、作者、简介、收藏按钮和章节列表。
 * 提供「阅读」直达阅读页与「换源」面板（跳转到同名同作者的其他源详情）。
 */
@Composable
fun NovelDetailScreen(
    viewModel: NovelDetailViewModel,
    item: MediaItem,
    onChapterClick: (ChapterItem) -> Unit,
    onRead: (MediaItem) -> Unit,
    onSwitchSource: (MediaItem) -> Unit,
    onBack: () -> Unit,
    onSearchAuthor: (String) -> Unit = {},
    onTagClick: (String) -> Unit = {},
) {
    val detail by viewModel.detail.collectAsState()
    val chapters by viewModel.chapters.collectAsState()
    val loading by viewModel.loading.collectAsState()
    val collected by viewModel.collected.collectAsState()
    val download by viewModel.download.collectAsState()
    val changeSourceOpen by viewModel.changeSourceOpen.collectAsState()
    val changeSourceCandidates by viewModel.changeSourceCandidates.collectAsState()
    val changeSourceLoading by viewModel.changeSourceLoading.collectAsState()
    val changeSourceError by viewModel.changeSourceError.collectAsState()
    val changeSourceProgress by viewModel.changeSourceProgress.collectAsState()
    val hasProgress by viewModel.hasProgress.collectAsState()
    val notice by viewModel.notice.collectAsState()
    val cacheProgress by viewModel.cacheProgress.collectAsState()
    val note by viewModel.note.collectAsState()

    LaunchedEffect(item) {
        viewModel.load(item)
    }

    // 换源提示自动消失
    LaunchedEffect(notice) {
        if (notice != null) {
            kotlinx.coroutines.delay(1800)
            viewModel.dismissNotice()
        }
    }

    // 批量离线缓存弹窗开关（v1.2.3.0）
    var showCacheDialog by rememberSaveable { mutableStateOf(false) }
    // 备注弹窗开关 + 草稿（草稿单独存，避免边输入边写库；取消时原备注不受影响）
    var showNoteDialog by rememberSaveable { mutableStateOf(false) }
    var noteDraft by rememberSaveable { mutableStateOf("") }
    // ===== 批量离线缓存弹窗（v1.2.3.0）：进行中显示进度，否则显示范围选择/完成提示 =====
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
            text = { Text("选择要缓存的章节范围，缓存完成后可离线阅读") },
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
                    }) { Text("前50章") }
                    androidx.compose.material3.TextButton(onClick = {
                        showCacheDialog = false
                        viewModel.cacheNext50()
                    }) { Text("后50章") }
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

    // ===== 备注编辑弹窗（C7） =====
    if (showNoteDialog) {
        AlertDialog(
            onDismissRequest = { showNoteDialog = false },
            title = { Text(if (note.isBlank()) "添加备注" else "编辑备注") },
            text = {
                Column {
                    OutlinedTextField(
                        value = noteDraft,
                        onValueChange = { noteDraft = it },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("例如：看到第几卷了、待追、剧透提醒…") },
                        // 多行输入，最多 6 行后内部滚动，弹窗高度不会被撑爆
                        minLines = 3,
                        maxLines = 6,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = "留空保存即删除备注",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    showNoteDialog = false
                    viewModel.saveNote(noteDraft)
                }) { Text("保存") }
            },
            dismissButton = {
                TextButton(onClick = { showNoteDialog = false }) { Text("取消") }
            },
        )
    }

    val display = detail ?: item

    // 目录是否展开（默认折叠，点击"目录"按钮展开）
    var showChapters by rememberSaveable { mutableStateOf(false) }
    // 目录排序：false=正序，true=倒序（#28）
    var reverseChapters by rememberSaveable { mutableStateOf(false) }

    Box(modifier = Modifier
        .fillMaxSize()
        // 夜间模式跟随：本页是裸 Box（不是 Scaffold），不铺背景就会透出
        // window 的白色背景，导致全局夜间开启时这里仍是刺眼白底。
        .background(MaterialTheme.colorScheme.background)) {
        // enableEdgeToEdge 下自绘顶栏必须自行避让系统栏
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding(),
        ) {
            // 顶部返回按钮（简化处理）
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

            // 注意：这里**不能**在加载时整页 return 一个转圈。
            // 进详情页时手上已经有列表页传过来的 item（封面、书名、作者都在），
            // 整页不渲染的结果是「一片空白 + 一个转圈」，慢网下能停十几秒，
            // 看起来跟卡死没区别。头部照常画，只有「章节数」这一处等接口。
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 24.dp),
            ) {
                // 头部信息
                item {
                    DetailHeader(
                        item = display,
                        collected = collected,
                        onToggleCollect = viewModel::toggleCollect,
                        onRead = { onRead(display) },
                        onOpenChangeSource = viewModel::openChangeSource,
                        download = download,
                        onDownload = viewModel::downloadBook,
                        onCancelDownload = viewModel::cancelDownload,
                        onOpenCacheDialog = { showCacheDialog = true },
                        onAuthorClick = onSearchAuthor,
                        hasProgress = hasProgress,
                        onReadLatest = {
                            // 点「最新章节」直达该章阅读；目录里匹配不到则退回「阅读」按钮逻辑
                            val last = BookMeta.lastChapter(display)
                            val target = if (last.isNullOrBlank()) null else chapters.firstOrNull {
                                it.title.trim() == last.trim() ||
                                    it.title.contains(last.trim(), ignoreCase = true)
                            }
                            if (target != null) onChapterClick(target) else onRead(display)
                        },
                    )
                }

                // 标签小栏（v1.6.5.0）：浏览上下文 + extras["kind"] 派生，点击跳标签搜索
                item {
                    DetailTagBar(
                        tags = com.novelreader.core.util.ItemTags.of(display),
                        onTagClick = onTagClick,
                    )
                }

                // 备注条（C7）：有备注显示首行 + 编辑图标，无备注显示「添加备注」
                item {
                    NovelNoteBar(
                        note = note,
                        onEdit = {
                            noteDraft = note
                            showNoteDialog = true
                        },
                    )
                }

                // 目录按钮（点击展开/收起目录）+ 正/倒序切换
                item {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        // 目录还没回来时按钮禁用并说明状态，避免「目录（0章）」误导用户以为这书没章节
                        val chaptersPending = loading && chapters.isEmpty()
                        Button(
                            onClick = { showChapters = !showChapters },
                            enabled = !chaptersPending,
                        ) {
                            Text(
                                when {
                                    chaptersPending -> "目录加载中…"
                                    showChapters -> "收起目录"
                                    else -> "目录（${chapters.size}章）"
                                }
                            )
                        }
                        if (chaptersPending) {
                            Spacer(Modifier.width(10.dp))
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp,
                            )
                        }
                        if (showChapters && chapters.size > 1) {
                            Spacer(Modifier.width(8.dp))
                            OutlinedButton(onClick = { reverseChapters = !reverseChapters }) {
                                Text(if (reverseChapters) "倒序" else "正序")
                            }
                        }
                    }
                }

                // 0 章提示：该源未返回任何章节，明确引导换源，避免用户误以为应用坏了
                if (!loading && chapters.isEmpty()) {
                    item {
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                            color = MaterialTheme.colorScheme.errorContainer,
                            shape = RoundedCornerShape(10.dp),
                        ) {
                            Column(modifier = Modifier.padding(14.dp)) {
                                Text(
                                    text = "该源未返回任何章节（0 章）",
                                    style = MaterialTheme.typography.titleSmall,
                                    color = MaterialTheme.colorScheme.onErrorContainer,
                                    fontWeight = FontWeight.Bold,
                                )
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    text = "此源目录可能失效或格式不兼容，建议点击「换源」切换到其他源。",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.85f),
                                )
                            }
                        }
                    }
                }

                // 章节列表（点击目录按钮后展开）
                if (showChapters) {
                    item {
                        Text(
                            text = "目录（${chapters.size}章）",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                        )
                    }

                    val ordered = if (reverseChapters) chapters.asReversed() else chapters
                    items(ordered) { chapter ->
                        ChapterRow(chapter = chapter, onClick = { onChapterClick(chapter) })
                        HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                    }
                }
            }
        }

        // ===== 换源面板（覆盖层） =====
        if (changeSourceOpen) {
            DetailChangeSourcePanel(
                candidates = changeSourceCandidates,
                loading = changeSourceLoading,
                progress = changeSourceProgress,
                error = changeSourceError,
                onPick = { candidate ->
                    viewModel.closeChangeSource()
                    onSwitchSource(candidate)
                },
                onClose = viewModel::closeChangeSource,
            )
        }

        // ===== 换源成功提示 =====
        notice?.let { msg ->
            Surface(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 48.dp, start = 24.dp, end = 24.dp),
                color = MaterialTheme.colorScheme.inverseSurface,
                shape = RoundedCornerShape(20.dp),
            ) {
                Text(
                    text = msg,
                    color = MaterialTheme.colorScheme.inverseOnSurface,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                )
            }
        }
    }
}

/** 详情页换源面板：列出同名同作者的其他源，点击跳转到对应详情页 */
@Composable
private fun DetailChangeSourcePanel(
    candidates: List<MediaItem>,
    loading: Boolean,
    progress: String?,
    error: String?,
    onPick: (MediaItem) -> Unit,
    onClose: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.4f))
            .clickable(onClick = onClose),
    ) {
        Surface(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .fillMaxHeight(0.65f),
            color = MaterialTheme.colorScheme.surface,
            shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "切换书源",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = "关闭",
                        color = MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier
                            .clickable(onClick = onClose)
                            .padding(8.dp),
                    )
                }
                Spacer(Modifier.height(8.dp))
                when {
                    // 还没有任何候选：转圈 + 进度
                    loading && candidates.isEmpty() -> {
                        Column(
                            modifier = Modifier.fillMaxSize(),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center,
                        ) {
                            CircularProgressIndicator()
                            Spacer(Modifier.height(12.dp))
                            Text(
                                text = progress ?: "正在搜索书源…",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    error != null -> {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text(error, color = MaterialTheme.colorScheme.error)
                        }
                    }
                    candidates.isEmpty() -> {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text(
                                text = "未找到同名同作者的其他书源",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    else -> {
                        LazyColumn(modifier = Modifier.fillMaxSize()) {
                            items(candidates, key = { "${it.sourceId}_${it.id}" }) { cand ->
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { onPick(cand) }
                                        .padding(vertical = 10.dp),
                                ) {
                                    Text(
                                        text = cand.title,
                                        style = MaterialTheme.typography.bodyLarge,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        cand.author?.let {
                                            Text(
                                                text = it,
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                        }
                                        if (cand.sourceName.isNotBlank()) {
                                            Spacer(Modifier.width(8.dp))
                                            Text(
                                                text = "· ${cand.sourceName}",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.primary,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                        }
                                    }
                                }
                                HorizontalDivider()
                            }
                            if (loading && progress != null) {
                                item(key = "progress_footer") {
                                    Text(
                                        text = progress,
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(vertical = 10.dp),
                                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * 详情页「备注」条（C7）。
 *
 * 有备注：显示**首行**内容 + 编辑图标（长备注只在弹窗里看，不能把详情页撑高）；
 * 无备注：显示「添加备注」入口。整行可点击，点击打开编辑弹窗。
 */
@Composable
private fun NovelNoteBar(
    note: String,
    onEdit: () -> Unit,
) {
    val hasNote = note.isNotBlank()
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .clickable(onClick = onEdit),
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(10.dp),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "备注",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.width(8.dp))
            if (hasNote) {
                // 只取首个非空行，单行省略——备注可能很长，详情页顶部区域要稳定
                val firstLine = note.lineSequence().firstOrNull { it.isNotBlank() }?.trim().orEmpty()
                Text(
                    text = firstLine,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.width(6.dp))
                Icon(
                    imageVector = Icons.Filled.Edit,
                    contentDescription = "编辑备注",
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
            } else {
                Text(
                    text = "添加备注",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun DetailHeader(
    item: MediaItem,
    collected: Boolean,
    onToggleCollect: () -> Unit,
    onRead: () -> Unit = {},
    onOpenChangeSource: () -> Unit = {},
    download: com.novelreader.core.download.DownloadState? = null,
    onDownload: () -> Unit = {},
    onCancelDownload: () -> Unit = {},
    onOpenCacheDialog: () -> Unit = {},
    onAuthorClick: ((String) -> Unit)? = null,
    hasProgress: Boolean = false,
    onReadLatest: () -> Unit = {},
) {
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
        ) {
            val cover = item.cover
            if (cover.isNullOrBlank()) {
                CoverPlaceholderLetter(
                    Modifier.size(width = 96.dp, height = 128.dp),
                    item.title,
                )
            } else {
                SubcomposeAsyncImage(
                    model = cover,
                    contentDescription = item.title,
                    modifier = Modifier.size(width = 96.dp, height = 128.dp),
                    contentScale = ContentScale.Crop,
                ) {
                    when (painter.state) {
                        is AsyncImagePainter.State.Error -> CoverPlaceholderLetter(Modifier.fillMaxSize(), item.title)
                        is AsyncImagePainter.State.Loading -> CoverPlaceholderLetter(Modifier.fillMaxSize(), item.title)
                        else -> SubcomposeAsyncImageContent()
                    }
                }
            }
            Spacer(Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = item.title,
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(8.dp))
                // 作者 + 状态（连载中/已完结）——点击作者搜索其作品（#31）
                val authorLine = listOfNotNull(
                    item.author?.takeIf { it.isNotBlank() },
                    BookMeta.statusText(item),
                ).joinToString(" · ")
                if (authorLine.isNotEmpty()) {
                    Text(
                        text = authorLine,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.clickable(enabled = onAuthorClick != null) {
                            item.author?.takeIf { it.isNotBlank() }?.let { onAuthorClick?.invoke(it) }
                        },
                    )
                }
                // 元信息：字数 · 更新时间（「最新章节」拆成独立可点行，见下）
                val meta = BookMeta.summaryLine(item, showLastChapter = false, showUpdateTime = true)
                if (meta.isNotEmpty()) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = meta,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                // 最新章节：独立一行，点击直达该章（旧实现仅作为元信息文本，不可点）
                BookMeta.lastChapter(item)?.let { last ->
                    Spacer(Modifier.height(4.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(onClick = onReadLatest)
                            .padding(vertical = 4.dp),
                    ) {
                        Text(
                            text = "最新章节：",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            text = last,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            text = "去阅读 ›",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
                // 来源名
                if (item.sourceName.isNotBlank()) {
                    Text(
                        text = "来源：${item.sourceName}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                Spacer(Modifier.height(12.dp))
                // 主操作行：阅读 / 加入书架。
                // 用 FlowRow 而不是 Row：窄屏下「加入书架」原本会被挤成「加入书/架」两行，
                // 改成整颗按钮换行，文字永远单行完整。
                @OptIn(ExperimentalLayoutApi::class)
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Button(onClick = onRead) { Text(if (hasProgress) "继续阅读" else "开始阅读") }
                    OutlinedButton(onClick = onToggleCollect) {
                        Text(if (collected) "已收藏" else "加入书架")
                    }
                }
                Spacer(Modifier.height(8.dp))
                // 三颗按钮在 360dp 窄屏上放不下会被挤出边界：「缓存」虽然可点，
                // 但文字被裁得一个都不显示（真机复现）。改用 FlowRow 自动换行。
                @OptIn(ExperimentalLayoutApi::class)
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedButton(onClick = onOpenChangeSource) { Text("换源") }
                    OutlinedButton(onClick = onDownload) { Text("下载") }
                    OutlinedButton(onClick = onOpenCacheDialog) { Text("缓存") }
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
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "下载中… ${(state.progress * 100).toInt()}%",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            text = "取消",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier
                                .clickable(onClick = onCancelDownload)
                                .padding(4.dp),
                        )
                    }
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

    item.desc?.let { desc ->
        Text(
            text = desc,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            maxLines = 6,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun ChapterRow(
    chapter: ChapterItem,
    onClick: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Text(
            text = chapter.title,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
