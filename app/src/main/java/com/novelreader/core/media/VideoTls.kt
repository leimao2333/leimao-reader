package com.novelreader.core.media

import android.util.Log
import okhttp3.OkHttpClient
import java.security.KeyStore
import java.security.cert.CertPathValidatorException
import java.security.cert.CertificateException
import java.security.cert.CertificateExpiredException
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/**
 * 视频播放专用 TLS 兼容层（v1.9.5.1）。
 *
 * 采集站的播放 CDN 经常**忘记续期证书**：接口域名证书是好的（首页 / 搜索 / 详情都正常），
 * 但 m3u8 分片所在域名的证书已经过期。Media3 默认的 `DefaultHttpDataSource` 走系统
 * `HttpsURLConnection`，由 `NetworkSecurityTrustManager` 强制校验证书有效期，
 * 结果是整条流直接 `SSLHandshakeException: Chain validation failed`，
 * 表现为「列表能出来、点进去一直加载不出视频」。
 *
 * 这里把播放分片的数据源换成项目自己的 OkHttp 客户端（见 [VideoMediaCache]），
 * 并对证书链**只放宽「已过期」这一种情况**：
 * - 仍然使用系统信任库做完整链式校验，自签名 / 签发者不可信 / 域名不匹配照旧拒绝；
 * - 域名校验保持 OkHttp 默认的严格实现，不设置任何全信任 HostnameVerifier；
 * - 只有「链里确实有过期证书」且「失败原因正是有效期」时才放行。
 *
 * 影响范围仅限视频播放数据源，[com.novelreader.core.network.HttpClient] 的
 * 源解析 / 图片 / 更新等请求仍走系统默认校验，不受影响。
 */
object VideoTls {

    private const val TAG = "VideoTls"
    private const val CONNECT_TIMEOUT_MS = 15_000L
    private const val READ_TIMEOUT_MS = 20_000L

    @Volatile
    private var cached: OkHttpClient? = null

    /** 播放专用客户端（进程内单例，复用连接池） */
    fun client(): OkHttpClient = cached ?: synchronized(this) {
        cached ?: buildClient().also { cached = it }
    }

    private fun buildClient(): OkHttpClient {
        val tolerant = ExpiryTolerantTrustManager(systemTrustManager())
        val sslContext = SSLContext.getInstance("TLS").apply {
            init(null, arrayOf<TrustManager>(tolerant), null)
        }
        return OkHttpClient.Builder()
            .sslSocketFactory(sslContext.socketFactory, tolerant)
            // 不设置 hostnameVerifier：保留 OkHttp 默认的严格域名校验
            .connectTimeout(CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .readTimeout(READ_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .followRedirects(true)
            // 部分采集站 https 播放地址会 302 到 http 分片，允许跨协议重定向
            .followSslRedirects(true)
            .build()
    }

    private fun systemTrustManager(): X509TrustManager {
        val factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        factory.init(null as KeyStore?)
        return factory.trustManagers
            ?.filterIsInstance<X509TrustManager>()
            ?.firstOrNull()
            ?: throw IllegalStateException("系统信任库不可用")
    }

    /**
     * 是否可以只因为「证书过期」而放行。
     *
     * 两个条件都要满足，避免把自签名 / 域名伪造这类真正的风险一起放过去：
     * 1. 证书链里确实存在已过期的证书；
     * 2. 系统校验失败的原因链里出现有效期相关的错误（异常类型或错误信息）。
     *
     * 抽成纯函数便于单测；`now` 显式传入避免隐藏的系统时间依赖。
     */
    internal fun isExpiryOnly(
        chain: Array<out X509Certificate>,
        error: CertificateException,
        now: Long = System.currentTimeMillis(),
    ): Boolean {
        if (!chain.any { it.notAfter.time < now }) return false
        var cause: Throwable? = error
        while (cause != null) {
            if (cause is CertificateExpiredException) return true
            if ((cause as? CertPathValidatorException)?.reason ==
                CertPathValidatorException.BasicReason.EXPIRED
            ) return true
            val msg = cause.message.orEmpty()
            if (msg.contains("expired", ignoreCase = true) ||
                msg.contains("timestamp check failed", ignoreCase = true) ||
                msg.contains("certificate not valid", ignoreCase = true)
            ) return true
            cause = cause.cause
        }
        return false
    }

    private class ExpiryTolerantTrustManager(
        private val delegate: X509TrustManager,
    ) : X509TrustManager {

        override fun checkClientTrusted(chain: Array<out X509Certificate>, authType: String) =
            delegate.checkClientTrusted(chain, authType)

        override fun checkServerTrusted(chain: Array<out X509Certificate>, authType: String) {
            try {
                delegate.checkServerTrusted(chain, authType)
            } catch (e: CertificateException) {
                if (!isExpiryOnly(chain, e)) throw e
                Log.i(
                    TAG,
                    "放行已过期的播放证书：${chain.firstOrNull()?.subjectX500Principal?.name.orEmpty()}",
                )
            }
        }

        override fun getAcceptedIssuers(): Array<X509Certificate> = delegate.acceptedIssuers
    }
}
