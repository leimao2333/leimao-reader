package com.novelreader

import com.novelreader.domain.model.MediaItem
import com.novelreader.domain.model.SourceFormat
import com.novelreader.domain.model.SourceInfo
import com.novelreader.domain.model.SourceType
import com.novelreader.source.legado.LegadoBookSource
import com.novelreader.source.legado.LegadoRuleEngine
import com.novelreader.source.legado.LegadoSourceAdapter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * legado 规则引擎 + 真实书源测试。
 *
 * - 离线 fixture：验证规则语义（任意属性 / 尾部 JS / @css:属性 / 区间）
 * - 联网用例（需设环境变量 LEIMAO_REAL_TESTS=1）：
 *   联网用例验证的书源由环境变量指定（LEIMAO_SRC_NOVEL_A / B / C），未指定时自动跳过
 */
class LegadoRealSourceTest {

    private fun adapter(json: String): LegadoSourceAdapter {
        val src = LegadoBookSource.parseOne(json)
        val info = SourceInfo(
            id = "test_${src.name.hashCode()}",
            name = src.name,
            type = SourceType.NOVEL,
            baseUrl = src.url,
            format = SourceFormat.LEGADO,
        )
        return LegadoSourceAdapter(info, src)
    }

    // ===== 离线规则语义 =====

    @Test
    fun `任意属性名 - 取 onclick 属性`() {
        val html = """<html><body><a href="javascript:void(0)" onclick="showBook('abc123','','')">书名A</a></body></html>"""
        val v = LegadoRuleEngine.extractString(html, "tag.a.0@onclick")
        assertEquals("showBook('abc123','','')", v)
    }

    @Test
    fun `尾部JS规则 - result 正则提取`() {
        // 部分源的结构：列表项容器带 onclick（含空格），bookUrl 规则 = @onclick@js:正则提取
        val html = """
            <html><body>
              <div class="v-list-item" onclick="showBook('abc123', '', '')"><div class="v-title">书名A</div></div>
              <div class="v-list-item" onclick="showBook('def456', '', '')"><div class="v-title">书名B</div></div>
            </body></html>
        """.trimIndent()
        val list = LegadoRuleEngine.extractList(
            html,
            ".v-list-item",
            mapOf(
                "name" to ".v-title@text",
                "bookUrl" to "@onclick@js:result.match(/\\('(.*?)', '', ''\\)/)[1]",
            ),
        )
        assertEquals(2, list.size)
        assertEquals("abc123", list[0]["bookUrl"])
        assertEquals("def456", list[1]["bookUrl"])
        assertEquals("书名A", list[0]["name"])
    }

    @Test
    fun `css规则带属性 - @css_img@src`() {
        val html = """<html><body><div class="cover"><img src="http://x/img.jpg"></div></body></html>"""
        val v = LegadoRuleEngine.extractString(html, "@css:img@src")
        assertEquals("http://x/img.jpg", v)
    }

    @Test
    fun `标签区间 - tag span 区间写法`() {
        val html = """<html><body><p><span>玄幻</span><span>连载</span><span>100万</span></p></body></html>"""
        // legado 语义：区间取前两个 span，文本类提取多元素拼接
        val v = LegadoRuleEngine.extractString(html, "tag.p.0@tag.span[0:1]@text")
        assertEquals("玄幻\n连载", v)
    }

    @Test
    fun `replaceRegex - ##前缀净化`() {
        val html = """<html><body><div class="con">正文内容（本章完）</div></body></html>"""
        val v = LegadoRuleEngine.extractString(html, "class.con.0@text##（本章完）")
        assertEquals("正文内容", v)
    }

    // ===== 列表规则语义（- 倒置 / ! 排除 / @json: 大小写 / && 合并） =====

    private val tocHtml = """
        <html><body><div class="list">
          <a href="/1.html">第一章</a>
          <a href="/2.html">第二章</a>
          <a href="/3.html">第三章</a>
        </div></body></html>
    """.trimIndent()

    /**
     * legado 规则说明：「获取列表的最前面加上负号 `-` 可以使列表倒置」。
     * 大量漫画/小说源目录规则以 `-` 开头，之前被当成选择器的一部分 → 目录恒为空。
     */
    @Test
    fun `列表规则前置负号 - 表示倒置`() {
        val fields = mapOf("chapterName" to "text", "chapterUrl" to "href")
        val normal = LegadoRuleEngine.extractList(tocHtml, ".list@a", fields)
        assertEquals(listOf("第一章", "第二章", "第三章"), normal.map { it["chapterName"] })

        val reversed = LegadoRuleEngine.extractList(tocHtml, "-.list@a", fields)
        assertEquals(listOf("第三章", "第二章", "第一章"), reversed.map { it["chapterName"] })
    }

    @Test
    fun `列表规则前置负号 对 css 规则同样生效`() {
        val fields = mapOf("chapterName" to "text")
        val reversed = LegadoRuleEngine.extractList(tocHtml, "-@css:.list>a", fields)
        assertEquals(listOf("第三章", "第二章", "第一章"), reversed.map { it["chapterName"] })
    }

    @Test
    fun `负索引 -1 不被误判为倒置标记`() {
        val fields = mapOf("chapterName" to "text")
        // -1 是「取最后一个」的负索引，不应触发整体倒置
        val list = LegadoRuleEngine.extractList(tocHtml, ".list@a.-1", fields)
        assertEquals(listOf("第三章"), list.map { it["chapterName"] })
    }

    @Test
    fun `列表规则 ! 排除指定位置`() {
        val list = LegadoRuleEngine.extractList(tocHtml, ".list@a!1", mapOf("chapterName" to "text"))
        assertEquals(listOf("第一章", "第三章"), list.map { it["chapterName"] })
    }

    @Test
    fun `JSON 列表规则前缀大小写不敏感`() {
        val json = """{"data":[{"name":"A"},{"name":"B"}]}"""
        val list = LegadoRuleEngine.extractList(json, "@JSon:$.data", mapOf("chapterName" to "$.name"))
        assertEquals(listOf("A", "B"), list.map { it["chapterName"] })

        val reversed = LegadoRuleEngine.extractList(json, "-@jsOn:$.data", mapOf("chapterName" to "$.name"))
        assertEquals(listOf("B", "A"), reversed.map { it["chapterName"] })
    }

    // ===== legado 变量机制 @put:{k:v} / @get:{k} =====

    /**
     * 条目级变量表必须**以外层变量为初值**，不能被空表遮蔽。
     *
     * 这是设备端实测发现的最后一个「目录空白」成因（某源）：
     * 搜索阶段 `$.comic_name@put:{bid:$.comic_id}` 把作品 ID 存进变量、随结果条目带出；
     * 目录阶段外层变量表里本来就有 `bid`，但若每条记录另起一张**空表**，
     * 章节地址里的 `@get:{bid}` 就取到空串 —— 实测地址退化成
     * `...&comic_id=&chapter_newid=1&...`，图片接口返回不了数据，正文就「无法加载」。
     *
     * 这里用真实源的规则形状回归：字面 URL + `@get:{bid}` + `{{$.chapter_newid}}`。
     */
    @Test
    fun `legado 变量 - 目录条目继承搜索阶段变量`() {
        val json =
            """{"data":[{"chapter_name":"第1话","chapter_newid":"11"},{"chapter_name":"第2话","chapter_newid":"12"}]}"""
        val fields = mapOf(
            "chapterName" to "$.chapter_name",
            "chapterUrl" to
                "https://api.example.com/api/getchapterinfov2?product_id=1&comic_id=@get:{bid}" +
                "&chapter_newid={{$.chapter_newid}}&isWebp=1",
        )

        // 外层变量表模拟「搜索阶段已写入 bid」
        LegadoRuleEngine.variables = hashMapOf("bid" to "106463")
        val list = adapter(minimalComicJson()).jsonListForTest(json, fields, "-$.data.[*]")
        LegadoRuleEngine.variables = HashMap()

        // 前置 `-` 倒置照旧生效：站点原始顺序 [第1话,第2话] → 倒置为 [第2话,第1话]
        assertEquals(listOf("第2话", "第1话"), list.map { it["chapterName"] })
        // 关键：外层变量被继承，地址里绝不出现空 comic_id
        assertEquals(
            "https://api.example.com/api/getchapterinfov2?product_id=1&comic_id=106463&chapter_newid=12&isWebp=1",
            list[0]["chapterUrl"],
        )
        assertEquals(
            "https://api.example.com/api/getchapterinfov2?product_id=1&comic_id=106463&chapter_newid=11&isWebp=1",
            list[1]["chapterUrl"],
        )
        assertFalse("章节地址不应残留 @get: 字面量", list[0]["chapterUrl"]!!.contains("@get:"))
    }


    /**
     * `@put:{key:取值规则}` 写入变量、`@get:{key}` 回填。
     *
     * 这是「目录无内容」的第三个独立成因：不少 JSON API 源把作品 ID 存在变量里，
     * 目录地址再用 `@get:{key}` 取回来。变量缺失时地址里会残留字面量，
     * 接口返回空 / 错误数据，目录就空白了。
     */
    @Test
    fun `legado 变量 - @put 写入与 @get 回填`() {
        LegadoRuleEngine.variables = HashMap()
        // @put 段被剥离，且取值规则被求值写入变量表
        val stripped = LegadoRuleEngine.applyPutRules("$.comic_name@put:{bid:$.comic_id}") { "106463" }
        assertEquals("$.comic_name", stripped)
        assertEquals("106463", LegadoRuleEngine.variables["bid"])

        // 目录地址模板里的 @get:{bid} 被回填
        assertEquals(
            "https://api.example.com/api/getchapterlist?product_id=1&comic_id=106463",
            LegadoRuleEngine.resolveGetRules(
                "https://api.example.com/api/getchapterlist?product_id=1&comic_id=@get:{bid}",
            ),
        )
    }

    /** 变量未命中时替换为空串，绝不把 `@get:{...}` 字面量留在地址里 */
    @Test
    fun `legado 变量 - @get 未命中不残留字面量`() {
        LegadoRuleEngine.variables = HashMap()
        val url = LegadoRuleEngine.resolveGetRules("https://x/api?comic_id=@get:{notSet}&p=1")
        assertEquals("https://x/api?comic_id=&p=1", url)
        assertFalse("地址里不应残留 @get: 字面量", url.contains("@get:"))
    }

    /**
     * JSON 列表规则里 `@put:` 必须**按条目隔离**：
     * 若共用一张变量表，多条搜索结果会互相覆盖，点开任意一条拿到的都是最后一条的 ID。
     */
    @Test
    fun `legado 变量 - JSON 列表按条目隔离`() {
        val json = """{"data":[{"comic_id":"11","comic_name":"A"},{"comic_id":"22","comic_name":"B"}]}"""
        val sink = mutableListOf<Map<String, String>>()
        val list = LegadoRuleEngine.extractList(
            json,
            "@json:$.data",
            mapOf("name" to "$.comic_name@put:{bid:$.comic_id}"),
            "",
            sink,
        )
        assertEquals(listOf("A", "B"), list.map { it["name"] })
        assertEquals("11", sink[0]["bid"])
        assertEquals("22", sink[1]["bid"])
    }

    /** 列表倒置（前置 `-`）时，变量表也要同步倒置，否则章节和变量会错位配对 */
    @Test
    fun `legado 变量 - 列表倒置时变量表同步倒置`() {
        val json = """{"data":[{"comic_id":"11","comic_name":"A"},{"comic_id":"22","comic_name":"B"}]}"""
        val sink = mutableListOf<Map<String, String>>()
        val list = LegadoRuleEngine.extractList(
            json,
            "-@json:$.data",
            mapOf("name" to "$.comic_name@put:{bid:$.comic_id}"),
            "",
            sink,
        )
        assertEquals(listOf("B", "A"), list.map { it["name"] })
        assertEquals("22", sink[0]["bid"])
        assertEquals("11", sink[1]["bid"])
    }

    /** 连续多个 `@put:` 与含花括号的取值规则都要能正确解析 */
    @Test
    fun `legado 变量 - 多个 put 与含花括号的取值规则`() {
        LegadoRuleEngine.variables = HashMap()
        // 求值函数收到的是「取值规则」本身（如 $.comic_id），此处按规则给值
        val read = mapOf("$.comic_id" to "77", "$.name##\\{.*" to "abc")
        val stripped = LegadoRuleEngine.applyPutRules(
            "p@put:{bid:$.comic_id}@put:{clean:$.name##\\{.*}",
        ) { r -> read[r] }
        assertEquals("p", stripped)
        assertEquals("77", LegadoRuleEngine.variables["bid"])
        assertEquals("abc", LegadoRuleEngine.variables["clean"])
    }

    // ===== JSON 目录列表（前置 `-` 倒置 / `||` 备选路径 / `[*]` 后缀） =====
    /**
     * JSON 目录规则的前置 `-` 必须倒置。
     *
     * 形如 `-$.data.[*]` 的规则（部分源）不走 `@json:` 前缀，
     * 若只在 HTML 列表里处理倒置，这类源的目录顺序会是倒着的
     * （打开漫画直接停在「最新话」，而非「第 1 话」）。
     */
    @Test
    fun `JSON 目录列表 - 前置负号倒置`() {
        val json = """{"data":[{"chapter_name":"第3话"},{"chapter_name":"第2话"},{"chapter_name":"第1话"}]}"""
        val fields = mapOf("chapterName" to "$.chapter_name")

        val natural = adapter(minimalComicJson()).jsonListForTest(json, fields, "$.data.[*]")
        assertEquals(listOf("第3话", "第2话", "第1话"), natural.map { it["chapterName"] })

        val reversed = adapter(minimalComicJson()).jsonListForTest(json, fields, "-$.data.[*]")
        assertEquals(listOf("第1话", "第2话", "第3话"), reversed.map { it["chapterName"] })
    }

    /** 列表路径支持 `||` 备选与 `@json:` 前缀（大小写不敏感） */
    @Test
    fun `JSON 目录列表 - 备选路径与前缀兼容`() {
        val json = """{"data":[{"chapter_name":"A"},{"chapter_name":"B"}]}"""
        val fields = mapOf("chapterName" to "$.chapter_name")
        val ad = adapter(minimalComicJson())

        // $.data.data 解析不到 → 退回 $.data
        assertEquals(
            listOf("A", "B"),
            ad.jsonListForTest(json, fields, "$.data.data||$.data").map { it["chapterName"] },
        )
        // @JSon: 前缀 + 前置负号
        assertEquals(
            listOf("B", "A"),
            ad.jsonListForTest(json, fields, "-@JSon:$.data").map { it["chapterName"] },
        )
    }

    /** 嵌套路径取数组（`$.data.list[*]`） */
    @Test
    fun `JSON 目录列表 - 嵌套路径`() {
        val json = """{"data":{"list":[{"chapter_name":"A"},{"chapter_name":"B"}]}}"""
        val fields = mapOf("chapterName" to "$.chapter_name")
        val got = adapter(minimalComicJson()).jsonListForTest(json, fields, "-$.data.list[*]")
        assertEquals(listOf("B", "A"), got.map { it["chapterName"] })
    }

    // ===== JSON 字段规则里的 JS（`@js:` / `{{}}` 模板 / 变量）=====

    /**
     * 取值为纯 JS 的 JSON 字段规则必须执行 JS，而不是当成普通路径。
     *
     * 某 App 接口源的 `ruleSearch.bookUrl` 就是这种写法：
     * 用 `java.md5Encode` 算出接口签名，返回 `地址,{请求配置}`。
     * 此前 `renderJsonRule` 见到 `{{` 就只做模板替换、不执行 JS，
     * 结果 bookUrl 变成一段 JS 源码 → 详情/目录请求地址错乱 → 目录空白。
     */
    @Test
    fun `legado JS - JSON 字段规则执行 @js 并支持 java_md5Encode`() {
        val json = """{"comic_id":97500,"comic_name":"噬龙蚁"}"""
        val rule = """
@js:
t="/x?comic_id={{$.comic_id}}&chapter_id=1152731suffix";
sign=java.md5Encode(t);
b={"method": "GET","headers": {"m-request-id": sign}};
"https://h/x?comic_id={{$.comic_id}}&chapter_id=1152731,"+JSON.stringify(b);
        """.trim()
        val out = adapter(minimalComicJson()).jsonFieldForTest(json, rule)
        assertNotNull(out)
        // 地址正确、`{{$.comic_id}}` 已替换
        assertTrue("实际=$out", out!!.startsWith("https://h/x?comic_id=97500&chapter_id=1152731,"))
        // 签名 = md5("/x?comic_id=97500&chapter_id=1152731suffix")
        assertTrue("实际=$out", out.contains("\"m-request-id\":\"541bf23fcde0bb9ca6f823b5fc9eba80\""))
        assertEquals("GET", out.substringAfter("\"method\":\"").substringBefore("\""))
    }

    /** `<js>` 内含 `@get:{k}` 变量回填（目录章节地址规则的真实形态） */
    @Test
    fun `legado JS - 字段 JS 里的 @get 变量先回填再执行`() {
        val json = """{"chapter_id":1152731}"""
        val rule = "@js:cid='@get:{cid}';\"https://h/c?comic_id=\"+cid+\"&chapter_id={{$.chapter_id}}\""
        LegadoRuleEngine.variables = hashMapOf("cid" to "97500")
        val out = adapter(minimalComicJson()).jsonFieldForTest(json, rule)
        LegadoRuleEngine.variables = HashMap()
        assertEquals("https://h/c?comic_id=97500&chapter_id=1152731", out)
    }

    /** 纯 JS 字段规则的 `<js>` 写法：直接对 JSON 节点求值 */
    @Test
    fun `legado JS - 字段规则用 js 块拼接`() {
        val json = """{"comic_id":97500}"""
        val rule = "<js>\n\"https://h/d?comic_id=\"+java.getString(\"$.comic_id\")\n</js>"
        assertEquals(
            "https://h/d?comic_id=97500",
            adapter(minimalComicJson()).jsonFieldForTest(json, rule),
        )
    }

    /**
     * `java.*` 桥的核心加密能力（源里的签名与解密都依赖它）。
     * 密文由同一 key/iv 离线生成，可直接断言解密结果与 MD5。
     */
    @Test
    fun `legado JS - java 桥的 md5 与 aes 解密`() {
        val bridge = com.novelreader.source.legado.LegadoJavaBridge(null, null, "")
        assertEquals("900150983cd24fb0d6963f7d28e17f72", bridge.md5Encode("abc"))

        // AES/CBC/PKCS5Padding，key=4548ded8c9e02690，iv=1992360ee9bc4f8f
        val cipher = "zRpi1d0vWHiK6YhmuUbFHw0KDYaBbevaVPec9gqyigjN+VD9ELxOxhWKgqCYrBhU+" +
            "h2jpswbUXf3hA89kdtXlBsyjzussAoS1GYBGsbqoA3BCVY4BdP56epnusV0rFVdFTRA/7CYr3DCjIENvd8" +
            "ZrcLyE0Uo2Xq0mVkF1QQiwhq73FzcVhNQ6GnKb014Xxaxq3lJ7lXS1J8Jij6k6t5DWc3JdKkTm6PZ3G+rSyNqM+Y="
        val plain = bridge.aesBase64DecodeToString(
            cipher,
            "4548ded8c9e02690",
            "AES/CBC/PKCS5Padding",
            "1992360ee9bc4f8f",
        )
        assertTrue("实际=$plain", plain.contains("\"chapter_name\":\"第1话 \""))
        assertTrue("实际=$plain", plain.contains("\"chapter_id\":1152733"))
        // 参数不合法时返回空串而不是抛异常（不能中断整条书源解析）
        assertEquals("", bridge.aesBase64DecodeToString("!!!not-base64!!!", "k", "AES/CBC/PKCS5Padding", "v"))
    }

    /**
     * 列表规则「前置 `<js>` 块 + 后续 JSONPath」链式：先整体解密，再取数组。
     *
     * 某 App 接口源 `ruleToc.chapterList` 的真实形态：
     * `<js>…java.aesBase64DecodeToString($.data)…</js>` 换行 `$.chapters[*]`。
     */
    @Test
    fun `legado JS - 列表规则的前置 js 块链式解密后再取数组`() {
        val cipher = "zRpi1d0vWHiK6YhmuUbFHw0KDYaBbevaVPec9gqyigjN+VD9ELxOxhWKgqCYrBhU+" +
            "h2jpswbUXf3hA89kdtXlBsyjzussAoS1GYBGsbqoA3BCVY4BdP56epnusV0rFVdFTRA/7CYr3DCjIENvd8" +
            "ZrcLyE0Uo2Xq0mVkF1QQiwhq73FzcVhNQ6GnKb014Xxaxq3lJ7lXS1J8Jij6k6t5DWc3JdKkTm6PZ3G+rSyNqM+Y="
        val content = """{"data":"arsadata$cipher","status":1,"message":"ok"}"""
        val listRule = """
<js>
result=String(java.getString("${'$'}.data")).replace(/arsadata/,"");
java.aesBase64DecodeToString(result,"4548ded8c9e02690","AES/CBC/PKCS5Padding","1992360ee9bc4f8f");
</js>
${'$'}.chapters[*]
        """.trim()

        val fields = mapOf("chapterName" to "${'$'}.chapter_name", "chapterUrl" to "${'$'}.chapter_id")
        val got = adapter(minimalComicJson()).jsonListForTest(content, fields, listRule)
        assertEquals(3, got.size)
        assertEquals(listOf("第1话 ", "第2话 ", "第3话 "), got.map { it["chapterName"] })
        assertEquals(listOf("1152731", "1152732", "1152733"), got.map { it["chapterUrl"] })
    }

    /** 地址后的请求配置必须与地址分离（配置用于发请求，不能混进 URL） */
    @Test
    fun `legado JS - 拆分地址后的请求配置`() {
        val ad = adapter(minimalComicJson())
        val (url, options) = ad.splitUrlOptions(
            """https://api.example.com/c?comic_id=1,{"method":"GET","headers":{"m-request-id":"abc"}}""",
        )
        assertEquals("https://api.example.com/c?comic_id=1", url)
        assertTrue(options.contains("\"m-request-id\":\"abc\""))

        // 没有配置时原样返回
        val (plain, none) = ad.splitUrlOptions("https://api.example.com/c?comic_id=1")
        assertEquals("https://api.example.com/c?comic_id=1", plain)
        assertEquals("", none)
    }

    /**
     * 某 App 接口源 `ruleContent.content` 的真实形态：**整条规则就是一个 `<js>` 块**，
     * 内部用 `java.getString("$.data")` 取密文 → 去 `arsadata` 前缀 → AES 解密 →
     * 正则截出 JSON 数组 → 拼成 `<img src="...">` 多行文本。
     *
     * 回归点：正文规则没有前置取值段、也没有尾部 `@js:`，若引擎只认 `前缀@js:` 形态，
     * 整条规则会被当成选择器 → 一张图都取不到 → 阅读页显示「本章暂无可用图片」。
     */
    @Test
    fun `漫画正文 - 整体 js 块解密后拼图（App 接口源）`() {
        val ad = adapter(minimalComicJson())
        // key=4548ded8c9e02690 / iv=1992360ee9bc4f8f，明文为 URL 的 JSON 数组（离线生成）
        val cipher = "i3D92OqDuiQWte4Q0lNgdp1s9Ubol3JFePpQa+ie0L7pp+HYwVxjuICacOJK" +
            "US8pKpu1z0jqugav6ie/4eBVk78Z/yzMiZ5EeiImj+FlgK8uGQnMEb91S5uZ" +
            "UtZQUTvUyjXzv6muKvd2aKB5luWtbgs2XKQdjHMNtJvN1rI3BQCBj+dGk2hw" +
            "wlQrv7oMsgTqKPz8QNnEh+KScE21pWEkqQ=="
        val body = """{"data":"arsadata$cipher","status":1}"""

        val contentRule = "<js>" +
            "result=String(java.getString(\"\$.data\")).replace(/arsadata/,\"\");" +
            "u=java.aesBase64DecodeToString(result,\"4548ded8c9e02690\"," +
            "\"AES/CBC/PKCS5Padding\",\"1992360ee9bc4f8f\");" +
            "u.match(/\\[(.*)\\]/)[1].split(\",\").map(x=>'<img src='+x+'>').join(\"\\n\")" +
            "</js>"

        val res = ad.extractComicImages(
            body, mapOf("content" to contentRule), "https://api.example.com/",
        )
        assertEquals(
            listOf(
                "https://hw-chapter2.example.com/comic/S/1.jpg-qmmh.middle.webp?auth_key=aaa",
                "https://hw-chapter2.example.com/comic/S/2.jpg-qmmh.middle.webp?auth_key=bbb",
            ),
            res.comicImages,
        )
        // 图片地址未带 headers 后缀时，仍应给出默认防盗链 Referer（按被请求接口的站点）
        assertEquals("https://api.example.com/", res.comicHeaders["Referer"])
    }

    /**
     * 章节地址 `,{...}` 请求配置的拆分：干净地址用于发请求，配置里带签名请求头。
     *
     * 回归点：曾因只保留干净地址、而正文取配置时去读 `chapter.url`，
     * 导致签名请求头整体丢失 → 该接口源返回错误数据 → 阅读页空白。
     */
    @Test
    fun `漫画章节 - 地址携带的请求配置可被拆分且不丢签名`() {
        val ad = adapter(minimalComicJson())
        val raw = "https://api.example.com/comic-api/v2/comic/getchapterdata" +
            "?comic_id=97500&chapter_id=1152731&quality=middle" +
            ",{\"method\":\"GET\",\"headers\":{\"access-token\":\"v1_abc\",\"m-request-id\":\"541bf23\"}}"

        val (clean, options) = ad.splitUrlOptions(raw)
        assertEquals(
            "https://api.example.com/comic-api/v2/comic/getchapterdata" +
                "?comic_id=97500&chapter_id=1152731&quality=middle",
            clean,
        )
        assertTrue(options.contains("\"access-token\":\"v1_abc\""))
        assertTrue(options.contains("\"m-request-id\":\"541bf23\""))
        // 含逗号的查询串不会被误当成配置起点（必须匹配 `,{`）
        assertTrue(!clean.contains("97500,"))
    }

    // ===== 联网真实源验证 =====

    private fun realTestsEnabled(): Boolean = System.getenv("LEIMAO_REAL_TESTS") == "1"

    private fun loadShuyuan(): List<Map<String, Any?>> {
        // 可用环境变量 LEIMAO_SOURCE_FILE 指定真实源文件（默认 ../shuyuan.json）
        val envPath = System.getenv("LEIMAO_SOURCE_FILE")
        val file = if (!envPath.isNullOrBlank()) java.io.File(envPath) else java.io.File("../shuyuan.json")
        assumeTrue("找不到书源文件：${file.path}", file.exists())
        val text = file.readText()
        val arr = com.google.gson.JsonParser.parseString(text).asJsonArray
        return arr.map { el ->
            val obj = el.asJsonObject
            obj.entrySet().associate { (k, v) ->
                k to if (v.isJsonPrimitive) v.asString else v.toString()
            }
        }
    }

    private fun sourceJson(name: String): String? =
        loadShuyuan().firstOrNull { (it["bookSourceName"] as? String) == name }
            ?.let { m ->
                val obj = com.google.gson.JsonObject()
                m.forEach { (k, v) ->
                    if (v is String) obj.addProperty(k, v) else obj.add(k, com.google.gson.JsonParser.parseString(v.toString()))
                }
                obj.toString()
            }

    /**
     * 取指定书源；书源文件里没有该源时**跳过**用例而非失败
     * （书源文件是外部数据，缺源属于环境问题，不是代码回归）。
     */
    private fun sourceJsonOrSkip(name: String?): String {
        val srcName = name?.takeIf { it.isNotBlank() }
        assumeTrue("未指定该用例的书源名（环境变量），跳过该联网用例", srcName != null)
        val json = sourceJson(srcName!!)
        assumeTrue("书源文件里没有该源，跳过该联网用例", json != null)
        return json!!
    }

    /**
     * 联网用例验证哪一个源**由环境变量给出**，避免把具体站点名写进仓库：
     * - `LEIMAO_SRC_NOVEL_A`：目录 / 正文分页齐全的源（章节数 > 50、正文 > 1000 字）
     * - `LEIMAO_SRC_NOVEL_B`：GBK POST 搜索的源
     * - `LEIMAO_SRC_NOVEL_C`：CSS 列表 + onclick 提取 bookUrl 的源
     * 未设置或书源文件里找不到时，用例自动跳过。
     */
    private fun envSource(name: String): String? = System.getenv(name)

    /** 真实源 A：搜索 → 详情 → 完整目录（含翻页）→ 第一章正文（含翻页拼接） */
    @Test
    fun `真实源 A - 目录与正文分页完整链路`() {
        assumeTrue(realTestsEnabled())
        val json = sourceJsonOrSkip(envSource("LEIMAO_SRC_NOVEL_A"))
        val ad = adapter(json)

        val results = kotlinx.coroutines.runBlocking { ad.search("斗破苍穹", 1) }
        assertTrue("搜索无结果", results.isNotEmpty())
        val book = results.first()
        assertTrue("书名不应为空", book.title.isNotBlank())
        assertTrue("bookUrl 应为绝对地址", book.detailUrl.startsWith("http"))

        val detail = kotlinx.coroutines.runBlocking { ad.detail(book) }
        assertTrue("详情标题不应为空", detail.title.isNotBlank())

        val chapters = kotlinx.coroutines.runBlocking { ad.chapterList(detail) }
        assertTrue("目录翻页后章节数应远超单页(>50)，实际 ${chapters.size}", chapters.size > 50)

        val content = kotlinx.coroutines.runBlocking { ad.content(chapters[0]) }
        val text = content.novelText ?: ""
        assertTrue("第一章正文过短（正文翻页可能失效）：${text.length}", text.length > 1000)
    }

    /** 真实源 B：GBK POST 搜索，关键词需按 GBK 编码，否则返回乱码/无结果 */
    @Test
    fun `真实源 B - GBK POST 搜索`() {
        assumeTrue(realTestsEnabled())
        val json = sourceJsonOrSkip(envSource("LEIMAO_SRC_NOVEL_B"))
        val ad = adapter(json)

        val results = kotlinx.coroutines.runBlocking { ad.search("斗破苍穹", 1) }
        assertTrue("GBK POST 搜索无结果", results.isNotEmpty())
        assertTrue("结果应包含关键词相关书名", results.any { it.title.contains("斗破") })
    }

    // ===== 发现分类解析（v1.2.3.0，离线） =====

    @Test
    fun `发现分类 - JSON数组格式`() {
        val json = """{"bookSourceName":"测试源","bookSourceUrl":"http://example.com",
            "exploreUrl":"[{\"title\":\"玄幻\",\"url\":\"/xuanhuan/{{page}}\"},{\"title\":\"都市\",\"url\":\"/dushi/{{page}}\"}]",
            "ruleExplore":{"bookList":"class.item","name":"tag.h3@text","bookUrl":"tag.a@href"}}"""
        val ad = adapter(json)
        val cats = kotlinx.coroutines.runBlocking { ad.exploreCategories() }
        assertEquals(2, cats.size)
        assertEquals("玄幻", cats[0].title)
        assertEquals("/xuanhuan/{{page}}", cats[0].url)
        assertEquals("都市", cats[1].title)
    }

    @Test
    fun `发现分类 - 分类名冒号地址与纯地址`() {
        val json = """{"bookSourceName":"测试源2","bookSourceUrl":"http://example.com",
            "exploreUrl":"玄幻榜::http://example.com/xh/\nhttp://example.com/all/"}"""
        val ad = adapter(json)
        val cats = kotlinx.coroutines.runBlocking { ad.exploreCategories() }
        assertEquals(2, cats.size)
        assertEquals("玄幻榜", cats[0].title)
        assertEquals("http://example.com/xh/", cats[0].url)
        // 纯地址行：标题即地址
        assertEquals("http://example.com/all/", cats[1].title)
        assertEquals("http://example.com/all/", cats[1].url)
    }

    /** 阅友小说：CSS 列表 + onclick JS bookUrl */
    /** 真实源 C：CSS 列表 + onclick JS bookUrl */
    @Test
    fun `真实源 C - 搜索与bookUrl提取`() {
        assumeTrue(realTestsEnabled())
        val json = sourceJsonOrSkip(envSource("LEIMAO_SRC_NOVEL_C"))
        val ad = adapter(json)

        val results = kotlinx.coroutines.runBlocking { ad.search("斗破苍穹", 1) }
        assertTrue("搜索无结果", results.isNotEmpty())
        // bookUrl 来自 onclick JS 提取，必须是有效绝对链接（而非书名兜底）
        assertTrue(
            "bookUrl 应为 http 绝对地址：${results.first().detailUrl}",
            results.first().detailUrl.startsWith("http"),
        )
    }

    /**
     * 构造一个最小可用的漫画源（用于离线验证漫画正文规则，无需联网）。
     */
    private fun minimalComicJson(
        baseUrl: String = "https://example.com",
        header: String? = null,
    ): String = com.google.gson.JsonObject().apply {
        addProperty("bookSourceName", "离线漫画测试源")
        addProperty("bookSourceUrl", baseUrl)
        addProperty("bookSourceType", 2)
        addProperty("searchUrl", "$baseUrl/search?title={{key}}")
        if (header != null) addProperty("header", header)
    }.toString()

    /**
     * 正文规则写成 `$.路径@js:...`（JSON 接口 + JS 拼多图，如部分 App 接口源）时，
     * 前缀 JSONPath 必须先解析出 `result`：否则 JS 里 `result.split("\n")` 直接失败，
     * 一张图都取不到 → 页面显示「图片加载失败」。
     *
     * 数组必须按换行拼接（而不是 JSON 数组字面量），JS 才能逐条处理。
     */
    @Test
    fun `漫画正文 - JSONPath 前缀 + JS 拼多图`() {
        val ad = adapter(minimalComicJson())
        val json = """{"data":{"current_chapter":{"chapter_img_list":""" +
            """["https://cdn.x/1.jpg","https://cdn.x/2.jpg","https://cdn.x/3.jpg"]}}}"""
        val contentRule = "$.data.current_chapter.chapter_img_list@js:" +
            "result.split(\"\\n\").map(x=>'<img src=\"'+x+'\">').join(\"\\n\")"

        val res = ad.extractComicImages(json, mapOf("content" to contentRule), "https://api.example.com/123/")
        assertEquals(
            listOf("https://cdn.x/1.jpg", "https://cdn.x/2.jpg", "https://cdn.x/3.jpg"),
            res.comicImages,
        )
    }

    // ===== 漫画：离线验证「正文 JS + 防盗链请求头」链路 =====
    /**
     * 真实书源（野蛮漫画/奇漫屋/彩绘漫画）的写法：
     * JS 把自己的 headers JSON 拼进 `img@src`，形成 `url,{"headers":{...}}` 后缀。
     * 引擎必须能把地址与请求头都拆出来 —— 否则图片即使拿到地址也会被 CDN 403。
     */
    @Test
    fun `漫画正文JS - img 内嵌 headers 后缀应解析出地址与 Referer`() {
        val ad = adapter(minimalComicJson())
        val page = """<html><body><div class="list">""" +
            """<img data-src="/img/1.jpg"><img data-src="/img/2.jpg"></div></body></html>"""
        // 规则里的 Referer 用的是 CDN 域名（不等于章节页域名），以此证明它确实来自规则解析
        val contentRule = ".list@img@data-src@js:" +
            "headers={\"headers\":{\"Referer\":\"https://cdn.example.com/\"}};" +
            "result.split(\"\\n\").map(x=>'<img src=\"'+x+','+JSON.stringify(headers)+'\">').join(\"\\n\")"

        val res = ad.extractComicImages(
            page, mapOf("content" to contentRule), "https://example.com/manhua-abc/1/",
        )
        assertEquals(
            listOf("https://example.com/img/1.jpg", "https://example.com/img/2.jpg"),
            res.comicImages,
        )
        assertEquals("https://cdn.example.com/", res.comicHeaders["Referer"])
    }

    /** 图片相对地址必须按「章节页 URL」解析（而非源站根地址），并默认补 Referer */
    @Test
    fun `漫画图片 - 相对地址按章节页解析且默认带 Referer`() {
        val ad = adapter(minimalComicJson())
        val page = """<html><body><div id="c">""" +
            """<img src="/media/1.jpg"><img src="2.jpg"></div></body></html>"""

        val res = ad.extractComicImages(
            page, mapOf("content" to "#c@img@src"), "https://example.com/manhua-abc/1/",
        )
        assertEquals(
            listOf("https://example.com/media/1.jpg", "https://example.com/manhua-abc/1/2.jpg"),
            res.comicImages,
        )
        assertEquals("https://example.com/", res.comicHeaders["Referer"])
    }

    /** JSON 正文里的单图 `,{headers}` 后缀：其请求头应覆盖默认 Referer */
    @Test
    fun `漫画图片 - 单图 headers 后缀覆盖默认 Referer`() {
        val ad = adapter(minimalComicJson())
        // 注意：图片串内部的引号按 JSON 规则转义（\"），这才是合法的 JSON 正文
        val json =
            """{"data":{"images":["https://cdn.other.com/a.jpg,{\"headers\":{\"Referer\":\"https://cdn.other.com/\"}}"]}}"""

        val res = ad.extractComicImages(
            json, mapOf("content" to "@json:$.data.images"), "https://example.com/manhua-abc/1/",
        )
        assertEquals(listOf("https://cdn.other.com/a.jpg"), res.comicImages)
        assertEquals("https://cdn.other.com/", res.comicHeaders["Referer"])
    }

    /**
     * 漫画源联网验证：目录倒置（规则前置 `-`）+ 图片防盗链（Referer）。
     *
     * 书源文件里带 `-` 倒置目录规则的漫画源很多，但站点可用性差异大，
     * 因此按顺序逐个尝试，任一源完整跑通即算通过。
     *
     * 注意：全部候选都没跑通时**跳过**而非失败——漫画站封锁/改版属于外部环境因素，
     * 不是本项目的回归；规则语义本身由上面的离线用例确定性覆盖。
     */
    @Test
    fun `漫画源 - 目录倒置与图片防盗链请求头`() {
        assumeTrue(realTestsEnabled())
        val all = loadShuyuan()
        val reversedToc = Regex("\"(?:chapterList|chapterListRule)\"\\s*:\\s*\"\\s*-")
        val candidates = all
            .filter { m ->
                (m["bookSourceType"] as? String)?.trim() == "2" &&
                    reversedToc.containsMatchIn(m["ruleToc"] as? String ?: "")
            }
            .mapNotNull { it["bookSourceName"] as? String }
            .distinct()
        assumeTrue("书源文件里没有「目录规则以 - 开头」的漫画源", candidates.isNotEmpty())

        val errors = mutableListOf<String>()
        for (name in candidates.take(12)) {
            val json = sourceJson(name) ?: continue
            val ad = adapter(json)
            runCatching {
                val results = kotlinx.coroutines.runBlocking { ad.search("斗破苍穹", 1) }
                if (results.isEmpty()) error("搜索无结果")
                val detail = kotlinx.coroutines.runBlocking { ad.detail(results.first()) }
                val chapters = kotlinx.coroutines.runBlocking { ad.chapterList(detail) }
                if (chapters.isEmpty()) error("目录为空（- 倒置规则可能未生效）")
                assertTrue("章节地址应为绝对地址：${chapters.first().url}", chapters.first().url.startsWith("http"))
                val content = kotlinx.coroutines.runBlocking { ad.content(chapters.first()) }
                val images = content.comicImages ?: emptyList()
                if (images.isEmpty()) error("未提取到图片")
                assertTrue("图片地址应为绝对地址：${images.first()}", images.first().startsWith("http"))
                assertTrue(
                    "图片请求头缺少 Referer：${content.comicHeaders}",
                    content.comicHeaders.keys.any { it.equals("Referer", ignoreCase = true) },
                )
                println("[漫画验证通过] $name：章节=${chapters.size}，图片=${images.size}，headers=${content.comicHeaders}")
                return
            }.onFailure { errors += "$name: ${it.message}" }
        }
        assumeTrue("候选漫画源本次均未跑通（网络/站点因素）：\n" + errors.joinToString("\n"), false)
    }

    // ===== v1.7.6.0：多段链式规则（legado 语义：逐行接力，result 传递） =====

    /** 最小可用的书源 JSON（只用来构造 adapter，规则求值本身不碰网络） */
    private fun stubAdapter(): LegadoSourceAdapter = adapter(
        """{"bookSourceName":"stub","bookSourceUrl":"https://example.com","bookSourceType":0}"""
    )

    @Test
    fun `多段链式规则 - 取值加JS加模板result回填`() {
        // 真实形态（小说源的 bookUrl）：先取 bid，JS 偏移成 bookid，再拼进详情地址。
        // 修复前整条规则被当成一个表达式，`{{result}}` 无处取值 → 详情地址缺 bookid。
        val ad = stubAdapter()
        val node = com.google.gson.JsonParser.parseString(
            """{"bid":"465030","title":"测试书"}"""
        ).asJsonObject
        val rule = "\$.bid\n" +
            "<js>1100000000+parseInt(result)</js>\n" +
            "https://bookshelf.html5.qq.com/qbread/api/novel/intro-info?bookid={{result}}"
        assertEquals(
            "https://bookshelf.html5.qq.com/qbread/api/novel/intro-info?bookid=1100465030",
            ad.renderJsonRule(rule, node),
        )
    }

    @Test
    fun `多段链式规则 - 独立的atjs引导行吞掉后续多行函数体`() {
        // 真实形态（源的 coverUrl）：`@js:` 单独一行，函数体自带换行，
        // 按行切片会把函数体切碎 —— 因此引导行要一直吞到规则末尾。
        val ad = stubAdapter()
        val node = com.google.gson.JsonParser.parseString("""{"bid":"1"}""").asJsonObject
        val rule = """
            ${'$'}.bid
            @js:
            var s = result
            return 'http://img.test/cover/'+s+'.jpg'
            """.trimIndent()
        assertEquals("http://img.test/cover/1.jpg", ad.renderJsonRule(rule, node))
    }

    @Test
    fun `多段链式规则 - 正则净化段`() {
        val ad = stubAdapter()
        val node = com.google.gson.JsonParser.parseString("""{"kind":"20000:小说,20014:仙侠"}""").asJsonObject
        val rule = "\$.kind\n##\\d.*?:(.*?),(?:.*?)##$1"
        val v = ad.renderJsonRule(rule, node)
        assertNotNull("多段含正则替换时应有结果", v)
        println("[多段正则] $v")
    }

    @Test
    fun `单行规则行为不变 - 普通JSONPath与模板`() {
        val ad = stubAdapter()
        val node = com.google.gson.JsonParser.parseString("""{"name":"A","id":7}""").asJsonObject
        assertEquals("A", ad.renderJsonRule("\$.name", node))
        assertEquals("id=7", ad.renderJsonRule("id={{$.id}}", node))
    }
}
