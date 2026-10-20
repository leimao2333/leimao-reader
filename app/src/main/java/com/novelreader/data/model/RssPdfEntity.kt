package com.novelreader.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 导入到订阅书架的本地 PDF（Room，v1.8.6.0 待办 #29）。
 *
 * 只记**元数据**：文件本体复制到 `filesDir/rss_pdf/<fileName>`，删除时按
 * [fileName] 同步清理，避免只删库记录留下孤儿文件。
 *
 * 不进 `books` 表：那是小说 / 漫画书架（BookEntity 挂着章节缓存、阅读进度、
 * 分组那一整套），PDF 只是「导入即读」的静态文档，借那张表会被书架逻辑
 * （长按删除清进度、分组筛选…）缠上不该有的行为。
 */
@Entity(tableName = "rss_pdfs")
data class RssPdfEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    /** 展示名（一般是文件名去扩展名，导入时保留原名） */
    val name: String,
    /** `filesDir/rss_pdf/` 下的文件名（UUID + .pdf，防重名覆盖） */
    val fileName: String,
    /** 文件字节数 */
    val size: Long,
    /** 导入时间（毫秒） */
    val addedAt: Long = System.currentTimeMillis(),
)
