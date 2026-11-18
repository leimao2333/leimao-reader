package com.novelreader.ui.screens.history

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.WindowInsets
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
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import com.novelreader.core.reader.GlobalStats
import com.novelreader.core.reader.ReadingStats
import com.novelreader.core.reader.TimelineBucket
import com.novelreader.domain.model.RecentReading
import com.novelreader.domain.model.SourceType
import com.novelreader.ui.components.BookMeta
import com.novelreader.ui.components.CoverPlaceholderLetter

/**
 * 阅读历史独立页（v1.4.3.0）。
 *
 * 按类型筛选所有阅读/播放记录，支持多选批量清除与「清空全部」。
 * 点击单条继续阅读（跳转对应阅读/播放页）。
 *
 * A14：新增「统计」页签 —— 统计头部（总时长 / 连续天数 / 本月时长 / 日均时长 / 已读书目数）、
 * 阅读时间轴（日 / 周 / 月粒度切换）、每日趋势条，以及点书目看章节维度统计。
 *
 * 为什么时间轴不是独立路由：导航集中在 `AppNavHost`，A14 明确不许改它，
 * 所以时间轴做成本页的第二个页签。
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

    // A14 统计相关
    val globalStats by viewModel.globalStats.collectAsState()
    val buckets by viewModel.timeline.collectAsState()
    val trend by viewModel.trend.collectAsState()
    val granularity by viewModel.granularity.collectAsState()
    val bookStats by viewModel.selectedBookStats.collectAsState()
    val chapters by viewModel.selectedChapters.collectAsState()

    // 页签：0 = 记录，1 = 统计（时间轴）
    var tab by rememberSaveable { mutableIntStateOf(0) }

    // 回前台刷新（进度可能已变化）
    val lifecycleOwner = androidx.compose.ui.platform.LocalLifecycleOwner.current
    androidx.compose.runtime.DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) viewModel.refresh()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    var confirmClearAll by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                    // 顶栏避让状态栏：enableEdgeToEdge 下部分设备 Scaffold 默认 inset 失效
                    windowInsets = WindowInsets.statusBars,
                title = { Text(if (tab == 0) "阅读历史" else "阅读统计") },
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
                    // 多选 / 清空只对「记录」页签有意义，统计页签不显示
                    if (tab == 0 && items.isNotEmpty()) {
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
            if (selecting && tab == 0) {
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
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize(),
        ) {
            TabRow(selectedTabIndex = tab) {
                Tab(
                    selected = tab == 0,
                    onClick = { tab = 0 },
                    text = { Text("记录") },
                )
                Tab(
                    selected = tab == 1,
                    onClick = { tab = 1 },
                    text = { Text("统计") },
                )
            }

            // 类型筛选：记录页与统计页共用同一份筛选结果，
            // 避免「列表是小说、统计是全部」这种对不上的情况
            TypeFilterRow(selectedType, viewModel::selectType)

            when (tab) {
                0 -> RecordsTab(
                    modifier = Modifier.weight(1f),
                    items = items,
                    loading = loading,
                    selecting = selecting,
                    selectedKeys = selectedKeys,
                    onToggleSelect = viewModel::toggleSelect,
                    onClick = onContinue,
                    onLongClick = {
                        if (!selecting) {
                            viewModel.toggleSelecting()
                            viewModel.toggleSelect(HistoryViewModel.keyOf(it))
                        }
                    },
                    onDelete = viewModel::remove,
                )

                else -> StatisticsTab(
                    modifier = Modifier.weight(1f),
                    stats = globalStats,
                    buckets = buckets,
                    trend = trend,
                    granularity = granularity,
                    onGranularityChange = viewModel::setGranularity,
                    onBookClick = viewModel::selectBook,
                )
            }
        }
    }

    // 章节维度统计面板（点时间轴里的书目弹出）
    if (bookStats != null) {
        BookChapterStatsSheet(
            stats = bookStats!!,
            chapters = chapters,
            onDismiss = viewModel::clearSelectedBook,
        )
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

/**
 * 「统计」页签内容：统计头部 + 阅读时间轴。
 *
 * 统计头部与时间轴放在一起：两者讲的是同一件事的两面
 * （「一共读了多久」与「什么时候读的」），拆成两个页面反而割裂。
 */
@Composable
private fun StatisticsTab(
    stats: GlobalStats,
    buckets: List<TimelineBucket>,
    trend: List<DailyTrendPoint>,
    granularity: ReadingStats.Granularity,
    onGranularityChange: (ReadingStats.Granularity) -> Unit,
    onBookClick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        HistoryStatsHeader(
            stats = stats,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
        ReaderTimelineScreen(
            modifier = Modifier.weight(1f),
            buckets = buckets,
            trend = trend,
            granularity = granularity,
            onGranularityChange = onGranularityChange,
            onBookClick = onBookClick,
        )
    }
}

/**
 * 「记录」页签内容：历史记录列表。
 *
 * 列表保持原来的样子（统计头部挪到了「统计」页签），
 * 这样「记录」页签仍然是一屏能看完的纯列表。
 */
@Composable
private fun RecordsTab(
    items: List<RecentReading>,
    loading: Boolean,
    selecting: Boolean,
    selectedKeys: Set<String>,
    onToggleSelect: (String) -> Unit,
    onClick: (RecentReading) -> Unit,
    onLongClick: (RecentReading) -> Unit,
    onDelete: (RecentReading) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (items.isEmpty() && !loading) {
        // modifier 已带 weight(1f)：占满页签剩余高度，空态文案居中
        Box(modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Text(
                text = "暂无阅读记录\n去搜索并阅读喜欢的内容吧",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }

    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(bottom = 16.dp),
    ) {
        items(items, key = { HistoryViewModel.keyOf(it) }) { r ->
            HistoryRow(
                item = r,
                selecting = selecting,
                selected = HistoryViewModel.keyOf(r) in selectedKeys,
                onToggleSelect = { onToggleSelect(HistoryViewModel.keyOf(r)) },
                onClick = {
                    if (selecting) onToggleSelect(HistoryViewModel.keyOf(r))
                    else onClick(r)
                },
                onLongClick = { onLongClick(r) },
                // 单条快速删除（v1.5.10.0 历史编辑）：不用先进多选模式
                onDelete = { onDelete(r) },
            )
            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
        }
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

/** 单条历史记录行：封面 + 标题/章节/时间 + 类型标签 + 可选复选框 + 单条删除 */
@Composable
private fun HistoryRow(
    item: RecentReading,
    selecting: Boolean,
    selected: Boolean,
    onToggleSelect: () -> Unit,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    /** 单条快速删除（非多选模式下显示删除按钮，v1.5.10.0 历史编辑） */
    onDelete: () -> Unit = {},
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

        // 单条快速删除（多选模式下用复选框，不重复显示）
        if (!selecting) {
            IconButton(onClick = onDelete) {
                Icon(
                    Icons.Filled.Delete,
                    contentDescription = "删除「${item.title}」的记录",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp),
                )
            }
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
