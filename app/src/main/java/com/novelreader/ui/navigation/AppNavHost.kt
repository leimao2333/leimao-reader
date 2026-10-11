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
            HomeScreen(
                onOpenNovelItem = { item -> navController.navigate(Routes.novelDetail(item)) },
                onOpenComicItem = { item -> navController.navigate(Routes.comicDetail(item)) },
                onOpenVideoItem = { item -> navController.navigate(Routes.videoDetail(item)) },
                onOpenBookshelf = { navController.navigate(Routes.BOOKSHELF) },
                onOpenSources = { navController.navigate(Routes.SOURCE_MANAGE) },
                onOpenSettings = { navController.navigate(Routes.SETTINGS) },
                onOpenNovelSearch = { navController.navigate(Routes.NOVEL_SEARCH) },
                onOpenComicSearch = { navController.navigate(Routes.COMIC_SEARCH) },
                onOpenVideoSearch = { navController.navigate(Routes.VIDEO_SEARCH) },
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
                                navController.navigate(Routes.novelReader(item, chapters, target))
                            com.novelreader.domain.model.SourceType.COMIC ->
                                navController.navigate(Routes.comicReader(item, chapters, target))
                            com.novelreader.domain.model.SourceType.VIDEO ->
                                navController.navigate(Routes.videoPlayer(item, chapters, target))
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
            )
        }

        // ===== 更新日志 =====
        composable(Routes.CHANGELOG) {
            com.novelreader.ui.screens.settings.ChangelogScreen(
                onBack = { navController.popBackStack() },
            )
        }

        // ===== 小说：搜索 =====
        composable(Routes.NOVEL_SEARCH) {
            val viewModel: NovelSearchViewModel = viewModel(
                factory = NovelViewModelFactory(container) {
                    NovelSearchViewModel(it.contentRepository, it.preferencesManager)
                }
            )
            NovelSearchScreen(
                viewModel = viewModel,
                onItemClick = { item ->
                    navController.navigate(Routes.novelDetail(item))
                },
                onBack = { navController.popBackStack() },
            )
        }

        // ===== 小说：详情 =====
        composable(
            route = Routes.NOVEL_DETAIL,
            arguments = listOf(navArgument("item") { type = NavType.StringType }),
        ) { backStackEntry ->
            val item = NavArgs.decodeItem(backStackEntry.arguments?.getString("item") ?: "")
            val viewModel: NovelDetailViewModel = viewModel(
                factory = NovelViewModelFactory(container) {
                    NovelDetailViewModel(it.contentRepository, it.bookRepository, it.downloadManager)
                }
            )
            NovelDetailScreen(
                viewModel = viewModel,
                item = item,
                onChapterClick = { chapter ->
                    val chapters = viewModel.chapters.value
                    navController.navigate(
                        Routes.novelReader(item, chapters, chapter)
                    )
                },
                onBack = { navController.popBackStack() },
            )
        }

        // ===== 小说：阅读 =====
        composable(
            route = Routes.NOVEL_READER,
            arguments = listOf(
                navArgument("item") { type = NavType.StringType },
                navArgument("chapters") { type = NavType.StringType },
                navArgument("chapter") { type = NavType.StringType },
            ),
        ) { backStackEntry ->
            val item = NavArgs.decodeItem(backStackEntry.arguments?.getString("item") ?: "")
            val chapters = NavArgs.decodeChapters(backStackEntry.arguments?.getString("chapters") ?: "")
            val chapter = NavArgs.decodeChapter(backStackEntry.arguments?.getString("chapter") ?: "")
            val viewModel: NovelReaderViewModel = viewModel(
                factory = NovelViewModelFactory(container) {
                    NovelReaderViewModel(
                        it.contentRepository,
                        it.progressRepository,
                        it.preferencesManager,
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
            ComicDetailScreen(
                viewModel = viewModel,
                item = item,
                onChapterClick = { chapter ->
                    val chapters = viewModel.chapters.value
                    navController.navigate(Routes.comicReader(item, chapters, chapter))
                },
                onBack = { navController.popBackStack() },
            )
        }

        // ===== 漫画：阅读 =====
        composable(
            route = Routes.COMIC_READER,
            arguments = listOf(
                navArgument("item") { type = NavType.StringType },
                navArgument("chapters") { type = NavType.StringType },
                navArgument("chapter") { type = NavType.StringType },
            ),
        ) { backStackEntry ->
            val item = NavArgs.decodeItem(backStackEntry.arguments?.getString("item") ?: "")
            val chapters = NavArgs.decodeChapters(backStackEntry.arguments?.getString("chapters") ?: "")
            val chapter = NavArgs.decodeChapter(backStackEntry.arguments?.getString("chapter") ?: "")
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
                    navController.navigate(Routes.videoPlayer(item, episodes, episode))
                },
                onBack = { navController.popBackStack() },
            )
        }

        // ===== 视频：播放 =====
        composable(
            route = Routes.VIDEO_PLAYER,
            arguments = listOf(
                navArgument("item") { type = NavType.StringType },
                navArgument("chapters") { type = NavType.StringType },
                navArgument("chapter") { type = NavType.StringType },
            ),
        ) { backStackEntry ->
            val item = NavArgs.decodeItem(backStackEntry.arguments?.getString("item") ?: "")
            val episodes = NavArgs.decodeChapters(backStackEntry.arguments?.getString("chapters") ?: "")
            val episode = NavArgs.decodeChapter(backStackEntry.arguments?.getString("chapter") ?: "")
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
