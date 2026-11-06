package com.novelreader

import com.novelreader.core.util.SourceUrlNormalizer
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 源基础地址规范化（v1.9.3.0）。
 *
 * 样例取自真机上同一类聚合源包里反复出现的手工标记写法，域名做了泛化。
 */
class SourceUrlNormalizerTest {

    @Test
    fun stripsImageMarkerPrefix() {
        assertEquals(
            "http://www.example.com",
            SourceUrlNormalizer.normalize("[图片]http://www.example.com"),
        )
    }

    @Test
    fun stripsChineseCommentSuffix() {
        assertEquals(
            "http://www.example.com",
            SourceUrlNormalizer.normalize("http://www.example.com已校验"),
        )
        assertEquals(
            "http://book.example.com",
            SourceUrlNormalizer.normalize("http://book.example.com已校验"),
        )
    }

    @Test
    fun addsSchemeAndStripsCommentForBareHost() {
        assertEquals(
            "https://book.example.com",
            SourceUrlNormalizer.normalize("book.example.com已整理"),
        )
    }

    @Test
    fun stripsHashRemarksWithOrWithoutEmoji() {
        assertEquals(
            "https://book.example.com",
            SourceUrlNormalizer.normalize("https://book.example.com##发现"),
        )
        assertEquals(
            "http://s.example.com",
            SourceUrlNormalizer.normalize("http://s.example.com#🎃"),
        )
    }

    @Test
    fun keepsNormalUrlsAndPreservesPathAndQuery() {
        assertEquals(
            "https://api.example.com/v2",
            SourceUrlNormalizer.normalize("https://api.example.com/v2/"),
        )
        assertEquals(
            "https://book.example.com/search?q=1&p=2",
            SourceUrlNormalizer.normalize("https://book.example.com/search?q=1&p=2"),
        )
    }

    @Test
    fun returnsEmptyForGarbageInsteadOfInventingAHost() {
        assertEquals("", SourceUrlNormalizer.normalize(""))
        assertEquals("", SourceUrlNormalizer.normalize("这是一个说明"))
        assertEquals("", SourceUrlNormalizer.normalize("https://"))
    }

    @Test
    fun rootDomainFoldsSubdomains() {
        assertEquals("example.com", SourceUrlNormalizer.rootDomain("http://book.example.com/Novel/1"))
        assertEquals("example.com", SourceUrlNormalizer.rootDomain("https://www.example.com"))
        assertEquals("example.com.cn", SourceUrlNormalizer.rootDomain("https://www.example.com.cn"))
        assertEquals("", SourceUrlNormalizer.rootDomain("已校验"))
    }
}
