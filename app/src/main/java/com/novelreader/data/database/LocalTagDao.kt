package com.novelreader.data.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.novelreader.data.model.LocalTagEntity

/** 本地标签索引表（v1.8.1.2）。 */
@Dao
interface LocalTagDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(items: List<LocalTagEntity>)

    /** 取某类型的全部索引（数据量在几千条量级，直接在内存里做多标签组合更灵活） */
    @Query("SELECT * FROM local_tags WHERE itemType = :type")
    suspend fun all(type: String): List<LocalTagEntity>

    /** 某类型已索引的条目数 */
    @Query("SELECT COUNT(*) FROM local_tags WHERE itemType = :type")
    suspend fun count(type: String): Int

    /** 清空某类型的索引（重新构建前调用） */
    @Query("DELETE FROM local_tags WHERE itemType = :type")
    suspend fun clear(type: String)

    /**
     * 还没有「视觉」来源标签、且有封面的条目（v1.8.1.4 封面视觉打标的队列）。
     *
     * `NOT EXISTS` 子查询保证重复跑不会重复推理 —— 打过一次的作品永远跳过。
     */
    @Query(
        """SELECT t.sourceId AS sourceId, t.itemId AS itemId, t.cover AS cover FROM local_tags t
           WHERE t.itemType = :type AND t.cover != ''
             AND NOT EXISTS (
                 SELECT 1 FROM tag_instances i
                 WHERE i.itemType = t.itemType AND i.sourceId = t.sourceId AND i.itemId = t.itemId
                   AND i.origin = '视觉')
           LIMIT :limit""",
    )
    suspend fun coversWithoutVisual(type: String, limit: Int): List<CoverRef>
}

/** (sourceId, itemId, cover)：视觉打标队列的一行 */
data class CoverRef(val sourceId: String, val itemId: String, val cover: String)
