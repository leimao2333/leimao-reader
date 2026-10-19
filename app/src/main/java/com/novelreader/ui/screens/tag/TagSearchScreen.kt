package com.novelreader.ui.screens.tag

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.SuggestionChipDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImagePainter
import coil.compose.SubcomposeAsyncImage
import coil.compose.SubcomposeAsyncImageContent
import com.novelreader.data.repository.TagIndexRepository
import com.novelreader.domain.model.MediaItem
import com.novelreader.domain.model.SourceType
import com.novelreader.ui.components.CoverPlaceholderLetter
import com.novelreader.ui.components.MediaItemCard
import com.novelreader.ui.components.SourceFilterDropdown

/**
 * 标签搜索页（v1.6.1.0 重做）。
 *
 * 设计目标：让标签搜索变成「**和普通搜索同一个入口**」的第二种形态，而非独立的次级页面。
 * 入口在首页搜索栏左侧下拉（搜索 / 标签），进入此页后看到：
 *
 * 1. **热门推荐**：从已收集标签池按覆盖源数取前 [HOT_TAG_VISIBLE] 个展示在行内，
 *    点选即追加到搜索条件；
 * 2. **「更多 ▾ N」面板**（v1.6.5.0）：**直接展示全部标签**（按覆盖源数倒序，
 *    不再只给热门 top N——标签池上百个，只放热门等于大部分标签点不到）；
 * 3. **手动搜索**：输入框接受多个标签（**空格分隔 = 复合搜索**）。每个标签连同它的
 *    全部译名算一个**大集**，作品需在**每个大集里各命中至少一个**写法才会显示；
 *    点右下角搜索按钮 / IME 搜索即触发；
 * 4. **已选标签 chips**：单点 chip 即可移除；
 * 5. **结果区**：单标签直接聚合；复合标签按「组内取并、组间取交」筛选
 *    （跨源同名视为同一部，见 [com.novelreader.core.util.TagQuery.intersect]）。
 *
 * 结果展示按类型分流：视频 = 3 列海报网格；小说 / 漫画 = 列表卡片。
 */
@Composable
fun TagSearchScreen(
    viewModel: TagSearchViewModel,
    onItemClick: (MediaItem) -> Unit,
    onBack: () -> Unit,
) {
    val hotTags by viewModel.hotTags.collectAsState()
    val allTags by viewModel.allTags.collectAsState() // v1.6.2.0：用于总数显示
    val tagsByCoverage by viewModel.tagsByCoverage.collectAsState() // v1.6.5.0：「更多」面板= 全部标签
    val collecting by viewModel.collecting.collectAsState()
    val query by viewModel.query.collectAsState()
    val results by viewModel.results.collectAsState()
    val searching by viewModel.searching.collectAsState()
    val progress by viewModel.progress.collectAsState()
    val notice by viewModel.notice.collectAsState()
    val sources by viewModel.sources.collectAsState()
    val selectedSourceIds by viewModel.selectedSourceIds.collectAsState()
    val indexCount by viewModel.indexCount.collectAsState()
    val indexing by viewModel.indexing.collectAsState()
    val indexPaused by viewModel.indexPaused.collectAsState()
    val indexMessage by viewModel.indexMessage.collectAsState()
    val hasMore by viewModel.hasMore.collectAsState()
    val loadingMore by viewModel.loadingMore.collectAsState()
    val activeTags by viewModel.activeTags.collectAsState()

    // v1.6.2.0：「更多热门」展开面板开关
    var showAllHotTags by remember { mutableStateOf(false) }

    // v1.8.1.3：全量索引预热入口（遍历源 + 详情打标，重活，按需触发）
    var showIndexDialog by remember { mutableStateOf(false) }
    // v1.8.1.5：云端打标表（PC 工具 → GitHub → App 导入）
    val context = LocalContext.current
    val dialogScope = rememberCoroutineScope()
    var cloudTableUrl by remember { mutableStateOf(TagIndexRepository.DEFAULT_CLOUD_TABLE_URL) }
    val exportListLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { uri ->
        if (uri != null) {
            dialogScope.launch {
                val json = viewModel.exportTagTableJson()
                if (json == null) {
                    viewModel.notifyIndexMessage("导出收录清单失败")
                } else {
                    runCatching {
                        context.contentResolver.openOutputStream(uri)?.use { stream ->
                            stream.write(json.toByteArray(Charsets.UTF_8))
                        } ?: error("无法写入所选位置")
                    }.onFailure { viewModel.notifyIndexMessage("导出失败：${it.message}") }
                }
            }
        }
    }
    if (showIndexDialog) {
        AlertDialog(
            onDismissRequest = { showIndexDialog = false },
            title = { Text("本地标签索引") },
            text = {
                Column {
                    Text("索引会遍历已启用的源，逐部读源站题材并打标签存到本地。建好后标签搜索先命中本地，再补网络，复合筛选不受源站有没有分类页限制。")
                    Spacer(Modifier.height(8.dp))
                    Text("封面视觉打标会对已收录封面跑 WD14 模型（Danbooru 体系），映射进受控词表后以「视觉」来源入库；模型约 356MB，仅 Wi-Fi 下按需下载一次。")
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "已收录 $indexCount 部" + if (indexing) " · 正在处理…" else "",
                        style = MaterialTheme.typography.labelLarge,
                    )
                    if (!indexMessage.isNullOrBlank()) {
                        Spacer(Modifier.height(4.dp))
                        Text(indexMessage!!, style = MaterialTheme.typography.bodySmall)
                    }
                    // v1.8.1.9：遍历可暂停 / 继续 / 停止；关掉弹窗不中断（去阅读页也能继续）
                    if (indexing) {
                        Spacer(Modifier.height(4.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            TextButton(
                                onClick = if (indexPaused) viewModel::resumeIndexing else viewModel::pauseIndexing,
                            ) { Text(if (indexPaused) "继续" else "暂停") }
                            TextButton(onClick = viewModel::stopIndexing) { Text("停止") }
                        }
                        Text(
                            "关闭弹窗不影响后台遍历，可以先去阅读",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    HorizontalDivider()
                    Spacer(Modifier.height(8.dp))
                    // v1.8.1.5：与 PC 端打标工具（仓库 tagger/）协作 —— 导出清单给 PC 补打标，
                    // PC 推到 GitHub 后在这里导入合并（同名标签取更高置信度）。
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TextButton(
                            onClick = { exportListLauncher.launch("leimao-tag-table.json") },
                            enabled = !indexing,
                        ) { Text("导出收录清单") }
                        Spacer(Modifier.width(4.dp))
                        TextButton(
                            onClick = { viewModel.importTagTableFromUrl(cloudTableUrl) },
                            enabled = !indexing,
                        ) { Text("云端导入") }
                    }
                    OutlinedTextField(
                        value = cloudTableUrl,
                        onValueChange = { cloudTableUrl = it },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodySmall,
                        label = { Text("云端打标表地址") },
                    )
                }
            },
            confirmButton = {
                Row {
                    TextButton(onClick = viewModel::buildIndex, enabled = !indexing) {
                        Text(if (indexing) "建立中…" else "遍历建索引")
                    }
                    TextButton(onClick = viewModel::runVisualTagging, enabled = !indexing) {
                        Text("封面视觉打标")
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = { showIndexDialog = false }) { Text("关闭") }
            },
        )
    }

    // enableEdgeToEdge 下自绘顶栏必须自行避让系统栏
    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding(),
    ) {
        // ===== 顶栏：返回 + 标题（显示当前 Type，让用户分辨小说/漫画/视频）=====
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "标签搜索 · ${typeLabel(viewModel.type)}",
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                // 总数 + 状态副标题：明确当前是哪个 Type、已收集多少标签
                Text(
                    text = buildString {
                        if (allTags.isNotEmpty()) append("共 ${allTags.size} 个标签")
                        if (collecting) {
                            if (isNotEmpty()) append(" · ")
                            append("正在收集…")
                        }
                    }.ifBlank { "从各源发现分类自动收集" },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = viewModel::refreshTags, enabled = !collecting) {
                Icon(Icons.Filled.Refresh, contentDescription = "重新收集标签")
            }
        }

        // ===== 热门推荐标签（v1.6.1.0）：按覆盖源数倒序的 top N =====
        // v1.6.2.0：行内只显示前 HOT_TAG_VISIBLE 个 + 「更多 ▾ N」入口；
        // v1.6.5.0：「更多」面板 [AllHotTagsSheet] 展示**全部**标签
        //         （tagsByCoverage），不再有 HOT_TAG_LIMIT 硬顶。
        if (hotTags.isNotEmpty() || collecting) {
            Column {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "热门推荐",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.weight(1f))
                    if (collecting) {
                        CircularProgressIndicator(
                            Modifier.size(12.dp),
                            strokeWidth = 1.5.dp,
                        )
                    }
                }
                if (hotTags.isEmpty() && collecting) {
                    Text(
                        text = "正在收集标签…",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 6.dp),
                    )
                } else {
                    LazyRow(
                        modifier = Modifier.fillMaxWidth(),
                        contentPadding = PaddingValues(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        // 前 8 个可见
                        items(
                            hotTags.take(HOT_TAG_VISIBLE),
                            key = { "hot_$it" },
                        ) { tag ->
                            HotTagChip(
                                tag = tag,
                                active = tag in activeTags,
                                onClick = {
                                    // 点已选 = 移除；点未选 = 追加并搜索
                                    if (tag in activeTags) viewModel.removeTag(tag)
                                    else viewModel.addTag(tag)
                                },
                            )
                        }
                        // 「更多」入口——剩余标签数（v1.6.5.0：面板里是**全部**标签，
                        // 不只是热门，所以计数按 tagsByCoverage 全集算）
                        val more = tagsByCoverage.size - HOT_TAG_VISIBLE
                        if (more > 0) {
                            item(key = "hot_more") {
                                MoreHotChip(
                                    remain = more,
                                    onClick = { showAllHotTags = true },
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                }
            }
        }

        HorizontalDivider()

        // ===== 手动搜索（v1.6.1.0）：输入框 + IME 搜索 / 回车触发 =====
        OutlinedTextField(
            value = query,
            onValueChange = viewModel::setQuery,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            placeholder = { Text("标签（空格=同时满足 · -排除 · 题材:标签 · 魔法* 通配）") },
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyMedium,
            trailingIcon = {
                if (searching) {
                    CircularProgressIndicator(
                        Modifier
                            .size(20.dp)
                            .padding(end = 12.dp),
                        strokeWidth = 2.dp,
                    )
                } else {
                    IconButton(onClick = viewModel::search) {
                        Icon(Icons.Filled.Search, contentDescription = "搜索")
                    }
                }
            },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { viewModel.search() }),
        )

        // 「按源搜索」（v1.8.1.2）：标签池是跨源收集的，只搜某一个源能省掉几十个源一起等的长尾
        // 「按源搜索」多选下拉 + 本地标签索引入口（v1.8.1.2）
        if (sources.isNotEmpty()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SourceFilterDropdown(
                    sources = sources,
                    selectedIds = selectedSourceIds,
                    onSelectionChange = viewModel::setSelectedSources,
                    modifier = Modifier.padding(start = 8.dp),
                )
                Spacer(Modifier.weight(1f))
                // 索引不再是「要先跑一遍的独立任务」，而是搜索时自动累积（v1.8.1.2）；
                // v1.8.1.3 起这个数字同时是**全量预热**入口（遍历源 + 详情打标）。
                Text(
                    text = if (indexCount > 0) "已收录 $indexCount 部 ▸" else "建立本地索引 ▸",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .padding(end = 12.dp)
                        .clickable { showIndexDialog = true }
                        .padding(vertical = 6.dp),
                )
            }
        }

        // ===== 已选标签 chips（v1.6.1.0）：点选移除 =====
        if (activeTags.isNotEmpty()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "已选标签：",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.width(4.dp))
                LazyRow(
                    modifier = Modifier.weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    contentPadding = PaddingValues(end = 12.dp),
                ) {
                    items(activeTags, key = { it }) { tag ->
                        AssistChip(
                            onClick = { viewModel.removeTag(tag) },
                            label = { Text(tag) },
                            trailingIcon = {
                                Icon(
                                    Icons.Filled.Close,
                                    contentDescription = "移除 $tag",
                                    modifier = Modifier.size(14.dp),
                                )
                            },
                        )
                    }
                }
            }
        }

        // ===== 进度 / 提示 =====
        progress?.let { (done, total) ->
            Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp)) {
                Text(
                    text = "正在搜索 $done/$total…",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(4.dp))
                LinearProgressIndicator(
                    progress = { if (total > 0) done.toFloat() / total else 0f },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        notice?.let { message ->
            // v1.8.1.2：这块内容可能极长（「无码」27 个写法 + 「全彩」42 个），
            // 改成**可折叠面板**：默认只露两行摘要 + 下拉箭头，点一下才铺开全部；
            // 展开后仍限高可滚动，不会把下面的结果区挤没。
            var noticeExpanded by remember(message) { mutableStateOf(false) }
            Column(
                Modifier
                    .fillMaxWidth()
                    .clickable { noticeExpanded = !noticeExpanded }
                    .padding(horizontal = 16.dp, vertical = 4.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = if (noticeExpanded) "标签详情（点此收起）" else message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = if (noticeExpanded) 1 else 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Icon(
                        imageVector = if (noticeExpanded) Icons.Filled.KeyboardArrowUp
                        else Icons.Filled.KeyboardArrowDown,
                        contentDescription = if (noticeExpanded) "收起" else "展开",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp),
                    )
                }
                if (noticeExpanded) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .heightIn(max = 200.dp)
                            .verticalScroll(rememberScrollState())
                            .padding(top = 4.dp),
                    ) {
                        Text(
                            text = message,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }
        }

        // ===== 结果区 =====
        when {
            searching && results.isEmpty() -> {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }

            results.isEmpty() -> {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        text = if (activeTags.isEmpty()) "点上面「热门推荐」或在搜索框输入标签\n多个标签用空格分开可组合筛选"
                        else "没有匹配的内容",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    )
                }
            }

            viewModel.type == SourceType.VIDEO -> {
                val gridState = rememberLazyGridState()
                // 触底即加载下一页（v1.8.1.2）：首次搜索已后台预取 3 页，这里只兜住更深的滚动
                LaunchedEffect(gridState, hasMore) {
                    snapshotFlow { gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }
                        .distinctUntilChanged()
                        .collect { last ->
                            val total = gridState.layoutInfo.totalItemsCount
                            if (hasMore && total > 0 && last >= total - 3) viewModel.loadMore()
                        }
                }
                LazyVerticalGrid(
                    columns = GridCells.Fixed(3),
                    modifier = Modifier.fillMaxSize(),
                    state = gridState,
                    contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(results, key = { "${it.sourceId}:${it.id}" }) { item ->
                        TagPosterCard(item = item, onClick = { onItemClick(item) })
                    }
                    if (loadingMore) {
                        item(key = "loading-more") { LoadMoreFooter(loading = true, hasMore = true) {} }
                    } else if (hasMore) {
                        item(key = "load-more") { LoadMoreFooter(loading = false, hasMore = true) { viewModel.loadMore() } }
                    }
                }
            }

            else -> {
                val listState = rememberLazyListState()
                LaunchedEffect(listState, hasMore) {
                    snapshotFlow { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }
                        .distinctUntilChanged()
                        .collect { last ->
                            val total = listState.layoutInfo.totalItemsCount
                            if (hasMore && total > 0 && last >= total - 3) viewModel.loadMore()
                        }
                }
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    state = listState,
                    contentPadding = PaddingValues(vertical = 8.dp),
                ) {
                    items(results, key = { "${it.sourceId}:${it.id}" }) { item ->
                        MediaItemCard(
                            item = item,
                            onClick = { onItemClick(item) },
                            onRead = { onItemClick(item) },
                        )
                    }
                    if (loadingMore) {
                        item(key = "loading-more") { LoadMoreFooter(loading = true, hasMore = true) {} }
                    } else if (hasMore) {
                        item(key = "load-more") { LoadMoreFooter(loading = false, hasMore = true) { viewModel.loadMore() } }
                    }
                }
            }
        }
    }

    // ===== 「更多热门标签」底部面板（v1.6.2.0；v1.6.5.0 改为全部标签）=====
    if (showAllHotTags) {
        AllHotTagsSheet(
            tags = tagsByCoverage,
            activeTags = activeTags,
            onPick = { tag ->
                if (tag in activeTags) viewModel.removeTag(tag)
                else viewModel.addTag(tag)
            },
            onDismiss = { showAllHotTags = false },
        )
    }
}

/**
 * 「更多」底部弹层（v1.6.2.0）。
 *
 * 顶部热门行只显示前 [HOT_TAG_VISIBLE] 个，其余全部塞进这个面板。
 * v1.6.5.0：面板内容是**全部标签**（按覆盖源数倒序），不再是热门 top N——
 * 标签池上百个，只放热门等于大部分标签用户永远点不到。
 * 面板标题 = "全部标签 · 小说"（明确类型），副标题 = "共 N 个，按覆盖源数倒序」；
 * 内容区是 [FlowRow]（横纵自动换行），不是 12 列网格，避免「少年漫」这种短词被拉成夸张高度。
 *
 * 单个标签点击行为与顶部行一致：未选 = 追加并搜索；已选 = 移除。
 */
@OptIn(
    androidx.compose.foundation.layout.ExperimentalLayoutApi::class,
    androidx.compose.material3.ExperimentalMaterial3Api::class,
)
@Composable
private fun AllHotTagsSheet(
    tags: List<String>,
    activeTags: List<String>,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    // v1.6.6.0：Venera 源分类进来后漫画标签池有几百个，FlowRow 不是懒加载
    // （一次性 compose 全部子项），全量渲染会明显卡顿 → 分批展开。
    var visibleCount by remember(tags.size) { mutableStateOf(TAG_SHEET_PAGE) }
    val shown = if (tags.size <= visibleCount) tags else tags.take(visibleCount)

    androidx.compose.material3.ModalBottomSheet(
        onDismissRequest = onDismiss,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                // v1.6.5.0：全部标签可能上百个，FlowRow 必须可滚动
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            Text(
                text = "全部标签",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Medium,
            )
            Text(
                text = "共 ${tags.size} 个，按覆盖源数倒序",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp, bottom = 12.dp),
            )
            androidx.compose.foundation.layout.FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                shown.forEach { tag ->
                    HotTagChip(
                        tag = tag,
                        active = tag in activeTags,
                        onClick = { onPick(tag) },
                    )
                }
            }
            if (shown.size < tags.size) {
                androidx.compose.material3.TextButton(
                    onClick = { visibleCount += TAG_SHEET_PAGE },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("显示更多（还剩 ${tags.size - shown.size} 个）")
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

/**
 * 当前 Type 的中文显示名（v1.6.2.0）。
 *
 * 顶部副标题与「更多热门」面板都要用，集中一处避免拼写漂移。
 */
private fun typeLabel(type: SourceType): String = when (type) {
    SourceType.NOVEL -> "小说"
    SourceType.COMIC -> "漫画"
    SourceType.VIDEO -> "视频"
}

/**
 * v1.6.2.0：热门推荐行内直接可见的标签数。
 *
 * 行宽按 6 寸屏测算，每 chip 约 64dp + 8dp spacing，
 * 屏幕宽 360dp 大约放下 4~5 个；这里给 8 个，超出部分进「更多 ▾ N」入口。
 * 用户的视觉上限是 1 行（再放多就换行了，看起来乱）。
 */
private const val HOT_TAG_VISIBLE = 8

/**
 * 「全部标签」面板每批展开多少个（v1.6.6.0）。
 *
 * 面板用 [FlowRow] 承载，**不是懒加载** —— 会一次性 compose 所有子项。
 * Venera 源分类接入后漫画标签池有几百个，全量渲染明显卡顿，所以分批展开。
 */
private const val TAG_SHEET_PAGE = 150

/**
 * 热门推荐标签 chip（v1.6.1.0）。
 *
 * 未选：浅灰 suggestion chip 配色；已选：primary container 高亮；
 * 点选行为由外层 [TagSearchScreen] 决定（追加 / 移除）。
 */
@Composable
private fun HotTagChip(
    tag: String,
    active: Boolean,
    onClick: () -> Unit,
) {
    if (active) {
        AssistChip(
            onClick = onClick,
            label = {
                Text(
                    tag,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            },
            colors = AssistChipDefaults.assistChipColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer,
                labelColor = MaterialTheme.colorScheme.onPrimaryContainer,
            ),
            trailingIcon = {
                Icon(
                    Icons.Filled.Close,
                    contentDescription = "移除 $tag",
                    modifier = Modifier.size(14.dp),
                )
            },
        )
    } else {
        SuggestionChip(
            onClick = onClick,
            label = {
                Text(
                    tag,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            },
            colors = SuggestionChipDefaults.suggestionChipColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
                labelColor = MaterialTheme.colorScheme.onSurfaceVariant,
            ),
        )
    }
}

/**
 * 「更多 ▾ N」入口 chip（v1.6.2.0）。
 *
 * 行内 [HOT_TAG_VISIBLE] 之外的标签数；点开弹 [AllHotTagsSheet]。
 * 与普通热门 chip 视觉一致（surfaceVariant），仅在 label 上区分开。
 */
@Composable
private fun MoreHotChip(remain: Int, onClick: () -> Unit) {
    AssistChip(
        onClick = onClick,
        label = {
            Text(
                "更多 ▾ $remain",
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                fontWeight = FontWeight.Medium,
            )
        },
        colors = AssistChipDefaults.assistChipColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
            labelColor = MaterialTheme.colorScheme.onSurfaceVariant,
        ),
    )
}

/** 标签搜索结果的海报卡（视频）：2:3 封面 + 标题 + 来源名 */
@Composable
private fun TagPosterCard(item: MediaItem, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(2f / 3f)
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
        ) {
            val cover = item.cover
            if (cover.isNullOrBlank()) {
                CoverPlaceholderLetter(modifier = Modifier.fillMaxSize(), title = item.title)
            } else {
                SubcomposeAsyncImage(
                    model = cover,
                    contentDescription = item.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                ) {
                    when (painter.state) {
                        is AsyncImagePainter.State.Error -> CoverPlaceholderLetter(
                            Modifier.fillMaxSize(), item.title,
                        )
                        is AsyncImagePainter.State.Loading -> CoverPlaceholderLetter(
                            Modifier.fillMaxSize(), item.title,
                        )
                        else -> SubcomposeAsyncImageContent()
                    }
                }
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            text = item.title,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (item.sourceName.isNotBlank()) {
            Text(
                text = item.sourceName,
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * 列表底部的「加载更多」条（v1.8.1.2）。
 *
 * 正常情况下预取已经把下一页准备好了，这里只在用户滚得比预取更快时兜底。
 */
@Composable
private fun LoadMoreFooter(loading: Boolean, hasMore: Boolean, onLoadMore: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        when {
            loading -> CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
            hasMore -> TextButton(onClick = onLoadMore) { Text("加载更多") }
            else -> Text(
                text = "没有更多了",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
