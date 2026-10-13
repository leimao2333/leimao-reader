package com.novelreader.source.moontv

import com.google.gson.JsonObject
import com.google.gson.JsonParser

/**
 * MoonTV / LunaTV 单个采集站点。
 *
 * 配置文件里的 `api_site` 是一个 `站点标识 -> {name, api, detail}` 的字典，
 * 每个条目本质上就是一个「苹果 CMS(maccms10) 采集接口源」。
 */
data class MoonTvSite(
    /** 站点标识（通常是域名，配置 JSON 的 key） */
    val key: String,
    /** 展示名 */
    val name: String,
    /** 采集接口地址（`.../api.php/provide/vod`） */
    val api: String,
    /** 站点主页（可选） */
    val detail: String = "",
) {
    /**
     * 本地源 id：由站点标识推导，保证「同一份配置重复导入原地更新」、
     * 「重启后按 id 精确恢复」。
     */
    val sourceId: String
        get() = "moontv_" + key.trim().replace(Regex("[^A-Za-z0-9_.-]"), "_")
}

/**
 * MoonTV / LunaTV 配置解析。
 *
 * 支持两种输入：
 * 1. **Base58 编码的 JSON**（订阅链接 `*.txt`、`?format=2&source=xxx`）——自动解码；
 * 2. 原始 JSON（`*.json`、直接粘贴配置内容）。
 *
 * 配置结构（节选）：
 * ```json
 * {
 *   "cache_time": 7200,
 *   "api_site": {
 *     "iqiyizyapi.com": {
 *       "name": "🎬-爱奇艺-",
 *       "api": "https://iqiyizyapi.com/api.php/provide/vod",
 *       "detail": "https://iqiyizyapi.com"
 *     }
 *   }
 * }
 * ```
 */
object MoonTvConfig {

    /** 配置中的站点字典字段 */
    const val KEY_API_SITE = "api_site"

    /**
     * 订阅内容可能是 Base58，先尝试解包。
     *
     * 只有「整段不含 `{`」且「符合 Base58 特征」时才解码，
     * 避免把正常 JSON 误伤。
     */
    fun unwrap(raw: String): String {
        val text = raw.trim()
        if (text.contains('{')) return text
        if (!Base58.looksLikeBase58(text)) return text
        val decoded = Base58.decodeToStringOrNull(text) ?: return text
        return if (decoded.trim().startsWith("{")) decoded else text
    }

    /** 是否为 MoonTV / LunaTV 配置（含 `api_site` 字典） */
    fun isConfig(raw: String): Boolean = runCatching {
        val obj = JsonParser.parseString(unwrap(raw))
        obj.isJsonObject && obj.asJsonObject.has(KEY_API_SITE)
    }.getOrDefault(false)

    /**
     * 解析配置文件 → 站点列表（非法条目跳过）。
     *
     * 兼容两种写法：
     * - `"key": { "name": ..., "api": ... }`
     * - `"key": "https://.../api.php/provide/vod"`（把接口地址直接写为值）
     */
    fun parseConfig(raw: String): List<MoonTvSite> {
        val root = runCatching { JsonParser.parseString(unwrap(raw)) }.getOrNull() ?: return emptyList()
        if (!root.isJsonObject) return emptyList()
        val siteObj = root.asJsonObject.get(KEY_API_SITE) ?: return emptyList()
        if (!siteObj.isJsonObject) return emptyList()

        val result = mutableListOf<MoonTvSite>()
        val seen = mutableSetOf<String>()
        for ((key, value) in siteObj.asJsonObject.entrySet()) {
            val k = key.trim()
            if (k.isEmpty()) continue
            val site = when {
                value.isJsonObject -> {
                    val o = value.asJsonObject
                    MoonTvSite(
                        key = k,
                        name = o.stringOrEmpty("name").ifBlank { k },
                        api = o.stringOrEmpty("api"),
                        detail = o.stringOrEmpty("detail"),
                    )
                }
                value.isJsonPrimitive -> MoonTvSite(key = k, name = k, api = value.asString.trim())
                else -> null
            } ?: continue
            if (site.api.isBlank()) continue
            if (!site.api.startsWith("http")) continue
            if (seen.add(site.sourceId)) result.add(site)
        }
        return result
    }

    /** 单个站点 → 原始 JSON（持久化用；重导入时解析回同结构） */
    fun siteToRaw(site: MoonTvSite): String = JsonObject().apply {
        addProperty("key", site.key)
        addProperty("name", site.name)
        addProperty("api", site.api)
        addProperty("detail", site.detail)
    }.toString()

    /** 单站点原始 JSON → 站点（含旧版本兼容） */
    fun siteFromRaw(raw: String): MoonTvSite? = runCatching {
        val o = JsonParser.parseString(raw.trim()).asJsonObject
        val api = o.stringOrEmpty("api")
        if (api.isBlank()) return null
        MoonTvSite(
            key = o.stringOrEmpty("key").ifBlank { api.substringAfter("//").substringBefore('/') },
            name = o.stringOrEmpty("name").ifBlank { o.stringOrEmpty("key") },
            api = api,
            detail = o.stringOrEmpty("detail"),
        )
    }.getOrNull()

    /**
     * 是否为「单站点 JSON」。
     *
     * 与自研 JSON 源区分：自研源必须带 `search` 规则；本格式只有 `api`（+可选 name/detail）。
     */
    fun looksLikeSite(raw: String): Boolean = runCatching {
        val o = JsonParser.parseString(raw.trim())
        if (!o.isJsonObject) return false
        val jo = o.asJsonObject
        jo.has("api") && !jo.has("search") && !jo.has("bookSourceName") && !jo.has(KEY_API_SITE)
    }.getOrDefault(false)

    private fun JsonObject.stringOrEmpty(name: String): String =
        get(name)?.takeIf { it.isJsonPrimitive }?.asString?.trim().orEmpty()
}
