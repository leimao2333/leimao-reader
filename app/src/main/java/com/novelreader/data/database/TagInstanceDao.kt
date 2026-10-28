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
     * 每个标签各收录了多少部（v1.8.9.7，待办 #47）。
     *
     * 标签页要在每个 chip 上显示「已收录 N 部」。注意去重口径必须和 [itemCount] 一致
     * （`sourceId || '|' || itemId`）—— 一部作品在同一标签下可能有「站方 + 行为」两行，
     * 直接 COUNT(*) 会翻倍。
     */
    @Query(
        """SELECT tagName AS tag, COUNT(DISTINCT sourceId || '|' || itemId) AS count
           FROM tag_instances WHERE itemType = :type GROUP BY tagName""",
    )
    suspend fun tagCounts(type: String): List<TagCount>

    /**
     * 判断一批 (sourceId, itemId) 里哪些已在本地收录（v1.8.9.7，待办 #47）。
     *
     * 标签搜索结果回来后用它标记「已收录」并排到前面。`refsWithAny` 是「按标签查条目」，
     * 这里反过来「按条目查是否在索引里」，两者用途不同。
     */
    @Query(
        """SELECT DISTINCT sourceId || '|' || itemId FROM tag_instances
           WHERE itemType = :type AND (sourceId || '|' || itemId) IN (:keys)""",
    )
    suspend fun indexedKeys(type: String, keys: List<String>): List<String>

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
             AND origin <> :searchOrigin
           GROUP BY sourceId, itemId""",
    )
    suspend fun refsWithAny(
        type: String,
        names: List<String>,
        minConfidence: Int,
        category: String?,
        excludeAdult: Int,
        /**
         * 「搜索命中」来源的标签在召回时**整体排除**（v1.8.9.9）。
         *
         * 这类标签只是为了让「已收录 N 部」跟得上搜索结果，证据很弱（标题里出现而已）。
         * 一旦让它们参与召回，搜一次就把整批结果钉成「本地已收录」，之后每次搜索都绕过
         * 判定直接合并进结果 —— 越搜越脏。传 [com.novelreader.core.util.TagTagger.ORIGIN_SEARCH]。
         */
        searchOrigin: String,
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

    /**
     * 某类型**全部**标签行（v1.8.2.0 批量导入用）。
     *
     * 导入两万条时逐条 `tagsOf` 是 2 万次往返，实测模拟器上四分钟都跑不完；
     * 一次全量读回（十万行级别）再在内存里按作品合并，快一个数量级。
     */
    @Query(
        """SELECT sourceId, itemId, itemType, tagName, category, confidence, origin, updatedAt
           FROM tag_instances WHERE itemType = :type""",
    )
    suspend fun allOfType(type: String): List<TagInstanceEntity>
}

/** (sourceId, itemId) + 置信度：标签查询的中间结果，求交与排序用 */
data class ScoredRef(val sourceId: String, val itemId: String, val confidence: Int)

/** 单个标签的收录数（待办 #47：标签页 chip 角标） */
data class TagCount(val tag: String, val count: Int)

/** 单条标签行（行为加权 / 视觉合并的读模型） */
data class TagRow(
    val tagName: String,
    val category: String,
    val confidence: Int,
    val origin: String,
    val updatedAt: Long = 0L,
)
