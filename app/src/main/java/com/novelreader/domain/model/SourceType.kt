package com.novelreader.domain.model

/**
 * 内容类型枚举。
 *
 * 三大内容形态：小说 / 漫画 / 视频。
 * 预留扩展：未来新增内容类型时，在此追加枚举并实现对应 Adapter。
 */
enum class SourceType {
    NOVEL,   // 小说
    COMIC,   // 漫画
    VIDEO,   // 视频
    ;

    companion object {
        /** 从字符串解析类型（源文件中的 type 字段），未知值返回 null */
        fun fromString(value: String?): SourceType? = when (value?.lowercase()?.trim()) {
            "novel", "小说", "book" -> NOVEL
            "comic", "漫画", "manga", "manhua" -> COMIC
            "video", "视频", "tv", "movie", "anime" -> VIDEO
            else -> null
        }
    }
}
