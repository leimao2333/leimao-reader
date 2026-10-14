package com.novelreader.source.loader

import com.google.gson.JsonParser

/**
 * 聚合源包清单（leimaoPack）解析（v1.5.6.0）。
 *
 * 「一条链接导入全部源」的载体：清单本身**不是源**，而是一串同目录下的源包文件名。
 * App 拿到清单后逐个下载子包、合并成一份候选列表，用户确认一次即可全部入库
 * （见 `SourceManageViewModel.importPack`）。
 *
 * 之所以需要这一层：导入链路一次只识别一种格式（legado 数组 / MoonTV 配置 /
 * Venera 索引），三种格式互斥、无法塞进同一个文件。清单把「多格式」这件事
 * 从文件层提到了 App 层。
 *
 * 格式（`all.json`）：
 * ```json
 * {
 *   "leimaoPack": 1,
 *   "name": "磊毛阅读聚合源",
 *   "version": "1.0",
 *   "files": ["legado-all.json", "video-all.json", "venera/index.json"]
 * }
 * ```
 * - `leimaoPack` 为魔数：只有它存在时才按清单处理，避免误吞普通 JSON 源
 * - `files` 支持相对路径（相对清单自身 URL 所在目录）与 http(s) 绝对地址
 */
object SourcePack {

    /** 清单魔数：JSON 顶层含该字段即认定为聚合包清单 */
    const val MAGIC_KEY = "leimaoPack"

    /** 当前支持的清单版本 */
    const val SUPPORTED_VERSION = 1

    /** 清单内容 */
    data class Manifest(
        /** 清单名称（展示用，可空） */
        val name: String,
        /** 清单版本（[MAGIC_KEY] 字段的值） */
        val version: Int,
        /** 子包文件名列表（相对清单 URL 或绝对地址） */
        val files: List<String>,
    )

    /**
     * 轻量判断是否为聚合包清单。
     *
     * 只做「看起来像」的字符串判断，供导入入口提前分流（此时还不必解析）。
     * 真解析用 [parse]。
     */
    fun looksLikeManifest(content: String): Boolean {
        val trimmed = content.trim()
        return trimmed.startsWith("{") && trimmed.contains("\"$MAGIC_KEY\"")
    }

    /**
     * 解析聚合包清单。
     *
     * @return 合法清单；不是清单 / 缺少 files / JSON 非法时返回 null
     */
    fun parse(content: String): Manifest? {
        if (!looksLikeManifest(content)) return null
        return runCatching {
            val obj = JsonParser.parseString(content.trim()).takeIf { it.isJsonObject }?.asJsonObject
                ?: return null
            if (!obj.has(MAGIC_KEY)) return null
            val files = obj.getAsJsonArray("files")
                ?.mapNotNull { el ->
                    runCatching { el.asString?.trim()?.takeIf { it.isNotEmpty() } }.getOrNull()
                }
                .orEmpty()
            if (files.isEmpty()) return null
            Manifest(
                name = runCatching { obj.get("name")?.asString }.getOrNull().orEmpty(),
                version = runCatching { obj.get(MAGIC_KEY)?.asInt }.getOrNull() ?: 0,
                files = files,
            )
        }.getOrNull()
    }

    /**
     * 把清单里的文件项解析成可下载的绝对地址。
     *
     * - 已是 http(s) 绝对地址 → 原样返回
     * - 否则相对清单 URL 所在目录解析（丢掉清单自身的查询串 / 片段，折叠 `./` 与 `../`）
     *
     * @param packUrl 清单自身的 URL；文件项为绝对地址时可以为空
     * @return 绝对地址；文件项为相对路径但清单 URL 不可用时返回 null
     */
    fun resolveUrl(packUrl: String?, file: String): String? {
        val f = file.trim()
        if (f.isEmpty()) return null
        if (f.startsWith("http://") || f.startsWith("https://")) return f

        val base = packUrl?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val clean = base.substringBefore('?').substringBefore('#')
        val dir = clean.substringBeforeLast('/', "")
        if (dir.isEmpty()) return null
        val joined = if (f.startsWith("/")) "$dir$f" else "$dir/$f"
        return normalize(joined)
    }

    /** 折叠路径中的 `./` 与 `../`，并压缩重复斜杠（保留 `scheme://`） */
    private fun normalize(url: String): String {
        val schemeEnd = url.indexOf("://")
        val prefix = if (schemeEnd >= 0) url.substring(0, schemeEnd + 3) else ""
        val stack = mutableListOf<String>()
        url.substring(prefix.length).split('/').forEach { seg ->
            when {
                seg.isEmpty() || seg == "." -> Unit
                seg == ".." -> if (stack.isNotEmpty()) stack.removeAt(stack.size - 1)
                else -> stack += seg
            }
        }
        return prefix + stack.joinToString("/")
    }
}
