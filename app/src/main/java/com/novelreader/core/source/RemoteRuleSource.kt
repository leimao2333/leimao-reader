package com.novelreader.core.source

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlin.coroutines.cancellation.CancellationException

/**
 * 远程规则源拉取（B7 · HTTP 层）。
 *
 * 与 [RuleMerger] 的分工：
 * - [RuleMerger] 只管「内容合不合法、怎么合并」，是纯逻辑、可单测；
 * - 本类只管「怎么把内容取回来、存到哪」，是 IO 与网络。
 *
 * 两点刻意的设计：
 * - **拉到的内容先过一遍校验再落盘**：坏订阅不该留在磁盘上等着哪天被误用，
 *   更不该进入合并流程（合并前校验是最后一道保险，这里是更早的一道）。
 * - **失败只返回失败原因，不抛异常**：订阅是「锦上添花」的功能，
 *   拉取失败不该让调用方（源管理页 / 后台任务）崩掉。
 */
class RemoteRuleSource(
    private val context: Context,
    private val client: OkHttpClient = defaultClient(),
) {

    companion object {
        /** 订阅内容的体积上限（4MB）：规则 JSON 远小于此，超了多半不是规则文件 */
        private const val MAX_CONTENT_BYTES = 4 * 1024 * 1024

        /** 缓存目录名（放 cacheDir：内容随时可以重新拉，不该占用户的正式存储） */
        private const val CACHE_DIR = "rule_subscriptions"

        /**
         * 订阅地址是否允许。
         *
         * 只放行 http / https：订阅内容会被解析并合并进源，
         * `file://` / `content://` 这类本地协议没有意义（源本来就在本地），
         * 放行反而会让「订阅」变成一个任意文件读取的口子。
         */
        fun isAllowedUrl(url: String): Boolean {
            val trimmed = url.trim()
            if (trimmed.isEmpty()) return false
            val lower = trimmed.lowercase()
            if (!lower.startsWith("http://") && !lower.startsWith("https://")) return false
            return runCatching { java.net.URI(trimmed).host?.isNotEmpty() == true }
                .getOrDefault(false)
        }

        private fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .callTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    /** 拉取结果 */
    sealed interface RemoteRuleResult {
        data class Success(
            val content: String,
            /** 内容自带的版本号 */
            val version: String?,
            val bytes: Int,
            /** 是否来自本地缓存 */
            val fromCache: Boolean,
        ) : RemoteRuleResult

        data class Failure(val reason: String) : RemoteRuleResult
    }

    /**
     * 拉取订阅内容（不落盘）。
     *
     * 内容会在返回前经过 [RuleMerger.validate] 校验：不合法直接判失败，
     * 免得坏内容一路流到合并环节才被拦下。
     */
    suspend fun fetch(subscription: RuleSubscription): RemoteRuleResult = withContext(Dispatchers.IO) {
        if (!isAllowedUrl(subscription.url)) {
            return@withContext RemoteRuleResult.Failure("订阅地址不合法")
        }
        val request = Request.Builder()
            .url(subscription.url.trim())
            .header("User-Agent", com.novelreader.core.network.HttpClient.DEFAULT_USER_AGENT)
            .header("Accept", "application/json, text/plain, */*")
            .get()
            .build()

        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return@withContext RemoteRuleResult.Failure("HTTP ${response.code}")
                }
                val body = response.body
                    ?: return@withContext RemoteRuleResult.Failure("响应为空")
                val bytes = body.byteStream().readCapped(MAX_CONTENT_BYTES)
                    ?: return@withContext RemoteRuleResult.Failure("订阅内容过大（>4MB）")
                val charset = body.contentType()?.charset(Charsets.UTF_8) ?: Charsets.UTF_8
                val content = String(bytes, charset)
                if (content.isBlank()) {
                    return@withContext RemoteRuleResult.Failure("订阅内容为空")
                }
                val validation = RuleMerger.validate(content)
                if (validation is RuleValidation.Invalid) {
                    return@withContext RemoteRuleResult.Failure("订阅内容校验失败：${validation.reason}")
                }
                val version = (validation as RuleValidation.Valid).payload.version
                RemoteRuleResult.Success(content, version, bytes.size, fromCache = false)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // 只记异常类名：message 里常带完整 URL，不能进日志 / 展示层
            RemoteRuleResult.Failure(e.javaClass.simpleName)
        }
    }

    /**
     * 拉取并落盘（覆盖上次缓存）。
     */
    suspend fun fetchAndSave(subscription: RuleSubscription): RemoteRuleResult {
        val result = fetch(subscription)
        if (result is RemoteRuleResult.Success) {
            runCatching { saveCache(subscription, result.content) }
        }
        return result
    }

    /** 读缓存内容；没有缓存返回 null */
    fun readCache(subscription: RuleSubscription): String? = runCatching {
        val file = cacheFile(subscription)
        if (file.isFile) file.readText() else null
    }.getOrNull()

    /** 缓存文件路径（按订阅地址的哈希命名，同一地址只有一份） */
    fun cacheFile(subscription: RuleSubscription): File =
        File(File(context.cacheDir, CACHE_DIR), "${hash(subscription.url)}.json")

    /** 写缓存 */
    private fun saveCache(subscription: RuleSubscription, content: String) {
        val file = cacheFile(subscription)
        file.parentFile?.mkdirs()
        file.writeText(content)
    }

    /** URL → 文件名（sha1，避免地址里的非法字符，也避免超长文件名） */
    private fun hash(url: String): String {
        val digest = MessageDigest.getInstance("SHA-1").digest(url.trim().toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }

    /** 读流并限长：超过上限返回 null（防止把内存吃穿） */
    private fun InputStream.readCapped(cap: Int): ByteArray? {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(8 * 1024)
        var total = 0
        while (true) {
            val n = read(buf)
            if (n <= 0) break
            total += n
            if (total > cap) return null
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }
}
