package com.novelreader.ui.screens.novel

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.novelreader.domain.model.ChapterItem
import com.novelreader.domain.model.MediaItem

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

    LaunchedEffect(item) {
        viewModel.load(item)
    }

    val display = detail ?: item

    // 目录是否展开（默认折叠，点击"目录"按钮展开）
    var showChapters by rememberSaveable { mutableStateOf(false) }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
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

            if (loading && chapters.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
                return@Column
            }

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
                    )
                }

                // 目录按钮（点击展开/收起目录）
                item {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Button(onClick = { showChapters = !showChapters }) {
                            Text(if (showChapters) "收起目录" else "目录（${chapters.size}章）")
                        }
                    }
                }

                // 章节列表（点击目录按钮后展开）
                if (showChapters) {
                    item {
                        Text(
                            text = "目录（${chapters.size}章）",
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                        )
                    }

                    items(chapters) { chapter ->
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
                error = changeSourceError,
                onPick = { candidate ->
                    viewModel.closeChangeSource()
                    onSwitchSource(candidate)
                },
                onClose = viewModel::closeChangeSource,
            )
        }
    }
}

/** 详情页换源面板：列出同名同作者的其他源，点击跳转到对应详情页 */
@Composable
private fun DetailChangeSourcePanel(
    candidates: List<MediaItem>,
    loading: Boolean,
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
                    loading -> {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator()
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
                        }
                    }
                }
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
) {
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
        ) {
            AsyncImage(
                model = item.cover,
                contentDescription = item.title,
                modifier = Modifier.size(width = 96.dp, height = 128.dp),
                contentScale = ContentScale.Crop,
            )
            Spacer(Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = item.title,
                    style = MaterialTheme.typography.titleLarge,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(8.dp))
                item.author?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
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
                // 主操作行：阅读 / 加入书架 / 换源
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Button(onClick = onRead) { Text("阅读") }
                    Spacer(Modifier.width(8.dp))
                    OutlinedButton(onClick = onToggleCollect) {
                        Text(if (collected) "已收藏" else "加入书架")
                    }
                }
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(onClick = onOpenChangeSource) { Text("换源") }
                    Spacer(Modifier.width(8.dp))
                    OutlinedButton(onClick = onDownload) { Text("下载") }
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

    item.desc?.let { desc ->
        Text(
            text = desc,
            style = MaterialTheme.typography.bodyMedium,
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
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
