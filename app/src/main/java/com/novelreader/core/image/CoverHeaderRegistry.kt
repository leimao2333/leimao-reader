package com.novelreader.core.image

import java.util.concurrent.ConcurrentHashMap

/**
 * 封面请求头注册表（v1.2.11.0 漫画封面修复）。
 *
 * 大量漫画站对图片（含封面）做防盗链：要求 `Referer` 指向站点自身、
 * 校验 `User-Agent`，甚至要求特定 Cookie。书架 / 搜索 / 详情的封面由 Coil
 * 直接加载，拿不到源上下文；适配器在解析出「站点 / 图床请求头」时把
 * `host → headers` 注册到这里，全局图片加载器的 [CoverDefaultsInterceptor]
 * 按 URL host 匹配后补齐请求头。
 *
 * 匹配规则：精确 host 优先，其次逐级父域（`img.example.com` 命中
 * `example.com` 的注册条目）。
 */
object CoverHeaderRegistry {

    private val byHost = ConcurrentHashMap<String, Map<String, String>>()

    /** 注册一组请求头；同名 host 后注册覆盖先注册 */
    fun register(host: String, headers: Map<String, String>) {
        val h = host.trim().lowercase()
        if (h.isBlank() || headers.isEmpty()) return
        byHost[h] = headers.filterKeys { it.isNotBlank() }.filterValues { it.isNotBlank() }
    }

    /** 按 URL host 查请求头；精确匹配优先，未命中时逐级回退父域 */
    fun find(urlHost: String?): Map<String, String> {
        val host = urlHost?.trim()?.lowercase()?.takeIf { it.isNotBlank() } ?: return emptyMap()
        byHost[host]?.let { return it }
        val parts = host.split('.')
        for (i in 1 until parts.size - 1) {
            byHost[parts.subList(i, parts.size).joinToString(".")]?.let { return it }
        }
        return emptyMap()
    }
}
