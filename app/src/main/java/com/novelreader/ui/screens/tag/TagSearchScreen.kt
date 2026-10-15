package com.novelreader.ui.screens.tag

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImagePainter
import coil.compose.SubcomposeAsyncImage
import coil.compose.SubcomposeAsyncImageContent
import com.novelreader.domain.model.MediaItem
import com.novelreader.domain.model.SourceType
import com.novelreader.ui.components.CoverPlaceholderLetter
import com.novelreader.ui.components.MediaItemCard

/**
 * 标签搜索页（v1.5.10.0）。
 *
 * 顶部：返回 + 标签输入框（**多个标签用空格分开 = 复合搜索**）+ 搜索；
 * 输入框右侧「标签」下拉可直接追加标签；已选标签在结果区上方以 chip 展示（可删）。
 *
 * 结果展示按类型分流：视频 = 3 列海报网格；小说 / 漫画 = 列表卡片。
 */
@Composable
fun TagSearchScreen(
    viewModel: TagSearchViewModel,
    onItemClick: (MediaItem) -> Unit,
    onBack: () -> Unit,
) {
    val allTags by viewModel.allTags.collectAsState()
    val collecting by viewModel.collecting.collectAsState()
    val query by viewModel.query.collectAsState()
    val results by viewModel.results.collectAsState()
    val searching by viewModel.searching.collectAsState()
    val progress by viewModel.progress.collectAsState()
    val notice by viewModel.notice.collectAsState()
    val activeTags by viewModel.activeTags.collectAsState()

    var menuOpen by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxSize()) {
        // ===== 顶栏：返回 + 标签输入 + 搜索 =====
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
            }

            // 标签输入框 + 「标签」下拉（追加标签）
            OutlinedTextField(
                value = query,
                onValueChange = viewModel::setQuery,
                modifier = Modifier.weight(1f),
                placeholder = { Text("输入标签，多个标签用空格分开") },
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyMedium,
                trailingIcon = {
                    Box {
                        Row(
                            modifier = Modifier
                                .clip(RoundedCornerShape(14.dp))
                                .clickable { menuOpen = true }
                                .padding(horizontal = 6.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = "标签",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary,
                            )
                            if (collecting) {
                                Spacer(Modifier.width(4.dp))
                                CircularProgressIndicator(Modifier.size(12.dp), strokeWidth = 1.5.dp)
                            } else {
                                Icon(
                                    Icons.Filled.ArrowDropDown,
                                    contentDescription = "选择标签",
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(18.dp),
                                )
                            }
                        }
                        DropdownMenu(
                            expanded = menuOpen,
                            onDismissRequest = { menuOpen = false },
                            modifier = Modifier.heightIn(max = 360.dp),
                        ) {
                            if (allTags.isEmpty()) {
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            if (collecting) "正在收集标签…" else "暂无标签（源没有发现分类）",
                                            style = MaterialTheme.typography.bodySmall,
                                        )
                                    },
                                    onClick = {},
                                )
                            }
                            // 已选中的标签在菜单里置灰（重复添加无意义）
                            val selected = query.split(' ', '　').filter { it.isNotBlank() }
                            allTags.forEach { tag ->
                                DropdownMenuItem(
                                    text = { Text(tag, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                    enabled = tag !in selected,
                                    onClick = {
                                        menuOpen = false
                                        viewModel.addTag(tag)
                                    },
                                )
                            }
                        }
                    }
                },
            )

            IconButton(onClick = viewModel::search, enabled = !searching) {
                if (searching) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                } else {
                    Icon(Icons.Filled.Search, contentDescription = "搜索")
                }
            }
        }

        // ===== 已选标签 chips（可单独移除）+ 刷新标签池 =====
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (activeTags.isNotEmpty()) {
                LazyRow(
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(horizontal = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    items(activeTags, key = { it }) { tag ->
                        AssistChip(
                            onClick = { viewModel.removeTag(tag) },
                            label = { Text(tag) },
                            trailingIcon = {
                                Icon(
                                    Icons.Filled.Close,
                                    contentDescription = "移除 $tag",
                                    modifier = Modifier.size(14.dp),
                                )
                            },
                        )
                    }
                }
            } else {
                Spacer(Modifier.weight(1f))
            }
            IconButton(onClick = viewModel::refreshTags, enabled = !collecting) {
                Icon(Icons.Filled.Refresh, contentDescription = "重新收集标签")
            }
        }

        // ===== 进度 / 提示 =====
        progress?.let { (done, total) ->
            Text(
                text = "正在搜索 $done/$total 个标签…",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
            )
        }
        notice?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
            )
        }

        // ===== 结果区 =====
        when {
            searching && results.isEmpty() -> {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }

            results.isEmpty() -> {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        text = if (query.isBlank()) "输入或选择标签开始搜索\n多个标签用空格分开可组合筛选"
                        else "没有匹配的内容",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            viewModel.type == SourceType.VIDEO -> {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(3),
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(results, key = { "${it.sourceId}:${it.id}" }) { item ->
                        TagPosterCard(item = item, onClick = { onItemClick(item) })
                    }
                }
            }

            else -> {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(vertical = 8.dp),
                ) {
                    items(results, key = { "${it.sourceId}:${it.id}" }) { item ->
                        MediaItemCard(
                            item = item,
                            onClick = { onItemClick(item) },
                            onRead = { onItemClick(item) },
                        )
                    }
                }
            }
        }
    }
}

/** 标签搜索结果的海报卡（视频）：2:3 封面 + 标题 + 来源名 */
@Composable
private fun TagPosterCard(item: MediaItem, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(2f / 3f)
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
        ) {
            val cover = item.cover
            if (cover.isNullOrBlank()) {
                CoverPlaceholderLetter(modifier = Modifier.fillMaxSize(), title = item.title)
            } else {
                SubcomposeAsyncImage(
                    model = cover,
                    contentDescription = item.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                ) {
                    when (painter.state) {
                        is AsyncImagePainter.State.Error -> CoverPlaceholderLetter(
                            Modifier.fillMaxSize(), item.title,
                        )
                        is AsyncImagePainter.State.Loading -> CoverPlaceholderLetter(
                            Modifier.fillMaxSize(), item.title,
                        )
                        else -> SubcomposeAsyncImageContent()
                    }
                }
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            text = item.title,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (item.sourceName.isNotBlank()) {
            Text(
                text = item.sourceName,
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
