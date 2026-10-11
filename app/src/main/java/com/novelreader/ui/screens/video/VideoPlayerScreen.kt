package com.novelreader.ui.screens.video

import android.annotation.SuppressLint
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.novelreader.domain.model.ChapterItem
import com.novelreader.domain.model.MediaItem as NovelMediaItem

/**
 * 视频播放页。
 *
 * 核心功能：
 * - Media3 ExoPlayer 播放
 * - 视频目录（选集列表）
 * - 播放进度保存
 */
@SuppressLint("UnusedMaterial3ScaffoldPaddingParameter")
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun VideoPlayerScreen(
    viewModel: VideoPlayerViewModel,
    item: NovelMediaItem,
    episodes: List<ChapterItem>,
    episode: ChapterItem,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val loading by viewModel.loading.collectAsState()
    val error by viewModel.error.collectAsState()
    val isPlaying by viewModel.isPlaying.collectAsState()
    val episodeTitle by viewModel.episodeTitle.collectAsState()
    val episodesList by viewModel.episodes.collectAsState()

    // 创建并绑定 ExoPlayer
    val player = androidx.compose.runtime.remember {
        ExoPlayer.Builder(context).build().also { viewModel.attachPlayer(it) }
    }

    DisposableEffect(Unit) {
        onDispose {
            viewModel.saveCurrentProgress()
            player.release()
        }
    }

    LaunchedEffect(item, episode) {
        viewModel.init(item, episodes, episode)
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // 顶部栏
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "‹ 返回",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .clickable(onClick = onBack)
                    .padding(8.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = episodeTitle,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        // 播放器区域（16:9）
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .background(Color.Black),
        ) {
            when {
                loading -> {
                    CircularProgressIndicator(
                        modifier = Modifier.align(Alignment.Center),
                        color = Color.White,
                    )
                }
                error != null -> {
                    Text(
                        text = error ?: "加载失败",
                        color = Color.White,
                        modifier = Modifier
                            .align(Alignment.Center)
                            .padding(16.dp),
                    )
                }
                else -> {
                    // 长按快进提示
                    var showSeekTip by remember { mutableStateOf(false) }

                    Box(modifier = Modifier.fillMaxSize()) {
                        AndroidView(
                            factory = { ctx ->
                                PlayerView(ctx).apply {
                                    this.player = player
                                    useController = true // 显示播放控制条
                                }
                            },
                            modifier = Modifier
                                .fillMaxSize()
                                .combinedClickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = null,
                                    onClick = {},
                                    onLongClick = {
                                        viewModel.seekForwardBy(10_000L)
                                        showSeekTip = true
                                    },
                                ),
                        )

                        // 长按快进提示浮层
                        if (showSeekTip) {
                            androidx.compose.runtime.LaunchedEffect(Unit) {
                                kotlinx.coroutines.delay(600)
                                showSeekTip = false
                            }
                            Text(
                                text = "已快进 10 秒",
                                color = Color.White,
                                style = MaterialTheme.typography.labelLarge,
                                modifier = Modifier
                                    .align(Alignment.Center)
                                    .background(
                                        color = Color.Black.copy(alpha = 0.6f),
                                        shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
                                    )
                                    .padding(horizontal = 16.dp, vertical = 8.dp),
                            )
                        }
                    }
                }
            }
        }

        // 选集列表
        Text(
            text = "选集（${episodesList.size}）",
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
        LazyColumn(modifier = Modifier.fillMaxSize()) {
            items(episodesList) { ep ->
                val isCurrent = ep.url == episode.url
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            if (isCurrent) MaterialTheme.colorScheme.primary.copy(alpha = 0.1f)
                            else Color.Transparent
                        )
                        .clickable { viewModel.playEpisode(ep) }
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                ) {
                    Text(
                        text = ep.title,
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (isCurrent) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
            }
        }
    }
}
