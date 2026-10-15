package com.novelreader.core.util

/**
 * 漫画通用题材词表（v1.6.6.0）。
 *
 * **为什么需要它**：漫画标签池长期只有几十个，而小说有两千多个。根因是漫画源
 * 普遍没有「发现分类」能力——
 * - 25 个 Venera 源在协议层就没有分类浏览接口（只有 search / info / chapters / content）；
 * - legado 漫画源里也只有少数带 `exploreUrl`（本地 11 个里仅 4 个）。
 *
 * 于是这里维护一份跨源通用的漫画题材词，收集标签时给漫画源绑一个
 * 「按题材搜索」入口（见 [TagEntryUrl.search]）——**不依赖源是否提供分类页**，
 * 所以 Venera 源也能参与，点击标签即跨源按题材搜索。
 *
 * 配套的另一半在 Venera 侧：`VeneraSourceAdapter.exploreCategories` 会把
 * 源代码里声明的 `category.parts[].categories` 解析成标签（同样绑搜索入口），
 * 那批是**源自己维护的**分类，覆盖面比这份通用词表更细。
 * 两者叠加后漫画标签池从几十个涨到几百个。
 *
 * 词表取自主流漫画站的分类命名，统一用**中文通用词**：Venera 里的中文站
 * （包子漫画 / 拷贝漫画 / 漫画人 / 漫小肆 / 爱看漫 …）能直接命中；
 * 纯英文站（MangaDex / nhentai 等）搜不到时只是返回空，不会报错。
 */
object ComicGenres {

    /** 通用漫画题材（32 个） */
    val ALL: List<String> = listOf(
        "热血", "恋爱", "校园", "奇幻", "冒险", "悬疑", "科幻", "日常",
        "搞笑", "恐怖", "治愈", "运动", "竞技", "后宫", "耽美", "百合",
        "励志", "职场", "美食", "机甲", "推理", "历史", "战争", "青春",
        "都市", "仙侠", "魔幻", "格斗", "萌系", "少女", "重生", "穿越",
    )

    /**
     * 单个题材词最多绑定多少个源。
     *
     * 标签搜索是「等全部源返回再展示」（见 `TagSearchViewModel.search` 的 `awaitAll`），
     * 每源上限 12s 超时、8 路并发 —— 绑太多会把等待时间拉长到几十秒。
     * 限到 8 个正好一批跑完（最坏 ≈12s，正常源 1~3s 就有结果），
     * 而 8 源 × 一页 ≈ 上百部作品，够用。
     *
     * 源的选择见 `ContentRepositoryImpl.searchableSourceIds`：那里把
     * Venera 与 legado 源**交替**排列，保证取前 8 个时两类源都覆盖到。
     */
    const val MAX_SOURCES_PER_GENRE = 8
}
