package com.novelreader

import com.novelreader.core.image.ImageDecryptor
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

/**
 * 图床「整包 AES-CBC 加密」图片的解密验证。
 *
 * 样本由前端密钥（`0B6666A0-BB59-1381-B746-a0E4C9AC`）离线加密生成：
 * 明文是一段 JPEG 头，`IV` 取 0..15，密文按 PKCS7 填充，最终 blob = IV + 密文。
 */
class ImageDecryptorTest {

    /** IV(0..15) + AES-256-CBC(JPEG 头, PKCS7) */
    private val encryptedJpeg = Base64.getDecoder().decode(
        "AAECAwQFBgcICQoLDA0OD2IWydesgFyQfaLYNLLK5aCyCqF3+SlD3Ell4/sexj9l",
    )

    /** 与上面样本对应的明文前 12 字节（JPEG SOI + APP0/JFIF） */
    private val jpegHead = byteArrayOf(
        0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(),
        0x00, 0x10, 0x4A, 0x46, 0x49, 0x46, 0x00, 0x01,
    )

    @Test
    fun `AES 加密的图片会被还原成 JPEG`() {
        val out = ImageDecryptor.maybeDecrypt(encryptedJpeg)
        assertTrue("应当发生解密", out !== encryptedJpeg)
        assertArrayEquals(jpegHead, out.copyOfRange(0, jpegHead.size))
        assertTrue(ImageDecryptor.looksLikeImage(out))
    }

    @Test
    fun `已经是 JPEG 的字节原样返回`() {
        val data = jpegHead + ByteArray(64) { 0x20 }
        assertTrue(ImageDecryptor.maybeDecrypt(data) === data)
    }

    @Test
    fun `PNG GIF WEBP ftyp BMP 都算图片`() {
        val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47) + ByteArray(20)
        val gif = byteArrayOf(0x47, 0x49, 0x46, 0x38) + ByteArray(20)
        val webp = "RIFF".toByteArray() + ByteArray(4) + "WEBP".toByteArray() + ByteArray(8)
        val avif = ByteArray(4) + "ftypavif".toByteArray() + ByteArray(8)
        val bmp = byteArrayOf(0x42, 0x4D) + ByteArray(22)
        listOf(png, gif, webp, avif, bmp).forEach {
            assertTrue(ImageDecryptor.looksLikeImage(it))
            assertTrue(ImageDecryptor.maybeDecrypt(it) === it)
        }
    }

    @Test
    fun `无法解密的随机字节原样返回`() {
        // 16 字节 IV + 32 字节「密文」，但密钥不对 → PKCS7 校验失败 → 原样返回
        val junk = ByteArray(48) { (it * 7 + 3).toByte() }
        assertTrue(ImageDecryptor.maybeDecrypt(junk) === junk)
        assertFalse(ImageDecryptor.looksLikeImage(junk))
    }

    @Test
    fun `过短的数据不处理`() {
        val short = ByteArray(20) { 1 }
        assertTrue(ImageDecryptor.maybeDecrypt(short) === short)
    }

    @Test
    fun `多块 CBC 密文同样能还原`() {
        val blob = Base64.getDecoder().decode(MULTI_BLOCK_B64)
        val out = ImageDecryptor.maybeDecrypt(blob)
        assertTrue("应当发生解密", out !== blob)
        assertTrue(ImageDecryptor.looksLikeImage(out))
        assertTrue("解出来应当是 JPEG", out[0] == 0xFF.toByte() && out[1] == 0xD8.toByte())
        // 密文 128 字节 = IV 16 + 密文 112（明文 96 + 一整块 PKCS7 填充 16）
        assertTrue("明文长度应为 96", out.size == 96)
    }

    private companion object {
        /** IV(0x5A × 16) + AES-256-CBC 加密的 96 字节「JPEG」(PKCS7 后 112 字节) */
        const val MULTI_BLOCK_B64 =
            "WlpaWlpaWlpaWlpaWlpaWhDPJUzH/Kf6yVjbeOJdV1e5WHQjSsT0JNgp" +
                "6dOXTdp3gz5Y5yqxibslJi9WdCmItTv1dD54tbAQq/sZ+Dcu7rJdxC3L" +
                "KXA7sZ7V9HIcbV0hH2mN6xhAcpfsnNVzVocfIqC7cPhJdboFjfi7SodF" +
                "cN4="
    }
}
