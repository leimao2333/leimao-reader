package com.novelreader.ui.screens.rss

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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Circle
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.RssFeed
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.AlertDialog
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
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** 文章筛选维度 */
enum class RssFilter(val label: String) {
    ALL("全部"),
    UNREAD("未读"),
    STARRED("收藏"),
}

/**
 * 订阅页 ViewModel（v1.4.0）。
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
            _message.value = summarize(results)
        }
    }

    fun refreshAll() {
        if (_refreshing.value) return
        viewModelScope.launch {
            _refreshing.value = true
            val results = runCatching { repository.refreshAll() }.getOrDefault(emptyList())
            _refreshing.value = false
            val newCount = results.sumOf { it.newArticles }
            val failed = results.count { !it.ok }
            _message.value = buildString {
                append("已更新 ${results.size - failed}/${results.size} 个源，新增 $newCount 篇")
                if (failed > 0) append("，$failed 个失败")
            }
        }
    }

    fun refreshFeed(feedId: Long) {
        viewModelScope.launch {
            _refreshing.value = true
            val result = runCatching { repository.refreshFeed(feedId) }.getOrNull()
            _refreshing.value = false
            _message.value = when {
                result == null -> "刷新失败"
                result.ok -> "已更新《${result.title}》，新增 ${result.newArticles} 篇"
                else -> "《${result.title}》刷新失败：${result.error}"
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

    private fun summarize(results: List<RssFeedResult>): String {
        if (results.isEmpty()) return "没有识别到可用的订阅地址"
        val ok = results.filter { it.ok }
        val bad = results.filterNot { it.ok }
        val parts = mutableListOf<String>()
        if (ok.isNotEmpty()) {
            parts += "已订阅 ${ok.size} 个源，新增 ${ok.sumOf { it.newArticles }} 篇"
        }
        bad.take(3).forEach { parts += "订阅失败：${it.error}" }
        if (bad.size > 3) parts += "另有 ${bad.size - 3} 个失败"
        return parts.joinToString("\n")
    }

    companion object {
        /** 超过 30 分钟未抓取即视为过期（进入页面时自动刷新） */
        private const val STALE_MILLIS = 30 * 60 * 1000L
    }
}

/**
 * 订阅页（v1.4.0）。
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

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("订阅") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
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
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            if (feeds.isNotEmpty()) {
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
                    items(feeds, key = { it.feedId }) { feed ->
                        RssChip(
                            label = if (feed.unreadCount > 0) "${feed.title} ${feed.unreadCount}" else feed.title,
                            selected = selected == feed.feedId,
                            onLongClick = { manageFeed = feed },
                            onClick = { viewModel.selectFeed(feed.feedId) },
                        )
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
                    TextButton(onClick = {
                        viewModel.refreshFeed(feed.feedId)
                        manageFeed = null
                    }) { Text("立即刷新") }
                    TextButton(onClick = { viewModel.markFeedRead(feed.feedId, feed.title) }) {
                        Text("该源全部已读")
                    }
                    TextButton(onClick = {
                        viewModel.deleteFeed(feed.feedId, feed.title)
                        manageFeed = null
                    }) { Text("删除订阅", color = MaterialTheme.colorScheme.error) }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = nameInput.isNotBlank() && nameInput.trim() != feed.title,
                    onClick = {
                        viewModel.renameFeed(feed.feedId, nameInput)
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
