package com.novelreader.ui.screens.video

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.novelreader.domain.model.ChapterItem
import com.novelreader.domain.model.MediaItem
import com.novelreader.ui.screens.common.HomeViewModel

/**
 * 视频详情与目录（选集）页。
 *
 * v1.3.4.0 起补全了元信息展示（年份 / 评分 / 地区 / 分类 / 导演 / 主演 / 完整简介）——
 * 这些字段 `MacCmsParser` 早就解析进 `extras` 了，只是一直没渲染出来。
 *
 * 另一个改动是**续播定位**：从「继续观看」进来时 `extras` 里带着上次看到第几集，
 * 目录加载完成后自动跳进播放器并续播（对标 MoonTV 点「继续观看」直接接着看）。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun VideoDetailScreen(
    viewModel: VideoDetailViewModel,
    item: MediaItem,
    onEpisodeClick: (ChapterItem) -> Unit,
    onBack: () -> Unit,
    onTagClick: (String) -> Unit = {},
) {
    val detail by viewModel.detail.collectAsState()
    val episodes by viewModel.episodes.collectAsState()
    val loading by viewModel.loading.collectAsState()
    val collected by viewModel.collected.collectAsState()
    val download by viewModel.download.collectAsState()

    LaunchedEffect(item) {
        viewModel.load(item)
    }

    val display = detail ?: item

    // ===== 续播定位（来自「继续观看」，见 HomeViewModel.buildContinueWatching）=====
    val resumeIndex = item.extras[HomeViewModel.EXTRA_RESUME_INDEX]?.toIntOrNull() ?: -1
    val resumeUrl = item.extras[HomeViewModel.EXTRA_RESUME_URL].orEmpty()
    val wantsResume = resumeIndex >= 0 || resumeUrl.isNotBlank()
    var resumed by remember(item.id) { mutableStateOf(false) }
    LaunchedEffect(episodes, loading) {
        if (resumed || !wantsResume || loading || episodes.isEmpty()) return@LaunchedEffect
        resumed = true
        // 地址优先（同一部剧不同源的集号可能不一致），退回集号匹配
        val target = episodes.firstOrNull { resumeUrl.isNotBlank() && it.url == resumeUrl }
            ?: episodes.firstOrNull { it.index == resumeIndex }
        if (target != null) onEpisodeClick(target)
    }

    var descExpanded by remember(display.id) { mutableStateOf(false) }

    // 应用开启了 enableEdgeToEdge()，这里自绘顶栏必须自己让开系统栏，
    // 否则「返回 + 详情」会压在状态栏下面。
    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
            }
            Text(
                text = "详情",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
            )
        }

        if (loading && episodes.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            return@Column
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 24.dp),
        ) {
            // ===== 头部：封面 + 标题 + 主创 + 操作 =====
            item {
                Row(modifier = Modifier.padding(16.dp)) {
                    AsyncImage(
                        model = display.cover,
                        contentDescription = display.title,
                        modifier = Modifier
                            .size(width = 96.dp, height = 128.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant),
                        contentScale = ContentScale.Crop,
                    )
                    Spacer(Modifier.width(14.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = display.title,
                            style = MaterialTheme.typography.titleLarge,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        val director = display.extras["director"].orEmpty()
                        if (director.isNotBlank()) {
                            Spacer(Modifier.height(4.dp))
                            Text(
                                text = "导演：$director",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        val actor = display.extras["actor"].orEmpty()
                        if (actor.isNotBlank()) {
                            Spacer(Modifier.height(2.dp))
                            Text(
                                text = "主演：$actor",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Spacer(Modifier.height(10.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            // 收藏：MoonTV 是心形，改用图标而不是文字按钮
                            IconButton(onClick = viewModel::toggleCollect) {
                                Icon(
                                    imageVector = if (collected) Icons.Filled.Favorite
                                    else Icons.Filled.FavoriteBorder,
                                    contentDescription = if (collected) "取消收藏" else "收藏",
                                    tint = if (collected) MaterialTheme.colorScheme.error
                                    else MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            val first = episodes.firstOrNull()
                            if (first != null) {
                                FilledTonalButton(onClick = { onEpisodeClick(first) }) {
                                    Icon(
                                        Icons.Filled.PlayArrow,
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp),
                                    )
                                    Spacer(Modifier.width(4.dp))
                                    Text("播放")
                                }
                                Spacer(Modifier.width(8.dp))
                            }
                            OutlinedButton(onClick = viewModel::downloadVideo) {
                                Icon(
                                    Icons.Filled.Download,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp),
                                )
                                Spacer(Modifier.width(4.dp))
                                Text("下载")
                            }
                        }
                    }
                }
            }

            // ===== 标签小栏（v1.6.5.0）：浏览上下文 + type_name 派生，点击跳标签搜索 =====
            item(key = "tag_bar") {
                com.novelreader.ui.components.DetailTagBar(
                    tags = com.novelreader.core.util.ItemTags.of(display),
                    onTagClick = onTagClick,
                )
            }

            // ===== 元信息标签行：评分 · 年份 · 地区 · 语言 · 作者 · 源名 =====
            // 注：分类（typeName）不在本行——v1.6.5.0 起收进上方可点的「标签小栏」；
            // author 对苹果 CMS 源就是 type_name，与标签小栏重复，也一并过滤。
            // v1.8.4.0：分类名会被拆成多个标签（里番动漫 → 动画 + 里番），
            // 原串不再等于任何一个标签，所以要按「是不是 typeName」判重。
            val itemTags = com.novelreader.core.util.ItemTags.of(display)
            val rawType = display.extras["typeName"].orEmpty().trim()
            val tags = buildList {
                display.extras["year"].orEmpty().takeIf { it.isNotBlank() }?.let { add(it) }
                display.extras["area"].orEmpty().takeIf { it.isNotBlank() }?.let { add(it) }
                display.extras["lang"].orEmpty().takeIf { it.isNotBlank() }?.let { add(it) }
                display.author?.takeIf {
                    it.isNotBlank() && it !in this && it !in itemTags && it != rawType
                }?.let { add(it) }
                display.sourceName.takeIf { it.isNotBlank() && it !in this }?.let { add(it) }
            }
            val score = display.scoreLabel()
            if (tags.isNotEmpty() || score.isNotBlank()) {
                item {
                    FlowRow(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        if (score.isNotBlank()) {
                            TagChip(text = "评分 $score", highlighted = true)
                        }
                        tags.forEach { TagChip(text = it, highlighted = false) }
                    }
                    Spacer(Modifier.height(12.dp))
                }
            }

            // ===== 简介（超长可展开）=====
            val summary = display.extras["content"].orEmpty().ifBlank { display.desc.orEmpty() }
            if (summary.isNotBlank()) {
                item {
                    val long = summary.length > 90
                    Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                        Text(
                            text = summary,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = if (descExpanded || !long) Int.MAX_VALUE else 4,
                            overflow = TextOverflow.Ellipsis,
                            lineHeight = 20.sp,
                        )
                        if (long) {
                            TextButton(
                                onClick = { descExpanded = !descExpanded },
                                contentPadding = PaddingValues(horizontal = 0.dp),
                            ) {
                                Text(if (descExpanded) "收起" else "展开全部")
                            }
                        }
                    }
                }
            }

            // ===== 下载进度 =====
            download?.let { state ->
                item {
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

            // ===== 目录 =====
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "选集（${episodes.size}）",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f),
                    )
                    if (wantsResume && resumeIndex >= 0) {
                        Text(
                            text = "上次看到 ${resumeIndex + 1}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }

            if (episodes.isEmpty()) {
                item {
                    Text(
                        text = "没有取到剧集列表，可能是源暂时不可用",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }
            }

            itemsIndexed(episodes, key = { i, ep -> "${ep.url}#$i" }) { index, episode ->
                val isCurrent = index == resumeIndex
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onEpisodeClick(episode) }
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        // 采集站有的线路「只有地址、没有集名」，回退成序号
                        text = episode.title.ifBlank { "第 ${index + 1} 集" },
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (isCurrent) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    if (isCurrent) {
                        Text(
                            text = "上次看到",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
                HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
            }
        }
    }
}

/** 详情页元信息小标签（分类 / 年份 / 地区 / 评分 …） */
@Composable
private fun TagChip(text: String, highlighted: Boolean) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = if (highlighted) MaterialTheme.colorScheme.primary
        else MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(
                if (highlighted) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                else MaterialTheme.colorScheme.surfaceVariant,
            )
            .padding(horizontal = 8.dp, vertical = 4.dp),
    )
}

/** 评分清洗：`8.50` → `8.5`；`0` / 非法 → 空串 */
private fun MediaItem.scoreLabel(): String {
    val raw = extras["score"].orEmpty().trim()
    if (raw.isEmpty()) return ""
    val value = raw.toDoubleOrNull() ?: return ""
    if (value <= 0.0) return ""
    return if (value == value.toInt().toDouble()) value.toInt().toString()
    else String.format("%.1f", value)
}
