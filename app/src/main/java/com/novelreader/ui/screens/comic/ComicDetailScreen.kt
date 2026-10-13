package com.novelreader.ui.screens.comic

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
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
) {
    val detail by viewModel.detail.collectAsState()
    val chapters by viewModel.chapters.collectAsState()
    val loading by viewModel.loading.collectAsState()
    val collected by viewModel.collected.collectAsState()
    val download by viewModel.download.collectAsState()
    val chapterDesc by viewModel.chapterDesc.collectAsState()
    // 批量离线缓存（v1.2.11）
    val cacheProgress by viewModel.cacheProgress.collectAsState()
    var showCacheDialog by remember { mutableStateOf(false) }

    LaunchedEffect(item) {
        viewModel.load(item)
    }

    val display = detail ?: item
    // 倒序仅改变展示顺序：点击仍用章节自身的 index，阅读器的「上一话/下一话」按源顺序走
    val visibleChapters = remember(chapters, chapterDesc) {
        if (chapterDesc) chapters.asReversed().toList() else chapters
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

    Column(modifier = Modifier.fillMaxSize()) {
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
                        text = "没有获取到章节，可能源已失效",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
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
                    AsyncImage(
                        model = display.cover,
                        contentDescription = display.title,
                        modifier = Modifier.size(width = 96.dp, height = 128.dp),
                        contentScale = ContentScale.Crop,
                    )
                    Spacer(Modifier.width(16.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = display.title,
                            style = MaterialTheme.typography.titleLarge,
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
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Button(
                                onClick = onRead,
                                enabled = chapters.isNotEmpty(),
                            ) {
                                Text(if (chapters.isEmpty()) "暂无章节" else "开始阅读")
                            }
                            if (collected) {
                                OutlinedButton(onClick = viewModel::toggleCollect) { Text("已收藏") }
                            } else {
                                OutlinedButton(onClick = viewModel::toggleCollect) { Text("加入书架") }
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
                        modifier = Modifier.weight(1f),
                    )
                    OrderChip(
                        text = if (chapterDesc) "倒序" else "正序",
                        selected = chapterDesc,
                        onClick = { viewModel.toggleChapterOrder() },
                    )
                }
            }

            items(visibleChapters, key = { "${it.index}_${it.url}" }) { chapter ->
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onChapterClick(chapter) }
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                ) {
                    Text(
                        text = chapter.title,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
            }
        }

            // ===== 批量离线缓存弹窗（v1.2.11）：进行中显示进度，否则范围选择/完成提示 =====
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
