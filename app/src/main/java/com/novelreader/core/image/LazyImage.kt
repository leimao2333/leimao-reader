package com.novelreader.core.image

/**
 * 惰性图片占位地址（v1.9.2.3）。
 *
 * 背景：Venera 图库类源一章动辄几百页，官方是在**图片真正要加载时**才调源的
 * `onImageLoad`（`lib/network/images.dart` 的 `getImageLoadingConfig`）；我们此前
 * 是「打开章节就把整章解析完」。实测某图站 429 页的章节光预解析就要 429 次 POST，
 * 直接撞上调用硬上限 —— 章节根本打不开。
 *
 * 现在运行时只预解析前若干张，其余图片下发 [PREFIX] 开头的占位地址：
 * - Coil 打不开（未知 scheme，本地立刻失败，**不发任何网络请求**）；
 * - 上层 `onLoadFailed` → `retryImage` → 运行时现场解析出真实地址并回填；
 * - 回填后地址变化，组件以新地址重新加载。
 *
 * 前缀必须与 `assets/venera/runtime.js` 的 `LAZY_IMAGE_PREFIX` 完全一致。
 */
object LazyImage {

    /** 与 runtime.js 的 `LAZY_IMAGE_PREFIX` 一致 */
    const val PREFIX = "venera-image:"

    /** 是否是需要现场解析的占位地址 */
    fun isLazy(url: String): Boolean = url.startsWith(PREFIX)
}
