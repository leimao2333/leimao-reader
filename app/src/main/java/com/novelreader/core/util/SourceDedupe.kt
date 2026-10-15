package com.novelreader.core.util

/**
 * 重复源识别（v1.6.6.0）。
 *
 * **背景**：同一个站点被重复导入时，源管理页会出现看起来一模一样的条目。最常见的是
 * 聚合源包里同一份源出现两次、`baseUrl` 只差一个结尾斜杠，例如：
 *
 * ```
 * 酸奶漫画   https://m.1kkk.com
 * 酸奶漫画   https://m.1kkk.com/
 * ```
 *
 * 这类条目功能完全重合，只会让列表变长、标签池出现重复入口；合并掉最安全。
 *
 * **判定口径**（保守，宁可漏合不可错合）：
 * 1. 同类型 + **同名**（trim 后完全相等）；
 * 2. `baseUrl` 归一化后**相等**——去空白、统一小写、去掉 `#` 片段、去掉协议前缀、
 *    去掉全部结尾斜杠。所以 `http://A.com/`、`https://a.com`、`a.com#x` 视为同一站；
 * 3. `baseUrl` 为空的源**不参与**（Venera 源与内置源没有站点地址，
 *    只靠名字判重会误伤，例如 5 个站点不同但都叫「笔趣阁」的源必须各自保留）。
 *
 * 保留哪一条：**启用优先 → 规则内容更长（更完整）→ 导入更早 → id 字典序**，
 * 保证结果确定、可测。
 *
 * 纯函数、不依赖 Room，方便单测（实体到 [Candidate] 的搬运由调用方完成）。
 */
object SourceDedupe {

    /** 参与判重所需的最小信息 */
    data class Candidate(
        val id: String,
        val type: String,
        val name: String,
        val baseUrl: String,
        val enabled: Boolean,
        /** 规则原文长度（越长越完整） */
        val rawLength: Int,
        val createdAt: Long,
    )

    /** 归一化站点地址为判重键 */
    fun urlKey(baseUrl: String): String {
        var u = baseUrl.trim().lowercase()
        u = u.substringBefore('#')                       // 去掉 # 片段
        u = u.removePrefix("https://").removePrefix("http://")
        while (u.endsWith("/")) u = u.dropLast(1)        // 去掉全部结尾斜杠
        return u.trim()
    }

    /** 分组键；`baseUrl` 为空时返回 null（不参与合并） */
    fun groupKeyOf(c: Candidate): Triple<String, String, String>? {
        val url = urlKey(c.baseUrl)
        if (url.isEmpty()) return null
        return Triple(c.type.lowercase(), c.name.trim(), url)
    }

    /**
     * 算出该删除哪些源 id（每组保留一条）。
     *
     * @return 冗余 id 列表；无重复时为空
     */
    fun redundantIds(candidates: List<Candidate>): List<String> {
        val groups = LinkedHashMap<Triple<String, String, String>, MutableList<Candidate>>()
        candidates.forEach { c ->
            val key = groupKeyOf(c) ?: return@forEach
            groups.getOrPut(key) { mutableListOf() }.add(c)
        }
        val redundant = mutableListOf<String>()
        groups.values.forEach { group ->
            if (group.size < 2) return@forEach
            val keeper = group.sortedWith(keeperOrder()).first()
            group.filter { it.id != keeper.id }.forEach { redundant.add(it.id) }
        }
        return redundant
    }

    /** 保留者排序：启用优先 → 内容更完整 → 导入更早 → id 字典序（确定性） */
    private fun keeperOrder(): Comparator<Candidate> =
        compareByDescending<Candidate> { it.enabled }
            .thenByDescending { it.rawLength }
            .thenBy { it.createdAt }
            .thenBy { it.id }
}
