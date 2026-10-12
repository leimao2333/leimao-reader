package com.novelreader.ui.screens.source

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.novelreader.domain.model.SourceInfo
import com.novelreader.domain.model.SourceType

/**
 * 源管理页。
 *
 * 展示已导入的源列表，支持启用/禁用、删除，并通过浮动按钮进入导入页。
 * 支持类型/分类筛选、排序、列表/表格两种视图。
 */
@OptIn(ExperimentalMaterial3Api::class)
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
    // 排序方式
    var sortOrder by remember { mutableStateOf(SourceSortOrder.DEFAULT) }
    // 视图模式：false = 列表，true = 表格
    var tableView by remember { mutableStateOf(false) }
    // 排序菜单展开
    var sortMenuOpen by remember { mutableStateOf(false) }
    // 批量选择模式
    var selectionMode by remember { mutableStateOf(false) }
    val selectedIds = remember { androidx.compose.runtime.mutableStateListOf<String>() }

    LaunchedEffect(Unit) { viewModel.observe() }

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
    val grouped = remember(typeFiltered) { viewModel.groupByCategory(typeFiltered) }
    val categories = remember(typeFiltered) { viewModel.sortedCategories(typeFiltered) }
    val categoryFiltered = remember(typeFiltered, selectedCategory) {
        if (selectedCategory == null) typeFiltered
        else grouped[selectedCategory] ?: emptyList()
    }
    val displayedSources = remember(categoryFiltered, sortOrder) {
        viewModel.sortSources(categoryFiltered, sortOrder)
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
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val failedCount = testResults.count { !it.value }
                    Text(
                        text = when {
                            testing.isNotEmpty() -> "正在检测…（剩余 ${testing.size}）"
                            testResults.isEmpty() -> "尚未检测源可用性"
                            failedCount == 0 -> "检测完成：全部可用"
                            else -> "检测到 $failedCount 个疑似失效"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(
                        enabled = testing.isEmpty(),
                        onClick = { viewModel.testSources(displayedSources) },
                    ) { Text("检测可用性") }
                    TextButton(
                        enabled = failedCount > 0 && testing.isEmpty(),
                        onClick = { viewModel.disableFailedSources() },
                    ) { Text("禁用失效") }
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
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(displayedSources, key = { it.id }) { source ->
                            SourceCard(
                                source = source,
                                selectionMode = selectionMode,
                                selected = source.id in selectedIds,
                                testStatus = when {
                                    source.id in testing -> SourceTestStatus.TESTING
                                    testResults[source.id] == true -> SourceTestStatus.OK
                                    testResults[source.id] == false -> SourceTestStatus.FAILED
                                    else -> SourceTestStatus.UNKNOWN
                                },
                                onToggle = { viewModel.toggleEnabled(source) },
                                onEdit = { viewModel.openEditSource(source) },
                                onDelete = { viewModel.deleteSource(source) },
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
) {
    val cardModifier = Modifier
        .fillMaxWidth()
        .then(
            if (selectionMode) {
                Modifier.clickable(onClick = onSelectToggle)
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
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
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
