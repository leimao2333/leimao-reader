package com.novelreader.core.pdf

/**
 * 页码跳转输入解析（v1.8.7.2 待办 #23）。
 *
 * 底部页码从「只读文本」变成可点击：论文按页引用（"见第 7 页"）时
 * 不用再划七下。输入框只接受 1~[pageCount] 的整数，其它一律当没填。
 */
object PdfPageJump {

    /**
     * 把用户输入解析成 0 基页码；不合法时返回 null。
     *
     * 规则：
     * - 去掉首尾空白后必须是纯数字（拒绝 "7页" "p.7" "-1" "1.5"）；
     * - 取值范围 1~[pageCount]（越界视为无效，让 UI 保持禁用而不是跳失败）；
     * - [pageCount] <= 0（还没打开文档）时一律无效。
     */
    fun parse(raw: String, pageCount: Int): Int? {
        if (pageCount <= 0) return null
        val text = raw.trim()
        if (text.isEmpty()) return null
        val value = text.toIntOrNull() ?: return null
        if (value < 1 || value > pageCount) return null
        return value - 1
    }
}
