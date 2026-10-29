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
            // v1.9.2.7：兜底改成**站点根域**而不是图片自身域名。
            // 图床多为独立子域 + 防盗链（只认站点页面的 Referer），
            // 拿 `images.xxx.com` 当 Referer 会被 CDN 边缘直接拦成一张 HTML 拦截页 ——
            // 封面全空白且没有任何错误码可看。详见 [ImageReferer]。
            ImageReferer.of(request.url.toString())?.let { builder.header("Referer", it) }
        }
        if ("accept" !in declared) builder.header("Accept", IMAGE_ACCEPT)
        return chain.proceed(builder.build())
    }

    companion object {
        /**
         * 图片请求 UA（v1.9.2.3 起对齐 venera 官方的 `webUA`，桌面版 Chrome）。
         *
         * 旧值是 Android 移动版 Chrome UA。改桌面版的依据：上游 `lib/foundation/consts.dart`
         * 的 `webUA` 与 `lib/network/images.dart`「源没声明 UA 时补 webUA」用的都是桌面 UA；
         * 且部分站点会按 `Mobile` 分词返回移动版页面 / 移动版图床分支。
         * 详见 [com.novelreader.core.network.UserAgents]。
         */
        const val IMAGE_USER_AGENT = com.novelreader.core.network.UserAgents.WEB

        const val IMAGE_ACCEPT = "image/avif,image/webp,image/apng,image/*,*/*;q=0.8"
    }
}
