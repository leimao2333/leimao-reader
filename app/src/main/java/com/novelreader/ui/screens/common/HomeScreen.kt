package com.novelreader.ui.screens.common

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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material.icons.filled.CollectionsBookmark
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Source
import androidx.compose.material3.Card
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.novelreader.domain.model.ChapterItem
import com.novelreader.domain.model.MediaItem
import com.novelreader.domain.model.RecentReading
import com.novelreader.domain.model.SourceType

/**
 * 主页。
 *
 * 底部导航三栏：小说 / 动漫 / 视频，各自对应独立页面。
 * 每个页面顶部是「搜索栏」（点击进入独立搜索页，带历史记录），
 * 搜索栏下方是「书架」入口，再往下是「最近阅读」列表（可切换列表/表格展现形式），
 * 最下方是内容引导区。
 * 顶栏右侧提供 源管理 / 设置 入口。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    viewModel: HomeViewModel,
    onOpenNovelItem: (MediaItem) -> Unit,
    onOpenComicItem: (MediaItem) -> Unit,
    onOpenVideoItem: (MediaItem) -> Unit,
    onOpenBookshelf: () -> Unit,
    onOpenSources: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenNovelSearch: () -> Unit,
    onOpenComicSearch: () -> Unit,
    onOpenVideoSearch: () -> Unit,
    onContinueReading: (MediaItem, List<ChapterItem>, ChapterItem?) -> Unit,
) {
    var selectedTab by rememberSaveable { mutableIntStateOf(0) }
    val recent by viewModel.recent.collectAsState()

    // 每次回到首页时刷新最近阅读列表
    androidx.compose.runtime.LaunchedEffect(Unit) {
        viewModel.refresh()
    }

    // 最近阅读展示形式：0=列表，1=表格
    var recentViewMode by rememberSaveable { mutableIntStateOf(0) }

    // 点击最近阅读条目：加载章节列表并定位进度后导航
    val handleContinue: (RecentReading) -> Unit = { item ->
        viewModel.continueReading(item) { chapters, target ->
            onContinueReading(item.toMediaItem(), chapters, target)
        }
    }

    data class TabItem(val label: String, val icon: ImageVector)

    val tabs = listOf(
        TabItem("小说", Icons.AutoMirrored.Filled.MenuBook),
        TabItem("动漫", Icons.Filled.PhotoLibrary),
        TabItem("视频", Icons.Filled.PlayCircle),
    )

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
            when (selectedTab) {
                0 -> HomeTab(
                    label = "小说",
                    searchPlaceholder = "搜索小说",
                    recent = recent,
                    recentViewMode = recentViewMode,
                    onToggleRecentView = { recentViewMode = if (recentViewMode == 0) 1 else 0 },
                    onContinueReading = handleContinue,
                    onOpenSearch = onOpenNovelSearch,
                    onOpenBookshelf = onOpenBookshelf,
                )
                1 -> HomeTab(
                    label = "动漫",
                    searchPlaceholder = "搜索漫画",
                    recent = recent,
                    recentViewMode = recentViewMode,
                    onToggleRecentView = { recentViewMode = if (recentViewMode == 0) 1 else 0 },
                    onContinueReading = handleContinue,
                    onOpenSearch = onOpenComicSearch,
                    onOpenBookshelf = onOpenBookshelf,
                )
                else -> HomeTab(
                    label = "视频",
                    searchPlaceholder = "搜索视频",
                    recent = recent,
                    recentViewMode = recentViewMode,
                    onToggleRecentView = { recentViewMode = if (recentViewMode == 0) 1 else 0 },
                    onContinueReading = handleContinue,
                    onOpenSearch = onOpenVideoSearch,
                    onOpenBookshelf = onOpenBookshelf,
                )
            }
        }
    }
}

/**
 * 单个 tab 的主页内容：搜索栏 + 书架入口 + 最近阅读列表 + 引导区。
 */
@Composable
private fun HomeTab(
    label: String,
    searchPlaceholder: String,
    recent: List<RecentReading>,
    recentViewMode: Int,
    onToggleRecentView: () -> Unit,
    onContinueReading: (RecentReading) -> Unit,
    onOpenSearch: () -> Unit,
    onOpenBookshelf: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        // 搜索栏（点击进入独立搜索页）
        SearchEntryBar(
            placeholder = searchPlaceholder,
            onClick = onOpenSearch,
        )

        Spacer(Modifier.height(8.dp))

        // 书架入口
        BookshelfEntry(onClick = onOpenBookshelf)

        Spacer(Modifier.height(8.dp))

        // 最近阅读列表（可滚动）
        RecentReadingSection(
            recent = recent,
            viewMode = recentViewMode,
            onToggleView = onToggleRecentView,
            onContinueReading = onContinueReading,
            modifier = Modifier.weight(1f),
        )
    }
}

/**
 * 最近阅读区：标题 + 切换展现形式按钮 + 列表/表格。
 */
@Composable
private fun RecentReadingSection(
    recent: List<RecentReading>,
    viewMode: Int,
    onToggleView: () -> Unit,
    onContinueReading: (RecentReading) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        // 标题行 + 切换按钮
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Filled.History,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = "最近阅读",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
            )
            // 切换展现形式按钮
            IconButton(onClick = onToggleView) {
                Icon(
                    imageVector = if (viewMode == 0) Icons.AutoMirrored.Filled.ViewList else Icons.Filled.GridView,
                    contentDescription = if (viewMode == 0) "切换为表格" else "切换为列表",
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
        }

        if (recent.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 24.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "暂无阅读记录，去搜索开始阅读吧",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else if (viewMode == 0) {
            // 列表形式
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    start = 16.dp, end = 16.dp, bottom = 16.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(recent, key = { "${it.sourceId}:${it.itemId}" }) { item ->
                    RecentReadingCard(
                        item = item,
                        onClick = { onContinueReading(item) },
                    )
                }
            }
        } else {
            // 表格形式
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    start = 16.dp, end = 16.dp, bottom = 16.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                items(recent, key = { "${it.sourceId}:${it.itemId}" }) { item ->
                    RecentReadingTableRow(
                        item = item,
                        onClick = { onContinueReading(item) },
                    )
                }
            }
        }
    }
}

/** 列表形式的最近阅读卡片 */
@Composable
private fun RecentReadingCard(
    item: RecentReading,
    onClick: () -> Unit,
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
                modifier = Modifier.size(width = 48.dp, height = 64.dp),
                contentScale = ContentScale.Crop,
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = item.title,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = typeLabel(item.type),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = "读到：${item.chapterTitle}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                text = "›",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** 表格形式的最近阅读行 */
@Composable
private fun RecentReadingTableRow(
    item: RecentReading,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = item.title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = typeLabel(item.type),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = item.chapterTitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

private fun RecentReading.toMediaItem(): MediaItem = MediaItem(
    id = itemId,
    title = title,
    cover = cover,
    type = type,
    detailUrl = detailUrl,
    sourceId = sourceId,
)

private fun typeLabel(type: SourceType): String = when (type) {
    SourceType.NOVEL -> "小说"
    SourceType.COMIC -> "漫画"
    SourceType.VIDEO -> "视频"
}

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

/**
 * 书架入口卡片。
 */
@Composable
private fun BookshelfEntry(onClick: () -> Unit) {
    Card(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Filled.CollectionsBookmark,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "书架",
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = "查看收藏的小说 / 漫画 / 视频",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                text = "›",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
