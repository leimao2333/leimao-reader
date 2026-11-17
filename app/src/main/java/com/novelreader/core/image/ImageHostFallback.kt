package com.novelreader.core.image

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * 图床「备用域名」映射表（v1.9.6.2）。
 *
 * 背景：部分图床（典型如漫蛙吧 manwaba 系列站）把图片放在**裸 IP + 非标端口**上，
 * 例如 `https://52.128.238.250:5367/en_images/...`。这种地址在部分网络下会被
 * 直接阻断：TCP 能建连（几毫秒），但 TLS 握手立刻被切断
 * （`SSL_ERROR_SYSCALL`，`time_connect` 有值而 `time_appconnect=0`），
 * 于是 Coil 拿不到任何字节 → 封面整屏色块、正文一张图都出不来。
 *
 * 而站点前端自己也声明了同一图床的**域名入口**（`tu.mwzu.cc`），实测：
 * - 路径结构完全一致（`/en_images/<yyyymm>/cover/<name>.jpg`）；
 * - 返回字节与 IP 图床**逐字节相同**（同 IV、同密文、同长度）；
 * - 在 PC 与手机网络下均可达（200）。
 *
 * 因此这里维护一份 `host:port → 备用 host` 的映射：原地址请求失败时，
 * 换用备用域名重试一次，即可绕开对裸 IP 端口的阻断。
 *
 * 注意：换域名**不影响解密** —— 备用域名下发的同样是 AES 整包密文，
 * 仍由 [ImageDecryptor] 还原，二者互不干扰。
 */
object ImageHostFallback {

    /**
     * 被封的图床地址 → 可用域名入口。
     *
     * 键为 `host:port`（端口是区分同一 IP 上不同图床服务的关键，
     * 例如 8088 / 8067 / 5367 分别是不同的节点）。
     */
    private val FALLBACKS: Map<String, String> = mapOf(
        // 漫蛙吧（manwaba）图床：三个节点都指向同一套存储，域名入口为 tu.mwzu.cc
        "52.128.238.250:5367" to "https://tu.mwzu.cc",
        "38.147.106.154:8088" to "https://tu.mwzu.cc",
        "38.246.114.12:8067" to "https://tu.mwzu.cc",
    )

    /**
     * 若 [url] 命中已知被封图床，返回换用备用域名后的地址（保留原 path / query）；
     * 否则返回 null（调用方按原地址请求，行为不变）。
     */
    fun alternate(url: HttpUrl): HttpUrl? {
        val base = FALLBACKS["${url.host}:${url.port}"] ?: return null
        val alt = base.toHttpUrlOrNull() ?: return null
        val rewritten = url.newBuilder()
            .scheme(alt.scheme)
            .host(alt.host)
            .port(alt.port)
            .build()
        // 备用域名若与原地址等价（理论上不会），视为无映射，避免无意义重试
        return rewritten.takeIf { it != url }
    }
}
