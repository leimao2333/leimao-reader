package com.novelreader.data.cache

import com.novelreader.core.media.VideoMediaCache

/**
 * 视频播放缓存（边播边存）的读写入口（v1.7.7.1）。
 *
 * 抽成接口是为了让「缓存管理」页的 ViewModel 可测 —— 真实实现要碰
 * media3 的 `SimpleCache`（需要 Android Context / 磁盘），JVM 单测跑不了；
 * 注入假实现就能断言「清空后大小归零、提示文案带上了释放的容量」。
 */
interface VideoPlayCacheStore {

    /** 当前磁盘占用（字节） */
    fun sizeBytes(): Long

    /** 清空并重建，返回释放的字节数 */
    fun clear(): Long
}

/** 真实实现：委托给 media3 的 [VideoMediaCache] */
object VideoMediaCacheStore : VideoPlayCacheStore {
    override fun sizeBytes(): Long = VideoMediaCache.cacheSizeBytes()
    override fun clear(): Long = VideoMediaCache.clearAndReset()
}
