package com.novelreader.ui.screens.pdf

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
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
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.InvertColors
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.material.icons.filled.ZoomOut
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.novelreader.core.pdf.PdfPageJump
import com.novelreader.ui.screens.rss.openInBrowser
import kotlinx.coroutines.launch

/**
 * 应用内 PDF 阅读页（v1.8.5.1）。
 *
 * 交互模型刻意做得像「看图」而不是「看文档」：
 * - **一页一屏**：整页按比例塞进屏幕（`ContentScale.Fit`），横向滑动翻页；
 * - **双指缩放 / 单指拖动**：放大到 4 倍看正文小字，缩放后翻页让位给拖动；
 * - **底部常驻页码**：论文动辄十几页，没有页码会不知道读到哪。
 *
 * 没做「按宽度铺满 + 纵向滚动」：那套在手机上正文只剩半屏宽，
 * 双栏排版的期刊每栏窄到必须横向滚动，反而更难读。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PdfViewerScreen(
    viewModel: PdfViewerViewModel,
    onBack: () -> Unit,
) {
    val state by viewModel.state.collectAsState()
    val context = LocalContext.current
    // 显式标注 () -> Unit：两个分支分别是 Result<Unit> 和 Unit，不标注会被推成 () -> Any
    val openInBrowserAction: () -> Unit = {
        // 优先给本地文件：出版方那边可能还在拦，本地这份已经拿到手了
        val local = viewModel.localFile()
        if (local != null) {
            runCatching {
                val uri = androidx.core.content.FileProvider.getUriForFile(
                    context,
                    context.packageName + ".fileprovider",
                    local,
                )
                val intent = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, "application/pdf")
                    addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    // 从 Application context 起 Activity 必须带 NEW_TASK
                    addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
            }.onFailure { openInBrowser(context, viewModel.url) }
        } else {
            openInBrowser(context, viewModel.url)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = viewModel.title.ifBlank { "PDF" },
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
                    // 夜间反色（待办 #23）：写的是全局偏好，退出再进来还保持，
                    // 设置页里也有同一个开关，两边同源
                    IconButton(onClick = { viewModel.toggleNightInvert() }) {
                        Icon(
                            imageVector = Icons.Filled.InvertColors,
                            contentDescription = if (state.nightInvert) "关闭夜间反色" else "开启夜间反色",
                            tint = if (state.nightInvert) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                    }
                    if (viewModel.url.isNotBlank()) {
                        IconButton(onClick = openInBrowserAction) {
                            Icon(
                                Icons.AutoMirrored.Filled.OpenInNew,
                                contentDescription = "用其它应用打开",
                            )
                        }
                    }
                },
            )
        },
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            when (state.stage) {
                PdfStage.Preparing, PdfStage.Downloading, PdfStage.Opening ->
                    DownloadingPane(state)

                PdfStage.Failed ->
                    FailedPane(
                        message = state.error,
                        onRetry = { viewModel.load() },
                        onBrowser = openInBrowserAction,
                    )

                PdfStage.Ready ->
                    ReadyPane(
                        viewModel = viewModel,
                        pageCount = state.pageCount,
                        nightInvert = state.nightInvert,
                    )
            }
        }
    }
}

/** 下载 / 打开中 */
@Composable
private fun DownloadingPane(state: PdfUiState) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 40.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        val progress = state.progress
        if (progress == null) {
            // 服务端不给 Content-Length（分块传输）时百分比没有意义，
            // 用不定态进度条 + 已下载量，避免出现「下了 7MB 还显示 0%」的假进度
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        } else {
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Spacer(Modifier.height(14.dp))
        Text(
            text = when (state.stage) {
                PdfStage.Preparing -> "正在准备…"
                PdfStage.Opening -> "正在打开…"
                else -> buildString {
                    append("正在下载 PDF")
                    if (progress != null) append(" ${(progress * 100).toInt()}%")
                    if (state.downloadedBytes > 0) {
                        append("（${formatBytes(state.downloadedBytes)}")
                        if (state.totalBytes > 0) append(" / ${formatBytes(state.totalBytes)}")
                        append("）")
                    }
                }
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = "首次打开需要下载，之后同一篇会直接用本地缓存",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** 失败：说清原因 + 两条退路 */
@Composable
private fun FailedPane(
    message: String,
    onRetry: () -> Unit,
    onBrowser: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "打不开这份 PDF",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = message.ifBlank { "未知原因" },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(20.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            TextButton(onClick = onRetry) {
                Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
                Text("重试")
            }
            TextButton(onClick = onBrowser) {
                Icon(
                    Icons.AutoMirrored.Filled.OpenInNew,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(4.dp))
                Text("用浏览器打开")
            }
        }
    }
}

/** 可翻页 + 可缩放的正文 */
@Composable
private fun ReadyPane(
    viewModel: PdfViewerViewModel,
    pageCount: Int,
    nightInvert: Boolean,
) {
    val pagerState = rememberPagerState(pageCount = { pageCount })
    val scope = rememberCoroutineScope()

    // 跳页对话框（待办 #23）：页码从只读文本变成可点按钮
    var showJump by remember { mutableStateOf(false) }

    // 渲染分辨率：屏宽 1.5 倍。整页 Fit 显示时是缩小采样（字更锐），
    // 放大到 1.5 倍仍然清晰；再往上会略糊，但换来的是单页 12MB 的内存上限
    val density = LocalDensity.current
    val screenWidthDp = LocalConfiguration.current.screenWidthDp
    val renderWidth = remember(screenWidthDp) {
        with(density) { (screenWidthDp.dp.toPx() * 1.5f).toInt() }.coerceIn(720, 2400)
    }

    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var viewport by remember { mutableStateOf(IntSize.Zero) }

    fun clampOffset(candidate: Offset, currentScale: Float): Offset {
        val maxX = viewport.width * (currentScale - 1f) / 2f
        val maxY = viewport.height * (currentScale - 1f) / 2f
        return Offset(
            x = candidate.x.coerceIn(-maxX, maxX),
            y = candidate.y.coerceIn(-maxY, maxY),
        )
    }

    // 换页即复位缩放：上一页放大在右下角，翻到下一页还停在那儿会莫名空白
    LaunchedEffect(pagerState.currentPage) {
        scale = 1f
        offset = Offset.Zero
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .clipToBounds()
                .onSizeChanged { viewport = it },
        ) {
            HorizontalPager(
                state = pagerState,
                // 放大后翻页手势让位给拖动，否则想平移却翻到了下一页
                userScrollEnabled = scale <= 1.01f,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 6.dp),
            ) { index ->
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(vertical = 4.dp)
                        // 手势必须**按需消费**：1 倍时单指横向拖动留给 Pager 翻页，
                        // 只有双指（缩放）或已放大（平移）时才接管。
                        // 直接用 detectTransformGestures 会把单指 pan 一并 consume，
                        // 结果是「文档里写的横向翻页」实际划不动 —— 模拟器实测抓到的。
                        .pointerInput(Unit) {
                            awaitEachGesture {
                                awaitFirstDown(requireUnconsumed = false)
                                while (true) {
                                    val event = awaitPointerEvent()
                                    val pressed = event.changes.count { it.pressed }
                                    if (pressed == 0) break
                                    val zoomChange = event.calculateZoom()
                                    val panChange = event.calculatePan()
                                    if (pressed >= 2 || scale > 1.01f) {
                                        val next = (scale * zoomChange).coerceIn(1f, 4f)
                                        scale = next
                                        offset = if (next <= 1.01f) Offset.Zero
                                        else clampOffset(offset + panChange, next)
                                        event.changes.forEach { change ->
                                            if (change.positionChanged()) change.consume()
                                        }
                                    }
                                }
                            }
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    PdfPageImage(
                        viewModel = viewModel,
                        index = index,
                        renderWidth = renderWidth,
                        scale = scale,
                        offset = offset,
                        nightInvert = nightInvert,
                    )
                }
            }
        }

        HorizontalDivider(
            thickness = 0.5.dp,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f),
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 2.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(
                enabled = pagerState.currentPage > 0,
                onClick = {
                    scope.launch { pagerState.animateScrollToPage(pagerState.currentPage - 1) }
                },
                contentPadding = PaddingValues(horizontal = 8.dp),
            ) {
                Icon(Icons.Filled.ChevronLeft, contentDescription = null)
                Text("上一页")
            }

            TextButton(
                onClick = { showJump = true },
                contentPadding = PaddingValues(horizontal = 10.dp),
            ) {
                // 论文动辄十几页，页码常驻：翻页手势之外还得有个「读到哪了」。
                // 点一下可以跳页（待办 #23）—— 按页引用「见第 7 页」时不用划七下
                Text(
                    text = "${pagerState.currentPage + 1} / $pageCount",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(
                    onClick = {
                        // 一键在「整页」和「2 倍」之间切换：双指缩放不总是好按，
                        // 尤其是单手持机读论文的时候
                        val next = if (scale > 1.01f) 1f else 2f
                        scale = next
                        offset = Offset.Zero
                    },
                ) {
                    Icon(
                        imageVector = if (scale > 1.01f) Icons.Filled.ZoomOut else Icons.Filled.ZoomIn,
                        contentDescription = if (scale > 1.01f) "缩小到整页" else "放大到 2 倍",
                    )
                }
                TextButton(
                    enabled = pagerState.currentPage < pageCount - 1,
                    onClick = {
                        scope.launch { pagerState.animateScrollToPage(pagerState.currentPage + 1) }
                    },
                    contentPadding = PaddingValues(horizontal = 8.dp),
                ) {
                    Text("下一页")
                    Icon(Icons.Filled.ChevronRight, contentDescription = null)
                }
            }
        }
    }

    if (showJump) {
        PageJumpDialog(
            pageCount = pageCount,
            currentPage = pagerState.currentPage,
            onDismiss = { showJump = false },
            onJump = { target ->
                showJump = false
                scope.launch { pagerState.animateScrollToPage(target) }
            },
        )
    }
}

/**
 * 跳页对话框（v1.8.7.2 待办 #23）。
 *
 * 输入框预填当前页、只放数字键、边输边校验：越界时按钮直接禁用并给一行红字，
 * 而不是点了才弹「页码无效」。解析规则全在 [PdfPageJump.parse] 里，有单测覆盖。
 */
@Composable
private fun PageJumpDialog(
    pageCount: Int,
    currentPage: Int,
    onDismiss: () -> Unit,
    onJump: (Int) -> Unit,
) {
    // 光标默认落在末尾：焦点一进来就在最前面的话，接着打「3」会变成「31」，
    // 用户得先把预填的当前页删掉
    val initialText = (currentPage + 1).toString()
    var value by remember {
        mutableStateOf(
            androidx.compose.ui.text.input.TextFieldValue(
                text = initialText,
                selection = androidx.compose.ui.text.TextRange(initialText.length),
            )
        )
    }
    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val target = PdfPageJump.parse(value.text, pageCount)

    // 对话框一出来就聚焦 + 弹键盘：跳页是个「打完数字就回车」的动作，
    // 多一步点输入框纯属浪费
    LaunchedEffect(Unit) {
        runCatching { focusRequester.requestFocus() }
        keyboard?.show()
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("跳到指定页") },
        text = {
            Column {
                OutlinedTextField(
                    value = value,
                    // 只留数字：中文输入法在全角状态下能打出「７」，这里一并挡掉
                    onValueChange = { input ->
                        val digits = input.text.filter { it in '0'..'9' }.take(5)
                        value = input.copy(
                            text = digits,
                            selection = androidx.compose.ui.text.TextRange(
                                input.selection.end.coerceIn(0, digits.length),
                            ),
                        )
                    },
                    singleLine = true,
                    label = { Text("页码（1 - $pageCount）") },
                    isError = value.text.isNotEmpty() && target == null,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Number,
                        imeAction = ImeAction.Go,
                    ),
                    keyboardActions = KeyboardActions(onGo = { target?.let(onJump) }),
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(focusRequester),
                )
                if (value.text.isNotEmpty() && target == null) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = "请输入 1 到 $pageCount 之间的页码",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(enabled = target != null, onClick = { target?.let(onJump) }) {
                Text("跳转")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

/** 单页位图（按需渲染，失败时给一行提示而不是空白） */
@Composable
private fun PdfPageImage(
    viewModel: PdfViewerViewModel,
    index: Int,
    renderWidth: Int,
    scale: Float,
    offset: Offset,
    nightInvert: Boolean,
) {
    // nightInvert 要进 key：反色是在渲染时套的滤镜，开关翻转后必须重新渲染，
    // 否则界面上只剩图标变了、纸还是白的
    var bitmap by remember(index, renderWidth, nightInvert) { mutableStateOf<Bitmap?>(null) }
    var failed by remember(index, renderWidth, nightInvert) { mutableStateOf(false) }

    LaunchedEffect(index, renderWidth, nightInvert) {
        bitmap = viewModel.renderPage(index, renderWidth)
        failed = bitmap == null
    }

    val bmp = bitmap
    when {
        bmp != null -> {
            val image = remember(bmp) { bmp.asImageBitmap() }
            Image(
                bitmap = image,
                contentDescription = "第 ${index + 1} 页",
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer(
                        scaleX = scale,
                        scaleY = scale,
                        translationX = offset.x,
                        translationY = offset.y,
                    )
                    .background(if (nightInvert) Color.Black else Color.White),
            )
        }
        failed -> Text(
            text = "这一页渲染失败",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        else -> Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
        }
    }
}

/** 字节数格式化（下载进度用） */
private fun formatBytes(bytes: Long): String = when {
    bytes >= 1024L * 1024L -> "%.1f MB".format(bytes / 1024f / 1024f)
    bytes >= 1024L -> "%.0f KB".format(bytes / 1024f)
    else -> "$bytes B"
}
