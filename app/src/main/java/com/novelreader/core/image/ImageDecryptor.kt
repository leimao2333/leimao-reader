package com.novelreader.core.image

import android.util.Log
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * 图床「整包加密」图片的解密器。
 *
 * 背景：部分漫画站为了防止图片被直接盗链／被阅读器抓取，
 * 把**整张图片用 AES-256-CBC 加密后**再下发给客户端，并用同样的字节长度谎报
 * `Content-Type: image/jpeg`。浏览器的网页端在拿到字节流后由前端 JS 解密
 * （`BaseUtil.getSecureImageUrl`：前 16 字节是 IV，其余是密文，密钥硬编码在前端），
 * 再 `URL.createObjectURL(blob)` 显示。
 *
 * 而书源（Venera / legado）只提供图片地址、不会解密，于是客户端拿到的永远是
 * 「一堆高熵字节」——Coil 解码失败，读者看到的就是「图片加载失败」。
 * 这与站点是否可达、请求头是否正确完全无关（已实测：同一地址带完整浏览器头、
 * 用浏览器打开都是同一份密文）。
 *
 * 处理方式：在响应字节层面兜住。
 * - 先按**魔数**判断是否已经是图片（JPEG/PNG/GIF/WEBP/AVIF/HEIC/BMP）——是则原样放行；
 * - 否则按「前 16 字节 IV + AES-CBC 密文」试解，解出来确实是图片才采用；
 * - 两者都不成立就原样返回。
 *
 * 这样对其它源零影响：正常图直接命中第一分支；万一某个非图片响应被拿去解密，
 * CBC 的 PKCS7 填充校验几乎必然失败，同样回落到原样返回。
 */
object ImageDecryptor {

    private const val TAG = "ImageDecryptor"

    /** 前 16 字节是 IV */
    private const val IV_LENGTH = 16

    /**
     * 已知的图床密钥（前端 `BaseUtil.AES_KEY`）。
     *
     * 同一套前端模板会在同系列站点间复用，因此这里按列表维护，逐个尝试即可；
     * 以后遇到别的加密图床，把 key 追加进来就行。
     */
    private val KEYS: List<ByteArray> = listOf(
        "0B6666A0-BB59-1381-B746-a0E4C9AC".toByteArray(Charsets.UTF_8),
    )

    /**
     * 若 [data] 是加密图片则返回解密后的字节，否则原样返回（同一引用）。
     *
     * 调用方用 `===` 判断是否发生了转换即可，不需要额外开销。
     */
    fun maybeDecrypt(data: ByteArray): ByteArray {
        if (data.size <= IV_LENGTH + 16) return data
        if (looksLikeImage(data)) return data
        for (key in KEYS) {
            val plain = runCatching { aesCbcDecrypt(data, key) }.getOrNull() ?: continue
            if (looksLikeImage(plain)) {
                Log.d(TAG, "解密成功 ${data.size} -> ${plain.size} 字节（${describe(plain)}）")
                return plain
            }
        }
        return data
    }

    /** 是否已经是浏览器/解码器认得的图片格式 */
    fun looksLikeImage(data: ByteArray): Boolean {
        if (data.size < 12) return false
        // JPEG
        if (data[0] == 0xFF.toByte() && data[1] == 0xD8.toByte()) return true
        // PNG
        if (data[0] == 0x89.toByte() && data[1] == 0x50.toByte() &&
            data[2] == 0x4E.toByte() && data[3] == 0x47.toByte()
        ) return true
        // GIF
        if (data[0] == 'G'.code.toByte() && data[1] == 'I'.code.toByte() &&
            data[2] == 'F'.code.toByte()
        ) return true
        // RIFF....WEBP
        if (data[0] == 'R'.code.toByte() && data[1] == 'I'.code.toByte() &&
            data[2] == 'F'.code.toByte() && data[3] == 'F'.code.toByte() &&
            data[8] == 'W'.code.toByte() && data[9] == 'E'.code.toByte()
        ) return true
        // 偏移 4 起的 ftyp：AVIF / HEIC / MP4 等 ISO-BMFF
        if (data[4] == 'f'.code.toByte() && data[5] == 't'.code.toByte() &&
            data[6] == 'y'.code.toByte() && data[7] == 'p'.code.toByte()
        ) return true
        // BMP
        if (data[0] == 'B'.code.toByte() && data[1] == 'M'.code.toByte()) return true
        return false
    }

    /** AES-256-CBC + PKCS7：前 16 字节为 IV */
    private fun aesCbcDecrypt(data: ByteArray, key: ByteArray): ByteArray {
        val iv = data.copyOfRange(0, IV_LENGTH)
        val ciphertext = data.copyOfRange(IV_LENGTH, data.size)
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(
            Cipher.DECRYPT_MODE,
            SecretKeySpec(key.copyOf(32), "AES"),
            IvParameterSpec(iv),
        )
        return cipher.doFinal(ciphertext)
    }

    private fun describe(data: ByteArray): String = when {
        data[0] == 0xFF.toByte() && data[1] == 0xD8.toByte() -> "JPEG"
        data[0] == 0x89.toByte() -> "PNG"
        data[0] == 'R'.code.toByte() -> "WEBP"
        data[0] == 'G'.code.toByte() -> "GIF"
        else -> "未知(${data.take(4).joinToString("") { "%02x".format(it) }})"
    }
}
