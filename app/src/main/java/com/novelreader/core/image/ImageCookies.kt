package com.novelreader.core.image

/**
 * 图片请求的 Cookie 提供者（v1.9.2.8）。
 *
 * ## 为什么需要它
 *
 * 封面与正文图由 Coil 加载，走的是 `NovelReaderApp.newImageLoader()` 里那个
 * **独立的 OkHttpClient**；而源脚本的页面请求走的是 `VeneraEngine` 自己的
 * `OkHttpClient`（带 `SimpleCookieJar`）。两套客户端互不相干 —— 图片请求
 * 默认是 `CookieJar.NO_COOKIES`，**一条 Cookie 都不带**。
 *
 * 浏览器里这两件事是同一件事：站点在 `example.com` 上设的会话 Cookie，
 * 对 `images.example.com` 的图片请求同样会带上。图床把这一点当成了鉴权/防盗链
 * 的一环 —— 实测某源站（2026-10-29）：
 *
 * ```
 * 带站点会话 Cookie + Referer → 200 image/jpeg 117254B
 * 不带 Cookie，仅带 Referer    → 403 text/html（安全服务拦截页）
 * ```
 *
 * 也就是说，光补 Referer 不够，图片一定被拦成一张 HTML 拦截页 —— 而 Coil 只会
 * 把它当「解码失败」静默吞掉，用户侧就是「封面全空白、正文一张图都出不来」，
 * 日志里连个错误码都没有。
 *
 * ## 做法
 *
 * 不去共享 CookieJar 对象（那会让图片请求和源请求互相写状态），而是让
 * `VeneraEngine` 注册一个**只读查询**：给地址，回该地址能用的 Cookie 头。
 * [CoverDefaultsInterceptor] 在源没自己声明 `Cookie` 时才用它兜底。
 *
 * 查询失败 / 未注册一律返回 null，绝不影响图片加载本身。
 */
object ImageCookies {

    /** 由 `AppContainer` 在创建源引擎时注册；参数是图片地址，返回 `k=v; k2=v2` */
    @Volatile
    private var provider: ((String) -> String)? = null

    fun register(provider: (String) -> String) {
        this.provider = provider
    }

    /** 该地址可用的 Cookie 头；没有 / 出错都返回 null */
    fun headerFor(url: String): String? = runCatching {
        provider?.invoke(url)
    }.getOrNull()?.takeIf { it.isNotBlank() }
}
