package com.novelreader.ui.screens.history

import androidx.compose.foundation.background
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckBox
import androidx.compose.material.icons.filled.CheckBoxOutlineBlank
import androidx.compose.material.icons.filled.Delete
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImagePainter
import coil.compose.SubcomposeAsyncImage
import coil.compose.SubcomposeAsyncImageContent
import com.novelreader.domain.model.RecentReading
import com.novelreader.domain.model.SourceType
import com.novelreader.ui.components.BookMeta
import com.novelreader.ui.components.CoverPlaceholderLetter

/**
 * 阅读历史独立页（v1.4.3.0）。
 *
 * 按类型筛选所有阅读/播放记录，支持多选批量清除与「清空全部」。
 * 点击单条继续阅读（跳转对应阅读/播放页）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(
    viewModel: HistoryViewModel,
    onContinue: (RecentReading) -> Unit,
    onBack: () -> Unit,
) {
    val items by viewModel.items.collectAsState()
    val selectedType by viewModel.selectedType.collectAsState()
    val loading by viewModel.loading.collectAsState()
    val selecting by viewModel.selecting.collectAsState()
    val selectedKeys by viewModel.selectedKeys.collectAsState()

    // 回前台刷新（进度可能已变化）
    val lifecycleOwner = androidx.compose.ui.platform.LocalLifecycleOwner.current
    androidx.compose.runtime.DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) viewModel.refresh()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    var confirmClearAll by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("阅读历史") },
                navigationIcon = {
                    Text(
                        text = "‹ 返回",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .clickable(onClick = onBack)
                            .padding(8.dp),
                    )
                },
                actions = {
                    if (items.isNotEmpty()) {
                        TextButton(onClick = { viewModel.toggleSelecting() }) {
                            Text(if (selecting) "取消" else "选择")
                        }
                        if (!selecting) {
                            IconButton(onClick = { confirmClearAll = true }) {
                                Icon(Icons.Filled.Delete, contentDescription = "清空全部")
                            }
                        }
                    }
                },
            )
        },
        bottomBar = {
            if (selecting) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surface)
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(onClick = { viewModel.toggleSelectAll() }) {
                        Text(if (selectedKeys.size == items.size) "取消全选" else "全选")
                    }
                    Spacer(Modifier.weight(1f))
                    Text(
                        text = "已选 ${selectedKeys.size}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.width(12.dp))
                    TextButton(
                        enabled = selectedKeys.isNotEmpty(),
                        onClick = { viewModel.removeSelected() },
                    ) {
                        Text("删除", color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        },
    ) { padding ->
        Box(modifier = Modifier.padding(padding).fillMaxSize()) {
            Column(modifier = Modifier.fillMaxSize()) {
                // 类型筛选 chips
                TypeFilterRow(selectedType, viewModel::selectType)

                if (items.isEmpty() && !loading) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            text = "暂无阅读记录\n去搜索并阅读喜欢的内容吧",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(bottom = 16.dp),
                    ) {
                        items(items, key = { HistoryViewModel.keyOf(it) }) { r ->
                            HistoryRow(
                                item = r,
                                selecting = selecting,
                                selected = HistoryViewModel.keyOf(r) in selectedKeys,
                                onToggleSelect = { viewModel.toggleSelect(HistoryViewModel.keyOf(r)) },
                                onClick = {
                                    if (selecting) viewModel.toggleSelect(HistoryViewModel.keyOf(r))
                                    else onContinue(r)
                                },
                                onLongClick = {
                                    if (!selecting) {
                                        viewModel.toggleSelecting()
                                        viewModel.toggleSelect(HistoryViewModel.keyOf(r))
                                    }
                                },
                            )
                            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                        }
                    }
                }
            }
        }
    }

    if (confirmClearAll) {
        AlertDialog(
            onDismissRequest = { confirmClearAll = false },
            title = { Text("清空全部历史") },
            text = { Text("确定要清空所有阅读/播放记录吗？此操作不可恢复。") },
            confirmButton = {
                TextButton(onClick = {
                    confirmClearAll = false
                    viewModel.clearAll()
                }) { Text("清空", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { confirmClearAll = false }) { Text("取消") }
            },
        )
    }
}

/** 类型筛选 chips：全部 / 小说 / 漫画 / 视频 */
@Composable
private fun TypeFilterRow(
    selected: SourceType?,
    onSelect: (SourceType?) -> Unit,
) {
    val entries = listOf<Pair<SourceType?, String>>(
        null to "全部",
        SourceType.NOVEL to "小说",
        SourceType.COMIC to "漫画",
        SourceType.VIDEO to "视频",
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        entries.forEach { (type, label) ->
            val isSelected = selected == type
            Text(
                text = label,
                style = MaterialTheme.typography.bodySmall,
                color = if (isSelected) MaterialTheme.colorScheme.onPrimary
                else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .background(
                        if (isSelected) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.surfaceVariant,
                        RoundedCornerShape(16.dp),
                    )
                    .clickable { onSelect(type) }
                    .padding(horizontal = 14.dp, vertical = 6.dp),
            )
        }
    }
}

/** 单条历史记录行：封面 + 标题/章节/时间 + 类型标签 + 可选复选框 */
@Composable
private fun HistoryRow(
    item: RecentReading,
    selecting: Boolean,
    selected: Boolean,
    onToggleSelect: () -> Unit,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 多选模式下的复选框
        if (selecting) {
            Icon(
                imageVector = if (selected) Icons.Filled.CheckBox else Icons.Filled.CheckBoxOutlineBlank,
                contentDescription = if (selected) "取消选择" else "选择",
                tint = if (selected) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .size(24.dp)
                    .clickable(onClick = onToggleSelect),
            )
            Spacer(Modifier.width(10.dp))
        }

        // 封面
        val cover = item.cover
        if (cover.isNullOrBlank()) {
            CoverPlaceholderLetter(
                Modifier.size(width = 52.dp, height = 68.dp),
                item.title,
            )
        } else {
            SubcomposeAsyncImage(
                model = cover,
                contentDescription = item.title,
                modifier = Modifier.size(width = 52.dp, height = 68.dp),
                contentScale = ContentScale.Crop,
            ) {
                when (painter.state) {
                    is AsyncImagePainter.State.Error -> CoverPlaceholderLetter(Modifier.fillMaxSize(), item.title)
                    is AsyncImagePainter.State.Loading -> CoverPlaceholderLetter(Modifier.fillMaxSize(), item.title)
                    else -> SubcomposeAsyncImageContent()
                }
            }
        }
        Spacer(Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = item.title,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                Spacer(Modifier.width(8.dp))
                TypeBadge(item.type)
            }
            Spacer(Modifier.height(4.dp))
            Text(
                text = item.chapterTitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = BookMeta.relativeTime(item.updatedAt),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** 类型小标签（小说/漫画/视频） */
@Composable
private fun TypeBadge(type: SourceType) {
    val label = when (type) {
        SourceType.NOVEL -> "小说"
        SourceType.COMIC -> "漫画"
        SourceType.VIDEO -> "视频"
    }
    Text(
        text = label,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.Medium,
        modifier = Modifier
            .background(
                MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                RoundedCornerShape(6.dp),
            )
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}
