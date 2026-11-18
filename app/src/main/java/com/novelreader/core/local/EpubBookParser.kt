package com.novelreader.core.local

import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode
import org.w3c.dom.Document
import org.w3c.dom.Node as DomNode
import org.w3c.dom.NodeList
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.charset.Charset
import java.util.zip.ZipInputStream
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element as DomElement

/**
 * EPUB 解析器（B9 · 本地 EPUB 导入）。
 *
 * EPUB 本质是「zip 包 + 一堆 XML」，所以这里**不引第三方 EPUB 库**，
 * 只用标准库（[java.util.zip.ZipInputStream] + [javax.xml.parsers]）+ 项目已有的 Jsoup：
 * - 引入 EPUB 库意味着多一个依赖与一份体积，收益却只有「省几十行解析代码」，不值；
 * - 而纯标准库实现可以做成**纯逻辑**：输入字节流、输出数据类，不碰任何 Android API，
 *   测试里用 [java.util.zip.ZipOutputStream] 现造一个最小 EPUB 就能验证。
 *
 * 解析链路：
 * ```
 * META-INF/container.xml → rootfile@full-path → OPF
 * OPF → metadata（书名 / 作者）/ manifest（id → href）/ spine（章节顺序）
 * 章节正文 = manifest[spine[i]].href 对应的 xhtml → 抽纯文本
 * 章节标题 = nav / NCX 目录里的标题 → 退化为正文第一个 heading → 再退化为文件名
 * ```
 *
 * 健壮性约定（**绝不抛未捕获异常**）：缺 container.xml、OPF 损坏、zip 为空、
 * 章节文件缺失等一律返回 `null` 或跳过该章，由调用方决定如何提示用户。
 */
object EpubBookParser {

    /** 解析出的一本书 */
    data class EpubBook(
        val title: String,
        val author: String,
        val chapters: List<EpubChapter>,
    )

    /** 一个章节：标题 + 在 OPF 里的原始 href + 抽好的正文纯文本 */
    data class EpubChapter(
        val title: String,
        val href: String,
        val text: String,
    )

    /** container.xml 在包内的固定路径 */
    private const val CONTAINER_PATH = "META-INF/container.xml"

    /** 单个文本条目的读取上限（8MB）：正文文件远小于此，超过说明不是正文 */
    private const val MAX_ENTRY_BYTES = 8 * 1024 * 1024

    /** 这些扩展名是图片 / 字体 / 音视频——EPUB 体积的大头，正文用不上，直接丢弃 */
    private val BINARY_EXTENSIONS = setOf(
        "jpg", "jpeg", "png", "gif", "webp", "bmp", "tif", "tiff", "svg", "ico",
        "ttf", "otf", "woff", "woff2", "eot",
        "mp3", "mp4", "m4a", "m4v", "ogg", "oga", "wav", "flac", "webm", "m3u8",
        "css", "zip", "epub", "js", "json", "db", "opf2",
    )

    /** 块级标签：这些标签前后要断行，否则整章正文会糊成一大坨 */
    private val BLOCK_TAGS = setOf(
        "p", "div", "br", "hr", "li", "ul", "ol", "dl", "dt", "dd",
        "h1", "h2", "h3", "h4", "h5", "h6", "blockquote", "pre", "section",
        "article", "aside", "header", "footer", "nav", "figure", "figcaption",
        "table", "tr", "td", "th", "caption", "center", "body", "main",
        "address", "form", "fieldset", "details", "summary", "dd", "dt",
    )

    /** 直接丢弃的标签：脚本 / 样式 / 二进制内嵌对象 */
    private val SKIP_TAGS = setOf(
        "script", "style", "noscript", "svg", "iframe", "object", "embed",
        "audio", "video", "canvas", "map", "head",
    )

    /** 连续空白（含全角空格）压缩成一个半角空格 */
    private val MULTI_SPACE = Regex("[\\t\\u00A0\\u3000 ]+")

    /** XML 声明里的 encoding：解析前要改写成 UTF-8（因为我们已自行解码过） */
    private val XML_DECL_ENCODING =
        Regex("""(<\?xml[^>]*?encoding\s*=\s*["'])([^"']+)(["'])""", RegexOption.IGNORE_CASE)

    /**
     * 解析 EPUB 字节流。
     *
     * @return 解析结果；失败返回 `null`（不抛异常）
     */
    fun parse(bytes: ByteArray): EpubBook? = parse(ByteArrayInputStream(bytes))

    /**
     * 解析 EPUB 输入流（读完即关闭由调用方负责，本方法内部会关）。
     *
     * @return 解析结果；失败返回 `null`（不抛异常）
     */
    fun parse(stream: InputStream): EpubBook? = runCatching { parseInternal(stream) }.getOrNull()

    // ------------------------------------------------------------------
    // 内部实现
    // ------------------------------------------------------------------

    private fun parseInternal(stream: InputStream): EpubBook? {
        val entries = readEntries(stream)
        if (entries.isEmpty()) return null

        val containerBytes = entries[CONTAINER_PATH] ?: return null
        val opfPath = findRootFile(containerBytes) ?: return null
        val opfBytes = entries[opfPath] ?: return null
        val opfDoc = parseXml(opfBytes) ?: return null

        val baseDir = opfPath.substringBeforeLast('/', "")
        val manifest = parseManifest(opfDoc)
        val spine = parseSpine(opfDoc)
        if (manifest.isEmpty() || spine.isEmpty()) return null

        val tocTitles = parseTocTitles(entries, manifest, baseDir, opfDoc)

        val chapters = mutableListOf<EpubChapter>()
        spine.forEach { idref ->
            val item = manifest[idref] ?: return@forEach
            val path = resolvePath(baseDir, item.href) ?: return@forEach
            // 章节文件缺失：跳过这一章（不整本失败），其余章节照常可读
            val bytes = entries[path] ?: return@forEach
            val html = decodeText(bytes)
            val title = tocTitles[path]
                ?: extractHeading(html)
                ?: fileTitle(path)
            chapters += EpubChapter(title = title, href = item.href, text = extractText(html, title))
        }
        if (chapters.isEmpty()) return null

        return EpubBook(
            title = extractMetadata(opfDoc, "title"),
            author = extractMetadata(opfDoc, "creator"),
            chapters = chapters,
        )
    }

    /**
     * 读取 zip 条目。
     *
     * 只保留文本类条目：图片 / 字体 / 音视频占 EPUB 体积的九成以上，
     * 而正文解析根本用不到，全量读进内存既慢又容易 OOM。
     */
    private fun readEntries(stream: InputStream): Map<String, ByteArray> {
        val entries = LinkedHashMap<String, ByteArray>()
        ZipInputStream(stream.buffered()).use { zip ->
            while (true) {
                val entry = try {
                    zip.nextEntry
                } catch (e: Exception) {
                    break
                } ?: break
                try {
                    if (!entry.isDirectory) {
                        val name = normalizeEntryName(entry.name)
                        if (!isBinary(name)) {
                            readCapped(zip, MAX_ENTRY_BYTES)?.let { entries[name] = it }
                        } else {
                            drain(zip)
                        }
                    }
                } catch (e: Exception) {
                    // 单个条目损坏不应中断整本书的解析
                } finally {
                    runCatching { zip.closeEntry() }
                }
            }
        }
        return entries
    }

    /** 读取单个条目，超出上限则丢弃（返回 null） */
    private fun readCapped(zip: ZipInputStream, cap: Int): ByteArray? {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(8 * 1024)
        var total = 0
        while (true) {
            val n = try {
                zip.read(buf)
            } catch (e: Exception) {
                break
            }
            if (n <= 0) break
            total += n
            if (total > cap) return null
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }

    /** 把二进制条目读完丢弃（保持 zip 流位置正确） */
    private fun drain(zip: ZipInputStream) {
        val buf = ByteArray(8 * 1024)
        while (true) {
            val n = try {
                zip.read(buf)
            } catch (e: Exception) {
                break
            }
            if (n <= 0) break
        }
    }

    /** 条目名归一化：去掉前导 `./`、统一正斜杠 */
    private fun normalizeEntryName(raw: String): String =
        raw.replace('\\', '/').removePrefix("./").trimStart('/')

    private fun isBinary(name: String): Boolean {
        val ext = name.substringAfterLast('.', "").lowercase()
        return ext in BINARY_EXTENSIONS
    }

    /** container.xml → OPF 的包内路径 */
    private fun findRootFile(containerBytes: ByteArray): String? {
        val doc = parseXml(containerBytes) ?: return null
        elementsByLocalName(doc, "rootfile").forEach { el ->
            val path = el.getAttribute("full-path").trim()
            if (path.isNotEmpty()) return normalizeEntryName(path)
        }
        return null
    }

    /** manifest：id → (href, mediaType, properties) */
    private data class ManifestItem(
        val href: String,
        val mediaType: String,
        val properties: String,
    )

    private fun parseManifest(doc: Document): Map<String, ManifestItem> {
        val result = mutableMapOf<String, ManifestItem>()
        elementsByLocalName(doc, "item").forEach { el ->
            val id = el.getAttribute("id").trim()
            val href = el.getAttribute("href").trim()
            if (id.isEmpty() || href.isEmpty()) return@forEach
            result[id] = ManifestItem(
                href = href,
                mediaType = el.getAttribute("media-type").trim(),
                properties = el.getAttribute("properties").trim(),
            )
        }
        return result
    }

    /** spine：章节顺序（itemref 的 idref 列表） */
    private fun parseSpine(doc: Document): List<String> =
        elementsByLocalName(doc, "itemref")
            .mapNotNull { it.getAttribute("idref").trim().takeIf { id -> id.isNotEmpty() } }

    /**
     * 目录标题表：章节包内路径 → 标题。
     *
     * 先找 EPUB2 的 NCX（`spine@toc` 指向的条目或 media-type 命中的），
     * 再找 EPUB3 的 nav 文档（manifest 里 `properties` 含 `nav`）。
     * 两者都是可选的：都没有时靠正文里的第一个 heading 兜底。
     */
    private fun parseTocTitles(
        entries: Map<String, ByteArray>,
        manifest: Map<String, ManifestItem>,
        baseDir: String,
        opfDoc: Document,
    ): Map<String, String> {
        val titles = mutableMapOf<String, String>()

        // --- EPUB2：NCX ---
        val ncxCandidates = buildList {
            val tocId = elementsByLocalName(opfDoc, "spine").firstOrNull()
                ?.getAttribute("toc")?.trim().orEmpty()
            if (tocId.isNotEmpty()) manifest[tocId]?.let { add(it) }
            manifest.values
                .filter { it.mediaType.equals("application/x-dtbncx+xml", true) }
                .forEach { add(it) }
            manifest.values
                .filter { it.href.endsWith(".ncx", ignoreCase = true) }
                .forEach { add(it) }
        }
        ncxCandidates.forEach { item ->
            val path = resolvePath(baseDir, item.href) ?: return@forEach
            val bytes = entries[path] ?: return@forEach
            val doc = parseXml(bytes) ?: return@forEach
            val ncxDir = path.substringBeforeLast('/', "")
            elementsByLocalName(doc, "navPoint").forEach { point ->
                val src = elementsByLocalName(point, "content").firstOrNull()
                    ?.getAttribute("src")?.trim().orEmpty()
                if (src.isEmpty()) return@forEach
                val label = elementsByLocalName(point, "navLabel").firstOrNull()
                    ?.let { navLabel ->
                        elementsByLocalName(navLabel, "text").firstOrNull()?.textContent?.trim()
                    }
                    .orEmpty()
                val key = resolvePath(ncxDir, src) ?: return@forEach
                if (label.isNotEmpty() && !titles.containsKey(key)) titles[key] = label
            }
        }

        // --- EPUB3：nav 文档 ---
        manifest.values
            .filter { item ->
                item.properties.split(Regex("\\s+")).any { it.equals("nav", true) } ||
                    item.href.endsWith("nav.xhtml", ignoreCase = true) ||
                    item.href.endsWith("toc.xhtml", ignoreCase = true)
            }
            .forEach { item ->
                val path = resolvePath(baseDir, item.href) ?: return@forEach
                val bytes = entries[path] ?: return@forEach
                val doc = Jsoup.parse(decodeText(bytes))
                val nav = doc.select("nav").firstOrNull { navEl ->
                    navEl.attr("epub:type").contains("toc", true) ||
                        navEl.select("a[href]").isNotEmpty()
                } ?: return@forEach
                val navDir = path.substringBeforeLast('/', "")
                nav.select("a[href]").forEach { a ->
                    val href = a.attr("href").trim()
                    if (href.isEmpty()) return@forEach
                    val key = resolvePath(navDir, href) ?: return@forEach
                    val label = a.text().trim()
                    if (label.isNotEmpty() && !titles.containsKey(key)) titles[key] = label
                }
            }

        return titles
    }

    /** metadata 里的书名 / 作者（dc:title / dc:creator，按 localName 匹配，不挑前缀） */
    private fun extractMetadata(doc: Document, localName: String): String {
        elementsByLocalName(doc, localName).forEach { el ->
            val text = el.textContent?.trim().orEmpty()
            if (text.isNotEmpty()) return text
        }
        return ""
    }

    /** 抽正文纯文本 */
    private fun extractText(html: String, title: String? = null): String {
        if (html.isBlank()) return ""
        val doc = Jsoup.parse(html)
        doc.select("script, style, noscript, svg, iframe, object, embed, head").remove()
        val sb = StringBuilder()
        doc.body()?.let { walk(it, sb) }
        return dropDuplicateHeading(normalizeText(sb.toString()), title)
    }

    /**
     * 去掉与章节标题重复的首行。
     *
     * EPUB 的正文首行通常是 `<h1>` 标题，而标题本身已经单独作为章节标题展示；
     * 不去掉的话读者会看到两遍一样的标题。
     */
    private fun dropDuplicateHeading(text: String, title: String?): String {
        if (title.isNullOrBlank() || text.isEmpty()) return text
        val lines = text.split('\n')
        if (lines.isEmpty()) return text
        if (!lines[0].trim().equals(title.trim(), ignoreCase = true)) return text
        return lines.drop(1).joinToString("\n")
    }

    /** 深度优先遍历，按块级标签断行 */
    private fun walk(node: Node, sb: StringBuilder) {
        for (child in node.childNodes()) {
            when {
                child is TextNode -> sb.append(child.wholeText)
                child is Element -> {
                    val tag = child.tagName().lowercase()
                    if (tag in SKIP_TAGS) continue
                    if (tag == "br") {
                        sb.append('\n')
                        continue
                    }
                    val block = tag in BLOCK_TAGS
                    if (block) sb.append('\n')
                    walk(child, sb)
                    if (block) sb.append('\n')
                }
            }
        }
    }

    /** 归一化：压缩空白、去掉空行、行间用 \n 分隔 */
    private fun normalizeText(raw: String): String {
        val sb = StringBuilder()
        raw.replace("\r\n", "\n").replace('\r', '\n').split('\n').forEach { line ->
            val cleaned = MULTI_SPACE.replace(line, " ").trim()
            if (cleaned.isEmpty()) return@forEach
            if (sb.isNotEmpty()) sb.append('\n')
            sb.append(cleaned)
        }
        return sb.toString()
    }

    /** 章节标题兜底：正文里的第一个 heading */
    private fun extractHeading(html: String): String? {
        if (html.isBlank()) return null
        val doc = Jsoup.parse(html)
        val heading = doc.selectFirst("h1, h2, h3, h4, h5, h6, title") ?: return null
        return heading.text().trim().takeIf { it.isNotEmpty() }
    }

    /** 章节标题兜底之二：文件名（去掉扩展名） */
    private fun fileTitle(path: String): String {
        val name = path.substringAfterLast('/')
        val title = name.substringBeforeLast('.', name).trim()
        return title.ifEmpty { "第 1 节" }
    }

    // ------------------------------------------------------------------
    // XML / 编码 / 路径
    // ------------------------------------------------------------------

    /**
     * 解析 XML。
     *
     * 外部实体一律禁用（EPUB 来自用户本地文件，但 XXE 仍不该留口子）：
     * 关闭 DTD 加载与实体展开即可挡住，DOCTYPE 本身保留——EPUB2 的 XHTML
     * 常常带 `&lt;!DOCTYPE html PUBLIC ...&gt;`，禁掉 DOCTYPE 会让解析直接失败。
     */
    private fun parseXml(bytes: ByteArray): Document? {
        val text = sanitizeXmlDeclaration(decodeText(bytes))
        return runCatching {
            val factory = DocumentBuilderFactory.newInstance()
            factory.isNamespaceAware = true
            factory.isValidating = false
            factory.setExpandEntityReferences(false)
            runCatching { factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true) }
            runCatching {
                factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
            }
            runCatching {
                factory.setFeature("http://xml.org/sax/features/external-general-entities", false)
            }
            runCatching {
                factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false)
            }
            val builder = factory.newDocumentBuilder()
            builder.parse(ByteArrayInputStream(text.toByteArray(Charsets.UTF_8)))
        }.getOrNull()
    }

    /** 已自行解码过，XML 声明里的 encoding 必须同步改成 UTF-8，否则解析器会按错编码读 */
    private fun sanitizeXmlDeclaration(text: String): String =
        XML_DECL_ENCODING.replace(text) { m ->
            m.groupValues[1] + "UTF-8" + m.groupValues[3]
        }

    /**
     * 解码文本：优先 XML 声明 / meta 里声明的编码，其次 UTF-8，最后 GBK 兜底。
     *
     * 与 [LocalBookStore] 的 TXT 解码口径保持一致（BOM → 声明 → UTF-8 严格 → GBK），
     * 区别是这里多认了「xml 声明里的 encoding」——EPUB 里的 XML 文件常显式声明 GBK。
     */
    private fun decodeText(bytes: ByteArray): String {
        if (bytes.isEmpty()) return ""
        if (bytes.size >= 3 &&
            bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()
        ) {
            return String(bytes, 3, bytes.size - 3, Charsets.UTF_8)
        }
        if (bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) {
            return String(bytes, 2, bytes.size - 2, Charsets.UTF_16LE)
        }
        if (bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()) {
            return String(bytes, 2, bytes.size - 2, Charsets.UTF_16BE)
        }

        sniffCharset(bytes)?.let { declared ->
            val cs = runCatching { Charset.forName(declared) }.getOrNull()
            if (cs != null) return String(bytes, cs)
        }

        val utf8 = runCatching { String(bytes, Charsets.UTF_8) }.getOrNull()
        if (utf8 != null && !utf8.contains('\uFFFD')) return utf8
        return String(bytes, Charset.forName("GBK"))
    }

    /**
     * 从文件头里嗅探编码声明。
     *
     * XML 声明与 meta 标签都在 ASCII 范围内，用 ISO-8859-1（字节→字符 1:1）
     * 读前 1KB 就能安全地正则匹配，不用先猜编码。
     */
    private fun sniffCharset(bytes: ByteArray): String? {
        val limit = minOf(bytes.size, 1024)
        val head = String(bytes, 0, limit, Charsets.ISO_8859_1)
        val xmlDecl = Regex(
            """encoding\s*=\s*["']([A-Za-z0-9_.\-]+)["']""",
            RegexOption.IGNORE_CASE,
        ).find(head)
        if (xmlDecl != null) return xmlDecl.groupValues[1]
        val meta = Regex(
            """charset\s*=\s*["']?([A-Za-z0-9_.\-]+)""",
            RegexOption.IGNORE_CASE,
        ).find(head)
        return meta?.groupValues?.get(1)
    }

    /**
     * 解析包内相对路径。
     *
     * OPF 里的 href 是相对 OPF 文件所在目录的（如 `../Text/ch1.xhtml`），
     * 直接拿它当 zip 条目名会找不到文件，所以这里做一次「目录 + 相对路径」的合并，
     * 并把 `..` 折叠掉。
     */
    private fun resolvePath(baseDir: String, href: String): String? {
        val cleaned = href.substringBefore('#').trim()
        if (cleaned.isEmpty()) return null
        val decoded = percentDecode(cleaned)
        val rootRelative = decoded.startsWith('/')
        val base = if (rootRelative) "" else baseDir
        val parts = base.split('/').filter { it.isNotEmpty() } + decoded.split('/')
        val stack = ArrayDeque<String>()
        for (part in parts) {
            when (part) {
                "", "." -> Unit
                ".." -> if (stack.isNotEmpty()) stack.removeLast()
                else -> stack.addLast(part)
            }
        }
        val result = stack.joinToString("/")
        return result.ifEmpty { null }
    }

    /** 百分号解码（不做 `+` → 空格，那是表单编码的规则，路径里不适用） */
    private fun percentDecode(raw: String): String {
        if (!raw.contains('%')) return raw
        val bytes = java.io.ByteArrayOutputStream()
        var i = 0
        while (i < raw.length) {
            val c = raw[i]
            if (c == '%' && i + 2 < raw.length) {
                val hex = raw.substring(i + 1, i + 3)
                val value = hex.toIntOrNull(16)
                if (value != null) {
                    bytes.write(value)
                    i += 3
                    continue
                }
            }
            bytes.write(c.toString().toByteArray(Charsets.UTF_8))
            i++
        }
        return String(bytes.toByteArray(), Charsets.UTF_8)
    }

    /**
     * 按 localName 找元素（不关心命名空间前缀）。
     *
     * EPUB 里 `dc:title`、`title` 都可能出现在不同文件里，按 localName 匹配最稳。
     *
     * 注意：`getElementsByTagName` 在 DOM 里只挂在 Document 与 Element 上（不在 Node 上），
     * 所以这里分成两个重载。
     */
    private fun elementsByLocalName(root: Document, name: String): List<DomElement> =
        collectByLocalName(root.getElementsByTagName("*"), name)

    private fun elementsByLocalName(root: DomElement, name: String): List<DomElement> =
        collectByLocalName(root.getElementsByTagName("*"), name)

    private fun collectByLocalName(nodes: NodeList, name: String): List<DomElement> {
        val result = mutableListOf<DomElement>()
        for (i in 0 until nodes.length) {
            val el = nodes.item(i) as? DomElement ?: continue
            if (localNameOf(el).equals(name, ignoreCase = true)) result += el
        }
        return result
    }

    private fun localNameOf(node: DomNode): String {
        val local = node.localName
        if (!local.isNullOrEmpty()) return local
        return node.nodeName?.substringAfterLast(':').orEmpty()
    }
}
