package com.novelreader.core.media

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import java.io.File

/**
 * 视频播放的**边播边缓存**（v1.7.7.0）。
 *
 * 两个目的，一个实现：
 * 1. **流畅**：播放的同时把分片落盘，网络抖动 / 回退重播 / 快退重看都直接从本地读，
 *    不再每次都重新拉一遍（采集站的 m3u8 分片大多只给一次机会，重连就是几秒空白）。
 * 2. **可展示**：本地已缓存的字节数可以查出来，配合播放器自身的 `bufferedPosition`
 *    在进度条上把「已缓冲」画出来。
 *
 * 用 media3 官方的 [SimpleCache] + [CacheDataSource]，`LeastRecentlyUsed` 上限 512MB，
 * 超了自动淘汰最久未用的分片——不需要用户手动清缓存，也不会悄悄吃掉几个 G。
 *
 * 进程内**只能有一个** SimpleCache 实例（同时打开同一个目录会抛
 * `IllegalStateException: Another SimpleCache instance uses the folder`），
 * 所以这里做成单例并由 [init] 在 AppContainer 里初始化一次。
 *
 * 注意：HLS 的缓存单位是**分片**（key 是分片 URL），所以 `cachedBytes` 是「这部片子
 * 已经落盘多少字节」，**不等于**「时间轴上缓存到第几分钟」。进度条上的缓冲段用的是
 * 播放器自己的 `bufferedPosition`（那才是时间轴语义），磁盘缓存只做加速与状态展示。
 */
@OptIn(UnstableApi::class)
object VideoMediaCache {

    /** 磁盘缓存上限：512MB（LRU 淘汰，不占用户可见的「已下载」里） */
    private const val MAX_BYTES = 512L * 1024 * 1024

    /** 网络超时：比默认 8s 更宽，弱网下不至于一个分片重连就断整条流 */
    private const val CONNECT_TIMEOUT_MS = 15_000
    private const val READ_TIMEOUT_MS = 20_000

    @Volatile
    private var cache: SimpleCache? = null

    @Volatile
    private var database: StandaloneDatabaseProvider? = null

    /** 是否已初始化（UI 侧判断要不要显示「已缓存」相关文案） */
    val ready: Boolean get() = cache != null

    /**
     * 初始化磁盘缓存。重复调用安全（AppContainer 只调一次，这里再兜一层并发）。
     */
    fun init(context: Context) {
        if (cache != null) return
        synchronized(this) {
            if (cache != null) return
            val dir = File(context.applicationContext.cacheDir, "video_media")
            val db = StandaloneDatabaseProvider(context.applicationContext)
            database = db
            cache = SimpleCache(dir, LeastRecentlyUsedCacheEvictor(MAX_BYTES), db)
        }
    }

    /**
     * 上游 HTTP 数据源（带防盗链请求头）。
     *
     * 播放器原来直接用 `DefaultHttpDataSource.Factory()`，此处顺手把超时放宽 ——
     * 采集站分片偶发 5~10s 才响应，默认 8s 读超时会把正常但慢的分片判死。
     */
    fun httpFactory(headers: Map<String, String>): DefaultHttpDataSource.Factory =
        DefaultHttpDataSource.Factory()
            .setUserAgent("NovelReader/1.0")
            .setDefaultRequestProperties(headers)
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(CONNECT_TIMEOUT_MS)
            .setReadTimeoutMs(READ_TIMEOUT_MS)

    /**
     * 带缓存的 DataSource 工厂，交给 `DefaultMediaSourceFactory` 使用。
     *
     * 未初始化时退化为纯网络数据源（功能不受影响，只是没有缓存）。
     */
    fun dataSourceFactory(headers: Map<String, String>): DataSource.Factory {
        val c = cache ?: return httpFactory(headers)
        return CacheDataSource.Factory()
            .setCache(c)
            .setUpstreamDataSourceFactory(httpFactory(headers))
            // 缓存读失败时回退到网络，而不是让整条流报错
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
    }

    /**
     * 某个地址已经落盘的字节数（HLS 分片按 URL 分别缓存，这里按前缀汇总不了，
     * 因此主要用于「当前分片/整文件」这类粗粒度展示）。
     */
    fun cachedBytes(url: String): Long = runCatching {
        cache?.getCachedBytes(url, 0L, C.LENGTH_UNSET.toLong()) ?: 0L
    }.getOrDefault(0L)

    /** 磁盘缓存当前占用（供「清除缓存」等场景展示） */
    fun cacheSizeBytes(): Long = runCatching { cache?.cacheSpace ?: 0L }.getOrDefault(0L)

    /** 清空磁盘缓存（播放中调用会中断当前流的缓存写入，因此只在离开播放页后调用） */
    fun clear() {
        runCatching { cache?.release() }
        cache = null
        runCatching { database?.close() }
        database = null
    }
}
