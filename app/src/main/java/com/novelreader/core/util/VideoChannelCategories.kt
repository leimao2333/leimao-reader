package com.novelreader.core.util

import com.novelreader.domain.model.SourceInfo

/**
 * 频道页分类的取值语义（v1.8.4.2）。
 *
 * 频道页（电影 / 动漫 / 短剧）的分类下拉里，**「全部」是一个真实选项**，
 * 不是一个「还没选」的空状态：它表示跨该频道所有分类、所有源聚合。
 *
 * 以前空串被当成「未选」，带来两个后果：
 * 1. **默认值不是「全部」**：加载时取 `plan.keys.first()`，而 plan 只保证
 *    「分类名恰好等于关键词」的排最前。动漫频道的分类多半叫「国产动漫 /
 *    日本动漫 / 欧美动漫」，没有哪个是纯「动漫」，于是落点随源探测顺序漂移，
 *    用户进来看到的是某个子分类，还以为频道就这点内容；
 * 2. **「全部」是个死选项**：手动选它时按空 key 取条目取不到，列表被清空、
 *    显示「暂无内容」。
 */
object VideoChannelCategories {

    /** 条目身份：同一个源的同一个分类地址（分类地址在各源内部唯一，跨源会撞号） */
    private fun keyOf(entry: Pair<SourceInfo, String>) = "${entry.first.id}|${entry.second}"

    /**
     * 解析某个分类需要请求的（源, 分类地址）列表。
     *
     * - [category] 为空（或全空白）= **全部**：把各分类的条目拍平，按「源 + 地址」
     *   去重并保持 plan 顺序（分类按正片优先级排好，源按成人源沉底排好）；
     * - 具体分类：只取该分类的条目；
     * - 不认识的分类：空列表 —— **不回退到全部**，否则「点了个空分类」会变成
     *   「突然冒出全部内容」，比空态更让人困惑。
     */
    fun entriesOf(
        plan: Map<String, List<Pair<SourceInfo, String>>>,
        category: String,
    ): List<Pair<SourceInfo, String>> =
        if (category.isBlank()) {
            plan.values.flatten().distinctBy(::keyOf)
        } else {
            plan[category].orEmpty()
        }
}
