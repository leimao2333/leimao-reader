package com.novelreader.data.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.novelreader.data.model.ProgressEntity

/**
 * 阅读/播放进度 DAO。
 */
@Dao
interface ProgressDao {

    @Query("SELECT * FROM progress")
    suspend fun getAll(): List<com.novelreader.data.model.ProgressEntity>

    @Query("SELECT * FROM progress WHERE sourceId = :sourceId AND itemId = :itemId LIMIT 1")
    suspend fun get(sourceId: String, itemId: String): ProgressEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(progress: ProgressEntity)

    @Query("DELETE FROM progress WHERE sourceId = :sourceId AND itemId = :itemId")
    suspend fun delete(sourceId: String, itemId: String)

    /**
     * 最近阅读列表：progress 表按更新时间倒序，左连接 books 表补充封面等字段。
     * 返回 [RecentReadingRow]（POJO 投影，非实体表）。
     *
     * 用子查询按 sourceId+itemId 去重（progress 表无该联合唯一约束，可能有多行，
     * 取每本书最新的一条 updatedAt），避免列表 key 重复。
     *
     * 标题/类型优先取 progress 表自有的 itemTitle/itemType（v4 起记录），
     * 回退到 books 表，最后回退章节标题。
     *
     * @param type 类型过滤；空字符串 = 不过滤（全部类型）
     */
    @Query(
        """
        SELECT p.sourceId, p.itemId, p.chapterTitle, p.chapterIndex, p.totalChapter, p.updatedAt,
               IFNULL(NULLIF(p.itemTitle, ''), b.title) AS title,
               IFNULL(NULLIF(p.itemCover, ''), b.cover) AS cover,
               IFNULL(NULLIF(p.itemAuthor, ''), b.author) AS author,
               IFNULL(NULLIF(p.itemType, ''), b.type) AS type, b.detailUrl
        FROM (
            SELECT sourceId, itemId, chapterTitle, chapterIndex, totalChapter, itemType, itemTitle,
                   itemCover, itemAuthor, MAX(updatedAt) AS updatedAt
            FROM progress
            GROUP BY sourceId, itemId
        ) p
        LEFT JOIN books b ON b.sourceId = p.sourceId AND b.itemId = p.itemId
        WHERE :type = '' OR IFNULL(NULLIF(p.itemType, ''), b.type) = :type
        ORDER BY p.updatedAt DESC
        """
    )
    suspend fun recentReadings(type: String = ""): List<RecentReadingRow>
}

/**
 * 最近阅读查询投影（progress join books）。
 *
 * book 相关字段（title/cover/author/type/detailUrl）可能为 null（进度存在但已从书架移除）。
 */
data class RecentReadingRow(
    val sourceId: String,
    val itemId: String,
    val chapterTitle: String,
    val chapterIndex: Int,
    val totalChapter: Int,
    val updatedAt: Long,
    val title: String?,
    val cover: String?,
    val author: String?,
    val type: String?,
    val detailUrl: String?,
)
