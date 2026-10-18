package com.novelreader.ui.screens.common

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items as rowItems
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.LibraryAdd
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.RssFeed
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Source
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
    /** 打开 RSS 订阅（v1.3.1.0） */
    onOpenRss: () -> Unit = {},
    onContinueReading: (MediaItem, List<ChapterItem>, ChapterItem?) -> Unit,
    /** 打开短剧（直达竖屏播放，v1.3.10.0） */
    onOpenShortDrama: (MediaItem) -> Unit = {},
    /** 打开阅读历史（v1.4.3.0） */
    onOpenHistory: () -> Unit = {},
    /** 打开标签搜索（v1.5.10.0）；[initialTag] 为预填标签（可空 = 用户主动进入 / 复合搜索页可展示热门推荐） */
    onOpenTagSearch: (SourceType, String) -> Unit = { _, _ -> },
) {
    val importMessage by viewModel.importMessage.collectAsState()
    val context = androidx.compose.ui.platform.LocalContext.current
    LaunchedEffect(importMessage) {
        importMessage?.let {
            android.widget.Toast.makeText(context, it, android.widget.Toast.LENGTH_SHORT).show()
            viewModel.dismissImportMessage()
        }
    }
    val txtPicker = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            val displayName = runCatching {
                context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                    val idx = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0 && cursor.moveToFirst()) cursor.getString(idx) else null
                }
            }.getOrNull() ?: uri.lastPathSegment
            viewModel.importLocalBook(uri, displayName)
        }
    }
    var selectedTab by rememberSaveable { mutableIntStateOf(0) }
    val books by viewModel.books.collectAsState()
    val progressMap by viewModel.progressMap.collectAsState()
    val lastChapterMap by viewModel.lastChapterMap.collectAsState()
    val chapterCountMap by viewModel.chapterCountMap.collectAsState()

    // 影视首页状态（v1.3.3.0，MoonTV 风格）
    val videoHomeState by viewModel.videoHome.collectAsState()
    // 小说 / 漫画「推荐」视图状态（v1.7.1.0）
    val novelFeedState by viewModel.novelFeed.collectAsState()
    val comicFeedState by viewModel.comicFeed.collectAsState()
    // 小说 / 漫画 Tab 的视图：0=推荐 1=发现 2=书架（v1.7.1.0；与视频 Tab 的频道胶囊同构）
    var contentSubTab by rememberSaveable { mutableIntStateOf(0) }
    // 视频 Tab 频道栏目：0=首页 1=电影 2=动漫 3=短剧 4=收藏（v1.5.0.0 持久化，重启恢复）
    var videoSubTab by rememberSaveable { mutableIntStateOf(0) }
    // 从持久化读回上次停留的频道（v1.5.0.0），并触发对应频道的加载
    // （重启后频道运行时是空的，只置索引不加载会停在空态）
    LaunchedEffect(Unit) {
        val saved = runCatching { viewModel.getVideoChannelIndex() }.getOrDefault(0)
        if (saved in 0..4) {
            videoSubTab = saved
            when (saved) {
                1 -> viewModel.onMovieChannelShown()
                2 -> viewModel.onAnimeChannelShown()
                3 -> viewModel.onShortDramaShown()
            }
        }
    }

    // 书架分组（v1.3.0.0）
    val groupNames by viewModel.groupNames.collectAsState()
    val selectedGroup by viewModel.selectedGroup.collectAsState()
    var assignTarget by remember { mutableStateOf<MediaItem?>(null) }
    var manageGroup by remember { mutableStateOf<String?>(null) }
    var newGroupForItem by remember { mutableStateOf<MediaItem?>(null) }
    var coverEditForItem by remember { mutableStateOf<MediaItem?>(null) }
    // 长按「继续观看」海报待删除的记录（v1.5.10.0 历史编辑）
    var removeContinueTarget by remember { mutableStateOf<MediaItem?>(null) }

    // 展现形式：0=封面墙网格，1=列表
    var gridMode by rememberSaveable { mutableIntStateOf(0) }

    // 搜索模式：SEARCH = 进入普通搜索页；TAG = 进入标签搜索页（v1.6.1.0）
    // rememberSaveable 跨 tab 切回保留用户选择
    var searchMode by rememberSaveable { mutableStateOf(SearchMode.SEARCH) }

    // 每次回到前台时刷新（进度可能已变化）
    // key 里带上 selectedTab：观察者闭包会捕获它，只用 lifecycleOwner 作 key 会读到旧值
    val lifecycleOwner = androidx.compose.ui.platform.LocalLifecycleOwner.current
    androidx.compose.runtime.DisposableEffect(lifecycleOwner, selectedTab) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                viewModel.refreshProgress()
                // 影视 Tab：播放页返回后「继续观看」要立刻反映刚看过的片
                if (selectedTab == 2) viewModel.refreshContinueWatching()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    data class TabItem(val label: String, val icon: ImageVector)

    val tabs = listOf(
        TabItem("小说", Icons.AutoMirrored.Filled.MenuBook),
        // 注意：这里是**漫画**板块（SourceType.COMIC），不是视频里的「动漫」频道。
        // 两者名字相近但语义不同，别把视频频道的「动漫」改掉（v1.6.6.0 修正过此处文案）。
        TabItem("漫画", Icons.Filled.PhotoLibrary),
        TabItem("视频", Icons.Filled.PlayCircle),
    )

    // 当前 tab 的类型（用于书架过滤）
    val currentType = when (selectedTab) {
        0 -> SourceType.NOVEL
        1 -> SourceType.COMIC
        else -> SourceType.VIDEO
    }

    // 影视 Tab 首次进入 / 每次切回时刷新源列表并按需加载首屏
    androidx.compose.runtime.LaunchedEffect(selectedTab) {
        if (selectedTab == 2) viewModel.onVideoTabShown()
    }

    // 小说 / 漫画「推荐」视图：首次进入 / 切回时按需加载首屏（v1.7.1.0，幂等）
    androidx.compose.runtime.LaunchedEffect(selectedTab, contentSubTab) {
        if (selectedTab != 2 && contentSubTab == 0) viewModel.onFeedTabShown(currentType)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(tabs[selectedTab].label) },
                actions = {
                    IconButton(onClick = onOpenRss) {
                        Icon(Icons.Filled.RssFeed, contentDescription = "订阅")
                    }
                    IconButton(onClick = onOpenHistory) {
                        Icon(Icons.Filled.History, contentDescription = "阅读历史")
                    }
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
                // 搜索栏（点击进入对应页面）+ 左侧「搜索 / 标签」模式切换（v1.6.1.0）
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    SearchModeDropdown(
                        mode = searchMode,
                        onSelect = { searchMode = it },
                    )
                    SearchEntryBar(
                        placeholder = if (searchMode == SearchMode.SEARCH) placeholder else "标签搜索（多个标签用空格分开）",
                        onClick = {
                            if (searchMode == SearchMode.SEARCH) onOpenSearch()
                            else onOpenTagSearch(currentType, "")
                        },
                        modifier = Modifier.weight(1f),
                    )
                }

                Spacer(Modifier.height(8.dp))

                // 视频 Tab：首页（多分区）/ 电影 / 动漫 / 短剧（竖屏沉浸）/ 收藏 五视图切换
                // v1.3.4.0 起改用 MoonTV 式胶囊切换（灰色药丸 + 滑动指示块）
                // v1.5.0.0 新增「电影」「动漫」频道（分类 Tab + 海报网格）
                if (selectedTab == 2) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 8.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        com.novelreader.ui.components.CapsuleSwitch(
                            options = listOf("首页", "电影", "动漫", "短剧", "收藏"),
                            selectedIndex = videoSubTab,
                            onSelect = { idx ->
                                videoSubTab = idx
                                // 持久化频道选择（v1.5.0.0，重启 App 仍停留该频道）
                                viewModel.setVideoChannelIndex(idx)
                                when (idx) {
                                    1 -> viewModel.onMovieChannelShown()
                                    2 -> viewModel.onAnimeChannelShown()
                                    3 -> viewModel.onShortDramaShown()
                                }
                            },
                        )
                    }
                }

                // 小说 / 漫画 Tab：推荐（首位）/ 书架 两视图切换（v1.7.1.0）
                // 推荐与发现**合并为一个视图**：推荐页顶部带「栏目」下拉，
                // 默认「推荐」= 各源推荐位的横滑合览；选中具体栏目 = 该栏目的聚合结果。
                // 两者本就是同一件事的两个粒度（先扫一眼推荐位，不满意再按栏目精确找），
                // 拆成两个平级视图反而让人多点一次。
                if (selectedTab != 2) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 8.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        com.novelreader.ui.components.CapsuleSwitch(
                            options = listOf("推荐", "书架"),
                            selectedIndex = contentSubTab,
                            onSelect = { idx ->
                                contentSubTab = idx
                                if (idx == 0) viewModel.onFeedTabShown(currentType)
                            },
                        )
                    }
                }

                val showVideoHome = selectedTab == 2 && videoSubTab == 0
                val showMovieChannel = selectedTab == 2 && videoSubTab == 1
                val showAnimeChannel = selectedTab == 2 && videoSubTab == 2
                val showShortDrama = selectedTab == 2 && videoSubTab == 3
                if (showVideoHome) {
                    // 影视首页（MoonTV 风格）：继续观看 + 分类分区横滑 / 分类网格 + 四维筛选（v1.3.5.0）
                    // v1.5.1.0：去掉下拉刷新容器，刷新仅靠顶部刷新按钮（下拉手势太灵敏，容易误触发）
                    com.novelreader.ui.screens.video.VideoHomeContent(
                        state = videoHomeState,
                        onSelectType = viewModel::selectVideoType,
                        onSelectCategory = viewModel::selectVideoCategory,
                        onSelectArea = viewModel::selectVideoArea,
                        onSelectYear = viewModel::selectVideoYear,
                        onSelectOrder = viewModel::selectVideoOrder,
                        onExpandCategory = viewModel::expandVideoCategory,
                        onCollapseCategory = viewModel::collapseVideoCategory,
                        onItemClick = onOpenVideoItem,
                        onRefresh = viewModel::refreshVideoHome,
                        onLoadMore = viewModel::loadMoreVideo,
                        onOpenSearch = onOpenVideoSearch,
                        onRemoveContinue = { removeContinueTarget = it },
                    )
                }

                if (showMovieChannel) {
                    // 电影频道（v1.5.0.0：分类 Tab + 海报网格 + 触底分页；v1.5.1.0 刷新仅靠顶部按钮）
                    val channelState by viewModel.movieChannel.collectAsState()
                    com.novelreader.ui.screens.video.VideoChannelScreen(
                        title = "电影",
                        state = channelState,
                        onSelectCategory = viewModel::selectMovieChannelCategory,
                        onLoadMore = viewModel::loadMoreMovieChannel,
                        onRefresh = { viewModel.refreshMovieChannel() },
                        onOpenDetail = onOpenVideoItem,
                    )
                }

                if (showAnimeChannel) {
                    // 动漫频道（v1.5.0.0：同上结构）
                    val channelState by viewModel.animeChannel.collectAsState()
                    com.novelreader.ui.screens.video.VideoChannelScreen(
                        title = "动漫",
                        state = channelState,
                        onSelectCategory = viewModel::selectAnimeChannelCategory,
                        onLoadMore = viewModel::loadMoreAnimeChannel,
                        onRefresh = { viewModel.refreshAnimeChannel() },
                        onOpenDetail = onOpenVideoItem,
                    )
                }

                if (showShortDrama) {
                    // 短剧频道（红果式：分类 Tab + 竖屏海报网格）
                    val dramaState by viewModel.shortDrama.collectAsState()
                    com.novelreader.ui.screens.video.VideoChannelScreen(
                        title = "短剧",
                        state = dramaState,
                        onSelectCategory = viewModel::selectShortDramaCategory,
                        onLoadMore = viewModel::loadMoreShortDrama,
                        onRefresh = { viewModel.refreshShortDrama() },
                        onOpenDetail = onOpenShortDrama,
                    )
                }

                // 书架内容（合并后的主内容区）
                // 视频 Tab 的「收藏」视图同样落在这里，所以对视频强制走书架分支
                val contentTab = if (selectedTab == 2) 1 else contentSubTab
                if (!showVideoHome && !showMovieChannel && !showAnimeChannel && !showShortDrama) when (contentTab) {
                    // 推荐（v1.7.1.0，含原「发现」）：顶部栏目下拉 + 横滑排 / 栏目聚合网格
                    0 -> FeedContent(
                        state = if (currentType == SourceType.COMIC) comicFeedState else novelFeedState,
                        onItemClick = if (currentType == SourceType.COMIC) onOpenComicItem else onOpenNovelItem,
                        onRefresh = { viewModel.refreshFeed(currentType) },
                        onSelectCategory = { viewModel.selectFeedCategory(currentType, it) },
                        onOpenSearch = onOpenSearch,
                    )
                    else -> BookshelfContent(
                        books = books,
                        currentType = currentType,
                        progressMap = progressMap,
                        lastChapterMap = lastChapterMap,
                        chapterCountMap = chapterCountMap,
                        gridMode = gridMode,
                        onToggleGridMode = { gridMode = if (gridMode == 0) 1 else 0 },
                        onImportLocalBook = { txtPicker.launch(arrayOf("text/plain", "text/*", "application/octet-stream")) },
                        groupNames = groupNames,
                        selectedGroup = selectedGroup,
                        onSelectGroup = { viewModel.selectGroup(it) },
                        onManageGroup = { manageGroup = it },
                        onAssignGroup = { assignTarget = it },
                        onItemClick = { item ->
                            viewModel.continueReading(item) { chapters, target ->
                                onContinueReading(item, chapters, target)
                            }
                        },
                    )
                }

                // 分组管理弹窗（长按分组 chip 触发：重命名 / 删除）
                manageGroup?.let { g ->
                    var nameInput by remember(g) { mutableStateOf(g) }
                    AlertDialog(
                        onDismissRequest = { manageGroup = null },
                        title = { Text("管理分组") },
                        text = {
                            OutlinedTextField(
                                value = nameInput,
                                onValueChange = { nameInput = it },
                                label = { Text("分组名") },
                                singleLine = true,
                            )
                        },
                        confirmButton = {
                            TextButton(
                                enabled = nameInput.isNotBlank() && nameInput.trim() != g,
                                onClick = {
                                    viewModel.renameGroup(g, nameInput)
                                    manageGroup = null
                                },
                            ) { Text("重命名") }
                        },
                        dismissButton = {
                            TextButton(onClick = {
                                viewModel.deleteGroup(g)
                                manageGroup = null
                            }) { Text("删除分组") }
                        },
                    )
                }

                // 移动到分组弹窗（长按书触发）
                assignTarget?.let { item ->
                    AlertDialog(
                        onDismissRequest = { assignTarget = null },
                        title = { Text("移动到分组") },
                        text = {
                            Column {
                                if (groupNames.isEmpty()) {
                                    Text(
                                        text = "还没有分组，先新建一个吧",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                groupNames.forEach { g ->
                                    TextButton(onClick = {
                                        viewModel.setBookGroup(item, g)
                                        assignTarget = null
                                    }) { Text(g) }
                                }
                                if (item.groupName.isNotBlank()) {
                                    TextButton(onClick = {
                                        viewModel.setBookGroup(item, "")
                                        assignTarget = null
                                    }) { Text("移出分组") }
                                }
                                TextButton(onClick = {
                                    newGroupForItem = item
                                    assignTarget = null
                                }) { Text("＋ 新建分组") }
                                TextButton(onClick = {
                                    coverEditForItem = item
                                    assignTarget = null
                                }) { Text("更换封面") }
                            }
                        },
                        confirmButton = {},
                        dismissButton = {
                            TextButton(onClick = { assignTarget = null }) { Text("取消") }
                        },
                    )
                }

                // 新建分组输入弹窗
                newGroupForItem?.let { item ->
                    var nameInput by remember { mutableStateOf("") }
                    AlertDialog(
                        onDismissRequest = { newGroupForItem = null },
                        title = { Text("新建分组") },
                        text = {
                            OutlinedTextField(
                                value = nameInput,
                                onValueChange = { nameInput = it },
                                label = { Text("分组名") },
                                singleLine = true,
                            )
                        },
                        confirmButton = {
                            TextButton(
                                enabled = nameInput.isNotBlank(),
                                onClick = {
                                    viewModel.setBookGroup(item, nameInput.trim())
                                    newGroupForItem = null
                                },
                            ) { Text("确定") }
                        },
                        dismissButton = {
                            TextButton(onClick = { newGroupForItem = null }) { Text("取消") }
                        },
                    )
                }

                // 长按「继续观看」删除确认（v1.5.10.0 历史编辑）
                removeContinueTarget?.let { target ->
                    AlertDialog(
                        onDismissRequest = { removeContinueTarget = null },
                        title = { Text("删除观看记录") },
                        text = { Text("确定删除「${target.title}」的观看记录吗？\n（不影响收藏，仅清除继续观看与历史）") },
                        confirmButton = {
                            TextButton(onClick = {
                                viewModel.removeContinueWatching(target)
                                removeContinueTarget = null
                            }) { Text("删除", color = MaterialTheme.colorScheme.error) }
                        },
                        dismissButton = {
                            TextButton(onClick = { removeContinueTarget = null }) { Text("取消") }
                        },
                    )
                }

                // 更换封面弹窗（长按书 → 更换封面，v1.2.3.0）
                coverEditForItem?.let { item ->
                    var urlInput by remember(item) { mutableStateOf(item.cover ?: "") }
                    AlertDialog(
                        onDismissRequest = { coverEditForItem = null },
                        title = { Text("更换封面") },
                        text = {
                            Column {
                                Text(
                                    text = "输入新的封面图片地址",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Spacer(Modifier.height(8.dp))
                                OutlinedTextField(
                                    value = urlInput,
                                    onValueChange = { urlInput = it },
                                    label = { Text("封面 URL") },
                                    singleLine = true,
                                )
                            }
                        },
                        confirmButton = {
                            TextButton(
                                enabled = urlInput.isNotBlank() && urlInput.trim() != item.cover,
                                onClick = {
                                    viewModel.updateCover(item, urlInput.trim())
                                    coverEditForItem = null
                                },
                            ) { Text("确定") }
                        },
                        dismissButton = {
                            TextButton(onClick = { coverEditForItem = null }) { Text("取消") }
                        },
                    )
                }
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
    onImportLocalBook: () -> Unit = {},
    groupNames: List<String> = emptyList(),
    selectedGroup: String? = null,
    onSelectGroup: (String?) -> Unit = {},
    onManageGroup: (String) -> Unit = {},
    onAssignGroup: (MediaItem) -> Unit = {},
    onItemClick: (MediaItem) -> Unit,
) {
    // 按当前 tab 类型过滤
    val typeFiltered = books.filter { it.type == currentType }
    // 分组筛选：null=全部；""=未分组；其余=指定分组
    val filtered = when (selectedGroup) {
        null -> typeFiltered
        "" -> typeFiltered.filter { it.groupName.isBlank() }
        else -> typeFiltered.filter { it.groupName == selectedGroup }
    }

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
            IconButton(onClick = onImportLocalBook) {
                Icon(
                    imageVector = Icons.Filled.LibraryAdd,
                    contentDescription = "导入本地 TXT",
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
            IconButton(onClick = onToggleGridMode) {
                Icon(
                    imageVector = if (gridMode == 0) Icons.AutoMirrored.Filled.ViewList else Icons.Filled.GridView,
                    contentDescription = if (gridMode == 0) "切换为列表" else "切换为封面墙",
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
        }

        // 分组筛选 chips（长按分组可重命名/删除）
        if (groupNames.isNotEmpty()) {
            LazyRow(
                modifier = Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item {
                    GroupChip("全部", selectedGroup == null) { onSelectGroup(null) }
                }
                rowItems(groupNames) { g ->
                    GroupChip(g, selectedGroup == g, onLongClick = { onManageGroup(g) }) {
                        onSelectGroup(g)
                    }
                }
                item {
                    GroupChip("未分组", selectedGroup == "") { onSelectGroup("") }
                }
            }
        }

        if (filtered.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = if (selectedGroup != null && typeFiltered.isNotEmpty()) "该分组下暂无内容\n长按封面可移动到分组"
                    else "书架空空如也\n去搜索并收藏喜欢的内容吧",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else if (gridMode == 0) {
            // 封面墙网格（复用书架组件）
            BookGrid(
                books = filtered,
                columns = 3,
                progressMap = progressMap,
                lastChapterMap = lastChapterMap,
                chapterCountMap = chapterCountMap,
                selecting = false,
                selectedKeys = emptySet(),
                onClick = onItemClick,
                onLongClick = onAssignGroup,
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
                        onLongClick = { onAssignGroup(item) },
                    )
                }
            }
        }
    }
}

/** 分组筛选 chip（长按触发分组管理） */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun GroupChip(
    label: String,
    selected: Boolean,
    onLongClick: (() -> Unit)? = null,
    onClick: () -> Unit,
) {
    val bg = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant
    val fg = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
    Text(
        text = label,
        style = MaterialTheme.typography.bodySmall,
        color = fg,
        maxLines = 1,
        modifier = Modifier
            .background(bg, RoundedCornerShape(16.dp))
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 14.dp, vertical = 6.dp),
    )
}

private fun com.novelreader.domain.model.MediaItem.itemKey(): String = "$sourceId:$id"

/**
 * 搜索栏左侧的「搜索模式」切换器（v1.6.1.0）。
 *
 * 用户选择「搜索」或「标签」两种模式，决定点击搜索栏后进入哪种页面。
 * 设计目的：把标签搜索和普通搜索**统一到同一行入口**，用户切模式即可；
 * 不再让标签搜索作为独立的「标签下拉菜单」藏在搜索框左侧。
 */
private enum class SearchMode(val label: String) {
    SEARCH("搜索"),
    TAG("标签"),
}

/**
 * 「搜索 / 标签」模式切换下拉按钮（v1.6.1.0）。
 *
 * 单选：SEARCH ⇄ TAG。
 * 当前选中的模式高亮（primary container 配色），未选 mode 显示表面色；
 * 点击下拉图标弹出菜单选另一模式即可。
 */
@Composable
private fun SearchModeDropdown(
    mode: SearchMode,
    onSelect: (SearchMode) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Box(modifier = Modifier.padding(start = 12.dp)) {
        Surface(
            onClick = { open = true },
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.primaryContainer,
            modifier = Modifier.padding(end = 4.dp),
        ) {
            Row(
                modifier = Modifier.padding(start = 12.dp, end = 6.dp, top = 12.dp, bottom = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = mode.label,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
                Icon(
                    imageVector = Icons.Filled.ArrowDropDown,
                    contentDescription = "切换搜索模式",
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.size(18.dp),
                )
            }
        }

        DropdownMenu(
            expanded = open,
            onDismissRequest = { open = false },
        ) {
            SearchMode.values().forEach { m ->
                DropdownMenuItem(
                    text = {
                        Text(
                            text = m.label,
                            fontWeight = if (m == mode) androidx.compose.ui.text.font.FontWeight.Bold
                            else androidx.compose.ui.text.font.FontWeight.Normal,
                        )
                    },
                    onClick = {
                        open = false
                        onSelect(m)
                    },
                )
            }
        }
    }
}

/**
 * 可点击的「搜索栏」入口（非输入框，点击跳转搜索页）。
 */
@Composable
private fun SearchEntryBar(
    placeholder: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        onClick = onClick,
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp),
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
