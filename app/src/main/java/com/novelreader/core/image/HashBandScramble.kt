package com.novelreader.core.image

import java.security.MessageDigest

/**
 * 「段数由哈希推导」的分段打散方案。
 *
 * 有一类站点不在响应里直接给出段数，而是只给两个元数据（专辑号 `aid` 与页序号），
 * 让前端用一段固定公式现算：**`md5(aid + 页序号)` 的末位字符**决定段数，
 * 专辑号落到更大的区间时再对末位取模。因此同一话里**每页的段数可以不同**，
 * 不能当成一个常量缓存。
 *
 * 还原动作本身仍是「按段倒序粘贴」，交给 [ComicImageScramble.unscramble]；
 * 这里只负责纯计算，方便在 JVM 单测里钉住边界。
 *
 * 举例：`aid=1487381`、页 `00001` → `md5("148738100001")` 末位 `9`，
 * `aid` 已过大号阈值故 `9 % 8 = 1` → **4 段**。
 */
object HashBandScramble {

    /** 阅读链路里用来携带段数的标记前缀（由 [ComicImageScramble.bandCountFromScript] 解析） */
    const val MARKER = "bands:"

    /** 未命中映射表时的默认段数（与站点脚本里的初值一致） */
    private const val DEFAULT_BANDS = 10

    /** 专辑号落在 [AID_MOD10_FROM, AID_MOD10_TO] 时，末位字符对 10 取模 */
    private const val AID_MOD10_FROM = 268_850L
    private const val AID_MOD10_TO = 421_925L

    /** 专辑号 ≥ 该值时，末位字符对 8 取模 */
    private const val AID_MOD8_FROM = 421_926L

    /** 末位（0~9）→ 段数；越界一律按 [DEFAULT_BANDS] */
    private val BANDS_BY_DIGIT = intArrayOf(2, 4, 6, 8, 10, 12, 14, 16, 18, 20)

    /**
     * 按专辑号与页序号推导该页的分段数。
     *
     * @param aid 专辑号（站点页面里的 `aid`）
     * @param pageId 页序号字符串，**保留前导零**（`00001`），参与 md5 的原文
     * @return 段数（2~20）
     */
    fun bandCount(aid: Long, pageId: String): Int {
        val lastChar = md5Hex(aid.toString() + pageId).lastOrNull() ?: return DEFAULT_BANDS
        val digit = lastChar.code
        val index = when {
            // 中段专辑：末位对 10 取模
            aid in AID_MOD10_FROM..AID_MOD10_TO -> digit % 10
            // 大号专辑：末位对 8 取模
            aid >= AID_MOD8_FROM -> digit % 8
            // 小号专辑：直接用末位字符码（超出映射范围 → 默认段数）
            else -> digit
        }
        return BANDS_BY_DIGIT.getOrElse(index) { DEFAULT_BANDS }
    }

    /** 打包成阅读链路认识的标记，如 `bands:4` */
    fun script(bandCount: Int): String = "$MARKER$bandCount"

    /** 小写十六进制 md5 */
    internal fun md5Hex(text: String): String {
        val bytes = MessageDigest.getInstance("MD5").digest(text.toByteArray(Charsets.UTF_8))
        val sb = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            val v = b.toInt() and 0xFF
            sb.append(HEX[v ushr 4]).append(HEX[v and 0x0F])
        }
        return sb.toString()
    }

    private val HEX = "0123456789abcdef".toCharArray()
}
