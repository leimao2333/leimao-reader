package com.novelreader.source.venera

import com.google.gson.JsonObject
import com.google.gson.JsonParser

/**
 * Venera 章节地址的编解码（v1.7.6.5）。
 *
 * 阅读页只需要一对 (comicId, epId) 就能让源代码的 `loadEp(comicId, epId)` 出图，
 * 但这两段**都可能自带 `|`**：
 * - H-Comic 的条目 id 是 `${c.id}|${title}`（comicId 里有竖线）；
 * - 它的章节 id 是 `${comic_source}|${media_id}|${num_pages}`（epId 里也有竖线）。
 *
 * 早期用 `"$comicId|$epId"` 拼接、再 `substringBefore/After('|')` 还原，
 * 一遇到这种源就必然错位：comicId 被截成 `11`、epId 变成 `Test Comic|abc|42|3`，
 * 于是 `loadInfo` 拼出 `/comics/view/1?id=11` 这样的错地址（404），
 * 读者看到的就是「搜得到、点进去没有内容」。
 *
 * 解法：只有**确实含分隔符**时才改用 JSON 承载（`vj:{...}`）。普通源仍然
 * 生成 `"$comicId|$epId"` —— 与旧数据、旧缓存、阅读进度完全兼容，
 * 不会因为这次修复让老用户的续读位置失效。
 */
object VeneraChapterCodec {

    /** 旧格式（也是常见格式）的分隔符 */
    const val SEP = "|"

    /** JSON 承载格式的前缀（普通 id 不会以它开头） */
    const val JSON_PREFIX = "vj:"

    /**
     * 生成章节地址。
     *
     * 两段都不含 `|` 时保持 `comicId|epId`（旧格式），否则用 JSON 承载。
     */
    fun encode(comicId: String, epId: String): String {
        if (!comicId.contains(SEP) && !epId.contains(SEP)) {
            return "$comicId$SEP$epId"
        }
        val obj = JsonObject().apply {
            addProperty("c", comicId)
            addProperty("e", epId)
        }
        return JSON_PREFIX + obj.toString()
    }

    /**
     * 还原 (comicId, epId)。
     *
     * @param url 章节地址（[encode] 的产物，可能是旧格式）
     * @param fallbackEpId 解析不出时的兜底（调用方通常传 `chapter.id`）
     */
    fun decode(url: String, fallbackEpId: String = ""): Pair<String, String> {
        val text = url.trim()
        if (text.startsWith(JSON_PREFIX)) {
            val parsed = runCatching {
                val obj = JsonParser.parseString(text.substring(JSON_PREFIX.length)).asJsonObject
                obj.get("c")?.asString.orEmpty() to obj.get("e")?.asString.orEmpty()
            }.getOrNull()
            // v1.9.2.4：`e`（epId）**允许为空**——单话作品的 epId 就是空串，
            // 含义是「loadEp 只传 comicId」。此前要求两段都非空，空 epId 的
            // JSON 形态会被判为损坏、退回旧格式解析，还原出 `{"c":...` 这样的垃圾。
            if (parsed != null && parsed.first.isNotBlank()) {
                return parsed
            }
            // 损坏的 JSON 一律退回旧格式解析，不抛异常
        }
        if (text.contains(SEP)) {
            val ep = text.substringAfter(SEP, fallbackEpId)
            return text.substringBefore(SEP) to ep.ifBlank { fallbackEpId }
        }
        return text to fallbackEpId
    }
}
