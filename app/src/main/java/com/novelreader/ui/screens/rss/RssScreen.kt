package com.novelreader.ui.screens.rss

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Circle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Deselect
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.DriveFileMove
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.RssFeed
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import coil.compose.AsyncImagePainter
import coil.compose.SubcomposeAsyncImage
import coil.compose.SubcomposeAsyncImageContent
import com.novelreader.core.rss.RssMessages
import com.novelreader.core.rss.RssParser
import com.novelreader.domain.model.RssArticle
import com.novelreader.domain.model.RssFeed
import com.novelreader.domain.model.RssFeedResult
import com.novelreader.domain.repository.RssRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** 文章筛选维度 */
enum class RssFilter(val label: String) {
    ALL("全部"),
    UNREAD("未读"),
    STARRED("收藏"),
}

/** 「未分组」伪分组：分组筛选里 null 表示全部，空串表示未分组 */
const val UNGROUPED = ""

/** 批量删除的待确认信息：删几个源、连带删几篇文章 */
data class DeleteConfirm(
    val feedIds: List<Long>,
    val feedCount: Int,
    val articleCount: Int,
)

/**
 * 订阅页 ViewModel（v1.3.1.0）。
 *
 * 数据全部来自 Room Flow，界面无需手动刷新：
 * 抓取入库后列表自动更新；筛选（全部 / 未读 / 收藏）在内存中完成，不额外查库。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RssViewModel(
    private val repository: RssRepository,
) : ViewModel() {

    /** 当前选中的订阅源（null = 全部） */
    private val selectedFeedId = MutableStateFlow<Long?>(null)
    private val filter = MutableStateFlow(RssFilter.ALL)

    private val _refreshing = MutableStateFlow(false)
    val refreshing: StateFlow<Boolean> = _refreshing.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    val feeds: StateFlow<List<RssFeed>> = repository.observeFeeds()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val unreadTotal: StateFlow<Int> = repository.observeUnreadTotal()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    val selected: StateFlow<Long?> = selectedFeedId.asStateFlow()

    val currentFilter: StateFlow<RssFilter> = filter.asStateFlow()

    // ==================== 分组 / 批量管理（v1.8.3.0 待办 #16） ====================

    /** 分组筛选：null = 全部，[UNGROUPED] = 未分组，其余为具体分组名 */
    private val groupFilter = MutableStateFlow<String?>(null)
    val currentGroup: StateFlow<String?> = groupFilter.asStateFlow()

    /**
     * 现有分组名。直接从 [feeds] 派生，不额外查库——
     * 源改分组后 chip 会自动增删，不需要手动触发刷新。
     */
    val groupNames: StateFlow<List<String>> = feeds
        .map { list -> list.map { it.groupName }.filter { it.isNotBlank() }.distinct().sorted() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** 按当前分组筛选后的源（顶部 chips 与批量管理列表都用这份） */
    val visibleFeeds: StateFlow<List<RssFeed>> = combine(feeds, groupFilter) { list, group ->
        when (group) {
            null -> list
            UNGROUPED -> list.filter { it.groupName.isBlank() }
            else -> list.filter { it.groupName == group }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _manageMode = MutableStateFlow(false)
    val manageMode: StateFlow<Boolean> = _manageMode.asStateFlow()

    private val _selectedFeeds = MutableStateFlow<Set<Long>>(emptySet())
    val selectedFeeds: StateFlow<Set<Long>> = _selectedFeeds.asStateFlow()

    /** 管理列表里被折叠的分组（空串代表「未分组」组） */
    private val _collapsedGroups = MutableStateFlow<Set<String>>(emptySet())
    val collapsedGroups: StateFlow<Set<String>> = _collapsedGroups.asStateFlow()

    /** 待二次确认的批量删除（含将一并清掉的文章数） */
    private val _deleteConfirm = MutableStateFlow<DeleteConfirm?>(null)
    val deleteConfirm: StateFlow<DeleteConfirm?> = _deleteConfirm.asStateFlow()

    fun selectGroup(group: String?) {
        groupFilter.value = group
        // 切分组时把选中项清掉：否则会误删到看不见的源
        _selectedFeeds.value = emptySet()
        val current = selectedFeedId.value
        if (current != null && visibleFeeds.value.none { it.feedId == current }) {
            selectedFeedId.value = null
        }
    }

    fun enterManage() {
        _manageMode.value = true
        _selectedFeeds.value = emptySet()
    }

    fun exitManage() {
        _manageMode.value = false
        _selectedFeeds.value = emptySet()
    }

    fun toggleSelect(feedId: Long) {
        val cur = _selectedFeeds.value
        _selectedFeeds.value = if (feedId in cur) cur - feedId else cur + feedId
    }

    fun selectAll() {
        _selectedFeeds.value = visibleFeeds.value.map { it.feedId }.toSet()
    }

    fun invertSelection() {
        val all = visibleFeeds.value.map { it.feedId }.toSet()
        _selectedFeeds.value = all - _selectedFeeds.value
    }

    fun toggleGroupCollapsed(group: String) {
        val cur = _collapsedGroups.value
        _collapsedGroups.value = if (group in cur) cur - group else cur + group
    }

    /** 选中项移动到指定分组（空串 = 未分组） */
    fun moveSelectedTo(group: String) {
        val ids = _selectedFeeds.value.toList()
        if (ids.isEmpty()) return
        viewModelScope.launch {
            repository.setFeedGroupBatch(ids, group)
            _message.value = if (group.isBlank()) "已移出分组（${ids.size} 个源）"
            else "已移动到「$group」（${ids.size} 个源）"
            _selectedFeeds.value = emptySet()
        }
    }

    /** 重命名分组：把该组下所有源改到新名字 */
    fun renameGroup(old: String, new: String) {
        val target = new.trim()
        if (target.isEmpty() || target == old) return
        viewModelScope.launch {
            val ids = feeds.value.filter { it.groupName == old }.map { it.feedId }
            repository.setFeedGroupBatch(ids, target)
            if (groupFilter.value == old) groupFilter.value = target
            _message.value = "分组已重命名为「$target」"
        }
    }

    fun setFeedGroup(feedId: Long, group: String) {
        viewModelScope.launch {
            repository.setFeedGroup(feedId, group)
            _message.value = if (group.isBlank()) "已移出分组" else "已移动到「$group」"
        }
    }

    /** 请求删除：先查会连带删掉多少文章，再弹二次确认 */
    fun requestDelete(feedIds: List<Long>) {
        if (feedIds.isEmpty()) return
        viewModelScope.launch {
            val articles = runCatching { repository.countArticlesOfFeeds(feedIds) }.getOrDefault(0)
            _deleteConfirm.value = DeleteConfirm(feedIds, feedIds.size, articles)
        }
    }

    fun cancelDelete() {
        _deleteConfirm.value = null
    }

    fun confirmDelete() {
        val pending = _deleteConfirm.value ?: return
        _deleteConfirm.value = null
        viewModelScope.launch {
            repository.deleteFeeds(pending.feedIds)
            if (selectedFeedId.value in pending.feedIds) selectedFeedId.value = null
            _selectedFeeds.value = emptySet()
            _message.value = "已删除 ${pending.feedIds.size} 个订阅"
        }
    }

    val articles: StateFlow<List<RssArticle>> =
        combine(selectedFeedId.flatMapLatest { repository.observeArticles(it) }, filter) { list, f ->
            when (f) {
                RssFilter.ALL -> list
                RssFilter.UNREAD -> list.filter { !it.read }
                RssFilter.STARRED -> list.filter { it.starred }
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** 是否已做过「进入自动刷新」判断（避免每次重组都触发） */
    private var autoRefreshed = false

    fun selectFeed(feedId: Long?) {
        selectedFeedId.value = feedId
    }

    fun setFilter(value: RssFilter) {
        filter.value = value
    }

    fun dismissMessage() {
        _message.value = null
    }

    /**
     * 进入页面时的自动刷新：只要有源超过 [STALE_MILLIS] 没抓过就刷一遍。
     * 全新订阅（刚添加过）不会重复抓取。
     */
    fun refreshIfStale() {
        if (autoRefreshed) return
        autoRefreshed = true
        viewModelScope.launch {
            val stale = feeds.value
                .filter { System.currentTimeMillis() - it.lastFetchAt > STALE_MILLIS }
            if (stale.isEmpty()) return@launch
            _refreshing.value = true
            runCatching { repository.refreshAll() }
            _refreshing.value = false
        }
    }

    /** 添加订阅：支持一行一个地址、多个地址混排，以及直接粘贴 OPML */
    fun addSubscription(input: String) {
        val text = input.trim()
        if (text.isEmpty()) return
        viewModelScope.launch {
            _refreshing.value = true
            _message.value = "正在抓取订阅…"
            val results = runCatching {
                if (RssParser.isOpml(text)) {
                    repository.addOpml(text)
                } else {
                    val urls = text.split(Regex("[\\s,，;；]+"))
                        .map { it.trim() }
                        .filter { it.startsWith("http://", true) || it.startsWith("https://", true) }
                    if (urls.isEmpty()) emptyList() else repository.addFeeds(urls)
                }
            }.getOrElse { listOf(RssFeedResult(0, "", "", error = it.message ?: "导入失败")) }
            _refreshing.value = false
            _message.value = RssMessages.summarize(results)
        }
    }

    fun refreshAll() {
        if (_refreshing.value) return
        viewModelScope.launch {
            _refreshing.value = true
            val results = runCatching { repository.refreshAll() }.getOrDefault(emptyList())
            _refreshing.value = false
            _message.value = RssMessages.refreshAll(results)
        }
    }

    fun refreshFeed(feedId: Long) {
        viewModelScope.launch {
            _refreshing.value = true
            val result = runCatching { repository.refreshFeed(feedId) }.getOrNull()
            _refreshing.value = false
            _message.value = when {
                result == null -> "刷新失败"
                else -> RssMessages.refreshOne(result)
            }
        }
    }

    fun renameFeed(feedId: Long, title: String) {
        viewModelScope.launch {
            repository.renameFeed(feedId, title)
            _message.value = "已重命名"
        }
    }

    fun deleteFeed(feedId: Long, title: String) {
        viewModelScope.launch {
            repository.deleteFeed(feedId)
            if (selectedFeedId.value == feedId) selectedFeedId.value = null
            _message.value = "已删除订阅《$title》"
        }
    }

    /** 全部标记已读：当前选中了源就只标该源 */
    fun markAllRead() {
        viewModelScope.launch {
            val feedId = selectedFeedId.value
            repository.markAllRead(feedId)
            _message.value = if (feedId == null) "已全部标记为已读" else "该源已标记为已读"
        }
    }

    /** 指定源全部标记已读（长按 chip 管理时用，与当前选中项无关） */
    fun markFeedRead(feedId: Long, title: String) {
        viewModelScope.launch {
            repository.markAllRead(feedId)
            _message.value = "《$title》已标记为已读"
        }
    }

    fun toggleRead(article: RssArticle) {
        viewModelScope.launch { repository.markRead(article.articleId, !article.read) }
    }

    fun toggleStar(article: RssArticle) {
        viewModelScope.launch { repository.toggleStar(article.articleId) }
    }

    companion object {
        /** 超过 30 分钟未抓取即视为过期（进入页面时自动刷新） */
        private const val STALE_MILLIS = 30 * 60 * 1000L
    }
}

/**
 * 订阅页（v1.3.1.0）。
 *
 * 顶部：返回 + 订阅管理（添加 / 刷新 / 全部已读）
 * 中部：源筛选 chips（长按管理该源）+ 状态筛选 chips（全部 / 未读 / 收藏）
 * 列表：文章卡片（缩略图 + 标题 + 来源时间 + 摘要 + 未读点 + 收藏星）
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RssScreen(
    viewModel: RssViewModel,
    onOpenArticle: (Long) -> Unit,
    onBack: () -> Unit,
) {
    val feeds by viewModel.feeds.collectAsState()
    val articles by viewModel.articles.collectAsState()
    val unreadTotal by viewModel.unreadTotal.collectAsState()
    val selected by viewModel.selected.collectAsState()
    val filter by viewModel.currentFilter.collectAsState()
    val refreshing by viewModel.refreshing.collectAsState()
    val message by viewModel.message.collectAsState()
    // 分组 / 批量管理（#16）
    val currentGroup by viewModel.currentGroup.collectAsState()
    val groupNames by viewModel.groupNames.collectAsState()
    val visibleFeeds by viewModel.visibleFeeds.collectAsState()
    val manageMode by viewModel.manageMode.collectAsState()
    val selectedFeeds by viewModel.selectedFeeds.collectAsState()
    val collapsedGroups by viewModel.collapsedGroups.collectAsState()
    val deleteConfirm by viewModel.deleteConfirm.collectAsState()

    val context = androidx.compose.ui.platform.LocalContext.current
    LaunchedEffect(message) {
        message?.let {
            android.widget.Toast.makeText(context, it, android.widget.Toast.LENGTH_LONG).show()
            viewModel.dismissMessage()
        }
    }
    LaunchedEffect(Unit) { viewModel.refreshIfStale() }

    var showAddDialog by rememberSaveable { mutableStateOf(false) }
    var manageFeed by remember { mutableStateOf<RssFeed?>(null) }
    var actionArticle by remember { mutableStateOf<RssArticle?>(null) }
    /** 正在重命名的分组（null = 没打开） */
    var renameGroupTarget by remember { mutableStateOf<String?>(null) }
    /** 打开中的「移动到分组」选择器 */
    var moveToGroupPicker by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    if (manageMode) {
                        Text(
                            if (selectedFeeds.isEmpty()) "管理订阅"
                            else "已选 ${selectedFeeds.size} / ${visibleFeeds.size}",
                        )
                    } else {
                        Text("订阅")
                    }
                },
                navigationIcon = {
                    IconButton(onClick = { if (manageMode) viewModel.exitManage() else onBack() }) {
                        Icon(
                            imageVector = if (manageMode) Icons.Filled.Close
                            else Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = if (manageMode) "退出管理" else "返回",
                        )
                    }
                },
                actions = {
                    if (manageMode) {
                        // 管理态：只留选择相关操作，避免误触刷新/全部已读
                        IconButton(onClick = {
                            if (selectedFeeds.size == visibleFeeds.size) viewModel.invertSelection()
                            else viewModel.selectAll()
                        }) {
                            Icon(
                                imageVector = if (selectedFeeds.size == visibleFeeds.size &&
                                    visibleFeeds.isNotEmpty()
                                ) Icons.Filled.Deselect else Icons.Filled.SelectAll,
                                contentDescription = if (selectedFeeds.size == visibleFeeds.size) "反选" else "全选",
                            )
                        }
                    } else {
                        if (refreshing) {
                            CircularProgressIndicator(
                                modifier = Modifier
                                    .size(22.dp)
                                    .padding(end = 4.dp),
                                strokeWidth = 2.dp,
                            )
                        } else {
                            IconButton(onClick = { viewModel.refreshAll() }) {
                                Icon(Icons.Filled.Refresh, contentDescription = "刷新全部")
                            }
                        }
                        IconButton(onClick = { viewModel.markAllRead() }) {
                            Icon(Icons.Filled.DoneAll, contentDescription = "全部已读")
                        }
                        IconButton(onClick = { showAddDialog = true }) {
                            Icon(Icons.Filled.Add, contentDescription = "添加订阅")
                        }
                        if (feeds.isNotEmpty()) {
                            IconButton(onClick = { viewModel.enterManage() }) {
                                Icon(Icons.Filled.Checklist, contentDescription = "管理订阅")
                            }
                        }
                    }
                },
            )
        },
        bottomBar = {
            // 管理态底部浮条：移动分组 + 删除，选中 0 项时置灰
            if (manageMode && visibleFeeds.isNotEmpty()) {
                ManageBottomBar(
                    selectedCount = selectedFeeds.size,
                    onMoveToGroup = { moveToGroupPicker = true },
                    onDelete = { viewModel.requestDelete(selectedFeeds.toList()) },
                )
            }
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            // 管理态不显示源 chips / 文章筛选项：那两行是给「挑源读文章」用的，
            // 管理列表本身按分组分节，留着只会把可用高度吃掉
            if (feeds.isNotEmpty() && !manageMode) {
                LazyRow(
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    item {
                        RssChip(
                            label = if (unreadTotal > 0) "全部 $unreadTotal" else "全部",
                            selected = selected == null,
                            onClick = { viewModel.selectFeed(null) },
                        )
                    }
                    items(visibleFeeds, key = { it.feedId }) { feed ->
                        RssChip(
                            label = if (feed.unreadCount > 0) "${feed.title} ${feed.unreadCount}" else feed.title,
                            selected = selected == feed.feedId,
                            onLongClick = { manageFeed = feed },
                            onClick = { viewModel.selectFeed(feed.feedId) },
                        )
                    }
                }

                // 分组筛选行（#16）：有分组才显示，避免只有一个「全部」时白占一行
                if (groupNames.isNotEmpty()) {
                    LazyRow(
                        modifier = Modifier.fillMaxWidth(),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 2.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        item {
                            RssChip(
                                label = "全部分组",
                                selected = currentGroup == null,
                                onClick = { viewModel.selectGroup(null) },
                            )
                        }
                        item {
                            RssChip(
                                label = "未分组",
                                selected = currentGroup == UNGROUPED,
                                onClick = { viewModel.selectGroup(UNGROUPED) },
                            )
                        }
                        items(groupNames, key = { "g_$it" }) { group ->
                            RssChip(
                                label = group,
                                selected = currentGroup == group,
                                onLongClick = { renameGroupTarget = group },
                                onClick = { viewModel.selectGroup(group) },
                            )
                        }
                    }
                }

                LazyRow(
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 2.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(RssFilter.values().toList()) { f ->
                        RssChip(
                            label = f.label,
                            selected = filter == f,
                            onClick = { viewModel.setFilter(f) },
                        )
                    }
                }

                HorizontalDivider(
                    modifier = Modifier.padding(top = 6.dp),
                    thickness = 0.5.dp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f),
                )
            }

            when {
                // 管理态：主区域换成「按分组折叠的源列表」，多选与批量操作都在这里
                manageMode -> FeedManageList(
                    feeds = visibleFeeds,
                    selected = selectedFeeds,
                    collapsed = collapsedGroups,
                    onToggleFeed = { viewModel.toggleSelect(it) },
                    onToggleGroup = { viewModel.toggleGroupCollapsed(it) },
                    onLongClickFeed = { manageFeed = it },
                )

                feeds.isEmpty() -> EmptyState(
                    title = "还没有订阅",
                    subtitle = "点右上角「＋」添加订阅地址\n支持 RSS / Atom 源，也可直接粘贴 OPML",
                    onAction = { showAddDialog = true },
                )

                articles.isEmpty() -> EmptyState(
                    title = if (filter == RssFilter.UNREAD) "没有未读文章" else "还没有文章",
                    subtitle = if (filter == RssFilter.UNREAD) "都读完了，换个筛选看看"
                    else "点右上角刷新按钮抓取最新内容",
                    onAction = null,
                )

                else -> LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = 16.dp),
                ) {
                    items(articles, key = { it.articleId }) { article ->
                        ArticleRow(
                            article = article,
                            onClick = { onOpenArticle(article.articleId) },
                            onLongClick = { actionArticle = article },
                        )
                    }
                }
            }
        }
    }

    // 添加订阅
    if (showAddDialog) {
        var input by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showAddDialog = false },
            title = { Text("添加订阅") },
            text = {
                Column {
                    Text(
                        text = "一行一个订阅地址；直接粘贴博客首页会自动发现订阅源；粘贴 OPML 内容可批量导入。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = input,
                        onValueChange = { input = it },
                        label = { Text("订阅地址 / OPML") },
                        minLines = 3,
                        maxLines = 8,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = input.isNotBlank(),
                    onClick = {
                        viewModel.addSubscription(input)
                        showAddDialog = false
                    },
                ) { Text("订阅") }
            },
            dismissButton = {
                TextButton(onClick = { showAddDialog = false }) { Text("取消") }
            },
        )
    }

    // 订阅源管理（长按 chip）
    manageFeed?.let { feed ->
        var nameInput by remember(feed.feedId) { mutableStateOf(feed.title) }
        var groupInput by remember(feed.feedId) { mutableStateOf(feed.groupName) }
        AlertDialog(
            onDismissRequest = { manageFeed = null },
            title = { Text("管理订阅") },
            text = {
                Column {
                    Text(
                        text = RssFormat.fetchTime(feed.lastFetchAt) +
                            if (feed.lastError.isNotBlank()) " · 上次失败：${feed.lastError}" else "",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = nameInput,
                        onValueChange = { nameInput = it },
                        label = { Text("订阅名称") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = groupInput,
                        onValueChange = { groupInput = it },
                        label = { Text("分组（留空 = 未分组）") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (groupNames.isNotEmpty()) {
                        // 已有分组一键填入，省得手打错字又变成一个新分组
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            items(groupNames) { g ->
                                RssChip(
                                    label = g,
                                    selected = groupInput == g,
                                    onClick = { groupInput = g },
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    TextButton(onClick = {
                        viewModel.refreshFeed(feed.feedId)
                        manageFeed = null
                    }) { Text("立即刷新") }
                    TextButton(onClick = { viewModel.markFeedRead(feed.feedId, feed.title) }) {
                        Text("该源全部已读")
                    }
                    TextButton(onClick = {
                        // 单源删除也走二次确认，避免误触把整源文章清空
                        viewModel.requestDelete(listOf(feed.feedId))
                        manageFeed = null
                    }) { Text("删除订阅", color = MaterialTheme.colorScheme.error) }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = nameInput.isNotBlank() &&
                        (nameInput.trim() != feed.title || groupInput.trim() != feed.groupName),
                    onClick = {
                        if (nameInput.trim() != feed.title) viewModel.renameFeed(feed.feedId, nameInput)
                        if (groupInput.trim() != feed.groupName) {
                            viewModel.setFeedGroup(feed.feedId, groupInput.trim())
                        }
                        manageFeed = null
                    },
                ) { Text("保存") }
            },
            dismissButton = {
                TextButton(onClick = { manageFeed = null }) { Text("取消") }
            },
        )
    }

    // 文章长按操作
    actionArticle?.let { article ->
        AlertDialog(
            onDismissRequest = { actionArticle = null },
            title = { Text(article.title, maxLines = 2, overflow = TextOverflow.Ellipsis) },
            text = {
                Column {
                    TextButton(onClick = {
                        viewModel.toggleRead(article)
                        actionArticle = null
                    }) { Text(if (article.read) "标记为未读" else "标记为已读") }
                    TextButton(onClick = {
                        viewModel.toggleStar(article)
                        actionArticle = null
                    }) { Text(if (article.starred) "取消收藏" else "收藏") }
                    if (article.link.isNotBlank()) {
                        TextButton(onClick = {
                            openInBrowser(context, article.link)
                            actionArticle = null
                        }) { Text("浏览器打开原文") }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { actionArticle = null }) { Text("关闭") }
            },
        )
    }

    // 重命名分组（长按分组 chip）
    renameGroupTarget?.let { group ->
        var nameInput by remember(group) { mutableStateOf(group) }
        AlertDialog(
            onDismissRequest = { renameGroupTarget = null },
            title = { Text("重命名分组") },
            text = {
                Column {
                    Text(
                        text = "该分组下的 ${feeds.count { it.groupName == group }} 个订阅会一起改到新名字。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = nameInput,
                        onValueChange = { nameInput = it },
                        label = { Text("分组名") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = nameInput.isNotBlank() && nameInput.trim() != group,
                    onClick = {
                        viewModel.renameGroup(group, nameInput)
                        renameGroupTarget = null
                    },
                ) { Text("保存") }
            },
            dismissButton = {
                TextButton(onClick = { renameGroupTarget = null }) { Text("取消") }
            },
        )
    }

    // 批量移动到分组（管理态底部浮条触发）
    if (moveToGroupPicker) {
        var newGroupInput by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { moveToGroupPicker = false },
            title = { Text("移动到分组（${selectedFeeds.size} 个源）") },
            text = {
                Column {
                    // 已有分组：点一下直接移过去
                    groupNames.forEach { group ->
                        TextButton(
                            onClick = {
                                viewModel.moveSelectedTo(group)
                                moveToGroupPicker = false
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text(group, modifier = Modifier.fillMaxWidth()) }
                    }
                    TextButton(
                        onClick = {
                            viewModel.moveSelectedTo(UNGROUPED)
                            moveToGroupPicker = false
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("移出分组（未分组）", modifier = Modifier.fillMaxWidth()) }
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = newGroupInput,
                        onValueChange = { newGroupInput = it },
                        label = { Text("或新建分组") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = newGroupInput.isNotBlank(),
                    onClick = {
                        viewModel.moveSelectedTo(newGroupInput.trim())
                        moveToGroupPicker = false
                    },
                ) { Text("新建并移动") }
            },
            dismissButton = {
                TextButton(onClick = { moveToGroupPicker = false }) { Text("取消") }
            },
        )
    }

    // 批量删除二次确认：明确告知会连带清掉多少文章
    deleteConfirm?.let { pending ->
        AlertDialog(
            onDismissRequest = { viewModel.cancelDelete() },
            title = { Text("删除 ${pending.feedCount} 个订阅？") },
            text = {
                Text(
                    text = "会同时删除这些订阅下的 ${pending.articleCount} 篇文章，删除后无法恢复。",
                    style = MaterialTheme.typography.bodyMedium,
                )
            },
            confirmButton = {
                TextButton(onClick = { viewModel.confirmDelete() }) {
                    Text("删除", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.cancelDelete() }) { Text("取消") }
            },
        )
    }
}

/**
 * 管理态的源列表：按分组分节，分节标题可点全选本组、点箭头折叠（#16）。
 * 未分组永远排在最后，避免一堆散源把有组织的分组挤下去。
 */
@Composable
private fun FeedManageList(
    feeds: List<RssFeed>,
    selected: Set<Long>,
    collapsed: Set<String>,
    onToggleFeed: (Long) -> Unit,
    onToggleGroup: (String) -> Unit,
    onLongClickFeed: (RssFeed) -> Unit,
) {
    val grouped = remember(feeds) {
        feeds.groupBy { it.groupName }
            .toList()
            .sortedWith(compareBy({ it.first.isEmpty() }, { it.first }))
    }

    if (feeds.isEmpty()) {
        EmptyState(title = "该分组下没有订阅", subtitle = "换个分组看看，或点右上角添加", onAction = null)
        return
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 16.dp),
    ) {
        grouped.forEach { (group, members) ->
            val isCollapsed = group in collapsed
            item(key = "head_$group") {
                GroupSectionHeader(
                    title = if (group.isEmpty()) "未分组" else group,
                    count = members.size,
                    collapsed = isCollapsed,
                    onToggleCollapse = { onToggleGroup(group) },
                )
            }
            if (!isCollapsed) {
                items(members, key = { it.feedId }) { feed ->
                    FeedManageRow(
                        feed = feed,
                        selected = feed.feedId in selected,
                        onToggle = { onToggleFeed(feed.feedId) },
                        onLongClick = { onLongClickFeed(feed) },
                    )
                }
            }
        }
    }
}

/** 分组分节标题 */
@Composable
private fun GroupSectionHeader(
    title: String,
    count: Int,
    collapsed: Boolean,
    onToggleCollapse: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
            .clickable(onClick = onToggleCollapse)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = if (collapsed) Icons.Filled.ChevronRight else Icons.Filled.ExpandMore,
            contentDescription = if (collapsed) "展开" else "折叠",
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = "$count",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** 管理列表里的一行订阅 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FeedManageRow(
    feed: RssFeed,
    selected: Boolean,
    onToggle: () -> Unit,
    onLongClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onToggle, onLongClick = onLongClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = selected, onCheckedChange = { onToggle() })
        Spacer(Modifier.width(4.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = feed.title.ifBlank { feed.url },
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = buildString {
                    if (feed.unreadCount > 0) append("${feed.unreadCount} 篇未读 · ")
                    if (feed.lastError.isNotBlank()) append("上次失败") else append(RssFormat.fetchTime(feed.lastFetchAt))
                },
                style = MaterialTheme.typography.labelSmall,
                color = if (feed.lastError.isNotBlank()) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
    HorizontalDivider(
        modifier = Modifier.padding(start = 52.dp),
        thickness = 0.5.dp,
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f),
    )
}

/** 管理态底部浮条 */
@Composable
private fun ManageBottomBar(
    selectedCount: Int,
    onMoveToGroup: () -> Unit,
    onDelete: () -> Unit,
) {
    val enabled = selectedCount > 0
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = if (enabled) "已选 $selectedCount 项" else "未选择",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onMoveToGroup, enabled = enabled) {
            Icon(Icons.Filled.DriveFileMove, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(4.dp))
            Text("分组")
        }
        TextButton(onClick = onDelete, enabled = enabled) {
            Icon(
                Icons.Filled.Delete,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = if (enabled) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(4.dp))
            Text(
                "删除",
                color = if (enabled) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** 文章行 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ArticleRow(
    article: RssArticle,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        if (article.imageUrl.isNotBlank()) {
            Box(
                modifier = Modifier
                    .size(64.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center,
            ) {
                SubcomposeAsyncImage(
                    model = article.imageUrl,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                ) {
                    when (painter.state) {
                        is AsyncImagePainter.State.Error -> RssPlaceholder()
                        is AsyncImagePainter.State.Loading -> RssPlaceholder()
                        else -> SubcomposeAsyncImageContent()
                    }
                }
            }
            Spacer(Modifier.width(12.dp))
        }

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = article.title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = if (article.read) FontWeight.Normal else FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = buildString {
                    if (article.feedTitle.isNotBlank()) append(article.feedTitle).append(" · ")
                    append(RssFormat.relativeTime(article.pubDate).ifBlank { "时间未知" })
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (article.summary.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = article.summary,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(start = 8.dp),
        ) {
            if (!article.read) {
                Icon(
                    imageVector = Icons.Filled.Circle,
                    contentDescription = "未读",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(9.dp),
                )
            }
            if (article.starred) {
                Spacer(Modifier.height(6.dp))
                Icon(
                    imageVector = Icons.Filled.Star,
                    contentDescription = "已收藏",
                    tint = MaterialTheme.colorScheme.tertiary,
                    modifier = Modifier.size(16.dp),
                )
            }
        }
    }
    HorizontalDivider(
        modifier = Modifier.padding(start = 16.dp),
        thickness = 0.5.dp,
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f),
    )
}

/** 筛选 / 源 chip（长按可管理） */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun RssChip(
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
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier
            .background(bg, RoundedCornerShape(16.dp))
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 14.dp, vertical = 6.dp),
    )
}

@Composable
private fun RssPlaceholder() {
    Icon(
        imageVector = Icons.Filled.RssFeed,
        contentDescription = null,
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.size(22.dp),
    )
}

@Composable
private fun EmptyState(
    title: String,
    subtitle: String,
    onAction: (() -> Unit)?,
) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                imageVector = Icons.Filled.RssFeed,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(48.dp),
            )
            Spacer(Modifier.height(12.dp))
            Text(text = title, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(6.dp))
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 32.dp),
            )
            if (onAction != null) {
                Spacer(Modifier.height(12.dp))
                TextButton(onClick = onAction) {
                    Icon(Icons.Filled.Add, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text("添加订阅")
                }
            }
        }
    }
}

/** 用系统浏览器打开原文 */
internal fun openInBrowser(context: android.content.Context, url: String) {
    runCatching {
        context.startActivity(
            android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url))
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}
