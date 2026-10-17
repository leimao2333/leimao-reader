package com.novelreader.core.util

/**
 * 源可用性判定的纯逻辑（v1.7.6.5）。
 *
 * 抽出来是为了有单测守着「什么算失效」这条规则——它直接决定
 * 「禁用失效 / 删除失效」会误伤还是漏网，不该散落在 ViewModel 的
 * 协程嵌套里靠人眼保证。
 */
object SourceHealth {

    /**
     * 判定一个源是否**可用**。
     *
     * @param searchOk [SourceAdapter.test] 的结果：能否搜到东西。
     * @param readable 目录可读性：`true` 读到章节 / `false` 搜到了书却一章都取不到 /
     *   `null` 无法判定（搜索本身没结果，可能只是关键词不匹配）。
     * @return 只有「能搜到 **且** 目录读得出来」才算可用。
     *
     * `readable == false` 必须算失效：这类源点进去没有章节可读，留着只会让人
     * 反复点进去又退出来（点外漫画的目录接口被 WAF 400、52PO 的目录规则
     * 对不上改版后的页面）。`null` 不能算失效，否则关键词不适用的源会被误杀。
     */
    fun isUsable(searchOk: Boolean, readable: Boolean?): Boolean = searchOk && readable != false

    /**
     * 该源是否属于「搜得到但读不了目录」这一类（用于 UI 交代失效原因）。
     * 搜索本身失败的不算——那是另一种失效，原因不同。
     */
    fun isNoToc(searchOk: Boolean, readable: Boolean?): Boolean = searchOk && readable == false
}
