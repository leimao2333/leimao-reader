package com.novelreader.data.model

import androidx.room.Entity

/**
 * 目录（章节列表）本地缓存（v1.8.8.5）。
 *
 * 为什么单独一张表：正文缓存（`chapter_cache`）只覆盖**读过的章**，而目录本身
 * 每次进详情页 / 书架点书都要重新发一次网络请求 —— 几百上千章的目录，
 * 普通源一两秒、慢源好几秒，用户体感就是「点了没反应」。
 *
 * 这里把整份目录以 JSON 存下来，进书先读本地（几毫秒），网络只在缓存过期或
 * 用户主动刷新时才走。JSON 存整表而不是一章一行：目录是**整体**使用的，
 * 拆行只会让读取变成 N 次查询，没有收益。
 */
@Entity(tableName = "chapter_list_cache", primaryKeys = ["sourceId", "itemId"])
data class ChapterListCacheEntity(
    val sourceId: String,
    val itemId: String,
    /** 章节列表的 JSON（Gson 序列化 `List<ChapterItem>`） */
    val json: String,
    /** 章节数，便于判断空目录与展示 */
    val count: Int,
    /** 写入时间戳，用于 TTL 判断 */
    val cachedAt: Long = System.currentTimeMillis(),
)
