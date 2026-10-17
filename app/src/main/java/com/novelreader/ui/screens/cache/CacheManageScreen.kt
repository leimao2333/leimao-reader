package com.novelreader.ui.screens.cache

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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.novelreader.domain.repository.BookCacheSummary

/**
 * 缓存管理页（#30，v1.6.0.0 扩展）。
 *
 * 三类缓存分区：
 * - **视频播放缓存**（v1.7.7.1）：边播边存的视频分片，真正占空间的那类，可单独清理；
 * - **视频预加载**（v1.6.0.0）：播放地址 / 解析结果，很小；
 * - **章节缓存**：按书聚合，可单本删除或全清。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CacheManageScreen(
    viewModel: CacheManageViewModel,
    onBack: () -> Unit,
) {
    val books by viewModel.books.collectAsState()
    val totalCount by viewModel.totalCount.collectAsState()
    val totalSize by viewModel.totalSize.collectAsState()
    val videoPrefetchSize by viewModel.videoPrefetchSize.collectAsState()
    val videoPrefetchCount by viewModel.videoPrefetchCount.collectAsState()
    val videoPlayCacheSize by viewModel.videoPlayCacheSize.collectAsState()
    val message by viewModel.message.collectAsState()

    var confirmClearAll by remember { mutableStateOf(false) }
    var pendingRemove by remember { mutableStateOf<BookCacheSummary?>(null) }
    var pendingClearVideoPrefetch by remember { mutableStateOf(false) }
    var pendingClearVideoPlay by remember { mutableStateOf(false) }

    /** 三类缓存的总占用（概览与「清空」文案共用） */
    val allCacheSize = totalSize + videoPrefetchSize + videoPlayCacheSize

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("缓存管理") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    if (books.isNotEmpty() || videoPrefetchSize > 0 || videoPlayCacheSize > 0) {
                        TextButton(onClick = { confirmClearAll = true }) {
                            Text("清空")
                        }
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            // 概览
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = "已缓存 $totalCount 章",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    text = "占用 ${viewModel.formatSize(allCacheSize)}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            HorizontalDivider()

            // 视频播放缓存分区（v1.7.7.1）：边播边存的分片，是真正占空间的那一类
            CacheSection(
                title = "视频播放缓存",
                subtitle = if (videoPlayCacheSize > 0) {
                    viewModel.formatSize(videoPlayCacheSize)
                } else {
                    "暂无"
                },
                description = "播放时边播边存的视频分片（上限 512MB，自动淘汰最久未看的）。" +
                    "清理后重新播放会再生成缓存，正在播放的视频会短暂重新加载。",
                actionLabel = if (videoPlayCacheSize > 0) "清理" else null,
                onAction = { pendingClearVideoPlay = true },
            )

            // 视频预加载分区（v1.6.0.0）
            CacheSection(
                title = "视频预加载",
                subtitle = "${videoPrefetchCount} 集 · ${viewModel.formatSize(videoPrefetchSize)}",
                description = "视频播放时自动预取的下一批播放地址；换线路 / 退出播放页会自动清理过期条目",
                actionLabel = if (videoPrefetchSize > 0) "清空" else null,
                onAction = { pendingClearVideoPrefetch = true },
            )

            // 章节缓存分区
            Text(
                text = "章节缓存",
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )

            if (books.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        text = "暂无章节缓存\n阅读 / 播放时会自动缓存到本地",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    )
                }
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(books, key = { "${it.sourceId}_${it.itemId}" }) { book ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { pendingRemove = book }
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = book.itemTitle.ifBlank { book.itemId },
                                    style = MaterialTheme.typography.bodyLarge,
                                    maxLines = 1,
                                )
                                Spacer(Modifier.height(2.dp))
                                Text(
                                    text = "${typeLabel(book.itemType)} · 已缓存 ${book.chapterCount} 章 · ${viewModel.formatSize(book.totalSize)}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = "删除",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.error,
                                modifier = Modifier
                                    .clickable { pendingRemove = book }
                                    .padding(8.dp),
                            )
                        }
                        HorizontalDivider()
                    }
                }
            }
        }
    }

    // 删除单本确认
    pendingRemove?.let { book ->
        AlertDialog(
            onDismissRequest = { pendingRemove = null },
            title = { Text("清理缓存") },
            text = { Text("确定删除《${book.itemTitle.ifBlank { book.itemId }}》的全部章节缓存（${book.chapterCount} 章）？") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.removeBook(book)
                    pendingRemove = null
                }) { Text("删除") }
            },
            dismissButton = {
                TextButton(onClick = { pendingRemove = null }) { Text("取消") }
            },
        )
    }

    // 清空视频预加载确认
    if (pendingClearVideoPrefetch) {
        AlertDialog(
            onDismissRequest = { pendingClearVideoPrefetch = false },
            title = { Text("清空视频预加载") },
            text = { Text("确定清空全部视频预加载（${viewModel.formatSize(videoPrefetchSize)}）？这不会影响正在播放的视频。") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.clearVideoPrefetch()
                    pendingClearVideoPrefetch = false
                }) { Text("清空") }
            },
            dismissButton = {
                TextButton(onClick = { pendingClearVideoPrefetch = false }) { Text("取消") }
            },
        )
    }

    // 清空视频播放缓存确认（v1.7.7.1）
    if (pendingClearVideoPlay) {
        AlertDialog(
            onDismissRequest = { pendingClearVideoPlay = false },
            title = { Text("清理视频播放缓存") },
            text = {
                Text(
                    "确定清理全部视频播放缓存（${viewModel.formatSize(videoPlayCacheSize)}）？\n" +
                        "清理后继续观看会自动重新生成；正在播放的视频会短暂重新加载。",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.clearVideoPlayCache()
                    pendingClearVideoPlay = false
                }) { Text("清理") }
            },
            dismissButton = {
                TextButton(onClick = { pendingClearVideoPlay = false }) { Text("取消") }
            },
        )
    }

    // 清空全部确认（含视频预加载 + 播放缓存）
    if (confirmClearAll) {
        AlertDialog(
            onDismissRequest = { confirmClearAll = false },
            title = { Text("清空全部缓存") },
            text = {
                Text(
                    "确定清空全部缓存（章节 ${viewModel.formatSize(totalSize)}、" +
                        "视频预加载 ${viewModel.formatSize(videoPrefetchSize)}、" +
                        "播放缓存 ${viewModel.formatSize(videoPlayCacheSize)}，" +
                        "共 ${viewModel.formatSize(allCacheSize)}）？\n" +
                        "此操作不可恢复。",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.clearAllIncludingPrefetch()
                    confirmClearAll = false
                }) { Text("清空") }
            },
            dismissButton = {
                TextButton(onClick = { confirmClearAll = false }) { Text("取消") }
            },
        )
    }

    // 操作结果提示
    message?.let {
        androidx.compose.runtime.LaunchedEffect(it) {
            kotlinx.coroutines.delay(1800)
            viewModel.consumeMessage()
        }
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
            Text(
                text = it,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(bottom = 32.dp),
            )
        }
    }
}

/**
 * 缓存分区头部（v1.6.0.0）。一个分区一段：标题 + 摘要 + 可选清理按钮。
 */
@Composable
private fun CacheSection(
    title: String,
    subtitle: String,
    description: String,
    actionLabel: String?,
    onAction: () -> Unit,
) {
    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(text = title, style = MaterialTheme.typography.titleSmall)
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (actionLabel != null) {
                Text(
                    text = actionLabel,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier
                        .clickable(onClick = onAction)
                        .padding(8.dp),
                )
            }
        }
        Text(
            text = description,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
            modifier = Modifier.padding(top = 4.dp),
        )
    }
    HorizontalDivider()
}

private fun typeLabel(type: String?): String = when (type) {
    "novel" -> "小说"
    "comic" -> "漫画"
    "video" -> "视频"
    else -> "内容"
}
