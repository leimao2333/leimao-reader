package com.novelreader.ui.navigation

import androidx.compose.runtime.Composable
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.novelreader.di.AppContainer
import com.novelreader.ui.NovelViewModelFactory
import com.novelreader.ui.screens.bookshelf.BookShelfScreen
import com.novelreader.ui.screens.bookshelf.BookShelfViewModel
import com.novelreader.ui.screens.common.HomeScreen
import com.novelreader.ui.screens.common.HomeViewModel
import com.novelreader.ui.screens.comic.ComicDetailScreen
import com.novelreader.ui.screens.comic.ComicDetailViewModel
import com.novelreader.ui.screens.comic.ComicReaderScreen
import com.novelreader.ui.screens.comic.ComicReaderViewModel
import com.novelreader.ui.screens.comic.ComicSearchScreen
import com.novelreader.ui.screens.comic.ComicSearchViewModel
import com.novelreader.ui.screens.novel.NovelDetailScreen
import com.novelreader.ui.screens.novel.NovelDetailViewModel
import com.novelreader.ui.screens.novel.NovelReaderScreen
import com.novelreader.ui.screens.novel.NovelReaderViewModel
import com.novelreader.ui.screens.novel.NovelSearchScreen
import com.novelreader.ui.screens.novel.NovelSearchViewModel
import com.novelreader.ui.screens.settings.SettingsScreen
import com.novelreader.ui.screens.settings.SettingsViewModel
import com.novelreader.ui.screens.source.SourceImportScreen
import com.novelreader.ui.screens.source.SourceManageScreen
import com.novelreader.ui.screens.source.SourceManageViewModel
import com.novelreader.ui.screens.video.VideoDetailScreen
import com.novelreader.ui.screens.video.VideoDetailViewModel
import com.novelreader.ui.screens.video.VideoPlayerScreen
import com.novelreader.ui.screens.video.VideoPlayerViewModel
import com.novelreader.ui.screens.video.VideoSearchScreen
import com.novelreader.ui.screens.video.VideoSearchViewModel
import kotlinx.coroutines.launch

/**
 * 应用导航图。
 *
 * 完整链路：
 * 首页 → 小说/漫画/视频（搜索→详情→阅读/播放）
 * 首页 → 书架 / 源管理（含导入）/ 设置
 */
@Composable
fun AppNavHost(container: AppContainer) {
    val navController = rememberNavController()

    NavHost(navController = navController, startDestination = Routes.HOME) {
        // ===== 首页 =====
        composable(Routes.HOME) {
            val homeViewModel: HomeViewModel = viewModel(
                factory = NovelViewModelFactory(container) {
                    HomeViewModel(
                        it.bookRepository,
                        it.progressRepository,
                        it.contentRepository,
                    )
                }
            )
            HomeScreen(
                viewModel = homeViewModel,
                onOpenNovelItem = { item -> navController.navigate(Routes.novelDetail(item)) },
                onOpenComicItem = { item -> navController.navigate(Routes.comicDetail(item)) },
                onOpenVideoItem = { item -> navController.navigate(Routes.videoDetail(item)) },
                onOpenSources = { navController.navigate(Routes.SOURCE_MANAGE) },
                onOpenSettings = { navController.navigate(Routes.SETTINGS) },
                onOpenNovelSearch = { navController.navigate(Routes.novelSearch()) },
                onOpenComicSearch = { navController.navigate(Routes.COMIC_SEARCH) },
                onOpenVideoSearch = { navController.navigate(Routes.VIDEO_SEARCH) },
                onContinueReading = { item, chapters, target ->
                    NavCache.putChapters(item, chapters)
                    val index = target?.index?.coerceAtLeast(0) ?: 0
                    if (chapters.isEmpty() || target == null) {
                        when (item.type) {
                            com.novelreader.domain.model.SourceType.NOVEL ->
                                navController.navigate(Routes.novelDetail(item))
                            com.novelreader.domain.model.SourceType.COMIC ->
                                navController.navigate(Routes.comicDetail(item))
                            com.novelreader.domain.model.SourceType.VIDEO ->
                                navController.navigate(Routes.videoDetail(item))
                        }
                    } else {
                        when (item.type) {
                            com.novelreader.domain.model.SourceType.NOVEL ->
                                navController.navigate(Routes.novelReader(item, index))
                            com.novelreader.domain.model.SourceType.COMIC ->
                                navController.navigate(Routes.comicReader(item, index))
                            com.novelreader.domain.model.SourceType.VIDEO ->
                                navController.navigate(Routes.videoPlayer(item, index))
                        }
                    }
                },
            )
        }

        // ===== 书架 =====
        composable(Routes.BOOKSHELF) {
            val viewModel: BookShelfViewModel = viewModel(
                factory = NovelViewModelFactory(container) {
                    BookShelfViewModel(
                        it.bookRepository,
                        it.progressRepository,
                        it.contentRepository,
                    )
                }
            )
            BookShelfScreen(
                viewModel = viewModel,
                onContinueReading = { item, chapters, target ->
                    // 章节列表走内存缓存，路由只传索引（避免大列表序列化导致 ANR）
                    NavCache.putChapters(item, chapters)
                    val index = target?.index?.coerceAtLeast(0) ?: 0
                    if (chapters.isEmpty() || target == null) {
                        // 无章节/无进度，回详情页
                        when (item.type) {
                            com.novelreader.domain.model.SourceType.NOVEL ->
                                navController.navigate(Routes.novelDetail(item))
                            com.novelreader.domain.model.SourceType.COMIC ->
                                navController.navigate(Routes.comicDetail(item))
                            com.novelreader.domain.model.SourceType.VIDEO ->
                                navController.navigate(Routes.videoDetail(item))
                        }
                    } else {
                        // 有进度章节，直达阅读/播放
                        when (item.type) {
                            com.novelreader.domain.model.SourceType.NOVEL ->
                                navController.navigate(Routes.novelReader(item, index))
                            com.novelreader.domain.model.SourceType.COMIC ->
                                navController.navigate(Routes.comicReader(item, index))
                            com.novelreader.domain.model.SourceType.VIDEO ->
                                navController.navigate(Routes.videoPlayer(item, index))
                        }
                    }
                },
                onBack = { navController.popBackStack() },
            )
        }

        // ===== 源管理 =====
        composable(Routes.SOURCE_MANAGE) {
            val viewModel: SourceManageViewModel = viewModel(
                factory = NovelViewModelFactory(container) {
                    SourceManageViewModel(
                        it.sourceRepository,
                        it.sourceRepository as? com.novelreader.data.repository.SourceRepositoryImpl,
                    )
                }
            )
            SourceManageScreen(
                viewModel = viewModel,
                onImport = { navController.navigate(Routes.SOURCE_IMPORT) },
                onBack = { navController.popBackStack() },
            )
        }

        // ===== 源导入 =====
        composable(Routes.SOURCE_IMPORT) {
            // 复用源管理的 ViewModel（同一作用域），或新建
            val viewModel: SourceManageViewModel = viewModel(
                factory = NovelViewModelFactory(container) {
                    SourceManageViewModel(
                        it.sourceRepository,
                        it.sourceRepository as? com.novelreader.data.repository.SourceRepositoryImpl,
                    )
                }
            )
            SourceImportScreen(
                viewModel = viewModel,
                onBack = { navController.popBackStack() },
            )
        }

        // ===== 设置 =====
        composable(Routes.SETTINGS) {
            val viewModel: SettingsViewModel = viewModel(
                factory = NovelViewModelFactory(container) {
                    SettingsViewModel(it.preferencesManager)
                }
            )
            SettingsScreen(
                viewModel = viewModel,
                onBack = { navController.popBackStack() },
                onOpenChangelog = { navController.navigate(Routes.CHANGELOG) },
                onOpenCacheManage = { navController.navigate(Routes.CACHE_MANAGE) },
            )
        }

        // ===== 缓存管理（#30）=====
        composable(Routes.CACHE_MANAGE) {
            val viewModel: com.novelreader.ui.screens.cache.CacheManageViewModel = viewModel(
                factory = NovelViewModelFactory(container) {
                    com.novelreader.ui.screens.cache.CacheManageViewModel(
                        it.chapterCacheRepository,
                        it.preferencesManager,
                    )
                }
            )
            com.novelreader.ui.screens.cache.CacheManageScreen(
                viewModel = viewModel,
                onBack = { navController.popBackStack() },
            )
        }

        // ===== 更新日志 =====
        composable(Routes.CHANGELOG) {
            com.novelreader.ui.screens.settings.ChangelogScreen(
                onBack = { navController.popBackStack() },
            )
        }

        // ===== 小说：搜索 =====
        composable(
            route = Routes.NOVEL_SEARCH,
            arguments = listOf(
                navArgument("keyword") { type = NavType.StringType; defaultValue = "" },
            ),
        ) { backStackEntry ->
            val viewModel: NovelSearchViewModel = viewModel(
                factory = NovelViewModelFactory(container) {
                    NovelSearchViewModel(it.contentRepository, it.preferencesManager, it.bookRepository)
                }
            )
            val scope = androidx.compose.runtime.rememberCoroutineScope()
            // 作者名等预搜索关键词
            val initialKeyword = backStackEntry.arguments?.getString("keyword").orEmpty()
            androidx.compose.runtime.LaunchedEffect(initialKeyword) {
                if (initialKeyword.isNotBlank()) viewModel.search(initialKeyword)
            }
            NovelSearchScreen(
                viewModel = viewModel,
                onItemClick = { item ->
                    navController.navigate(Routes.novelDetail(item))
                },
                onRead = { item ->
                    // 阅读：有进度直达历史章节，无进度拉目录进第一章
                    scope.launch {
                        val progress = runCatching {
                            container.progressRepository.getProgress(item.sourceId, item.id)
                        }.getOrNull()
                        val chapters = runCatching {
                            container.contentRepository.chapterList(item)
                        }.getOrDefault(emptyList())
                        if (chapters.isEmpty()) {
                            // 拉不到目录则退回详情页
                            navController.navigate(Routes.novelDetail(item))
                        } else {
                            NavCache.putChapters(item, chapters)
                            // 有进度且章节索引有效 → 历史章节；否则第一章
                            val index = progress?.chapterIndex?.coerceIn(0, chapters.size - 1) ?: 0
                            navController.navigate(Routes.novelReader(item, index))
                        }
                    }
                },
                onBack = { navController.popBackStack() },
            )
        }

        // ===== 小说：详情 =====
        composable(
            route = Routes.NOVEL_DETAIL,
            arguments = listOf(navArgument("item") { type = NavType.StringType }),
        ) { backStackEntry ->
            val routeItem = NavArgs.decodeItem(backStackEntry.arguments?.getString("item") ?: "")
            // 原地换源后当前书会变，用本地状态跟踪，保证「目录 / 阅读」都指向新的源
            val currentItemState = androidx.compose.runtime.remember(routeItem) {
                androidx.compose.runtime.mutableStateOf(routeItem)
            }
            val currentItem = currentItemState.value
            val viewModel: NovelDetailViewModel = viewModel(
                factory = NovelViewModelFactory(container) {
                    NovelDetailViewModel(
                        it.contentRepository,
                        it.bookRepository,
                        it.downloadManager,
                        it.progressRepository,
                    )
                }
            )
            val scope = androidx.compose.runtime.rememberCoroutineScope()
            NovelDetailScreen(
                viewModel = viewModel,
                item = currentItem,
                onChapterClick = { chapter ->
                    // 章节列表走内存缓存（3461 章编码进路由会 ANR）
                    val chapters = viewModel.chapters.value
                    NavCache.putChapters(currentItem, chapters)
                    // 用章节在列表中的真实下标（源返回的 index 可能与下标不一致，会跳错章）
                    val pos = chapters.indexOf(chapter).let { if (it >= 0) it else chapter.index }
                    navController.navigate(Routes.novelReader(currentItem, pos.coerceAtLeast(0)))
                },
                onRead = { target ->
                    // 阅读：有进度直达历史章节，无进度进第一章（与搜索页 onRead 一致）
                    scope.launch {
                        val progress = runCatching {
                            container.progressRepository.getProgress(target.sourceId, target.id)
                        }.getOrNull()
                        val chapters = runCatching {
                            container.contentRepository.chapterList(target)
                        }.getOrDefault(emptyList())
                        if (chapters.isEmpty()) return@launch
                        NavCache.putChapters(target, chapters)
                        val index = progress?.chapterIndex?.coerceIn(0, chapters.size - 1) ?: 0
                        navController.navigate(Routes.novelReader(target, index))
                    }
                },
                onSwitchSource = { candidate ->
                    // 换源改为「原地切换」：不再 navigate 新详情页（避免返回栈无限堆叠）
                    currentItemState.value = candidate
                    viewModel.switchTo(candidate)
                },
                onBack = { navController.popBackStack() },
                onSearchAuthor = { author ->
                    // 点击作者：跳到小说搜索页并按作者名预搜索（#31）
                    navController.navigate(Routes.novelSearch(initialKeyword = author))
                },
            )
        }

        // ===== 小说：阅读 =====
        composable(
            route = Routes.NOVEL_READER,
            arguments = listOf(
                navArgument("item") { type = NavType.StringType },
                navArgument("chapterIndex") { type = NavType.IntType },
            ),
        ) { backStackEntry ->
            val item = NavArgs.decodeItem(backStackEntry.arguments?.getString("item") ?: "")
            val chapterIndex = backStackEntry.arguments?.getInt("chapterIndex") ?: 0
            val chapters = NavCache.getChapters(item)
            val chapter = chapters.getOrNull(chapterIndex) ?: chapters.firstOrNull()
                ?: return@composable
            val viewModel: NovelReaderViewModel = viewModel(
                factory = NovelViewModelFactory(container) {
                    NovelReaderViewModel(
                        it.contentRepository,
                        it.progressRepository,
                        it.preferencesManager,
                        it.chapterCacheRepository,
                        it.bookRepository,
                        it.bookmarkRepository,
                    )
                }
            )
            NovelReaderScreen(
                viewModel = viewModel,
                item = item,
                chapters = chapters,
                chapter = chapter,
                onBack = { navController.popBackStack() },
            )
        }

        // ===== 漫画：搜索 =====
        composable(Routes.COMIC_SEARCH) {
            val viewModel: ComicSearchViewModel = viewModel(
                factory = NovelViewModelFactory(container) {
                    ComicSearchViewModel(it.contentRepository, it.preferencesManager)
                }
            )
            ComicSearchScreen(
                viewModel = viewModel,
                onItemClick = { item ->
                    navController.navigate(Routes.comicDetail(item))
                },
                onBack = { navController.popBackStack() },
            )
        }

        // ===== 漫画：详情 =====
        composable(
            route = Routes.COMIC_DETAIL,
            arguments = listOf(navArgument("item") { type = NavType.StringType }),
        ) { backStackEntry ->
            val item = NavArgs.decodeItem(backStackEntry.arguments?.getString("item") ?: "")
            val viewModel: ComicDetailViewModel = viewModel(
                factory = NovelViewModelFactory(container) {
                    ComicDetailViewModel(it.contentRepository, it.bookRepository, it.downloadManager)
                }
            )
            val scope = androidx.compose.runtime.rememberCoroutineScope()
            ComicDetailScreen(
                viewModel = viewModel,
                item = item,
                onChapterClick = { chapter ->
                    val chapters = viewModel.chapters.value
                    NavCache.putChapters(item, chapters)
                    navController.navigate(Routes.comicReader(item, chapter.index))
                },
                onRead = {
                    // 继续阅读：有进度直达历史章节，无进度进第一章
                    scope.launch {
                        val progress = runCatching {
                            container.progressRepository.getProgress(item.sourceId, item.id)
                        }.getOrNull()
                        val chapters = viewModel.chapters.value
                        if (chapters.isEmpty()) return@launch
                        NavCache.putChapters(item, chapters)
                        val index = progress?.chapterIndex?.coerceIn(0, chapters.size - 1) ?: 0
                        navController.navigate(Routes.comicReader(item, index))
                    }
                },
                onBack = { navController.popBackStack() },
            )
        }

        // ===== 漫画：阅读 =====
        composable(
            route = Routes.COMIC_READER,
            arguments = listOf(
                navArgument("item") { type = NavType.StringType },
                navArgument("chapterIndex") { type = NavType.IntType },
            ),
        ) { backStackEntry ->
            val item = NavArgs.decodeItem(backStackEntry.arguments?.getString("item") ?: "")
            val chapterIndex = backStackEntry.arguments?.getInt("chapterIndex") ?: 0
            val chapters = NavCache.getChapters(item)
            val chapter = chapters.getOrNull(chapterIndex) ?: chapters.firstOrNull()
                ?: return@composable
            val viewModel: ComicReaderViewModel = viewModel(
                factory = NovelViewModelFactory(container) {
                    ComicReaderViewModel(
                        it.contentRepository,
                        it.progressRepository,
                        it.preferencesManager,
                    )
                }
            )
            ComicReaderScreen(
                viewModel = viewModel,
                item = item,
                chapters = chapters,
                chapter = chapter,
                onBack = { navController.popBackStack() },
            )
        }

        // ===== 视频：搜索 =====
        composable(Routes.VIDEO_SEARCH) {
            val viewModel: VideoSearchViewModel = viewModel(
                factory = NovelViewModelFactory(container) {
                    VideoSearchViewModel(it.contentRepository, it.preferencesManager)
                }
            )
            VideoSearchScreen(
                viewModel = viewModel,
                onItemClick = { item ->
                    navController.navigate(Routes.videoDetail(item))
                },
                onBack = { navController.popBackStack() },
            )
        }

        // ===== 视频：详情 =====
        composable(
            route = Routes.VIDEO_DETAIL,
            arguments = listOf(navArgument("item") { type = NavType.StringType }),
        ) { backStackEntry ->
            val item = NavArgs.decodeItem(backStackEntry.arguments?.getString("item") ?: "")
            val viewModel: VideoDetailViewModel = viewModel(
                factory = NovelViewModelFactory(container) {
                    VideoDetailViewModel(it.contentRepository, it.bookRepository, it.downloadManager)
                }
            )
            VideoDetailScreen(
                viewModel = viewModel,
                item = item,
                onEpisodeClick = { episode ->
                    val episodes = viewModel.episodes.value
                    NavCache.putChapters(item, episodes)
                    navController.navigate(Routes.videoPlayer(item, episode.index))
                },
                onBack = { navController.popBackStack() },
            )
        }

        // ===== 视频：播放 =====
        composable(
            route = Routes.VIDEO_PLAYER,
            arguments = listOf(
                navArgument("item") { type = NavType.StringType },
                navArgument("chapterIndex") { type = NavType.IntType },
            ),
        ) { backStackEntry ->
            val item = NavArgs.decodeItem(backStackEntry.arguments?.getString("item") ?: "")
            val chapterIndex = backStackEntry.arguments?.getInt("chapterIndex") ?: 0
            val episodes = NavCache.getChapters(item)
            val episode = episodes.getOrNull(chapterIndex) ?: episodes.firstOrNull()
                ?: return@composable
            val viewModel: VideoPlayerViewModel = viewModel(
                factory = NovelViewModelFactory(container) {
                    VideoPlayerViewModel(it.contentRepository, it.progressRepository)
                }
            )
            VideoPlayerScreen(
                viewModel = viewModel,
                item = item,
                episodes = episodes,
                episode = episode,
                onBack = { navController.popBackStack() },
            )
        }
    }
}
