package com.novelreader.ui.screens.novel

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkAdd
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.TextFormat
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.novelreader.core.preferences.PreferencesManager
import com.novelreader.core.text.ReplaceRule
import com.novelreader.domain.model.ChapterItem
import com.novelreader.domain.model.MediaItem
import com.novelreader.domain.repository.Bookmark
import com.novelreader.ui.components.BookMeta
import com.novelreader.ui.theme.ReaderBackgrounds
import kotlinx.coroutines.launch

private const val ACCENT = 0xFF4A6FA5
private val AccentColor = Color(ACCENT)

/** 主色 / 前景色配对（选中态 / 未选中态） */
private fun chipColors(selected: Boolean, textColor: Color): Pair<Color, Color> {
    val isDark = textColor.luminance() > 0.5f
    val bg = when {
        selected -> AccentColor
        isDark -> Color(0xFF2A2A2A)
        else -> Color(0xFFE3E3E3)
    }
    val fg = when {
        selected -> Color.White
        isDark -> Color(0xFFE0E0E0)
        else -> Color(0xFF333333)
    }
    return bg to fg
}

/**
 * 当前电量（0..100）；拿不到时返回 -1（顶栏就不画，不占位）。
 *
 * 沉浸全屏把系统状态栏藏了，读着书根本看不到还剩多少电 —— 顶栏把这条信息补回来。
 * 用 `ACTION_BATTERY_CHANGED` 这条 **sticky 广播**：注册的瞬间系统就会把当前值推过来，
 * 不必等电量变化；只在顶栏展开期间注册，收起即注销（这条广播很频繁，没必要常驻）。
 */
@Composable
private fun rememberBatteryPercent(): Int {
    val context = LocalContext.current
    var percent by remember { mutableIntStateOf(-1) }
    DisposableEffect(context) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                val i = intent ?: return
                val level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                val scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
                if (level >= 0 && scale > 0) percent = (level * 100 / scale).coerceIn(0, 100)
            }
        }
        // Android 14 起动态注册必须显式声明导出性；系统广播统一用 NOT_EXPORTED
        runCatching {
            ContextCompat.registerReceiver(
                context,
                receiver,
                IntentFilter(Intent.ACTION_BATTERY_CHANGED),
                ContextCompat.RECEIVER_NOT_EXPORTED,
            )
        }
        onDispose { runCatching { context.unregisterReceiver(receiver) } }
    }
    return percent
}

/**
 * 顶栏电量：小电池轮廓 + 百分比。
 *
 * 不用 Material 图标 —— 核心图标集里没有电池形状，自带一个 14×8dp 的轮廓更轻，
 * 灰度也跟顶栏其余小字一致；20% 以下描边转红，扫一眼就知道该充电了。
 */
@Composable
private fun BatteryBadge(percent: Int, tint: Color) {
    if (percent < 0) return
    val low = percent <= 20
    val fg = if (low) Color(0xFFE05252) else tint
    Row(verticalAlignment = Alignment.CenterVertically) {
        Canvas(modifier = Modifier.width(12.dp).height(7.dp)) {
            val capW = 1.5.dp.toPx()
            val bodyW = size.width - capW
            val strokeW = 0.9.dp.toPx()
            // 外壳
            drawRoundRect(
                color = fg.copy(alpha = 0.55f),
                topLeft = Offset(0f, 0f),
                size = Size(bodyW, size.height),
                cornerRadius = CornerRadius(1.6f * density, 1.6f * density),
                style = Stroke(width = strokeW),
            )
            // 正极凸点
            drawRoundRect(
                color = fg.copy(alpha = 0.55f),
                topLeft = Offset(bodyW + 0.4.dp.toPx(), size.height * 0.28f),
                size = Size(capW, size.height * 0.44f),
                cornerRadius = CornerRadius(0.5f * density),
            )
            // 电量填充（留一点内缩，避免和描边糊在一起）
            val inset = strokeW * 1.6f
            val fillW = (bodyW - inset * 2f) * (percent / 100f)
            if (fillW > 0f) {
                drawRoundRect(
                    color = fg.copy(alpha = 0.85f),
                    topLeft = Offset(inset, inset),
                    size = Size(fillW, size.height - inset * 2f),
                    cornerRadius = CornerRadius(0.8f * density),
                )
            }
        }
        Spacer(Modifier.width(3.dp))
        Text(
            text = "$percent%",
            color = fg.copy(alpha = 0.7f),
            fontSize = 10.sp,
        )
    }
}

/** 顶部栏（返回 + 书名/章节/来源 + 收藏 + 加书签 + 进度 + 电量） */
@Composable
internal fun BoxScope.ReaderTopBar(
    bookTitle: String,
    sourceName: String,
    title: String,
    backgroundColor: Color,
    textColor: Color,
    chapterIndex: Int,
    chapterCount: Int,
    pageText: String,
    fromCache: Boolean,
    collected: Boolean,
    onToggleCollect: () -> Unit,
    onAddBookmark: () -> Unit,
    onOpenContentSearch: () -> Unit,
    onBack: () -> Unit,
) {
    val batteryPercent = rememberBatteryPercent()
    Row(
        modifier = Modifier
            .align(Alignment.TopStart)
            .fillMaxWidth()
            .background(backgroundColor.copy(alpha = 0.97f))
            // 阅读页是 edge-to-edge：不避让状态栏的话，书名会和系统时间 / 信号图标叠在一起
            .statusBarsPadding()
            .padding(horizontal = 4.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) {
            Icon(Icons.Filled.ArrowBack, contentDescription = "返回", tint = textColor)
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = bookTitle,
                color = textColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            val sub = buildString {
                append(title)
                if (sourceName.isNotBlank()) append(" · ").append(sourceName)
                if (fromCache) append(" · 缓存")
            }
            Text(
                text = sub,
                color = textColor.copy(alpha = 0.7f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        // 顶栏要塞下「返回 + 书名 + 3 图标 + 进度 + 电量」，48dp 的默认触摸盒把书名挤成「千…」，
        // 收到 40dp（仍在可点范围内），把宽度还给标题。
        IconButton(onClick = onOpenContentSearch, modifier = Modifier.size(40.dp)) {
            Icon(Icons.Filled.Search, contentDescription = "正文搜索", tint = textColor)
        }
        IconButton(onClick = onAddBookmark, modifier = Modifier.size(40.dp)) {
            // 加书签用「书签 + 加号」，与右边的「加入书架」区分开：
            // 此前两个按钮都画 Bookmark / BookmarkBorder，并排放在一起
            // 用户根本分不出哪个是收藏、哪个是加书签。
            Icon(Icons.Filled.BookmarkAdd, contentDescription = "添加书签", tint = textColor)
        }
        IconButton(onClick = onToggleCollect, modifier = Modifier.size(40.dp)) {
            Icon(
                // 收藏 = 加入书架，用星标而不是书签：书签的语义在阅读场景里
                // 天然指「记住这一页」，两者共用一个图标必然有歧义。
                imageVector = if (collected) Icons.Filled.Star else Icons.Filled.StarBorder,
                contentDescription = if (collected) "移出书架" else "加入书架",
                tint = if (collected) AccentColor else textColor,
            )
        }
        if (chapterCount > 0) {
            Column(
                horizontalAlignment = Alignment.End,
                modifier = Modifier.padding(end = 6.dp),
            ) {
                Text(
                    text = "${chapterIndex + 1}/$chapterCount 章",
                    color = textColor.copy(alpha = 0.85f),
                    style = MaterialTheme.typography.labelMedium,
                )
                Text(
                    text = "${((chapterIndex + 1) * 100f / chapterCount).toInt()}% · $pageText",
                    color = textColor.copy(alpha = 0.55f),
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }
        // 沉浸全屏把系统状态栏藏了，电量只能由顶栏自己报 —— 固定在**最右端**，
        // 位置固定不随进度块宽度浮动，扫一眼就在同一个地方。
        // 目录尚未回来（chapterCount = 0）时也照常显示，不让读数凭空消失。
        BatteryBadge(batteryPercent, textColor)
        Spacer(Modifier.width(8.dp))
    }
}

/**
 * 阅读页常驻顶部状态条：左边章节名（居中）+ 最右电量。
 *
 * 阅读时（菜单收起）常驻于正文上方，随时可确认「本章名」；
 * 菜单展开时顶部栏已显示更完整的信息（书名 + 章节 + 来源 + 进度），
 * 此时本状态条由调用方隐藏，避免重复。
 *
 * v1.8.8.6：沉浸全屏默认打开后系统状态栏被隐藏，电量没地方看 ——
 * 就钉在这条常驻状态条的最右端，不点屏幕也能看到剩余电量。
 */
@Composable
internal fun BoxScope.ReaderChapterStatusBar(
    bookTitle: String,
    chapterTitle: String,
    textColor: Color,
) {
    if (chapterTitle.isBlank()) return
    val line = buildString {
        if (bookTitle.isNotBlank()) append(bookTitle).append(" · ")
        append(chapterTitle)
    }
    val batteryPercent = rememberBatteryPercent()
    Row(
        modifier = Modifier
            .align(Alignment.TopCenter)
            .fillMaxWidth()
            // 这里**不要**加 statusBarsPadding：正文区域本身已经避开了状态栏，
            // 这条常驻状态条正好落在「状态栏下沿」与「正文首行」之间的空隙里。
            // 加了 inset 反而把它往下推进正文标题上，两行字叠在一起。
            .padding(start = 16.dp, end = 12.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // weight + 居中：章节名占掉「扣掉电量」之后的整段，短标题也在视觉中心附近，
        // 且不会像绝对定位那样被右侧电量压住尾巴
        Text(
            text = line,
            color = textColor.copy(alpha = 0.5f),
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(8.dp))
        BatteryBadge(batteryPercent, textColor)
    }
}

/** 底部菜单：进度条 + 章节导航 + 工具行 + 页签面板 */
@Composable
internal fun BoxScope.ReaderBottomMenu(
    viewModel: NovelReaderViewModel,
    chapters: List<ChapterItem>,
    chapterPosition: Int,
    cachedIndices: Set<Int>,
    chapterQuery: String,
    chapterDesc: Boolean,
    bookmarks: List<Bookmark>,
    menuTab: Int,
    backgroundColor: Color,
    textColor: Color,
    nightMode: Boolean,
    // 界面面板所需
    fontSize: Float,
    fontFamily: Int,
    bold: Boolean,
    indent: Boolean,
    letterSpacing: Float,
    lineHeight: Float,
    paragraphSpacing: Float,
    sidePadding: Float,
    bgIndex: Int,
    pageType: Int,
    // 设置面板所需
    fullScreen: Boolean,
    hideTopBar: Boolean,
    hideBottomBar: Boolean,
    hideChapterTitle: Boolean,
    volumePage: Boolean,
    volumeUpPrev: Boolean,
    autoPage: Boolean,
    autoPageInterval: Int,
    brightness: Float,
    readSeconds: Long,
    replaceRules: List<ReplaceRule>,
    convertMode: Int,
    speaking: Boolean,
    speechRate: Float,
    onAddBookmark: () -> Unit,
) {
    val backgroundColors = if (nightMode) ReaderBackgrounds.nightColors else ReaderBackgrounds.dayColors

    Surface(
        modifier = Modifier
            .align(Alignment.BottomCenter)
            .fillMaxWidth(),
        color = backgroundColor,
        shadowElevation = 8.dp,
        shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            // ===== 面板区（目录 / 界面 / 设置）=====
            // 菜单刚弹出时是「收起」态（MENU_TAB_NONE）：只露进度条 + 工具行，
            // 不预先铺开一整列章节，点哪个页签才展开哪个。
            when (menuTab) {
                NovelReaderViewModel.MENU_TAB_NONE -> Unit
                0 -> DirectoryPanel(viewModel, chapters, chapterPosition, cachedIndices, chapterQuery, chapterDesc, textColor)
                1 -> MenuScrollPanel {
                    InterfacePanel(
                        viewModel = viewModel,
                        backgroundColors = backgroundColors,
                        fontSize = fontSize,
                        fontFamily = fontFamily,
                        bold = bold,
                        indent = indent,
                        letterSpacing = letterSpacing,
                        lineHeight = lineHeight,
                        paragraphSpacing = paragraphSpacing,
                        sidePadding = sidePadding,
                        bgIndex = bgIndex,
                        pageType = pageType,
                        textColor = textColor,
                    )
                }
                2 -> MenuScrollPanel {
                    SettingsPanel(
                        viewModel = viewModel,
                        nightMode = nightMode,
                        fullScreen = fullScreen,
                        hideTopBar = hideTopBar,
                        hideBottomBar = hideBottomBar,
                        hideChapterTitle = hideChapterTitle,
                        volumePage = volumePage,
                        volumeUpPrev = volumeUpPrev,
                        autoPage = autoPage,
                        autoPageInterval = autoPageInterval,
                        brightness = brightness,
                        readSeconds = readSeconds,
                        replaceRules = replaceRules,
                        convertMode = convertMode,
                        speaking = speaking,
                        speechRate = speechRate,
                        textColor = textColor,
                    )
                }
            }

            Spacer(Modifier.height(6.dp))

            // ===== 进度行：上一章 | 章节滑块 | 下一章 =====
            if (!hideBottomBar) {
                val maxIndex = (chapters.size - 1).coerceAtLeast(0)
                var sliderValue by remember(chapterPosition, chapters.size) {
                    mutableFloatStateOf(chapterPosition.toFloat())
                }
                val targetIndex = sliderValue.toInt().coerceIn(0, maxIndex)
                val targetTitle = chapters.getOrNull(targetIndex)?.title.orEmpty()
                Column(modifier = Modifier.fillMaxWidth()) {
                    if (chapters.isNotEmpty()) {
                        Text(
                            text = "第 ${targetIndex + 1}/${chapters.size} 章  $targetTitle",
                            color = textColor.copy(alpha = 0.75f),
                            style = MaterialTheme.typography.labelSmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                        )
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                        Text(
                            text = "上一章",
                            color = textColor,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier
                                .clickable { viewModel.prevChapter() }
                                .padding(8.dp),
                        )
                        Slider(
                            value = sliderValue,
                            onValueChange = { sliderValue = it },
                            onValueChangeFinished = {
                                val target = sliderValue.toInt().coerceIn(0, maxIndex)
                                if (chapters.isNotEmpty() && target != chapterPosition) {
                                    viewModel.switchChapter(target)
                                    viewModel.closeMenu()
                                }
                            },
                            valueRange = 0f..(if (maxIndex <= 0) 1f else maxIndex.toFloat()),
                            steps = 0,
                            modifier = Modifier
                                .weight(1f)
                                .padding(horizontal = 8.dp),
                        )
                        Text(
                            text = "下一章",
                            color = textColor,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier
                                .clickable { viewModel.nextChapter() }
                                .padding(8.dp),
                        )
                    }
                }
            }

            Spacer(Modifier.height(6.dp))

            // ===== 工具行：目录 / 界面 / 设置 / 换源 / 书签 / 缓存 / 听书 =====
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                ToolButton("目录", Icons.Filled.MenuBook, menuTab == 0, textColor) { viewModel.setMenuTab(0) }
                ToolButton("界面", Icons.Filled.TextFormat, menuTab == 1, textColor) { viewModel.setMenuTab(1) }
                ToolButton("设置", Icons.Filled.Settings, menuTab == 2, textColor) { viewModel.setMenuTab(2) }
                ToolButton("换源", Icons.Filled.SwapHoriz, false, textColor) { viewModel.openChangeSource() }
                ToolButton("书签", Icons.Filled.BookmarkBorder, menuTab == 3, textColor) { viewModel.setMenuTab(3) }
                ToolButton("缓存", Icons.Filled.Download, false, textColor) { viewModel.cacheCurrentChapter() }
                // 听书（v1.8.7.4）：朗读原先只藏在「设置」页签里，得先展开菜单再切页签才能找到。
                // 这是阅读时的高频动作，提到工具行做成一键开关；正在朗读时图标变方块、高亮显示。
                ToolButton(
                    label = if (speaking) "停止" else "听书",
                    icon = if (speaking) Icons.Filled.Stop else Icons.Filled.Headphones,
                    selected = speaking,
                    textColor = textColor,
                ) {
                    if (speaking) viewModel.stopSpeaking() else viewModel.speakCurrentChapter()
                }
            }
            Spacer(Modifier.height(4.dp))
        }
    }

    // 书签面板（独立覆盖层，因为工具行里的「书签」不占用页签）
    if (menuTab == 3) {
        BookmarkPanel(
            bookmarks = bookmarks,
            backgroundColor = backgroundColor,
            textColor = textColor,
            onAddCurrent = onAddBookmark,
            onJump = { viewModel.jumpToBookmark(it) },
            onDelete = { viewModel.removeBookmark(it) },
            onClose = { viewModel.closeMenuPanel() },
        )
    }
}

@Composable
private fun ToolButton(
    label: String,
    icon: ImageVector,
    selected: Boolean,
    textColor: Color,
    onClick: () -> Unit,
) {
    val (bg, fg) = chipColors(selected, textColor)
    Column(
        modifier = Modifier
            .background(bg, RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, contentDescription = label, tint = fg, modifier = Modifier.size(20.dp))
        Spacer(Modifier.height(2.dp))
        Text(text = label, color = fg, style = MaterialTheme.typography.labelSmall)
    }
}

/** 目录面板：搜索 + 正倒序 + 列表（已缓存标记 / 当前高亮 + 快速定位条） */
@Composable
private fun DirectoryPanel(
    viewModel: NovelReaderViewModel,
    chapters: List<ChapterItem>,
    chapterPosition: Int,
    cachedIndices: Set<Int>,
    chapterQuery: String,
    chapterDesc: Boolean,
    textColor: Color,
) {
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    // 过滤 + 排序（保留原始下标，点击用下标定位，避免源 index 漂移）
    val visible = remember(chapters, chapterQuery, chapterDesc) {
        val base = chapters.withIndex().toList()
        val filtered = if (chapterQuery.isBlank()) base
        else base.filter { it.value.title.contains(chapterQuery, ignoreCase = true) }
        if (chapterDesc) filtered.asReversed() else filtered
    }

    LaunchedEffect(chapters, chapterPosition) {
        if (chapters.isNotEmpty() && chapterQuery.isBlank()) {
            listState.scrollToItem(chapterPosition.coerceIn(0, chapters.size - 1))
        }
    }

    Column {
        OutlinedTextField(
            value = chapterQuery,
            onValueChange = viewModel::setChapterQuery,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp),
            singleLine = true,
            placeholder = { Text("搜索章节名", style = MaterialTheme.typography.bodySmall) },
            textStyle = MaterialTheme.typography.bodyMedium,
            trailingIcon = {
                if (chapterQuery.isNotEmpty()) {
                    Text(
                        "清空",
                        color = AccentColor,
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier
                            .clickable { viewModel.setChapterQuery("") }
                            .padding(8.dp),
                    )
                }
            },
        )
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "目录（${visible.size}/${chapters.size}章）",
                color = textColor,
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.weight(1f),
            )
            MenuChip(if (chapterDesc) "倒序" else "正序", selected = chapterDesc, textColor = textColor) {
                viewModel.toggleChapterOrder()
            }
        }
        Spacer(Modifier.height(4.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.45f),
        ) {
            LazyColumn(state = listState, modifier = Modifier.weight(1f)) {
                items(visible, key = { "${it.index}_${it.value.url}" }) { entry ->
                    val index = entry.index
                    val ch = entry.value
                    val isCurrent = index == chapterPosition
                    val cached = ch.index in cachedIndices
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                viewModel.switchChapter(index)
                                viewModel.closeMenu()
                            }
                            .padding(vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = ch.title,
                            color = if (isCurrent) AccentColor else textColor,
                            fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f),
                        )
                        if (cached) {
                            Text(
                                text = "已缓存",
                                color = AccentColor.copy(alpha = 0.85f),
                                style = MaterialTheme.typography.labelSmall,
                                modifier = Modifier.padding(start = 6.dp),
                            )
                        }
                    }
                }
            }
            // 快速定位条
            if (visible.size > 1) {
                Column(
                    modifier = Modifier
                        .width(36.dp)
                        .fillMaxHeight()
                        .padding(vertical = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxHeight()
                            .width(10.dp)
                            .background(textColor.copy(alpha = 0.12f), RoundedCornerShape(5.dp)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Box(Modifier.size(10.dp).background(AccentColor, CircleShape))
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .pointerInput(visible.size) {
                                    detectVerticalDragGestures(
                                        onDragStart = { offset ->
                                            val ratio = (offset.y / size.height).coerceIn(0f, 1f)
                                            val target = (ratio * (visible.size - 1)).toInt().coerceIn(0, visible.size - 1)
                                            scope.launch { listState.scrollToItem(target) }
                                        },
                                        onVerticalDrag = { change, _ ->
                                            val ratio = (change.position.y / size.height).coerceIn(0f, 1f)
                                            val target = (ratio * (visible.size - 1)).toInt().coerceIn(0, visible.size - 1)
                                            scope.launch { listState.scrollToItem(target) }
                                        },
                                    )
                                },
                        )
                    }
                }
            }
        }
    }
}

/** 界面面板：字号 / 字体 / 加粗 / 缩进 / 字间距 / 行距 / 段距 / 页边距 / 背景 / 翻页方式 */
@Composable
private fun InterfacePanel(
    viewModel: NovelReaderViewModel,
    backgroundColors: List<ReaderBackgrounds.ReaderColor>,
    fontSize: Float,
    fontFamily: Int,
    bold: Boolean,
    indent: Boolean,
    letterSpacing: Float,
    lineHeight: Float,
    paragraphSpacing: Float,
    sidePadding: Float,
    bgIndex: Int,
    pageType: Int,
    textColor: Color,
) {
    // 字号
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("字号", color = textColor, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
        MenuButton("A-", textColor) { viewModel.decreaseFontSize() }
        Text(
            text = "${fontSize.toInt()}",
            color = textColor,
            modifier = Modifier.padding(horizontal = 14.dp),
        )
        MenuButton("A+", textColor) { viewModel.increaseFontSize() }
    }
    Spacer(Modifier.height(10.dp))
    // 字体
    Text("字体", color = textColor, style = MaterialTheme.typography.titleSmall)
    Spacer(Modifier.height(6.dp))
    Row {
        MenuChip("系统", fontFamily == PreferencesManager.FONT_FAMILY_DEFAULT, textColor) { viewModel.setFontFamily(PreferencesManager.FONT_FAMILY_DEFAULT) }
        Spacer(Modifier.width(8.dp))
        MenuChip("衬线", fontFamily == PreferencesManager.FONT_FAMILY_SERIF, textColor) { viewModel.setFontFamily(PreferencesManager.FONT_FAMILY_SERIF) }
        Spacer(Modifier.width(8.dp))
        MenuChip("等宽", fontFamily == PreferencesManager.FONT_FAMILY_MONO, textColor) { viewModel.setFontFamily(PreferencesManager.FONT_FAMILY_MONO) }
    }
    Spacer(Modifier.height(10.dp))
    SettingSwitch("加粗", bold, textColor) { viewModel.setBold(it) }
    SettingSwitch("首行缩进", indent, textColor) { viewModel.setIndent(it) }
    SettingSlider(
        label = "字间距",
        value = letterSpacing,
        display = String.format("%.1f", letterSpacing),
        valueRange = 0f..3f,
        textColor = textColor,
        onChange = { viewModel.setLetterSpacing(it) },
    )
    SettingSlider(
        label = "行距",
        value = lineHeight,
        display = String.format("%.1f", lineHeight),
        valueRange = 1.0f..2.6f,
        textColor = textColor,
        onChange = { viewModel.setLineHeight(it) },
    )
    SettingSlider(
        label = "段距",
        value = paragraphSpacing,
        display = "${paragraphSpacing.toInt()}",
        valueRange = 0f..32f,
        textColor = textColor,
        onChange = { viewModel.setParagraphSpacing(it) },
    )
    SettingSlider(
        label = "页边距",
        value = sidePadding,
        display = "${sidePadding.toInt()}",
        valueRange = 8f..48f,
        textColor = textColor,
        onChange = { viewModel.setSidePadding(it) },
    )
    Spacer(Modifier.height(8.dp))
    // 背景颜色
    Text("背景颜色", color = textColor, style = MaterialTheme.typography.titleSmall)
    Spacer(Modifier.height(8.dp))
    Row {
        backgroundColors.forEachIndexed { index, rc ->
            val selectedRing = index == bgIndex
            Box(
                modifier = Modifier
                    .padding(end = 12.dp)
                    .size(36.dp)
                    .background(rc.background, CircleShape)
                    .clickable { viewModel.setBackground(index) },
                contentAlignment = Alignment.Center,
            ) {
                if (selectedRing) {
                    Box(
                        Modifier
                            .size(16.dp)
                            .background(AccentColor, CircleShape),
                    )
                }
            }
        }
    }
    Spacer(Modifier.height(12.dp))
    // 翻页方式
    Text("翻页方式", color = textColor, style = MaterialTheme.typography.titleSmall)
    Spacer(Modifier.height(6.dp))
    Row {
        MenuChip("覆盖", pageType == PreferencesManager.PAGE_TYPE_COVER, textColor) { viewModel.setPageType(PreferencesManager.PAGE_TYPE_COVER) }
        Spacer(Modifier.width(8.dp))
        MenuChip("仿真", pageType == PreferencesManager.PAGE_TYPE_SIMULATE, textColor) { viewModel.setPageType(PreferencesManager.PAGE_TYPE_SIMULATE) }
        Spacer(Modifier.width(8.dp))
        MenuChip("滑动", pageType == PreferencesManager.PAGE_TYPE_SLIDE, textColor) { viewModel.setPageType(PreferencesManager.PAGE_TYPE_SLIDE) }
    }
    Spacer(Modifier.height(8.dp))
    Row {
        MenuChip("滚动", pageType == PreferencesManager.PAGE_TYPE_SCROLL, textColor) { viewModel.setPageType(PreferencesManager.PAGE_TYPE_SCROLL) }
        Spacer(Modifier.width(8.dp))
        MenuChip("上下", pageType == PreferencesManager.PAGE_TYPE_VERTICAL, textColor) { viewModel.setPageType(PreferencesManager.PAGE_TYPE_VERTICAL) }
        Spacer(Modifier.width(8.dp))
        MenuChip("无动画", pageType == PreferencesManager.PAGE_TYPE_NO_ANIM, textColor) { viewModel.setPageType(PreferencesManager.PAGE_TYPE_NO_ANIM) }
    }
    Spacer(Modifier.height(8.dp))
}

/** 设置面板：夜间 / 隐藏栏目 / 全屏 / 音量键 / 自动翻页 / 亮度 / 净化 / 阅读时长 */
@Composable
private fun SettingsPanel(
    viewModel: NovelReaderViewModel,
    nightMode: Boolean,
    fullScreen: Boolean,
    hideTopBar: Boolean,
    hideBottomBar: Boolean,
    hideChapterTitle: Boolean,
    volumePage: Boolean,
    volumeUpPrev: Boolean,
    autoPage: Boolean,
    autoPageInterval: Int,
    brightness: Float,
    readSeconds: Long,
    replaceRules: List<ReplaceRule>,
    convertMode: Int,
    speaking: Boolean,
    speechRate: Float,
    textColor: Color,
) {
    SettingSwitch("夜间模式", nightMode, textColor) { viewModel.toggleNightMode() }
    SettingSwitch("沉浸式全屏", fullScreen, textColor) { viewModel.setFullScreen(it) }
    SettingSwitch("隐藏顶部栏", hideTopBar, textColor) { viewModel.setHideTopBar(it) }
    SettingSwitch("隐藏进度栏", hideBottomBar, textColor) { viewModel.setHideBottomBar(it) }
    SettingSwitch("隐藏章节标题", hideChapterTitle, textColor) { viewModel.setHideChapterTitle(it) }
    SettingSwitch("音量键翻页", volumePage, textColor) { viewModel.setVolumePage(it) }
    if (volumePage) {
        SettingSwitch("音量上键=上一页", volumeUpPrev, textColor) { viewModel.setVolumeUpPrev(it) }
    }
    SettingSwitch("自动翻页", autoPage, textColor) { viewModel.setAutoPage(it) }
    if (autoPage) {
        SettingSlider(
            label = "翻页间隔(秒)",
            value = autoPageInterval.toFloat(),
            display = "$autoPageInterval",
            valueRange = 5f..120f,
            textColor = textColor,
            onChange = { viewModel.setAutoPageInterval(it.toInt()) },
        )
    }
    // 朗读 / 听书（对标开源阅读 ReadAloud）
    Spacer(Modifier.height(10.dp))
    Text("朗读", color = textColor, style = MaterialTheme.typography.titleSmall)
    Spacer(Modifier.height(6.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        MenuChip(
            if (speaking) "停止朗读" else "朗读本章",
            speaking,
            textColor,
        ) {
            if (speaking) viewModel.stopSpeaking() else viewModel.speakCurrentChapter()
        }
    }
    Spacer(Modifier.height(6.dp))
    SettingSlider(
        label = "语速",
        value = speechRate,
        display = String.format("%.1f", speechRate),
        valueRange = 0.5f..2.0f,
        textColor = textColor,
        onChange = { viewModel.setSpeechRate(it) },
    )
    Spacer(Modifier.height(8.dp))

    // 简繁转换（对标开源阅读 ChineseConverter）
    Spacer(Modifier.height(10.dp))
    Text("简繁转换", color = textColor, style = MaterialTheme.typography.titleSmall)
    Spacer(Modifier.height(6.dp))
    Row {
        MenuChip(
            "不转换",
            convertMode == PreferencesManager.CONVERT_NONE,
            textColor,
        ) { viewModel.setConvertMode(PreferencesManager.CONVERT_NONE) }
        Spacer(Modifier.width(8.dp))
        MenuChip(
            "简转繁",
            convertMode == PreferencesManager.CONVERT_TO_TRADITIONAL,
            textColor,
        ) { viewModel.setConvertMode(PreferencesManager.CONVERT_TO_TRADITIONAL) }
        Spacer(Modifier.width(8.dp))
        MenuChip(
            "繁转简",
            convertMode == PreferencesManager.CONVERT_TO_SIMPLIFIED,
            textColor,
        ) { viewModel.setConvertMode(PreferencesManager.CONVERT_TO_SIMPLIFIED) }
    }
    Spacer(Modifier.height(8.dp))
    SettingSlider(
        label = if (brightness < 0f) "亮度（跟随系统）" else "亮度",
        value = if (brightness < 0f) 0.5f else brightness,
        display = if (brightness < 0f) "自动" else "${(brightness * 100).toInt()}%",
        valueRange = 0.05f..1f,
        textColor = textColor,
        onChange = { viewModel.setBrightness(it) },
    )
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("亮度跟随系统", color = textColor, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Switch(
            checked = brightness < 0f,
            onCheckedChange = { v -> viewModel.setBrightness(if (v) -1f else 0.5f) },
            colors = readerSwitchColors(textColor),
        )
    }
    Spacer(Modifier.height(8.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f)) {
            Text("正文净化", color = textColor, style = MaterialTheme.typography.titleSmall)
            Text(
                text = if (replaceRules.isEmpty()) "未配置替换规则" else "已启用 ${replaceRules.size} 条替换规则",
                color = textColor.copy(alpha = 0.6f),
                style = MaterialTheme.typography.labelSmall,
            )
        }
        MenuChip("配置", false, textColor) { viewModel.openReplaceRules() }
    }
    Spacer(Modifier.height(10.dp))
    Text(
        text = "累计阅读时长：${formatDuration(readSeconds)}",
        color = textColor.copy(alpha = 0.75f),
        style = MaterialTheme.typography.labelMedium,
    )
    Spacer(Modifier.height(8.dp))
}

/** 书签面板（覆盖层）：添加当前书签 + 列表跳转/删除 */
@Composable
private fun BoxScope.BookmarkPanel(
    bookmarks: List<Bookmark>,
    backgroundColor: Color,
    textColor: Color,
    onAddCurrent: () -> Unit,
    onJump: (Bookmark) -> Unit,
    onDelete: (Long) -> Unit,
    onClose: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.4f))
            .clickable(onClick = onClose),
    ) {
        Surface(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .fillMaxHeight(0.7f),
            color = backgroundColor,
            shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "书签（${bookmarks.size}）",
                        color = textColor,
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f),
                    )
                    MenuChip("添加当前书签", false, textColor) { onAddCurrent() }
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "关闭",
                        color = AccentColor,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier
                            .clickable(onClick = onClose)
                            .padding(8.dp),
                    )
                }
                Spacer(Modifier.height(8.dp))
                if (bookmarks.isEmpty()) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("暂无书签，点上方「添加当前书签」", color = textColor.copy(alpha = 0.7f))
                    }
                } else {
                    LazyColumn(modifier = Modifier.fillMaxSize()) {
                        items(bookmarks, key = { it.id }) { bm ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onJump(bm) }
                                    .padding(vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = bm.chapterTitle.ifBlank { "第 ${bm.chapterIndex + 1} 章" },
                                        color = textColor,
                                        style = MaterialTheme.typography.bodyMedium,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    if (bm.snippet.isNotBlank()) {
                                        Text(
                                            text = bm.snippet,
                                            color = textColor.copy(alpha = 0.6f),
                                            style = MaterialTheme.typography.labelSmall,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                }
                                Text(
                                    text = "删除",
                                    color = readerErrorColor(textColor),
                                    style = MaterialTheme.typography.labelMedium,
                                    modifier = Modifier
                                        .clickable { onDelete(bm.id) }
                                        .padding(8.dp),
                                )
                            }
                            HorizontalDivider(color = textColor.copy(alpha = 0.15f))
                        }
                    }
                }
            }
        }
    }
}

/** 正文净化规则配置面板（覆盖层） */
@Composable
internal fun BoxScope.TextReplacePanel(
    rules: List<ReplaceRule>,
    backgroundColor: Color,
    textColor: Color,
    onSave: (List<ReplaceRule>) -> Unit,
    onClose: () -> Unit,
) {
    var localRules by remember(rules) { mutableStateOf(rules) }
    var pattern by remember { mutableStateOf("") }
    var replacement by remember { mutableStateOf("") }
    var isRegex by remember { mutableStateOf(false) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.45f))
            .clickable(onClick = onClose),
    ) {
        Surface(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .fillMaxHeight(0.82f),
            color = backgroundColor,
            shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("正文净化 / 替换规则", color = textColor, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    Text(
                        "完成",
                        color = AccentColor,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier
                            .clickable {
                                onSave(localRules)
                                onClose()
                            }
                            .padding(8.dp),
                    )
                }
                Spacer(Modifier.height(8.dp))
                // 新增规则
                OutlinedTextField(
                    value = pattern,
                    onValueChange = { pattern = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("匹配内容（正则或普通文本）", style = MaterialTheme.typography.labelSmall) },
                    textStyle = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.height(6.dp))
                OutlinedTextField(
                    value = replacement,
                    onValueChange = { replacement = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("替换为（留空=删除）", style = MaterialTheme.typography.labelSmall) },
                    textStyle = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("正则匹配", color = textColor, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                    Switch(checked = isRegex, onCheckedChange = { isRegex = it }, colors = readerSwitchColors(textColor))
                }
                Spacer(Modifier.height(6.dp))
                MenuChip("添加规则", true, textColor) {
                    if (pattern.isNotBlank()) {
                        localRules = localRules + ReplaceRule(pattern, replacement, isRegex)
                        pattern = ""
                        replacement = ""
                        isRegex = false
                        onSave(localRules)
                    }
                }
                Spacer(Modifier.height(8.dp))
                HorizontalDivider(color = textColor.copy(alpha = 0.15f))
                if (localRules.isEmpty()) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("暂无规则", color = textColor.copy(alpha = 0.7f))
                    }
                } else {
                    LazyColumn(modifier = Modifier.fillMaxSize()) {
                        items(localRules) { rule ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = rule.pattern,
                                        color = textColor,
                                        style = MaterialTheme.typography.bodySmall,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Text(
                                        text = (if (rule.isRegex) "正则 → " else "文本 → ") +
                                            rule.replacement.ifBlank { "(删除)" },
                                        color = textColor.copy(alpha = 0.6f),
                                        style = MaterialTheme.typography.labelSmall,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                                Text(
                                    text = "删除",
                                    color = readerErrorColor(textColor),
                                    style = MaterialTheme.typography.labelMedium,
                                    modifier = Modifier
                                        .clickable {
                                            localRules = localRules - rule
                                            onSave(localRules)
                                        }
                                        .padding(8.dp),
                                )
                            }
                            HorizontalDivider(color = textColor.copy(alpha = 0.12f))
                        }
                    }
                }
            }
        }
    }
}

/** 换源面板（覆盖层）：搜索同名书 + 同作者筛选 + 候选列表（支持分页加载更多） */
@Composable
internal fun BoxScope.ChangeSourcePanel(
    candidates: List<MediaItem>,
    loading: Boolean,
    sameAuthorOnly: Boolean,
    error: String?,
    hasMore: Boolean,
    loadingMore: Boolean,
    progress: String?,
    backgroundColor: Color,
    textColor: Color,
    onToggleSameAuthor: (Boolean) -> Unit,
    onPick: (MediaItem) -> Unit,
    onLoadMore: () -> Unit,
    onClose: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.4f))
            .clickable(onClick = onClose),
    ) {
        Surface(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .fillMaxHeight(0.78f),
            color = backgroundColor,
            shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("换源", color = textColor, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    Text(
                        "关闭",
                        color = AccentColor,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier
                            .clickable(onClick = onClose)
                            .padding(8.dp),
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("仅同作者", color = textColor, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    Switch(checked = sameAuthorOnly, onCheckedChange = onToggleSameAuthor, colors = readerSwitchColors(textColor))
                }
                // 搜索进度反馈（边搜边显示）
                if (progress != null && loading) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 4.dp)) {
                        CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp, color = AccentColor)
                        Spacer(Modifier.width(8.dp))
                        Text(progress, color = AccentColor, style = MaterialTheme.typography.labelMedium)
                    }
                }
                Spacer(Modifier.height(4.dp))
                when {
                    // 还没有候选时才整屏转圈；有候选则列表常显（上方已有进度行）
                    loading && candidates.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                    error != null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(error, color = readerErrorColor(textColor), modifier = Modifier.padding(16.dp))
                    }
                    candidates.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("未找到可切换的同名书源", color = textColor)
                    }
                    else -> {
                        LazyColumn(modifier = Modifier.fillMaxSize()) {
                            items(candidates, key = { "${it.sourceId}_${it.id}" }) { cand ->
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { onPick(cand) }
                                        .padding(vertical = 10.dp),
                                ) {
                                    Text(
                                        text = cand.title,
                                        color = textColor,
                                        style = MaterialTheme.typography.bodyLarge,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        cand.author?.let {
                                            Text(
                                                text = it,
                                                color = textColor.copy(alpha = 0.55f),
                                                style = MaterialTheme.typography.bodySmall,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                        }
                                        if (cand.sourceName.isNotBlank()) {
                                            Spacer(Modifier.width(8.dp))
                                            Text(
                                                text = "· ${cand.sourceName}",
                                                color = AccentColor,
                                                style = MaterialTheme.typography.labelSmall,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                        }
                                    }
                                    val meta = BookMeta.summaryLine(cand)
                                    if (meta.isNotEmpty()) {
                                        Text(
                                            text = meta,
                                            color = textColor.copy(alpha = 0.45f),
                                            style = MaterialTheme.typography.labelSmall,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                }
                                HorizontalDivider(color = textColor.copy(alpha = 0.15f))
                            }
                            if (hasMore || loadingMore) {
                                item(key = "load_more") {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .then(if (loadingMore) Modifier else Modifier.clickable { onLoadMore() })
                                            .padding(vertical = 14.dp),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        if (loadingMore) {
                                            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                                        } else {
                                            Text("加载更多书源", color = AccentColor, style = MaterialTheme.typography.bodyMedium)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** 面板滚动容器（限高，避免小屏溢出） */
@Composable
internal fun MenuScrollPanel(content: @Composable () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 340.dp)
            .verticalScroll(rememberScrollState()),
    ) {
        content()
    }
}

/** 普通按钮（无选中态）。同样必须跟随阅读页配色，理由见 [SettingSwitch]。 */
@Composable
private fun MenuButton(text: String, textColor: Color, onClick: () -> Unit) {
    Text(
        text = text,
        color = textColor,
        fontSize = 15.sp,
        modifier = Modifier
            .background(textColor.copy(alpha = 0.10f), RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

/** 芯片按钮（带选中态） */
@Composable
private fun MenuChip(text: String, selected: Boolean, textColor: Color, onClick: () -> Unit) {
    val (bg, fg) = chipColors(selected, textColor)
    Text(
        text = text,
        color = fg,
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier
            .background(bg, RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 7.dp),
    )
}

/**
 * 阅读页菜单内开关的统一配色。
 *
 * 菜单面板铺的是**阅读页纸张色**（不是应用主题的 surface），所以开关也必须跟着纸张走：
 * 默认配色取的是应用主题的 surfaceVariant / outline —— 浅色主题下是一组浅灰，
 * 压到夜间纸张上过亮、很刺眼；反过来深色主题配浅色纸张又会糊成一片。
 */
@Composable
private fun readerSwitchColors(textColor: Color) = SwitchDefaults.colors(
    checkedThumbColor = Color.White,
    uncheckedThumbColor = textColor.copy(alpha = 0.6f),
    uncheckedTrackColor = textColor.copy(alpha = 0.15f),
    uncheckedBorderColor = textColor.copy(alpha = 0.35f),
)

/**
 * 错误提示色：跟随纸张明暗。
 *
 * Material 的 error 是浅色主题设计的深红，压在深色纸张上对比度太低（几乎看不清）；
 * 反过来深色主题的浅红落在浅色纸张上也会发飘。按正文色的明暗二选一。
 */
private fun readerErrorColor(textColor: Color): Color {
    val luminance = 0.299f * textColor.red + 0.587f * textColor.green + 0.114f * textColor.blue
    // 正文色偏亮 → 纸张是深色 → 用亮红；正文色偏暗 → 纸张是浅色 → 用深红
    return if (luminance > 0.5f) Color(0xFFFF8A80) else Color(0xFFB3261E)
}

/** 开关行 */
@Composable
private fun SettingSwitch(label: String, checked: Boolean, textColor: Color, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 必须用阅读页的文字色，**不能**用 MaterialTheme.colorScheme.onSurface：
        // 那是应用主题色（浅色主题下近黑），压到夜间纸张上就成了「深灰字 + 深底色」，
        // 夜间模式下整排开关标签几乎看不见。
        Text(text = label, color = textColor, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            colors = readerSwitchColors(textColor),
        )
    }
}

/** 滑块行 */
@Composable
private fun SettingSlider(
    label: String,
    value: Float,
    display: String,
    valueRange: ClosedFloatingPointRange<Float>,
    textColor: Color,
    onChange: (Float) -> Unit,
) {
    var local by remember(value) { mutableFloatStateOf(value) }
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(text = label, color = textColor, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            Text(text = display, color = textColor.copy(alpha = 0.7f), style = MaterialTheme.typography.bodySmall)
        }
        Slider(
            value = local,
            onValueChange = { local = it },
            onValueChangeFinished = { onChange(local) },
            valueRange = valueRange,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/**
 * 正文搜索面板（覆盖层）：在当前章节正文中查找关键词，支持上/下跳转。
 *
 * 对标开源阅读 SearchMenu 的「章节内搜索」能力。
 * 匹配下标由 ViewModel 基于原文计算，简繁转换为逐字映射（长度不变），
 * 因此高亮位置在转换后依然有效。
 */
@Composable
fun ContentSearchPanel(
    viewModel: NovelReaderViewModel,
    backgroundColor: Color,
    textColor: Color,
    onClose: () -> Unit,
) {
    val query by viewModel.contentQuery.collectAsState()
    val matches by viewModel.contentMatches.collectAsState()
    val currentIndex by viewModel.currentMatchIndex.collectAsState()

    // 搜索范围：false=本章，true=全书（v1.3.0.0 对标开源阅读「搜索全书」）
    var wholeBook by remember { mutableStateOf(false) }
    val bookResults by viewModel.bookSearchResults.collectAsState()
    val bookProgress by viewModel.bookSearchProgress.collectAsState()
    var bookInput by remember { mutableStateOf("") }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.5f))
            .clickable { onClose() },
    ) {
        Surface(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp)
                .clickable { /* 消费点击，避免穿透到遮罩触发关闭 */ },
            color = backgroundColor,
            shape = RoundedCornerShape(12.dp),
            shadowElevation = 8.dp,
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "正文搜索",
                        color = textColor,
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = "关闭",
                        color = AccentColor,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier
                            .clickable { onClose() }
                            .padding(8.dp),
                    )
                }
                // 范围切换：本章 / 全书
                Row(verticalAlignment = Alignment.CenterVertically) {
                    MenuChip("本章", !wholeBook, textColor) { wholeBook = false }
                    Spacer(Modifier.width(8.dp))
                    MenuChip("全书", wholeBook, textColor) { wholeBook = true }
                }
                Spacer(Modifier.height(8.dp))
                if (!wholeBook) {
                    OutlinedTextField(
                        value = query,
                        onValueChange = { viewModel.updateContentQuery(it) },
                        placeholder = { Text("输入要查找的内容") },
                        maxLines = 1,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(10.dp),
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = when {
                                query.isBlank() -> "输入关键词开始搜索"
                                matches.isEmpty() -> "本章未找到「$query」"
                                else -> "第 ${currentIndex + 1}/${matches.size} 个匹配"
                            },
                            color = textColor.copy(alpha = 0.75f),
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.weight(1f),
                        )
                        if (matches.isNotEmpty()) {
                            Text(
                                text = "上一个",
                                color = AccentColor,
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier
                                    .clickable { viewModel.prevMatch() }
                                    .padding(horizontal = 8.dp, vertical = 4.dp),
                            )
                            Text(
                                text = "下一个",
                                color = AccentColor,
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier
                                    .clickable { viewModel.nextMatch() }
                                    .padding(horizontal = 8.dp, vertical = 4.dp),
                            )
                        }
                    }
                } else {
                    // 全书搜索：输入 + 触发按钮 + 结果列表
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = bookInput,
                            onValueChange = { bookInput = it },
                            placeholder = { Text("搜索全书内容") },
                            maxLines = 1,
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(10.dp),
                        )
                        Text(
                            text = "搜索",
                            color = AccentColor,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier
                                .clickable {
                                    if (bookInput.isNotBlank()) viewModel.searchWholeBook(bookInput.trim())
                                }
                                .padding(horizontal = 10.dp, vertical = 6.dp),
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = when {
                            bookProgress != null -> bookProgress!!
                            bookInput.isBlank() -> "输入关键词搜索全书"
                            bookResults.isEmpty() -> "暂无结果"
                            else -> "共 ${bookResults.size} 处匹配，点击跳转"
                        },
                        color = textColor.copy(alpha = 0.75f),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    if (bookResults.isNotEmpty()) {
                        Spacer(Modifier.height(6.dp))
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 380.dp),
                        ) {
                            items(bookResults) { r ->
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { viewModel.gotoBookResult(r) }
                                        .padding(vertical = 6.dp),
                                ) {
                                    Text(
                                        text = r.chapterTitle,
                                        color = textColor,
                                        style = MaterialTheme.typography.titleSmall,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Text(
                                        text = r.excerpt,
                                        color = textColor.copy(alpha = 0.7f),
                                        style = MaterialTheme.typography.bodySmall,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                                HorizontalDivider(color = textColor.copy(alpha = 0.1f))
                            }
                        }
                    }
                }
            }
        }
    }
}

/** 秒 → 「x 小时 y 分」/「y 分钟」 */
internal fun formatDuration(seconds: Long): String {
    if (seconds <= 0) return "0 分钟"
    val h = seconds / 3600
    val m = (seconds % 3600) / 60
    return if (h > 0) "$h 小时 $m 分钟" else "${m.coerceAtLeast(1)} 分钟"
}
