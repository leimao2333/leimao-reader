package com.novelreader.core.network

import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl

/**
 * 进程内 Cookie 容器（v1.5.0.0 起；v1.8.5.2 两次修正）。
 *
 * **为什么必须自己实现**：OkHttp 默认的 `CookieJar.NO_COOKIES` 一条都不存，
 * 而采集站 / 出版方大量依赖 Cookie 做会话（登录态、反爬校验、分页偏好）。
 *
 * ## v1.8.5.2 修的两个真问题
 *
 * **一、按请求 host 分区，域级 Cookie 送不到兄弟域名。**
 * 老实现用 `cookieStore[url.host]` 存取。可出版方的会话 Cookie 常常是
 * `Domain=.nature.com` 这种**域级** Cookie，由 `idp.nature.com` 种下、
 * 却要发给 `www.nature.com`。按 host 分区时两边互相看不见，
 * 「设 Cookie → 跳回原地址校验」永远不成立，于是一路重定向到 OkHttp 的
 * 20 次上限，报 `Too many follow-up requests: 21`。
 * 实测 Nature 的 PDF 直链正是这条链：
 * `www.nature.com/articles/….pdf` → `idp.nature.com/authorize`（种 idp_session）
 * → `idp.nature.com/transit` → `www.nature.com/articles/….pdf` → 文章页。
 * 现在改成**平铺存储 + `Cookie.matches(url)` 匹配**，域 / path / secure
 * 全部交给 OkHttp 自己的规则判断。
 *
 * **二、每次响应整份替换。** `saveFromResponse` 每次只带**这一次响应**新设的
 * Cookie，整份替换会把上一次响应种的 Cookie 冲掉。现在按
 * `name + path + domain` 合并，同键后写覆盖，并按过期时间清理
 * （过期的 `Set-Cookie` 就是服务端的删除指令）。
 */
internal class MemoryCookieJar : CookieJar {

    /** 平铺存放所有 Cookie：域级 Cookie 必须能跨子域命中，不能按请求 host 分区 */
    private val cookies = mutableListOf<Cookie>()

    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        if (cookies.isEmpty()) return
        synchronized(this.cookies) {
            val now = System.currentTimeMillis()
            cookies.forEach { incoming ->
                this.cookies.removeAll {
                    it.name == incoming.name &&
                        it.path == incoming.path &&
                        it.domain == incoming.domain
                }
                if (incoming.expiresAt > now) this.cookies.add(incoming)
            }
            this.cookies.removeAll { it.expiresAt <= now }
            // 兜底上限：正常站点几十条就够，超过说明有站点在刷 Cookie
            if (this.cookies.size > MAX_COOKIES) {
                this.cookies.sortBy { it.expiresAt }
                while (this.cookies.size > MAX_COOKIES) this.cookies.removeAt(0)
            }
        }
    }

    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        synchronized(cookies) {
            val now = System.currentTimeMillis()
            cookies.removeAll { it.expiresAt <= now }
            // matches 校验 domain（含 .nature.com 这类域级）、path 与 secure
            return cookies.filter { it.matches(url) }
        }
    }

    /** 测试用：当前保存的 Cookie 条数 */
    internal fun count(): Int = synchronized(cookies) { cookies.size }

    private companion object {
        /** Cookie 条数上限（防止异常站点无限塞） */
        const val MAX_COOKIES = 512
    }
}
