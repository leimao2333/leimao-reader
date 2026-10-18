package com.novelreader.data.cache

import com.novelreader.core.util.TagAlias
import java.io.File
import java.io.IOException
import java.util.UUID

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
    /** 标签名列表：同义词统一展示为中文规范名，保留首次出现顺序。 */
    val tags: List<String> get() = plan.keys.map(TagAlias::canonicalOf).distinct()

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
        val merged = LinkedHashMap<String, LinkedHashSet<Pair<String, String>>>()
        plan.forEach { (tag, entries) ->
            merged.getOrPut(TagAlias.canonicalOf(tag)) { linkedSetOf() }.addAll(entries)
        }
        val capped = if (limit <= 0) merged.size else limit
        return merged.entries
            .sortedWith(compareByDescending<Map.Entry<String, LinkedHashSet<Pair<String, String>>>> {
                it.value.size
            }.thenBy { it.key })
            .map { it.key }
            .take(capped)
    }

    /** 取标签及其规范名对应的全部入口；未命中再做包含匹配（如「热血」命中「热血漫画」）。 */
    fun entriesFor(tag: String): List<Pair<String, String>> {
        val canonical = TagAlias.canonicalOf(tag)
        val equivalent = plan.entries
            .filter { (candidate, _) -> TagAlias.canonicalOf(candidate) == canonical }
            .flatMap { it.value }
            .distinct()
        if (equivalent.isNotEmpty()) return equivalent
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
            dto.plan?.forEach { (rawTag, entryLists) ->
                // v1.6.2.0：清洗 key（落盘数据可能来自旧版本未清洗版本，
                // 也可能在源端每次抓不一致；折叠 "热血 漫画" / "热血漫画" 为同一标签）。
                val tag = normalizeTag(rawTag)
                if (tag.isEmpty()) return@forEach
                // v1.6.6.0：丢弃导航 / 状态类伪标签（旧缓存里可能残留「全部」「连载中」）
                if (isNoiseTag(tag)) return@forEach
                val entries = entryLists.orEmpty()
                    .mapNotNull { p ->
                        val srcId = p.getOrNull(0) ?: return@mapNotNull null
                        val url = p.getOrNull(1) ?: return@mapNotNull null
                        if (srcId.isNotEmpty() && url.isNotEmpty()) srcId to url else null
                    }
                if (entries.isNotEmpty()) {
                    // 同一清洗后 key 可能在旧数据里多次出现，合并 entries
                    plan.merge(tag, entries) { old, new -> (old + new).distinct() }
                }
            }
            TagPlan(collectedAtMs = dto.collectedAtMs, plan = plan)
        }.getOrNull()

        /**
         * 标签名规范化（v1.6.2.0）。
         *
         * - trim 前后空白
         * - 折叠所有连续空白字符（半角空格 / 全角空格 \u3000 / 换行 / Tab）到单个半角空格
         * - 与 [com.novelreader.data.repository.TagRepository.collect] 中清洗规则保持一致
         *
         * 公开给外部（如 [TagPlan.fromJson]）使用，外部落地缓存可能未经清洗。
         */
        fun normalizeTag(raw: String): String {
            val trimmed = raw.trim()
            if (trimmed.isEmpty()) return ""
            return trimmed.replace(WHITESPACE_REGEX, " ")
        }

        /**
         * 导航 / 状态类「伪标签」（v1.6.6.0）。
         *
         * 源会把「全部」「最近更新」「排行榜」「连载中」这类**入口词 / 状态词**
         * 也写进发现分类。它们不是内容题材：进标签池只会挤占热门推荐，
         * 点了也查不到对应作品（或只会把整个分类原样返回）。
         *
         * 精确匹配（大小写不敏感），不做包含匹配——「热血连载漫画」这种
         * 真题材词不能被误杀。
         */
        fun isNoiseTag(raw: String): Boolean {
            // 折叠空白后再比：源可能把「全部　漫画」写成带全角空格的形式，
            // 归一化后是「全部 漫画」，去掉空格才能命中集合里的「全部漫画」。
            val t = normalizeTag(raw).lowercase().replace(" ", "")
            return t.isNotEmpty() && t in NOISE_TAGS
        }

        private val NOISE_TAGS = setOf(
            // 入口 / 导航词
            "全部", "全部漫画", "全部小说", "全部视频", "全部分类",
            "分类", "首页", "主页", "目录", "书库",
            "最近更新", "最新更新", "最新上架", "最近上架", "今日更新", "更新", "最新",
            "排行榜", "排行", "热门", "推荐", "精选",
            // 状态词
            "连载", "连载中", "已完结", "完结", "完结漫画", "连载漫画", "已下架",
            // Venera 源里常见的「栏目名」（繁体，来自各站首页分区，不是题材）
            "熱門", "熱門推薦", "熱門漫畫", "每週必看", "大家都在看", "大濕推薦",
            "那年今天", "官方都在看", "隨便看看", "隨機",
            // 更新排期（ikmmh 等按星期分区）
            "星期一", "星期二", "星期三", "星期四", "星期五", "星期六", "星期日",
            "週一", "週二", "週三", "週四", "週五", "週六", "週日",
        )

        private val WHITESPACE_REGEX = Regex("[\\s\u3000]+")

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
            val target = fileOf(typeName)
            val tmp = File(dir, "$typeName.$LOGIC_VERSION.${UUID.randomUUID()}.tmp")
            try {
                tmp.writeText(plan.toJson())
                if (!tmp.renameTo(target)) {
                    val targetRemoved = !target.exists() || target.delete()
                    if (!targetRemoved || !tmp.renameTo(target)) {
                        throw IOException("Unable to replace tag cache: ${target.path}")
                    }
                }
            } finally {
                tmp.delete()
            }
            // 逻辑升级后顺手清掉旧版本的缓存文件（文件名带版本号，见 [fileOf]）
            File(dir, "$typeName.json").takeIf { it.isFile }?.delete()
        }
    }

    /**
     * 缓存文件名带 [LOGIC_VERSION]。
     *
     * 收集逻辑改版（比如 v1.6.6.0 加了漫画题材词、开始过滤噪声标签；
     * v1.7.0.0 漫画改绑**真分类页**入口、题材词只给没有分类能力的源）后，
     * 老缓存里的标签池是**过时的** —— 如果沿用同一个文件名，用户不手动点
     * 「刷新标签」就永远看不到新标签。带版本号后旧文件直接被忽略，
     * 首次使用自然重新收集一次。
     */
    fun fileOf(typeName: String) = File(dir, "$typeName.$LOGIC_VERSION.json")

    companion object {
        /**
         * 标签收集逻辑版本；**改动收集口径时必须 +1**。
         *
         * v4：v1.7.6.3 漫画题材词 182 → 394，且改为「全源轮转」绑定
         * （每个漫画源都能分到题材词，不再只限没有分类能力的前 8 个源）。
         * v6：增加漫画/小说细分标签及同人原著检索入口。
         * v7：扩展多语言规范名与成人标签同义词，旧缓存需重新归一化收集。
         * v8：标签视图按同义词合并展示，搜索入口合并同义项，并扩展无码/全彩的源覆盖。
         */
        const val LOGIC_VERSION = 8
    }
}
