package com.novelreader.ui.screens.bookshelf

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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Sort
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.novelreader.domain.model.ChapterItem
import com.novelreader.domain.model.MediaItem
import com.novelreader.domain.model.SourceType

/**
 * 书架页。
 *
 * 展示收藏的小说 / 漫画 / 视频。点击「继续阅读」定位到上次进度章节；
 * 无进度则回详情页。
 *
 * @param onContinueReading 点击条目时回调：参数为 (条目, 章节列表, 目标章节)。
 *                          目标章节为 null 表示无章节（应回详情页）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BookShelfScreen(
    viewModel: BookShelfViewModel,
    onContinueReading: (MediaItem, List<ChapterItem>, ChapterItem?) -> Unit,
    onBack: () -> Unit,
) {
    val books by viewModel.books.collectAsState()
    val progressMap by viewModel.progressMap.collectAsState()
    // 排序：0=默认，1=标题，2=类型
    var sortMode by remember { mutableStateOf(0) }
    var sortMenuOpen by remember { mutableStateOf(false) }

    val sortedBooks = remember(books, sortMode) {
        when (sortMode) {
            1 -> books.sortedBy { it.title }
            2 -> books.sortedBy { it.type.name }
            else -> books
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("书架") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    Box {
                        IconButton(onClick = { sortMenuOpen = true }) {
                            Icon(Icons.Default.Sort, contentDescription = "排序")
                        }
                        DropdownMenu(
                            expanded = sortMenuOpen,
                            onDismissRequest = { sortMenuOpen = false },
                        ) {
                            listOf("默认排序", "按标题", "按类型").forEachIndexed { index, label ->
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            text = if (sortMode == index) "✓ $label" else label,
                                            fontWeight = if (sortMode == index) FontWeight.Bold else FontWeight.Normal,
                                        )
                                    },
                                    onClick = {
                                        sortMode = index
                                        sortMenuOpen = false
                                    },
                                )
                            }
                        }
                    }
                },
            )
        },
    ) { padding ->
        if (books.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "书架空空如也\n去搜索并收藏喜欢的内容吧",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(sortedBooks, key = { "${it.sourceId}:${it.id}" }) { item ->
                    val progress = progressMap["${item.sourceId}:${item.id}"]
                    BookShelfItem(
                        item = item,
                        progressText = progress?.chapterTitle,
                        onClick = {
                            viewModel.continueReading(item) { chapters, target ->
                                onContinueReading(item, chapters, target)
                            }
                        },
                        onRemove = { viewModel.remove(item) },
                    )
                }
            }
        }
    }
}

@Composable
private fun BookShelfItem(
    item: MediaItem,
    progressText: String?,
    onClick: () -> Unit,
    onRemove: () -> Unit,
) {
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AsyncImage(
                model = item.cover,
                contentDescription = item.title,
                modifier = Modifier.size(width = 64.dp, height = 84.dp),
                contentScale = ContentScale.Crop,
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = item.title,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = typeLabel(item.type),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
                if (progressText != null) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = "进度：$progressText",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            TextButton(onClick = onRemove) { Text("移除") }
        }
    }
}

private fun typeLabel(type: SourceType): String = when (type) {
    SourceType.NOVEL -> "小说"
    SourceType.COMIC -> "漫画"
    SourceType.VIDEO -> "视频"
}
