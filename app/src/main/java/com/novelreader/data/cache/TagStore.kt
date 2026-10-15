package com.novelreader.data.cache

import java.io.File

/**
 * 标签快照：标签名 → 各源该标签分类的入口 (sourceId, url)。
 *
 * 标签来自各源**发现分类**的自动聚合（v1.5.10.0），plan 同时是标签搜索的执行计划：
 * 选中一个标签就知道要请求哪些源的哪个分类地址。
 *
 * 序列化用 Gson 而不是 org.json：org.json 在纯 JVM 单测里是 Android stub
 * （返回默认值），Gson 是真实库、单测可跑（v1.5.10.0 实测教训）。
 */
data class TagPlan(
    /** 收集完成时间戳（0 = 未知） */
    val collectedAtMs: Long = 0L,
    /** 标签 → 各 (sourceId, 分类url)；LinkedHashMap 保持收集顺序 */
    val plan: Map<String, List<Pair<String, String>>> = emptyMap(),
) {
    /** 标签名列表（按收集顺序） */
    val tags: List<String> get() = plan.keys.toList()

    /**
     * 热门标签：按「覆盖源数」（每个标签下有多少 (sourceId, url) 对）倒序，
     * 返回前 [limit] 个。覆盖数相同则按收集顺序（[plan] 顺序）稳定排序。
     *
     * 用法：首页 / 标签搜索页顶部推荐位，优先展示覆盖面广的分类。
     *
     * 标签为空时返回空列表；limit <= 0 时按当前标签总数。
     */
    fun hotTags(limit: Int): List<String> {
        if (plan.isEmpty()) return emptyList()
        val capped = if (limit <= 0) plan.size else limit
        return plan.entries
            .sortedWith(
                compareByDescending<Map.Entry<String, List<Pair<String, String>>>> { it.value.size }
                    .thenBy { it.key },
            )
            .map { it.key }
            .take(capped)
    }

    /** 精确匹配某标签的入口；未命中再做「互相包含」的宽松匹配（如「热血」命中「热血漫画」） */
    fun entriesFor(tag: String): List<Pair<String, String>> {
        plan[tag]?.let { return it }
        // 宽松匹配：查询标签与缓存标签互相包含（取最短命中，语义最贴近）
        val fuzzy = plan.entries.filter { it.key.contains(tag) || tag.contains(it.key) }
            .minByOrNull { it.key.length }
        return fuzzy?.value.orEmpty()
    }

    /** Gson 载体：Pair 不走反射构造，用两层字符串数组 */
    private data class Dto(val collectedAtMs: Long, val plan: Map<String, List<List<String>>>)

    fun toJson(): String {
        val dto = Dto(
            collectedAtMs = collectedAtMs,
            plan = plan.mapValues { (_, entries) -> entries.map { listOf(it.first, it.second) } },
        )
        return gson().toJson(dto)
    }

    companion object {
        private fun gson() = com.google.gson.GsonBuilder().disableHtmlEscaping().create()

        fun fromJson(text: String): TagPlan? = runCatching {
            val dto = gson().fromJson(text, Dto::class.java) ?: return null
            val plan = LinkedHashMap<String, List<Pair<String, String>>>()
            dto.plan?.forEach { (tag, entryLists) ->
                val entries = entryLists.orEmpty()
                    .mapNotNull { p ->
                        val srcId = p.getOrNull(0) ?: return@mapNotNull null
                        val url = p.getOrNull(1) ?: return@mapNotNull null
                        if (srcId.isNotEmpty() && url.isNotEmpty()) srcId to url else null
                    }
                if (entries.isNotEmpty()) plan[tag] = entries
            }
            TagPlan(collectedAtMs = dto.collectedAtMs, plan = plan)
        }.getOrNull()

        val EMPTY = TagPlan()
    }
}

/**
 * 标签磁盘缓存（v1.5.10.0）。
 *
 * 一个内容类型一个文件（`NOVEL.json` / `COMIC.json` / `VIDEO.json`），
 * 收集一次落盘，冷启动直接读——标签收集涉及逐源请求，不能每次进页都来一遍。
 *
 * 构造参数用 [File] 而不是 Context：单测可直接指到临时目录，无需 Robolectric。
 */
class TagStore(private val dir: File) {

    init {
        runCatching { dir.mkdirs() }
    }

    /** 读缓存（不存在 / 损坏 → null） */
    fun load(typeName: String): TagPlan? = runCatching {
        val f = fileOf(typeName)
        if (f.isFile) TagPlan.fromJson(f.readText()) else null
    }.getOrNull()

    /** 写缓存（失败静默：缓存只是加速，不该影响主流程） */
    fun save(typeName: String, plan: TagPlan) {
        runCatching {
            dir.mkdirs()
            val tmp = File(dir, "$typeName.tmp")
            tmp.writeText(plan.toJson())
            if (!tmp.renameTo(fileOf(typeName))) {
                // 个别文件系统 / Windows 下覆盖已存在文件会改名失败，退化为直接写
                fileOf(typeName).writeText(plan.toJson())
                tmp.delete()
            }
        }
    }

    private fun fileOf(typeName: String) = File(dir, "$typeName.json")
}
