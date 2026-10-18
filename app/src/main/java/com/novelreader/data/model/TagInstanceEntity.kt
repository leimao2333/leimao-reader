package com.novelreader.data.model

import androidx.room.Entity
import androidx.room.Index

/**
 * 标签实例（v1.8.1.2「中间标签层」）。
 *
 * 这是整套自建标签体系的**存储骨架**，对应服务端方案里 Elasticsearch 的 nested 标签对象：
 * 每个标签单独一行，而不是把一部作品的标签拼成一个字符串。
 *
 * 为什么要拆开：拼串存储时，`tags.name:"热血" AND tags.category:"题材"` 这类查询会
 * **跨标签误匹配**（题材命中一个标签、受众命中另一个标签也算命中）。拆成实例行后，
 * 「同一部作品内必须同时存在这几个标签」可以用 `GROUP BY ... HAVING` 精确表达。
 *
 * 四个字段对应方案里的标签对象：
 * - [tagName]：受控词表里的标准标签名（不是源站原始字符串）
 * - [category]：标签大类（题材 / 画风 / 受众 / 成人）
 * - [confidence]：置信度 0~100，查询时可做阈值过滤
 * - [origin]：来源（站方 / 文本 / 行为 / 视觉），用于加权与可信度判断
 */
@Entity(
    tableName = "tag_instances",
    primaryKeys = ["sourceId", "itemId", "tagName"],
    indices = [
        Index(value = ["itemType"]),
        Index(value = ["tagName"]),
        Index(value = ["itemType", "tagName"]),
    ],
)
data class TagInstanceEntity(
    /** 源 id */
    val sourceId: String,
    /** 源内条目 id */
    val itemId: String,
    /** 内容类型（NOVEL / COMIC / VIDEO） */
    val itemType: String,
    /** 标准标签名（受控词表） */
    val tagName: String,
    /** 标签大类：题材 / 画风 / 受众 / 成人 */
    val category: String = "题材",
    /** 置信度 0~100 */
    val confidence: Int = 0,
    /** 来源：站方 / 文本 / 行为 / 视觉 */
    val origin: String = "文本",
    /** 最近更新时间 */
    val updatedAt: Long = 0L,
)
