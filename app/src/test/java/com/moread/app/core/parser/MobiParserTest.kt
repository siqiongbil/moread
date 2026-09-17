package com.moread.app.core.parser

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File

class MobiParserTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val parser = MobiParser()

    // ---- PalmDoc LZ77 ----

    @Test
    fun `LZ77 字面与转义`() {
        // 0x41 在 0x09-0x7F：字面
        assertArrayEquals(byteArrayOf(0x41), PalmDoc.lz77Decompress(byteArrayOf(0x41)))
        // 0x00 转义下一字节
        assertArrayEquals(
            byteArrayOf(0xC3.toByte()),
            PalmDoc.lz77Decompress(byteArrayOf(0x00, 0xC3.toByte())),
        )
        // 0x01-0x08：后跟 N 个字面字节（可含任意高位字节）
        assertArrayEquals(
            byteArrayOf(0xE4.toByte(), 0xB8.toByte(), 0xAD.toByte(), 0xE6.toByte()),
            PalmDoc.lz77Decompress(
                byteArrayOf(0x04, 0xE4.toByte(), 0xB8.toByte(), 0xAD.toByte(), 0xE6.toByte()),
            ),
        )
    }

    @Test
    fun `LZ77 高位空格分支`() {
        // 0xC1 → 0x20, 0x41
        assertArrayEquals(byteArrayOf(0x20, 0x41), PalmDoc.lz77Decompress(byteArrayOf(0xC1.toByte())))
    }

    @Test
    fun `LZ77 回引`() {
        // "abc" + pair(0x80,0x18)：dist=3,len=3 → "abcabc"
        assertArrayEquals(
            "abcabc".toByteArray(),
            PalmDoc.lz77Decompress(byteArrayOf(0x61, 0x62, 0x63, 0x80.toByte(), 0x18)),
        )
    }

    @Test
    fun `LZ77 重叠回引`() {
        // "ab" + pair(0x80,0x12)：dist=2,len=5 → "abababa"
        assertArrayEquals(
            "abababa".toByteArray(),
            PalmDoc.lz77Decompress(byteArrayOf(0x61, 0x62, 0x80.toByte(), 0x12)),
        )
    }

    // ---- 完整 MOBI 构建 + 解析 ----

    /** 用“纯字面游程”的 LZ77 编码包装任意字节（0x01-0x08 前缀 + N 字节）。 */
    private fun lz77LiteralWrap(data: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(data.size * 2)
        var i = 0
        while (i < data.size) {
            val take = minOf(8, data.size - i)
            out.write(take)
            out.write(data, i, take)
            i += take
        }
        return out.toByteArray()
    }

    private fun htmlBody(): String = buildString {
        append("""<html><head><guide></guide></head><body>""")
        for (i in 1..6) {
            append("<h1>第${i}章 山月记事</h1>")
            append("<p>月光落在山脊上，像一层薄薄的霜，少年背起行囊继续赶路。</p>".repeat(3))
        }
        append("</body></html>")
    }

    private fun u16(v: Int) = byteArrayOf((v shr 8).toByte(), v.toByte())

    private fun u32(v: Long) = byteArrayOf(
        (v shr 24).toByte(), (v shr 16).toByte(), (v shr 8).toByte(), v.toByte(),
    )

    private fun u32(v: Int) = u32(v.toLong())

    private fun buildMobi(compression: Int = 2, title: String = "山月记", author: String = "墨测试"): File {
        val html = htmlBody().toByteArray(Charsets.UTF_8)
        val textRecord = if (compression == 2) lz77LiteralWrap(html) else html

        // 记录0：PalmDOC(16) + MOBI头(232) + fullName + EXTH
        val fullName = title.toByteArray(Charsets.UTF_8)
        val authorB = author.toByteArray(Charsets.UTF_8)
        val titleB = title.toByteArray(Charsets.UTF_8)
        val exthRecords = ByteArrayOutputStream()
        exthRecords.write(u32(100)); exthRecords.write(u32(8 + authorB.size)); exthRecords.write(authorB)
        exthRecords.write(u32(503)); exthRecords.write(u32(8 + titleB.size)); exthRecords.write(titleB)
        val exth = ByteArrayOutputStream()
        exth.write("EXTH".toByteArray())
        exth.write(u32(12 + exthRecords.size().toLong()))
        exth.write(u32(2))
        exth.write(exthRecords.toByteArray())

        // MOBI 头：定长 232，直接在固定偏移上填字段
        val mh = ByteArray(232)
        "MOBI".toByteArray().copyInto(mh, 0x00)
        u32(232).copyInto(mh, 0x04)          // header length
        u32(2).copyInto(mh, 0x08)            // mobi type
        u32(65001).copyInto(mh, 0x0C)        // text encoding UTF-8
        u32(0x40).copyInto(mh, 0x70)         // EXTH flags: bit6
        // 0x44 fullNameOffset（相对记录0，构建完回填）、0x48 fullNameLength
        u32(fullName.size.toLong()).copyInto(mh, 0x48)

        val r0 = ByteArrayOutputStream()
        r0.write(u16(compression))                 // compression
        r0.write(u16(0))                           // unused
        r0.write(u32(html.size.toLong()))          // textLength（解压后总长）
        r0.write(u16(1))                           // textRecordCount
        r0.write(u16(4096))                        // recordSize
        r0.write(u16(0))                           // encryption none
        r0.write(u16(0))                           // unused
        r0.write(mh)
        r0.write(exth.toByteArray())               // EXTH 必须紧跟 MOBI 头
        val nameOffset = r0.size()                 // fullName 放 EXTH 之后
        r0.write(fullName)
        val r0Bytes = r0.toByteArray()
        // 回填 fullNameOffset（记录0 内偏移 16+0x44）
        u32(nameOffset.toLong()).copyInto(r0Bytes, 16 + 0x44)

        // PDB：记录0 + 文本记录1
        val records = listOf(r0Bytes, textRecord)
        val numRecords = records.size
        val headerLen = 78 + numRecords * 8 + 2
        var offset = headerLen
        val offsets = IntArray(numRecords)
        val pdb = ByteArrayOutputStream()
        val nameField = "TestMobi".toByteArray(Charsets.US_ASCII).copyOf(32)
        pdb.write(nameField)
        pdb.write(u16(0))            // attributes
        pdb.write(u16(0))            // version
        repeat(4) { pdb.write(u32(0)) } // creation/modification/backup/modnum
        pdb.write(u32(0)); pdb.write(u32(0)) // appInfoID/sortInfoID
        pdb.write("BOOK".toByteArray())
        pdb.write("MOBI".toByteArray())
        pdb.write(u32(0))            // uniqueIDseed
        pdb.write(u32(0))            // nextRecordList
        pdb.write(u16(numRecords))
        for (i in records.indices) {
            offsets[i] = offset
            pdb.write(u32(offset.toLong()))
            // 属性 u8 + uniqueID u24（3 字节），共 8 字节一项
            pdb.write(byteArrayOf(0, (i shr 16).toByte(), (i shr 8).toByte(), i.toByte()))
            offset += records[i].size
        }
        pdb.write(u16(0))
        for (r in records) pdb.write(r)

        val file = tmp.newFile("test.mobi")
        file.writeBytes(pdb.toByteArray())
        return file
    }

    @Test
    fun `解析 LZ77 压缩的 MOBI`() {
        val book = parser.parse(buildMobi(compression = 2))
        assertEquals("山月记", book.title)
        assertEquals("墨测试", book.author)
        assertEquals("UTF-8", book.charsetName)
        assertTrue(book.text.contains("第1章 山月记事"))
        assertTrue(book.text.contains("第6章 山月记事"))
        assertTrue(!book.text.contains("<h1>"))
        // 段落换行（块级标签已转空行）
        assertTrue(book.text.contains("赶路。\n"))
    }

    @Test
    fun `解析无压缩 MOBI`() {
        val book = parser.parse(buildMobi(compression = 1))
        assertEquals("山月记", book.title)
        assertTrue(book.text.contains("第3章"))
    }

    @Test
    fun `HUFF 压缩给出友好错误`() {
        val file = buildMobi(compression = 2)
        val bytes = file.readBytes()
        // 记录0 起点 = PDB头78 + 2条记录表(16) + 填充2 = 96；compression 位于其 0-1 字节
        val r0 = 78 + 2 * 8 + 2
        bytes[r0] = ((17480 shr 8) and 0xFF).toByte()
        bytes[r0 + 1] = (17480 and 0xFF).toByte()
        val f2 = tmp.newFile("huff.mobi")
        f2.writeBytes(bytes)
        try {
            parser.parse(f2)
            fail("应当抛出异常")
        } catch (e: Exception) {
            assertTrue(e.message.orEmpty().contains("AZW3"))
        }
    }
}
