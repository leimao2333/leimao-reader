package com.novelreader.source.legado

import com.novelreader.domain.model.SourceFormat
import com.novelreader.domain.model.SourceInfo
import com.novelreader.domain.model.SourceType
import java.util.UUID

/**
 * legado 书源加载器。
 *
 * 将单个 legado 书源 JSON 解析为统一的 [SourceInfo]。
 */
class LegadoSourceLoader {

    /**
     * 从单个 legado 书源 JSON 字符串加载为 SourceInfo。
     */
    fun load(rawContent: String, fileName: String? = null): SourceInfo {
        val src = LegadoBookSource.parseOne(rawContent)
        return toSourceInfo(src, fileName)
    }

    /** 批量加载 legado 书源列表 */
    fun loadList(rawContent: String): List<SourceInfo> {
        val sources = LegadoBookSource.parseList(rawContent)
        return sources.map { toSourceInfo(it, null) }
    }

    private fun toSourceInfo(src: LegadoBookSource, fileName: String?): SourceInfo {
        val type = when (src.type) {
            2 -> SourceType.COMIC
            3 -> SourceType.VIDEO
            else -> SourceType.NOVEL
        }
        val id = fileName?.substringBeforeLast(".")?.ifBlank { null }
            ?: "legado_${src.name.hashCode().toString().replace("-", "_")}_${UUID.randomUUID().toString().take(6)}"

        return SourceInfo(
            id = id,
            name = src.name,
            type = type,
            version = "legado",
            author = src.group,
            desc = src.comment,
            baseUrl = src.url,
            format = SourceFormat.LEGADO,
        )
    }
}
