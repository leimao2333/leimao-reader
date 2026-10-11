package com.novelreader.ui.screens.novel

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
 */
@Composable
fun NovelDetailScreen(
    viewModel: NovelDetailViewModel,
    item: MediaItem,
    onChapterClick: (ChapterItem) -> Unit,
    onBack: () -> Unit,
) {
    val detail by viewModel.detail.collectAsState()
    val chapters by viewModel.chapters.collectAsState()
    val loading by viewModel.loading.collectAsState()
    val collected by viewModel.collected.collectAsState()
    val download by viewModel.download.collectAsState()

    LaunchedEffect(item) {
        viewModel.load(item)
    }

    val display = detail ?: item

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
                modifier = Modifier.padding(8.dp),
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
                    download = download,
                    onDownload = viewModel::downloadBook,
                )
            }

            // 章节标题
            item {
                Text(
                    text = "目录（${chapters.size}章）",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                )
            }

            // 章节列表
            items(chapters) { chapter ->
                ChapterRow(chapter = chapter, onClick = { onChapterClick(chapter) })
                HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
            }
        }
    }
}

@Composable
private fun DetailHeader(
    item: MediaItem,
    collected: Boolean,
    onToggleCollect: () -> Unit,
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
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (collected) {
                        OutlinedButton(onClick = onToggleCollect) { Text("已收藏") }
                    } else {
                        Button(onClick = onToggleCollect) { Text("加入书架") }
                    }
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
