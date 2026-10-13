package com.novelreader.core.image

import okhttp3.Interceptor
import okhttp3.Response

/**
 * 封面图片请求默认头拦截器（v1.2.11.0 漫画封面修复）。
 *
 * 书架 / 搜索结果 / 详情页的封面由 Coil 直接加载，此前不带任何请求头 ——
 * 而大量漫画 CDN 会按 UA / Referer 防盗链，导致「一些源封面全部显示为占位图」
 * （漫画阅读页的正文图片早就带了浏览器级默认头，所以阅读正常、封面失败）。
 *
 * 逻辑与阅读页 `applyImageDefaults` 保持同一套语义：
 * 1. [CoverHeaderRegistry] 按 host 命中源声明的请求头 → 原样应用（优先级最高）；
 * 2. 缺失时按需补齐：
 *    - `User-Agent`：Coil 默认发 `okhttp/x.y`，不少图床按 UA 拒绝；
 *    - `Referer`：退化为图片自身站点（同站点图床常据此防盗链）；
 *    - `Accept`：声明可接受的图片类型，避免 CDN 因 Accept 不匹配返回错误页。
 *
 * 对本地文件（file://）等非 HTTP 请求不会走到 OkHttp，天然无影响。
 */
class CoverDefaultsInterceptor : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val fromSource = CoverHeaderRegistry.find(request.url.host)
        val builder = request.newBuilder()
        fromSource.forEach { (k, v) -> builder.header(k, v) }

        val declared = fromSource.keys.map { it.lowercase() }.toSet()
        if ("user-agent" !in declared) builder.header("User-Agent", IMAGE_USER_AGENT)
        if ("referer" !in declared) {
            builder.header("Referer", "${request.url.scheme}://${request.url.host}/")
        }
        if ("accept" !in declared) builder.header("Accept", IMAGE_ACCEPT)
        return chain.proceed(builder.build())
    }

    companion object {
        /** 与阅读页 ComicReaderScreen 的 IMAGE_USER_AGENT 保持一致 */
        const val IMAGE_USER_AGENT =
            "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"

        const val IMAGE_ACCEPT = "image/avif,image/webp,image/apng,image/*,*/*;q=0.8"
    }
}
