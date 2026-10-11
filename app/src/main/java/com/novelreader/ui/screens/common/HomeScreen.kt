package com.novelreader.ui.screens.common

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.CollectionsBookmark
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Source
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.lifecycle.viewmodel.compose.viewModel
import com.novelreader.di.AppContainer
import com.novelreader.ui.NovelViewModelFactory
import com.novelreader.ui.screens.comic.ComicSearchScreen
import com.novelreader.ui.screens.comic.ComicSearchViewModel
import com.novelreader.ui.screens.novel.NovelSearchScreen
import com.novelreader.ui.screens.novel.NovelSearchViewModel
import com.novelreader.ui.screens.video.VideoSearchScreen
import com.novelreader.ui.screens.video.VideoSearchViewModel

/**
 * 主页。
 *
 * 底部导航三栏：小说 / 动漫 / 视频，各自对应独立页面。
 * 每个页面顶部都有搜索框，切换 tab 不离开主框架。
 * 顶栏右侧提供 书架 / 源管理 / 设置 入口。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    container: AppContainer,
    onOpenNovelItem: (com.novelreader.domain.model.MediaItem) -> Unit,
    onOpenComicItem: (com.novelreader.domain.model.MediaItem) -> Unit,
    onOpenVideoItem: (com.novelreader.domain.model.MediaItem) -> Unit,
    onOpenBookshelf: () -> Unit,
    onOpenSources: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    var selectedTab by rememberSaveable { mutableIntStateOf(0) }

    data class TabItem(val label: String, val icon: ImageVector)

    val tabs = listOf(
        TabItem("小说", Icons.AutoMirrored.Filled.MenuBook),
        TabItem("动漫", Icons.Filled.PhotoLibrary),
        TabItem("视频", Icons.Filled.PlayCircle),
    )

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(tabs[selectedTab].label) },
                actions = {
                    IconButton(onClick = onOpenBookshelf) {
                        Icon(Icons.Filled.CollectionsBookmark, contentDescription = "书架")
                    }
                    IconButton(onClick = onOpenSources) {
                        Icon(Icons.Filled.Source, contentDescription = "源管理")
                    }
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Filled.Settings, contentDescription = "设置")
                    }
                },
            )
        },
        bottomBar = {
            NavigationBar {
                tabs.forEachIndexed { index, tab ->
                    NavigationBarItem(
                        selected = selectedTab == index,
                        onClick = { selectedTab = index },
                        icon = { Icon(imageVector = tab.icon, contentDescription = tab.label) },
                        label = { Text(tab.label) },
                    )
                }
            }
        },
    ) { padding ->
        androidx.compose.foundation.layout.Box(modifier = Modifier.padding(padding)) {
            when (selectedTab) {
                0 -> NovelTab(container, onOpenNovelItem)
                1 -> ComicTab(container, onOpenComicItem)
                else -> VideoTab(container, onOpenVideoItem)
            }
        }
    }
}

/** 小说 tab：独立页面（搜索框 + 结果列表） */
@Composable
private fun NovelTab(
    container: AppContainer,
    onItemClick: (com.novelreader.domain.model.MediaItem) -> Unit,
) {
    val viewModel: NovelSearchViewModel = viewModel(
        factory = NovelViewModelFactory(container) { NovelSearchViewModel(it.contentRepository, it.preferencesManager) }
    )
    NovelSearchScreen(viewModel = viewModel, onItemClick = onItemClick)
}

/** 动漫 tab：独立页面 */
@Composable
private fun ComicTab(
    container: AppContainer,
    onItemClick: (com.novelreader.domain.model.MediaItem) -> Unit,
) {
    val viewModel: ComicSearchViewModel = viewModel(
        factory = NovelViewModelFactory(container) { ComicSearchViewModel(it.contentRepository, it.preferencesManager) }
    )
    ComicSearchScreen(viewModel = viewModel, onItemClick = onItemClick)
}

/** 视频 tab：独立页面 */
@Composable
private fun VideoTab(
    container: AppContainer,
    onItemClick: (com.novelreader.domain.model.MediaItem) -> Unit,
) {
    val viewModel: VideoSearchViewModel = viewModel(
        factory = NovelViewModelFactory(container) { VideoSearchViewModel(it.contentRepository, it.preferencesManager) }
    )
    VideoSearchScreen(viewModel = viewModel, onItemClick = onItemClick)
}
