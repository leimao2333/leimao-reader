package com.novelreader.ui.screens.bookshelf

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImagePainter
import coil.compose.SubcomposeAsyncImage
import coil.compose.SubcomposeAsyncImageContent
import com.novelreader.domain.model.ChapterItem
import com.novelreader.domain.model.MediaItem
import com.novelreader.domain.model.SourceType
import com.novelreader.domain.repository.ReadingProgress
import com.novelreader.ui.components.CoverPlaceholderLetter

/**
 * 书架页（改版：封面墙网格 + 进度条 + 分组 tab + 搜索 + 最近阅读排序 + 多选批量移除）。
 *
 * @param onContinueReading 点击条目时回调：参数为 (条目, 章节列表, 目标章节)。
 *                          目标章节为 null 表示无章节（应回详情页）。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun BookShelfScreen(
    viewModel: BookShelfViewModel,
    onContinueReading: (MediaItem, List<ChapterItem>, ChapterItem?) -> Unit,
    onBack: () -> Unit,
) {
    val books by viewModel.books.collectAsState()
    val progressMap by viewModel.progressMap.collectAsState()
    val lastChapterMap by viewModel.lastChapterMap.collectAsState()
    val chapterCountMap by viewModel.chapterCountMap.collectAsState()

    // 排序：0=最近阅读（默认），1=标题，2=类型
    var sortMode by rememberSaveable { mutableIntStateOf(0) }
    var sortMenuOpen by remember { mutableStateOf(false) }
    // 分组：0=全部，1=小说，2=漫画，3=视频
    var groupTab by rememberSaveable { mutableIntStateOf(0) }
    // 展现形式：0=封面墙网格，1=列表
    var gridMode by rememberSaveable { mutableIntStateOf(0) }
    // 搜索过滤
    var query by rememberSaveable { mutableStateOf("") }
    // 多选
    var selecting by remember { mutableStateOf(false) }
    var selectedKeys by remember { mutableStateOf<Set<String>>(emptySet()) }

    // 列表加载后补齐最新章节/章节总数（每项一次）
    androidx.compose.runtime.LaunchedEffect(books) {
        viewModel.enrichChapterInfo(books)
    }

    val filtered = remember(books, groupTab, query, sortMode, progressMap) {
        val byGroup = when (groupTab) {
            1 -> books.filter { it.type == SourceType.NOVEL }
            2 -> books.filter { it.type == SourceType.COMIC }
            3 -> books.filter { it.type == SourceType.VIDEO }
            else -> books
        }
        val byQuery = if (query.isBlank()) byGroup else byGroup.filter {
            it.title.contains(query, ignoreCase = true) ||
                (it.author?.contains(query, ignoreCase = true) == true)
        }
        when (sortMode) {
            // 最近阅读：按进度更新时间倒序，无进度的排最后（按加入顺序）
            0 -> byQuery.sortedByDescending { progressMap[it.itemKey()]?.updatedAt ?: 0L }
            1 -> byQuery.sortedBy { it.title }
            else -> byQuery.sortedBy { it.type.name }
        }
    }

    Scaffold(
        topBar = {
            if (selecting) {
                // 多选模式顶栏
                TopAppBar(
                    title = { Text("已选 ${selectedKeys.size} 项") },
                    navigationIcon = {
                        IconButton(onClick = {
                            selecting = false
                            selectedKeys = emptySet()
                        }) {
                            Icon(Icons.Filled.Close, contentDescription = "退出多选")
                        }
                    },
                    actions = {
                        IconButton(
                            onClick = {
                                val targets = filtered.filter { it.itemKey() in selectedKeys }
                                viewModel.removeBatch(targets)
                                selecting = false
                                selectedKeys = emptySet()
                            },
                        ) {
                            Icon(Icons.Filled.Delete, contentDescription = "移除所选")
                        }
                    },
                )
            } else {
                TopAppBar(
                    title = { Text("书架") },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.Default.ArrowBack, contentDescription = "返回")
                        }
                    },
                    actions = {
                        IconButton(onClick = { gridMode = if (gridMode == 0) 1 else 0 }) {
                            Icon(
                                imageVector = if (gridMode == 0) Icons.AutoMirrored.Filled.List else Icons.Filled.GridView,
                                contentDescription = if (gridMode == 0) "切换为列表" else "切换为封面墙",
                            )
                        }
                        Box {
                            IconButton(onClick = { sortMenuOpen = true }) {
                                Icon(Icons.AutoMirrored.Filled.Sort, contentDescription = "排序")
                            }
                            DropdownMenu(
                                expanded = sortMenuOpen,
                                onDismissRequest = { sortMenuOpen = false },
                            ) {
                                listOf("最近阅读", "按标题", "按类型").forEachIndexed { index, label ->
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
            }
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            // 分组 tab（小说/漫画/视频）
            TabRow(selectedTabIndex = groupTab) {
                listOf("全部", "小说", "动漫", "视频").forEachIndexed { index, label ->
                    Tab(
                        selected = groupTab == index,
                        onClick = { groupTab = index },
                        text = { Text(label) },
                    )
                }
            }
            // 书架内搜索
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                placeholder = { Text("搜索书架") },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                singleLine = true,
            )

            when {
                books.isEmpty() -> Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "书架空空如也\n去搜索并收藏喜欢的内容吧",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                filtered.isEmpty() -> Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "没有匹配的内容",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                gridMode == 0 -> BookGrid(
                    books = filtered,
                    progressMap = progressMap,
                    lastChapterMap = lastChapterMap,
                    chapterCountMap = chapterCountMap,
                    selecting = selecting,
                    selectedKeys = selectedKeys,
                    onClick = { item ->
                        if (selecting) {
                            selectedKeys = toggleKey(selectedKeys, item.itemKey())
                        } else {
                            viewModel.continueReading(item) { chapters, target ->
                                onContinueReading(item, chapters, target)
                            }
                        }
                    },
                    onLongClick = { item ->
                        selecting = true
                        selectedKeys = toggleKey(selectedKeys, item.itemKey())
                    },
                )
                else -> BookList(
                    books = filtered,
                    progressMap = progressMap,
                    lastChapterMap = lastChapterMap,
                    chapterCountMap = chapterCountMap,
                    selecting = selecting,
                    selectedKeys = selectedKeys,
                    onClick = { item ->
                        if (selecting) {
                            selectedKeys = toggleKey(selectedKeys, item.itemKey())
                        } else {
                            viewModel.continueReading(item) { chapters, target ->
                                onContinueReading(item, chapters, target)
                            }
                        }
                    },
                    onLongClick = { item ->
                        selecting = true
                        selectedKeys = toggleKey(selectedKeys, item.itemKey())
                    },
                    onRemove = { viewModel.remove(it) },
                )
            }
        }
    }
}

/** 封面墙网格 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun BookGrid(
    books: List<MediaItem>,
    progressMap: Map<String, ReadingProgress>,
    lastChapterMap: Map<String, String>,
    chapterCountMap: Map<String, Int>,
    selecting: Boolean,
    selectedKeys: Set<String>,
    onClick: (MediaItem) -> Unit,
    onLongClick: (MediaItem) -> Unit,
) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(3),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(12.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        items(books, key = { it.itemKey() }) { item ->
            val key = item.itemKey()
            BookGridCell(
                item = item,
                progress = progressMap[key],
                lastChapter = lastChapterMap[key],
                chapterCount = chapterCountMap[key],
                selected = key in selectedKeys,
                selecting = selecting,
                onClick = { onClick(item) },
                onLongClick = { onLongClick(item) },
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun BookGridCell(
    item: MediaItem,
    progress: ReadingProgress?,
    lastChapter: String?,
    chapterCount: Int?,
    selected: Boolean,
    selecting: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(0.72f),
        ) {
            // 封面
            val cover = item.cover
            if (cover.isNullOrBlank()) {
                CoverPlaceholderLetter(Modifier.fillMaxSize(), item.title)
            } else {
                SubcomposeAsyncImage(
                    model = cover,
                    contentDescription = item.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                ) {
                    when (painter.state) {
                        is AsyncImagePainter.State.Error -> CoverPlaceholderLetter(Modifier.fillMaxSize(), item.title)
                        is AsyncImagePainter.State.Loading -> CoverPlaceholderLetter(Modifier.fillMaxSize(), item.title)
                        else -> SubcomposeAsyncImageContent()
                    }
                }
            }
            // 多选勾选
            if (selecting) {
                Surface(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(4.dp),
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f),
                ) {
                    Icon(
                        imageVector = if (selected) Icons.Filled.CheckCircle else Icons.Filled.RadioButtonUnchecked,
                        contentDescription = null,
                        tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(2.dp),
                    )
                }
            }
            // 章节进度角标（右上/右下）
            if (progress != null) {
                Surface(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(4.dp),
                    shape = RoundedCornerShape(6.dp),
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.9f),
                ) {
                    Text(
                        text = progressLabel(progress, chapterCount),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text = item.title,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Medium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        // 进度条
        if (progress != null) {
            Spacer(Modifier.height(4.dp))
            val fraction = progressFraction(progress, chapterCount)
            if (fraction != null) {
                LinearProgressIndicator(
                    progress = { fraction },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(4.dp),
                )
                Spacer(Modifier.height(2.dp))
            }
            Text(
                text = lastChapter ?: progress.chapterTitle,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        } else {
            Spacer(Modifier.height(4.dp))
            Text(
                text = lastChapter ?: "未开始",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** 列表形式书架（保留原有详细行 + 进度条 + 多选） */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun BookList(
    books: List<MediaItem>,
    progressMap: Map<String, ReadingProgress>,
    lastChapterMap: Map<String, String>,
    chapterCountMap: Map<String, Int>,
    selecting: Boolean,
    selectedKeys: Set<String>,
    onClick: (MediaItem) -> Unit,
    onLongClick: (MediaItem) -> Unit,
    onRemove: (MediaItem) -> Unit,
) {
    androidx.compose.foundation.lazy.LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(books.size, key = { books[it].itemKey() }) { index ->
            val item = books[index]
            val key = item.itemKey()
            val progress = progressMap[key]
            val selected = key in selectedKeys
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .combinedClickable(
                        onClick = { onClick(item) },
                        onLongClick = { onLongClick(item) },
                    ),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (selecting) {
                        Icon(
                            imageVector = if (selected) Icons.Filled.CheckCircle else Icons.Filled.RadioButtonUnchecked,
                            contentDescription = null,
                            tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.width(8.dp))
                    }
                    val cover = item.cover
                    if (cover.isNullOrBlank()) {
                        CoverPlaceholderLetter(
                            Modifier.size(width = 64.dp, height = 84.dp),
                            item.title,
                        )
                    } else {
                        SubcomposeAsyncImage(
                            model = cover,
                            contentDescription = item.title,
                            modifier = Modifier.size(width = 64.dp, height = 84.dp),
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
                        Text(
                            text = item.title,
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = listOfNotNull(
                                typeLabel(item.type),
                                item.author?.takeIf { it.isNotBlank() },
                            ).joinToString(" · "),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Spacer(Modifier.height(4.dp))
                        val chapterCount = chapterCountMap[key]
                        val fraction = progress?.let { progressFraction(it, chapterCount) }
                        if (fraction != null) {
                            LinearProgressIndicator(
                                progress = { fraction },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(4.dp),
                            )
                            Spacer(Modifier.height(2.dp))
                        }
                        Text(
                            text = when {
                                progress != null -> "读到：${progress.chapterTitle}"
                                else -> "未开始"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        (lastChapterMap[key])?.let {
                            Spacer(Modifier.height(2.dp))
                            Text(
                                text = "最新：$it",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    if (!selecting) {
                        Button(onClick = { onClick(item) }) { Text("阅读") }
                        IconButton(onClick = { onRemove(item) }) {
                            Icon(Icons.Filled.Delete, contentDescription = "移除")
                        }
                    }
                }
            }
        }
    }
}

/** 进度标签：有总章节数时显示百分比，否则显示第几章 */
private fun progressLabel(progress: ReadingProgress, chapterCount: Int?): String {
    val fraction = progressFraction(progress, chapterCount)
    return if (fraction != null) "${(fraction * 100).toInt()}%" else "${progress.chapterIndex + 1}章"
}

/** 进度比例（0~1）；无总章节数时返回 null */
private fun progressFraction(progress: ReadingProgress, chapterCount: Int?): Float? {
    if (chapterCount == null || chapterCount <= 0) return null
    return ((progress.chapterIndex + 1).toFloat() / chapterCount).coerceIn(0f, 1f)
}

private fun toggleKey(set: Set<String>, key: String): Set<String> =
    if (key in set) set - key else set + key

private fun MediaItem.itemKey(): String = "$sourceId:$id"

private fun typeLabel(type: SourceType): String = when (type) {
    SourceType.NOVEL -> "小说"
    SourceType.COMIC -> "漫画"
    SourceType.VIDEO -> "视频"
}
