package com.novelreader.ui.screens.rss

import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.MarkEmailUnread
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import coil.compose.AsyncImagePainter
import coil.compose.SubcomposeAsyncImage
import coil.compose.SubcomposeAsyncImageContent
import com.novelreader.core.rss.RssBlock
import com.novelreader.core.rss.RssContent
import com.novelreader.domain.model.RssArticle
import com.novelreader.domain.repository.RssRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * 订阅文章阅读页 ViewModel（v1.4.0）。
 *
 * 支持同源内「上一篇 / 下一篇」：切换时只换文章 id，不重开页面
 * （避免连读时把返回栈堆满）。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RssReaderViewModel(
    private val repository: RssRepository,
    startArticleId: Long,
) : ViewModel() {

    /** 当前文章 id（切换上下篇时更新） */
    private val currentId = MutableStateFlow(startArticleId)

    private val _siblings = MutableStateFlow<List<RssArticle>>(emptyList())

    /** 同源文章快照（按时间倒序），用于上一篇 / 下一篇 */
    val siblings: StateFlow<List<RssArticle>> = _siblings.asStateFlow()

    val article: StateFlow<RssArticle?> = currentId
        .flatMapLatest { repository.observeArticle(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    init {
        load(startArticleId)
    }

    private fun load(articleId: Long) {
        currentId.value = articleId
        viewModelScope.launch {
            val target = repository.getArticle(articleId) ?: return@launch
            if (_siblings.value.isEmpty() || _siblings.value.none { it.feedId == target.feedId }) {
                _siblings.value = runCatching { repository.articlesOfFeed(target.feedId) }
                    .getOrDefault(emptyList())
            }
        }
    }

    fun open(articleId: Long) {
        if (articleId == currentId.value) return
        load(articleId)
    }

    fun markRead(read: Boolean) {
        viewModelScope.launch { repository.markRead(currentId.value, read) }
    }

    fun toggleStar() {
        viewModelScope.launch { repository.toggleStar(currentId.value) }
    }

    /** 上一篇（列表中更新的那一篇） */
    fun previous(): Long? {
        val list = _siblings.value
        val index = list.indexOfFirst { it.articleId == currentId.value }
        return if (index > 0) list[index - 1].articleId else null
    }

    /** 下一篇（列表中更旧的那一篇） */
    fun next(): Long? {
        val list = _siblings.value
        val index = list.indexOfFirst { it.articleId == currentId.value }
        return if (index >= 0 && index < list.size - 1) list[index + 1].articleId else null
    }
}

/**
 * 订阅文章阅读页（v1.4.0）。
 *
 * 正文来自源提供的 `content:encoded` / `description`，
 * 经 [RssContent] 转成段落 + 图片块渲染（样式与小说阅读页同源：正文 16sp / 行高 26sp）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RssReaderScreen(
    viewModel: RssReaderViewModel,
    onBack: () -> Unit,
) {
    val article by viewModel.article.collectAsState()
    val siblings by viewModel.siblings.collectAsState()
    val context = LocalContext.current

    // 打开即标记已读（用户手动标未读后不会重复触发：articleId 未变）
    LaunchedEffect(article?.articleId) {
        val a = article
        if (a != null && !a.read) viewModel.markRead(true)
    }

    val blocks = remember(article?.articleId, article?.contentHtml) {
        val a = article ?: return@remember emptyList()
        runCatching { RssContent.blocks(a.contentHtml, a.link) }.getOrDefault(emptyList())
    }

    val prevId = remember(article?.articleId, siblings) { viewModel.previous() }
    val nextId = remember(article?.articleId, siblings) { viewModel.next() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = article?.feedTitle?.ifBlank { "订阅" } ?: "订阅",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    article?.let { a ->
                        IconButton(onClick = { viewModel.toggleStar() }) {
                            Icon(
                                imageVector = if (a.starred) Icons.Filled.Star else Icons.Filled.StarBorder,
                                contentDescription = if (a.starred) "取消收藏" else "收藏",
                                tint = if (a.starred) MaterialTheme.colorScheme.tertiary
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        IconButton(onClick = { viewModel.markRead(false) }) {
                            Icon(Icons.Filled.MarkEmailUnread, contentDescription = "标记未读")
                        }
                        if (a.link.isNotBlank()) {
                            IconButton(onClick = { openInBrowser(context, a.link) }) {
                                Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = "浏览器打开原文")
                            }
                        }
                    }
                },
            )
        },
        bottomBar = {
            if (article != null) {
                Column {
                    HorizontalDivider(
                        thickness = 0.5.dp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f),
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        TextButton(
                            enabled = prevId != null,
                            onClick = { prevId?.let { viewModel.open(it) } },
                        ) {
                            Icon(Icons.Filled.ChevronLeft, contentDescription = null)
                            Text("上一篇")
                        }
                        TextButton(
                            enabled = nextId != null,
                            onClick = { nextId?.let { viewModel.open(it) } },
                        ) {
                            Text("下一篇")
                            Icon(Icons.Filled.ChevronRight, contentDescription = null)
                        }
                    }
                }
            }
        },
    ) { padding ->
        val a = article
        if (a == null) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator()
            }
            return@Scaffold
        }

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp),
        ) {
            item {
                Text(
                    text = a.title,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    text = buildString {
                        if (a.author.isNotBlank()) append(a.author).append(" · ")
                        append(RssFormat.absoluteTime(a.pubDate))
                        if (a.feedTitle.isNotBlank()) append(" · ").append(a.feedTitle)
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(16.dp))
                HorizontalDivider(
                    thickness = 0.5.dp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f),
                )
                Spacer(Modifier.height(16.dp))
            }

            if (blocks.isEmpty()) {
                item {
                    Text(
                        text = a.summary.ifBlank { "该订阅源没有提供正文，可点右上角在浏览器中查看原文。" },
                        style = MaterialTheme.typography.bodyLarge,
                        lineHeight = 26.sp,
                    )
                }
            }

            items(blocks) { block ->
                when (block) {
                    is RssBlock.Paragraph -> {
                        Text(
                            text = block.text,
                            style = MaterialTheme.typography.bodyLarge,
                            lineHeight = 26.sp,
                            modifier = Modifier.padding(bottom = 14.dp),
                        )
                    }
                    is RssBlock.Image -> {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = 14.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(MaterialTheme.colorScheme.surfaceVariant),
                            contentAlignment = Alignment.Center,
                        ) {
                            SubcomposeAsyncImage(
                                model = block.url,
                                contentDescription = null,
                                contentScale = ContentScale.FillWidth,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                when (painter.state) {
                                    is AsyncImagePainter.State.Error -> ImagePlaceholder()
                                    is AsyncImagePainter.State.Loading -> ImagePlaceholder()
                                    else -> SubcomposeAsyncImageContent()
                                }
                            }
                        }
                    }
                }
            }

            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

@Composable
private fun ImagePlaceholder() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(120.dp),
        contentAlignment = Alignment.Center,
    ) {
        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
    }
}
