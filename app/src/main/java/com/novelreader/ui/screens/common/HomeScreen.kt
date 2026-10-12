package com.novelreader.ui.screens.common

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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Source
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.novelreader.domain.model.ChapterItem
import com.novelreader.domain.model.MediaItem
import com.novelreader.domain.model.SourceType
import com.novelreader.ui.screens.bookshelf.BookGrid

/**
 * 主页（合并书架 + 最近阅读）。
 *
 * 底部导航三栏：小说 / 动漫 / 视频，各自对应独立页面。
 * 每个页面顶部是「搜索栏」（点击进入独立搜索页），
 * 搜索栏下方直接是「书架」封面墙（收藏内容，按最近阅读时间倒序），
 * 不再单设「最近阅读」列表与「书架」入口——两者本就是同一件事。
 * 顶栏右侧提供 源管理 / 设置 入口。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    viewModel: HomeViewModel,
    onOpenNovelItem: (MediaItem) -> Unit,
    onOpenComicItem: (MediaItem) -> Unit,
    onOpenVideoItem: (MediaItem) -> Unit,
    onOpenSources: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenNovelSearch: () -> Unit,
    onOpenComicSearch: () -> Unit,
    onOpenVideoSearch: () -> Unit,
    onContinueReading: (MediaItem, List<ChapterItem>, ChapterItem?) -> Unit,
) {
    var selectedTab by rememberSaveable { mutableIntStateOf(0) }
    val books by viewModel.books.collectAsState()
    val progressMap by viewModel.progressMap.collectAsState()
    val lastChapterMap by viewModel.lastChapterMap.collectAsState()
    val chapterCountMap by viewModel.chapterCountMap.collectAsState()

    // 展现形式：0=封面墙网格，1=列表
    var gridMode by rememberSaveable { mutableIntStateOf(0) }

    // 每次回到前台时刷新（进度可能已变化）
    val lifecycleOwner = androidx.compose.ui.platform.LocalLifecycleOwner.current
    androidx.compose.runtime.DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                viewModel.refreshProgress()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    data class TabItem(val label: String, val icon: ImageVector)

    val tabs = listOf(
        TabItem("小说", Icons.AutoMirrored.Filled.MenuBook),
        TabItem("动漫", Icons.Filled.PhotoLibrary),
        TabItem("视频", Icons.Filled.PlayCircle),
    )

    // 当前 tab 的类型（用于书架过滤）
    val currentType = when (selectedTab) {
        0 -> SourceType.NOVEL
        1 -> SourceType.COMIC
        else -> SourceType.VIDEO
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(tabs[selectedTab].label) },
                actions = {
                    IconButton(onClick = onOpenSources) {
                        Icon(Icons.Filled.Source, contentDescription = "源管理")
                    }
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Filled.Settings, contentDescription = "设置")
                    }
                },
            )
        },
        bottomBar = {
            NavigationBar {
                tabs.forEachIndexed { index, tab ->
                    NavigationBarItem(
                        selected = selectedTab == index,
                        onClick = { selectedTab = index },
                        icon = { Icon(imageVector = tab.icon, contentDescription = tab.label) },
                        label = { Text(tab.label) },
                    )
                }
            }
        },
    ) { padding ->
        Box(modifier = Modifier.padding(padding)) {
            val placeholder = when (selectedTab) {
                0 -> "搜索小说"
                1 -> "搜索漫画"
                else -> "搜索视频"
            }
            val onOpenSearch = when (selectedTab) {
                0 -> onOpenNovelSearch
                1 -> onOpenComicSearch
                else -> onOpenVideoSearch
            }

            Column(modifier = Modifier.fillMaxSize()) {
                // 搜索栏（点击进入独立搜索页）
                SearchEntryBar(placeholder = placeholder, onClick = onOpenSearch)

                Spacer(Modifier.height(8.dp))

                // 书架内容（合并后的主内容区）
                BookshelfContent(
                    books = books,
                    currentType = currentType,
                    progressMap = progressMap,
                    lastChapterMap = lastChapterMap,
                    chapterCountMap = chapterCountMap,
                    gridMode = gridMode,
                    onToggleGridMode = { gridMode = if (gridMode == 0) 1 else 0 },
                    onItemClick = { item ->
                        viewModel.continueReading(item) { chapters, target ->
                            onContinueReading(item, chapters, target)
                        }
                    },
                )
            }
        }
    }
}

/**
 * 书架内容区：标题行（含网格/列表切换）+ 封面墙。
 * 内容按当前 tab 类型过滤，未开始阅读的收藏也展示（无进度）。
 */
@Composable
private fun BookshelfContent(
    books: List<MediaItem>,
    currentType: SourceType,
    progressMap: Map<String, com.novelreader.domain.repository.ReadingProgress>,
    lastChapterMap: Map<String, String>,
    chapterCountMap: Map<String, Int>,
    gridMode: Int,
    onToggleGridMode: () -> Unit,
    onItemClick: (MediaItem) -> Unit,
) {
    // 按当前 tab 类型过滤
    val filtered = books.filter { it.type == currentType }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "书架",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onToggleGridMode) {
                Icon(
                    imageVector = if (gridMode == 0) Icons.AutoMirrored.Filled.ViewList else Icons.Filled.GridView,
                    contentDescription = if (gridMode == 0) "切换为列表" else "切换为封面墙",
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
        }

        if (filtered.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "书架空空如也\n去搜索并收藏喜欢的内容吧",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else if (gridMode == 0) {
            // 封面墙网格（复用书架组件）
            BookGrid(
                books = filtered,
                progressMap = progressMap,
                lastChapterMap = lastChapterMap,
                chapterCountMap = chapterCountMap,
                selecting = false,
                selectedKeys = emptySet(),
                onClick = onItemClick,
                onLongClick = { },
            )
        } else {
            // 列表形式（复用书架网格 cell 的封面+信息，简化）
            LazyVerticalGrid(
                columns = GridCells.Fixed(3),
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(filtered, key = { it.itemKey() }) { item ->
                    com.novelreader.ui.screens.bookshelf.BookGridCell(
                        item = item,
                        progress = progressMap[item.itemKey()],
                        lastChapter = lastChapterMap[item.itemKey()],
                        chapterCount = chapterCountMap[item.itemKey()],
                        selected = false,
                        selecting = false,
                        onClick = { onItemClick(item) },
                        onLongClick = { },
                    )
                }
            }
        }
    }
}

private fun com.novelreader.domain.model.MediaItem.itemKey(): String = "$sourceId:$id"

/**
 * 可点击的「搜索栏」入口（非输入框，点击跳转搜索页）。
 */
@Composable
private fun SearchEntryBar(
    placeholder: String,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Filled.Search,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = placeholder,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
