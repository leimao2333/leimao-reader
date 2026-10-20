package com.novelreader.data.cache

import android.content.Context
import coil.imageLoader

/**
 * 图片磁盘缓存（Coil `DiskCache`）的读写入口（v1.8.4.2）。
 *
 * 漫画内页图片和封面都走全局那一个 Coil `ImageLoader`，字节落在
 * `cacheDir/image_cache`（上限 512MB，LRU 自动淘汰最久未用的）。
 * 这块缓存以前**只进不出**：缓存管理页看不到、也没法清 —— 漫画看多了
 * 能悄悄吃掉几百 MB，用户只能去系统设置里「清除数据」。
 *
 * 抽成接口的理由同 [VideoPlayCacheStore]：真实实现要碰 Coil + Android Context
 * （还要打开 LRU journal），JVM 单测跑不了；注入假实现才能断言
 * 「刷新读到占用、清理后归零、提示带上释放的容量、一键清空也覆盖它」。
 */
interface ImageCacheStore {

    /** 当前磁盘占用（字节） */
    fun sizeBytes(): Long

    /** 清空并返回清理前的占用（字节） */
    fun clear(): Long
}

/**
 * 真实实现：委托给全局 Coil `ImageLoader` 的 `DiskCache`。
 *
 * 注意拿的是 `context.imageLoader`（Coil 的进程级单例），和
 * `NovelReaderApp.newImageLoader()` 返回的是**同一个** loader ——
 * 清理后仍在用的那个 loader 会照常重新落盘，不会「清完就再也没缓存」。
 */
class CoilImageCacheStore(context: Context) : ImageCacheStore {

    private val appContext = context.applicationContext

    /** 没配磁盘缓存 / 已关闭时返回 null，调用方按 0 处理 */
    private fun diskCache(): coil.disk.DiskCache? =
        runCatching { appContext.imageLoader.diskCache }.getOrNull()

    override fun sizeBytes(): Long =
        runCatching { diskCache()?.size ?: 0L }.getOrDefault(0L)

    override fun clear(): Long {
        val cache = diskCache() ?: return 0L
        val before = runCatching { cache.size }.getOrDefault(0L)
        // Coil 的 clear() 走 DiskLruCache.evictAll()：只删条目、缓存对象仍可用，
        // 后续加载会照常重新落盘。绝不能改成删目录 —— 那会把 LRU journal
        // 一起删掉，正在用的 loader 会一直写失败。
        runCatching { cache.clear() }
        return before
    }
}
