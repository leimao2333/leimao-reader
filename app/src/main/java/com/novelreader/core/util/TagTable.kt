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

    /**
     * 云端版本清单（`data/manifest.json`，v1.8.2.0）。
     *
     * 存在的意义是**省流量**：打标表十几 MB，而这份清单只有几百字节。
     * App 先拉清单比对 [generatedAt]，只有云端确实更新了才去下载整表。
     */
    data class Manifest(
        val latest: String = "tag_table.json",
        val generatedAt: Long = 0L,
        val updatedAt: Long = 0L,
        val count: Int = 0,
        val taggedCount: Int = 0,
        val bytes: Long = 0L,
        val sha256: String = "",
        val types: Map<String, Int> = emptyMap(),
        val sources: Int = 0,
        val archive: List<String> = emptyList(),
    )

    private val gson = GsonBuilder().disableHtmlEscaping().create()

    fun parse(text: String): Payload? = runCatching {
        val obj = com.google.gson.JsonParser.parseString(text).asJsonObject
        // 必须真的带 items 字段 —— 全缺省的 JSON 会被 Kotlin 无参构造填成默认值，不算合法表
        if (!obj.has("items")) return null
        gson.fromJson(obj, Payload::class.java)
    }.getOrNull()

    /**
     * 流式解析打标表（v1.8.8.3）。
     *
     * 大标签文件（几十 MB 到上百 MB）不能整表读进内存再反序列化 ——
     * 旧的 [parse] 会同时驻留「原始字节 + 整段字符串 + 反序列化后的 Payload」三份，
     * 大文件直接 OOM。这里改用 Gson 的 [com.google.gson.stream.JsonReader] 逐条解析：
     * 每读完一个 item 就回调给上层（上层按类型分流、分块写库），内存峰值从
     * 「整表 × 3」降到「单条 item」级别。
     *
     * 约定：先回调顶层元数据（version / generatedAt / source），再逐条回调 items。
     * 回调抛出的异常会向上传播（上层用它做取消 / 条数校验）。
     *
     * @param reader 已打开的输入（只读，不负责关闭，由调用方关闭）
     * @param onMeta 读到顶层元数据时回调一次（version / generatedAt / source）
     * @param onItem 每读到一条 item 回调一次（suspend：上层可在此做写库 / 取消检查）
     * @return 表内 item 总数
     */
    suspend fun parseItems(
        reader: java.io.Reader,
        onMeta: (version: Int, generatedAt: Long, source: String) -> Unit,
        onItem: suspend (Item) -> Unit,
    ): Int {
        var version = VERSION
        var generatedAt = 0L
        var source = SOURCE_APP
        var count = 0

        val json = com.google.gson.stream.JsonReader(reader)
        json.isLenient = true
        json.beginObject()
        while (json.hasNext()) {
            when (json.nextName()) {
                "version" -> version = json.nextInt()
                "generatedAt" -> generatedAt = json.nextLong()
                "source" -> source = json.nextString().ifBlank { SOURCE_APP }
                "items" -> {
                    json.beginArray()
                    while (json.hasNext()) {
                        onItem(readItem(json))
                        count++
                    }
                    json.endArray()
                }
                else -> json.skipValue()
            }
        }
        json.endObject()
        onMeta(version, generatedAt, source)
        return count
    }

    /** 流式读单条 item（字段名与 [Item] 的 Gson 序列化一致） */
    private fun readItem(json: com.google.gson.stream.JsonReader): Item {
        var sourceId = ""
        var itemId = ""
        var itemType = ""
        var title = ""
        var cover = ""
        var detailUrl = ""
        var sourceName = ""
        var author = ""
        var tags: List<Entry> = emptyList()

        json.beginObject()
        while (json.hasNext()) {
            when (json.nextName()) {
                "sourceId" -> sourceId = json.nextString()
                "itemId" -> itemId = json.nextString()
                "itemType" -> itemType = json.nextString()
                "title" -> title = json.nextString()
                "cover" -> cover = json.nextString()
                "detailUrl" -> detailUrl = json.nextString()
                "sourceName" -> sourceName = json.nextString()
                "author" -> author = json.nextString()
                "tags" -> {
                    val list = ArrayList<Entry>()
                    json.beginArray()
                    while (json.hasNext()) {
                        list += readEntry(json)
                    }
                    json.endArray()
                    tags = list
                }
                else -> json.skipValue()
            }
        }
        json.endObject()
        return Item(sourceId, itemId, itemType, title, cover, detailUrl, sourceName, author, tags)
    }

    /** 流式读单条标签 */
    private fun readEntry(json: com.google.gson.stream.JsonReader): Entry {
        var name = ""
        var category = "题材"
        var confidence = 0
        var origin = "文本"
        json.beginObject()
        while (json.hasNext()) {
            when (json.nextName()) {
                "name" -> name = json.nextString()
                "category" -> category = json.nextString()
                "confidence" -> confidence = json.nextInt()
                "origin" -> origin = json.nextString()
                else -> json.skipValue()
            }
        }
        json.endObject()
        return Entry(name, category, confidence, origin)
    }

    /** 解析云端清单；连 count / latest 都没有的一律当无效（防止把 HTML 错误页当清单） */
    fun parseManifest(text: String): Manifest? = runCatching {
        val obj = com.google.gson.JsonParser.parseString(text).asJsonObject
        if (!obj.has("count") && !obj.has("latest")) return null
        gson.fromJson(obj, Manifest::class.java)
    }.getOrNull()

    fun toJson(payload: Payload): String = gson.toJson(payload)

    /**
     * 合并单条标签：同名字段取置信度高的一方。
     * 置信度相同保留既有数据（已有来源优先，结果稳定可复现）。
     */
    fun mergeEntry(existing: Entry?, incoming: Entry): Entry =
        if (existing == null || incoming.confidence > existing.confidence) incoming else existing
}
