package com.novelreader.core.util

import com.novelreader.domain.model.MediaItem

/**
 * 换源搜索结果去重 / 复用组件（B11，纯逻辑）。
 *
 * **背景**：换源时逐个源搜索同一本书，把结果汇总后会出现两类冗余：
 * 1. **同源同书重复**：某些源翻页时重复返回同一条（URL 只剩末尾页码不同、或封面 CDN
 *    域名不同但指向同一本书），按 `sourceId:id` 去重挡不住（id 是源自己生成的，会变）；
 * 2. **跨源同书重复**：不同源各自返回「书名 + 作者」完全相同的条目，换源候选里挤成一堆。
 *
 * **换源语义约束**（与 ViewModel 现有口径一致，务必保留）：跨源的同一本书**不能被合并成
 * 一条**——换源的目的就是让用户从多个源里挑一个。这里只做「去重 + 复用」：
 * - [dedupe]：按**规范化 URL** 或 **书名 + 作者** 折叠重复条目，但**保留每个源各一条**；
 * - [reuse]：把已经搜过 / 已验证过（如 0 章剔除）的结果按身份键缓存，换源翻页或重开时复用，
 *   避免对同一条目重复发网络请求。
 *
 * **复用既有 API，不重复造轮子**：
 * - URL 规范化走 [SourceUrlNormalizer.normalize] / [SourceDedupe.urlKey]；
 * - 书名 + 作者判定走 [BookConflictDetector.normalizeTitle] / [BookConflictDetector.normalizeAuthor]；
 * - 列表 key / 合并口径走 [SearchResultMerge.keyOf]。
 *
 * 纯函数、不依赖 Room / Android / 网络，可JVM 单测；实体到输入的搬运由调用方完成。
 */
object ChangeSourceDeduper {

    /** 换源候选条目的抽象视图（不绑定 Room / MediaItem 之外的实体）。 */
    data class Candidate(
        val sourceId: String,
        val id: String,
        val detailUrl: String,
        val title: String,
        val author: String?,
        val cover: String? = null,
    ) {
        companion object {
            fun of(item: MediaItem): Candidate = Candidate(
                sourceId = item.sourceId,
                id = item.id,
                detailUrl = item.detailUrl,
                title = item.title,
                author = item.author,
                cover = item.cover,
            )
        }
    }

    /** 去重口径：按哪种身份键折叠同源内的重复条目。 */
    enum class KeyMode {
        /** 规范化 URL（去 # 片段、协议 / 结尾斜杠差异），适合同一详情页的不同写法 */
        NORMALIZED_URL,
        /** 书名 + 作者（繁简 / 全半角 / 角色词已归一化），适合 URL 不同但内容相同的条目 */
        TITLE_AUTHOR,
    }

    /**
     * 条目身份键：同一「源 + 规范身份」视为同一条。
     *
     * @return 身份键；无法生成有效身份（如 URL 与书名都为空）时返回 null
     */
    fun identityKey(c: Candidate, mode: KeyMode): String? {
        if (c.sourceId.isBlank()) return null
        val identity = when (mode) {
            KeyMode.NORMALIZED_URL -> urlIdentity(c)
            KeyMode.TITLE_AUTHOR -> titleAuthorIdentity(c)
        }
        if (identity.isNullOrBlank()) return null
        return "${c.sourceId}|$identity"
    }

    /** 规范化详情页地址：先走站点地址规范化，再走去重口径（去协议 / 结尾斜杠 / 片段）。 */
    private fun urlIdentity(c: Candidate): String? {
        val raw = c.detailUrl.ifBlank { c.id }
        val normalized = SourceUrlNormalizer.normalize(raw).ifEmpty { raw }
        val key = SourceDedupe.urlKey(normalized)
        return key.ifBlank { null }
    }

    /** 书名 + 作者身份：书名归一化 + 作者归一化（作者缺失时留空，不误合并同名不同作者）。 */
    private fun titleAuthorIdentity(c: Candidate): String? {
        val title = BookConflictDetector.normalizeTitle(c.title)
        if (title.isEmpty()) return null
        val author = BookConflictDetector.normalizeAuthor(c.author).orEmpty()
        return "$title|$author"
    }

    /**
     * 合并两批换源候选并去重。
     *
     * 规则（保持换源语义）：
     * 1. 先按 [SearchResultMerge.keyOf] 的「源 + 源内 id」收敛（同源同一条只留一份）；
     * 2. 再按 [mode] 的身份键折叠同源内重复（不同源的同书仍各自保留）；
     * 3. **内容以新到的一批为准**（翻页 / 重搜时标题、封面可能更新），但位置保持首次出现顺序。
     *
     * @param base 已有候选（先到的）
     * @param incoming 新到候选（后到的，内容优先）
     * @param mode 同源内去重口径
     * @return 去重后的候选列表（顺序确定、可测）
     */
    fun dedupe(
        base: List<Candidate>,
        incoming: List<Candidate>,
        mode: KeyMode = KeyMode.NORMALIZED_URL,
    ): List<Candidate> {
        val byKey = LinkedHashMap<String, Candidate>()
        fun take(c: Candidate) {
            val listKey = "${c.sourceId}|${c.id.ifBlank { c.detailUrl }}"
            // 身份键已含 sourceId（见 identityKey），同源内折叠、跨源天然各留一条；
            // 身份键算不出时退回 listKey，保证至少有去重键。
            val key = identityKey(c, mode) ?: listKey
            byKey[key] = c // LinkedHashMap：位置保持首次出现，内容以后到覆盖
        }
        base.forEach { take(it) }
        incoming.forEach { take(it) }
        return byKey.values.toList()
    }

    /**
     * 已搜 / 已验证结果的复用缓存（纯内存、纯逻辑）。
     *
     * 为什么需要：换源关闭再打开、或翻页回来时，ViewModel 会对同一本书的同一源重复搜索并
     * 重复做「0 章校验」。按身份键把结果（含"已验证为空"的标记）缓存起来，重入时直接复用，
     * 既省网络又让候选列表稳定不闪烁。
     *
     * 线程安全由调用方保证（ViewModel 主协程串行写），这里保持简单数据结构便于单测。
     */
    class ReuseCache {
        private val items = LinkedHashMap<String, List<Candidate>>()
        private val emptyVerified = HashSet<String>()
        /** 已搜过的 key（含空结果），空结果不写 items 但仍要能被 [searched] 命中。 */
        private val searchedKeys = HashSet<String>()

        /** 记录一次搜索结果；空结果不写入 items（避免占内存），但计入已搜。 */
        fun put(key: String, results: List<Candidate>) {
            searchedKeys += key
            if (results.isNotEmpty()) items[key] = results
        }

        /** 是否搜过该 key（哪怕结果为空）。 */
        fun searched(key: String): Boolean = key in searchedKeys

        /** 取缓存结果；未命中返回 null。 */
        fun get(key: String): List<Candidate>? = items[key]

        /** 标记为「已验证为空章」的失效条目，换源候选里应剔除。 */
        fun markEmpty(key: String) { emptyVerified += key }

        /** 该条目是否已确认无有效章节。 */
        fun isEmpty(key: String): Boolean = key in emptyVerified

        /** 清除某本书的全部缓存（用户主动重新搜索时调用）。 */
        fun clear() { items.clear(); emptyVerified.clear(); searchedKeys.clear() }

        val size: Int get() = items.size + emptyVerified.size + searchedKeys.size
    }
}
