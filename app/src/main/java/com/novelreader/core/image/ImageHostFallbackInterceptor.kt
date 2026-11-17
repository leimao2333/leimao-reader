package com.novelreader.core.image

import okhttp3.Interceptor
import okhttp3.Response
import java.io.IOException

/**
 * 图床「备用域名」回退拦截器（v1.9.6.2）。
 *
 * 只在**原地址请求失败**时介入：抓 [IOException]（含 TLS 握手被切断的
 * `SSLException` / `SSL_ERROR_SYSCALL`），若该地址命中 [ImageHostFallback]
 * 的映射表，就换用站点自带的域名入口重试一次。
 *
 * 为什么不无条件改写？—— 原地址在部分网络（如 PC 直连）是可用的，
 * 无条件换域名等于把可用路径也换掉，徒增一层不确定性；
 * 只在失败时回退，对其它源、其它网络零影响。
 *
 * 对解密无影响：[ImageHostFallback] 的备用域名下发的是同一份 AES 密文，
 * 仍由 [ImageDecryptor] 处理。
 */
class ImageHostFallbackInterceptor : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        // 未命中映射表的地址：原样请求，行为与改动前完全一致
        val altUrl = ImageHostFallback.alternate(request.url)
            ?: return chain.proceed(request)

        return try {
            chain.proceed(request)
        } catch (e: IOException) {
            // 主地址失败：换域名再试一次。备用域名也失败则抛原始异常，
            // 让 Coil 走占位逻辑（与旧行为一致）。
            val altRequest = request.newBuilder().url(altUrl).build()
            try {
                chain.proceed(altRequest)
            } catch (_: IOException) {
                throw e
            }
        }
    }
}
