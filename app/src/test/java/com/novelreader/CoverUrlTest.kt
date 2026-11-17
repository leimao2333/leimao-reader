package com.novelreader

import com.novelreader.core.util.CoverUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 封面地址清洗单测（v1.8.3.0）。
 *
 * 回归目标：JS / legado 源取不到封面时，引擎会把 `undefined` 当对象拼进 baseUrl，
 * 得到 `https://ubook.reader.qq.com/org.mozilla.javascript.Undefined@dc3369d`
 * 这类「非空但永远 404」的地址 —— 首页补封面逻辑用 isNullOrBlank 判断，
 * 于是整排企鹅阅读的书永远补不上封面。脏值必须在入队前清零。
 */
class CoverUrlTest {

    @Test
    fun `rhino undefined 拼接出的假地址被清空`() {
        assertNull(
            CoverUrl.sanitize("https://ubook.reader.qq.com/org.mozilla.javascript.Undefined@dc3369d"),
        )
    }

    @Test
    fun `纯空值字面量被清空`() {
        assertNull(CoverUrl.sanitize("undefined"))
        assertNull(CoverUrl.sanitize("NULL"))
        assertNull(CoverUrl.sanitize("[object Object]"))
        assertNull(CoverUrl.sanitize("NaN"))
        assertNull(CoverUrl.sanitize("   "))
        assertNull(CoverUrl.sanitize(null))
    }

    @Test
    fun `相对路径与空协议被清空`() {
        assertNull(CoverUrl.sanitize("/files/article/image/1.jpg"))
        assertNull(CoverUrl.sanitize("http://"))
        assertNull(CoverUrl.sanitize("https://"))
    }

    @Test
    fun `正常地址原样保留并去掉首尾空白`() {
        assertEquals(
            "http://www.shukuge.com/headimgs/92/92827/s92827.jpg",
            CoverUrl.sanitize(" http://www.shukuge.com/headimgs/92/92827/s92827.jpg "),
        )
        assertTrue(CoverUrl.isValid("https://img.example.com/a.jpg"))
        assertFalse(CoverUrl.isValid("undefined"))
    }

    @Test
    fun `file 与 content 协议仍可用（本地书）`() {
        assertTrue(CoverUrl.isValid("file:///sdcard/cover.jpg"))
        assertTrue(CoverUrl.isValid("content://media/external/images/1"))
    }

    // ===== v1.9.6.1：Venera 相对封面补全 =====

    @Test
    fun `绝对地址在 resolve 下行为与 sanitize 一致`() {
        // 漫蛙吧这类 IP+非标端口的绝对地址必须原样放行
        val ipCover = "https://52.128.238.250:5367/en_images/202605/cover/94789.jpg"
        assertEquals(ipCover, CoverUrl.resolve(ipCover, "https://mwuu.cc"))
        assertEquals(ipCover, CoverUrl.resolve("  $ipCover  ", null))
    }

    @Test
    fun `根相对封面用站点基址补全`() {
        assertEquals(
            "https://mwuu.cc/cover/1.jpg",
            CoverUrl.resolve("/cover/1.jpg", "https://mwuu.cc"),
        )
        // base 自带路径时只取协议+主机，不拼接 base 的路径
        assertEquals(
            "https://mwuu.cc/cover/1.jpg",
            CoverUrl.resolve("/cover/1.jpg", "https://mwuu.cc/api/v2/"),
        )
    }

    @Test
    fun `协议相对封面借用站点协议`() {
        assertEquals(
            "https://img.example.com/c.jpg",
            CoverUrl.resolve("//img.example.com/c.jpg", "https://mwuu.cc"),
        )
    }

    @Test
    fun `普通相对路径拼到基址目录后`() {
        assertEquals(
            "https://mwuu.cc/api/cover/1.jpg",
            CoverUrl.resolve("cover/1.jpg", "https://mwuu.cc/api"),
        )
    }

    @Test
    fun `补齐失败或脏值仍返回 null`() {
        assertNull(CoverUrl.resolve("/cover/1.jpg", null))
        assertNull(CoverUrl.resolve("undefined", "https://mwuu.cc"))
        assertNull(CoverUrl.resolve(null, "https://mwuu.cc"))
    }
}
