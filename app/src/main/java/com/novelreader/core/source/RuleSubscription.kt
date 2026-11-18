package com.novelreader.core.source

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser

/**
 * 规则订阅（B7）。
 *
 * 现状痛点：书源的规则（正则 / JS）都写死在源 JSON 里，站点一改版就得让用户
 * 重新导入整个源包——而像 shuyuan 这类源池是**持续更新**的，用户不可能天天手动更新。
 * 「规则订阅」把这件事拆成两部分：
 * - 订阅项描述「从哪个地址拉、拉给哪个源、多久拉一次」（本文件的数据模型）；
 * - 拉回来的内容**合并**进本地源（覆盖同名规则 / 新增缺失规则 / 按标记删除规则），
 *   合并前必须先校验，**一份坏订阅不能把用户已有的源改坏**。
 *
 * 本文件只放**纯逻辑**（模型 / 校验 / 合并 / 差异计算），不碰网络也不碰磁盘，
 * 于是「合并规则」这件事可以逐条断言；HTTP 与落盘在 [RemoteRuleSource]。
 */
data class RuleSubscription(
    /** 订阅的目标源 id（合并进哪个源） */
    val sourceId: String,
    /** 远程规则地址 */
    val url: String,
    /** 更新间隔（小时）；null = 每次都允许更新 */
    val updateIntervalHours: Int? = null,
    /** 上次更新时间（epoch millis） */
    val lastUpdatedAt: Long? = null,
    /** 已应用到的版本号；用于版本比对与幂等 */
    val version: String? = null,
) {
    /** 是否到了该更新的时间点 */
    fun isDue(now: Long = System.currentTimeMillis()): Boolean {
        val hours = updateIntervalHours ?: return true
        val last = lastUpdatedAt ?: return true
        return now - last >= hours * 3_600_000L
    }
}

/**
 * 规则分组：与 legado 书源的 ruleSearch / ruleBookInfo / ruleToc / ruleContent /
 * ruleExplore 一一对应。合并的粒度是「组 + 规则名」，例如 `ruleToc/chapterList`。
 */
enum class RuleGroup(val jsonKey: String) {
    SEARCH("ruleSearch"),
    BOOK_INFO("ruleBookInfo"),
    TOC("ruleToc"),
    CONTENT("ruleContent"),
    EXPLORE("ruleExplore"),
    ;

    companion object {
        fun fromJsonKey(key: String): RuleGroup? =
            entries.firstOrNull { it.jsonKey.equals(key, ignoreCase = true) }
    }
}

/** 差异预览：供 UI 在应用前告诉用户「会改什么」 */
data class RuleDiff(
    /** 新增的规则（组/规则名） */
    val added: List<String>,
    /** 被覆盖的规则（组/规则名） */
    val overwritten: List<String>,
    /** 被删除的规则（组/规则名） */
    val deleted: List<String>,
    /** 内容完全一致、无需改动的规则数 */
    val unchanged: Int,
) {
    val addedCount: Int get() = added.size
    val overwrittenCount: Int get() = overwritten.size
    val deletedCount: Int get() = deleted.size
    val hasChanges: Boolean get() = added.isNotEmpty() || overwritten.isNotEmpty() || deleted.isNotEmpty()

    companion object {
        val EMPTY = RuleDiff(emptyList(), emptyList(), emptyList(), 0)
    }
}

/** 一个规则组的内容 */
sealed interface GroupPayload {
    /** 规则集合；值为 null（或删除标记）表示「删除这条规则」 */
    data class Rules(val rules: Map<String, String?>) : GroupPayload

    /** 整组删除 */
    data object Deleted : GroupPayload
}

/** 校验通过后拿到的订阅载荷 */
data class RulePayload(
    val groups: Map<RuleGroup, GroupPayload>,
    /** 远程内容自带的版本号 */
    val version: String?,
    /** 是否是「完整书源」（含 bookSourceName / bookSourceUrl）而非仅规则片段 */
    val isFullSource: Boolean,
)

/** 校验结果 */
sealed interface RuleValidation {
    data class Valid(val payload: RulePayload) : RuleValidation
    data class Invalid(val reason: String) : RuleValidation
}

/** 合并结果 */
data class RuleMergeResult(
    /** 合并后的源 JSON（校验失败 / 版本过期时原样返回本地内容） */
    val mergedJson: String,
    /** 差异预览 */
    val diff: RuleDiff,
    /** 是否真的应用了变更 */
    val applied: Boolean,
    /** 面向用户的原因说明（不含 URL，避免进日志） */
    val reason: String,
    /** 应用后的版本号 */
    val version: String?,
)

/**
 * 规则合并器（纯逻辑）。
 *
 * 合并语义（**先校验，后合并**）：
 * - **新增**：远程有、本地没有的「组/规则名」→ 追加；
 * - **覆盖**：两边都有但内容不同 → 以远程为准；
 * - **删除**：远程该规则的值是 `null`（或 [DELETE_MARKER]）→ 从本地移除；
 *   远程把整个规则组置为 `null` → 整组移除；
 * - **不动**：内容一致 → 保持原样（幂等的基础）。
 *
 * 远程内容可以是完整书源（shuyuan 那种一整个源 JSON），
 * 也可以只含规则片段（只有 ruleXxx 字段，甚至写成 `ruleToc.chapterList` 这样的扁平键）。
 */
object RuleMerger {

    /** 显式删除标记：值为该字符串的规则会被删除（与 `null` 等价） */
    const val DELETE_MARKER = "__DELETE__"

    /** 版本字段候选名（legado 用 bookSourceVersion） */
    private val VERSION_KEYS = listOf("bookSourceVersion", "ruleVersion", "sourceVersion", "version")

    /**
     * 校验远程内容。
     *
     * 校验不通过 → 整体拒绝，**不做任何部分合并**：
     * 半截的规则改动比不更新更糟（用户会以为源已修好，实际只改了一半）。
     */
    fun validate(remoteJson: String): RuleValidation {
        val text = remoteJson.trim()
        if (text.isEmpty()) return RuleValidation.Invalid("订阅内容为空")

        val element: JsonElement = runCatching { JsonParser.parseString(text) }.getOrNull()
            ?: return RuleValidation.Invalid("订阅内容不是合法 JSON")
        if (!element.isJsonObject) return RuleValidation.Invalid("订阅内容不是 JSON 对象")
        val obj = element.asJsonObject

        // 完整书源：书名与站点地址都是必需的
        val isFullSource = obj.has("bookSourceName") || obj.has("bookSourceUrl")
        if (isFullSource) {
            if (stringField(obj, "bookSourceName").isNullOrBlank()) {
                return RuleValidation.Invalid("缺少 bookSourceName")
            }
            if (stringField(obj, "bookSourceUrl").isNullOrBlank()) {
                return RuleValidation.Invalid("缺少 bookSourceUrl")
            }
        }

        // 规则组可能直接在顶层，也可能包在 "rules" 里
        val holders = mutableListOf<JsonObject>()
        holders += obj
        obj.get("rules")?.takeIf { it.isJsonObject }?.let { holders += it.asJsonObject }

        val groups = mutableMapOf<RuleGroup, GroupPayload>()
        for (holder in holders) {
            for ((key, value) in holder.entrySet()) {
                val group = RuleGroup.fromJsonKey(key)
                if (group != null) {
                    val payload = parseGroup(value)
                        ?: return RuleValidation.Invalid("规则组 $key 格式不正确")
                    groups[group] = payload
                    continue
                }
                // 扁平写法："ruleToc.chapterList" → 归入 ruleToc 组
                val flatGroup = RuleGroup.fromJsonKey(key.substringBeforeLast('.', ""))
                val flatName = key.substringAfterLast('.', "")
                if (flatGroup != null && flatName.isNotEmpty() && value.isJsonPrimitive) {
                    val existing = groups[flatGroup]
                    if (existing is GroupPayload.Rules) {
                        groups[flatGroup] = GroupPayload.Rules(existing.rules + (flatName to value.asString))
                    } else if (existing == null) {
                        groups[flatGroup] = GroupPayload.Rules(mapOf(flatName to value.asString))
                    }
                }
            }
        }

        val hasContent = groups.values.any {
            it is GroupPayload.Deleted || (it as GroupPayload.Rules).rules.isNotEmpty()
        }
        if (!hasContent) return RuleValidation.Invalid("订阅内容不包含任何规则")

        return RuleValidation.Valid(
            RulePayload(
                groups = groups,
                version = extractVersion(obj),
                isFullSource = isFullSource,
            )
        )
    }

    /**
     * 把远程规则合并进本地源。
     *
     * @param localJson 本地源的原始 JSON（legado 单源对象）
     * @param remoteJson 远程订阅内容
     * @param subscription 订阅项（提供「上次应用到的版本」，用于版本比对与幂等）
     */
    fun merge(
        localJson: String,
        remoteJson: String,
        subscription: RuleSubscription? = null,
    ): RuleMergeResult {
        val localObj = runCatching { JsonParser.parseString(localJson.trim()) }.getOrNull()
            ?.takeIf { it.isJsonObject }
            ?.asJsonObject
            ?: return RuleMergeResult(
                mergedJson = localJson,
                diff = RuleDiff.EMPTY,
                applied = false,
                reason = "本地源不是合法 JSON 对象",
                version = null,
            )

        // 校验不通过 → 整体拒绝，绝不把用户已有的源改坏
        val validation = validate(remoteJson)
        if (validation is RuleValidation.Invalid) {
            return RuleMergeResult(
                mergedJson = localJson,
                diff = RuleDiff.EMPTY,
                applied = false,
                reason = "订阅校验失败：${validation.reason}",
                version = null,
            )
        }
        val payload = (validation as RuleValidation.Valid).payload

        // 版本比对：远端版本更旧或相同都不该覆盖本地（避免来回同步来回覆盖）
        val currentVersion = subscription?.version?.trim()?.takeIf { it.isNotEmpty() }
            ?: extractVersion(localObj)
        val remoteVersion = payload.version
        if (!remoteVersion.isNullOrBlank()) {
            val cmp = compareVersions(remoteVersion, currentVersion)
            if (cmp == 0) {
                return RuleMergeResult(
                    mergedJson = localJson,
                    diff = RuleDiff.EMPTY,
                    applied = false,
                    reason = "版本未变化（$remoteVersion），已跳过",
                    version = currentVersion,
                )
            }
            if (cmp < 0) {
                return RuleMergeResult(
                    mergedJson = localJson,
                    diff = RuleDiff.EMPTY,
                    applied = false,
                    reason = "远端版本较旧（$remoteVersion < $currentVersion），已跳过",
                    version = currentVersion,
                )
            }
        }

        val diff = computeDiff(localObj, payload)
        if (!diff.hasChanges) {
            // 内容一致：只把版本号对齐（让下次同步能正确跳过）
            val merged = localObj.deepCopy()
            if (!remoteVersion.isNullOrBlank()) merged.addProperty("bookSourceVersion", remoteVersion)
            return RuleMergeResult(
                mergedJson = merged.toString(),
                diff = diff,
                applied = false,
                reason = "订阅内容与本地源一致，无需更新",
                version = remoteVersion ?: currentVersion,
            )
        }

        val merged = localObj.deepCopy()
        applyGroups(merged, payload)
        if (!remoteVersion.isNullOrBlank()) merged.addProperty("bookSourceVersion", remoteVersion)
        return RuleMergeResult(
            mergedJson = merged.toString(),
            diff = diff,
            applied = true,
            reason = "已更新：新增 ${diff.addedCount} 条、覆盖 ${diff.overwrittenCount} 条、删除 ${diff.deletedCount} 条",
            version = remoteVersion ?: currentVersion,
        )
    }

    /**
     * 只算差异，不合并（UI 的「应用前预览」用）。
     */
    fun diff(localJson: String, remoteJson: String): RuleDiff {
        val localObj = runCatching { JsonParser.parseString(localJson.trim()) }.getOrNull()
            ?.takeIf { it.isJsonObject }
            ?.asJsonObject
            ?: return RuleDiff.EMPTY
        val validation = validate(remoteJson)
        if (validation is RuleValidation.Invalid) return RuleDiff.EMPTY
        return computeDiff(localObj, (validation as RuleValidation.Valid).payload)
    }

    // ------------------------------------------------------------------
    // 内部实现
    // ------------------------------------------------------------------

    private fun parseGroup(value: JsonElement): GroupPayload? = when {
        value.isJsonNull -> GroupPayload.Deleted
        value.isJsonObject -> {
            val rules = mutableMapOf<String, String?>()
            value.asJsonObject.entrySet().forEach { (name, v) ->
                when {
                    v.isJsonNull -> rules[name] = null
                    v.isJsonPrimitive -> rules[name] = v.asString
                    else -> return null
                }
            }
            GroupPayload.Rules(rules)
        }

        else -> null
    }

    private fun computeDiff(localObj: JsonObject, payload: RulePayload): RuleDiff {
        val added = mutableListOf<String>()
        val overwritten = mutableListOf<String>()
        val deleted = mutableListOf<String>()
        var unchanged = 0

        for ((group, groupPayload) in payload.groups) {
            val localRules = localRules(localObj, group)
            when (groupPayload) {
                is GroupPayload.Deleted -> localRules.keys.forEach { deleted += ruleKey(group, it) }

                is GroupPayload.Rules -> for ((name, value) in groupPayload.rules) {
                    val isDelete = value == null || value == DELETE_MARKER
                    val exists = localRules.containsKey(name)
                    if (isDelete) {
                        // 删除一条本地不存在的规则 = 无事可做，不计入差异
                        if (exists) deleted += ruleKey(group, name)
                        continue
                    }
                    when {
                        !exists -> added += ruleKey(group, name)
                        localRules[name] != value -> overwritten += ruleKey(group, name)
                        else -> unchanged++
                    }
                }
            }
        }
        return RuleDiff(added, overwritten, deleted, unchanged)
    }

    private fun applyGroups(target: JsonObject, payload: RulePayload) {
        for ((group, groupPayload) in payload.groups) {
            when (groupPayload) {
                is GroupPayload.Deleted -> target.remove(group.jsonKey)

                is GroupPayload.Rules -> {
                    val existing = target.get(group.jsonKey)
                    val rules = if (existing != null && existing.isJsonObject) {
                        existing.asJsonObject
                    } else {
                        JsonObject()
                    }
                    for ((name, value) in groupPayload.rules) {
                        if (value == null || value == DELETE_MARKER) rules.remove(name)
                        else rules.addProperty(name, value)
                    }
                    target.add(group.jsonKey, rules)
                }
            }
        }
    }

    /** 本地源里某个规则组的现有内容 */
    private fun localRules(obj: JsonObject, group: RuleGroup): Map<String, String> {
        val el = obj.get(group.jsonKey) ?: return emptyMap()
        if (!el.isJsonObject) return emptyMap()
        return el.asJsonObject.entrySet().associate { (k, v) ->
            k to if (v.isJsonPrimitive) v.asString else v.toString()
        }
    }

    private fun ruleKey(group: RuleGroup, name: String): String = "${group.jsonKey}/$name"

    private fun stringField(obj: JsonObject, key: String): String? {
        val el = obj.get(key) ?: return null
        if (el.isJsonNull) return null
        return if (el.isJsonPrimitive) el.asString else null
    }

    private fun extractVersion(obj: JsonObject): String? {
        for (key in VERSION_KEYS) {
            val value = stringField(obj, key)?.trim()
            if (!value.isNullOrEmpty()) return value
        }
        return null
    }

    /**
     * 版本比较：`-1` 远端更旧、`0` 相同、`1` 远端更新。
     *
     * 逐段比较（数字段按数值比，非数字段按字符串比），
     * 这样 `1.10` 会比 `1.9` 新、`shuyuan-20240201` 会比 `shuyuan-20240101` 新。
     */
    private fun compareVersions(a: String, b: String?): Int {
        if (b.isNullOrBlank()) return 1
        val aParts = a.trim().split('.', '-', '_')
        val bParts = b.trim().split('.', '-', '_')
        for (i in 0 until maxOf(aParts.size, bParts.size)) {
            val x = aParts.getOrNull(i) ?: return -1
            val y = bParts.getOrNull(i) ?: return 1
            val nx = x.toIntOrNull()
            val ny = y.toIntOrNull()
            val cmp = if (nx != null && ny != null) {
                nx.compareTo(ny)
            } else {
                x.compareTo(y, ignoreCase = true)
            }
            if (cmp != 0) return if (cmp < 0) -1 else 1
        }
        return 0
    }
}
