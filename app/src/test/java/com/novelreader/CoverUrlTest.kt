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
}
