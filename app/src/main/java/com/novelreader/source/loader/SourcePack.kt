package com.novelreader.source.loader

import com.google.gson.JsonElement
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
 * 格式（all.json）：
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
 *
 * v2（B10，分片源）：一个**逻辑源**可由多个**物理源分片**组成，运行时按优先级 + 健康度
 * 选一个健康分片使用。清单新增可选 `shards` 段：
 * ```json
 * {
 *   "leimaoPack": 2,
 *   "name": "...",
 *   "files": [],
 *   "shards": [
 *     {
 *       "logicalId": "logical-a",
 *       "name": "某书源",
 *       "members": [
 *         { "file": "a-primary.json", "priority": 10, "health": 1.0 },
 *         { "file": "a-backup.json",  "priority": 5,  "health": 0.9 }
 *       ]
 *     }
 *   ]
 * }
 * ```
 * `shards` 缺省时行为与 v1 完全一致（[Manifest.shards] 为空），既有源导入不受影响。
 */
object SourcePack {

    /** 清单魔数：JSON 顶层含该字段即认定为聚合包清单 */
    const val MAGIC_KEY = "leimaoPack"

    /** 当前支持的清单版本 */
    const val SUPPORTED_VERSION = 2

    /** 清单内容 */
    data class Manifest(
        /** 清单名称（展示用，可空） */
        val name: String,
        /** 清单版本（[MAGIC_KEY] 字段的值） */
        val version: Int,
        /** 子包文件名列表（相对清单 URL 或绝对地址） */
        val files: List<String>,
        /** 分片源定义（v2 新增；v1 清单为空，行为不回退） */
        val shards: List<ShardGroup> = emptyList(),
    )

    /**
     * 一个物理源分片。
     *
     * @param file 分片文件名 / 绝对地址（用 [resolveUrl] 补全）
     * @param priority 优先级，越大越优先
     * @param health 健康度（0~1），0 表示已失效不参与选择
     */
    data class Shard(
        val file: String,
        val priority: Int = 0,
        val health: Float = 1f,
    )

    /** 一个逻辑源及其若干物理分片（按 [Shard] 选择使用） */
    data class ShardGroup(
        val logicalId: String,
        val name: String,
        val members: List<Shard>,
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
     * @return 合法清单；不是清单 / 缺少 files 且缺少 shards / JSON 非法时返回 null
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
            val shards = parseShards(obj.getAsJsonArray("shards"))
            // v1 必须有 files；v2 允许只有 shards。二者皆空视为无效清单。
            if (files.isEmpty() && shards.isEmpty()) return null
            Manifest(
                name = runCatching { obj.get("name")?.asString }.getOrNull().orEmpty(),
                version = runCatching { obj.get(MAGIC_KEY)?.asInt }.getOrNull() ?: 0,
                files = files,
                shards = shards,
            )
        }.getOrNull()
    }

    /** 解析 `shards` 段；非法项跳过，保证一个坏分片不拖垮整张清单。 */
    private fun parseShards(arr: com.google.gson.JsonArray?): List<ShardGroup> {
        if (arr == null || arr.size() == 0) return emptyList()
        return arr.mapNotNull { el ->
            runCatching {
                if (!el.isJsonObject) return@runCatching null
                val o = el.asJsonObject
                val logicalId = str(o, "logicalId") ?: return@runCatching null
                val members = o.getAsJsonArray("members")?.mapNotNull { m ->
                    runCatching {
                        if (!m.isJsonObject) return@runCatching null
                        val mo = m.asJsonObject
                        val file = str(mo, "file") ?: return@runCatching null
                        Shard(
                            file = file,
                            priority = num(mo, "priority")?.toInt() ?: 0,
                            // 健康度钳到 [0,1]，脏数据（如 12 / -3）不会让排序失真
                            health = (num(mo, "health") ?: 1f).coerceIn(0f, 1f),
                        )
                    }.getOrNull()
                }.orEmpty()
                if (members.isEmpty()) return@runCatching null
                ShardGroup(
                    logicalId = logicalId,
                    name = str(o, "name").orEmpty(),
                    members = members,
                )
            }.getOrNull()
        }
    }

    private fun str(o: com.google.gson.JsonObject, key: String): String? =
        runCatching { o.get(key)?.takeIf { it.isJsonPrimitive }?.asString?.trim()?.ifEmpty { null } }.getOrNull()

    private fun num(o: com.google.gson.JsonObject, key: String): Float? =
        runCatching { o.get(key)?.takeIf { it.isJsonPrimitive }?.asFloat }.getOrNull()

    // ===== 分片选择（B10：按优先级 / 健康度） =====

    /**
     * 为一个逻辑源挑选当前可用的最佳分片。
     *
     * 规则（确定性，便于单测）：health <= 0 的分片视为失效直接排除；其余按
     * **优先级降序 → 健康度降序 → 文件名** 排序，取第一个。健康度作为优先级相同时的
     * 次序依据，让「同优先级里更健康的先上」；文件名兜底保证结果稳定、可复现。
     *
     * @return 选中的分片；全部失效 / 无分片时返回 null（调用方按「该源不可用」处理）
     */
    fun selectShard(group: ShardGroup): Shard? = orderedShards(group).firstOrNull()

    /**
     * 按优先级 / 健康度给出分片的**有序回退序列**。
     *
     * 为什么返回整条序列而非只给一个：主分片在运行时可能请求失败，调用方需要按此顺序
     * 依次尝试下一个健康分片（failover），而不是重新解析清单。
     */
    fun orderedShards(group: ShardGroup): List<Shard> =
        group.members
            .filter { it.health > 0f && it.file.isNotBlank() }
            .sortedWith(compareByDescending<Shard> { it.priority }
                .thenByDescending { it.health }
                .thenBy { it.file })

    /** 清单里是否定义了分片源（供导入入口分流；v1 清单恒为 false）。 */
    fun hasShards(manifest: Manifest): Boolean = manifest.shards.isNotEmpty()

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
