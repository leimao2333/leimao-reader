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
     */
    @Query(
        """SELECT DISTINCT sourceId, itemId FROM tag_instances
           WHERE itemType = :type AND tagName IN (:names) AND confidence >= :minConfidence""",
    )
    suspend fun itemIdsWithAny(
        type: String,
        names: List<String>,
        minConfidence: Int,
    ): List<ItemRef>
}

/** (sourceId, itemId) 二元组：标签查询的中间结果，求交用 */
data class ItemRef(val sourceId: String, val itemId: String)
