package com.novelreader.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 书架 / 收藏实体（Room）。
 *
 * 存储用户收藏的小说/漫画/视频条目。
 * 用 sourceId + itemId 唯一标识一个条目。
 */
@Entity(tableName = "books")
data class BookEntity(
    @PrimaryKey(autoGenerate = true)
    val bookId: Long = 0,
    /** 源 ID */
    val sourceId: String,
    /** 源内条目 ID */
    val itemId: String,
    /** 标题 */
    val title: String,
    /** 封面 */
    val cover: String?,
    /** 作者 */
    val author: String?,
    /** 简介 */
    val desc: String?,
    /** 内容类型：novel / comic / video */
    val type: String,
    /** 详情地址 */
    val detailUrl: String,
    /**
     * 详情地址随带的请求配置（legado `url,{...}` 后缀，App 接口源用于携带签名请求头）。
     *
     * 必须持久化：从书架重进详情时要原样带上 `m-request-id` / `access-token`，
     * 否则签名丢失 → 接口返回错误数据 → 目录空白（#全免漫画）。
     */
    val requestOptions: String = "",
    /** 收藏时间戳 */
    val addedAt: Long = System.currentTimeMillis(),
    /** 自定义分组名（空 = 未分组，v1.3.0） */
    val groupName: String = "",
)
