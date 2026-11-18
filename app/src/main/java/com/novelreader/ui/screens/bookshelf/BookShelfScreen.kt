package com.novelreader.ui.screens.bookshelf

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.WindowInsets
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.LibraryAdd
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material3.Button
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
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
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImagePainter
import coil.compose.SubcomposeAsyncImage
import coil.compose.SubcomposeAsyncImageContent
import com.novelreader.core.util.ShelfCompanionGroup
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
    val viewMode by viewModel.viewMode.collectAsState()
    val columns by viewModel.columns.collectAsState()
    val progressMap by viewModel.progressMap.collectAsState()
    val lastChapterMap by viewModel.lastChapterMap.collectAsState()
    val chapterCountMap by viewModel.chapterCountMap.collectAsState()
    // 智能伴生分组（C6）：分组 → 成员（用于 chips 数量）、itemKey → 分组（用于过滤）、当前选中项
    val companionGroups by viewModel.companionGroups.collectAsState()
    val companionGroupOf by viewModel.companionGroupOf.collectAsState()
    val selectedCompanion by viewModel.selectedCompanion.collectAsState()
    // C9 冲突检测
    val conflicts by viewModel.conflicts.collectAsState()

    // 排序：0=最近阅读（默认），1=标题，2=作者，3=类型，4=加入时间
    var sortMode by rememberSaveable { mutableIntStateOf(0) }
    var sortMenuOpen by remember { mutableStateOf(false) }
    // 分组：0=全部，1=小说，2=漫画，3=视频
    var groupTab by rememberSaveable { mutableIntStateOf(0) }
    // 展现形式：0=封面墙网格，1=紧凑网格，2=列表（持久化到偏好）
    var layoutMenuOpen by remember { mutableStateOf(false) }
    // 搜索过滤
    var query by rememberSaveable { mutableStateOf("") }
    // 本地 TXT 导入提示
    val importMessage by viewModel.importMessage.collectAsState()
    val snackbarHostState = remember { androidx.compose.material3.SnackbarHostState() }
    val scope = androidx.compose.runtime.rememberCoroutineScope()

    LaunchedEffect(importMessage) {
        importMessage?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.dismissImportMessage()
        }
    }

    // 本地 TXT 文件选择器（SAF）
    val context = androidx.compose.ui.platform.LocalContext.current
    val txtPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            // 取文件显示名：查询 OpenableColumns.DISPLAY_NAME，失败回退 lastPathSegment
            val displayName = runCatching {
                context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                    val idx = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0 && cursor.moveToFirst()) cursor.getString(idx) else null
                }
            }.getOrNull() ?: uri.lastPathSegment
            viewModel.importLocalBook(uri, displayName)
        }
    }

    // ===== 本地漫画导入（D20）：CBZ 压缩包 / 图片文件夹 =====
    var comicImportMenu by remember { mutableStateOf(false) }
    val cbzPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            val displayName = runCatching {
                context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                    val idx = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0 && cursor.moveToFirst()) cursor.getString(idx) else null
                }
            }.getOrNull() ?: uri.lastPathSegment
            viewModel.importLocalComic(uri, displayName)
        }
    }
    val comicFolderPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { uri ->
        if (uri != null) {
            // 目录树取不到 DISPLAY_NAME，用 docId 末段兜底；失败时 Store 会生成默认名
            val name = runCatching { android.provider.DocumentsContract.getTreeDocumentId(uri) }
                .getOrNull()?.substringAfterLast(':')?.substringAfterLast('/')
            viewModel.importLocalComicFolder(uri, name?.takeIf { it.isNotBlank() })
        }
    }

    // 导入方式选择（D20）
    if (comicImportMenu) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { comicImportMenu = false },
            title = { Text("导入本地漫画") },
            text = {
                Text(
                    "CBZ 压缩包会作为一话导入；图片文件夹会把顶层子目录各作为一话。" +
                        "文件会复制到应用私有目录，源文件删掉也能继续看。",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    comicImportMenu = false
                    cbzPicker.launch(
                        arrayOf(
                            "application/vnd.comicbook+zip",
                            "application/zip",
                            "application/octet-stream",
                            "*/*",
                        ),
                    )
                }) { Text("选 CBZ") }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = {
                        comicImportMenu = false
                        comicFolderPicker.launch(null)
                    }) { Text("选文件夹") }
                    TextButton(onClick = { comicImportMenu = false }) { Text("取消") }
                }
            },
        )
    }

    // 多选
    var selecting by remember { mutableStateOf(false) }
    var selectedKeys by remember { mutableStateOf<Set<String>>(emptySet()) }
    // v1.8.3.0 待办 #4/#5：删除二次确认、批量移动分组、全选菜单
    var pendingDelete by remember { mutableStateOf<List<MediaItem>>(emptyList()) }
    var moveGroupOpen by remember { mutableStateOf(false) }
    var selectMenuOpen by remember { mutableStateOf(false) }
    val groupNames by viewModel.groupNames.collectAsState()

    // 分组 tab 变化 → 同步到 ViewModel（分组名按类型过滤，#6），并清空多选
    LaunchedEffect(groupTab) {
        viewModel.setGroupTab(groupTab)
        selectedKeys = emptySet()
    }

    // 列表加载后补齐最新章节/章节总数（每项一次）
    androidx.compose.runtime.LaunchedEffect(books) {
        viewModel.enrichChapterInfo(books)
    }

    val filtered = remember(
        books,
        groupTab,
        query,
        sortMode,
        progressMap,
        companionGroupOf,
        selectedCompanion,
    ) {
        val byGroup = when (groupTab) {
            1 -> books.filter { it.type == SourceType.NOVEL }
            2 -> books.filter { it.type == SourceType.COMIC }
            3 -> books.filter { it.type == SourceType.VIDEO }
            else -> books
        }
        // 智能分组叠加在类型 tab 之上（先按分组、再按智能分组），两者互不覆盖
        val companionKeys = selectedCompanion?.let { sel ->
            companionGroupOf.filterValues { it == sel }.keys
        }
        val byCompanion = if (companionKeys == null) {
            byGroup
        } else {
            byGroup.filter { it.itemKey() in companionKeys }
        }
        val byQuery = if (query.isBlank()) byCompanion else byCompanion.filter {
            it.title.contains(query, ignoreCase = true) ||
                (it.author?.contains(query, ignoreCase = true) == true)
        }
        when (sortMode) {
            // 最近阅读：按进度更新时间倒序，无进度的排最后（按加入顺序）
            0 -> byQuery.sortedByDescending { progressMap[it.itemKey()]?.updatedAt ?: 0L }
            1 -> byQuery.sortedBy { it.title }
            2 -> byQuery.sortedBy { it.author ?: "" }
            3 -> byQuery.sortedBy { it.type.name }
            // 按加入时间倒序（新收藏的在前）
            else -> byQuery.sortedByDescending { it.addedAt }
        }
    }

    Scaffold(
        snackbarHost = { androidx.compose.material3.SnackbarHost(snackbarHostState) },
        topBar = {
            if (selecting) {
                // 多选模式顶栏
                TopAppBar(
                        // 顶栏避让状态栏：enableEdgeToEdge 下部分设备 Scaffold 默认 inset 失效
                        windowInsets = WindowInsets.statusBars,
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
                        // 全选 / 反选 / 清空（v1.8.3.0 待办 #5）
                        Box {
                            IconButton(onClick = { selectMenuOpen = true }) {
                                Icon(Icons.Filled.SelectAll, contentDescription = "选择")
                            }
                            DropdownMenu(
                                expanded = selectMenuOpen,
                                onDismissRequest = { selectMenuOpen = false },
                            ) {
                                DropdownMenuItem(
                                    text = { Text("全选（${filtered.size} 项）") },
                                    onClick = {
                                        selectedKeys = filtered.map { it.itemKey() }.toSet()
                                        selectMenuOpen = false
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text("反选") },
                                    onClick = {
                                        val all = filtered.map { it.itemKey() }.toSet()
                                        selectedKeys = all - selectedKeys
                                        selectMenuOpen = false
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text("清空选择") },
                                    onClick = {
                                        selectedKeys = emptySet()
                                        selectMenuOpen = false
                                    },
                                )
                            }
                        }
                        IconButton(
                            onClick = {
                                // 删除前二次确认（#4）：确认框里说明会一并清缓存与进度
                                pendingDelete = filtered.filter { it.itemKey() in selectedKeys }
                            },
                        ) {
                            Icon(Icons.Filled.Delete, contentDescription = "移除所选")
                        }
                    },
                )
            } else {
                TopAppBar(
                        // 顶栏避让状态栏：enableEdgeToEdge 下部分设备 Scaffold 默认 inset 失效
                        windowInsets = WindowInsets.statusBars,
                    title = { Text("书架") },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.Default.ArrowBack, contentDescription = "返回")
                        }
                    },
                    actions = {
                        // C9：冲突检测 / 去重入口
                        TextButton(
                            onClick = {
                                val n = viewModel.detectConflicts()
                                if (n == 0) {
                                    scope.launch { snackbarHostState.showSnackbar("未发现重复书籍") }
                                }
                            },
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                        ) {
                            Text("去重", style = MaterialTheme.typography.labelMedium)
                        }
                        TextButton(
                            onClick = {
                                txtPicker.launch(arrayOf("text/plain", "text/*", "application/octet-stream"))
                            },
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                        ) {
                            Text("加载本地资源", style = MaterialTheme.typography.labelMedium)
                        }
                        // D20：本地漫画（CBZ / 图片文件夹）
                        TextButton(
                            onClick = { comicImportMenu = true },
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                        ) {
                            Text("本地漫画", style = MaterialTheme.typography.labelMedium)
                        }
                        Box {
                            IconButton(onClick = { layoutMenuOpen = true }) {
                                Icon(
                                    imageVector = when (viewMode) {
                                        0 -> Icons.Filled.GridView
                                        1 -> Icons.Filled.Apps
                                        else -> Icons.AutoMirrored.Filled.List
                                    },
                                    contentDescription = "切换布局",
                                )
                            }
                            DropdownMenu(
                                expanded = layoutMenuOpen,
                                onDismissRequest = { layoutMenuOpen = false },
                            ) {
                                listOf("封面墙", "紧凑网格", "列表").forEachIndexed { index, label ->
                                    DropdownMenuItem(
                                        text = {
                                            Text(
                                                text = if (viewMode == index) "✓ $label" else label,
                                                fontWeight = if (viewMode == index) FontWeight.Bold else FontWeight.Normal,
                                            )
                                        },
                                        onClick = {
                                            viewModel.setViewMode(index)
                                            layoutMenuOpen = false
                                        },
                                    )
                                }
                                // 列数（仅网格布局生效）
                                HorizontalDivider()
                                Text(
                                    text = "列数：$columns",
                                    style = MaterialTheme.typography.labelMedium,
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                                )
                                Row(
                                    modifier = Modifier.padding(horizontal = 12.dp),
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                ) {
                                    listOf(2, 3, 4, 5, 6).forEach { c ->
                                        androidx.compose.material3.FilterChip(
                                            selected = columns == c,
                                            onClick = {
                                                viewModel.setColumns(c)
                                            },
                                            label = { Text("$c") },
                                        )
                                    }
                                }
                            }
                        }
                        Box {
                            IconButton(onClick = { sortMenuOpen = true }) {
                                Icon(Icons.AutoMirrored.Filled.Sort, contentDescription = "排序")
                            }
                            DropdownMenu(
                                expanded = sortMenuOpen,
                                onDismissRequest = { sortMenuOpen = false },
                            ) {
                                listOf("最近阅读", "按标题", "按作者", "按类型", "按加入时间").forEachIndexed { index, label ->
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
        bottomBar = {
            // 批量操作浮条（v1.8.3.0 待办 #5）：已选计数 + 删除 / 分组 / 清缓存
            if (selecting) {
                androidx.compose.material3.Surface(tonalElevation = 3.dp) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        Text(
                            text = "已选 ${selectedKeys.size} 本",
                            style = MaterialTheme.typography.labelLarge,
                            modifier = Modifier.weight(1f),
                        )
                        val targets = filtered.filter { it.itemKey() in selectedKeys }
                        TextButton(
                            enabled = targets.isNotEmpty(),
                            onClick = { moveGroupOpen = true },
                        ) { Text("分组") }
                        TextButton(
                            enabled = targets.isNotEmpty(),
                            onClick = {
                                viewModel.clearCacheBatch(targets)
                                scope.launch {
                                    snackbarHostState.showSnackbar("已清除 ${targets.size} 本的章节缓存")
                                }
                            },
                        ) { Text("清缓存") }
                        TextButton(
                            enabled = targets.isNotEmpty(),
                            onClick = { pendingDelete = targets },
                        ) {
                            Text(
                                text = "删除",
                                color = if (targets.isNotEmpty()) MaterialTheme.colorScheme.error
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {            // 分组 tab（小说/漫画/视频）
            TabRow(selectedTabIndex = groupTab) {
                listOf("全部", "小说", "漫画", "视频").forEachIndexed { index, label ->
                    Tab(
                        selected = groupTab == index,
                        onClick = { groupTab = index },
                        text = { Text(label) },
                    )
                }
            }
            // 智能分组 chips（C6）：全部 / 在读 / 未读 / 已读完，带组内数量。
            // 与上方类型 tab 叠加生效（先类型、再智能分组）；空组仍显示但禁用，避免用户以为书丢了。
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 12.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                androidx.compose.material3.FilterChip(
                    selected = selectedCompanion == null,
                    onClick = { viewModel.toggleCompanionFilter(null) },
                    label = { Text("全部") },
                )
                listOf(
                    ShelfCompanionGroup.READING,
                    ShelfCompanionGroup.UNREAD,
                    ShelfCompanionGroup.FINISHED,
                ).forEach { group ->
                    val count = companionGroups[group]?.size ?: 0
                    androidx.compose.material3.FilterChip(
                        selected = selectedCompanion == group,
                        // 数量 0 时禁用：点击无反应，但 chip 仍在，说明该组确实没有书
                        enabled = count > 0,
                        onClick = { viewModel.toggleCompanionFilter(group) },
                        label = { Text("${companionLabel(group)} $count") },
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
                        // 叠加筛选时说明原因，免得用户以为书丢了
                        text = "没有匹配的内容" + (selectedCompanion?.let { "（已按「${companionLabel(it)}」筛选）" } ?: ""),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                viewMode == 1 -> BookCompactGrid(
                    books = filtered,
                    columns = columns,
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
                viewMode == 2 -> BookList(
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
                    onRemove = { item -> pendingDelete = listOf(item) }
                )
                else -> BookGrid(
                    books = filtered,
                    columns = columns,
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
            }
        }
    }

    // ===== 删除二次确认（v1.8.3.0 待办 #4） =====
    if (pendingDelete.isNotEmpty()) {
        AlertDialog(
            onDismissRequest = { pendingDelete = emptyList() },
            title = { Text("删除 ${pendingDelete.size} 本书？") },
            text = {
                Text(
                    "删除会同时清除这些书的章节缓存与阅读进度，操作不可恢复。" +
                        "\n（书籍本身在书源里仍然存在，可重新搜索收藏）",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.removeBatch(pendingDelete)
                    pendingDelete = emptyList()
                    selecting = false
                    selectedKeys = emptySet()
                    scope.launch {
                        snackbarHostState.showSnackbar("已删除，缓存与进度已清理")
                    }
                }) {
                    Text("删除", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = emptyList() }) { Text("取消") }
            },
        )
    }

    // ===== 批量移动到分组（v1.8.3.0 待办 #5） =====
    if (moveGroupOpen) {
        val targets = filtered.filter { it.itemKey() in selectedKeys }
        var newGroup by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { moveGroupOpen = false },
            title = { Text("移动 ${targets.size} 本到分组") },
            text = {
                Column {
                    if (groupNames.isEmpty()) {
                        Text(
                            text = "还没有分组，可在下方新建一个",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    groupNames.forEach { g ->
                        TextButton(onClick = {
                            viewModel.setGroupBatch(targets, g)
                            moveGroupOpen = false
                            selectedKeys = emptySet()
                            scope.launch { snackbarHostState.showSnackbar("已移动到「$g」") }
                        }) { Text(g) }
                    }
                    TextButton(onClick = {
                        viewModel.setGroupBatch(targets, "")
                        moveGroupOpen = false
                        selectedKeys = emptySet()
                    }) { Text("移出分组") }
                    OutlinedTextField(
                        value = newGroup,
                        onValueChange = { newGroup = it },
                        label = { Text("新建分组") },
                        singleLine = true,
                    )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = newGroup.isNotBlank(),
                    onClick = {
                        viewModel.setGroupBatch(targets, newGroup.trim())
                        moveGroupOpen = false
                        selectedKeys = emptySet()
                    },
                ) { Text("新建并移入") }
            },
            dismissButton = {
                TextButton(onClick = { moveGroupOpen = false }) { Text("取消") }
            },
        )
    }

    // C9：冲突检测结果（每组选一条保留，删掉其余）
    if (conflicts.isNotEmpty()) {
        ConflictDetectDialog(
            groups = conflicts,
            onDismiss = { viewModel.clearConflicts() },
            onKeep = { group, keepKey -> viewModel.keepOnlyInGroup(group, keepKey) },
        )
    }
}

/** 封面墙网格 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun BookGrid(
    books: List<MediaItem>,
    columns: Int,
    progressMap: Map<String, ReadingProgress>,
    lastChapterMap: Map<String, String>,
    chapterCountMap: Map<String, Int>,
    selecting: Boolean,
    selectedKeys: Set<String>,
    onClick: (MediaItem) -> Unit,
    onLongClick: (MediaItem) -> Unit,
) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(columns.coerceIn(2, 6)),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
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
internal fun BookGridCell(
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
                .aspectRatio(0.7f),
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

/**
 * 紧凑网格：仅封面 + 悬浮书名（对标 legado 紧凑网格布局），信息密度最高。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun BookCompactGrid(
    books: List<MediaItem>,
    columns: Int,
    selecting: Boolean,
    selectedKeys: Set<String>,
    onClick: (MediaItem) -> Unit,
    onLongClick: (MediaItem) -> Unit,
) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(columns.coerceIn(2, 6)),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        items(books, key = { it.itemKey() }) { item ->
            val key = item.itemKey()
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(0.7f)
                    .combinedClickable(onClick = { onClick(item) }, onLongClick = { onLongClick(item) }),
            ) {
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
                // 底部书名渐变遮罩
                Surface(
                    modifier = Modifier.align(Alignment.BottomCenter),
                    color = MaterialTheme.colorScheme.scrim.copy(alpha = 0.55f),
                ) {
                    Text(
                        text = item.title,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 4.dp, vertical = 2.dp),
                    )
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
                            imageVector = if (key in selectedKeys) Icons.Filled.CheckCircle else Icons.Filled.RadioButtonUnchecked,
                            contentDescription = null,
                            tint = if (key in selectedKeys) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(2.dp),
                        )
                    }
                }
            }
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
internal fun progressLabel(progress: ReadingProgress, chapterCount: Int?): String {
    val fraction = progressFraction(progress, chapterCount)
    return if (fraction != null) "${(fraction * 100).toInt()}%" else "${progress.chapterIndex + 1}章"
}

/** 进度比例（0~1）；无总章节数时返回 null */
internal fun progressFraction(progress: ReadingProgress, chapterCount: Int?): Float? {
    if (chapterCount == null || chapterCount <= 0) return null
    return ((progress.chapterIndex + 1).toFloat() / chapterCount).coerceIn(0f, 1f)
}

private fun toggleKey(set: Set<String>, key: String): Set<String> =
    if (key in set) set - key else set + key

internal fun MediaItem.itemKey(): String = "$sourceId:$id"

internal fun typeLabel(type: SourceType): String = when (type) {
    SourceType.NOVEL -> "小说"
    SourceType.COMIC -> "漫画"
    SourceType.VIDEO -> "视频"
}

/** 智能分组中文名（C6，界面与空态提示共用一份） */
internal fun companionLabel(group: ShelfCompanionGroup): String = when (group) {
    ShelfCompanionGroup.READING -> "在读"
    ShelfCompanionGroup.UNREAD -> "未读"
    ShelfCompanionGroup.FINISHED -> "已读完"
}
