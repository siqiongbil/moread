package com.moread.app.core.parser

import com.moread.app.core.model.BookFormat
import com.moread.app.core.model.BookSource
import com.moread.app.core.model.ChapterBound
import org.w3c.dom.Document
import org.w3c.dom.Element
import java.io.ByteArrayInputStream
import java.io.File
import java.net.URLDecoder
import javax.xml.parsers.DocumentBuilderFactory

/** EPUB 一章（spine 项）的元数据。 */
data class EpubChapter(
    /** zip 内路径（相对根、已解码已归一化） */
    val href: String,
    val title: String,
)

data class EpubBook(
    val title: String,
    val author: String,
    val chapters: List<EpubChapter>,
)

/**
 * 轻量 EPUB 2/3 解析器：仅用 JDK 内置 zip + DOM（无第三方依赖）。
 * 覆盖小说场景所需：container.xml → OPF（metadata/manifest/spine）→ 目录标题
 * （EPUB3 NAV 优先，EPUB2 NCX 兜底，缺失则按 spine 顺序命名）。
 */
class EpubParser {

    fun parse(file: File): EpubBook {
        java.util.zip.ZipFile(file).use { zip ->
            val opfPath = locateOpf(zip) ?: error("缺少 META-INF/container.xml 或 OPF")
            val opfBytes = zip.entryBytes(opfPath) ?: error("OPF 不存在: $opfPath")
            val opf = dom(opfBytes)
            val opfDir = opfPath.substringBeforeLast('/', "")

            // manifest：id → (href, mediaType, properties)
            data class Item(val href: String, val mediaType: String, val properties: String)
            val manifest = HashMap<String, Item>()
            for (el in elements(opf, "item")) {
                val id = el.attr("id") ?: continue
                val href = el.attr("href") ?: continue
                manifest[id] = Item(
                    href = href,
                    mediaType = el.attr("media-type") ?: "",
                    properties = el.attr("properties") ?: "",
                )
            }

            // spine 顺序 → XHTML 章节
            val spineIds = ArrayList<String>()
            var ncxId: String? = null
            elements(opf, "spine").firstOrNull()?.let { spineEl ->
                ncxId = spineEl.attr("toc")
                for (el in elements(spineEl, "itemref")) {
                    el.attr("idref")?.let { spineIds += it }
                }
            }
            val chapters = ArrayList<EpubChapter>()
            for (idref in spineIds) {
                val item = manifest[idref] ?: continue
                val isDoc = item.mediaType.contains("xhtml", true) ||
                    item.mediaType.contains("html", true) ||
                    item.href.substringBefore('#').endsWith(".xhtml", true) ||
                    item.href.substringBefore('#').endsWith(".html", true) ||
                    item.href.substringBefore('#').endsWith(".htm", true)
                if (!isDoc) continue
                chapters += EpubChapter(
                    href = resolveHref(opfDir, item.href.substringBefore('#')),
                    title = "", // 占位，下面补
                )
            }
            if (chapters.isEmpty()) error("spine 中没有可读的 XHTML 章节")

            // 目录标题映射（href → 标题）
            val tocTitles = HashMap<String, String>()
            val navHref = manifest.values.firstOrNull { it.properties.contains("nav", true) }?.href
            if (navHref != null) {
                val navPath = resolveHref(opfDir, navHref.substringBefore('#'))
                zip.entryBytes(navPath)?.let {
                    tocTitles += parseNav(dom(it), navPath.substringBeforeLast('/', ""))
                }
            }
            if (tocTitles.isEmpty()) {
                val ncxHref = manifest[ncxId]?.href
                    ?: manifest.values.firstOrNull { it.mediaType.equals("application/x-dtbncx+xml", true) }?.href
                if (ncxHref != null) {
                    val ncxPath = resolveHref(opfDir, ncxHref.substringBefore('#'))
                    zip.entryBytes(ncxPath)?.let { tocTitles += parseNcx(dom(it), ncxPath) }
                }
            }

            val titled = chapters.mapIndexed { i, c ->
                val t = tocTitles[c.href]
                c.copy(title = if (t.isNullOrBlank()) "第 ${i + 1} 部分" else t.trim())
            }

            val title = opf.firstText("dc:title") ?: opf.firstText("title") ?: file.nameWithoutExtension
            val author = opf.firstText("dc:creator") ?: opf.firstText("creator") ?: ""
            return EpubBook(title.trim(), author.trim(), titled)
        }
    }

    /** 一次开包批量抽取多章正文（大书性能关键路径：避免每章重复开 zip/解析 OPF）。 */
    fun readChapterTexts(
        file: File,
        hrefs: List<String>,
        onProgress: ((Int, Int) -> Unit)? = null,
    ): Map<String, String> {
        val out = HashMap<String, String>(hrefs.size)
        java.util.zip.ZipFile(file).use { zip ->
            hrefs.forEachIndexed { i, href ->
                out[href] = zip.entryBytes(href)?.let { XhtmlText.extract(decodeXml(it)) } ?: ""
                onProgress?.invoke(i + 1, hrefs.size)
            }
        }
        return out
    }

    /** 读取并抽取一章正文为纯文本（页脚页码、分页复用 TXT 管线）。 */
    fun readChapterText(file: File, href: String): String {
        java.util.zip.ZipFile(file).use { zip ->
            val bytes = zip.entryBytes(href) ?: return ""
            return XhtmlText.extract(decodeXml(bytes))
        }
    }

    /** 提取封面图字节：EPUB3 cover-image 属性 → EPUB2 meta[name=cover] → 含 cover 字样的图片项。 */
    fun coverImage(file: File): ByteArray? = runCatching {
        java.util.zip.ZipFile(file).use { zip ->
            val opfPath = locateOpf(zip) ?: return@runCatching null
            val opfBytes = zip.entryBytes(opfPath) ?: return@runCatching null
            val opf = dom(opfBytes)
            val opfDir = opfPath.substringBeforeLast('/', "")
            val hrefById = HashMap<String, Pair<String, String>>() // id → (href, mediaType/properties)
            for (el in elements(opf, "item")) {
                val id = el.attr("id") ?: continue
                val href = el.attr("href") ?: continue
                hrefById[id] = href to (el.attr("media-type") ?: "") + " " + (el.attr("properties") ?: "")
            }
            val coverId = elements(opf, "meta")
                .firstOrNull { it.attr("name") == "cover" }?.attr("content")
            val entry = hrefById.entries.firstOrNull { it.value.second.contains("cover-image", true) }
                ?: coverId?.let { cid -> hrefById.entries.firstOrNull { it.key == cid } }
                ?: hrefById.entries.firstOrNull {
                    it.value.first.contains("cover", true) && it.value.second.startsWith("image/")
                }
                // 兜底：精校版/转换书常不走标准封面标记，取清单里第一张图片
                ?: hrefById.entries.firstOrNull { it.value.second.startsWith("image/") }
                ?: return@runCatching null
            val href = entry.value.first.substringBefore('#')
            zip.entryBytes(resolveHref(opfDir, href))
        }
    }.getOrNull()

    // ---- 内部 ----

    private fun locateOpf(zip: java.util.zip.ZipFile): String? {
        val entry = zip.getEntry("META-INF/container.xml") ?: return null
        val doc = dom(zip.getInputStream(entry).readBytes())
        val rootFiles = doc.getElementsByTagName("rootfile")
        if (rootFiles.length == 0) return null
        return (rootFiles.item(0) as Element).attr("full-path")?.takeIf { it.isNotBlank() }
    }

    /** NAV (EPUB3)：nav/ol/li/a → href 与文字。href 按 NAV 所在目录归一化。 */
    private fun parseNav(doc: Document, baseDir: String): Map<String, String> {
        val out = HashMap<String, String>()
        for (a in elements(doc, "a")) {
            val href = a.attr("href") ?: continue
            val label = a.textContent?.trim().orEmpty()
            if (label.isEmpty()) continue
            out.putIfAbsent(resolveHref(baseDir, href.substringBefore('#')), label)
        }
        return out
    }

    /** NCX (EPUB2)：navPoint/navLabel/text + content src。src 相对 NCX 自身路径。 */
    private fun parseNcx(doc: Document, ncxPath: String): Map<String, String> {
        val out = HashMap<String, String>()
        for (point in elements(doc, "navPoint")) {
            val label = elements(point, "navLabel").firstOrNull()
                ?.let { elements(it, "text").firstOrNull()?.textContent?.trim() }.orEmpty()
            val src = elements(point, "content").firstOrNull()?.attr("src") ?: continue
            if (label.isEmpty()) continue
            out.putIfAbsent(resolveHref(ncxPath.substringBeforeLast('/', ""), src.substringBefore('#')), label)
        }
        return out
    }

    private fun dom(bytes: ByteArray): Document {
        val dbf = DocumentBuilderFactory.newInstance()
        dbf.isNamespaceAware = false
        val source = org.xml.sax.InputSource(ByteArrayInputStream(bytes))
        return dbf.newDocumentBuilder().parse(source)
    }

    private fun decodeXml(bytes: ByteArray): String {
        val text = when {
            bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte() ->
                String(bytes, 2, bytes.size - 2, Charsets.UTF_16LE)
            bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte() ->
                String(bytes, 2, bytes.size - 2, Charsets.UTF_16BE)
            else -> String(bytes, Charsets.UTF_8)
        }
        // 去掉 UTF-8 BOM
        return if (text.startsWith("\uFEFF")) text.substring(1) else text
    }

    private fun Element.attr(name: String): String? = getAttribute(name)?.takeIf { it.isNotEmpty() }

    /** NodeList → List<Element>（索引遍历，避开 iterator 扩展歧义）。 */
    private fun elements(el: Element, tag: String): List<Element> {
        val list = el.getElementsByTagName(tag)
        return (0 until list.length).mapNotNull { list.item(it) as? Element }
    }

    private fun elements(doc: Document, tag: String): List<Element> {
        val list = doc.getElementsByTagName(tag)
        return (0 until list.length).mapNotNull { list.item(it) as? Element }
    }

    private fun Document.firstText(tag: String): String? =
        getElementsByTagName(tag).item(0)?.textContent

    private fun java.util.zip.ZipFile.entryBytes(name: String): ByteArray? =
        getEntry(name)?.let { getInputStream(it).readBytes() }

    companion object {
        /** 归一化 zip 内路径：URL 解码 + 处理 ./ 与 ../。 */
        fun resolveHref(baseDir: String, href: String): String {
            val decoded = runCatching { URLDecoder.decode(href, "UTF-8") }.getOrDefault(href)
            val combined = if (baseDir.isBlank() || decoded.startsWith("/")) {
                decoded.removePrefix("/")
            } else {
                "$baseDir/$decoded"
            }
            val stack = ArrayList<String>()
            for (seg in combined.split('/')) {
                when (seg) {
                    "", "." -> {}
                    ".." -> if (stack.isNotEmpty()) stack.removeAt(stack.size - 1)
                    else -> stack += seg
                }
            }
            return stack.joinToString("/")
        }
    }
}

/** EPUB 的 BookSource 实现：章实体 key 列存 zip 内 href。 */
class EpubBookSource(private val zipPath: String) : BookSource {
    override val id: String = "local:epub:$zipPath"
    override val format: BookFormat = BookFormat.EPUB

    private val parser = EpubParser()

    override suspend fun openChapterText(bound: ChapterBound, charset: String): String {
        val full = parser.readChapterText(File(zipPath), bound.key ?: "")
        // 大章切分后 startOffset/endOffset 表示章内字符区间；0..0 表示整章
        return if (bound.endOffset > bound.startOffset) {
            full.substring(
                bound.startOffset.coerceIn(0, full.length),
                bound.endOffset.coerceAtMost(full.length),
            )
        } else full
    }
}

/**
 * XHTML → 阅读用纯文本：块级标签转段落换行、<br> 换行、剥其余标签、
 * 解码常见实体（命名 + 数字），容忍不严格的 HTML。纯 Kotlin，可 JVM 单测。
 */
object XhtmlText {

    private val BLOCK_TAGS =
        "p|div|h[1-6]|li|blockquote|section|article|header|footer|td|tr|table|ul|ol|dl|dt|dd|pre|hr|figcaption|mbp:pagebreak"

    private val NAMED_ENTITIES = mapOf(
        "nbsp" to " ", "quot" to "\"", "amp" to "&", "lt" to "<", "gt" to ">",
        "apos" to "'", "hellip" to "…", "mdash" to "—", "ndash" to "–",
        "ldquo" to "“", "rdquo" to "”", "lsquo" to "‘", "rsquo" to "’",
        "middot" to "·", "bull" to "•", "copy" to "©", "reg" to "®",
        "trade" to "™", "deg" to "°", "permil" to "‰", "prime" to "′",
        "ensp" to " ", "emsp" to " ", "thinsp" to " ", "zwnj" to "", "zwj" to "",
        "times" to "×", "divide" to "÷", "plusmn" to "±", "para" to "¶",
    )

    fun extract(xhtml: String): String {
        var s = xhtml
        // 头部/脚本/样式与注释整体移除
        s = Regex("(?is)<!--.*?-->").replace(s, "")
        s = Regex("(?is)<(head|script|style)\\b[^>]*>.*?</\\1\\s*>").replace(s, "")
        // 换行语义
        s = Regex("(?i)<br\\s*/?>").replace(s, "\n")
        // 块级开/闭标签 → 段落边界
        s = Regex("(?i)</?($BLOCK_TAGS)\\b[^>]*>").replace(s, "\n")
        // 剩余标签剥除
        s = Regex("(?s)<[^>]+>").replace(s, "")
        // 实体解码（命名 + 十进制 + 十六进制）
        s = Regex("&#x([0-9a-fA-F]+);").replace(s) { m ->
            m.groupValues[1].toIntOrNull(16)?.toChar()?.toString() ?: m.value
        }
        s = Regex("&#(\\d+);").replace(s) { m ->
            m.groupValues[1].toIntOrNull()?.toChar()?.toString() ?: m.value
        }
        s = Regex("&([a-zA-Z][a-zA-Z0-9]*);").replace(s) { m ->
            NAMED_ENTITIES[m.groupValues[1]] ?: m.value
        }
        return tidy(s)
    }

    /** 标准中文排版：删除全部空行，段首两个全角空格缩进（首段不缩进）。 */
    private fun tidy(s: String): String {
        val out = StringBuilder(s.length)
        var first = true
        for (line in s.lineSequence()) {
            val t = line.trim()
            // 空行与只含零宽/软连接等不可见字符的“假段落”（原书场景分隔）一并跳过
            if (!t.any { ch -> !ch.isWhitespace() && ch.code !in 0x200B..0x200F && ch.code != 0xFEFF && ch.code != 0xAD }) continue
            if (first) {
                out.append(t).append('\n')
                first = false
            } else {
                out.append("　　").append(t).append('\n')
            }
        }
        return out.toString()
    }
}
