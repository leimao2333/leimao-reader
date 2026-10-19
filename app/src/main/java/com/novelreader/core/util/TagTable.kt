package com.novelreader.core.util

import com.google.gson.GsonBuilder

/**
 * 云端打标表（v1.8.1.5）。
 *
 * PC 端打标工具（仓库 `tagger/pc_tagger.py`）遍历收录清单 → 封面 WD14 推理 →
 * 产出这份 JSON 推到 GitHub；App 端「云端导入」把它合并进本地标签层。
 *
 * Schema（PC 与 App 两端共同遵守，字段只增不改）：
 *
 * ```json
 * {
 *   "version": 1,
 *   "generatedAt": 1730000000000,
 *   "source": "pc | app",
 *   "items": [{
 *     "sourceId": "", "itemId": "", "itemType": "COMIC",
 *     "title": "", "cover": "", "detailUrl": "", "sourceName": "", "author": "",
 *     "tags": [{"name": "全彩", "category": "题材", "confidence": 72, "origin": "视觉"}]
 *   }]
 * }
 * ```
 *
 * 合并口径（[mergeEntry]）：**同名标签取置信度更高的一方**，origin 跟随胜者 ——
 * PC 端的视觉 72 分盖不过 App 里站方的 100 分，但能盖过文本猜的 60 分。
 * 纯函数无 Android 依赖，方便单测。
 */
object TagTable {

    const val VERSION = 1

    /** 来源方标记（便于排查看这条数据是谁打的） */
    const val SOURCE_APP = "app"
    const val SOURCE_PC = "pc"

    data class Entry(
        val name: String = "",
        val category: String = "题材",
        val confidence: Int = 0,
        val origin: String = "文本",
    )

    data class Item(
        val sourceId: String = "",
        val itemId: String = "",
        val itemType: String = "",
        val title: String = "",
        val cover: String = "",
        val detailUrl: String = "",
        val sourceName: String = "",
        val author: String = "",
        val tags: List<Entry> = emptyList(),
    )

    data class Payload(
        val version: Int = VERSION,
        val generatedAt: Long = 0L,
        val source: String = SOURCE_APP,
        val items: List<Item> = emptyList(),
    )

    private val gson = GsonBuilder().disableHtmlEscaping().create()

    fun parse(text: String): Payload? = runCatching {
        val obj = com.google.gson.JsonParser.parseString(text).asJsonObject
        // 必须真的带 items 字段 —— 全缺省的 JSON 会被 Kotlin 无参构造填成默认值，不算合法表
        if (!obj.has("items")) return null
        gson.fromJson(obj, Payload::class.java)
    }.getOrNull()

    fun toJson(payload: Payload): String = gson.toJson(payload)

    /**
     * 合并单条标签：同名字段取置信度高的一方。
     * 置信度相同保留既有数据（已有来源优先，结果稳定可复现）。
     */
    fun mergeEntry(existing: Entry?, incoming: Entry): Entry =
        if (existing == null || incoming.confidence > existing.confidence) incoming else existing
}
