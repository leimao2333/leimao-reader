@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

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
import androidx.compose.foundation.lazy.grid.GridItemSpan
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
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.SuggestionChipDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.novelreader.core.util.TagAlias
import com.novelreader.core.util.TagSuggestions
import com.novelreader.data.repository.TagIndexRepository
import com.novelreader.domain.model.MediaItem
import com.novelreader.domain.model.SourceType
import com.novelreader.ui.components.CoverPlaceholderLetter
import com.novelreader.ui.components.LazyCover
import com.novelreader.ui.components.MediaItemCard
import com.novelreader.ui.components.SourceFilterDropdown
import com.novelreader.ui.components.adaptiveGridColumns

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
 *
 * v1.9.2.0：普通标签（含组首词「全彩」）是**单标签搜索**；整组搜索写成
 * 「组首词 + 组」（「全彩组」）。已选整组写法时，下面多一行「组内标签」——
 * 点其中一个词就改搜那一个词；反过来，已选普通写法时结果区顶部给一条
 * 「改搜整组」的入口。
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun TagSearchScreen(
    viewModel: TagSearchViewModel,
    onItemClick: (MediaItem) -> Unit,
    onBack: () -> Unit,
) {
    val hotTags by viewModel.hotTags.collectAsState()
    val allTags by viewModel.allTags.collectAsState() // v1.6.2.0：用于总数显示
    // v1.9.2.0：「更多」面板 = 普通标签 + 组别卡片（按收录数排序）
    val sheetPlainTags by viewModel.sheetPlainTags.collectAsState()
    val sheetGroups by viewModel.sheetGroups.collectAsState()
    val sheetTotal by viewModel.sheetTotal.collectAsState()
    val collecting by viewModel.collecting.collectAsState()
    val query by viewModel.query.collectAsState()
    // v1.8.6.0 待办 #27：输入提示词（本地标签池，前缀优先）
    val suggestions by viewModel.suggestions.collectAsState()
    val results by viewModel.results.collectAsState()
    val searching by viewModel.searching.collectAsState()
    val progress by viewModel.progress.collectAsState()
    val notice by viewModel.notice.collectAsState()
    val sources by viewModel.sources.collectAsState()
    val selectedSourceIds by viewModel.selectedSourceIds.collectAsState()
    val indexCount by viewModel.indexCount.collectAsState()
    // 待办 #47：每个标签的已收录数（chip 角标）
    val tagCounts by viewModel.tagCounts.collectAsState()
    // v1.9.2.0：已选「整组」标签的组内成员（点一个 = 单标签搜索）
    val groupMembers by viewModel.groupMembers.collectAsState()
    // v1.9.2.0：当前处于「单标签搜索」的标签（成员 → 所属组首词）
    val individualTags by viewModel.individualTags.collectAsState()
    // 待办 #47：本轮结果里哪些是本地已收录的（卡片角标）
    val indexedKeys by viewModel.indexedKeys.collectAsState()
    val indexing by viewModel.indexing.collectAsState()
    // 视觉模型总开关（v1.8.7.2，默认关）：关着时「封面视觉打标」按钮禁用
    val visualTaggingEnabled by viewModel.visualTaggingEnabled.collectAsState()
    // 编辑模式（v1.9.2.0）：进入后点标签 = 删除（带确认）
    var editMode by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<String?>(null) }
    val indexPaused by viewModel.indexPaused.collectAsState()
    val indexMessage by viewModel.indexMessage.collectAsState()
    val hasMore by viewModel.hasMore.collectAsState()
    val loadingMore by viewModel.loadingMore.collectAsState()
    /** 自动翻是在第几页停下的（v1.8.7.8，0 = 还没停过）：页脚要说明「为什么到底」 */
    val emptyPage by viewModel.emptyPage.collectAsState()
    /** 正在加载第几页（v1.8.7.5）：页脚要显示「正在加载第 N 页」，否则几百个请求跑着像卡死 */
    val loadingPage by viewModel.loadingPage.collectAsState()
    /** 这次搜索用了多少个源（v1.8.7.5）：顶部与数量一起显示 */
    val sourceCount by viewModel.sourceCount.collectAsState()
    /**
     * 搜索阶段（v1.8.9.1）：STREAMING = 还有任务没进终态，DONE_PARTIAL = 有源没回话。
     *
     * 和 `searching` 的区别：`searching` 只管转圈，这个管**说法**——
     * 「还在收」和「收完了但可能不全」是两件事，界面上必须分开讲，
     * 否则用户看到结果停住不动，会以为这就是全部了。
     */
    val phase by viewModel.phase.collectAsState()
    /** 因超时/报错而「不知道有没有」的源数量（v1.8.9.1）：结果可能不全时说清楚 */
    val slowSourceCount by viewModel.slowSourceCount.collectAsState()
    val activeTags by viewModel.activeTags.collectAsState()
    /** 结果展示方式（v1.8.7.1）：true = 宫格，false = 列表 */
    val searchGrid by viewModel.searchGrid.collectAsState()
    /** 宫格列数（v1.8.7.5）：0 = 自适应（视频 3 列、其余 2 列），2~5 = 固定 */
    val gridColumns by viewModel.gridColumns.collectAsState()

    // 从详情页 / 阅读页返回时补完上次被中断的首屏召回（v1.8.9.6）。
    // 点开结果时为了腾出执行线程会主动停掉召回，回来不补的话顶部会一直挂着
    // 「部分源未返回」、列表也停在半截结果上 —— 用户侧就是「回来就不继续搜索了」。
    val lifecycleOwner = androidx.compose.ui.platform.LocalLifecycleOwner.current
    androidx.compose.runtime.DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) viewModel.resumeAfterReturn()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // v1.6.2.0：「更多热门」展开面板开关
    var showAllHotTags by remember { mutableStateOf(false) }

    // v1.8.1.3：全量索引预热入口（遍历源 + 详情打标，重活，按需触发）
    var showIndexDialog by remember { mutableStateOf(false) }
    // v1.8.9.2：清空当前类型已收录的标签池（标签池变脏时从头收集一份干净的）
    var confirmClearTags by remember { mutableStateOf(false) }
    // v1.8.1.5：云端打标表（PC 工具 → GitHub → App 导入）
    val context = LocalContext.current
    val dialogScope = rememberCoroutineScope()
    var cloudTableUrl by remember { mutableStateOf(TagIndexRepository.DEFAULT_CLOUD_TABLE_URL) }
    val exportListLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { uri ->
        if (uri != null) {
            dialogScope.launch {
                // v1.8.3.0 待办 #20：流式写出，边查边写（原来是先拼整串再一次性落盘）
                val ok = viewModel.exportTagTableToUri(context, uri)
                if (!ok) viewModel.notifyIndexMessage("导出收录清单失败")
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
                    if (!visualTaggingEnabled) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "视觉模型打标当前关闭 —— 在「设置 → 标签」里打开「启用视觉模型打标」后可用",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
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
                    TextButton(
                        onClick = viewModel::runVisualTagging,
                        enabled = !indexing && visualTaggingEnabled,
                    ) {
                        Text("封面视觉打标")
                    }
                }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = { confirmClearTags = true }) {
                        Text("清空收录标签", color = MaterialTheme.colorScheme.error)
                    }
                    TextButton(onClick = { showIndexDialog = false }) { Text("关闭") }
                }
            },
        )
    }

    // 清空标签池是破坏性操作，先确认再动手
    if (confirmClearTags) {
        AlertDialog(
            onDismissRequest = { confirmClearTags = false },
            title = { Text("清空收录标签？") },
            text = {
                Text(
                    "会删掉当前类型已收录的全部标签（以及这些标签指向的各源入口），" +
                        "下次进入本页会重新从各源分类收集一份。已建好的作品索引不受影响。",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmClearTags = false
                        viewModel.clearCollectedTags()
                    },
                ) { Text("清空", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { confirmClearTags = false }) { Text("取消") }
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
            // 宫格 / 列表切换（v1.8.7.1）：与小说 / 漫画 / 视频搜索页同一个交互位置
            IconButton(onClick = { viewModel.setSearchGrid(!searchGrid) }) {
                Icon(
                    imageVector = if (searchGrid) Icons.AutoMirrored.Filled.ViewList
                    else Icons.Filled.GridView,
                    contentDescription = if (searchGrid) "切换为列表" else "切换为宫格",
                )
            }
            // 宫格列数（v1.8.7.5）：只在宫格态出现 —— 列表态没有「列」的概念，
            // 常驻一个按钮只会占掉标题的位置，还会让人以为列表模式坏了。
            if (searchGrid) {
                var columnMenu by remember { mutableStateOf(false) }
                Box {
                    TextButton(onClick = { columnMenu = true }) {
                        Text(
                            text = if (gridColumns >= 2) "$gridColumns 列" else "自适应",
                            style = MaterialTheme.typography.labelMedium,
                        )
                    }
                    DropdownMenu(
                        expanded = columnMenu,
                        onDismissRequest = { columnMenu = false },
                    ) {
                        listOf(0, 2, 3, 4, 5).forEach { value ->
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        when (value) {
                                            0 -> "自适应（视频 3 · 其余 2）"
                                            else -> "$value 列"
                                        },
                                    )
                                },
                                onClick = {
                                    viewModel.setGridColumns(value)
                                    columnMenu = false
                                },
                                trailingIcon = {
                                    if (gridColumns == value) {
                                        Icon(
                                            Icons.Filled.Check,
                                            contentDescription = "当前",
                                            tint = MaterialTheme.colorScheme.primary,
                                        )
                                    }
                                },
                            )
                        }
                    }
                }
            }
            IconButton(onClick = viewModel::refreshTags, enabled = !collecting) {
                Icon(Icons.Filled.Refresh, contentDescription = "重新收集标签")
            }
        }

        // ===== 热门推荐标签（v1.6.1.0）：按覆盖源数倒序的 top N =====
        // v1.6.2.0：行内只显示前 HOT_TAG_VISIBLE 个 + 「更多 ▾ N」入口；
        // v1.6.5.0：「更多」面板 [AllHotTagsSheet] 展示**全部**标签
        //         （按收录数排序，组别系列一并展开），不再有 HOT_TAG_LIMIT 硬顶。
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
                                count = tagCounts[tag] ?: 0,
                                onClick = {
                                    // 点已选 = 移除；点未选 = 追加并搜索
                                    if (tag in activeTags) viewModel.removeTag(tag)
                                    else viewModel.addTag(tag)
                                },
                            )
                        }
                        // 「更多」入口——剩余标签数（v1.6.5.0：面板里是**全部**标签，
                        // 不只是热门，所以计数按面板全集算）
                        val more = sheetTotal - HOT_TAG_VISIBLE
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
        // v1.8.7.1：提示词从「浮动下拉菜单」改成**输入框正下方的内联面板**。
        // 老实现的两个硬伤：
        // 1) 下拉菜单的 Popup 默认 focusable，一弹出就抢走输入焦点 —— 软键盘当场收起，
        //    用户打完半个词要重新点输入框才能接着打，这是最要命的「不像人用的」；
        // 2) 菜单从输入框左上角开始绘制（只给了 x 偏移），直接盖住正在输入的那行字。
        // 内联面板贴在输入框下方：不抢焦点、键盘不动、位置天然对齐；代价是结果区会被
        // 推下去不到一屏的高度 —— 比「键盘莫名消失」划算得多。
        OutlinedTextField(
            value = query,
            onValueChange = viewModel::setQuery,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            placeholder = { Text("标签（空格=同时满足 · -排除 · 题材:标签 · 魔法* 通配 · 全彩组=整组）") },
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
        if (suggestions.isNotEmpty()) {
            TagSuggestionPanel(
                suggestions = suggestions,
                word = TagSuggestions.currentWord(query),
                onPick = viewModel::applySuggestion,
                onDismiss = viewModel::clearSuggestions,
            )
        }

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
                        // v1.9.1.1：已选标签也带收录数 —— 只写名字的话，用户不知道
                        // 这个标签到底能搜出多少，得点进去才看得出来。
                        // v1.9.2.0：整组写法（「全彩组」）不是真实标签，计数按组首词取。
                        // v1.9.2.0：自己的收录数 + （属于分组时）额外带上整组的收录数
                        val label = selectedChipLabel(tag, tagCounts)
                        AssistChip(
                            onClick = { viewModel.removeTag(tag) },
                            // 数字始终显示（含 0）：之前为 0 就只写名字，新标签看起来
                            // 像「没有计数」，用户会以为是坏了
                            label = { Text(label) },
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

        // ===== 组内标签（v1.9.2.0）=====
        // 已选「全彩」时，把这一组里其它写法铺一行：点一个词 = **单标签搜索**，
        // 而「全彩」这个 chip 本身仍然是整组搜索。分组、分组计数一点没变，
        // 组里每个词又能单独搜出来。
        if (groupMembers.isNotEmpty()) {
            Column(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 16.dp, end = 16.dp, top = 2.dp, bottom = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "组内标签（点一个词单独搜索）",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.weight(1f))
                    Text(
                        text = "共 ${groupMembers.size} 个",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                LazyRow(
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(groupMembers, key = { "member_$it" }) { member ->
                        HotTagChip(
                            tag = member,
                            active = false,
                            count = tagCounts[member] ?: 0,
                            onClick = { viewModel.searchIndividual(member) },
                        )
                    }
                }
                Spacer(Modifier.height(4.dp))
            }
        }

        // ===== 单标签搜索提示（v1.9.2.0）=====
        // 用户点了组内某个词（「彩绘」）后，结果里不再包含整组其它写法。
        // 这里给一条一键回到整组搜索的入口，避免「怎么比点全彩时少这么多」的困惑。
        if (individualTags.isNotEmpty()) {
            androidx.compose.foundation.layout.FlowRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                individualTags.take(2).forEach { (member, head) ->
                    AssistChip(
                        onClick = { viewModel.searchGroupOf(member) },
                        label = {
                            Text(
                                "「$member」单标签搜索 · 改搜整组「$head${TagAlias.GROUP_SUFFIX}」",
                            )
                        },
                    )
                }
            }
        }

        // ===== 已找到多少 / 总进度（v1.8.7.5 改口径，v1.8.7.6 再改）=====
        // 以前这里只显示「正在搜索 32/32」，分母是**本页要发的请求数**，翻页跑完也不清零，
        // 看着就像永远卡在 32。现在：
        // 1. 先给结果数量（用户真正关心的），再给覆盖面（用多少源）；
        // 2. 进度是**整次搜索的总进度**，不是每页各算各的 —— 每次翻页都从头跑一遍
        //    进度条，看着就像永远跑不完。
        // v1.8.7.8：页码段去掉了 —— 它跟「总进度」是同一件事的两种说法，并排显示
        // 反而让人以为是两个不同的进度，且行太长会折成两行（小屏尤其明显）。
        val found = results.size
        val busy = searching || loadingPage > 0
        if (found > 0 || busy) {
            Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp)) {
                Text(
                    text = buildString {
                        append(if (found > 0) "已找到 $found 部" else "正在搜索…")
                        // 「用多少源」也留着：这个数说明覆盖面，掉了用户会以为源被悄悄砍了
                        if (sourceCount > 0) append("　·　用 $sourceCount 个源")
                        // v1.8.9.1：总进度只在「还在收」时显示。收完之后分母里还挂着
                        // 预取那几页的请求数，留在屏幕上会变成「已找到 424 部 · 总进度 504/738」，
                        // 看着像没跑完。
                        if (phase == SearchPhase.STREAMING) {
                            progress?.let { (done, total) ->
                                if (total > 0) append("　·　总进度 $done/$total")
                            }
                        }
                        // v1.8.9.1：任务终态决定说法，不再靠「结果有没有变」猜
                        when (phase) {
                            SearchPhase.STREAMING -> append("　·　仍在收集")
                            SearchPhase.DONE_PARTIAL -> append(
                                if (slowSourceCount > 0) "　·　$slowSourceCount 个源未返回，结果可能不全"
                                else "　·　部分源未返回，结果可能不全",
                            )
                            else -> Unit
                        }
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (busy && progress != null) {
                    Spacer(Modifier.height(4.dp))
                    val (done, total) = progress!!
                    LinearProgressIndicator(
                        progress = { if (total > 0) done.toFloat() / total else 0f },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
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
        // 列表 / 宫格的 key 是 `sourceId:id`（同一条作品的唯一身份）。结果来自
        // 「本地索引 + 多源网络 + 翻页追加」三条路，任何一条漏了去重，这里就会撞出
        // `Key "…" was already used` 直接闪退 —— 而且**只在滚到那一条时才崩**，
        // 表面看就像「快速拖动滚动条导致闪退」（v1.8.7.5 加固：喂给列表前再收敛一次）。
        val displayResults = remember(results) {
            if (results.size < 2) {
                results
            } else {
                val seen = HashSet<String>(results.size)
                results.filter { seen.add("${it.sourceId}:${it.id}") }
            }
        }
        when {
            searching && displayResults.isEmpty() -> {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }

            displayResults.isEmpty() -> {
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

            // 宫格态（v1.8.7.1 起由用户开关决定，不再按类型写死）：
            // 列数由用户指定（v1.8.7.5），没指定时视频三列、小说 / 漫画两列 ——
            // 这两类封面本身比视频更方，三列会把书名挤成一两个字。
            searchGrid -> {
                val gridState = rememberLazyGridState()
                // 到底之后继续上滑 = 再往前问一页（v1.8.9.6，替代原来的「继续往下翻」按钮）
                val canSwipeContinue = remember(gridState) {
                    derivedStateOf {
                        // 判「到底」而不是判「没有更多」：以前要求 !hasMore，
                        // 一旦 hasMore 还是 true、自动触底加载又没跑起来（正在加载、
                        // 或最后几项判断没命中），列表就滑不动、上滑也不响应 —— 彻底卡死。
                        // 现在只要列表不能再往前滚、且不在加载中，上滑就当作「再问一页」。
                        results.isNotEmpty() && !loadingMore &&
                            gridState.layoutInfo.totalItemsCount > 0 &&
                            !gridState.canScrollForward
                    }
                }
                val density = LocalDensity.current
                val swipeContinue = remember(density) {
                    SwipeContinueConnection(
                        enabled = { canSwipeContinue.value },
                        thresholdPx = with(density) { SWIPE_CONTINUE_THRESHOLD_DP.dp.toPx() },
                        onTrigger = { viewModel.loadMore(force = true) },
                    )
                }
                val columns = gridColumns.takeIf { it >= 2 }
                    ?: adaptiveGridColumns(LocalConfiguration.current.screenWidthDp, viewModel.type)
                // 触底即加载下一页（v1.8.1.2）：首次搜索已后台预取 3 页，这里只兜住更深的滚动
                LaunchedEffect(gridState, hasMore) {
                    snapshotFlow { gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }
                        .distinctUntilChanged()
                        .collect { last ->
                            val total = gridState.layoutInfo.totalItemsCount
                            if (hasMore && total > 0 && last >= total - 3) viewModel.loadMore()
                        }
                }
                // v1.9.0.3：nestedScroll 必须挂在这里。v1.8.9.8 从 LazyVerticalGrid 上删掉
                // 之后说「只挂外层 Box」，但外层压根没挂过 —— 结果两边都没有，
                // onPostScroll 从来没被调用，上滑自然永远不响应。
                Box(Modifier.fillMaxSize().nestedScroll(swipeContinue)) {
                    // v1.9.0.1：关掉 overscroll。Android 12+ 的拉伸效果会把「列表已经到底、
                    // 用户还在往上推」的那段位移吃掉，nestedScroll 的 available 恒为 0，
                    // 上滑检测自然永远攒不满阈值。
                    androidx.compose.runtime.CompositionLocalProvider(
                        androidx.compose.foundation.LocalOverscrollConfiguration provides null,
                    ) {
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(columns),
                        // nestedScroll 只挂在**外层 Box** 上：两处都挂会收到两份
                        // onPostScroll，位移被重复累加，阈值形同虚设
                        modifier = Modifier.fillMaxSize(),
                        state = gridState,
                        contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        items(displayResults, key = { "${it.sourceId}:${it.id}" }) { item ->
                            TagPosterCard(
                                item = item,
                                indexed = "${item.sourceId}|${item.id}" in indexedKeys,
                                // 进详情前先放掉后台召回：它占着全应用共用的 JS 线程，
                                // 详情页的 info 会被排到 30 秒超时（v1.8.9.2）
                                onClick = {
                                    viewModel.abandonBackgroundWork()
                                    onItemClick(item)
                                },
                            )
                        }
                        // 页脚**始终**渲染（v1.8.7.8）：到底也得让用户看见为什么停了
                        item(
                            key = "load-more",
                            span = { GridItemSpan(maxLineSpan) },
                        ) {
                            LoadMoreFooter(
                                loading = loadingMore,
                                hasMore = hasMore,
                                emptyPage = emptyPage,
                                onLoadMore = { viewModel.loadMore() },
                            )
                        }
                    }
                    }
                    // 侧边滚动条（v1.8.2.0）：网格页翻到哪儿了看不出来
                    com.novelreader.ui.components.VerticalScrollbar(
                        gridState = gridState,
                        modifier = Modifier
                            .align(Alignment.CenterEnd)
                            .padding(end = 4.dp),
                    )
                }
            }

            else -> {
                val listState = rememberLazyListState()
                // 列表态同宫格态：到底之后上滑即翻页（v1.8.9.6）
                val canSwipeContinue = remember(listState) {
                    derivedStateOf {
                        results.isNotEmpty() && !loadingMore &&
                            listState.layoutInfo.totalItemsCount > 0 &&
                            !listState.canScrollForward
                    }
                }
                val density = LocalDensity.current
                val swipeContinue = remember(density) {
                    SwipeContinueConnection(
                        enabled = { canSwipeContinue.value },
                        thresholdPx = with(density) { SWIPE_CONTINUE_THRESHOLD_DP.dp.toPx() },
                        onTrigger = { viewModel.loadMore(force = true) },
                    )
                }
                LaunchedEffect(listState, hasMore) {
                    snapshotFlow { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }
                        .distinctUntilChanged()
                        .collect { last ->
                            val total = listState.layoutInfo.totalItemsCount
                            if (hasMore && total > 0 && last >= total - 3) viewModel.loadMore()
                        }
                }
                Box(Modifier.fillMaxSize().nestedScroll(swipeContinue)) {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        state = listState,
                        contentPadding = PaddingValues(vertical = 8.dp),
                    ) {
                        items(displayResults, key = { "${it.sourceId}:${it.id}" }) { item ->
                            val open = {
                                viewModel.abandonBackgroundWork()
                                onItemClick(item)
                            }
                            MediaItemCard(
                                item = item,
                                onClick = open,
                                onRead = open,
                            )
                        }
                        // 列表态同样**始终**渲染页脚（理由同宫格态）
                        item(key = "load-more") {
                            LoadMoreFooter(
                                loading = loadingMore,
                                hasMore = hasMore,
                                emptyPage = emptyPage,
                                onLoadMore = { viewModel.loadMore() },
                            )
                        }
                    }
                    com.novelreader.ui.components.VerticalScrollbar(
                        listState = listState,
                        modifier = Modifier
                            .align(Alignment.CenterEnd)
                            .padding(end = 2.dp),
                    )
                }
            }
        }
    }

    // ===== 「更多热门标签」底部面板（v1.6.2.0；v1.6.5.0 改为全部标签）=====
    if (showAllHotTags) {
        AllHotTagsSheet(
            plainTags = sheetPlainTags,
            groups = sheetGroups,
            activeTags = activeTags,
            tagCounts = tagCounts,
            editMode = editMode,
            onToggleEdit = { editMode = !editMode },
            onPick = { tag ->
                if (editMode) pendingDelete = tag
                else if (tag in activeTags) viewModel.removeTag(tag)
                else viewModel.addTag(tag)
            },
            onDismiss = { showAllHotTags = false },
        )
    }

    // ===== 删除标签确认（v1.9.2.0）=====
    // 删除是「从标签池摘掉 + 清索引收录 + 记入隐藏名单」三件事，不可撤销，
    // 所以必须确认一次，并把影响说清楚。
    pendingDelete?.let { target ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("删除标签「$target」？") },
            text = {
                Text(
                    "会从标签池里移除，并清掉本地索引里这个标签的收录记录；" +
                        "下次收集标签也不会再出现。作品本身不受影响。",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteTag(target)
                    pendingDelete = null
                }) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("取消") }
            },
        )
    }
}

@OptIn(
    androidx.compose.foundation.layout.ExperimentalLayoutApi::class,
    androidx.compose.material3.ExperimentalMaterial3Api::class,
)
@Composable
private fun AllHotTagsSheet(
    plainTags: List<String>,
    groups: List<TagSearchViewModel.TagGroupSection>,
    activeTags: List<String>,
    tagCounts: Map<String, Int>,
    editMode: Boolean = false,
    onToggleEdit: () -> Unit = {},
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    // v1.6.6.0：Voxel 源分类进来后漫画标签池有几百个，FlowRow 不是懒加载
    // （一次性 compose 全部子项），全量渲染会明显卡顿 → 两段各自分批展开。
    var visibleTags by remember(plainTags.size) { mutableStateOf(TAG_SHEET_PAGE) }
    var visibleGroups by remember(groups.size) { mutableStateOf(TAG_GROUP_PAGE) }
    val shownTags = if (plainTags.size <= visibleTags) plainTags else plainTags.take(visibleTags)
    val shownGroups = if (groups.size <= visibleGroups) groups else groups.take(visibleGroups)

    androidx.compose.material3.ModalBottomSheet(
        onDismissRequest = onDismiss,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            // 标题行：右侧「编辑 / 完成」—— 删除入口放在这个面板里（v1.9.2.0）
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "全部标签",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.weight(1f),
                )
                androidx.compose.material3.TextButton(onClick = onToggleEdit) {
                    Text(if (editMode) "完成" else "编辑")
                }
            }
            Text(
                text = if (editMode) {
                    "点任意标签即可删除（会清掉它的收录记录，下次收集也不会再出现）"
                } else {
                    "共 ${plainTags.size} 个标签 · ${groups.size} 个分组，按收录数倒序"
                },
                style = MaterialTheme.typography.bodySmall,
                color = if (editMode) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp, bottom = 12.dp),
            )

            // ===== 普通标签（不属于任何分组）=====
            if (shownTags.isNotEmpty()) {
                androidx.compose.foundation.layout.FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    shownTags.forEach { tag ->
                        HotTagChip(
                            tag = tag,
                            active = tag in activeTags,
                            count = tagCounts[tag] ?: 0,
                            deletable = editMode,
                            onClick = { onPick(tag) },
                        )
                    }
                }
                if (shownTags.size < plainTags.size) {
                    androidx.compose.material3.TextButton(
                        onClick = { visibleTags += TAG_SHEET_PAGE },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("显示更多标签（还剩 ${plainTags.size - shownTags.size} 个）")
                    }
                }
            }

            // ===== 组别系列（v1.9.2.0）=====
            // 每个分组一张卡片：组首词 + 整组入口 + 组内全部写法。
            // 以前这些写法跟普通标签摊在同一排，几百个词混在一起，看不出谁属于哪一组。
            if (groups.isNotEmpty()) {
                Text(
                    text = "组别系列",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(top = 16.dp, bottom = 2.dp),
                )
                Text(
                    text = "每个分组一张卡片；组内写法都能单独搜，点右上角「…组」搜整组",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
                shownGroups.forEach { section ->
                    GroupCard(
                        section = section,
                        activeTags = activeTags,
                        tagCounts = tagCounts,
                        editMode = editMode,
                        onPick = onPick,
                    )
                }
                if (shownGroups.size < groups.size) {
                    androidx.compose.material3.TextButton(
                        onClick = { visibleGroups += TAG_GROUP_PAGE },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("显示更多分组（还剩 ${groups.size - shownGroups.size} 个）")
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

/**
 * 一个分组卡片（v1.9.2.0）。
 *
 * 标题行 = 组首词 + 整组入口（「全彩组」），正文 = 组内全部写法。
 * 卡片用细边框 + 浅底，让「这一坨属于同一组」一眼看得出来。
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun GroupCard(
    section: TagSearchViewModel.TagGroupSection,
    activeTags: List<String>,
    tagCounts: Map<String, Int>,
    editMode: Boolean,
    onPick: (String) -> Unit,
) {
    val headCount = tagCounts[section.head] ?: 0
    androidx.compose.material3.Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 8.dp),
        shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
        border = androidx.compose.foundation.BorderStroke(
            width = 0.5.dp,
            color = MaterialTheme.colorScheme.outlineVariant,
        ),
    ) {
        Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = if (headCount > 0) "${section.head} · $headCount" else section.head,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.weight(1f),
                )
                HotTagChip(
                    tag = section.groupTag,
                    active = section.groupTag in activeTags,
                    count = 0,
                    deletable = editMode,
                    onClick = { onPick(section.groupTag) },
                )
            }
            androidx.compose.foundation.layout.FlowRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                section.members.forEach { member ->
                    HotTagChip(
                        tag = member,
                        active = member in activeTags,
                        count = tagCounts[member] ?: 0,
                        deletable = editMode,
                        onClick = { onPick(member) },
                    )
                }
            }
        }
    }
}

/**
 * 「已选标签」chip 的文案（v1.9.2.0）。
 *
 * 先给自己的收录数；如果它属于某个分组，再**额外**带上整组的收录数：
 * 「彩绘 · 12（组 2832）」。搜组内某个写法时，用户既想知道这个词自己收了多少，
 * 也想知道它所在的那一组一共多大 —— 两个数字一起给，不做替换。
 *
 * 自身计数与整组相同时（组首词、整组写法）不重复显示括号。
 */
private fun selectedChipLabel(tag: String, counts: Map<String, Int>): String {
    val own = counts[TagAlias.planTagOf(tag)] ?: 0
    val head = TagAlias.groupHeadOf(tag)
    val group = head?.let { counts[it] ?: 0 }
    return if (group != null && group != own) "$tag · $own（组 $group）" else "$tag · $own"
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
 * 「组别系列」每批展开多少个分组（v1.9.2.0）。
 *
 * 每张卡片里有整组写法（多则几十个），卡片本身不是懒加载，
 * 一次全铺开同样是几百个 chip，必须分批。
 */
private const val TAG_GROUP_PAGE = 12

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
    count: Int = 0,
    /** 编辑模式下给每个 chip 挂一个删除标记（v1.9.2.0） */
    deletable: Boolean = false,
    onClick: () -> Unit,
) {
    // 待办 #47：带上该标签的已收录数。为 0 时只显示标签本身，避免满屏的「· 0」。
    val label = if (count > 0) "$tag · $count" else tag
    if (active) {
        AssistChip(
            onClick = onClick,
            label = {
                Text(
                    label,
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
        // v1.9.2.0：编辑模式下换成 AssistChip —— SuggestionChip 不支持 trailingIcon，
        // 需要一个「×」告诉用户这个 chip 现在点一下是删除
        if (deletable) {
            AssistChip(
                onClick = onClick,
                label = {
                    Text(
                        label,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                colors = AssistChipDefaults.assistChipColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                    labelColor = MaterialTheme.colorScheme.onSurfaceVariant,
                ),
                trailingIcon = {
                    Icon(
                        Icons.Filled.Close,
                        contentDescription = "删除 $tag",
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(14.dp),
                    )
                },
            )
        } else {
            SuggestionChip(
                onClick = onClick,
                label = {
                    Text(
                        label,
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

/**
 * 输入补全面板（v1.8.7.1）。
 *
 * 刻意**不用** `DropdownMenu`：它的 Popup 默认 focusable，一展开就抢走输入焦点，
 * 软键盘当场收起 —— 用户打半个词就得重新点输入框，这是最影响手感的一处。
 * 内联在输入框下方则完全不碰焦点，键盘与光标都留在原地。
 *
 * 交互口径也一并改成「补全即止」：点一项只把正在输入的那一段换成完整标签
 * 并在末尾留一个空格，**不提交搜索**，想接着输第二个标签可以直接打字。
 * 候选里把用户已输入的片段加粗高亮，避免「为什么推荐这个词」看不出来。
 *
 * 面板带一个收起按钮：用户看清候选后不想选，可以主动关掉，不必靠继续打字
 * 把列表顶走。
 */
@Composable
private fun TagSuggestionPanel(
    suggestions: List<String>,
    word: String,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 2.dp),
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
    ) {
        Column(modifier = Modifier.padding(vertical = 2.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 12.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "补全建议 · 点选填入，可接着输入",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onDismiss, modifier = Modifier.size(30.dp)) {
                    Icon(
                        Icons.Filled.Close,
                        contentDescription = "收起补全建议",
                        modifier = Modifier.size(14.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            LazyColumn(modifier = Modifier.heightIn(max = 200.dp)) {
                items(suggestions, key = { it }) { tag ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onPick(tag) }
                            .padding(horizontal = 12.dp, vertical = 9.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = highlightMatch(tag, word),
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            text = "填入",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }
        }
    }
}

/** 把候选里用户已输入的那一段标成主题色加粗（找不到就原样返回） */
@Composable
private fun highlightMatch(tag: String, word: String): AnnotatedString {
    if (word.isEmpty()) return AnnotatedString(tag)
    val start = tag.indexOf(word, ignoreCase = true)
    if (start < 0) return AnnotatedString(tag)
    val accent = MaterialTheme.colorScheme.primary
    return buildAnnotatedString {
        append(tag)
        addStyle(
            SpanStyle(fontWeight = FontWeight.SemiBold, color = accent),
            start,
            start + word.length,
        )
    }
}

/** 标签搜索结果的海报卡（视频）：2:3 封面 + 标题 + 来源名 */
@Composable
private fun TagPosterCard(
    item: MediaItem,
    indexed: Boolean = false,
    onClick: () -> Unit,
) {
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
            // v1.8.7.5：不再用 SubcomposeAsyncImage（见 LazyCover 注释）——
            // 两层 SubcomposeLayout 叠在懒加载列表里，快速拖动滚动条时会崩在
            // 「measure is called on a deactivated node」。
            LazyCover(
                model = cover,
                contentDescription = item.title,
                modifier = Modifier.fillMaxSize(),
                fallback = { CoverPlaceholderLetter(Modifier.fillMaxSize(), item.title) },
            )
            // 待办 #47：本地索引已收录的标一个角标 —— 用户一眼能看出哪些是「搜过、存过」的
            if (indexed) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(5.dp)
                        .background(
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.92f),
                            RoundedCornerShape(4.dp),
                        )
                        .padding(horizontal = 5.dp, vertical = 2.dp),
                ) {
                    Text(
                        "已收录",
                        fontSize = 9.sp,
                        color = MaterialTheme.colorScheme.onPrimary,
                        maxLines = 1,
                    )
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
private fun LoadMoreFooter(
    loading: Boolean,
    hasMore: Boolean,
    emptyPage: Int = 0,
    onLoadMore: () -> Unit,
) {
    // v1.8.8.0（待办 #35）：页脚就是「触底哨兵」。
    //
    // 以前只靠上面那个 snapshotFlow 监听「最后一个可见项的下标变化」触发翻页 ——
    // 一旦某一页没有新增条目（视频源上架同一批作品时很常见），下标不变、流不再发射，
    // 用户在底部怎么滑都没反应，就是「到底部了刷不出东西」。
    // 现在只要页脚被组合出来（= 它出现在屏幕里）且还有下一页，就自动接着问；
    // 加载完若仍停在底部，loading 变回 false 会再次触发，一页页自动补下去，
    // 直到到底（hasMore=false）或用户把页脚滑出屏幕（页脚被回收，effect 结束）。
    LaunchedEffect(loading, hasMore, emptyPage) {
        if (!loading && hasMore) onLoadMore()
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        when {
            // 翻页要再把上一页有货的源问一遍，几秒到几十秒都有可能：
            // 只放一个小转圈，用户根本分不清「在加载」和「没反应」
            loading -> Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
                // v1.8.7.6：这里的 x/y 去掉了 —— 进度已改成整次搜索的总进度并在顶部
                // 常驻显示，页脚再挂一个同形的分数，只会让人以为「这一页又卡在某个数」
                Text(
                    text = "正在加载…",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            hasMore -> TextButton(onClick = onLoadMore) { Text("加载更多") }
            // v1.8.9.6：到底之后**不再挂按钮**，改成「继续上滑」的手势。
            // 以前这里是一个「继续往下翻」的 TextButton：既要多点一下，又常常被
            // 当成一句说明文字（用户反馈「这个按键去掉，改成上滑就翻」）。
            // 手势的实现在 [SwipeContinueConnection]，这里只留一行提示告诉用户怎么用。
            else -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = if (emptyPage > 0) {
                        // v1.8.8.0：说清到底的原因 —— 不是「没加载」，是源站把同一批内容又返回了一遍
                        "第 $emptyPage 页返回的还是同一批内容（源站没有更多了）"
                    } else {
                        "没有更多了"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "继续上滑可再试一次",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                )
            }
        }
    }
}

/**
 * 「到底之后继续上滑 = 再往前问一页」（v1.8.9.6）。
 *
 * 只认「列表已经推不动、但用户还在往上推」的那部分滚动量：
 * 没到底时列表会把滚动全部消费掉，`available.y` 恒为 0，这里自然不会触发。
 * 用**累积位移 + 冷却时间**兜住「一次猛滑连开好几页」，往回拉则清零，
 * 避免用户来回蹭把阈值凑满。
 */
private class SwipeContinueConnection(
    private val enabled: () -> Boolean,
    private val thresholdPx: Float,
    private val onTrigger: () -> Unit,
) : NestedScrollConnection {
    private var accumulated = 0f
    private var lastTriggerAt = 0L
    private var lastLogAt = 0L

    override fun onPostScroll(
        consumed: Offset,
        available: Offset,
        source: NestedScrollSource,
    ): Offset {
        // 诊断（强制记录，不受开关影响）：区分「onPostScroll 没被调用」和
        // 「调用了但 available 恒为 0」—— 这两种情况表现一样，靠开关日志分不出来。
        val nowMs = System.currentTimeMillis()
        if (nowMs - lastLogAt >= 120) {
            lastLogAt = nowMs
            com.novelreader.core.util.DebugLog.forceD(
                "SwipeContinue",
                "avail=${available.y.toInt()} consumed=${consumed.y.toInt()} " +
                    "acc=${accumulated.toInt()} on=${enabled()}",
            )
        }
        // 不满足条件时**不清零**：加载中那几百毫秒里用户往往还在继续滑，
        // 清零会让他觉得「白滑了」。真正的复位交给下面的「往回拉」分支。
        if (!enabled()) return Offset.Zero
        if (available.y < 0f) {
            // y < 0 = 内容继续往上走（用户想看更下面的东西），但列表已经到底了
            accumulated += -available.y
            val now = System.currentTimeMillis()
            if (accumulated >= thresholdPx && now - lastTriggerAt >= SWIPE_CONTINUE_COOLDOWN_MS) {
                accumulated = 0f
                lastTriggerAt = now
                onTrigger()
            }
        } else if (available.y > 0f) {
            accumulated = 0f
        }
        return Offset.Zero
    }
}

/** 上滑触发翻页需要累积的位移（dp）：太小会误触，太大要滑很久 */
private const val SWIPE_CONTINUE_THRESHOLD_DP = 56f

/** 两次上滑翻页之间的冷却，防止一次甩动连续开好几页 */
private const val SWIPE_CONTINUE_COOLDOWN_MS = 700L
