package com.novelreader

import androidx.test.core.app.ApplicationProvider
import com.novelreader.core.preferences.PreferencesManager
import com.novelreader.data.cache.VideoPlayCacheStore
import com.novelreader.data.cache.VideoPrefetchCache
import com.novelreader.domain.repository.BookCacheSummary
import com.novelreader.domain.repository.ChapterCacheRepository
import com.novelreader.ui.screens.cache.CacheManageViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * 缓存管理页的「视频播放缓存」入口（v1.7.7.1）。
 *
 * 真实实现要碰 media3 的 `SimpleCache`（磁盘 + Android Context，JVM 测不了），
 * 所以 ViewModel 依赖 `VideoPlayCacheStore` 接口，这里注入假实现验证：
 * 刷新能读到容量、清理后容量归零、提示文案带上释放的容量、一键清空也会清它。
 */
@RunWith(RobolectricTestRunner::class)
@OptIn(ExperimentalCoroutinesApi::class)
class CacheManageViewModelVideoCacheTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private class FakePlayCache(var size: Long) : VideoPlayCacheStore {
        var clearCalls = 0
        override fun sizeBytes(): Long = size
        override fun clear(): Long {
            clearCalls++
            val freed = size
            size = 0
            return freed
        }
    }

    private fun viewModel(play: FakePlayCache): CacheManageViewModel = CacheManageViewModel(
        chapterCacheRepository = EmptyChapterCacheRepository,
        videoPrefetchCache = VideoPrefetchCache(File(tmp.root, "video_prefetch")),
        preferences = PreferencesManager(ApplicationProvider.getApplicationContext()),
        videoPlayCache = play,
        // 真实的 Dispatchers.IO 不受 runTest 虚拟时间控制，断言时可能还没跑完
        ioDispatcher = UnconfinedTestDispatcher(),
    )

    @Test
    fun `刷新能读到播放缓存占用`() = runTest {
        val play = FakePlayCache(12_000_000L)
        val vm = viewModel(play)
        advanceUntilIdle()
        assertEquals(12_000_000L, vm.videoPlayCacheSize.value)
    }

    @Test
    fun `清理后容量归零且提示带释放容量`() = runTest {
        val play = FakePlayCache(12_000_000L)
        val vm = viewModel(play)
        advanceUntilIdle()

        vm.clearVideoPlayCache()
        advanceUntilIdle()

        assertEquals(1, play.clearCalls)
        assertEquals(0L, vm.videoPlayCacheSize.value)
        val msg = vm.message.value.orEmpty()
        assertTrue("提示应说明清空了播放缓存：$msg", msg.contains("已清空视频播放缓存"))
        // formatSize(12_000_000) = 11.44 MB
        assertTrue("提示应带上释放的容量：$msg", msg.contains("11.44 MB"))
    }

    @Test
    fun `一键清空也会清掉播放缓存`() = runTest {
        val play = FakePlayCache(8L * 1024 * 1024)
        val vm = viewModel(play)
        advanceUntilIdle()

        vm.clearAllIncludingPrefetch()
        advanceUntilIdle()

        assertEquals(1, play.clearCalls)
        assertEquals(0L, vm.videoPlayCacheSize.value)
        assertTrue(vm.message.value.orEmpty().contains("已清空全部缓存"))
    }

    @Test
    fun `没有缓存时清理不会报出奇怪的容量`() = runTest {
        val play = FakePlayCache(0L)
        val vm = viewModel(play)
        advanceUntilIdle()

        vm.clearVideoPlayCache()
        advanceUntilIdle()

        assertEquals(0L, vm.videoPlayCacheSize.value)
        assertTrue(vm.message.value.orEmpty().contains("0 B"))
    }

    /** 章节缓存与本用例无关，全部给空实现 */
    private object EmptyChapterCacheRepository : ChapterCacheRepository {
        override suspend fun getCached(sourceId: String, itemId: String, chapterIndex: Int): String? = null
        override suspend fun cachedIndices(sourceId: String, itemId: String): Set<Int> = emptySet()
        override suspend fun cachedCount(sourceId: String, itemId: String): Int = 0
        override suspend fun saveCache(
            item: com.novelreader.domain.model.MediaItem,
            chapterIndex: Int,
            chapterTitle: String,
            chapterUrl: String,
            content: String,
        ) = Unit

        override suspend fun removeChapter(sourceId: String, itemId: String, chapterIndex: Int) = Unit
        override suspend fun removeBook(sourceId: String, itemId: String) = Unit
        override suspend fun clearAll() = Unit
        override suspend fun totalSize(): Long = 0L
        override suspend fun totalCount(): Int = 0
        override suspend fun cacheBooks(): List<BookCacheSummary> = emptyList()
    }
}
