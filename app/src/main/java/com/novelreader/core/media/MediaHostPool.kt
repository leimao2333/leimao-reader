package com.novelreader.core.media

import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

/**
 * 采集源内部的 CDN 域名池（v1.9.5.5）。
 *
 * 采集站的内容通常同时挂在好几个并行 CDN 上：**同一路径在各域名互为镜像**。
 * `vod_play_url` 里只会写其中一个域名，谁到期 / 被停放，这条线路就成了死链。
 *
 * 实测：`/20240913/HCkz724H/index.m3u8` 在 `cdn2.shayubf3.com` 返回「域名出售」占位页，
 * 同一路径换到同源的 `3sybf.com` / `vip8.3sybf.com` 就是正常的 `#EXTM3U`。
 * 站点自己的播放页之所以看起来「能播」，是因为它走的是另一个域名。
 *
 * 池子只记域名（不记内容、不落盘），在解析搜索 / 详情 / 分类结果时顺带填充；
 * 播放失败时用**同一路径**去池里的其它域名上碰一次，命中即切换。
 */
object MediaHostPool {

    /** 单个源最多记多少个域名（采集站的 CDN 一般 3~8 个） */
    private const val MAX_HOSTS_PER_SOURCE = 16

    private val pools: MutableMap<String, MutableSet<String>> = ConcurrentHashMap()

    /**
     * 记下某来源出现过的播放域名。
     *
     * 用 `synchronized` 保护 `LinkedHashSet` 的插入顺序 —— 顺序有意义：
     * 最近出现的域名优先（站点换 CDN 后，新域名会自然排到前面）。
     */
    fun record(sourceId: String, hosts: Collection<String>) {
        if (sourceId.isBlank() || hosts.isEmpty()) return
        val set = pools.getOrPut(sourceId) {
            Collections.synchronizedSet(LinkedHashSet<String>())
        }
        synchronized(set) {
            hosts.forEach { host ->
                val h = host.trim().lowercase()
                if (!h.contains('.') || h.contains('/')) return@forEach
                // 已存在时先移除再插入：保证「最近使用」排在最前
                set.remove(h)
                set.add(h)
            }
            while (set.size > MAX_HOSTS_PER_SOURCE) {
                val oldest = set.firstOrNull() ?: break
                set.remove(oldest)
            }
        }
    }

    /** 把某个域名提到最前（镜像命中后调用，下次优先试它） */
    fun promote(sourceId: String, host: String) {
        record(sourceId, listOf(host))
    }

    /** 该来源已知的播放域名，最近出现的在前 */
    fun hosts(sourceId: String): List<String> {
        val set = pools[sourceId] ?: return emptyList()
        synchronized(set) { return set.toList().asReversed() }
    }

    /** 清空（测试 / 切换源库时用） */
    fun clear() = pools.clear()

    /** 取地址里的 host（含端口），非法地址返回 null */
    fun hostOf(url: String): String? = runCatching {
        val withoutScheme = url.substringAfter("://", "")
        if (withoutScheme.isEmpty()) return null
        withoutScheme.substringBefore('/').substringBefore('?').substringBefore('#')
            .takeIf { it.isNotBlank() }
    }.getOrNull()

    /**
     * 把 [url] 的域名换成 [host]，路径 / 查询串原样保留。
     *
     * 换不成（地址不是 http(s)、或没有路径）返回 null。
     */
    fun replaceHost(url: String, host: String): String? {
        if (!url.startsWith("http://") && !url.startsWith("https://")) return null
        val scheme = url.substringBefore("://")
        val rest = url.substringAfter("://")
        val path = rest.substringAfter('/', "")
        if (path.isEmpty()) return null
        return "$scheme://$host/$path"
    }
}
