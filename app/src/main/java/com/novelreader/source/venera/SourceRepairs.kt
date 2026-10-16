package com.novelreader.source.venera

/**
 * 已知失效源配置的一次性修复表（v1.7.5.0）。
 *
 * 背景：Venera 源的「默认配置」写在源 JS 里，上游（venera-app/venera-configs）
 * 更新不及时时，默认值可能已经失效 —— 典型是包子漫画的默认主域名
 * `bzmgcn.com` 2026-10-16 实测已是服务器级 403，而设置里的备选域名
 * （webmota / kukuc / twmanga / dinnerku）全部正常出数。
 *
 * 这里按「源 key → 设置名 → 可用值」声明修复，经 [VeneraEngine.ensureSetting]
 * **只在用户没有显式设置过时**写入引擎的存储（`venera:setting:<key>:<name>`），
 * 因此：
 * - 不会覆盖用户自己的选择；
 * - 源后续更新 / 重新导入后修复依然有效（设置按 key 存，不随源码重置）；
 * - 每条修复都在注释里记录实测日期与依据，失效后可以安全地改表。
 */
object SourceRepairs {

    /** 源 key →（设置名 → 修复值） */
    private val FIXES: Map<String, Map<String, String>> = mapOf(
        // 包子漫画：默认域名 bzmgcn.com 已 403（2026-10-16 实测，服务器明确拒绝，
        // 非网络不通）；webmota.com 的首页 / 分类 API / 搜索均 200 且正常出数
        // （Node 复现台验证 categoryComics 36 条）。
        "baozi" to mapOf("domains" to "webmota.com"),
    )

    /** 应用全部修复（幂等，可重复调用） */
    fun applyAll(engine: VeneraEngine) {
        FIXES.forEach { (key, settings) ->
            settings.forEach { (name, value) -> engine.ensureSetting(key, name, value) }
        }
    }
}
