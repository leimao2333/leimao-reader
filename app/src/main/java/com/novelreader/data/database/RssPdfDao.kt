package com.novelreader.data.database

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import com.novelreader.data.model.RssPdfEntity
import kotlinx.coroutines.flow.Flow

/**
 * 订阅书架里的本地 PDF（v1.8.6.0 待办 #29）。
 *
 * 只做导入 / 列表 / 删除三件事：列表按导入时间倒序（新的在上），
 * 删除按 id（文件清理由上层 [com.novelreader.core.rss.RssPdfStore] 负责）。
 */
@Dao
interface RssPdfDao {

    @Query("SELECT * FROM rss_pdfs ORDER BY addedAt DESC, id DESC")
    fun observeAll(): Flow<List<RssPdfEntity>>

    @Insert
    suspend fun insert(entity: RssPdfEntity): Long

    @Delete
    suspend fun delete(entity: RssPdfEntity)
}
