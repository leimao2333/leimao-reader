package com.novelreader

import com.novelreader.core.image.ImagePurpose
import com.novelreader.core.image.ThumbnailCacheKey
import com.novelreader.core.image.ThumbnailCancelHandle
import com.novelreader.core.image.ThumbnailCandidate
import com.novelreader.core.image.ThumbnailChannel
import com.novelreader.core.image.ThumbnailEntry
import com.novelreader.core.image.ThumbnailKind
import com.novelreader.core.image.ThumbnailMemoryCache
import com.novelreader.core.image.ThumbnailPolicies
import com.novelreader.core.image.ThumbnailSampling
import com.novelreader.core.image.ThumbnailTier
import com.novelreader.core.image.ThumbnailUrlResolver
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 缩略图通道（D15 + D17）单测。
 *
 * 覆盖三类逻辑：
 *
 * 1. **地址解析**（[ThumbnailUrlResolver]）：候选顺序、脏值拦截、相对地址补全；
 * 2. **缓存 key 与策略**（[ThumbnailCacheKey] / [ThumbnailPolicies]）：
 *    缩略图与正文图的 key 必须不同、按源分级给出不同超时与配额；
 * 3. **取消句柄**（[ThumbnailCancelHandle]）：重复取消幂等、取消后状态正确。
 */
class ThumbnailChannelTest {

    @After
    fun tearDown() {
        // 策略注册表是进程级状态，测试之间必须清干净，避免互相污染
        ThumbnailPolicies.clear()
    }

    // ===== A. 候选地址解析与排序 =====

    @Test
    fun `正常 thumbUrl 解析为唯一候选`() {
        val candidates = ThumbnailUrlResolver.resolve(
            ThumbnailEntry(
                thumbUrl = "https://img.example.com/thumb/1.jpg",
                coverUrl = "https://img.example.com/cover/1.jpg",
                baseUrl = "https://example.com",
            ),
        )
        assertEquals(2, candidates.size)
        assertEquals("https://img.example.com/thumb/1.jpg", candidates[0].url)
        assertEquals(ThumbnailKind.THUMBNAIL, candidates[0].kind)
    }

    @Test
    fun `thumbUrl 为空时退回 coverUrl`() {
        val candidates = ThumbnailUrlResolver.resolve(
            ThumbnailEntry(
                thumbUrl = null,
                coverUrl = "https://img.example.com/cover/1.jpg",
                baseUrl = "https://example.com",
            ),
        )
        assertEquals(1, candidates.size)
        assertEquals("https://img.example.com/cover/1.jpg", candidates[0].url)
        assertEquals(ThumbnailKind.COVER, candidates[0].kind)
    }

    @Test
    fun `多个候选按优先级排序 缩略图在前封面在后`() {
        val candidates = ThumbnailUrlResolver.resolve(
            ThumbnailEntry(
                thumbUrl = "https://img.example.com/thumb/1.jpg",
                coverUrl = "https://img.example.com/cover/1.jpg",
                baseUrl = "https://example.com",
            ),
        )
        assertEquals(ThumbnailKind.THUMBNAIL, candidates[0].kind)
        assertEquals(ThumbnailKind.COVER, candidates[1].kind)
        // 优先级数值本身也应是递增的（越小越优先）
        assertTrue(candidates[0].kind.priority < candidates[1].kind.priority)
    }

    @Test
    fun `缩略图与封面解析成同一地址时只保留一条`() {
        val same = "https://img.example.com/same.jpg"
        val candidates = ThumbnailUrlResolver.resolve(
            ThumbnailEntry(
                thumbUrl = same,
                coverUrl = same,
                baseUrl = "https://example.com",
            ),
        )
        assertEquals(1, candidates.size)
        // 保留优先级更高的那条（缩略图）
        assertEquals(ThumbnailKind.THUMBNAIL, candidates[0].kind)
    }

    // ===== B. 脏值拦截 =====

    @Test
    fun `undefined 等脏值字面量被拦截`() {
        val candidates = ThumbnailUrlResolver.resolve(
            ThumbnailEntry(
                thumbUrl = "undefined",
                coverUrl = "null",
                baseUrl = "https://example.com",
            ),
        )
        assertTrue("脏值不应产生任何候选", candidates.isEmpty())
    }

    @Test
    fun `空串与空白串被拦截`() {
        assertTrue(ThumbnailUrlResolver.resolve(ThumbnailEntry(thumbUrl = "")).isEmpty())
        assertTrue(ThumbnailUrlResolver.resolve(ThumbnailEntry(thumbUrl = "   ")).isEmpty())
        assertTrue(ThumbnailUrlResolver.resolve(ThumbnailEntry(thumbUrl = null)).isEmpty())
    }

    @Test
    fun `JS undefined 痕迹地址被拦截`() {
        val candidates = ThumbnailUrlResolver.resolve(
            ThumbnailEntry(
                thumbUrl = "https://site.example.org.mozilla.javascript.Undefined@dc3369d",
                baseUrl = "https://example.com",
            ),
        )
        assertTrue(candidates.isEmpty())
    }

    @Test
    fun `脏值不会被拼成永远 404 的假地址`() {
        // 回归点：脏值若被拼到基址下会得到 https://site/undefined 这种非空但必 404 的串
        val resolved = ThumbnailUrlResolver.resolveOne("undefined", "https://example.com")
        assertNull(resolved)
    }

    // ===== C. 相对地址补全 / 绝对地址原样 =====

    @Test
    fun `相对地址按站点基址补全`() {
        val candidates = ThumbnailUrlResolver.resolve(
            ThumbnailEntry(
                thumbUrl = "/thumb/1.jpg",
                baseUrl = "https://example.com",
            ),
        )
        assertEquals(1, candidates.size)
        assertEquals("https://example.com/thumb/1.jpg", candidates[0].url)
    }

    @Test
    fun `绝对地址原样返回`() {
        val absolute = "https://img.example.com/thumb/1.jpg"
        assertEquals(absolute, ThumbnailUrlResolver.resolveOne(absolute, "https://example.com"))
        assertEquals(absolute, ThumbnailUrlResolver.resolveOne(absolute, null))
    }

    @Test
    fun `协议相对地址借用基址协议`() {
        assertEquals(
            "https://img.example.com/thumb/1.jpg",
            ThumbnailUrlResolver.resolveOne("//img.example.com/thumb/1.jpg", "https://example.com"),
        )
    }

    @Test
    fun `没有基址时相对地址无法补全直接丢弃`() {
        assertNull(ThumbnailUrlResolver.resolveOne("/thumb/1.jpg", null))
        assertNull(ThumbnailUrlResolver.resolveOne("thumb/1.jpg", ""))
    }

    // ===== D. 缓存 key：用途隔离 =====

    @Test
    fun `缩略图与正文图的缓存 key 必须不同`() {
        // 核心用例：key 相同会让缩略图与正文大图互相污染（拿大图当缩略图用）
        val thumb = ThumbnailCacheKey.thumbnailKey("src-1", "https://img.example.com/1.jpg")
        val content = ThumbnailCacheKey.contentKey("src-1", "https://img.example.com/1.jpg")
        assertNotEquals(thumb, content)
        assertTrue(thumb.contains(ImagePurpose.THUMBNAIL.token))
        assertTrue(content.contains(ImagePurpose.CONTENT.token))
    }

    @Test
    fun `不同源的同一地址缓存 key 不同`() {
        val a = ThumbnailCacheKey.thumbnailKey("src-a", "https://img.example.com/1.jpg")
        val b = ThumbnailCacheKey.thumbnailKey("src-b", "https://img.example.com/1.jpg")
        assertNotEquals("按源隔离，避免张冠李戴", a, b)
    }

    @Test
    fun `相同输入得到稳定 key 与稳定文件名`() {
        val key = ThumbnailCacheKey.thumbnailKey("src-1", "https://img.example.com/1.jpg")
        assertEquals(key, ThumbnailCacheKey.thumbnailKey("src-1", "https://img.example.com/1.jpg"))
        val name = ThumbnailCacheKey.fileName(key)
        assertEquals(name, ThumbnailCacheKey.fileName(key))
        // 文件名必须是安全的（不含路径分隔符与 URL 特殊字符）
        assertFalse(name.contains('/'))
        assertFalse(name.contains('?'))
        assertTrue(name.startsWith(ThumbnailCacheKey.FILE_PREFIX))
    }

    // ===== E. 策略：按 sourceId 分级 =====

    @Test
    fun `不同档位给出不同的超时与容量策略`() {
        val light = ThumbnailPolicies.forTier(ThumbnailTier.LIGHT)
        val standard = ThumbnailPolicies.forTier(ThumbnailTier.STANDARD)
        val strict = ThumbnailPolicies.forTier(ThumbnailTier.STRICT)

        // 超时：轻档更短，重档更长
        assertTrue(light.connectTimeoutMs < standard.connectTimeoutMs)
        assertTrue(standard.connectTimeoutMs < strict.connectTimeoutMs)
        assertTrue(light.readTimeoutMs < standard.readTimeoutMs)
        assertTrue(standard.readTimeoutMs < strict.readTimeoutMs)

        // 容量：轻档配额最大，严格档最小
        assertTrue(light.diskCacheMaxEntries > standard.diskCacheMaxEntries)
        assertTrue(standard.diskCacheMaxEntries > strict.diskCacheMaxEntries)
        assertTrue(light.diskCacheMaxBytes > strict.diskCacheMaxBytes)
    }

    @Test
    fun `缩略图超时严格短于正文图通道`() {
        // 缩略图必须快速失败并降级，不能被正文的 10s/30s 拖住
        for (tier in ThumbnailTier.entries) {
            val p = ThumbnailPolicies.forTier(tier)
            assertTrue(
                "$tier 的连接超时应短于正文图",
                p.connectTimeoutMs < ThumbnailPolicies.CONTENT_CONNECT_TIMEOUT_MS,
            )
            assertTrue(
                "$tier 的读超时应短于正文图",
                p.readTimeoutMs < ThumbnailPolicies.CONTENT_READ_TIMEOUT_MS,
            )
        }
    }

    @Test
    fun `按源注册策略生效且互不影响`() {
        ThumbnailPolicies.registerTier("src-strict", ThumbnailTier.STRICT)
        ThumbnailPolicies.registerTier("src-light", ThumbnailTier.LIGHT)

        val strict = ThumbnailPolicies.policyFor("src-strict")
        val light = ThumbnailPolicies.policyFor("src-light")
        assertEquals(ThumbnailTier.STRICT, strict.tier)
        assertEquals(ThumbnailTier.LIGHT, light.tier)
        assertNotEquals(strict.diskCacheMaxEntries, light.diskCacheMaxEntries)
        assertNotEquals(strict.connectTimeoutMs, light.connectTimeoutMs)

        // 严格档禁止落盘
        assertFalse(strict.allowDiskCache)
        assertTrue(light.allowDiskCache)

        // 未注册的源回到默认档
        assertEquals(ThumbnailPolicies.DEFAULT_TIER, ThumbnailPolicies.tierOf("src-unknown"))
    }

    @Test
    fun `注销源策略后回到默认档`() {
        ThumbnailPolicies.registerTier("src-x", ThumbnailTier.STRICT)
        assertEquals(ThumbnailTier.STRICT, ThumbnailPolicies.tierOf("src-x"))
        ThumbnailPolicies.unregister("src-x")
        assertEquals(ThumbnailPolicies.DEFAULT_TIER, ThumbnailPolicies.tierOf("src-x"))
    }

    @Test
    fun `自定义策略可以单独关闭某个源的磁盘缓存`() {
        val custom = ThumbnailPolicies.forTier(ThumbnailTier.LIGHT).copy(
            allowDiskCache = false,
            diskCacheMaxEntries = 0,
        )
        ThumbnailPolicies.register("src-nodisk", custom)
        val policy = ThumbnailPolicies.policyFor("src-nodisk")
        assertFalse(policy.allowDiskCache)
        assertEquals(0, policy.diskCacheMaxEntries)
    }

    // ===== F. 取消句柄 =====

    @Test
    fun `重复取消不抛异常且状态正确`() {
        val handle = ThumbnailCancelHandle()
        assertFalse(handle.isCancelled)
        handle.cancel()
        handle.cancel()
        handle.cancel()
        assertTrue(handle.isCancelled)
    }

    @Test
    fun `取消动作只执行一次`() {
        val handle = ThumbnailCancelHandle()
        var count = 0
        handle.onCancel { count++ }
        handle.cancel()
        handle.cancel()
        assertEquals("重复取消不应重复执行动作", 1, count)
    }

    @Test
    fun `取消之后再注册动作立即执行`() {
        // 竞态保护：加载刚起步就被取消时，注册的动作不能被丢掉
        val handle = ThumbnailCancelHandle()
        handle.cancel()
        var fired = false
        handle.onCancel { fired = true }
        assertTrue(fired)
    }

    @Test
    fun `多个取消动作都会被执行`() {
        val handle = ThumbnailCancelHandle()
        var a = 0
        var b = 0
        handle.onCancel { a++ }
        handle.onCancel { b++ }
        handle.cancel()
        assertEquals(1, a)
        assertEquals(1, b)
    }

    // ===== G. 边界 =====

    @Test
    fun `空列表与无候选返回空结果`() {
        assertTrue(ThumbnailUrlResolver.resolve(ThumbnailEntry()).isEmpty())
        assertTrue(ThumbnailUrlResolver.resolve(baseUrl = "https://example.com").isEmpty())
        assertTrue(ThumbnailUrlResolver.resolve(ThumbnailEntry(thumbUrl = "undefined")).isEmpty())
    }

    @Test
    fun `极端长度地址被丢弃`() {
        val tooLong = "https://img.example.com/" + "a".repeat(ThumbnailUrlResolver.MAX_URL_LENGTH)
        assertTrue(tooLong.length > ThumbnailUrlResolver.MAX_URL_LENGTH)
        assertNull(ThumbnailUrlResolver.resolveOne(tooLong, "https://example.com"))
    }

    @Test
    fun `采样倍数按目标边长收敛且非法输入返回 1`() {
        assertEquals(1, ThumbnailSampling.sampleSize(100, 100, 320))
        assertEquals(2, ThumbnailSampling.sampleSize(800, 800, 320))
        assertEquals(4, ThumbnailSampling.sampleSize(1600, 1600, 320))
        assertEquals(1, ThumbnailSampling.sampleSize(0, 100, 320))
        assertEquals(1, ThumbnailSampling.sampleSize(100, 100, 0))
    }

    @Test
    fun `内存缓存超出配额时淘汰最旧条目`() {
        val cache = ThumbnailMemoryCache(maxBytes = 100)
        cache.put("a", ByteArray(60))
        cache.put("b", ByteArray(60))
        // 总共 120 > 100，最旧的 a 应被淘汰
        assertNull(cache.get("a"))
        assertEquals(60, cache.get("b")?.size)
        assertEquals(60L, cache.sizeBytes)
    }

    @Test
    fun `内存缓存命中同一 key 时不重复占用配额`() {
        val cache = ThumbnailMemoryCache(maxBytes = 1000)
        cache.put("a", ByteArray(100))
        cache.put("a", ByteArray(100))
        assertEquals(100L, cache.sizeBytes)
    }

    @Test
    fun `磁盘缓存目录与正文图目录相互独立`() {
        // 缩略图落在 cacheDir/thumbnails，正文图在 cacheDir/image_cache
        assertEquals("thumbnails", ThumbnailChannel.DISK_DIR_NAME)
        assertNotEquals("image_cache", ThumbnailChannel.DISK_DIR_NAME)
    }

    @Test
    fun `候选对象保留地址与来源类型`() {
        val candidate = ThumbnailCandidate("https://img.example.com/1.jpg", ThumbnailKind.THUMBNAIL)
        assertEquals("https://img.example.com/1.jpg", candidate.url)
        assertEquals(ThumbnailKind.THUMBNAIL, candidate.kind)
        // 策略数据类是可比较的值对象
        assertEquals(
            ThumbnailPolicies.forTier(ThumbnailTier.LIGHT),
            ThumbnailPolicies.forTier(ThumbnailTier.LIGHT),
        )
        assertNotEquals(
            ThumbnailPolicies.forTier(ThumbnailTier.LIGHT),
            ThumbnailPolicies.forTier(ThumbnailTier.STRICT),
        )
    }
}
