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
}
