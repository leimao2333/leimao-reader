package com.novelreader.data.database

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.novelreader.data.model.BookEntity
import kotlinx.coroutines.flow.Flow

/**
 * 书架（收藏）DAO。
 */
@Dao
interface BookDao {

    @Query("SELECT * FROM books ORDER BY addedAt DESC")
    fun observeAll(): Flow<List<BookEntity>>

    @Query("SELECT * FROM books WHERE type = :type ORDER BY addedAt DESC")
    fun observeByType(type: String): Flow<List<BookEntity>>

    @Query("SELECT * FROM books WHERE sourceId = :sourceId AND itemId = :itemId LIMIT 1")
    suspend fun get(sourceId: String, itemId: String): BookEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(book: BookEntity)

    @Query("DELETE FROM books WHERE sourceId = :sourceId AND itemId = :itemId")
    suspend fun delete(sourceId: String, itemId: String)

    @Query("UPDATE books SET cover = :cover WHERE sourceId = :sourceId AND itemId = :itemId")
    suspend fun updateCover(sourceId: String, itemId: String, cover: String)

    @Delete
    suspend fun delete(book: BookEntity)

    // ===== 分组管理（v1.3.0.0） =====

    /** 设置单个条目的分组（空串 = 移出分组） */
    @Query("UPDATE books SET groupName = :group WHERE sourceId = :sourceId AND itemId = :itemId")
    suspend fun setGroup(sourceId: String, itemId: String, group: String)

    /**
     * 某类型下所有已用分组名（去空）。
     *
     * v1.8.3.0：按 `type` 过滤 —— 小说建的分组不再出现在漫画书架里（#6）。
     */
    @Query("SELECT DISTINCT groupName FROM books WHERE groupName != '' AND type = :type ORDER BY groupName")
    fun observeGroupNames(type: String): Flow<List<String>>

    /** 重命名分组（该类型下组内所有条目同步） */
    @Query("UPDATE books SET groupName = :newName WHERE groupName = :oldName AND type = :type")
    suspend fun renameGroup(type: String, oldName: String, newName: String)

    /** 删除分组（该类型下组内条目变为未分组，不从书架移除） */
    @Query("UPDATE books SET groupName = '' WHERE groupName = :name AND type = :type")
    suspend fun clearGroup(type: String, name: String)

    // ===== 批量操作（v1.8.3.0 待办 #5：批量删除 / 批量移动分组） =====

    /**
     * 按 `sourceId:itemId` 组合键批量删除（一次 SQL，不循环单条）。
     *
     * 调用方需保证 [keys] 非空：Room 对空 IN 列表会拼出非法 SQL。
     */
    @Query("DELETE FROM books WHERE (sourceId || ':' || itemId) IN (:keys)")
    suspend fun deleteByKeys(keys: List<String>)

    /** 按组合键批量设置分组（空串 = 移出分组） */
    @Query("UPDATE books SET groupName = :group WHERE (sourceId || ':' || itemId) IN (:keys)")
    suspend fun setGroupByKeys(keys: List<String>, group: String)
}
