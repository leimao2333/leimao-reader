package com.novelreader.domain.model

/**
 * 源元信息。
 *
 * 一个"源"的静态描述，无论 JSON 源还是 JS 源，最终都归一化为本结构。
 */
data class SourceInfo(
    /** 源唯一标识（自动生成或由源声明） */
    val id: String,
    /** 源名称 */
    val name: String,
    /** 内容类型 */
    val type: SourceType,
    /** 源版本 */
    val version: String = "1.0.0",
    /** 源作者 */
    val author: String = "",
    /** 源描述 */
    val desc: String = "",
    /** 源基础地址 */
    val baseUrl: String = "",
    /** 原始格式：json 或 js */
    val format: SourceFormat = SourceFormat.JSON,
    /** 是否启用 */
    val enabled: Boolean = true,
    /** 智能分类（如 玄幻/都市/二次元/影视/综合 等） */
    val category: String = "",
    /**
     * 手动排序序号（v1.5.8.0）。
     *
     * 0 = 未手动排序（回退到自动排序）；>0 时按数值升序排列，
     * 用于源管理页的「手动」排序模式（上移 / 下移）。
     */
    val sortOrder: Int = 0,
)

/**
 * 源原始格式枚举。
 */
enum class SourceFormat {
    JSON,      // 自研 JSON 规则格式
    JS,        // JS（Rhino）格式
    LEGADO,    // 阅读(legado) 标准书源格式
    LOCAL,     // 本地 TXT 书（v1.2.0.0）
    VENERA,    // Venera 漫画源（`class X extends ComicSource`，WebView 运行时，v1.2.6.0）
    MOONTV,    // MoonTV / LunaTV 采集源（苹果 CMS 接口，含 Base58 订阅配置，v1.3.3.0）
}
