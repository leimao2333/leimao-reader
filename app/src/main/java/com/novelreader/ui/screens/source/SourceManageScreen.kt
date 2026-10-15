package com.novelreader.ui.screens.source

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.ViewList
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.novelreader.domain.model.SourceFormat
import com.novelreader.domain.model.SourceInfo
import com.novelreader.domain.model.SourceType

/**
 * 源管理页。
 *
 * 展示已导入的源列表，支持启用/禁用、删除，并通过浮动按钮进入导入页。
 * 支持类型/分类筛选、排序、列表/表格两种视图。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun SourceManageScreen(
    viewModel: SourceManageViewModel,
    onImport: () -> Unit,
    onBack: () -> Unit,
) {
    val sources by viewModel.sources.collectAsState()
    val message by viewModel.message.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    // 源名称搜索关键词
    var searchKeyword by remember { mutableStateOf("") }
    // 类型筛选：null = 全部类型
    var selectedType by remember { mutableStateOf<SourceType?>(null) }
    // 内容分类筛选：null = 全部分类
    var selectedCategory by remember { mutableStateOf<String?>(null) }
    // 漫画源二级格式筛选（v1.3.0.0）：选中「漫画」类型后出现下级菜单（legado / Venera）
    var selectedFormat by remember { mutableStateOf<SourceFormat?>(null) }
    // 排序方式
    var sortOrder by remember { mutableStateOf(SourceSortOrder.DEFAULT) }
    // 视图模式：false = 列表，true = 表格
    var tableView by remember { mutableStateOf(false) }
    // 排序菜单展开
    var sortMenuOpen by remember { mutableStateOf(false) }
    // 批量选择模式
    var selectionMode by remember { mutableStateOf(false) }
    val selectedIds = remember { androidx.compose.runtime.mutableStateListOf<String>() }
    // 正在登录的 Venera 源（非空弹出登录对话框，v1.3.0.0）
    var loginTarget by remember { mutableStateOf<SourceInfo?>(null) }
    // 删除失效源二次确认（v1.5.5.0）
    var confirmDeleteFailed by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        viewModel.observe()
        // v1.6.6.0：进页面先算一次「可合并的重复源」，驱动合并按钮的可用态
        viewModel.refreshDuplicateCount()
    }

    // 显示提示消息
    LaunchedEffect(message) {
        message?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.consumeMessage()
        }
    }

    // 先按关键词搜索，再按类型过滤，再按内容分类过滤，最后排序
    val keywordFiltered = remember(sources, searchKeyword) {
        val kw = searchKeyword.trim()
        if (kw.isEmpty()) sources
        else sources.filter {
            it.name.contains(kw, ignoreCase = true) ||
                it.author.contains(kw, ignoreCase = true) ||
                it.desc.contains(kw, ignoreCase = true)
        }
    }
    val typeFiltered = remember(keywordFiltered, selectedType) {
        if (selectedType == null) keywordFiltered
        else keywordFiltered.filter { it.type == selectedType }
    }
    // 切走漫画类型时复位二级筛选
    LaunchedEffect(selectedType) {
        if (selectedType != SourceType.COMIC) selectedFormat = null
    }
    // 二级格式筛选只对漫画类型生效
    val formatFiltered = remember(typeFiltered, selectedType, selectedFormat) {
        if (selectedType != SourceType.COMIC || selectedFormat == null) typeFiltered
        else typeFiltered.filter { it.format == selectedFormat }
    }
    val grouped = remember(formatFiltered) { viewModel.groupByCategory(formatFiltered) }
    val categories = remember(formatFiltered) { viewModel.sortedCategories(formatFiltered) }
    val categoryFiltered = remember(formatFiltered, selectedCategory) {
        if (selectedCategory == null) formatFiltered
        else grouped[selectedCategory] ?: emptyList()
    }
    val displayedSources = remember(categoryFiltered, sortOrder) {
        // 手动排序模式下强制展示全部源：拖拽排序需要看到完整列表才有意义，
        // 否则筛选会把部分源排除在外，拖拽持久化时会把它们的手动序号清空（v1.5.9.0）。
        val base = if (sortOrder == SourceSortOrder.MANUAL) sources else categoryFiltered
        viewModel.sortSources(base, sortOrder)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    if (selectionMode) Text("已选 ${selectedIds.size} 个")
                    else Text("源管理（${sources.size}）")
                },
                navigationIcon = {
                    IconButton(onClick = {
                        if (selectionMode) {
                            selectionMode = false
                            selectedIds.clear()
                        } else onBack()
                    }) {
                        Icon(
                            imageVector = if (selectionMode) Icons.Default.Close else Icons.Default.ArrowBack,
                            contentDescription = if (selectionMode) "退出多选" else "返回",
                        )
                    }
                },
                actions = {
                    if (selectionMode) {
                        // 批量操作：全选 / 全启用 / 全禁用 / 删除
                        TextButton(onClick = {
                            if (selectedIds.size == displayedSources.size) selectedIds.clear()
                            else {
                                selectedIds.clear()
                                selectedIds.addAll(displayedSources.map { it.id })
                            }
                        }) { Text(if (selectedIds.size == displayedSources.size && displayedSources.isNotEmpty()) "取消全选" else "全选") }
                        TextButton(
                            enabled = selectedIds.isNotEmpty(),
                            onClick = {
                                val sel = displayedSources.filter { it.id in selectedIds }
                                viewModel.setEnabledBatch(sel, true)
                                selectionMode = false
                                selectedIds.clear()
                            },
                        ) { Text("启用") }
                        TextButton(
                            enabled = selectedIds.isNotEmpty(),
                            onClick = {
                                val sel = displayedSources.filter { it.id in selectedIds }
                                viewModel.setEnabledBatch(sel, false)
                                selectionMode = false
                                selectedIds.clear()
                            },
                        ) { Text("禁用") }
                        TextButton(
                            enabled = selectedIds.isNotEmpty(),
                            onClick = {
                                val sel = displayedSources.filter { it.id in selectedIds }
                                viewModel.deleteBatch(sel)
                                selectionMode = false
                                selectedIds.clear()
                            },
                        ) { Text("删除") }
                    } else {
                        // 排序菜单
                        Box {
                            TextButton(onClick = { sortMenuOpen = true }) { Text("排序") }
                            DropdownMenu(
                                expanded = sortMenuOpen,
                                onDismissRequest = { sortMenuOpen = false },
                            ) {
                                SourceSortOrder.entries.forEach { order ->
                                    DropdownMenuItem(
                                        text = {
                                            Text(
                                                text = if (sortOrder == order) "✓ ${order.label}" else order.label,
                                                fontWeight = if (sortOrder == order) FontWeight.Bold else FontWeight.Normal,
                                            )
                                        },
                                        onClick = {
                                            sortOrder = order
                                            sortMenuOpen = false
                                        },
                                    )
                                }
                            }
                        }
                        // 批量管理入口
                        TextButton(onClick = { selectionMode = true }) { Text("批量") }
                        // 视图切换
                        IconButton(onClick = { tableView = !tableView }) {
                            Icon(
                                imageVector = if (tableView) Icons.Default.ViewList else Icons.Default.GridView,
                                contentDescription = if (tableView) "列表视图" else "表格视图",
                            )
                        }
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        floatingActionButton = {
            if (!selectionMode) {
                ExtendedFloatingActionButton(
                    onClick = onImport,
                    icon = { Icon(Icons.Default.Add, contentDescription = null) },
                    text = { Text("导入源") },
                )
            }
        },
    ) { padding ->
        if (sources.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = "还没有导入任何源",
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "点击右下角「导入源」按钮\n导入 JSON 或 JS 格式的小说/漫画/视频源",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
            ) {
                // 搜索框：按名称/作者/描述过滤
                androidx.compose.material3.OutlinedTextField(
                    value = searchKeyword,
                    onValueChange = { searchKeyword = it },
                    placeholder = { Text("搜索源名称 / 作者") },
                    singleLine = true,
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                    trailingIcon = {
                        if (searchKeyword.isNotEmpty()) {
                            IconButton(onClick = { searchKeyword = "" }) {
                                Icon(Icons.Default.Close, contentDescription = "清空")
                            }
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                )
                // 类型筛选栏（小说 / 漫画 / 视频）
                TypeFilterBar(
                    selected = selectedType,
                    onSelect = { selectedType = it },
                )
                // 漫画源二级格式筛选（legado / Venera，v1.3.0.0）
                if (selectedType == SourceType.COMIC) {
                    FormatFilterBar(
                        selected = selectedFormat,
                        onSelect = { selectedFormat = it },
                    )
                }
                // 内容分类筛选栏
                CategoryFilterBar(
                    categories = categories,
                    counts = grouped.mapValues { it.value.size },
                    selected = selectedCategory,
                    onSelect = { selectedCategory = it },
                )
                // 一键全启用 / 全禁用 + 启用统计
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val enabledCount = displayedSources.count { it.enabled }
                    Text(
                        text = "启用 $enabledCount / ${displayedSources.size}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = {
                        viewModel.setAllEnabled(displayedSources, true)
                    }) { Text("全部启用") }
                    TextButton(onClick = {
                        viewModel.setAllEnabled(displayedSources, false)
                    }) { Text("全部禁用") }
                }
                // 源有效性检测（自动筛选无用源）
                val testResults by viewModel.testResults.collectAsState()
                val testing by viewModel.testing.collectAsState()
                val testProgress by viewModel.testProgress.collectAsState()
                val testRunning by viewModel.testRunning.collectAsState()
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val failedCount = testResults.count { !it.value }
                    Text(
                        text = when {
                            testRunning -> "正在检测…"
                            testResults.isEmpty() -> "尚未检测源可用性"
                            failedCount == 0 -> "检测完成：全部可用"
                            else -> "检测到 $failedCount 个疑似失效"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(
                        enabled = !testRunning,
                        onClick = { viewModel.testSources(displayedSources) },
                    ) { Text(if (testRunning) "检测中" else "检测可用性") }
                    TextButton(
                        enabled = failedCount > 0 && !testRunning,
                        onClick = { viewModel.disableFailedSources() },
                    ) { Text("禁用失效") }
                    TextButton(
                        enabled = failedCount > 0 && !testRunning,
                        onClick = { confirmDeleteFailed = true },
                    ) { Text("删除失效", color = MaterialTheme.colorScheme.error) }
                }
                // 检测进度条（v1.4.7.0）
                if (testProgress != null) {
                    val (done, total) = testProgress!!
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 2.dp),
                    ) {
                        androidx.compose.material3.LinearProgressIndicator(
                            progress = { if (total > 0) done.toFloat() / total else 0f },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = "$done / $total",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                // 删除失效源二次确认（v1.5.5.0；v1.5.5.1 列出具体源名）
                if (confirmDeleteFailed) {
                    val failedNames = testResults.filterValues { !it }.keys.mapNotNull { id ->
                        sources.firstOrNull { it.id == id }?.name ?: id
                    }
                    androidx.compose.material3.AlertDialog(
                        onDismissRequest = { confirmDeleteFailed = false },
                        title = { Text("删除失效源") },
                        text = {
                            Column {
                                Text(
                                    text = "确定删除以下 ${failedNames.size} 个已检出的失效源吗？此操作不可撤销。",
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                                Spacer(Modifier.height(8.dp))
                                Text(
                                    text = failedNames.take(10).joinToString("、") +
                                        if (failedNames.size > 10) " 等" else "",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        },
                        confirmButton = {
                            TextButton(
                                onClick = {
                                    confirmDeleteFailed = false
                                    viewModel.deleteFailedSources()
                                },
                            ) { Text("删除", color = MaterialTheme.colorScheme.error) }
                        },
                        dismissButton = {
                            TextButton(onClick = { confirmDeleteFailed = false }) { Text("取消") }
                        },
                    )
                }

                // 书源 URL 一键更新（v1.3.0.0 对标 legado）
                val updating by viewModel.updating.collectAsState()
                val updateProgress by viewModel.updateProgress.collectAsState()
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = when {
                            updating -> updateProgress ?: "正在更新…"
                            else -> "从网络源地址拉取最新书源规则"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(
                        enabled = !updating,
                        onClick = { viewModel.updateFromNetwork(displayedSources) },
                    ) { Text(if (updating) "更新中" else "一键更新") }
                }

                // 合并重复源（v1.6.6.0）：同类型 + 同名 + 同站的条目只留一条
                val duplicateCount by viewModel.duplicateCount.collectAsState()
                if (duplicateCount > 0) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "检测到 $duplicateCount 个重复源（同名且同站点）",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = { viewModel.mergeDuplicateSources() }) {
                            Text("合并重复源")
                        }
                    }
                }

                if (tableView) {
                    SourceTableView(
                        sources = displayedSources,
                        testResults = testResults,
                        testing = testing,
                        onToggle = { viewModel.toggleEnabled(it) },
                        onEdit = { viewModel.openEditSource(it) },
                        onDelete = { viewModel.deleteSource(it) },
                    )
                } else {
                    val loginStates by viewModel.loginStates.collectAsState()
                    val loggingIn by viewModel.loggingIn.collectAsState()
                    val manualMode = sortOrder == SourceSortOrder.MANUAL
                    val lazyListState = rememberLazyListState()
                    val density = LocalDensity.current

                    // 手动排序模式下的实时顺序（拖拽过程中在本地交换，松手后持久化）。
                    // 当外部 displayedSources 变化时重置（例如刚切到手动模式、或拖拽松手后刷新）。
                    var manualList by remember(displayedSources) { mutableStateOf(displayedSources) }
                    // 当前正在拖拽的项在 manualList 中的索引（null = 未拖拽）
                    var draggingIndex by remember { mutableStateOf<Int?>(null) }
                    // 累计垂直位移（用于判断何时与相邻项交换）
                    var dragAccum by remember { mutableStateOf(0f) }

                    // 拖拽结束：把 manualList 的最终顺序持久化
                    fun commitDragOrder() {
                        if (manualList != displayedSources) {
                            viewModel.applyManualOrder(manualList)
                        }
                    }

                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        state = lazyListState,
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        itemsIndexed(manualList, key = { _, it -> it.id }) { index, source ->
                            val isDragging = draggingIndex == index
                            // 手动模式下用「长按拖动」手势；其它模式保持原有的长按进多选
                            val dragModifier = if (manualMode) {
                                Modifier.pointerInput(index, manualList) {
                                    detectDragGesturesAfterLongPress(
                                        onDragStart = {
                                            draggingIndex = index
                                            dragAccum = 0f
                                        },
                                        onDragEnd = {
                                            commitDragOrder()
                                            draggingIndex = null
                                            dragAccum = 0f
                                        },
                                        onDragCancel = {
                                            draggingIndex = null
                                            dragAccum = 0f
                                        },
                                        onDrag = { change, dragAmount ->
                                            change.consume()
                                            val currentIdx = draggingIndex ?: return@detectDragGesturesAfterLongPress
                                            dragAccum += dragAmount.y
                                            // 用当前拖拽项的实际高度作为交换阈值（含间距）
                                            val itemInfo = lazyListState.layoutInfo.visibleItemsInfo
                                                .firstOrNull { it.index == currentIdx }
                                            val itemHeight = itemInfo?.size ?: 0
                                            val spacingPx = with(density) { 8.dp.toPx() }
                                            val threshold = (itemHeight + spacingPx)
                                                .takeIf { it > 0f }
                                                ?: with(density) { 88.dp.toPx() }
                                            while (dragAccum > threshold && currentIdx < manualList.size - 1) {
                                                val newList = manualList.toMutableList()
                                                val moved = newList.removeAt(currentIdx)
                                                newList.add(currentIdx + 1, moved)
                                                manualList = newList
                                                draggingIndex = currentIdx + 1
                                                dragAccum -= threshold
                                            }
                                            while (dragAccum < -threshold && currentIdx > 0) {
                                                val newList = manualList.toMutableList()
                                                val moved = newList.removeAt(currentIdx)
                                                newList.add(currentIdx - 1, moved)
                                                manualList = newList
                                                draggingIndex = currentIdx - 1
                                                dragAccum += threshold
                                            }
                                        },
                                    )
                                }
                            } else {
                                Modifier
                            }

                            SourceCard(
                                source = source,
                                selectionMode = if (manualMode) false else selectionMode,
                                selected = if (manualMode) false else source.id in selectedIds,
                                testStatus = when {
                                    source.id in testing -> SourceTestStatus.TESTING
                                    testResults[source.id] == true -> SourceTestStatus.OK
                                    testResults[source.id] == false -> SourceTestStatus.FAILED
                                    else -> SourceTestStatus.UNKNOWN
                                },
                                loggedIn = loginStates[source.id] == true,
                                loggingIn = source.id in loggingIn,
                                manualMode = manualMode,
                                dragging = isDragging,
                                dragHandleModifier = dragModifier,
                                onToggle = { viewModel.toggleEnabled(source) },
                                onEdit = { viewModel.openEditSource(source) },
                                onDelete = { viewModel.deleteSource(source) },
                                onLogin = { loginTarget = source },
                                onLongClick = {
                                    selectionMode = true
                                    if (source.id !in selectedIds) selectedIds.add(source.id)
                                },
                                onSelectToggle = {
                                    if (source.id in selectedIds) selectedIds.remove(source.id)
                                    else selectedIds.add(source.id)
                                },
                            )
                        }
                    }
                }
            }
        }
    }

    // ===== 源规则编辑对话框 =====
    SourceEditDialog(viewModel = viewModel)

    // ===== 可选添加对话框（v1.4.7.0） =====
    ImportCandidatesDialog(viewModel = viewModel)

    // ===== Venera 登录对话框（v1.3.0.0）=====
    val loggingIn by viewModel.loggingIn.collectAsState()
    val target = loginTarget
    if (target != null) {
        VeneraLoginDialog(
            source = target,
            loggingIn = target.id in loggingIn,
            loggedIn = viewModel.loginStates.collectAsState().value[target.id] == true,
            onConfirm = { account, password ->
                viewModel.login(target, account, password)
                loginTarget = null
            },
            onLogout = {
                viewModel.logout(target)
                loginTarget = null
            },
            onDismiss = { loginTarget = null },
        )
    }
}

/**
 * Venera 源登录对话框（v1.3.0.0）。
 *
 * 输入账号密码调用源自己实现的 account.login；已登录时提供「退出登录」。
 */
@Composable
private fun VeneraLoginDialog(
    source: SourceInfo,
    loggingIn: Boolean,
    loggedIn: Boolean,
    onConfirm: (String, String) -> Unit,
    onLogout: () -> Unit,
    onDismiss: () -> Unit,
) {
    var account by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var showPassword by remember { mutableStateOf(false) }

    androidx.compose.material3.AlertDialog(
        onDismissRequest = { if (!loggingIn) onDismiss() },
        title = { Text("登录 · ${source.name}") },
        text = {
            Column {
                Text(
                    text = "使用该漫画源的网站账号登录，会话将保存到本机",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                androidx.compose.material3.OutlinedTextField(
                    value = account,
                    onValueChange = { account = it },
                    label = { Text("账号 / 邮箱") },
                    singleLine = true,
                    enabled = !loggingIn,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                androidx.compose.material3.OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("密码") },
                    singleLine = true,
                    enabled = !loggingIn,
                    visualTransformation = if (showPassword) {
                        androidx.compose.ui.text.input.VisualTransformation.None
                    } else {
                        androidx.compose.ui.text.input.PasswordVisualTransformation()
                    },
                    trailingIcon = {
                        IconButton(onClick = { showPassword = !showPassword }) {
                            Icon(
                                imageVector = if (showPassword) Icons.Default.Close else Icons.Default.Search,
                                contentDescription = if (showPassword) "隐藏密码" else "显示密码",
                            )
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = !loggingIn && account.isNotBlank() && password.isNotBlank(),
                onClick = { onConfirm(account, password) },
            ) { Text(if (loggingIn) "登录中…" else "登录") }
        },
        dismissButton = {
            Row {
                if (loggedIn) {
                    TextButton(
                        enabled = !loggingIn,
                        onClick = onLogout,
                    ) { Text("退出登录") }
                }
                TextButton(
                    enabled = !loggingIn,
                    onClick = onDismiss,
                ) { Text("取消") }
            }
        },
    )
}

/**
 * 「可选添加」对话框（v1.4.7.0）。
 *
 * 当一份内容解析出多个源（legado 列表 / MoonTV 配置）时弹出，
 * 让用户勾选要添加哪些源，而不是一股脑全部导入。
 */
@Composable
internal fun ImportCandidatesDialog(viewModel: SourceManageViewModel) {
    val candidates by viewModel.importCandidates.collectAsState()
    val selectedIds by viewModel.selectedImportIds.collectAsState()
    val importing by viewModel.importingSelected.collectAsState()

    if (candidates.isEmpty()) return

    androidx.compose.runtime.LaunchedEffect(candidates) {
        viewModel.onImportCandidatesShown()
    }

    androidx.compose.material3.AlertDialog(
        onDismissRequest = { if (!importing) viewModel.cancelImportCandidates() },
        title = { Text("选择要添加的源") },
        text = {
            Column {
                Text(
                    text = "解析出 ${candidates.size} 个源，勾选要添加的：",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                // 全选 / 取消全选
                TextButton(
                    onClick = { viewModel.toggleSelectAllImportCandidates() },
                    modifier = Modifier.align(Alignment.End),
                ) {
                    Text(if (selectedIds.size == candidates.size) "取消全选" else "全选")
                }
                // 候选源列表（可滚动）
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(360.dp),
                ) {
                    items(candidates, key = { it.id }) { src ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { viewModel.toggleImportCandidate(src.id) }
                                .padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            androidx.compose.material3.Checkbox(
                                checked = src.id in selectedIds,
                                onCheckedChange = { viewModel.toggleImportCandidate(src.id) },
                            )
                            Spacer(Modifier.width(8.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = src.name,
                                    style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    text = "${src.type.label()} · ${src.format.name}" +
                                        (if (src.author.isNotBlank()) " · ${src.author}" else ""),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = selectedIds.isNotEmpty() && !importing,
                onClick = { viewModel.confirmImportSelected() },
            ) {
                Text(if (importing) "添加中…" else "添加（${selectedIds.size}）")
            }
        },
        dismissButton = {
            TextButton(
                enabled = !importing,
                onClick = { viewModel.cancelImportCandidates() },
            ) { Text("取消") }
        },
    )
}

/**
 * 源规则编辑对话框。
 *
 * 展示并允许修改源的原始规则（JSON/JS/legado 文本），保存后重新解析并生效。
 */
@Composable
private fun SourceEditDialog(viewModel: SourceManageViewModel) {
    val editingSource by viewModel.editingSource.collectAsState()
    val editingContent by viewModel.editingContent.collectAsState()
    val editingLoading by viewModel.editingLoading.collectAsState()
    val editingSaving by viewModel.editingSaving.collectAsState()

    val source = editingSource ?: return

    androidx.compose.material3.AlertDialog(
        onDismissRequest = { if (!editingSaving) viewModel.closeEditSource() },
        title = { Text("编辑源规则") },
        text = {
            Column {
                Text(
                    text = source.name,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    text = "${source.format.name} · ${source.type.label()} · v${source.version}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                if (editingLoading) {
                    Box(
                        modifier = Modifier.fillMaxWidth().height(200.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        androidx.compose.material3.CircularProgressIndicator()
                    }
                } else {
                    androidx.compose.material3.OutlinedTextField(
                        value = editingContent,
                        onValueChange = viewModel::setEditingContent,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(280.dp),
                        textStyle = MaterialTheme.typography.bodySmall.copy(
                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                        ),
                        placeholder = { Text("在此编辑源规则（JSON / JS / legado）…") },
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !editingLoading && !editingSaving,
                onClick = { viewModel.saveEditingSource() },
            ) { Text(if (editingSaving) "保存中…" else "保存") }
        },
        dismissButton = {
            TextButton(
                enabled = !editingSaving,
                onClick = { viewModel.closeEditSource() },
            ) { Text("取消") }
        },
    )
}

/**
 * 表格视图：以表格形式展示源列表。
 */
@Composable
private fun SourceTableView(
    sources: List<SourceInfo>,
    testResults: Map<String, Boolean> = emptyMap(),
    testing: Set<String> = emptySet(),
    onToggle: (SourceInfo) -> Unit,
    onEdit: (SourceInfo) -> Unit,
    onDelete: (SourceInfo) -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        // 表头
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .padding(horizontal = 12.dp, vertical = 10.dp),
        ) {
            TableCell("名称", 2.0f)
            TableCell("类型", 0.8f)
            TableCell("状态", 1f)
            TableCell("启用", 0.9f)
            TableCell("操作", 1.4f)
        }
        HorizontalDivider()

        LazyColumn(modifier = Modifier.fillMaxSize()) {
            items(sources, key = { it.id }) { source ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TableCell(source.name, 2.0f)
                    TableCell(source.type.label(), 0.8f)
                    val status = when {
                        source.id in testing -> SourceTestStatus.TESTING
                        testResults[source.id] == true -> SourceTestStatus.OK
                        testResults[source.id] == false -> SourceTestStatus.FAILED
                        else -> SourceTestStatus.UNKNOWN
                    }
                    androidx.compose.foundation.layout.Box(modifier = Modifier.weight(1f)) {
                        SourceTestBadge(status)
                    }
                    Row(modifier = Modifier.weight(0.9f)) {
                        Switch(
                            checked = source.enabled,
                            onCheckedChange = { onToggle(source) },
                        )
                    }
                    Row(modifier = Modifier.weight(1.4f)) {
                        TextButton(onClick = { onEdit(source) }) { Text("编辑") }
                        TextButton(onClick = { onDelete(source) }) { Text("删除") }
                    }
                }
                HorizontalDivider()
            }
        }
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.TableCell(text: String, weight: Float) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.weight(weight),
    )
}

/** SourceType 中文标签 */
private fun SourceType.label(): String = when (this) {
    SourceType.NOVEL -> "小说"
    SourceType.COMIC -> "漫画"
    SourceType.VIDEO -> "视频"
}

/**
 * 类型筛选栏（小说 / 漫画 / 视频）。
 */
@Composable
private fun TypeFilterBar(
    selected: SourceType?,
    onSelect: (SourceType?) -> Unit,
) {
    data class TypeOption(val type: SourceType?, val label: String)

    val types = listOf(
        TypeOption(null, "全部"),
        TypeOption(SourceType.NOVEL, "小说"),
        TypeOption(SourceType.COMIC, "漫画"),
        TypeOption(SourceType.VIDEO, "视频"),
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        types.forEach { option ->
            FilterChip(
                selected = selected == option.type,
                onClick = { onSelect(option.type) },
                label = { Text(option.label) },
            )
        }
    }
}

/**
 * 漫画源二级格式筛选栏（v1.3.0.0）：全部分类下按源格式再分一级菜单，
 * Venera 源（独立 WebView 运行时，支持账号登录）与 legado 漫画源分开管理。
 */
@Composable
private fun FormatFilterBar(
    selected: SourceFormat?,
    onSelect: (SourceFormat?) -> Unit,
) {
    data class FormatOption(val format: SourceFormat?, val label: String)

    val options = listOf(
        FormatOption(null, "全部漫画"),
        FormatOption(SourceFormat.LEGADO, "legado"),
        FormatOption(SourceFormat.VENERA, "Venera"),
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        options.forEach { option ->
            FilterChip(
                selected = selected == option.format,
                onClick = { onSelect(option.format) },
                label = { Text(option.label) },
            )
        }
    }
}

/**
 * 分类筛选栏（横向滚动的 FilterChip）。
 */
@Composable
private fun CategoryFilterBar(
    categories: List<String>,
    counts: Map<String, Int>,
    selected: String?,
    onSelect: (String?) -> Unit,
) {
    LazyRow(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // "全部" chip
        item {
            FilterChip(
                selected = selected == null,
                onClick = { onSelect(null) },
                label = { Text("全部 ${counts.values.sum()}") },
            )
        }
        items(categories) { category ->
            FilterChip(
                selected = selected == category,
                onClick = { onSelect(if (selected == category) null else category) },
                label = { Text("$category ${counts[category] ?: 0}") },
            )
        }
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun SourceCard(
    source: SourceInfo,
    onToggle: () -> Unit,
    onEdit: () -> Unit = {},
    onDelete: () -> Unit,
    selectionMode: Boolean = false,
    selected: Boolean = false,
    testStatus: SourceTestStatus = SourceTestStatus.UNKNOWN,
    onLongClick: () -> Unit = {},
    onSelectToggle: () -> Unit = {},
    /** Venera 源登录态（非 Venera 源恒 false），v1.3.0.0 */
    loggedIn: Boolean = false,
    /** 正在登录中（转圈文案） */
    loggingIn: Boolean = false,
    /** 点击「登录」按钮（仅 Venera 源） */
    onLogin: () -> Unit = {},
    /** 手动排序模式（v1.5.8.0；v1.5.9.0 起为拖拽）：显示拖拽把手 */
    manualMode: Boolean = false,
    /** 是否正在被拖拽（拖拽中提升阴影） */
    dragging: Boolean = false,
    /** 拖拽把手的手势 modifier（在 ReorderableItemScope 内构造，v1.5.9.0） */
    dragHandleModifier: Modifier = Modifier,
) {
    val cardModifier = Modifier
        .fillMaxWidth()
        .then(
            if (selectionMode) {
                Modifier.clickable(onClick = onSelectToggle)
            } else if (manualMode) {
                // 手动模式：整卡长按拖动（手势在 dragHandleModifier 里），点击保持可切换
                Modifier.then(dragHandleModifier)
            } else {
                Modifier.combinedClickable(onClick = {}, onLongClick = onLongClick)
            }
        )
    Card(
        modifier = cardModifier,
        colors = if (selected) {
            androidx.compose.material3.CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer,
            )
        } else {
            androidx.compose.material3.CardDefaults.cardColors()
        },
        elevation = if (dragging) {
            androidx.compose.material3.CardDefaults.cardElevation(defaultElevation = 8.dp)
        } else {
            androidx.compose.material3.CardDefaults.cardElevation()
        },
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (manualMode) {
                // 拖拽把手（视觉提示，长按整卡即可拖动，v1.5.9.0）
                Icon(
                    imageVector = Icons.Filled.DragHandle,
                    contentDescription = "拖动排序",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(end = 4.dp),
                )
            }
            if (selectionMode) {
                androidx.compose.material3.Checkbox(
                    checked = selected,
                    onCheckedChange = { onSelectToggle() },
                )
                Spacer(Modifier.width(4.dp))
            }
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = source.name,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.width(8.dp))
                    TypeBadge(source.type)
                    if (source.category.isNotBlank()) {
                        Spacer(Modifier.width(6.dp))
                        CategoryBadge(source.category)
                    }
                    if (testStatus != SourceTestStatus.UNKNOWN) {
                        Spacer(Modifier.width(6.dp))
                        SourceTestBadge(testStatus)
                    }
                    if (source.format == SourceFormat.VENERA && loggedIn) {
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = "已登录",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color(0xFF2E7D32),
                        )
                    }
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "${source.format.name} · v${source.version}" +
                        (if (source.author.isNotEmpty()) " · ${source.author}" else ""),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (source.desc.isNotEmpty()) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = source.desc,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (!selectionMode) {
                Switch(checked = source.enabled, onCheckedChange = { onToggle() })
                // Venera 源：登录按钮（v1.3.0.0）
                if (source.format == SourceFormat.VENERA) {
                    TextButton(onClick = onLogin) {
                        Text(
                            when {
                                loggingIn -> "登录中…"
                                loggedIn -> "已登录"
                                else -> "登录"
                            }
                        )
                    }
                }
                TextButton(onClick = onEdit) { Text("编辑") }
                TextButton(onClick = onDelete) { Text("删除") }
            }
        }
    }
}

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
    )
}

@Composable
private fun CategoryBadge(category: String) {
    Text(
        text = category,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.tertiary,
    )
}

/** 源可用性检测状态 */
enum class SourceTestStatus { UNKNOWN, TESTING, OK, FAILED }

/** 源可用性徽标 */
@Composable
private fun SourceTestBadge(status: SourceTestStatus) {
    val (label, color) = when (status) {
        SourceTestStatus.TESTING -> "检测中" to MaterialTheme.colorScheme.onSurfaceVariant
        SourceTestStatus.OK -> "可用" to Color(0xFF2E7D32)
        SourceTestStatus.FAILED -> "疑似失效" to MaterialTheme.colorScheme.error
        SourceTestStatus.UNKNOWN -> return
    }
    Text(
        text = label,
        style = MaterialTheme.typography.labelSmall,
        color = color,
    )
}
