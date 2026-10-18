package com.novelreader

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.novelreader.domain.model.SourceFormat
import com.novelreader.domain.model.SourceInfo
import com.novelreader.domain.model.SourceType
import com.novelreader.source.legado.LegadoBookSource
import com.novelreader.source.legado.LegadoJavaBridge
import com.novelreader.source.legado.LegadoRuleEngine
import com.novelreader.source.legado.LegadoSourceAdapter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v1.7.9.1 引擎修复 B/C/D 的离线回归（任务 #126 / #127 / #128）。
 *
 * 规则全部取自 leimao-sources/legado-all.json 的**原文**（模板字面量 / `let` /
 * `padStart` 在 Rhino 1.7.15 + VERSION_1_8 下均可用，已用 JVM 探针验证），
 * 保证单测与线上源行为一致：
 * - **#126 目录地址**：`{{$.字段}}` 模板（中文书城 / 笔趣阁 api 站）、
 *   `data` 包装回退、整条 `@js:`（米读小说 try/catch + 模板先渲染；
 *   海棠书屋 index≤9 分支返回详情页地址，>9 分支要联网取验证码、留给诊断）、
 *   选择器 + `##正则##替换` 改写（笔趣阁 mm 站）；
 * - **#127 列表规则 JS**：整条 `@js:` 产出对象数组且跳过卷标项（中文万维）、
 *   JSON 路径 + 尾部 `<js>` 借 `java.put/getString` 传正文前缀变量（安稳小说）、
 *   `+<js>` 前缀纯桥解析且跳过卷头（轻菠萝包）、选择器 + 尾部 `<js>`
 *   的验证码反序重组分支（海棠书屋）；
 * - **#128 java 桥**：`getString('$.x')` 读 JSON、`[*]` 中段投影两层拍平、
 *   终态数组无 `[*]` 按元素换行拼接。
 */
class LegadoJsListRuleTest {

    @Test
    fun `JSONPath 支持带点号和方括号的引号键`() {
        val root = JsonParser.parseString(
            """{"data":{"book.info":{"chapter[list]":[{"title":"第一章"}]}}}""",
        )
        val values = LegadoRuleEngine.selectJsonList(
            root,
            "$.data['book.info']['chapter[list]'][*]",
        )
        assertEquals(1, values.size)
        assertEquals("第一章", values.single().asJsonObject.get("title").asString)
    }

    @Test
    fun `JSONPath 支持双引号键及空格`() {
        val root = JsonParser.parseString("""{"data":{"book title":{"name":"样本"}}}""")
        val values = LegadoRuleEngine.selectJsonList(root, """$["data"]["book title"]["name"]""")
        assertEquals("样本", values.single().asString)
    }

    @Test
    fun `JSONPath 支持 Unicode 转义的属性名`() {
        val root = JsonParser.parseString("""{"key name":"转义值"}""")
        val values = LegadoRuleEngine.selectJsonList(root, """$['key\u0020name']""")
        assertEquals("转义值", values.single().asString)
    }

    @Test
    fun `JSONPath 对不支持的过滤表达式返回空列表`() {
        val root = JsonParser.parseString("""{"items":[{"id":1},{"id":2}]}""")
        assertTrue(LegadoRuleEngine.selectJsonList(root, "$.items[?(@.id==2)]").isEmpty())
    }

    @Test
    fun `XPath 目录地址选择器提取 href 属性`() {
        val html = """<html><body>
            <a class="read start" href="/chapter/155711">免费试读</a>
            <a class="read" href="/book/155711/14051836">继续阅读</a>
        </body></html>"""
        val rule = """//*[@class="read start"]/@href"""

        assertEquals(
            "/chapter/155711",
            LegadoRuleEngine.extractString(html, rule, "https://www.heiyan.com/book/155711"),
        )
        assertEquals(
            listOf("/chapter/155711"),
            LegadoRuleEngine.extractStringList(html, rule, "https://www.heiyan.com/book/155711"),
        )
    }

    @Test
    fun `XPath 文本选择器提取 text 节点`() {
        val html = """<html><body><a class="read" href="/chapter/1">第一章</a></body></html>"""
        assertEquals("第一章", LegadoRuleEngine.extractString(html, """//a[@class="read"]/text()"""))
    }

    private fun adapter(): LegadoSourceAdapter {
        val src = LegadoBookSource.parseOne(
            JsonObject().apply {
                addProperty("bookSourceName", "离线小说测试源")
                addProperty("bookSourceUrl", "https://example.com")
                addProperty("bookSourceType", 0)
            }.toString(),
        )
        val info = SourceInfo(
            id = "test_${src.name.hashCode()}",
            name = src.name,
            type = SourceType.NOVEL,
            baseUrl = src.url,
            format = SourceFormat.LEGADO,
        )
        return LegadoSourceAdapter(info, src)
    }

    // ===== #126 目录地址（tocUrl）=====

    /** 中文书城：目录地址是 `{{$.bookId}}` 模板，详情页为 JSON */
    @Test
    fun `tocUrl - JSON 详情的 bookId 模板`() {
        val detail = """{"bookId":"12345","bookName":"测试书籍"}"""
        val url = adapter().tocUrlForTest(
            "https://readbook-service-freebook.cread.com/cx/itf/getvolume?bookId={{$.bookId}}",
            detail,
            "https://example.com/book/1",
        )
        assertEquals(
            "https://readbook-service-freebook.cread.com/cx/itf/getvolume?bookId=12345",
            url,
        )
    }

    /** 笔趣阁（api 站）：模板字段在 `data` 包装内层 → 根取不到再试 data */
    @Test
    fun `tocUrl - 模板字段在 data 包装内层时回退取值`() {
        val detail = """{"code":0,"data":{"novelId":"888"}}"""
        val url = adapter().tocUrlForTest(
            "http://api.wzyjxf.com/novel/{{$.novelId}}/chapters",
            detail,
            "https://example.com/book/2",
        )
        assertEquals("http://api.wzyjxf.com/novel/888/chapters", url)
    }

    /**
     * 米读小说：整条 `@js:` 目录地址。`{{$.book_id}}` 模板必须先渲染再执行 JS，
     * 否则 JS 里是字面量 `{{$.book_id}}` → 目录地址错乱。
     * try 分支成功走主站；catch 分支（book_spare）留作备用。
     */
    @Test
    fun `tocUrl - 整条 @js 带 try-catch 与 {{}} 模板先渲染再执行`() {
        val rule = """
@js:
try{
"https://book.midureader.com/book/chapter_list/100/{{$.book_id}}.txt"
}
catch(e){
	"https://book-spare.midureader.com/book/chapter_list/100/{{$.book_id}}.txt"
	}
""".trim()
        val detail = """{"book_id":"42","book_name":"测试"}"""
        val url = adapter().tocUrlForTest(rule, detail, "https://example.com/book/3")
        assertEquals("https://book.midureader.com/book/chapter_list/100/42.txt", url)
    }

    /**
     * 海棠书屋：整条 `@js:` 目录地址（原文）。章节数 ≤9 时目录页就是详情页本身
     * （`else{baseUrl}` 分支）；>9 分支要 `java.getVerificationCode` 联网取验证码，
     * 不在离线单测里执行（由 #129 的联网诊断覆盖）。
     */
    @Test
    fun `tocUrl - 整条 @js 章节数不超过9时返回详情页地址（海棠书屋）`() {
        val rule = """
@js:
index = result.match(/全部章节列表...\(共(\d+)章节\)/)[1];
if(index>9){
aid=result.match(/javascript:goChapterList\((\d+)\)/)[1];

url="https://www.beiyongzhan1.com/mytool/getChapterList/";
verify=java.getVerificationCode(book.origin+"/mytool/getVerify/?d="+Math.random());
body="verify="+verify+"&aid="+aid+"&sbt=提交";
option={
	"method":"POST",
	"body":String(body)
	}
result=url+","+JSON.stringify(option)
}else{baseUrl}
""".trim()
        val detail = """<html><body><div id="all">全部章节列表，点此(共5章节)</div></body></html>"""
        val detailUrl = "https://www.example.com/book/5678"
        assertEquals(detailUrl, adapter().tocUrlForTest(rule, detail, detailUrl))
    }

    /** 笔趣阁（mm 站）：选择器取值后按 `##正则##替换` 改写目录地址 */
    @Test
    fun `tocUrl - 选择器取值后按 ##正则##替换 改写`() {
        val rule = "class.lb_mulu chapterList@tag.li.0@a@href" +
            "##/novel/(\\d+)/(\\d+).html##https://mm.xyuzhaiwu.xyz/novel/list/\$1/1.html"
        val detail = """<html><body><div class="lb_mulu chapterList">""" +
            """<li><a href="/novel/555/666.html">目录</a></li></div></body></html>"""
        val url = adapter().tocUrlForTest(rule, detail, "https://www.example.com/novel/555/666.html")
        assertEquals("https://mm.xyuzhaiwu.xyz/novel/list/555/1.html", url)
    }

    // ===== #127 列表规则 JS =====

    /**
     * 中文万维：整条 `@js:` 目录规则（原文）。
     * JS 用 `JSON.parse(result)` 自己解析目录 JSON，产出对象数组（含
     * `📖[卷名]📖` 卷标项）。引擎必须：NativeArray → JSON 化 → 逐项按 JSON
     * 字段规则取 → 跳过卷标项（`volume: true`）。
     * 回归点：修复前整条规则被当成普通 JSON 路径 → 目录恒为 0 章。
     */
    @Test
    fun `列表JS - 整条 @js 产出对象数组且跳过卷标项（中文万维）`() {
        val content = """{"list":[
        {"name":"第一卷","bookChapters":[
            {"name":"第一章 开端","bookid":"1001","id":"2001","wordCount":"3200","updateDate":"2024-01-01"},
            {"name":"第二章 发展","bookid":"1001","id":"2002","wordCount":"3100","updateDate":"2024-01-02"}]},
        {"name":"第二卷","bookChapters":[
            {"name":"第三章 高潮","bookid":"1001","id":"2003","wordCount":"3300","updateDate":"2024-01-03"}]}]}"""
        val rule = """
@js:
let v = [],
		list = [];
JSON.parse(result).list.map($=>{

//分卷判定
		V = $.name;
		if(v[v.length-1]!=V&&!/^\s*$/.test(V)){
				v.push(V)
				list.push({
						name: '📖['+V+']📖',
						volume: true
					})
			}

$.bookChapters.map($=>{
		return list.push({
				name: $.name,
				url: `https://readbook-service-freebook.cread.com/cx/itf/chapterRead?bookId=${'$'}{$.bookid}&chapterId=${'$'}{$.id}`,
				info: `章节字数：${'$'}{$.wordCount}　更新时间：${'$'}{$.updateDate}`
			});
	});
});
v.length<2?list.filter($=>!$.volume):list
""".trim()
        val fields = mapOf("chapterName" to "name", "chapterUrl" to "url")
        val got = adapter().jsonListForTest(content, fields, rule)
        assertEquals(3, got.size)
        assertEquals(
            listOf("第一章 开端", "第二章 发展", "第三章 高潮"),
            got.map { it["chapterName"] },
        )
        assertEquals(
            listOf(
                "https://readbook-service-freebook.cread.com/cx/itf/chapterRead?bookId=1001&chapterId=2001",
                "https://readbook-service-freebook.cread.com/cx/itf/chapterRead?bookId=1001&chapterId=2002",
                "https://readbook-service-freebook.cread.com/cx/itf/chapterRead?bookId=1001&chapterId=2003",
            ),
            got.map { it["chapterUrl"] },
        )
    }

    /**
     * 安稳小说：JSON 路径 + 尾部 `<js>`（原文）。
     * 尾部 JS 借 `java.put` 把正文地址前缀写进变量表，`chapterUrl` 字段规则
     * （整条 `<js>`）再 `java.get` 读回来拼章节地址。
     * 回归点：修复前尾部 JS 段不执行 → volumeList 节点被当成普通数组、
     * 变量表为空 → 章节地址缺前缀 → 正文请求失败。
     */
    @Test
    fun `列表JS - JSON 路径加尾部 js 借 java put 传变量（安稳小说）`() {
        val content = """{"data":{
            "freeContUrlPrefix":"https://free.example.com/",
            "shortContUrlPrefix":"https://short.example.com/",
            "chapterList":[{"volumeList":[
                {"chapterName":"第1话 起点","contUrlSuffix":"cx/1"},
                {"chapterName":"第2话 发展","contUrlSuffix":"cx/2"}]}]}}"""
        val rule = "${'$'}.data.chapterList[0].volumeList" +
            "<js>java.put('freeUrlPre',java.getString('${'$'}.data.freeContUrlPrefix'));" +
            "java.put('shortUrlPre',java.getString('${'$'}.data.shortContUrlPrefix'));result</js>"
        val fields = mapOf(
            "chapterName" to "chapterName",
            "chapterUrl" to
                "<js>var l=java.getString('${'$'}.contUrlSuffix');" +
                "if(l.indexOf('reqEncryptParam')==-1){java.get('freeUrlPre')+l}" +
                "else{java.get('shortUrlPre')+java.getString('${'$'}.shortContUrlSuffix')}</js>",
        )
        // 变量表隔离：put 写的变量只在本用例可见
        val got = LegadoRuleEngine.withVariables(HashMap()) {
            adapter().jsonListForTest(content, fields, rule)
        }
        assertEquals(2, got.size)
        assertEquals(listOf("第1话 起点", "第2话 发展"), got.map { it["chapterName"] })
        assertEquals(
            listOf("https://free.example.com/cx/1", "https://free.example.com/cx/2"),
            got.map { it["chapterUrl"] },
        )
    }

    /**
     * 轻菠萝包：`+<js>` 前缀的纯桥解析目录（原文）。
     * JS 全程用 `java.getStringList/getElements/setContent/getString` 读页面，
     * 产出对象数组，并插入 `📖[卷名]📖` 卷头（`volsName: true`）——引擎必须跳过。
     * 回归点：修复前 `+` 前缀与整条 JS 都不被识别 → 目录恒为空。
     */
    @Test
    fun `列表JS - +js 前缀桥解析且跳过卷头（轻菠萝包）`() {
        val page = """
<html><body>
<div class="mulu">第一卷</div>
<div class="mulu">第二卷</div>
<div class="mulu_list">
<a href="/book/1/c1.html">第一章 相遇</a>
<a href="/book/1/c2.html">第二章 相知</a>
</div>
<div class="mulu_list">
<a href="/book/2/c3.html">第三章 相离</a>
</div>
</body></html>
""".trimIndent()
        val rule = """
+<js>
var rules = {
  volsName: '//*[@class="mulu"]/text()',
  volumes: 'class.mulu_list',
  chapters: 'tag.a',
  chName: 'text',
  chLink: 'href',
  vip: 'class.icon-lock2'
};
var opts = {
  showVolName: false
};

function chapterLevel(rules, opts){
  var check = (rule) => rule !== undefined && rule !== '' ? rule : '@xpath:/x[@x="" and @x!=""]';
  rules.volInfo = check(rules.volInfo);
  rules.vip = check(rules.vip);
  opts.showVolName = opts.showVolName === undefined ? true : !!opts.showVolName;

  var NL = '<br/>', NP = "<br/>\uFEFF<br/>";
  var list = [];
  var volsName = java.getStringList(rules.volsName).toArray();

  java.getElements(rules.volumes).toArray().forEach((vol,idx) => {
    java.setContent(vol);
    var volInfo = '简介：' + NL + java.getStringList(rules.volInfo).toArray().join(NL);
    var chs = java.getElements(rules.chapters).toArray().map(ch => {
      java.setContent(ch);
      var isVip = !java.getElements(rules.vip).isEmpty();
      var chName = java.getString(rules.chName);
      return {
        text: (isVip ? '✿·' : '').padStart(0, "\u2000")+(opts.showVolName ? volsName[idx]+"\x20" : '')+chName,
        href: java.getString(rules.chLink),
        name: chName

      };
    });

    volInfo += NP + '目录(' + chs.length + ')：' + NL;
    volInfo += chs.slice(0,10).map(v => v.name).join(NL);
    volInfo += chs.length > 10 ? NL + '……' : '';
    volInfo = volInfo.replace(/(\s)\1+/g, '$1');
    list.push({
      text: '📖['+volsName[idx]+']📖',
      volsName:true,
      href: 'https://translate.google.cn/#view=home&op=translate&sl=zh-CN&tl=en&text='+encodeURIComponent(volInfo)
    });
    list = list.concat(chs);
  });

  return list;
}

chapterLevel(rules, opts);
</js>
""".trim()
        val fields = mapOf("chapterName" to "${'$'}.text", "chapterUrl" to "${'$'}.href")
        val got = adapter().htmlListViaJsForTest(page, fields, rule, "https://example.com/mulu")
        assertNotNull("JS 列表规则应产出内容", got)
        assertEquals(3, got!!.size)
        // 源里 opts.showVolName = false → text 只含章节名（卷名只出现在被跳过的卷头项里）
        assertEquals(
            listOf("第一章 相遇", "第二章 相知", "第三章 相离"),
            got.map { it["chapterName"] },
        )
        assertEquals(
            listOf("/book/1/c1.html", "/book/1/c2.html", "/book/2/c3.html"),
            got.map { it["chapterUrl"] },
        )
    }

    /**
     * 海棠书屋：选择器 + 尾部 `<js>`（原文）。
     * 「请输入验证码」分支把选择器结果**反序拼接**成 HTML 再重新拆出链接
     * （站点目录是倒序返回的）；其余分支原样接力。
     * 回归点：修复前尾部 JS 不执行 → 验证码分支的链接重组丢失。
     */
    @Test
    fun `列表JS - 选择器加尾部 js 验证码反序重组（海棠书屋）`() {
        val rule = """
class.list.0@li@a||li@a
<js>
if(String(src).match(/验证码不对/)){
result=[{"text":"右上角三点刷新重新获取验证码"}]
}else if(src.match(/请输入验证码/)){	java.setContent(result.toArray().reverse().join(""))
result = java.getElement("a")
}
else{result=result}
</js>
""".trim()
        val fields = mapOf("chapterName" to "text", "chapterUrl" to "href")

        // 正常分支：结果原样接力
        val normalPage = """<html><body><div class="list">""" +
            """<li><a href="/1/1.html">第一章</a></li>""" +
            """<li><a href="/1/2.html">第二章</a></li></div></body></html>"""
        val normal = adapter().htmlListViaJsForTest(normalPage, fields, rule, "https://www.example.com/")
        assertNotNull(normal)
        assertEquals(listOf("第一章", "第二章"), normal!!.map { it["chapterName"] })
        assertEquals(listOf("/1/1.html", "/1/2.html"), normal.map { it["chapterUrl"] })

        // 验证码分支：反序拼接后重新拆链（页面是乙→甲，重组后取到甲）
        val captchaPage = """<html><body><div class="tip">请输入验证码</div><div class="list">""" +
            """<li><a href="/b/2.html">乙章</a></li>""" +
            """<li><a href="/a/1.html">甲章</a></li></div></body></html>"""
        val captcha = adapter().htmlListViaJsForTest(captchaPage, fields, rule, "https://www.example.com/")
        assertNotNull(captcha)
        assertEquals(1, captcha!!.size)
        assertEquals("甲章", captcha[0]["chapterName"])
        assertEquals("/a/1.html", captcha[0]["chapterUrl"])

        // 「验证码不对」分支：JS 直接给出提示项（不是章节，但不应中断解析）
        val wrongPage = """<html><body><div class="tip">验证码不对</div><div class="list">""" +
            """<li><a href="/1/1.html">第一章</a></li></div></body></html>"""
        val wrong = adapter().htmlListViaJsForTest(wrongPage, fields, rule, "https://www.example.com/")
        assertNotNull(wrong)
        assertEquals("右上角三点刷新重新获取验证码", wrong!![0]["chapterName"])
    }

    // ===== #128 java 桥 =====

    /**
     * `java.getString` 对 JSON 内容的路径读取：
     * - 单值路径直接取字符串；
     * - `[*]` 中段投影两层拍平（`rows[*].name`）；
     * - 终态数组无 `[*]` 时按元素换行拼接（legado 的 `result.split("\n")` 习惯）；
     * - 取不到返回空串而不是抛异常（不能中断整条书源）。
     */
    @Test
    fun `java 桥 - JSON 路径读取、星号投影两层拍平与终态数组取元素`() {
        val json = """{"a":{"b":"deep"},"rows":[{"name":"甲"},{"name":"乙"}],"tags":["红","蓝"]}"""
        val bridge = LegadoJavaBridge(json, null, "")
        assertEquals("deep", bridge.getString("$.a.b"))
        assertEquals("甲\n乙", bridge.getString("$.rows[*].name"))
        assertEquals("红\n蓝", bridge.getString("$.tags"))
        assertEquals("", bridge.getString("$.missing"))
    }

    /** `java.getString` 无参时返回当前内容原文（`setContent` 后的元素文本） */
    @Test
    fun `java 桥 - setContent 切到元素后 getString 读元素文本`() {
        val page = """<html><body><div class="vol"><a href="/c/1.html">第一章</a></div></body></html>"""
        val bridge = LegadoJavaBridge(page, null, "")
        val vol = bridge.getElements("class.vol").first()
        assertNotNull(vol)
        bridge.setContent(vol)
        assertEquals("第一章", bridge.getString("text"))
        assertEquals("第一章", bridge.getString())
        // 再切到子元素读属性（轻菠萝包目录 JS 的真实用法：setContent(a) 后 getString('href')）
        val link = bridge.getElements("tag.a").first()
        assertNotNull(link)
        bridge.setContent(link)
        assertEquals("/c/1.html", bridge.getString("href"))
        assertEquals("第一章", bridge.getString())
    }

    // ===== #129 失效源修复回归 =====

    /**
     * 安稳小说 bookUrl：`$.bid` + 尾部 `<js>`（原文）。
     * 尾部 JS 借 `java.put` 把 bid 写进条目变量表，再拼 UC 书页地址。
     * 回归点：修复前尾部 JS 段不执行，整条被当 JSONPath → bookUrl 为空 →
     * 详情/目录全废（diag 显示 detailUrl=""）。
     */
    @Test
    fun `字段规则 - JSON 路径加尾部 js 借 java put 写变量再拼地址（安稳小说 bookUrl）`() {
        val content = """{"data":[{"title":"斗破苍穹，妖后惑天下","bid":"7478041"}]}"""
        val fields = mapOf(
            "bookUrl" to "\$.bid" +
                "<js>java.put('bid',result);'http://xiaoshuo.uc.cn/#!/ct/cover/bid/'+result</js>",
            // 同一批次里回读变量：验证 put 的写入按条目可见
            "bidBack" to "<js>java.get('bid')</js>",
        )
        val got = adapter().jsonListForTest(content, fields, "\$.data[*]")
        assertEquals(1, got.size)
        assertEquals("http://xiaoshuo.uc.cn/#!/ct/cover/bid/7478041", got[0]["bookUrl"])
        assertEquals("7478041", got[0]["bidBack"])
    }

    /**
     * 红薯阅读 bookUrl：静态地址 + 多行单引号 JSON 请求配置（原文）。
     * 回归点：修复前多行规则被链式切分，配置每一行当成独立规则段，最后只剩
     * `'body': 'bid=47999'` —— detailUrl 变成 `https://host/'body':%20'bid=47999'`。
     */
    @Test
    fun `字段规则 - 多行 地址加请求配置 不被按行切分（红薯阅读 bookUrl）`() {
        val item = """{"catename":"玄破苍穹","bid":"47999"}"""
        val rule = "https://androidv2.hongshu.com/clientGetBookInfo?version=1.5.2&P27=5.1.3," +
            "{'method': 'POST',\n'body': 'bid={{$.bid}}'\n}"
        val got = adapter().jsonFieldForTest(item, rule)
        assertEquals(
            "https://androidv2.hongshu.com/clientGetBookInfo?version=1.5.2&P27=5.1.3," +
                "{'method': 'POST',\n'body': 'bid=47999'\n}",
            got,
        )
    }

    /** 多段链式规则（企鹅阅读形态）不受「请求配置剥离」影响 */
    @Test
    fun `字段规则 - 多段链式规则仍然有效`() {
        val item = """{"bid":"555"}"""
        val rule = "\$.bid\n<js>1100000000+parseInt(result)</js>\nhttps://example.com/intro?bookid={{result}}"
        val got = adapter().jsonFieldForTest(item, rule)
        assertEquals("https://example.com/intro?bookid=1100000555", got)
    }

    /** 静态 URL 字面量字段规则（不含占位符）按 legado 语义原样返回 */
    @Test
    fun `字段规则 - 静态 URL 字面量原样返回`() {
        val item = """{"catename":"某书"}"""
        val rule = "https://androidv2.hongshu.com/clientGetBookInfo?version=1.5.2&P27=5.1.3"
        assertEquals(rule, adapter().jsonFieldForTest(item, rule))
    }

    /**
     * 爱下电子书 tocUrl：POST 请求配置的 body 里用 `{{baseUrl.match(...)}}`
     * 从详情地址取 bid（原文）。
     * 回归点：修复前表达式原样留在 body 里 → 服务端返回 `{"rs":200,"data":[]}`
     * 空目录（diag 实测 rawTocHead 正是这 20 字节）。
     */
    @Test
    fun `tocUrl - POST 配置 body 里的 baseUrl 正则取 bid（爱下电子书）`() {
        val rule = "https://ixdzs8.com/novel/clist/,{\n" +
            "  \"body\": \"bid={{baseUrl.match(/\\/(\\d+)/)[1]}}\",\n" +
            "  \"method\": \"POST\"\n}"
        val url = adapter().tocUrlForTest(
            rule,
            "<html><body>详情页</body></html>",
            "https://ixdzs8.com/read/383523/",
        )
        assertEquals(
            "https://ixdzs8.com/novel/clist/,{\n" +
                "  \"body\": \"bid=383523\",\n" +
                "  \"method\": \"POST\"\n}",
            url,
        )
    }

    /**
     * 安稳小说 tocUrl：init JS 产出 `{'turl': 目录接口}` 后，tocUrl 规则写裸键 `turl`。
     * 回归点：修复前裸键被当选择器在详情页里找 → 恒为空 → 目录 0 章。
     */
    @Test
    fun `tocUrl - 裸键规则先查 init 并入的变量表（安稳小说 turl）`() {
        val turl = "https://ocean.shuqireader.com/api/bcspub/qswebapi/book/chapterlist" +
            "?_=&bookId=7478041&user_id=8000000&sign=dc9018fa&timestamp=1792292924"
        val url = adapter().tocUrlForTest(
            "turl",
            "<html><body>UC 书页 SPA 壳</body></html>",
            "http://xiaoshuo.uc.cn/",
            mapOf("bid" to "7478041", "turl" to turl),
        )
        assertEquals(turl, url)
    }

    /** 裸键不在变量表时保持原有选择器语义（不影响其他源） */
    @Test
    fun `tocUrl - 裸键不在变量表时仍按选择器解析`() {
        val detail = """<html><body><div class="mulu"><a href="/dir/1/">目录</a></div></body></html>"""
        val url = adapter().tocUrlForTest(
            "class.mulu@tag.a@href",
            detail,
            "https://example.com/book/1",
            mapOf("turl" to "https://other.example/"),
        )
        assertEquals("/dir/1/", url)
    }

    /**
     * 去读书啊 bookUrl：搜索行 4 个 `<a>`，`tag.a@href` 按 legado 语义换行拼接。
     * URL 字段必须取首行 —— 修复前整串被 sanitizeUrl 百分号编码（`%0A`）后请求，
     * 服务端返回 1 字节错误页，详情/目录全废。
     */
    @Test
    fun `URL 字段 - 多匹配拼接串取首行（去读书啊 bookUrl）`() {
        val joined = "http://www.qudushu.org/book/1275/1275271.html\n" +
            "http://www.qudushu.org/html/1275/1275271/\n" +
            "http://www.qudushu.org/read/1275/1275271.html"
        assertEquals(
            "http://www.qudushu.org/book/1275/1275271.html",
            LegadoRuleEngine.toAbsoluteUrl("http://www.qudushu.org", joined),
        )
        // 相对地址的首行也要能正确解析（`new URL(base, "/a\n/b")` 会把整串当路径）
        assertEquals(
            "http://www.quduwx.net/html/1275/1275271/",
            LegadoRuleEngine.toAbsoluteUrl(
                "http://www.quduwx.net/book/info/1275/1275271.html",
                "/html/1275/1275271/\nhttp://other.example/",
            ),
        )
    }

    /**
     * 去读书啊目录：站点 `<ul class="chapters">` 双层未闭合，jsoup 把第二个 ul
     * 嵌套进第一个。`class.index@li` 取唯一包裹容器的全部后代，每章恰好一次；
     * 修复前 `class.chapters@li` 因「每个容器各取一次后代」重复（1914+94+1569+251）。
     */
    @Test
    fun `目录列表 - 双层未闭合 ul 下取包裹容器不重复（去读书啊）`() {
        val page = """
<html><body>
<div class="index">
<div class="volume">第一卷 沙漠秘境</div>
<ul class="chapters">
<li><a href="/html/1275/1275271/1.html">第一章 陨落的天才</a></li>
<li><a href="/html/1275/1275271/2.html">第二章 斗气大陆</a></li>
<ul class="chapters">
<li><a href="/html/1275/1275271/3.html">第三章 魂殿</a></li>
<li><a href="/html/1275/1275271/4.html">第四章 薰儿</a></li>
</ul>
</ul>
</div>
</body></html>
        """.trimIndent()
        val fields = mapOf("chapterName" to "text", "chapterUrl" to "tag.a@href")
        val list = LegadoRuleEngine.extractList(page, "class.index@li", fields)
        assertEquals(4, list.size)
        assertEquals(
            listOf("第一章 陨落的天才", "第二章 斗气大陆", "第三章 魂殿", "第四章 薰儿"),
            list.map { it["chapterName"] },
        )
        assertEquals(
            listOf(
                "/html/1275/1275271/1.html",
                "/html/1275/1275271/2.html",
                "/html/1275/1275271/3.html",
                "/html/1275/1275271/4.html",
            ),
            list.map { it["chapterUrl"] },
        )
    }
}
