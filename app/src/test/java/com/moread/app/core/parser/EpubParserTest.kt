package com.moread.app.core.parser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class EpubParserTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val parser = EpubParser()

    private fun chapter(n: Int, paras: Int = 2): String = buildString {
        append("""<?xml version="1.0" encoding="utf-8"?>""")
        append("""<html xmlns="http://www.w3.org/1999/xhtml"><head><title>ch$n</title></head><body>""")
        append("""<h1>第${n}章 风雪夜归</h1>""")
        repeat(paras) { p ->
            append("""<p>这是第${n}章第${p}段正文，灯火在长街尽头明灭。&nbsp;&amp;&hellip;</p>""")
        }
        append("</body></html>")
    }

    private fun buildEpub(useNav: Boolean = true, useNcx: Boolean = false): File {
        val file = tmp.newFile("测试.epub")
        ZipOutputStream(file.outputStream()).use { zip ->
            fun put(name: String, content: String) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(content.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
            put("mimetype", "application/epub+zip")
            put(
                "META-INF/container.xml",
                """<?xml version="1.0"?>
                <container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
                  <rootfiles>
                    <rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/>
                  </rootfiles>
                </container>""".trimIndent(),
            )
            val tocItem = buildString {
                if (useNav) append("""<item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/>""")
                if (useNcx) append("""<item id="ncx" href="toc.ncx" media-type="application/x-dtbncx+xml"/>""")
            }
            put(
                "OEBPS/content.opf",
                """<?xml version="1.0" encoding="utf-8"?>
                <package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="uid">
                  <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
                    <dc:title>风雪夜归人</dc:title>
                    <dc:creator>测试作者</dc:creator>
                    <dc:identifier id="uid">test-001</dc:identifier>
                  </metadata>
                  <manifest>
                    $tocItem
                    <item id="c1" href="text/ch1.xhtml" media-type="application/xhtml+xml"/>
                    <item id="c2" href="text/ch2.xhtml" media-type="application/xhtml+xml"/>
                    <item id="c3" href="text/ch3.xhtml" media-type="application/xhtml+xml"/>
                  </manifest>
                  <spine ${if (useNcx) """toc="ncx"""" else ""}>
                    <itemref idref="c1"/><itemref idref="c2"/><itemref idref="c3"/>
                  </spine>
                </package>""".trimIndent(),
            )
            if (useNav) {
                put(
                    "OEBPS/nav.xhtml",
                    """<?xml version="1.0" encoding="utf-8"?>
                    <html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops">
                    <body><nav epub:type="toc"><ol>
                      <li><a href="text/ch1.xhtml">第一章 夜行</a></li>
                      <li><a href="text/ch2.xhtml#frag">第二章 风起</a></li>
                      <li><a href="text/ch3.xhtml">第三章 归途</a></li>
                    </ol></nav></body></html>""".trimIndent(),
                )
            }
            if (useNcx) {
                put(
                    "OEBPS/toc.ncx",
                    """<?xml version="1.0" encoding="utf-8"?>
                    <ncx xmlns="http://www.daisy.org/z3986/2005/ncx/" version="2005-1">
                      <navMap>
                        <navPoint id="n1"><navLabel><text>第一章 夜行</text></navLabel><content src="text/ch1.xhtml"/></navPoint>
                        <navPoint id="n2"><navLabel><text>第二章 风起</text></navLabel><content src="text/ch2.xhtml"/></navPoint>
                        <navPoint id="n3"><navLabel><text>第三章 归途</text></navLabel><content src="text/ch3.xhtml"/></navPoint>
                      </navMap>
                    </ncx>""".trimIndent(),
                )
            }
            put("OEBPS/text/ch1.xhtml", chapter(1))
            put("OEBPS/text/ch2.xhtml", chapter(2))
            put("OEBPS/text/ch3.xhtml", chapter(3))
        }
        return file
    }

    @Test
    fun `解析元数据与 NAV 目录`() {
        val epub = parser.parse(buildEpub(useNav = true))
        assertEquals("风雪夜归人", epub.title)
        assertEquals("测试作者", epub.author)
        assertEquals(3, epub.chapters.size)
        assertEquals("OEBPS/text/ch1.xhtml", epub.chapters[0].href)
        assertEquals(listOf("第一章 夜行", "第二章 风起", "第三章 归途"),
            epub.chapters.map { it.title })
    }

    @Test
    fun `NCX 目录兜底`() {
        val epub = parser.parse(buildEpub(useNav = false, useNcx = true))
        assertEquals(3, epub.chapters.size)
        assertEquals("第一章 夜行", epub.chapters[0].title)
        assertEquals("第二章 风起", epub.chapters[1].title)
    }

    @Test
    fun `无目录时按序命名`() {
        val epub = parser.parse(buildEpub(useNav = false, useNcx = false))
        assertEquals("第 1 部分", epub.chapters[0].title)
    }

    @Test
    fun `抽取章正文含标题段落与实体解码`() {
        val file = buildEpub()
        val text = parser.readChapterText(file, "OEBPS/text/ch2.xhtml")
        assertTrue(text.contains("第2章 风雪夜归"))
        assertTrue(text.contains("这是第2章第0段正文"))
        assertTrue(text.contains("这是第2章第1段正文"))
        // &nbsp; → 空格、&amp; → &、&hellip; → …
        assertTrue(text.contains("&…"))
        // head/title 被剥除
        assertTrue(!text.contains("<title>"))
    }

    @Test
    fun `href 归一化`() {
        assertEquals("OEBPS/text/ch1.xhtml", EpubParser.resolveHref("OEBPS", "text/ch1.xhtml"))
        // NCX 位于 OEBPS/ 下时，其内部 src 的 ../ 应退到 zip 根
        assertEquals("text/ch1.xhtml", EpubParser.resolveHref("OEBPS", "../text/ch1.xhtml"))
        assertEquals("a/b.xhtml", EpubParser.resolveHref("", "/a/b.xhtml"))
        assertEquals("OEBPS/my book.xhtml", EpubParser.resolveHref("OEBPS", "my%20book.xhtml"))
    }

    @Test
    fun `XhtmlText 换行与块级语义`() {
        val html = "<html><body><p>甲</p><p>乙</p><div>丙<br/>丁</div></body></html>"
        val text = XhtmlText.extract(html)
        val lines = text.lines().filter { it.isNotBlank() }
        // 标准中文排版：无空行，段首两个全角空格缩进（首段不缩进）
        assertEquals(listOf("甲", "　　乙", "　　丙", "　　丁"), lines)
        assertTrue(!text.contains("\n\n"))
        // 只含零宽/不可见字符的“场景分隔假段落”应整体删除
        val sep = XhtmlText.extract("<html><body><p>甲</p><p>\u200B\uFEFF</p><p>乙</p></body></html>")
        assertEquals(listOf("甲", "　　乙"), sep.lines().filter { it.isNotBlank() })
    }

    @Test
    fun `XhtmlText 移除脚本样式与数字实体`() {
        val html = "<html><head><style>p{color:red}</style></head><body>" +
            "<p>温度&#8451;</p><script>evil()</script><p>结束&#x4E86;</p></body></html>"
        val text = XhtmlText.extract(html)
        assertTrue(text.contains("温度℃"))
        assertTrue(text.contains("结束了"))
        assertTrue(!text.contains("evil"))
        assertTrue(!text.contains("color:red"))
    }
}
