package com.novelreader.core.update

import com.google.gson.JsonParser
import com.novelreader.core.network.HttpClient
import java.io.File

/**
 * 应用更新检测与下载（v1.5.4.0）。
 *
 * 从 GitHub Releases 拉取最新版本信息，与当前版本比对，
 * 若远端更新则返回可下载的 APK 信息；下载走 [HttpClient.downloadFile] 落盘到缓存目录。
 *
 * 仓库固定为 `leimao2333/leimao-reader`。
 */
object UpdateChecker {

    private const val API_URL =
        "https://api.github.com/repos/leimao2333/leimao-reader/releases/latest"

    /** 检查结果：null = 已是最新或检查失败 */
    data class UpdateInfo(
        val version: String,
        val apkUrl: String,
        val apkSize: Long,
        val body: String,
    )

    /**
     * 检查 GitHub 最新版本，返回是否比 [currentVersion] 更新。
     * 阻塞式网络调用，必须在 IO 线程执行。
     */
    fun checkLatest(currentVersion: String): UpdateInfo? = runCatching {
        val json = HttpClient.get(API_URL, timeoutMs = 15_000L, retry = false)
        val root = JsonParser.parseString(json).asJsonObject
        val tagName = root.get("tag_name")?.asString?.removePrefix("v") ?: return@runCatching null
        if (!isNewer(tagName, currentVersion)) return@runCatching null

        val assets = root.getAsJsonArray("assets") ?: return@runCatching null
        // 优先取 .apk 附件；找不到约定命名的就取第一个 .apk
        var apkAsset = assets.firstOrNull {
            it.asJsonObject.get("name")?.asString?.endsWith(".apk") == true
        } ?: return@runCatching null
        // 若存在多个 APK，取文件名带版本号的（leimao-reader-v1.5.4.0.apk）
        assets.firstOrNull {
            it.asJsonObject.get("name")?.asString == "leimao-reader-v$tagName.apk"
        }?.let { apkAsset = it }

        val apkUrl = apkAsset.asJsonObject.get("browser_download_url")?.asString
            ?: return@runCatching null
        val apkSize = apkAsset.asJsonObject.get("size")?.asLong ?: 0L
        val body = root.get("body")?.asString ?: ""
        UpdateInfo(tagName, apkUrl, apkSize, body)
    }.getOrNull()

    /**
     * 下载 APK 到 [target] 文件（带进度回调，运行在下载线程）。
     * 阻塞式，必须在 IO 线程执行。
     */
    fun downloadApk(url: String, target: File, onProgress: (Long, Long) -> Unit) {
        HttpClient.downloadFile(url, target, onProgress = { d, t -> onProgress(d, t) })
    }

    // ===== 内部工具 =====

    /**
     * 四段式版本比较（顶端.大.小.修订）。返回 [a] 是否比 [b] 更新。
     */
    private fun isNewer(a: String, b: String): Boolean {
        val pa = parseVersion(a) ?: return false
        val pb = parseVersion(b) ?: return false
        for (i in 0 until 4) {
            val x = pa.getOrElse(i) { 0 }
            val y = pb.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }

    private fun parseVersion(v: String): List<Int>? {
        val cleaned = v.trim().removePrefix("v")
        val parts = cleaned.split(".").mapNotNull { it.toIntOrNull() }
        return if (parts.isEmpty()) null else parts
    }
}
