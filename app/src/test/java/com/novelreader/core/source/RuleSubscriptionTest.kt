package com.novelreader.core.source

import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 规则订阅回归测试（B7）。
 *
 * 重点验证三件事：
 * - **合并语义**：新增 / 覆盖 / 删除 / 不动；
 * - **安全底线**：一份坏订阅必须被整体拒绝，绝不能把用户已有的源改坏；
 * - **幂等与版本**：同样的订阅合并两次结果不变，版本号决定是否跳过。
 */
class RuleSubscriptionTest {

    private val local = """
        {"bookSourceName":"测试源","bookSourceUrl":"https://example.com",
         "ruleToc":{"chapterList":"<a>旧</a>","chapterName":"<a>名称</a>"},
         "ruleContent":{"content":"<p>旧正文</p>"}}
    """.trimIndent()

    private fun rule(merged: String, group: String, name: String): String? {
        val obj = JsonParser.parseString(merged).asJsonObject
        val g = obj.getAsJsonObject(group) ?: return null
        val r = g.get(name) ?: return null
        return r.asString
    }

    // ------------------------------------------------------------------
    // 合并语义
    // ------------------------------------------------------------------

    @Test
    fun `合并新增规则 新键追加到本地源`() {
        val remote = """{"ruleToc":{"chapterList":"<a>旧</a>","nextPage":"<a>下一页</a>"}}"""
        val result = RuleMerger.merge(local, remote)
        assertTrue(result.applied)
        assertEquals("<a>下一页</a>", rule(result.mergedJson, "ruleToc", "nextPage"))
        assertTrue(result.diff.added.contains("ruleToc/nextPage"))
        assertEquals(1, result.diff.addedCount)
    }

    @Test
    fun `合并覆盖规则 同名键以远程为准`() {
        val remote = """{"ruleToc":{"chapterList":"<a>新</a>"}}"""
        val result = RuleMerger.merge(local, remote)
        assertTrue(result.applied)
        assertEquals("<a>新</a>", rule(result.mergedJson, "ruleToc", "chapterList"))
        assertTrue(result.diff.overwritten.contains("ruleToc/chapterList"))
        assertEquals(1, result.diff.overwrittenCount)
    }

    @Test
    fun `远程值为 null 表示删除该条规则`() {
        val remote = """{"ruleToc":{"chapterList":null}}"""
        val result = RuleMerger.merge(local, remote)
        assertTrue(result.applied)
        assertNull(rule(result.mergedJson, "ruleToc", "chapterList"))
        assertTrue(result.diff.deleted.contains("ruleToc/chapterList"))
        // 同组的其他规则不受影响
        assertEquals("<a>名称</a>", rule(result.mergedJson, "ruleToc", "chapterName"))
    }

    @Test
    fun `删除标记 __DELETE__ 同样生效`() {
        val remote = """{"ruleContent":{"content":"${RuleMerger.DELETE_MARKER}"}}"""
        val result = RuleMerger.merge(local, remote)
        assertTrue(result.applied)
        assertNull(rule(result.mergedJson, "ruleContent", "content"))
    }

    @Test
    fun `远程把整个规则组置空表示整组删除`() {
        val remote = """{"ruleContent":null}"""
        val result = RuleMerger.merge(local, remote)
        assertTrue(result.applied)
        val obj = JsonParser.parseString(result.mergedJson).asJsonObject
        assertNull(obj.get("ruleContent"))
        assertTrue(result.diff.deleted.contains("ruleContent/content"))
    }

    @Test
    fun `内容一致的规则不计入差异`() {
        val remote = """{"ruleToc":{"chapterName":"<a>名称</a>"}}"""
        val result = RuleMerger.merge(local, remote)
        assertEquals(1, result.diff.unchanged)
        assertFalse(result.diff.hasChanges)
        assertFalse(result.applied)
    }

    @Test
    fun `只含规则片段的订阅也能合并`() {
        val remote = """{"ruleToc":{"chapterList":"<a>片段</a>"}}"""
        val result = RuleMerger.merge(local, remote)
        assertTrue(result.applied)
        assertEquals("<a>片段</a>", rule(result.mergedJson, "ruleToc", "chapterList"))
    }

    @Test
    fun `扁平键写法 ruleToc_chapterList 也能识别`() {
        val remote = """{"ruleToc.chapterList":"<a>扁平</a>"}"""
        val result = RuleMerger.merge(local, remote)
        assertTrue(result.applied)
        assertEquals("<a>扁平</a>", rule(result.mergedJson, "ruleToc", "chapterList"))
    }

    // ------------------------------------------------------------------
    // 校验（坏订阅必须整体拒绝）
    // ------------------------------------------------------------------

    @Test
    fun `非法 JSON 被拒绝 本地源原样不动`() {
        val result = RuleMerger.merge(local, "这不是 JSON{{{")
        assertFalse(result.applied)
        assertEquals(local, result.mergedJson)
        assertTrue(result.reason.contains("校验失败"))
    }

    @Test
    fun `缺少 bookSourceName 的完整书源被拒绝`() {
        val remote = """{"bookSourceUrl":"https://example.com","ruleToc":{"chapterList":"<a>x</a>"}}"""
        val result = RuleMerger.merge(local, remote)
        assertFalse(result.applied)
        assertEquals(local, result.mergedJson)
        assertTrue(result.reason.contains("bookSourceName"))
    }

    @Test
    fun `缺少 bookSourceUrl 的完整书源被拒绝`() {
        val remote = """{"bookSourceName":"远端源","ruleToc":{"chapterList":"<a>x</a>"}}"""
        val result = RuleMerger.merge(local, remote)
        assertFalse(result.applied)
        assertEquals(local, result.mergedJson)
        assertTrue(result.reason.contains("bookSourceUrl"))
    }

    @Test
    fun `空订阅源被拒绝`() {
        val result = RuleMerger.merge(local, "{}")
        assertFalse(result.applied)
        assertEquals(local, result.mergedJson)
        assertTrue(result.reason.contains("规则"))
    }

    @Test
    fun `规则组为空的订阅被拒绝`() {
        val result = RuleMerger.merge(local, """{"ruleToc":{}}""")
        assertFalse(result.applied)
        assertEquals(local, result.mergedJson)
    }

    @Test
    fun `空白内容被拒绝`() {
        val result = RuleMerger.merge(local, "   \n  ")
        assertFalse(result.applied)
        assertEquals(local, result.mergedJson)
    }

    // ------------------------------------------------------------------
    // 差异预览
    // ------------------------------------------------------------------

    @Test
    fun `差异预览分别统计新增 覆盖 删除`() {
        val remote = """
            {"ruleToc":{"chapterList":"<a>新</a>","nextPage":"<a>下一页</a>"},
             "ruleContent":{"content":null},
             "ruleBookInfo":{"name":"<h1>书名</h1>"}}
        """.trimIndent()
        val diff = RuleMerger.diff(local, remote)
        assertEquals(2, diff.addedCount)          // ruleToc/nextPage、ruleBookInfo/name
        assertEquals(1, diff.overwrittenCount)    // ruleToc/chapterList
        assertEquals(1, diff.deletedCount)        // ruleContent/content
        assertTrue(diff.added.contains("ruleToc/nextPage"))
        assertTrue(diff.overwritten.contains("ruleToc/chapterList"))
        assertTrue(diff.deleted.contains("ruleContent/content"))
    }

    @Test
    fun `内容不合法的订阅差异为空`() {
        assertEquals(0, RuleMerger.diff(local, "not a json").addedCount)
        assertEquals(0, RuleMerger.diff(local, "not a json").deletedCount)
    }

    // ------------------------------------------------------------------
    // 幂等与版本
    // ------------------------------------------------------------------

    @Test
    fun `合并两次结果相同 幂等`() {
        val remote = """{"ruleToc":{"chapterList":"<a>新</a>","nextPage":"<a>下一页</a>"}}"""
        val first = RuleMerger.merge(local, remote)
        assertTrue(first.applied)
        val second = RuleMerger.merge(first.mergedJson, remote)
        assertEquals(first.mergedJson, second.mergedJson)
        assertFalse(second.applied)
        assertFalse(second.diff.hasChanges)
    }

    @Test
    fun `版本相同则跳过 不做无谓改动`() {
        val versioned = """{"bookSourceName":"测试源","bookSourceUrl":"https://example.com","bookSourceVersion":"2.0","ruleToc":{"chapterList":"<a>旧</a>"}}"""
        val remote = """{"bookSourceVersion":"2.0","ruleToc":{"chapterList":"<a>新</a>"}}"""
        val result = RuleMerger.merge(versioned, remote)
        assertFalse(result.applied)
        assertEquals(versioned, result.mergedJson)
        assertTrue(result.reason.contains("版本未变化"))
    }

    @Test
    fun `远端版本较旧则跳过 不覆盖本地`() {
        val versioned = """{"bookSourceName":"测试源","bookSourceUrl":"https://example.com","bookSourceVersion":"3.0","ruleToc":{"chapterList":"<a>旧</a>"}}"""
        val remote = """{"bookSourceVersion":"2.0","ruleToc":{"chapterList":"<a>新</a>"}}"""
        val result = RuleMerger.merge(versioned, remote)
        assertFalse(result.applied)
        assertEquals(versioned, result.mergedJson)
        assertTrue(result.reason.contains("较旧"))
    }

    @Test
    fun `远端版本更新则应用并写入版本号`() {
        val versioned = """{"bookSourceName":"测试源","bookSourceUrl":"https://example.com","bookSourceVersion":"1.0","ruleToc":{"chapterList":"<a>旧</a>"}}"""
        val remote = """{"bookSourceVersion":"2.0","ruleToc":{"chapterList":"<a>新</a>"}}"""
        val result = RuleMerger.merge(versioned, remote)
        assertTrue(result.applied)
        assertEquals("<a>新</a>", rule(result.mergedJson, "ruleToc", "chapterList"))
        assertEquals("2.0", result.version)
        val obj = JsonParser.parseString(result.mergedJson).asJsonObject
        assertEquals("2.0", obj.get("bookSourceVersion").asString)
    }

    @Test
    fun `订阅项记录的版本优先于源里的版本`() {
        val versioned = """{"bookSourceName":"测试源","bookSourceUrl":"https://example.com","bookSourceVersion":"1.0","ruleToc":{"chapterList":"<a>旧</a>"}}"""
        val remote = """{"bookSourceVersion":"2.0","ruleToc":{"chapterList":"<a>新</a>"}}"""
        val subscription = RuleSubscription(
            sourceId = "src-1",
            url = "https://example.com/rules.json",
            version = "2.0",
        )
        val result = RuleMerger.merge(versioned, remote, subscription)
        assertFalse(result.applied)
        assertTrue(result.reason.contains("版本未变化"))
    }

    // ------------------------------------------------------------------
    // 订阅模型
    // ------------------------------------------------------------------

    @Test
    fun `更新间隔到期判断`() {
        val now = 1_000_000_000L
        val never = RuleSubscription("s1", "https://example.com/r.json")
        assertTrue(never.isDue(now))

        val due = RuleSubscription("s1", "https://example.com/r.json", 24, lastUpdatedAt = now - 25 * 3600_000L)
        assertTrue(due.isDue(now))

        val notDue = RuleSubscription("s1", "https://example.com/r.json", 24, lastUpdatedAt = now - 3600_000L)
        assertFalse(notDue.isDue(now))
    }

    @Test
    fun `订阅地址只允许 http 与 https`() {
        assertTrue(RemoteRuleSource.isAllowedUrl("https://example.com/rules.json"))
        assertTrue(RemoteRuleSource.isAllowedUrl("http://example.com/rules.json"))
        assertFalse(RemoteRuleSource.isAllowedUrl("file:///etc/passwd"))
        assertFalse(RemoteRuleSource.isAllowedUrl("ftp://example.com/rules.json"))
        assertFalse(RemoteRuleSource.isAllowedUrl(""))
        assertFalse(RemoteRuleSource.isAllowedUrl("   "))
    }
}
