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
import androidx.compose.foundation.layout.padding
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
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.LibraryAdd
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.RssFeed
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Source
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
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
    onOpenExplore: (SourceType) -> Unit = {},
    /** 打开 RSS 订阅（v1.3.1.0） */
    onOpenRss: () -> Unit = {},
    onContinueReading: (MediaItem, List<ChapterItem>, ChapterItem?) -> Unit,
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

    // 书架分组（v1.3.0.0）
    val groupNames by viewModel.groupNames.collectAsState()
    val selectedGroup by viewModel.selectedGroup.collectAsState()
    var assignTarget by remember { mutableStateOf<MediaItem?>(null) }
    var manageGroup by remember { mutableStateOf<String?>(null) }
    var newGroupForItem by remember { mutableStateOf<MediaItem?>(null) }
    var coverEditForItem by remember { mutableStateOf<MediaItem?>(null) }

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
                    IconButton(onClick = { onOpenExplore(currentType) }) {
                        Icon(Icons.Filled.Explore, contentDescription = "发现")
                    }
                    IconButton(onClick = onOpenRss) {
                        Icon(Icons.Filled.RssFeed, contentDescription = "订阅")
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
