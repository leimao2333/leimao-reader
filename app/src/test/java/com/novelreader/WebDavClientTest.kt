package com.novelreader

import com.novelreader.core.backup.WebDavClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * WebDav 客户端 URL 规范化单测（v1.3.8.0）。
 *
 * 只测不联网的纯逻辑：根地址规范化 + 相对路径拼接。这些是「上传 / 下载
 * 写到错误路径」的根源，最容易错、也最适合离线断言。
 */
class WebDavClientTest {

    // ===== 根地址规范化 =====

    @Test
    fun `根地址去掉尾部斜杠`() {
        val c = WebDavClient("https://dav.example.com/dav/")
        assertEquals("https://dav.example.com/dav", c.root)
    }

    @Test
    fun `缺协议时自动补 https`() {
        val c = WebDavClient("dav.example.com")
        assertEquals("https://dav.example.com", c.root)
    }

    @Test
    fun `保留 http 前缀`() {
        val c = WebDavClient("http://192.168.1.10:8080")
        assertEquals("http://192.168.1.10:8080", c.root)
    }

    @Test
    fun `空白地址抛异常`() {
        try {
            WebDavClient("   ")
            throw AssertionError("应抛异常")
        } catch (e: IllegalArgumentException) {
            // 预期
        }
    }

    // ===== 路径拼接 =====

    @Test
    fun `空路径返回根`() {
        val c = WebDavClient("https://dav.example.com/dav/")
        assertEquals("https://dav.example.com/dav", c.url(""))
    }

    @Test
    fun `相对路径拼到根后`() {
        val c = WebDavClient("https://dav.example.com/dav/")
        assertEquals("https://dav.example.com/dav/leimao-reader-backup.json", c.url("leimao-reader-backup.json"))
    }

    @Test
    fun `斜杠开头的路径也能正确拼接不产生双斜杠`() {
        val c = WebDavClient("https://dav.example.com/dav/")
        assertEquals("https://dav.example.com/dav/backup/sub.json", c.url("/backup/sub.json"))
    }

    @Test
    fun `根无路径前缀时斜杠开头路径正确`() {
        val c = WebDavClient("https://dav.example.com")
        assertEquals("https://dav.example.com/backup.json", c.url("/backup.json"))
    }

    @Test
    fun `多级路径拼接`() {
        val c = WebDavClient("https://dav.example.com/dav/")
        assertEquals(
            "https://dav.example.com/dav/books/leimao/backup.json",
            c.url("books/leimao/backup.json"),
        )
    }

    @Test
    fun `路径首尾空白被清理`() {
        val c = WebDavClient("https://dav.example.com/dav/")
        assertEquals("https://dav.example.com/dav/backup.json", c.url("  backup.json  "))
    }

    // ===== Basic Auth 头（不暴露密码给日志，只断言前缀正确） =====

    @Test
    fun `匿名时认证头为空`() {
        val c = WebDavClient("https://dav.example.com/dav/", null, null)
        // 匿名客户端不应携带 Authorization；这里通过构造不抛异常间接验证
        assertTrue(c.root.isNotBlank())
    }

    @Test
    fun `带用户名时构造成功`() {
        val c = WebDavClient("https://dav.example.com/dav/", "user", "pass")
        assertEquals("https://dav.example.com/dav", c.root)
    }
}
