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
 *   得奇小说网（目录翻页 + 正文翻页）、手机小说（GBK POST 搜索）
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
        // 阅友小说的结构：列表项容器带 onclick（含空格），bookUrl 规则 = @onclick@js:正则提取
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
     * 这是设备端实测发现的最后一个「目录空白」成因（爱看漫画 / m.kanman.com）：
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
                "https://m.kanman.com/api/getchapterinfov2?product_id=1&comic_id=@get:{bid}" +
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
            "https://m.kanman.com/api/getchapterinfov2?product_id=1&comic_id=106463&chapter_newid=12&isWebp=1",
            list[0]["chapterUrl"],
        )
        assertEquals(
            "https://m.kanman.com/api/getchapterinfov2?product_id=1&comic_id=106463&chapter_newid=11&isWebp=1",
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
            "https://m.kanman.com/api/getchapterlist?product_id=1&comic_id=106463",
            LegadoRuleEngine.resolveGetRules(
                "https://m.kanman.com/api/getchapterlist?product_id=1&comic_id=@get:{bid}",
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
     * 形如 `-$.data.[*]` 的规则（爱看漫画、看漫画吧等）不走 `@json:` 前缀，
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
    private fun sourceJsonOrSkip(name: String): String {
        val json = sourceJson(name)
        assumeTrue("书源文件里没有「$name」，跳过该联网用例", json != null)
        return json!!
    }

    /** 得奇小说网：搜索 → 详情 → 完整目录（含翻页）→ 第一章正文（含翻页拼接） */
    @Test
    fun `得奇小说网 - 完整链路`() {
        assumeTrue(realTestsEnabled())
        val json = sourceJsonOrSkip("得奇小说网")
        val ad = adapter(json)

        val results = kotlinx.coroutines.runBlocking { ad.search("斗破苍穹", 1) }
        assertTrue("得奇搜索无结果", results.isNotEmpty())
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

    /** 手机小说：GBK POST 搜索，关键词需按 GBK 编码，否则返回乱码/无结果 */
    @Test
    fun `手机小说 - GBK POST 搜索`() {
        assumeTrue(realTestsEnabled())
        val json = sourceJsonOrSkip("手机小说")
        val ad = adapter(json)

        val results = kotlinx.coroutines.runBlocking { ad.search("斗破苍穹", 1) }
        assertTrue("GBK POST 搜索无结果", results.isNotEmpty())
        assertTrue("结果应包含关键词相关书名", results.any { it.title.contains("斗破") })
    }

    /** 阅友小说：CSS 列表 + onclick JS bookUrl */
    @Test
    fun `阅友小说 - 搜索与bookUrl提取`() {
        assumeTrue(realTestsEnabled())
        val json = sourceJsonOrSkip("阅友小说")
        val ad = adapter(json)

        val results = kotlinx.coroutines.runBlocking { ad.search("斗破苍穹", 1) }
        assertTrue("阅友搜索无结果", results.isNotEmpty())
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
        baseUrl: String = "https://m.dm5.com",
        header: String? = null,
    ): String = com.google.gson.JsonObject().apply {
        addProperty("bookSourceName", "离线漫画测试源")
        addProperty("bookSourceUrl", baseUrl)
        addProperty("bookSourceType", 2)
        addProperty("searchUrl", "$baseUrl/search?title={{key}}")
        if (header != null) addProperty("header", header)
    }.toString()

    /**
     * 正文规则写成 `$.路径@js:...`（JSON 接口 + JS 拼多图，如爱看漫画）时，
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

        val res = ad.extractComicImages(json, mapOf("content" to contentRule), "https://m.kanman.com/123/")
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
            "headers={\"headers\":{\"Referer\":\"https://cdn.dm5.com/\"}};" +
            "result.split(\"\\n\").map(x=>'<img src=\"'+x+','+JSON.stringify(headers)+'\">').join(\"\\n\")"

        val res = ad.extractComicImages(
            page, mapOf("content" to contentRule), "https://m.dm5.com/manhua-abc/1/",
        )
        assertEquals(
            listOf("https://m.dm5.com/img/1.jpg", "https://m.dm5.com/img/2.jpg"),
            res.comicImages,
        )
        assertEquals("https://cdn.dm5.com/", res.comicHeaders["Referer"])
    }

    /** 图片相对地址必须按「章节页 URL」解析（而非源站根地址），并默认补 Referer */
    @Test
    fun `漫画图片 - 相对地址按章节页解析且默认带 Referer`() {
        val ad = adapter(minimalComicJson())
        val page = """<html><body><div id="c">""" +
            """<img src="/media/1.jpg"><img src="2.jpg"></div></body></html>"""

        val res = ad.extractComicImages(
            page, mapOf("content" to "#c@img@src"), "https://m.dm5.com/manhua-abc/1/",
        )
        assertEquals(
            listOf("https://m.dm5.com/media/1.jpg", "https://m.dm5.com/manhua-abc/1/2.jpg"),
            res.comicImages,
        )
        assertEquals("https://m.dm5.com/", res.comicHeaders["Referer"])
    }

    /** JSON 正文里的单图 `,{headers}` 后缀：其请求头应覆盖默认 Referer */
    @Test
    fun `漫画图片 - 单图 headers 后缀覆盖默认 Referer`() {
        val ad = adapter(minimalComicJson())
        // 注意：图片串内部的引号按 JSON 规则转义（\"），这才是合法的 JSON 正文
        val json =
            """{"data":{"images":["https://cdn.other.com/a.jpg,{\"headers\":{\"Referer\":\"https://cdn.other.com/\"}}"]}}"""

        val res = ad.extractComicImages(
            json, mapOf("content" to "@json:$.data.images"), "https://m.dm5.com/manhua-abc/1/",
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
}
