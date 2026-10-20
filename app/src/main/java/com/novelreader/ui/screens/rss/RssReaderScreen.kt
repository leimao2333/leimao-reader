package com.novelreader.ui.screens.rss

import androidx.compose.foundation.background
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.FindInPage
import androidx.compose.material.icons.filled.FormatQuote
import androidx.compose.material.icons.filled.MarkEmailUnread
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import coil.compose.AsyncImagePainter
import coil.compose.SubcomposeAsyncImage
import coil.compose.SubcomposeAsyncImageContent
import com.novelreader.core.network.HttpClient
import com.novelreader.core.rss.ArticleBodyExtractor
import com.novelreader.core.rss.CrossrefClient
import com.novelreader.core.rss.JournalCatalog
import com.novelreader.core.rss.PaperCitation
import com.novelreader.core.rss.PaperMeta
import com.novelreader.core.rss.PaperMetaParser
import com.novelreader.core.rss.PaperPdf
import com.novelreader.core.rss.RssBlock
import com.novelreader.core.rss.RssContent
import com.novelreader.domain.model.RssArticle
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

/** 引用导出格式（v1.8.5.0） */
enum class CitationFormat(val label: String, val hint: String) {
    BIBTEX("BibTeX", "粘贴进 LaTeX / Overleaf 的 .bib"),
    RIS("RIS", "导入 EndNote / Zotero / NoteExpress"),
}

/**
 * 论文增强状态（v1.8.5.0）。
 *
 * 期刊源（尤其 Crossref 虚拟源）常常只有题录没有正文，阅读页因此多了三个动作：
 * **获取全文**（抓原文页抽正文）、**补全题录**（按 DOI 问 Crossref）、
 * **打开 PDF**（按 DOI 拼各大出版社的 PDF 直链）。这三个都是网络动作，
 * 状态集中在一个 data class 里，避免散落的五六个 MutableStateFlow。
 */
data class JournalUiState(
    /** 网络动作进行中（按钮转圈） */
    val loading: Boolean = false,
    /** 一次性提示（打开原文失败 / 只拿到摘要 / 已补全） */
    val note: String = "",
    /** 抓到的正文 HTML（空 = 还没抓，用源里带的正文） */
    val fullTextHtml: String = "",
    /** 这次抓到的是全文还是只有摘要（付费墙） */
    val partial: Boolean = false,
    /** 从原文页 meta / Crossref 补全到的题录（比源里的更全） */
    val enrichedMeta: PaperMeta? = null,
)

/**
 * 订阅文章阅读页 ViewModel（v1.3.1.0）。
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

    private val _journal = MutableStateFlow(JournalUiState())

    /** 论文增强状态（v1.8.5.0） */
    val journal: StateFlow<JournalUiState> = _journal.asStateFlow()

    /**
     * 当前文章的有效题录：抓取补全过就用补全的。
     * 单独暴露一个 Flow，是为了让题录卡在「补全完成」后自动更新。
     */
    val paperMeta: StateFlow<PaperMeta> =
        combine(article, _journal) { a, state ->
            state.enrichedMeta?.takeIf { it.hasContent } ?: a?.paperMeta ?: PaperMeta()
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PaperMeta())

    init {
        load(startArticleId)
    }

    private fun load(articleId: Long) {
        currentId.value = articleId
        // 换文章时把上一篇的全文 / 题录缓存清掉：留着会让下一篇显示别人的正文
        _journal.value = JournalUiState()
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

    // ==================== 论文增强（v1.8.5.0） ====================

    /**
     * 抓原文页抽正文 / 摘要；失败时退回 Crossref 摘要，并给出**被拦的真实原因**。
     *
     * 期刊 RSS 的 description 通常只有一句引用（"Science, Volume 394, Issue 6821…"），
     *  Crossref 又没有 abstract 的刊（ACS / RSC 都不给），阅读页就是一张空卡。
     *
     * 为什么先问 Crossref：实测 Science / PNAS / Cell / ACS 的原文页全是 Cloudflare
     * 挑战页（403），Nature 对无 cookie 客户端返回 404 —— 直接抓原文页基本白跑一趟。
     * Crossref 免 key、无反爬，且 RSC（Chemical Science）等刊确实有 abstract，
     * 摘要够长时这一步就能填满阅读页，还省下一次注定被拦的请求。
     */
    fun loadFullText() {
        val a = article.value ?: return
        viewModelScope.launch {
            _journal.value = _journal.value.copy(loading = true, note = "")

            val crossref = if (a.doi.isNotBlank()) {
                runCatching { CrossrefClient.workByDoi(a.doi) }.getOrNull()
            } else null
            val crossrefHtml = crossref?.abstractHtml.orEmpty()

            if (RssContent.plainText(crossrefHtml).length >= USABLE_ABSTRACT_CHARS) {
                _journal.value = _journal.value.copy(
                    loading = false,
                    fullTextHtml = crossrefHtml,
                    partial = true,
                    note = "已获取 Crossref 摘要；出版方正文页有反爬验证，全文需用浏览器打开",
                    enrichedMeta = (article.value?.paperMeta ?: PaperMeta())
                        .mergeWith(crossref?.toPaperMeta() ?: PaperMeta())
                        .takeIf { it.hasContent },
                )
                return@launch
            }

            val url = a.link.ifBlank { a.paperMeta.doiUrl().ifBlank { PaperPdf.pdfUrl(a.doi) } }
            if (url.isBlank()) {
                _journal.value = _journal.value.copy(
                    loading = false,
                    note = if (crossrefHtml.isNotBlank()) "只有 Crossref 摘要这么长"
                    else "这篇文章没有原文链接",
                )
                return@launch
            }
            val html = runCatching {
                HttpClient.get(url, ARTICLE_PAGE_HEADERS, timeoutMs = 25_000L)
            }.getOrNull()
            if (html.isNullOrBlank()) {
                _journal.value = _journal.value.copy(loading = false, note = "打不开原文页（超时或域名无法解析）")
                return@launch
            }
            // 被反爬拦下：说清原因，别让用户以为 App 坏了
            val blocked = ArticleBodyExtractor.blockedReason(html)
            if (blocked.isNotEmpty()) {
                _journal.value = _journal.value.copy(loading = false, note = blocked)
                return@launch
            }
            val extraction = ArticleBodyExtractor.extract(html, url)
            if (extraction.isEmpty) {
                _journal.value = _journal.value.copy(
                    loading = false,
                    note = "页面里没抽出正文，可点右上角用浏览器打开",
                )
                return@launch
            }
            _journal.value = _journal.value.copy(
                loading = false,
                fullTextHtml = extraction.html,
                partial = !extraction.looksFullText,
                note = if (extraction.looksFullText) "已获取全文（${extraction.textLength} 字）"
                else "只拿到摘要：付费墙或页面结构与识别规则不匹配",
                // 页面 Highwire meta 的作者列表通常比 RSS 全；用「补空」而不是「替换」，
                // 避免把 Crossref 已有的卷 / 期 / 页冲掉
                enrichedMeta = (article.value?.paperMeta ?: PaperMeta())
                    .mergeWith(extraction.meta)
                    .takeIf { it.hasContent },
            )
        }
    }

    /** 按 DOI 向 Crossref 补全题录（卷 / 期 / 页 / 完整作者 / 摘要） */
    fun enrichFromCrossref() {
        val doi = paperMeta.value.doi.ifBlank { article.value?.doi.orEmpty() }
        if (PaperMetaParser.normalizeDoi(doi).isEmpty()) {
            _journal.value = _journal.value.copy(note = "这篇文章没有 DOI，无法向 Crossref 补全")
            return
        }
        viewModelScope.launch {
            _journal.value = _journal.value.copy(loading = true, note = "")
            val work = runCatching { CrossrefClient.workByDoi(doi) }.getOrNull()
            _journal.value = if (work == null) {
                _journal.value.copy(loading = false, note = "Crossref 没查到这篇（DOI 可能未注册）")
            } else {
                _journal.value.copy(
                    loading = false,
                    note = "题录已按 Crossref 补全",
                    // 同样是补空：Crossref 没有 abstract 时不能把源里已有的摘要冲掉
                    enrichedMeta = _journal.value.enrichedMeta
                        ?.mergeWith(work.toPaperMeta())
                        ?: work.toPaperMeta(),
                )
            }
        }
    }

    /** PDF 直链（拼不出来返回空串） */
    fun pdfUrl(): String {
        val a = article.value ?: return ""
        return PaperPdf.pdfUrl(paperMeta.value.doi.ifBlank { a.doi }, a.link)
    }

    /** 生成引用文本 */
    fun citation(format: CitationFormat): String {
        val a = article.value ?: return ""
        val meta = paperMeta.value
        val authors = meta.authors.ifEmpty { PaperMetaParser.splitAuthors(a.author) }
        return when (format) {
            CitationFormat.BIBTEX -> PaperCitation.bibtex(meta, a.title, authors)
            CitationFormat.RIS -> PaperCitation.ris(meta, a.title, authors)
        }
    }

    /** 期刊显示名（命中内置预设时用中文简称，阅读页顶栏更好认） */
    fun journalLabel(): String {
        val meta = paperMeta.value
        if (meta.journal.isBlank()) return ""
        return JournalCatalog.PRESETS.firstOrNull { p ->
            meta.journal.contains(p.shortName, ignoreCase = true) ||
                meta.journal.contains(p.title, ignoreCase = true)
        }?.shortName ?: meta.journal
    }

    fun dismissJournalNote() {
        if (_journal.value.note.isNotEmpty()) {
            _journal.value = _journal.value.copy(note = "")
        }
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

    private companion object {
        /**
         * 抓原文页用的附加请求头。
         * [HttpClient.get] 已经默认带上浏览器 UA 与 Accept，这里只补语言偏好 ——
         * 学术站点（Science / Nature / RSC）会按语言返回界面，中文偏好下抽正文更稳。
         */
        val ARTICLE_PAGE_HEADERS: Map<String, String> = mapOf(
            "Accept-Language" to "zh-CN,zh;q=0.9,en;q=0.8",
        )

        /**
         * Crossref 摘要至少多少字才值得单独展示。
         * 低于这个数说明只是「一句话摘要」，还不如去抓原文页试试。
         */
        const val USABLE_ABSTRACT_CHARS = 120
    }
}

/**
 * 订阅文章阅读页（v1.3.1.0；v1.8.5.0 增加论文题录卡与期刊增强动作）。
 *
 * 正文来自源提供的 `content:encoded` / `description`，
 * 经 [RssContent] 转成段落 + 图片块渲染（样式与小说阅读页同源：正文 16sp / 行高 26sp）。
 *
 * 期刊文章（[RssArticle.isPaper]）在标题下方多一张**题录卡**：期刊 / 卷 / 期 / 页 /
 * DOI / 文章类型，配四个动作 —— 获取全文、补全题录、打开 PDF、导出引用。
 * 普通订阅文章不显示题录卡，界面与旧版完全一致。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RssReaderScreen(
    viewModel: RssReaderViewModel,
    onBack: () -> Unit,
    /**
     * 打开 PDF（v1.8.5.1）。
     *
     * 以前是 `Intent.ACTION_VIEW` 丢给浏览器 —— 跳出去读完就回不来了。
     * 现在交给应用内阅读页（[com.novelreader.ui.screens.pdf.PdfViewerScreen]），
     * 保留默认空实现是为了让只关心正文的调用方 / UI 测试不必传参。
     */
    onOpenPdf: (url: String, title: String) -> Unit = { _, _ -> },
) {
    val article by viewModel.article.collectAsState()
    val siblings by viewModel.siblings.collectAsState()
    val paperMeta by viewModel.paperMeta.collectAsState()
    val journalState by viewModel.journal.collectAsState()
    val context = LocalContext.current

    // 打开即标记已读（用户手动标未读后不会重复触发：articleId 未变）
    LaunchedEffect(article?.articleId) {
        val a = article
        if (a != null && !a.read) viewModel.markRead(true)
    }

    /**
     * 论文动作的结果提示**常驻**在题录卡里，不用 Toast。
     * 「出版方有反爬验证，抓不到正文」这类原因正是用户最需要看到的信息，
     * 3 秒的 Toast 一眨就过去了；常驻到切换文章才清掉。
     */

    /** 引用导出弹窗 */
    var showCitation by remember { mutableStateOf<CitationFormat?>(null) }

    // 正文：抓到的全文优先，否则用源里带的 contentHtml
    val bodyHtml = journalState.fullTextHtml.ifBlank { article?.contentHtml.orEmpty() }
    val blocks = remember(article?.articleId, bodyHtml) {
        runCatching { RssContent.blocks(bodyHtml, article?.link.orEmpty()) }.getOrDefault(emptyList())
    }

    val prevId = remember(article?.articleId, siblings) { viewModel.previous() }
    val nextId = remember(article?.articleId, siblings) { viewModel.next() }
    val pdfUrl = remember(article?.articleId, paperMeta.doi) { viewModel.pdfUrl() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = article?.let { a ->
                            if (a.isPaper) {
                                viewModel.journalLabel().ifBlank {
                                    a.feedTitle.ifBlank { "订阅" }
                                }
                            } else {
                                a.feedTitle.ifBlank { "订阅" }
                            }
                        } ?: "订阅",
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
                        if (a.isPaper) {
                            IconButton(onClick = { showCitation = CitationFormat.BIBTEX }) {
                                Icon(Icons.Filled.FormatQuote, contentDescription = "导出引用")
                            }
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

            // 论文题录卡 + 期刊增强动作（v1.8.5.0）
            if (a.isPaper) {
                item(key = "citation_${a.articleId}") {
                    PaperCitationCard(
                        meta = paperMeta,
                        pdfUrl = pdfUrl,
                        loading = journalState.loading,
                        note = journalState.note,
                        fetchedFullText = journalState.fullTextHtml.isNotBlank(),
                        partialFullText = journalState.partial,
                        onFetchFullText = { viewModel.loadFullText() },
                        onEnrich = { viewModel.enrichFromCrossref() },
                        onOpenPdf = {
                            if (pdfUrl.isNotBlank()) {
                                onOpenPdf(pdfUrl, a.title)
                            }
                        },
                        onCite = { showCitation = it },
                    )
                    Spacer(Modifier.height(16.dp))
                }
            }

            if (blocks.isEmpty()) {
                item {
                    if (a.isPaper) {
                        // 期刊文章没有正文是常态（Crossref 无 abstract / 官方 RSS 只给题录）
                        Text(
                            text = "这篇还没有正文。可点上方「获取全文」抓原文页，" +
                                "或点右上角用浏览器打开原文。",
                            style = MaterialTheme.typography.bodyLarge,
                            lineHeight = 26.sp,
                        )
                    } else {
                        Text(
                            text = a.summary.ifBlank { "该订阅源没有提供正文，可点右上角在浏览器中查看原文。" },
                            style = MaterialTheme.typography.bodyLarge,
                            lineHeight = 26.sp,
                        )
                    }
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

    // 引用导出弹窗（BibTeX / RIS，可整段复制）
    showCitation?.let { format ->
        PaperCitationDialog(
            format = format,
            text = viewModel.citation(format),
            title = article?.title.orEmpty(),
            onSwitchFormat = { showCitation = it },
            onDismiss = { showCitation = null },
        )
    }
}

/** 论文题录卡 + 期刊增强动作（v1.8.5.0）。 */
@Composable
private fun PaperCitationCard(
    meta: PaperMeta,
    pdfUrl: String,
    loading: Boolean,
    /** 上一个动作的结果 / 失败原因（空 = 不显示） */
    note: String,
    fetchedFullText: Boolean,
    partialFullText: Boolean,
    onFetchFullText: () -> Unit,
    onEnrich: () -> Unit,
    onOpenPdf: () -> Unit,
    onCite: (CitationFormat) -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            // 题录头：期刊名 + 卷(期): 页 (年)
            val line = meta.citationLine()
            if (line.isNotBlank()) {
                Text(
                    text = line,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.height(6.dp))
            }

            // 题录明细
            val details = buildList {
                if (meta.articleType.isNotBlank()) add(meta.articleType)
                if (meta.authors.isNotEmpty()) add("${meta.authors.size} 位作者")
                if (meta.publisher.isNotBlank()) add(meta.publisher)
            }
            if (details.isNotEmpty()) {
                Text(
                    text = details.joinToString(" · "),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (meta.authors.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    // 作者列表可能很长（几十人），题录卡里只露前 6 位
                    text = meta.authors.take(6).joinToString(", ") +
                        if (meta.authors.size > 6) " 等 ${meta.authors.size} 人" else "",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (meta.doi.isNotBlank()) {
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "DOI ${meta.doi}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            Spacer(Modifier.height(10.dp))
            // 动作行：获取全文 / 补全题录 / PDF / 引用
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                if (loading) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(4.dp))
                } else {
                    TextButton(
                        onClick = onFetchFullText,
                        contentPadding = PaddingValues(horizontal = 8.dp),
                    ) {
                        Icon(Icons.Filled.MenuBook, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(3.dp))
                        Text(
                            text = when {
                                fetchedFullText && partialFullText -> "再看摘要"
                                fetchedFullText -> "重新获取"
                                else -> "获取全文"
                            },
                            style = MaterialTheme.typography.labelMedium,
                        )
                    }
                }
                TextButton(
                    onClick = onEnrich,
                    enabled = !loading && meta.doi.isNotBlank(),
                    contentPadding = PaddingValues(horizontal = 8.dp),
                ) {
                    Icon(Icons.Filled.FindInPage, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(3.dp))
                    Text("补全题录", style = MaterialTheme.typography.labelMedium)
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                TextButton(
                    onClick = onOpenPdf,
                    enabled = pdfUrl.isNotBlank(),
                    contentPadding = PaddingValues(horizontal = 8.dp),
                ) {
                    Icon(Icons.Filled.PictureAsPdf, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(3.dp))
                    // v1.8.5.1：应用内阅读，不再跳浏览器
                    Text("阅读 PDF", style = MaterialTheme.typography.labelMedium)
                }
                TextButton(
                    onClick = { onCite(CitationFormat.BIBTEX) },
                    contentPadding = PaddingValues(horizontal = 8.dp),
                ) {
                    Icon(Icons.Filled.FormatQuote, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(3.dp))
                    Text("引用", style = MaterialTheme.typography.labelMedium)
                }
            }

            // 动作结果 / 失败原因常驻显示。
            // 「出版方有反爬验证」这类信息是用户最需要看到的 —— 一闪而过的 Toast 会被错过。
            if (note.isNotBlank()) {
                Spacer(Modifier.height(8.dp))
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.7f),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = note,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                    )
                }
            }
        }
    }
}

/** 引用导出弹窗（v1.8.5.0）：BibTeX / RIS 两种格式，可切换、可整段复制。 */
@Composable
private fun PaperCitationDialog(
    format: CitationFormat,
    text: String,
    title: String,
    onSwitchFormat: (CitationFormat) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("导出引用") },
        text = {
            Column {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CitationFormat.values().forEach { f ->
                        TextButton(onClick = { onSwitchFormat(f) }) {
                            Text(
                                text = f.label,
                                color = if (f == format) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                                fontWeight = if (f == format) FontWeight.SemiBold else FontWeight.Normal,
                            )
                        }
                    }
                }
                Text(
                    text = format.hint,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.surface,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 140.dp, max = 280.dp),
                ) {
                    // 纵向滚动 + 自然换行。
                    // 别用 horizontalScroll：引用条目的每一行都很长（DOI / 作者列表），
                    // 横向滚动会让 Text 按无限宽测量从而完全不换行，右侧整段被裁掉。
                    Box(
                        modifier = Modifier
                            .verticalScroll(rememberScrollState())
                            .padding(10.dp),
                    ) {
                        SelectionContainer {
                            Text(
                                text = text,
                                style = MaterialTheme.typography.bodySmall,
                                // 引用格式靠等宽对齐，字段名竖着成一列更好比对
                                fontFamily = FontFamily.Monospace,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    copyToClipboard(context, "citation", text)
                    android.widget.Toast.makeText(context, "引用已复制", android.widget.Toast.LENGTH_SHORT).show()
                    onDismiss()
                },
            ) { Text("复制") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("关闭") }
        },
    )
}

/** 复制到系统剪贴板（引用导出用） */
private fun copyToClipboard(context: android.content.Context, label: String, text: String) {
    val cm = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
        as? android.content.ClipboardManager ?: return
    cm.setPrimaryClip(android.content.ClipData.newPlainText(label, text))
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
