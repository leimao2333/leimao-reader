package com.novelreader.source.venera

import com.google.gson.JsonArray
import com.google.gson.JsonParser
import com.novelreader.domain.model.SourceFormat
import com.novelreader.domain.model.SourceInfo
import com.novelreader.domain.model.SourceType

/**
 * Venera 源集合索引（`index.json`）解析。
 *
 * 索引本身**不是源**：它只是清单（`[{name,fileName,key,version}]`），
 * 每条对应同目录下的一个 `.js` 源文件，需要按 `fileName` 逐个下载再导入。
 *
 * 原先这段逻辑散落在 `SourceManageViewModel`（私有 `parseVeneraIndex` + 私有
 * `VeneraIndexEntry`），聚合包导入（v1.5.6.0）也需要同一套解析与候选映射，
 * 于是抽到这里，导入入口与聚合包共用一份实现。
 */
object VeneraIndex {

    /** 索引里的单条候选（含下载 `.js` 所需的文件名与元信息） */
    data class Entry(
        val fileName: String,
        val key: String,
        val name: String,
        val version: String,
    ) {
        /** 由文件名推导的源 id（约定见 [VeneraSourceId]，可能为空） */
        val id: String? get() = VeneraSourceId.fromFileName(fileName)
    }

    /**
     * 解析 Venera 索引。
     *
     * @return 不是索引（非数组 / 首元素无 fileName+key）返回 null；是索引则返回条目
     *   （条目可能为空列表，调用方需自行提示「未解析到有效清单」）
     */
    fun parse(content: String): List<Entry>? {
        val trimmed = content.trim()
        if (!trimmed.startsWith("[")) return null
        return runCatching {
            val arr = JsonParser.parseString(trimmed) as? JsonArray ?: return null
            val first = arr.firstOrNull()?.takeIf { it.isJsonObject }?.asJsonObject ?: return null
            if (!first.has("fileName") || !first.has("key")) return null

            arr.mapNotNull { el ->
                val obj = el.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
                val fileName = obj.get("fileName")?.asString?.trim()?.takeIf { it.isNotEmpty() }
                    ?: return@mapNotNull null
                val fallback = fileName.substringBeforeLast('.')
                Entry(
                    fileName = fileName,
                    key = obj.get("key")?.asString ?: fallback,
                    name = obj.get("name")?.asString ?: fallback,
                    version = obj.get("version")?.asString ?: "",
                )
            }
        }.getOrNull()
    }

    /**
     * 索引条目的候选 [SourceInfo]（勾选对话框展示用）。
     *
     * id 与「下载 `.js` 后真正注册时」保持一致（都由 [VeneraSourceId] 从文件名推导），
     * 否则用户勾选的 id 在注册阶段会匹配不上。
     */
    fun toSourceInfo(entry: Entry): SourceInfo = SourceInfo(
        id = entry.id ?: ("venera_" + entry.fileName.substringBeforeLast('.')),
        name = entry.name.ifBlank { entry.key.ifBlank { entry.fileName } },
        type = SourceType.COMIC,
        version = entry.version.ifBlank { "venera" },
        author = entry.key,
        desc = "",
        baseUrl = "",
        format = SourceFormat.VENERA,
        category = "漫画",
    )
}
