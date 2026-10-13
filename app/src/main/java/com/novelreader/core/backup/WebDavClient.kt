package com.novelreader.core.backup

import com.novelreader.core.network.HttpClient
import com.novelreader.core.network.NetworkException
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import java.nio.charset.StandardCharsets
import java.util.Base64

/**
 * WebDav 客户端（v1.3.8.0 云备份）。
 *
 * WebDav 本质是 HTTP 加一组方法：`PUT` 上传、`GET` 下载、`DELETE` 删除、
 * `MKCOL` 建目录、`PROPFIND` 探测。这里只用到最通用的几个（Basic Auth 认证），
 * 兼容坚果云 / Nextcloud / 群晖 WebDav / 各类自建 WebDav 服务。
 *
 * 设计：
 * - 认证用 **HTTP Basic**（WebDav 事实标准，覆盖面最广），用户名密码拼 Authorization 头。
 * - URL 规范化：把「服务器根 + 相对路径」拼成一个无歧义的地址（补斜杠、去空段）。
 * - 所有调用都是**阻塞式**，必须在 IO 线程执行（`withContext(Dispatchers.IO)`）。
 *
 * @param baseUrl WebDav 服务器根地址（如 `https://dav.jianguoyun.com/dav/`），可带路径前缀。
 * @param username Basic 认证用户名，null 表示匿名。
 * @param password Basic 认证密码，用户名非空时使用。
 */
class WebDavClient(
    baseUrl: String,
    private val username: String? = null,
    private val password: String? = null,
) {

    /** 规范化后的服务器根（去尾部多余斜杠，至少保留 `http(s)://host`）。 */
    val root: String = normalizeBaseUrl(baseUrl)

    /** 认证请求头（Basic Auth，匿名则为空 map）。 */
    private val authHeaders: Map<String, String> = buildAuthHeaders()

    /**
     * 把相对路径拼到服务器根上，得到完整资源地址。
     *
     * 规则：
     * - 路径为空 → 返回根；
     * - 相对路径是否以 `/` 开头都能正确拼接（根会先去掉尾部 `/`，路径会补上前导 `/`）。
     * - 路径中的 `//`、`.`、`..` 交给 OkHttp 的 [okhttp3.HttpUrl] 规范解析。
     */
    fun url(path: String): String {
        val clean = path.trim()
        if (clean.isEmpty()) return root
        val joined = if (clean.startsWith("/")) "$root$clean" else "$root/$clean"
        return runCatching { joined.toHttpUrl().toString() }.getOrDefault(joined)
    }

    /** 上传内容到指定路径（覆盖已存在的文件）。 */
    fun put(path: String, content: String, contentType: String = "application/json") {
        val body = content.toByteArray(StandardCharsets.UTF_8)
        val request = okhttp3.Request.Builder()
            .url(url(path))
            .put(body.toRequestBody(contentType.toMediaTypeOrNull()))
            .header("User-Agent", HttpClient.DEFAULT_USER_AGENT)
            .apply { authHeaders.forEach { (k, v) -> header(k, v) } }
            .build()

        HttpClient.executeRaw(request)
    }

    /** 下载指定路径的内容，返回 UTF-8 字符串。 */
    fun get(path: String): String {
        val request = okhttp3.Request.Builder()
            .url(url(path))
            .get()
            .header("User-Agent", HttpClient.DEFAULT_USER_AGENT)
            .apply { authHeaders.forEach { (k, v) -> header(k, v) } }
            .build()

        return HttpClient.executeRaw(request).body?.bytes()?.toString(StandardCharsets.UTF_8)
            ?: throw NetworkException("空响应: ${url(path)}")
    }

    /** 删除指定路径的文件（不存在不报错，返回是否真正删除了东西）。 */
    fun delete(path: String): Boolean {
        val request = okhttp3.Request.Builder()
            .url(url(path))
            .delete()
            .header("User-Agent", HttpClient.DEFAULT_USER_AGENT)
            .apply { authHeaders.forEach { (k, v) -> header(k, v) } }
            .build()

        val response = HttpClient.executeRaw(request)
        // 404 表示本来就不存在，视为「无需删除」；其余 2xx 视为删除成功。
        return if (response.code == 404) false else response.isSuccessful
    }

    /**
     * 测试连接：发一个 `PROPFIND`（Depth: 0）探测服务器根是否可用、认证是否正确。
     * 返回 null 表示连接成功，否则返回错误信息。
     */
    fun testConnection(): String? = runCatching {
        val request = okhttp3.Request.Builder()
            .url(root)
            .method("PROPFIND", null)
            .header("User-Agent", HttpClient.DEFAULT_USER_AGENT)
            .header("Depth", "0")
            .apply { authHeaders.forEach { (k, v) -> header(k, v) } }
            .build()

        HttpClient.executeRaw(request).use { resp ->
            // 207 Multi-Status（标准 WebDav 响应）或任意 2xx/3xx 都算通；401/403 是认证问题。
            when {
                resp.code == 401 || resp.code == 403 -> "认证失败，请检查用户名与密码（${resp.code}）"
                resp.isSuccessful || resp.code == 207 -> null
                else -> "连接失败：HTTP ${resp.code}"
            }
        }
    }.getOrElse { "连接失败：${it.message ?: "未知错误"}" }

    /** 把服务器根规范化：补协议、去尾部多余斜杠。 */
    private fun normalizeBaseUrl(input: String): String {
        var s = input.trim().trimEnd('/')
        if (s.isEmpty()) throw IllegalArgumentException("服务器地址不能为空")
        if (!s.startsWith("http://") && !s.startsWith("https://")) {
            s = "https://$s"
        }
        return s
    }

    private fun buildAuthHeaders(): Map<String, String> {
        val u = username?.takeIf { it.isNotBlank() } ?: return emptyMap()
        val token = Base64.getEncoder()
            .encodeToString("$u:${password.orEmpty()}".toByteArray(StandardCharsets.UTF_8))
        return mapOf("Authorization" to "Basic $token")
    }
}
