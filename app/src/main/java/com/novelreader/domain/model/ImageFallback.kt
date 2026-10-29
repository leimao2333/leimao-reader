package com.novelreader.domain.model

/**
 * 图片加载失败后向源要到的「下一份可用地址」（v1.9.2.3 扩展）。
 *
 * 两种来源：
 * 1. **备用镜像**：源在 `onImageLoad` 里给出的 `onLoadFailed`（禁漫天堂等有 5 条线路）。
 * 2. **惰性解析**：图库类源一章动辄几百页，打开章节时只预解析前若干张，其余图片
 *    会带着 `venera-image:` 占位地址下发；等它真正进入视口、加载失败时再回来解析。
 *
 * @param url 新地址
 * @param headers 该地址需要的请求头（签名 / 防盗链）
 * @param script 逐图后处理脚本（源的 `modifyImage`，乱序还原用）；没有则为 null
 */
data class ImageFallback(
    val url: String,
    val headers: Map<String, String> = emptyMap(),
    val script: String? = null,
)
