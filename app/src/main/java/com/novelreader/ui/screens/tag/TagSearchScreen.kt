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
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.SuggestionChipDefaults
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
import androidx.compose.ui.graphics.Color
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
 * 标签搜索页（v1.6.1.0 重做）。
 *
 * 设计目标：让标签搜索变成「**和普通搜索同一个入口**」的第二种形态，而非独立的次级页面。
 * 入口在首页搜索栏左侧下拉（搜索 / 标签），进入此页后看到：
 *
 * 1. **热门推荐**：从已收集标签池按覆盖源数取 top [HOT_TAG_LIMIT]，点选即追加到搜索条件；
 * 2. **手动搜索**：输入框接受多个标签（**空格分隔 = 复合搜索，按标题求交集**），
 *    点右下角搜索按钮 / IME 搜索即触发；
 * 3. **已选标签 chips**：单点 chip 即可移除；
 * 4. **结果区**：单标签直接聚合；复合标签按标题交集（[com.novelreader.core.util.TagQuery.intersect]）。
 *
 * 结果展示按类型分流：视频 = 3 列海报网格；小说 / 漫画 = 列表卡片。
 */
@Composable
fun TagSearchScreen(
    viewModel: TagSearchViewModel,
    onItemClick: (MediaItem) -> Unit,
    onBack: () -> Unit,
) {
    val hotTags by viewModel.hotTags.collectAsState()
    val collecting by viewModel.collecting.collectAsState()
    val query by viewModel.query.collectAsState()
    val results by viewModel.results.collectAsState()
    val searching by viewModel.searching.collectAsState()
    val progress by viewModel.progress.collectAsState()
    val notice by viewModel.notice.collectAsState()
    val activeTags by viewModel.activeTags.collectAsState()

    Column(modifier = Modifier.fillMaxSize()) {
        // ===== 顶栏：返回 + 标题 =====
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
            }
            Text(
                text = "标签搜索",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = viewModel::refreshTags, enabled = !collecting) {
                Icon(Icons.Filled.Refresh, contentDescription = "重新收集标签")
            }
        }

        // ===== 热门推荐标签（v1.6.1.0）：按覆盖源数倒序的 top N，点选即追加 =====
        if (hotTags.isNotEmpty() || collecting) {
            Column {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "热门推荐",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.weight(1f))
                    if (collecting) {
                        CircularProgressIndicator(
                            Modifier.size(12.dp),
                            strokeWidth = 1.5.dp,
                        )
                    }
                }
                if (hotTags.isEmpty() && collecting) {
                    Text(
                        text = "正在收集标签…",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 6.dp),
                    )
                } else {
                    LazyRow(
                        modifier = Modifier.fillMaxWidth(),
                        contentPadding = PaddingValues(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(hotTags, key = { "hot_$it" }) { tag ->
                            HotTagChip(
                                tag = tag,
                                active = tag in activeTags,
                                onClick = {
                                    // 点已选 = 移除；点未选 = 追加并搜索
                                    if (tag in activeTags) viewModel.removeTag(tag)
                                    else viewModel.addTag(tag)
                                },
                            )
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                }
            }
        }

        HorizontalDivider()

        // ===== 手动搜索（v1.6.1.0）：输入框 + IME 搜索 / 回车触发 =====
        OutlinedTextField(
            value = query,
            onValueChange = viewModel::setQuery,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            placeholder = { Text("输入标签，多个标签用空格分开（= 复合搜索）") },
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyMedium,
            trailingIcon = {
                if (searching) {
                    CircularProgressIndicator(
                        Modifier
                            .size(20.dp)
                            .padding(end = 12.dp),
                        strokeWidth = 2.dp,
                    )
                } else {
                    IconButton(onClick = viewModel::search) {
                        Icon(Icons.Filled.Search, contentDescription = "搜索")
                    }
                }
            },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { viewModel.search() }),
        )

        // ===== 已选标签 chips（v1.6.1.0）：点选移除 =====
        if (activeTags.isNotEmpty()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "已选标签：",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.width(4.dp))
                LazyRow(
                    modifier = Modifier.weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    contentPadding = PaddingValues(end = 12.dp),
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
                        text = if (activeTags.isEmpty()) "点上面「热门推荐」或在搜索框输入标签\n多个标签用空格分开可组合筛选"
                        else "没有匹配的内容",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
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

/**
 * 热门推荐标签 chip（v1.6.1.0）。
 *
 * 未选：浅灰 suggestion chip 配色；已选：primary container 高亮；
 * 点选行为由外层 [TagSearchScreen] 决定（追加 / 移除）。
 */
@Composable
private fun HotTagChip(
    tag: String,
    active: Boolean,
    onClick: () -> Unit,
) {
    if (active) {
        AssistChip(
            onClick = onClick,
            label = {
                Text(
                    tag,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            },
            colors = AssistChipDefaults.assistChipColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer,
                labelColor = MaterialTheme.colorScheme.onPrimaryContainer,
            ),
            trailingIcon = {
                Icon(
                    Icons.Filled.Close,
                    contentDescription = "移除 $tag",
                    modifier = Modifier.size(14.dp),
                )
            },
        )
    } else {
        SuggestionChip(
            onClick = onClick,
            label = {
                Text(
                    tag,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            },
            colors = SuggestionChipDefaults.suggestionChipColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
                labelColor = MaterialTheme.colorScheme.onSurfaceVariant,
            ),
        )
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