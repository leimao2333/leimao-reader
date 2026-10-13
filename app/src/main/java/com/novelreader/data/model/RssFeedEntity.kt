package com.novelreader.data.model

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * RSS 订阅源实体（Room，v1.3.1.0 新增）。
 *
 * 一条记录 = 一个订阅地址。与「书源」完全独立：书源是搜索型规则源，
 * 订阅源是推送型内容流，没有搜索/换源语义，所以单独建表。
 */
@Entity(
    tableName = "rss_feeds",
    indices = [Index(value = ["url"], unique = true)],
)
data class RssFeedEntity(
    @PrimaryKey(autoGenerate = true)
    val feedId: Long = 0,
    /** 订阅地址（唯一） */
    val url: String,
    /** 源标题（首次抓取时从 feed 元信息填充） */
    val title: String = "",
    /** 站点首页 */
    val siteUrl: String = "",
    /** 源描述 */
    val description: String = "",
    /** 站点图标 */
    val iconUrl: String = "",
    /** 分组名（空 = 未分组） */
    val groupName: String = "",
    /** 排序序号 */
    val sortOrder: Int = 0,
    /** 最近一次抓取时间 */
    val lastFetchAt: Long = 0,
    /** 最近一次抓取失败原因（空 = 正常） */
    val lastError: String = "",
    val createdAt: Long = System.currentTimeMillis(),
)
