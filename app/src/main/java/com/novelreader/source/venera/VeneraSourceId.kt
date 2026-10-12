package com.novelreader.source.venera

/**
 * Venera 源的 id 约定。
 *
 * **必须由 `.js` 文件名推导，不能用源里的 `key`。**
 *
 * 原因：某些 Venera 源集合的 `index.json` 里存在 **key 重复** 的条目 ——
 * 同一站点的「普通版」与「多账号版」两个 `.js` 共用了同一个 `key`。
 * 若 id 取 key，后导入的会覆盖先导入的（实测 33 条只进 32 条）。
 * 同一目录下文件名必然唯一，因此文件名是天然稳定的主键。
 *
 * 另外这个约定被三处共用，改这里即可同时生效：
 * - 导入（导入源页 / index.json 批量导入）
 * - 启动恢复（`restoreSources` 用 `"${id}.${format}"` 反推出同一个 id）
 * - 引擎侧 `putSource(key, code)`
 */
object VeneraSourceId {

    private const val PREFIX = "venera_"

    /**
     * 由文件名推导 id。
     *
     * 兼容三种入参（都返回同一个 id，保证导入与恢复幂等）：
     * - `"baozi.js"`        → `"venera_baozi"`
     * - `"venera_baozi.venera"` → `"venera_baozi"`（恢复时构造的名字，不会二次加前缀）
     * - `"venera_baozi"`    → `"venera_baozi"`
     *
     * @return id；文件名为空时返回 null，调用方自行兜底
     */
    fun fromFileName(fileName: String?): String? {
        val base = fileName
            ?.substringAfterLast('/')
            ?.substringAfterLast('\\')
            ?.substringBeforeLast('.')
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: return null
        return if (base.startsWith(PREFIX)) base else PREFIX + base
    }
}
