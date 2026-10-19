package com.novelreader.data.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.novelreader.data.model.TagInstanceEntity

/**
 * 标签实例表（v1.8.1.2「中间标签层」）。
 *
 * 每个标签一行，是 Elasticsearch nested 标签对象在 Room 里的等价物：
 * 这样「同一部作品内必须同时有这几个标签」才表达得精确，不会跨标签误匹配。
 */
@Dao
interface TagInstanceDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(items: List<TagInstanceEntity>)

    /** 清空某类型的标签（重建时用） */
    @Query("DELETE FROM tag_instances WHERE itemType = :type")
    suspend fun clear(type: String)

    /** 已收录作品数（按 sourceId+itemId 去重） */
    @Query(
        "SELECT COUNT(DISTINCT sourceId || '|' || itemId) FROM tag_instances WHERE itemType = :type",
    )
    suspend fun itemCount(type: String): Int

    /**
     * 组内 OR：命中该组**任一**标签的条目（[minConfidence] 以下的猜测不计）。
     *
     * 组间 AND 不在 SQL 里做 —— 多组交集要在应用侧把结果集求交，
     * 否则 SQL 会把「题材命中 A、受众命中 B」也算命中（正是 nested 要避免的）。
     *
     * @param category 限定大类（null = 不限，v1.8.1.3 的 `题材:` 语法）
     * @param excludeAdult 1 = 排除「成人」大类的标签（隐藏成人内容时整类过滤）
     * @return 每个条目在该组里的**最高置信度**，供加权排序用
     */
    @Query(
        """SELECT sourceId, itemId, MAX(confidence) AS confidence FROM tag_instances
           WHERE itemType = :type AND tagName IN (:names) AND confidence >= :minConfidence
             AND (:category IS NULL OR category = :category)
             AND (:excludeAdult = 0 OR category <> '成人')
           GROUP BY sourceId, itemId""",
    )
    suspend fun refsWithAny(
        type: String,
        names: List<String>,
        minConfidence: Int,
        category: String?,
        excludeAdult: Int,
    ): List<ScoredRef>

    /** 某部作品当前的标签行（行为回流 / 视觉合并时读全量行） */
    @Query(
        """SELECT tagName, category, confidence, origin, updatedAt FROM tag_instances
           WHERE itemType = :type AND sourceId = :sourceId AND itemId = :itemId""",
    )
    suspend fun tagsOf(
        type: String,
        sourceId: String,
        itemId: String,
    ): List<TagRow>
}

/** (sourceId, itemId) + 置信度：标签查询的中间结果，求交与排序用 */
data class ScoredRef(val sourceId: String, val itemId: String, val confidence: Int)

/** 单条标签行（行为加权 / 视觉合并的读模型） */
data class TagRow(
    val tagName: String,
    val category: String,
    val confidence: Int,
    val origin: String,
    val updatedAt: Long = 0L,
)
