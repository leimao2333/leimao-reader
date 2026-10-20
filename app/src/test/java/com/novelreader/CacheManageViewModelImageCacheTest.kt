package com.novelreader

import androidx.test.core.app.ApplicationProvider
import com.novelreader.core.preferences.PreferencesManager
import com.novelreader.data.cache.ImageCacheStore
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
 * 缓存管理页的「漫画图片缓存」入口（v1.8.4.2）。
 *
 * 真实实现要碰 Coil 的 `DiskCache`（Android Context + 磁盘 LRU journal，JVM 测不了），
 * 所以 ViewModel 依赖 [ImageCacheStore] 接口。这里注入假实现验证：
 * 刷新读到占用、清理后归零且提示带释放容量、一键清空也覆盖它、未注入时不影响其它分区。
 */
@RunWith(RobolectricTestRunner::class)
@OptIn(ExperimentalCoroutinesApi::class)
class CacheManageViewModelImageCacheTest {

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

    private class FakeImageCache(var size: Long) : ImageCacheStore {
        var clearCalls = 0
        override fun sizeBytes(): Long = size
        override fun clear(): Long {
            clearCalls++
            val freed = size
            size = 0
            return freed
        }
    }

    private class FakePlayCache(var size: Long) : VideoPlayCacheStore {
        override fun sizeBytes(): Long = size
        override fun clear(): Long {
            val freed = size
            size = 0
            return freed
        }
    }

    private fun viewModel(
        image: ImageCacheStore?,
        play: FakePlayCache = FakePlayCache(0L),
    ): CacheManageViewModel = CacheManageViewModel(
        chapterCacheRepository = EmptyChapterCacheRepository,
        videoPrefetchCache = VideoPrefetchCache(File(tmp.root, "video_prefetch")),
        preferences = PreferencesManager(ApplicationProvider.getApplicationContext()),
        videoPlayCache = play,
        // 真实的 Dispatchers.IO 不受 runTest 虚拟时间控制，断言时可能还没跑完
        ioDispatcher = UnconfinedTestDispatcher(),
        imageCache = image,
    )

    @Test
    fun `刷新能读到图片缓存占用`() = runTest {
        val vm = viewModel(FakeImageCache(30L * 1024 * 1024))
        advanceUntilIdle()
        assertEquals(30L * 1024 * 1024, vm.imageCacheSize.value)
    }

    @Test
    fun `清理后容量归零且提示带释放容量`() = runTest {
        val image = FakeImageCache(30L * 1024 * 1024)
        val vm = viewModel(image)
        advanceUntilIdle()

        vm.clearImageCache()
        advanceUntilIdle()

        assertEquals(1, image.clearCalls)
        assertEquals(0L, vm.imageCacheSize.value)
        val msg = vm.message.value.orEmpty()
        assertTrue("提示应说明清了图片缓存：$msg", msg.contains("已清理图片缓存"))
        assertTrue("提示应带上释放的容量：$msg", msg.contains("30.00 MB"))
    }

    @Test
    fun `一键清空也会清掉图片缓存`() = runTest {
        val image = FakeImageCache(5L * 1024 * 1024)
        val vm = viewModel(image)
        advanceUntilIdle()

        vm.clearAllIncludingPrefetch()
        advanceUntilIdle()

        assertEquals(1, image.clearCalls)
        assertEquals(0L, vm.imageCacheSize.value)
        val msg = vm.message.value.orEmpty()
        assertTrue(msg.contains("已清空全部缓存"))
        assertTrue("提示应列出图片占用的释放量：$msg", msg.contains("图片 5.00 MB"))
    }

    @Test
    fun `没有缓存时清理不会报出奇怪的容量`() = runTest {
        val vm = viewModel(FakeImageCache(0L))
        advanceUntilIdle()

        vm.clearImageCache()
        advanceUntilIdle()

        assertEquals(0L, vm.imageCacheSize.value)
        assertTrue(vm.message.value.orEmpty().contains("0 B"))
    }

    @Test
    fun `未注入图片缓存时按 0 处理且不崩`() = runTest {
        val vm = viewModel(image = null)
        advanceUntilIdle()
        assertEquals(0L, vm.imageCacheSize.value)

        vm.clearImageCache()
        advanceUntilIdle()
        // 明确告知不支持，而不是假装清了 0 B
        assertTrue(vm.message.value.orEmpty().contains("不支持"))
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
        override suspend fun removeBooks(items: List<com.novelreader.domain.model.MediaItem>) = Unit
        override suspend fun clearAll() = Unit
        override suspend fun totalSize(): Long = 0L
        override suspend fun totalCount(): Int = 0
        override suspend fun cacheBooks(): List<BookCacheSummary> = emptyList()
    }
}
