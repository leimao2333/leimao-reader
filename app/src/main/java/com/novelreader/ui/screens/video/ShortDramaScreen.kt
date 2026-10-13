package com.novelreader.ui.screens.video

import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem as ExoMediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.novelreader.domain.model.MediaItem
import com.novelreader.ui.screens.common.HomeViewModel
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * 短剧频道（v1.3.9.0，对标抖音式竖屏沉浸短剧流）。
 *
 * 交互：
 * - **上下滑换片**：VerticalPager，滑到哪播哪，离开的页立即暂停并释放解码负担
 * - **点击画面暂停 / 继续**；**播完自动连下一集**（一部短剧多集，播完一部停留）
 * - 右下角「详情」跳现有影视详情页（可看全部剧集 / 换源）
 *
 * 数据：HomeViewModel 聚合各源的「短剧」分类，逐（源, 分类）翻页轮询合并。
 */
@Composable
fun ShortDramaScreen(
    state: HomeViewModel.ShortDramaState,
    playback: Map<String, HomeViewModel.DramaPlayback>,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onLoadMore: () -> Unit,
    onResolveEpisode: (MediaItem, Int) -> Unit,
    onNextEpisode: (MediaItem) -> Unit,
    onOpenDetail: (MediaItem) -> Unit,
) {
    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        when {
            state.loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(color = Color.White)
                    Spacer(Modifier.height(10.dp))
                    Text("正在聚合短剧源…", color = Color.White)
                }
            }

            state.items.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = state.error ?: "暂无短剧内容",
                        color = Color.White,
                        modifier = Modifier.clickable { onRefresh() },
                    )
                    Spacer(Modifier.height(8.dp))
                    Text("点一下重试", color = Color.White.copy(alpha = 0.6f))
                }
            }

            else -> {
                val pagerState = rememberPagerState(pageCount = { state.items.size })
                // 触底预加载：滑到倒数第 3 部时拉下一批
                LaunchedEffect(pagerState, state.items.size) {
                    snapshotFlow { pagerState.currentPage }
                        .distinctUntilChanged()
                        .collect { page ->
                            if (page >= state.items.size - 3) onLoadMore()
                        }
                }
                VerticalPager(
                    state = pagerState,
                    modifier = Modifier.fillMaxSize(),
                    beyondViewportPageCount = 0,
                ) { page ->
                    val item = state.items.getOrNull(page) ?: return@VerticalPager
                    DramaPlayerPage(
                        item = item,
                        active = page == pagerState.currentPage,
                        playbackState = playback["${item.sourceId}:${item.id}"],
                        onResolve = onResolveEpisode,
                        onNextEpisode = onNextEpisode,
                        onOpenDetail = onOpenDetail,
                    )
                }
            }
        }

        // 顶部返回 + 标题（悬浮在视频上）
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.TopStart)
                .background(Color.Black.copy(alpha = 0.25f)),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回", tint = Color.White)
            }
            Text("短剧", color = Color.White, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.width(8.dp))
            if (state.planCount > 0) {
                Text(
                    "$planLabel ${state.planCount}",
                    color = Color.White.copy(alpha = 0.6f),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

private const val planLabel = "聚合源"

/** 短剧流里的单页：全屏竖屏播放 + 信息浮层 */
@Composable
private fun DramaPlayerPage(
    item: MediaItem,
    active: Boolean,
    playbackState: HomeViewModel.DramaPlayback?,
    onResolve: (MediaItem, Int) -> Unit,
    onNextEpisode: (MediaItem) -> Unit,
    onOpenDetail: (MediaItem) -> Unit,
) {
    val context = LocalContext.current
    val key = "${item.sourceId}:${item.id}"
    val pb = playbackState

    // 进入活动页时解析当前集；解析结果变化时交给播放器
    LaunchedEffect(active, item) {
        if (active && pb == null) onResolve(item, 0)
    }

    val player = remember {
        ExoPlayer.Builder(context).build().apply {
            repeatMode = Player.REPEAT_MODE_OFF
            addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(playbackState: Int) {
                    if (playbackState == Player.STATE_ENDED) onNextEpisode(item)
                }
            })
        }
    }
    DisposableEffect(Unit) {
        onDispose { player.release() }
    }

    // 换页：离开立即暂停；换集（url 变化）换源播放
    LaunchedEffect(active, pb?.url) {
        if (!active) {
            player.pause()
            return@LaunchedEffect
        }
        val url = pb?.url
        if (active && url != null) {
            val current = player.currentMediaItem?.localConfiguration?.uri?.toString()
            if (current != url) {
                player.setMediaItem(
                    ExoMediaItem.Builder().setUri(url).build(),
                )
                player.prepare()
            }
            player.playWhenReady = true
        }
    }

    Box(Modifier.fillMaxSize()) {
        // 视频层（BACKGROUND 模式裁剪填满竖屏）
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    useController = false
                    resizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                    layoutParams = FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    )
                }
            },
            update = { view -> view.player = player },
            modifier = Modifier.fillMaxSize(),
        )

        // 点击画面：暂停 / 继续
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clickable {
                    if (player.isPlaying) player.pause() else player.play()
                },
        )

        // 加载 / 错误 / 播完了的浮层
        when {
            pb?.loading == true -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = Color.White)
            }

            pb?.error != null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    pb.error,
                    color = Color.White,
                    modifier = Modifier
                        .background(Color.Black.copy(alpha = 0.5f), RoundedCornerShape(8.dp))
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                )
            }

            pb?.url == null && active -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Icon(
                    Icons.Filled.PlayArrow,
                    contentDescription = null,
                    tint = Color.White.copy(alpha = 0.8f),
                    modifier = Modifier.size(64.dp),
                )
            }
        }

        // 底部信息：片名 + 备注
        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .background(Color.Black.copy(alpha = 0.35f))
                .padding(horizontal = 14.dp, vertical = 12.dp),
        ) {
            Text(
                text = item.title,
                color = Color.White,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = listOfNotNull(
                        pb?.episodes?.getOrNull(pb.episodeIndex)?.title,
                        item.sourceName.takeIf { it.isNotBlank() },
                    ).joinToString(" · "),
                    color = Color.White.copy(alpha = 0.75f),
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                // 详情入口（看全部剧集 / 换源 / 完整简介）
                Box(
                    modifier = Modifier
                        .background(Color.White.copy(alpha = 0.2f), CircleShape)
                        .clickable { onOpenDetail(item) }
                        .padding(horizontal = 10.dp, vertical = 5.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Filled.Info,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(14.dp),
                        )
                        Spacer(Modifier.width(4.dp))
                        Text("详情", color = Color.White, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}
