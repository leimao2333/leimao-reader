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

    // ===== 智能伴生分组（C6：在读 / 未读 / 已读完） =====

    /**
     * 按类型取「每本书的阅读状态」，供 [com.novelreader.core.util.BookshelfAutoGroup] 归类。
     *
     * books 左连接 progress（progress 有 sourceId+itemId 联合唯一索引，不会放大行数）：
     * - `hasProgress`：progress 行是否存在
     * - `finished`：**实体没有 finished 字段**，用「进度 ≥ 99%」近似 ——
     *   `(chapterIndex + 1) * 100 >= totalChapter * 99`。
     *   `totalChapter <= 0`（目录未缓存/未知）一律算未读完，避免新书被误判为已读完。
     *
     * 判定表达式与 `BookshelfAutoGroup.approxFinished` 保持一致，改一处要同步改另一处。
     *
     * @param type 类型过滤（novel/comic/video，大小写不敏感）；空字符串 = 不过滤
     */
    @Query(
        """
        SELECT b.bookId AS bookId,
               CASE WHEN p.progressId IS NULL THEN 0 ELSE 1 END AS hasProgress,
               CASE WHEN p.progressId IS NOT NULL
                         AND p.totalChapter > 0
                         AND (p.chapterIndex + 1) * 100 >= p.totalChapter * 99
                    THEN 1 ELSE 0 END AS finished
        FROM books b
        LEFT JOIN progress p ON p.sourceId = b.sourceId AND p.itemId = b.itemId
        WHERE :type = '' OR UPPER(b.type) = UPPER(:type)
        ORDER BY b.addedAt DESC
        """
    )
    fun observeShelfStates(type: String = ""): Flow<List<BookShelfStateRow>>

    /** [observeShelfStates] 的一次性版本（非响应式场景 / 单测用） */
    @Query(
        """
        SELECT b.bookId AS bookId,
               CASE WHEN p.progressId IS NULL THEN 0 ELSE 1 END AS hasProgress,
               CASE WHEN p.progressId IS NOT NULL
                         AND p.totalChapter > 0
                         AND (p.chapterIndex + 1) * 100 >= p.totalChapter * 99
                    THEN 1 ELSE 0 END AS finished
        FROM books b
        LEFT JOIN progress p ON p.sourceId = b.sourceId AND p.itemId = b.itemId
        WHERE :type = '' OR UPPER(b.type) = UPPER(:type)
        ORDER BY b.addedAt DESC
        """
    )
    suspend fun shelfStates(type: String = ""): List<BookShelfStateRow>
}

/**
 * 书架条目阅读状态投影（books 左连接 progress）。
 *
 * `finished` 是近似值：见 [BookDao.observeShelfStates] 的注释。
 */
data class BookShelfStateRow(
    val bookId: Long,
    val hasProgress: Boolean,
    val finished: Boolean,
)

/** 转为智能分组输入（C6），UI / ViewModel 直接喂给 BookshelfAutoGroup.classify */
fun List<BookShelfStateRow>.toAutoGroupInputs() =
    map { com.novelreader.core.util.AutoGroupInput(it.bookId, it.hasProgress, it.finished) }
