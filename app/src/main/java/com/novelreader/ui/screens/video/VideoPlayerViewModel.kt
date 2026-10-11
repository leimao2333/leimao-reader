package com.novelreader.ui.screens.video

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.novelreader.domain.model.ChapterItem
import com.novelreader.domain.model.MediaItem as NovelMediaItem
import com.novelreader.domain.repository.ContentRepository
import com.novelreader.domain.repository.ProgressRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 视频播放 ViewModel。
 *
 * 管理 Media3 ExoPlayer 实例、播放状态、视频目录和进度。
 * 支持防盗链请求头注入与播放进度恢复。
 */
@UnstableApi
class VideoPlayerViewModel(
    private val contentRepository: ContentRepository,
    private val progressRepository: ProgressRepository,
) : ViewModel() {

    /** ExoPlayer 实例（由 UI 层绑定到 PlayerView） */
    var player: ExoPlayer? = null
        private set

    /** 当前视频地址 */
    private val _videoUrl = MutableStateFlow<String?>(null)
    val videoUrl: StateFlow<String?> = _videoUrl.asStateFlow()

    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    /** 是否正在播放 */
    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    private var currentItem: NovelMediaItem? = null
    private var currentEpisode: ChapterItem? = null

    private val _episodes = MutableStateFlow<List<ChapterItem>>(emptyList())
    val episodes: StateFlow<List<ChapterItem>> = _episodes.asStateFlow()

    /** 当前选集标题 */
    private val _episodeTitle = MutableStateFlow("")
    val episodeTitle: StateFlow<String> = _episodeTitle.asStateFlow()

    /**
     * 初始化播放器。
     * @param player 由 UI 层创建的 ExoPlayer（绑定到 Composable 生命周期）
     */
    fun attachPlayer(player: ExoPlayer) {
        this.player = player
        player.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                _isPlaying.value = isPlaying
            }
        })
    }

    fun init(item: NovelMediaItem, episodes: List<ChapterItem>, episode: ChapterItem) {
        currentItem = item
        _episodes.value = episodes
        currentEpisode = episode
        _episodeTitle.value = episode.title
        loadEpisode(episode, restoreProgress = true)
    }

    /** 加载某集的播放地址 */
    fun loadEpisode(episode: ChapterItem, restoreProgress: Boolean = false) {
        currentEpisode = episode
        _episodeTitle.value = episode.title
        viewModelScope.launch {
            _loading.value = true
            _error.value = null
            runCatching { contentRepository.content(episode) }
                .onSuccess { result ->
                    val url = result.videoUrl
                    if (url.isNullOrEmpty()) {
                        _error.value = "未获取到播放地址"
                    } else {
                        _videoUrl.value = url
                        play(url, result.videoHeaders, restoreProgress)
                    }
                }
                .onFailure { e ->
                    _error.value = e.message ?: "加载失败"
                }
            _loading.value = false
        }
    }

    private fun play(url: String, headers: Map<String, String>, restoreProgress: Boolean) {
        val player = player ?: return

        val mediaItem = MediaItem.Builder().setUri(url).build()

        // 防盗链请求头：通过 DefaultHttpDataSource.Factory 注入
        if (headers.isNotEmpty()) {
            val httpFactory = DefaultHttpDataSource.Factory()
                .setUserAgent("NovelReader/1.0")
                .setDefaultRequestProperties(headers)
                .setAllowCrossProtocolRedirects(true)
            val mediaSourceFactory = DefaultMediaSourceFactory(httpFactory)
            val mediaSource = mediaSourceFactory.createMediaSource(mediaItem)
            player.setMediaSource(mediaSource)
        } else {
            player.setMediaItem(mediaItem)
        }

        player.prepare()

        // 恢复上次播放进度
        if (restoreProgress) {
            viewModelScope.launch {
                val currentItem = currentItem ?: return@launch
                val currentEpisode = currentEpisode ?: return@launch
                val progress = progressRepository.getProgress(currentItem.sourceId, currentItem.id)
                val position = progress?.playPositionMs ?: 0L
                // 若进度对应当前集，则 seek 到该位置（避免跨集误 seek）
                if (progress != null && progress.chapterUrl == currentEpisode.url && position > 0) {
                    player.seekTo(position)
                }
                player.play()
            }
        } else {
            player.play()
        }
    }

    /** 快进指定毫秒（长按快进） */
    fun seekForwardBy(ms: Long = 10_000L) {
        val player = player ?: return
        val target = (player.currentPosition + ms).coerceAtMost(player.duration.takeIf { it > 0 } ?: Long.MAX_VALUE)
        player.seekTo(target)
    }

    /** 切换播放/暂停 */
    fun togglePlay() {
        val player = player ?: return
        if (player.isPlaying) {
            player.pause()
        } else {
            player.play()
        }
    }

    /** 播放指定集 */
    fun playEpisode(episode: ChapterItem) {
        loadEpisode(episode, restoreProgress = false)
    }

    private fun saveProgress() {
        val item = currentItem ?: return
        val episode = currentEpisode ?: return
        viewModelScope.launch {
            progressRepository.saveProgress(
                sourceId = item.sourceId,
                itemId = item.id,
                chapterUrl = episode.url,
                chapterTitle = episode.title,
                chapterIndex = episode.index,
                playPositionMs = player?.currentPosition ?: 0L,
                itemType = com.novelreader.domain.model.SourceType.VIDEO.name,
                itemTitle = item.title,
            )
        }
    }

    /** 保存当前播放进度（供退出时调用） */
    fun saveCurrentProgress() {
        saveProgress()
    }

    override fun onCleared() {
        super.onCleared()
        saveCurrentProgress()
        player?.release()
        player = null
    }
}
