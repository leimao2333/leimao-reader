package com.novelreader.core.image

import okhttp3.Interceptor
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody

/**
 * 在 OkHttp 响应层把「加密图床」的图片还原成正常图片字节。
 *
 * 只挂在 Coil 的图片加载 OkHttpClient 上（见 [com.novelreader.NovelReaderApp]），
 * 因此视频播放、JSON 接口等流量不受影响。
 *
 * 具体解密规则见 [ImageDecryptor]。
 */
class ImageDecryptInterceptor : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val response = chain.proceed(chain.request())
        if (!response.isSuccessful) return response
        val body = response.body ?: return response

        // 图片不大，直接全量读出后再判断 / 解密；body 读一次即失效，两种情况都要重建
        val raw = runCatching { body.bytes() }.getOrNull() ?: return response
        val fixed = ImageDecryptor.maybeDecrypt(raw)
        return response.newBuilder()
            .body(fixed.toResponseBody(body.contentType()))
            .build()
    }
}
