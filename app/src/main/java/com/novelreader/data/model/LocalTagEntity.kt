package com.novelreader.data.model

import androidx.room.Entity

/**
 * 本地标签索引（v1.8.1.2）。
 *
 * 背景：漫画源大多**没有分类页**，标签只能靠标题关键词搜 —— 搜「无码 全彩」得到的是
 * 「标题里出现这个词」的作品，而不是真正属于这两个题材的作品，复合筛选因此不成立。
 *
 * 这里换个思路：**遍历各源的列表，再逐部拉详情，把源站自己声明的题材（legado 的 `kind`、
 * Venera 的 `tags`）落到本地表**。之后标签搜索直接查这张表，按标签做真正的 AND / OR，
 * 不再受「源站有没有分类页」限制。
 *
 * 主键用 (sourceId, itemId)：同一部作品在多个源上各存一条（跨源合并由查询侧按标题做）。
 */
@Entity(tableName = "local_tags", primaryKeys = ["sourceId", "itemId"])
data class LocalTagEntity(
    /** 源 id */
    val sourceId: String,
    /** 源内条目 id */
    val itemId: String,
    /** 内容类型（NOVEL / COMIC / VIDEO），查询按类型隔离 */
    val itemType: String,
    /** 标题 */
    val title: String,
    /** 封面 */
    val cover: String? = null,
    /** 详情地址 */
    val detailUrl: String = "",
    /** 源名称（结果卡片展示用） */
    val sourceName: String = "",
    /** 作者 */
    val author: String? = null,
    /** 标签原串，`|` 分隔（保留原始大小写，便于展示） */
    val tags: String = "",
    /** 小写标签串，检索用（`|无码|全彩|`，两端也带分隔符，方便 LIKE 精确匹配单个标签） */
    val tagsLower: String = "",
    /** 索引时间 */
    val updatedAt: Long = 0L,
)
