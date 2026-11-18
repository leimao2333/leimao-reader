package com.novelreader.core.text

import com.novelreader.core.preferences.PreferencesManager
import kotlinx.coroutines.flow.first

/**
 * 正文处理配置工厂（B8）：把「用户偏好」翻译成「流水线配置」。
 *
 * 为什么要有这么一个类：流水线（[ContentPipeline]）只回答「按什么顺序、带着什么参数跑」，
 * 而「哪些阶段该开、参数取多少」是**产品决策**。把这层决策收在一处，
 * 调用方就只剩「拿 config → 跑流水线」两步；将来再加新的正文相关偏好
 * （比如正文内搜索词高亮）也只改这一个文件，不用到处撒开关。
 *
 * 纯 Kotlin：只用到 [PreferencesManager] 的 Flow 与本包既有组件，
 * 核心映射是**纯函数**（入参是普通数据），能在 JVM 单测里直接跑，不需要 Android 上下文。
 */
object ContentPipelineConfigFactory {

    /**
     * 正文里最多保留几个连续空行。
     *
     * 取 1：与既有 [TextCleaner] 的「3 行以上空行压成 1 个空行」同口径，
     * 也与 [ContentPipelineConfig.maxBlankLines] 的默认值一致。
     *
     * 目前偏好里**没有**对应项（`KEY_NOVEL_PARAGRAPH_SPACING` 是渲染用的「段距 dp」，
     * 语义不同，不能硬映射成空行数），所以先给常量；
     * 将来真加了「最大连续空行」偏好项，再把它接进 [Preferences]。
     */
    const val DEFAULT_MAX_BLANK_LINES = 1

    /**
     * 流水线关心的那几项用户偏好（与 DataStore 解耦，便于单测）。
     *
     * @param replaceRules 用户自定义的替换 / 净化规则（对应 `KEY_NOVEL_REPLACE_RULES`）
     * @param indentEnabled 是否段首缩进（对应 `KEY_NOVEL_INDENT`）
     * @param maxBlankLines 最多保留几个连续空行
     */
    data class Preferences(
        val replaceRules: List<ReplaceRule> = emptyList(),
        val indentEnabled: Boolean = false,
        val maxBlankLines: Int = DEFAULT_MAX_BLANK_LINES,
    )

    /**
     * 偏好原始值 → [Preferences]（纯函数）。
     *
     * 单独抽出来是为了让「读偏好」与「映射偏好」两件事分开：
     * 前者要 Android 上下文（单测里不好造），后者是纯计算（随便测）。
     *
     * @param replaceRulesRaw 替换规则的存储串（[TextReplaceRules] 的自定义编码）
     */
    fun preferencesOf(
        replaceRulesRaw: String?,
        indentEnabled: Boolean,
        maxBlankLines: Int = DEFAULT_MAX_BLANK_LINES,
    ): Preferences = Preferences(
        replaceRules = TextReplaceRules.decode(replaceRulesRaw),
        indentEnabled = indentEnabled,
        maxBlankLines = maxBlankLines.coerceAtLeast(0),
    )

    /**
     * 从 [PreferencesManager] 读偏好。
     *
     * [preferences] 为 null（未注入 / 单测）时返回「什么都不开」的默认值。
     * 读取用 [kotlinx.coroutines.flow.first]：DataStore 的快照在内存里有缓存，
     * 一次读取只是一次挂起，不会比读文件贵多少。
     */
    suspend fun readPreferences(preferences: PreferencesManager?): Preferences {
        if (preferences == null) return Preferences()
        return preferencesOf(
            replaceRulesRaw = preferences.novelReplaceRulesFlow.first(),
            indentEnabled = preferences.novelIndentFlow.first(),
        )
    }

    /**
     * 偏好 → 完整配置：净化 + 替换 + 缩进 + 质量检查。
     *
     * 这是「用户偏好」到「流水线配置」的**完整**映射：所有会影响正文的偏好都进来。
     * 给「整条阅读链路都走流水线」的调用方用。
     */
    fun toConfig(prefs: Preferences): ContentPipelineConfig = ContentPipelineConfig(
        sanitize = true,
        replaceRules = prefs.replaceRules,
        indentEnabled = prefs.indentEnabled,
        maxBlankLines = prefs.maxBlankLines,
        qualityCheck = true,
    )

    /**
     * 数据层（正文返回路径）用的配置：**只净化**，不替换、不缩进、也不做完整评分。
     *
     * 为什么不在这里跑替换阶段：用户替换规则目前由阅读页**唯一**一处执行
     * （`NovelReaderViewModel.applyContent` → `TextCleaner.clean(raw, rules)`）。
     * 数据层再跑一遍 = 每条规则被执行两次；对「删广告」这类收敛规则无所谓，
     * 但对「第」→「第一」这种不收敛的规则，正文会被改成「第一一」—— 那是实打实的回退。
     * 等阅读页那处改走流水线之后，把下面的 replaceRules 换成 [Preferences.replaceRules]
     * 即可（其余判断都不用动）。
     *
     * 为什么不在这里跑缩进阶段：缩进是**排版偏好**，阅读页渲染时会按开关补全角空格
     * （并先把行首空白 trim 掉），数据层再插一份纯属白做。
     *
     * 为什么质量检查默认关闭：完整评分要扫全文，不能每章都做。
     * 数据层用两段式（先快判、判低质量才复评），由调用方按需打开
     * [ContentPipelineConfig.qualityCheck] 开关。
     *
     * 这份配置与用户偏好无关（净化口径是产品默认值），刻意**不读偏好**：
     * 省掉每章一次 DataStore 读，也避免和阅读页重复应用替换规则。
     */
    fun forRepository(maxBlankLines: Int = DEFAULT_MAX_BLANK_LINES): ContentPipelineConfig =
        ContentPipelineConfig(
            sanitize = true,
            replaceRules = emptyList(),
            highlightTerms = emptyList(),
            indentEnabled = false,
            maxBlankLines = maxBlankLines.coerceAtLeast(0),
            qualityCheck = false,
        )

    /**
     * 数据层「低质量复评」用的配置：**只做完整评分**，不改动正文。
     *
     * 为什么单独一份：复评只在「快判命中低质量」的罕见路径上发生，
     * 那时正文已经净化过了，再跑一遍净化 / 替换纯属浪费（还可能把正文改坏）。
     */
    fun forRescoring(): ContentPipelineConfig = ContentPipelineConfig(
        sanitize = false,
        replaceRules = emptyList(),
        highlightTerms = emptyList(),
        indentEnabled = false,
        qualityCheck = true,
    )
}
