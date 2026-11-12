package com.novelreader.core.media

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.datasource.okhttp.OkHttpDataSource
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

    /** 网络超时由 [VideoTls] 的播放专用 OkHttp 客户端统一管理 */
    @Volatile
    private var cache: SimpleCache? = null

    @Volatile
    private var database: StandaloneDatabaseProvider? = null

    /** 保存 applicationContext：清空缓存后要能自己重建，调用方不必再传 */
    @Volatile
    private var appContext: Context? = null

    /** 是否已初始化（UI 侧判断要不要显示「已缓存」相关文案） */
    val ready: Boolean get() = cache != null

    /**
     * 初始化磁盘缓存。重复调用安全（AppContainer 只调一次，这里再兜一层并发）。
     */
    fun init(context: Context) {
        if (cache != null) return
        synchronized(this) {
            if (cache != null) return
            appContext = context.applicationContext
            val dir = File(context.applicationContext.cacheDir, "video_media")
            val db = StandaloneDatabaseProvider(context.applicationContext)
            database = db
            cache = SimpleCache(dir, LeastRecentlyUsedCacheEvictor(MAX_BYTES), db)
        }
    }

    /**
     * 上游 HTTP 数据源（带防盗链请求头）。
     *
     * v1.9.5.1 起改用 [OkHttpDataSource] + [VideoTls]：
     * Media3 默认的 `DefaultHttpDataSource` 走系统 HttpsURLConnection，证书一过期
     * （采集站 CDN 很常见）整条 m3u8 就播不了，而首页 / 搜索 / 详情都正常，
     * 极难排查。OkHttp 客户端只放宽「证书已过期」这一种校验，其余照旧严格。
     * 超时由 [VideoTls] 统一放宽，弱网下慢分片不会被默认 8s 读超时判死。
     *
     * 纯网络、不带广告过滤 —— 过滤要放在**缓存之外**，见 [dataSourceFactory]。
     */
    private fun httpFactory(headers: Map<String, String>): DataSource.Factory =
        OkHttpDataSource.Factory(VideoTls.client())
            .setUserAgent("NovelReader/1.0")
            .setDefaultRequestProperties(headers)

    /**
     * 不走磁盘缓存、但带广告过滤的网络数据源（v1.9.6.0）。
     *
     * 「跳过缓存重播」分支用它：既不要旧缓存（上一次可能是坏网页），又要过滤广告。
     */
    fun networkFactory(headers: Map<String, String>): DataSource.Factory =
        AdFilterDataSource.factory(httpFactory(headers))

    /**
     * 带缓存 + 广告过滤的 DataSource 工厂，交给 `DefaultMediaSourceFactory` 使用。
     *
     * 过滤放在缓存**外面**（v1.9.6.0）：缓存里存的是站点原样的 m3u8，每次读清单时
     * 现过滤。好处是改过滤规则不用清缓存、关掉开关立刻恢复原样。
     * 未初始化缓存时退化为纯网络数据源（功能不受影响，只是没有缓存）。
     */
    fun dataSourceFactory(headers: Map<String, String>): DataSource.Factory {
        val c = cache ?: return networkFactory(headers)
        return AdFilterDataSource.factory(
            CacheDataSource.Factory()
                .setCache(c)
                .setUpstreamDataSourceFactory(httpFactory(headers))
                // 缓存读失败时回退到网络，而不是让整条流报错
                .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR),
        )
    }

    /**
     * 直接拿一个「写进同一份缓存」的数据源（v1.8.4.3，尾段预缓冲用）。
     *
     * 预缓冲下一集时用它 + `CacheWriter` 把分片写进 [cache] —— key 与播放器
     * 自己读分片时算出来的 key 一致（都是分片 URL 字符串），所以播放器切到下一集
     * 就是**缓存命中**，不再重新下载。未初始化时返回 null（预缓冲直接跳过）。
     */
    fun cacheDataSource(headers: Map<String, String>): CacheDataSource? {
        val c = cache ?: return null
        return CacheDataSource.Factory()
            .setCache(c)
            .setUpstreamDataSourceFactory(httpFactory(headers))
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
            .createDataSource() as? CacheDataSource
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

    /**
     * 丢掉某个地址的缓存资源（v1.9.5.1）。
     *
     * 采集站偶尔会把 HTML 播放页 / 错误页当作 m3u8 返回，若这份内容被当成
     * 播放列表缓存下来，之后即使站点恢复也会一直读到旧网页，报「不是有效视频」。
     * 播放失败并确认网络上已是正常流时，清掉这一条再重播即可，不影响其它分片。
     */
    fun invalidate(url: String) {
        runCatching { cache?.removeResource(url) }
    }

    /**
     * 清空磁盘缓存并**立即重建**（v1.7.7.1）。
     *
     * 只 release 不重建会让清完之后彻底没有缓存（`init` 只在 App 启动时跑一次），
     * 用户清完缓存继续看视频反而更卡，所以这里清完就重新开一个。
     * 重建失败也只是退化成「无缓存」，不影响播放。
     *
     * @return 释放的字节数（用于提示文案）
     *
     * ⚠️ 播放中调用会让当前流重新拉分片（画面可能短暂卡一下），
     * 因此缓存管理页的确认框要写明这一点。
     */
    fun clearAndReset(): Long {
        val ctx = appContext ?: return 0L
        synchronized(this) {
            val freed = runCatching { cache?.cacheSpace ?: 0L }.getOrDefault(0L)
            runCatching { cache?.release() }
            cache = null
            runCatching { database?.close() }
            database = null
            val dir = File(ctx.cacheDir, "video_media")
            runCatching { dir.deleteRecursively() }
            runCatching {
                val db = StandaloneDatabaseProvider(ctx)
                database = db
                cache = SimpleCache(dir, LeastRecentlyUsedCacheEvictor(MAX_BYTES), db)
            }
            return freed
        }
    }
}
