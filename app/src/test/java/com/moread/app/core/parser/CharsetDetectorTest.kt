package com.moread.app.core.parser

import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.charset.Charset

class CharsetDetectorTest {

    private val chinese =
        "天地玄黄，宇宙洪荒。日月盈昃，辰宿列张。寒来暑往，秋收冬藏。" +
            "闰余成岁，律吕调阳。云腾致雨，露结为霜。金生丽水，玉出昆冈。" +
            "剑号巨阙，珠称夜光。果珍李柰，菜重芥姜。海咸河淡，鳞潜羽翔。"

    @Test
    fun `检测 UTF-8 中文`() {
        val charset = CharsetDetector.detectAndOpen(chinese.toByteArray(Charsets.UTF_8))
        assertEquals(Charsets.UTF_8, charset)
    }

    @Test
    fun `检测 GBK 并以 GB18030 解码`() {
        val gbk = chinese.toByteArray(charset("GBK"))
        val charset = CharsetDetector.detectAndOpen(gbk)
        assertEquals(Charset.forName("GB18030"), charset)
        // 解码回读无损
        val text = String(gbk, charset)
        assertEquals(chinese, text)
    }

    @Test
    fun `检测 Big5`() {
        val big5 = "劍號巨闕，珠稱夜光。果珍李柰，菜重芥薑。海鹹河淡，鱗潛羽翔。".repeat(3)
            .toByteArray(charset("Big5"))
        val charset = CharsetDetector.detectAndOpen(big5)
        assertEquals(Charset.forName("Big5"), charset)
    }

    @Test
    fun `BOM 优先`() {
        val utf8Bom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) +
            chinese.toByteArray(Charsets.UTF_8)
        assertEquals(Charsets.UTF_8, CharsetDetector.detectAndOpen(utf8Bom))
        assertEquals(3, CharsetDetector.bomLength(utf8Bom))

        val utf16Le = byteArrayOf(0xFF.toByte(), 0xFE.toByte()) +
            chinese.toByteArray(Charsets.UTF_16LE)
        assertEquals(Charsets.UTF_16LE, CharsetDetector.detectAndOpen(utf16Le))
        assertEquals(2, CharsetDetector.bomLength(utf16Le))
    }

    @Test
    fun `纯 ASCII 按 UTF-8 处理`() {
        assertEquals(Charsets.UTF_8, CharsetDetector.detectAndOpen("plain ascii text".toByteArray()))
    }

    private fun charset(name: String): Charset = Charset.forName(name)
}
