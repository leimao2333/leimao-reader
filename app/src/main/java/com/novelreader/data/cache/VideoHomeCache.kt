package com.novelreader.data.cache

import java.io.File

/**
 * 影视首页 / 频道内容的磁盘缓存（v1.5.7.0「加载一次之后固定」）。
 *
 * 解决「每次冷启动进视频页都是空白」：内容此前只存在内存里，进程一死就得
 * 重新探测源 + 拉内容（真实采集站要好几秒），期间页面一直空白。现在加载成功
 * 后把内容序列化成 JSON 落盘，下次进入（含冷启动）先读缓存直接上屏——
 * **加载一次之后固定，重新拉取只靠刷新按钮（或切源 / 换分类 / 筛选）**。
 *
 * - 一个 key 一个文件（`home.json` / `channel_MOVIE.json` …），整文件替换；
 * - 写入先落临时文件再改名，中途被杀不会留下半截内容；
 * - 只缓存「成功且非空」的结果；不做过期时间——内容新鲜度由用户手里的
 *   刷新按钮决定（这正是本缓存的产品语义）；
 * - 缓存 payload 里带启用视频源 id 集合，**源集变了缓存作废**（旧聚合不再成立），
 *   该判断由 ViewModel 做（见 [com.novelreader.ui.screens.common.HomeViewModel]）。
 *
 * 构造参数用 [File] 而不是 Context：单测可直接指到临时目录，无需 Robolectric。
 */
class VideoHomeCache(private val dir: File) {

    init {
        runCatching { dir.mkdirs() }
    }

    /** 读缓存（不存在 / 损坏 → null）。进入视频页时调用一次，几十 KB 读开销可忽略 */
    fun load(key: String): String? = runCatching {
        val f = fileOf(key)
        if (f.isFile) f.readText() else null
    }.getOrNull()

    /** 写缓存（失败静默：缓存只是加速，不该影响主流程） */
    fun save(key: String, payload: String) {
        runCatching {
            dir.mkdirs()
            val tmp = File(dir, "$key.tmp")
            tmp.writeText(payload)
            if (!tmp.renameTo(fileOf(key))) {
                // 个别文件系统 / Windows 下覆盖已存在文件会改名失败，退化为直接写
                fileOf(key).writeText(payload)
                tmp.delete()
            }
        }
    }

    /** 清空全部缓存（设置页「清空缓存」等场景备用） */
    fun clear() {
        runCatching { dir.listFiles()?.forEach { it.delete() } }
    }

    private fun fileOf(key: String) = File(dir, "$key.json")
}
