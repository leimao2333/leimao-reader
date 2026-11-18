package com.novelreader.ui.screens.comic

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.novelreader.core.download.DownloadManager
import com.novelreader.domain.model.ChapterItem
import com.novelreader.domain.model.MediaItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 漫画下载管理页（D32）：把「已经缓存到本地的话」可视化，并支持单章 / 全部删除。
 *
 * 数据全部来自 [DownloadManager] 的本地缓存目录，不依赖源：
 * - 已缓存章节 = `comic_cache/<书名>_<源后缀>/<5位章节号>/` 下有文件的目录；
 * - 单章大小 / 总大小通过遍历目录计算，放在 IO 线程做（章多时遍历并不便宜）。
 */
@Composable
fun ComicCacheScreen(
    item: MediaItem,
    chapters: List<ChapterItem>,
    downloadManager: DownloadManager,
    onBack: () -> Unit,
) {
    // 缓存状态快照；refresh 变化时重算
    var refresh by remember { mutableIntStateOf(0) }
    var confirmClearAll by remember { mutableStateOf(false) }

    val snapshot by produceState(CacheSnapshot(), item, refresh) {
        value = withContext(Dispatchers.IO) {
            val cached = runCatching { downloadManager.cachedComicChapters(item) }
                .getOrDefault(emptySet())
            val sizes = cached.associateWith { idx ->
                runCatching {
                    downloadManager.comicChapterCacheDir(item, idx)
                        .walkTopDown().filter(File::isFile).sumOf { it.length() }
                }.getOrDefault(0L)
            }
            CacheSnapshot(cached, sizes.values.sum(), sizes)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding(),
    ) {
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
            Spacer(Modifier.width(4.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "下载管理",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = item.title,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        Text(
            text = "已缓存 ${snapshot.cached.size} 话 · ${formatBytes(snapshot.sizeBytes)}",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        )

        Spacer(Modifier.height(8.dp))

        if (snapshot.cached.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = "还没有缓存任何章节",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = "在详情页点「缓存」可以选择范围离线下载",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            return@Column
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 24.dp),
        ) {
            item(key = "clear_all") {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = if (confirmClearAll) "确认清空全部缓存？" else "清空全部漫画缓存",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedButton(onClick = {
                        if (!confirmClearAll) {
                            confirmClearAll = true
                        } else {
                            downloadManager.clearComicCache(item)
                            confirmClearAll = false
                            refresh++
                        }
                    }) {
                        Text(if (confirmClearAll) "确认" else "清空")
                    }
                }
                HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
            }

            // 已缓存的章节按源顺序展示；源目录里存在但当前目录没有的（源换章）忽略
            val cachedChapters = chapters.filter { it.index in snapshot.cached }
            items(cachedChapters, key = { "${it.index}_${it.url}" }) { chapter ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = chapter.title,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = formatBytes(snapshot.chapterSizes[chapter.index] ?: 0L),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Text(
                        text = "删除",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier
                            .clickable {
                                downloadManager.clearComicChapter(item, chapter.index)
                                refresh++
                            }
                            .padding(horizontal = 8.dp, vertical = 6.dp),
                    )
                }
                HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
            }

            // 有缓存目录但目录里已经没有对应章节（源目录变动）时，仍允许整体清理
            val orphan = snapshot.cached.size - cachedChapters.size
            if (orphan > 0) {
                item(key = "orphan") {
                    Text(
                        text = "另有 $orphan 话缓存无法对应到当前目录（可能是源目录变化），可用上方「清空」一并删除",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                    )
                }
            }
        }
    }
}

/** 漫画缓存快照（D32） */
private data class CacheSnapshot(
    val cached: Set<Int> = emptySet(),
    val sizeBytes: Long = 0L,
    val chapterSizes: Map<Int, Long> = emptyMap(),
)

/** 字节数 → 人类可读（B / KB / MB / GB） */
private fun formatBytes(bytes: Long): String {
    if (bytes <= 0L) return "0 B"
    val units = listOf("B", "KB", "MB", "GB")
    var value = bytes.toDouble()
    var i = 0
    while (value >= 1024 && i < units.lastIndex) {
        value /= 1024
        i++
    }
    return if (i == 0) "${bytes} B" else String.format("%.1f %s", value, units[i])
}
