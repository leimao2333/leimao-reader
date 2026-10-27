package com.novelreader

import com.google.gson.JsonParser
import com.novelreader.source.legado.LegadoBookSource
import java.io.File
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URI
import java.net.URL
import java.security.MessageDigest
import java.nio.charset.StandardCharsets
import kotlin.system.measureTimeMillis

internal object SourceBundleTestSupport {
    const val DEFAULT_MANIFEST_URL =
        "https://raw.githubusercontent.com/Jyy7998/maomao/main/all.json"

    data class LoadedSources(
        val sources: List<LegadoBookSource>,
        val manifestUrl: String,
        val manifestMs: Long,
        val legadoBundleMs: Long,
    )

    fun sourceId(source: LegadoBookSource, prefix: String): String {
        val identity = com.google.gson.Gson().toJson(
            listOf(
                source.name.trim(),
                source.url.trim().substringBefore('#').trimEnd('/'),
                source.type,
                source.searchUrl.trim(),
                source.ruleSearch.toSortedMap(),
                source.ruleBookInfo.toSortedMap(),
                source.ruleToc.toSortedMap(),
                source.ruleContent.toSortedMap(),
            ),
        )
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(identity.toByteArray(StandardCharsets.UTF_8))
            .take(12)
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
        return "${prefix}_$digest"
    }

    fun medianDurationMs(values: List<Long>): Double {
        if (values.isEmpty()) return 0.0
        val sorted = values.sorted()
        val middle = sorted.size / 2
        return if (sorted.size % 2 == 0) {
            (sorted[middle - 1].toDouble() + sorted[middle].toDouble()) / 2.0
        } else {
            sorted[middle].toDouble()
        }
    }

    fun loadLegado(location: String? = null): LoadedSources {
        val target = location?.takeIf { it.isNotBlank() } ?: DEFAULT_MANIFEST_URL
        val timeoutMs = 20_000
        val manifestUrl: String
        var manifestText = ""
        var manifestMs = 0L

        if (target.startsWith("http://") || target.startsWith("https://")) {
            manifestUrl = target
            val elapsed = measureTimeMillis { manifestText = fetch(target, timeoutMs) }
            manifestMs = elapsed
        } else {
            val file = File(target).absoluteFile
            require(file.isFile) { "Source file does not exist: ${file.path}" }
            manifestUrl = file.toURI().toString()
            manifestText = file.readText()
        }

        val root = JsonParser.parseString(manifestText)
        val files = root.takeIf { it.isJsonObject }
            ?.asJsonObject?.getAsJsonArray("files")
        val legadoName = files?.firstOrNull {
            it.isJsonPrimitive && it.asString.substringAfterLast('/') == "legado-all.json"
        }?.asString
        if (legadoName == null) {
            val directSources = LegadoBookSource.parseList(manifestText)
            return LoadedSources(directSources, manifestUrl, manifestMs, 0L)
        }

        var bundleText = ""
        val bundleMs: Long
        if (manifestUrl.startsWith("file:")) {
            val manifestFile = File(URL(manifestUrl).toURI())
            val bundleFile = File(manifestFile.parentFile, legadoName.substringAfterLast('/'))
            require(bundleFile.isFile) {
                "Manifest points to ${bundleFile.path}, but the sibling source file is missing"
            }
            val elapsed = measureTimeMillis { bundleText = bundleFile.readText() }
            bundleMs = elapsed
        } else {
            val bundleUrl = URL(URL(manifestUrl), legadoName).toString()
            val elapsed = measureTimeMillis { bundleText = fetch(bundleUrl, timeoutMs) }
            bundleMs = elapsed
        }
        return LoadedSources(
            LegadoBookSource.parseList(bundleText),
            manifestUrl,
            manifestMs,
            bundleMs,
        )
    }

    private fun fetch(url: String, timeoutMs: Int): String {
        val proxyAddress = if (url.startsWith("https://", ignoreCase = true)) {
            System.getenv("HTTPS_PROXY") ?: System.getenv("https_proxy")
                ?: System.getenv("HTTP_PROXY") ?: System.getenv("http_proxy")
        } else {
            System.getenv("HTTP_PROXY") ?: System.getenv("http_proxy")
        }
        val proxy = proxyAddress?.takeIf { it.isNotBlank() }?.let {
            val uri = URI(if (it.contains("://")) it else "http://$it")
            Proxy(Proxy.Type.HTTP, InetSocketAddress(uri.host, uri.port.takeIf { p -> p > 0 } ?: 80))
        }
        val connection = if (proxy == null) URL(url).openConnection() else URL(url).openConnection(proxy)
        connection.connectTimeout = timeoutMs
        connection.readTimeout = timeoutMs
        connection.setRequestProperty("User-Agent", "LeimaoReader-SourceHealthTest/1.0")
        return connection.getInputStream().bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
    }
}
