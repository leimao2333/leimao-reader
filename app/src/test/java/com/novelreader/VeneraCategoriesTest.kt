package com.novelreader

import com.novelreader.source.venera.VeneraCategories
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Venera 源分类解析测试（v1.6.6.0）。
 *
 * 素材照抄真实源的结构：新式 `parts[].categories = ["全部","恋爱",...]`、
 * 老式 `categories = [{ label, target }]`、一个源多组分类、以及夹杂注释。
 */
class VeneraCategoriesTest {

    // ===== 新式：parts 里的字符串数组 =====

    @Test
    fun `解析新式 parts 分类`() {
        val code = """
            class Baozi extends ComicSource {
              name = "包子漫画";
              key = "baozi";

              category = {
                title: "包子漫画",
                parts: [
                  {
                    name: "题材",
                    type: "fixed",
                    categories: ["全部", "恋爱", "古风", "异能", "悬疑"],
                  },
                ],
              }

              search(keyword, page) { /* ... */ }
            }
        """.trimIndent()
        assertEquals(
            listOf("全部", "恋爱", "古风", "异能", "悬疑"),
            VeneraCategories.parse(code),
        )
    }

    @Test
    fun `多组 parts 全部合并并按出现顺序去重`() {
        val code = """
            category = {
              parts: [
                { name: "题材", categories: ["热血", "冒险", "热血"] },
                { name: "地区", categories: ["日漫", "国漫", "冒险"] },
              ],
            }
        """.trimIndent()
        assertEquals(listOf("热血", "冒险", "日漫", "国漫"), VeneraCategories.parse(code))
    }

    // ===== 老式：对象数组取 label =====

    @Test
    fun `老式对象数组只取 label 不取键名`() {
        val code = """
            category = {
              parts: [
                {
                  name: "热门TAG",
                  categories: [
                    { label: "全部", target: { page: "category", attributes: { category: "全部" } } },
                    { label: "全彩", target: { page: "category", attributes: { category: "全彩", param: "全彩" } } },
                    { label: "無修正", target: { page: "category", attributes: { category: "無修正" } } },
                  ],
                },
              ],
            }
        """.trimIndent()
        val got = VeneraCategories.parse(code)
        assertEquals(listOf("全部", "全彩", "無修正"), got)
        // 「category」「page」「attributes」「param」「target」「label」这些键名不能被当成分类
        assertTrue(got.none { it in setOf("category", "page", "attributes", "param", "target", "label") })
    }

    // ===== 边界 =====

    @Test
    fun `没有 categories 声明时返回空`() {
        val code = """class X extends ComicSource { name = "X"; search(k,p){} }"""
        assertTrue(VeneraCategories.parse(code).isEmpty())
    }

    @Test
    fun `单引号与转义字符能正确解析`() {
        val code = """
            categories: ['全彩', "带\"引号\"的名字", 'It\'s fine']
        """.trimIndent()
        val got = VeneraCategories.parse(code)
        assertEquals(3, got.size)
        assertEquals("全彩", got[0])
        assertTrue(got[1].contains("引号"))
        assertTrue(got[2].contains("It"))
    }

    @Test
    fun `嵌套数组不会打乱配对`() {
        val code = """
            categories: ["甲", ["嵌套" , "不算"], "乙"]
        """.trimIndent()
        // 嵌套数组里的元素在 {} 深度 0 —— 内层也是数组，这里按同样是顶层字符串处理
        val got = VeneraCategories.parse(code)
        assertTrue("甲" in got)
        assertTrue("乙" in got)
    }

    @Test
    fun `括号不平衡时不崩溃且能取到已解析部分`() {
        val code = """categories: ["甲", "乙""""
        val got = VeneraCategories.parse(code)
        assertTrue("甲" in got)
    }

    @Test
    fun `空数组返回空`() {
        assertTrue(VeneraCategories.parse("""categories: []""").isEmpty())
    }

    @Test
    fun `空白与换行被裁掉`() {
        val code = """
            categories: [
              "  热血  ",
              "\t校园\n",
            ]
        """.trimIndent()
        assertEquals(listOf("热血", "校园"), VeneraCategories.parse(code))
    }

    @Test
    fun `真实 kavita 风格 - 只有 category 字段名没有分类声明`() {
        val code = """
            class Kavita extends ComicSource {
              name = "Kavita";
              settings = {
                url: { title: "服务器地址", type: "input" },
              }
              search(keyword, page) { return [] }
            }
        """.trimIndent()
        assertTrue(VeneraCategories.parse(code).isEmpty())
    }

    @Test
    fun `settings 里出现 categories 之外的数组不受影响`() {
        val code = """
            searchUrl = "/search";
            options: ["a", "b"];
            categories: ["只看这个"];
        """.trimIndent()
        assertEquals(listOf("只看这个"), VeneraCategories.parse(code))
    }
}
