package com.novelreader.ui.screens.novel

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel

/**
 * 正文搜索独立页（C12）。
 *
 * 把旧版嵌在 NovelReaderMenu 里的 ContentSearchPanel 覆盖层搬成可导航的独立页面：
 * 输入关键词 → 本章/全书匹配 → 结果列表（章节名 + 上下文 + 高亮）→ 点击跳回阅读页定位。
 *
 * 正文为什么由入参传入：阅读页的正文是懒加载的，没有「全书正文」状态可直接读；
 * 让调用方（后续路由接线）把已加载的章节列表传进来，页面就不依赖 NovelReaderViewModel
 * 的内部字段，也方便单测用假数据驱动。
 *
 * @param chapters 待搜索的章节（含已净化正文）；调用方从阅读页组装后传入
 * @param initialQuery 进页自带的关键词（如从选中文字发起搜索），非空则进页即搜
 * @param initialScope 初始搜索范围
 * @param currentChapterIndex 当前章在 [chapters] 中的下标，供「本章」范围过滤
 * @param onBack 返回上层（退出独立页）
 * @param onResultClick 点击结果：把命中的章节与位置交回调用方跳章定位
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContentSearchScreen(
    chapters: List<ContentSearchChapter>,
    onBack: () -> Unit,
    onResultClick: (ContentSearchMatch) -> Unit,
    modifier: Modifier = Modifier,
    initialQuery: String = "",
    initialScope: ContentSearchScope = ContentSearchScope.WHOLE_BOOK,
    currentChapterIndex: Int = 0,
) {
    // 手写 factory：ViewModel 需要章节等运行时入参，用构造参数注入比全局容器更清晰。
    val factory = object : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = ContentSearchViewModel(
            chapters = chapters,
            initialQuery = initialQuery,
            initialScope = initialScope,
            currentChapterIndex = currentChapterIndex,
        ) as T
    }
    val vm: ContentSearchViewModel = viewModel(factory = factory)

    val query by vm.query.collectAsState()
    val scope by vm.scope.collectAsState()
    val results by vm.results.collectAsState()
    val currentIndex by vm.currentIndex.collectAsState()
    val isSearching by vm.isSearching.collectAsState()

    val listState = rememberLazyListState()
    // 「上/下一个」移动选中项后把该项滚进视野，否则高亮在屏幕外用户看不到。
    LaunchedEffect(currentIndex) {
        if (currentIndex in results.indices) listState.animateScrollToItem(currentIndex)
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        // 为什么显式传 windowInsets：项目开启了 edge-to-edge，默认顶栏会钻到状态栏底下；
        // 显式用 statusBars 让顶栏自行避让，夜间模式也不会透出浅色系统栏。
        topBar = {
            TopAppBar(
                title = { Text(text = "正文搜索") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                windowInsets = WindowInsets.statusBars,
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface,
                    navigationIconContentColor = MaterialTheme.colorScheme.onSurface,
                ),
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            // 范围切换：本章 / 全书，对齐旧面板的两个 Chip。
            Row(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FilterChip(
                    selected = scope == ContentSearchScope.CURRENT_CHAPTER,
                    onClick = { vm.setScope(ContentSearchScope.CURRENT_CHAPTER) },
                    label = { Text("本章") },
                )
                Spacer(Modifier.width(8.dp))
                FilterChip(
                    selected = scope == ContentSearchScope.WHOLE_BOOK,
                    onClick = { vm.setScope(ContentSearchScope.WHOLE_BOOK) },
                    label = { Text("全书") },
                )
            }

            OutlinedTextField(
                value = query,
                onValueChange = vm::onQueryChange,
                placeholder = {
                    Text(if (scope == ContentSearchScope.WHOLE_BOOK) "搜索全书正文" else "搜索本章正文")
                },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
            )

            // 计数 + 上/下一个：仅在有结果时出现，空态下不显示会误导用户的「0/0」。
            if (results.isNotEmpty()) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "共 ${results.size} 处匹配",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = { vm.prevResult() }) {
                        Icon(Icons.Filled.KeyboardArrowUp, contentDescription = "上一个结果")
                    }
                    Text(
                        text = "${currentIndex + 1}/${results.size}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    IconButton(onClick = { vm.nextResult() }) {
                        Icon(Icons.Filled.KeyboardArrowDown, contentDescription = "下一个结果")
                    }
                }
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center,
            ) {
                when {
                    query.isBlank() -> Text(
                        text = "输入关键词开始搜索",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    isSearching -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator()
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = "正在搜索正文…",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }

                    results.isEmpty() -> Text(
                        text = "未找到「${query.trim()}」",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    else -> LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                        itemsIndexed(results) { index, match ->
                            val selected = index == currentIndex
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(
                                        if (selected) MaterialTheme.colorScheme.secondaryContainer
                                        else MaterialTheme.colorScheme.background
                                    )
                                    .clickable {
                                        vm.selectResult(index)
                                        onResultClick(match)
                                    }
                                    .padding(horizontal = 16.dp, vertical = 10.dp),
                            ) {
                                Text(
                                    text = match.chapter.title,
                                    style = MaterialTheme.typography.titleSmall,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Spacer(Modifier.height(2.dp))
                                HighlightedExcerpt(
                                    text = match.excerpt,
                                    start = match.excerptMatchStart,
                                    end = match.excerptMatchEnd,
                                    isCurrent = selected,
                                )
                            }
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        }
                    }
                }
            }
        }
    }
}

/**
 * 结果摘录：把命中关键词用主题色高亮。
 *
 * 为什么用 AnnotatedString 的 [start, end) 而不是再 indexOf 一次：
 * ContentSearchEngine 截取上下文时就算好了精确偏移，二次查找会被重复词/省略号带偏；
 * 颜色取 colorScheme 而非写死色值，白天与夜间都能看清（当前项用 primary 更醒目）。
 */
@Composable
private fun HighlightedExcerpt(
    text: String,
    start: Int,
    end: Int,
    isCurrent: Boolean,
) {
    val annotated = buildAnnotatedString {
        append(text)
        if (start >= 0 && end > start && end <= text.length) {
            addStyle(
                style = SpanStyle(
                    background = if (isCurrent) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.primaryContainer,
                    color = if (isCurrent) MaterialTheme.colorScheme.onPrimary
                    else MaterialTheme.colorScheme.onPrimaryContainer,
                ),
                start = start,
                end = end,
            )
        }
    }
    Text(
        text = annotated,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurface,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
    )
}
