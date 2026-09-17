package com.moread.app.core.parser

import java.io.File
import java.nio.charset.Charset

/**
 * 轻量 MOBI 解析器（PalmDOC 家族，纯 Kotlin / 零依赖，JVM 可测）。
 *
 * 结构：PDB 头（记录表）→ 记录0（PalmDOC 头 + MOBI 头 + EXTH 元数据）→
 * 文本记录 1..N（PalmDOC LZ77 压缩的 HTML 片段）。
 * 拼接解压后的 HTML，交给 XhtmlText 抽取为阅读文本（进而走 TXT 分章管线）。
 *
 * 不支持：HUFF/CDIC 压缩（多见于 AZW3，compression=17480）与加密（DRM）文件，
 * 二者抛出带说明的异常。
 */
class MobiParser {

    data class MobiBook(
        val title: String,
        val author: String,
        val charsetName: String,
        /** 已抽取的阅读文本（非原始 HTML） */
        val text: String,
    )

    fun parse(file: File): MobiBook {
        val bytes = file.readBytes()
        if (bytes.size < 80) error("MOBI 文件过小或已损坏")

        // ---- PDB 头 ----
        val type = str(bytes, 60, 4)
        val creator = str(bytes, 64, 4)
        if (type != "BOOK" || creator != "MOBI") error("不是有效的 MOBI 文件")
        val numRecords = u16(bytes, 76)
        if (numRecords < 2) error("MOBI 记录数异常")
        val offsets = IntArray(numRecords) { u32(bytes, 78 + it * 8).toInt() }
        fun record(i: Int): ByteArray {
            val end = if (i + 1 < numRecords) offsets[i + 1] else bytes.size
            return bytes.copyOfRange(offsets[i], end.coerceAtMost(bytes.size))
        }

        // ---- 记录0：PalmDOC 头 ----
        val r0 = record(0)
        if (r0.size < 16) error("MOBI 头损坏")
        val compression = u16(r0, 0)
        if (compression == HUFF_CDIC) {
            error("HUFF/CDIC 压缩（多为 AZW3/新 Kindle 文件）暂不支持，请转换为 EPUB 后导入")
        }
        if (compression != 1 && compression != 2) error("未知压缩方式：$compression")
        val textLength = u32(r0, 4)
        val textRecordCount = u16(r0, 8)
        val encryption = u16(r0, 12)
        if (encryption != 0) error("加密（DRM）的 MOBI 文件暂不支持")

        // ---- MOBI 头 ----
        var title = ""
        var author = ""
        var charset: Charset = Charsets.UTF_8
        var extraFlags = 0
        if (r0.size >= 24 && str(r0, 16, 4) == "MOBI") {
            val mobiLen = u32(r0, 16 + 4).toInt()
            val enc = u32(r0, 16 + 0x0C)
            charset = when (enc) {
                65001L -> Charsets.UTF_8
                1252L -> Charset.forName("windows-1252")
                else -> Charsets.UTF_8
            }
            // fullName 相对记录0起始
            val nameOff = u32(r0, 16 + 0x44).toInt()
            val nameLen = u32(r0, 16 + 0x48).toInt()
            if (nameOff > 0 && nameLen > 0 && nameOff + nameLen <= r0.size) {
                title = String(r0, nameOff, nameLen, charset)
            }
            // EXTH：记录0 内 16+mobiLen 处
            if (r0.size >= 16 + mobiLen + 12 && str(r0, 16 + mobiLen, 4) == "EXTH") {
                val exth = parseExth(r0, 16 + mobiLen, charset)
                exth[503]?.let { title = it }
                author = exth[100].orEmpty()
            }
            // extraFlags（trailing entries）：记录0 0xF2 处 u16，存在才读
            if (r0.size >= 0xF4) extraFlags = u16(r0, 0xF2)
        }

        // ---- 文本记录 ----
        if (textRecordCount <= 0 || 1 + textRecordCount > numRecords) error("MOBI 文本记录数异常")
        val html = StringBuilder(minOf(textLength, 16 shl 20).toInt().coerceAtLeast(64))
        var remaining = textLength
        for (i in 1..textRecordCount) {
            var rec = record(i)
            if (rec.isEmpty()) continue
            if (extraFlags != 0) {
                val trail = sizeOfTrailingEntries(rec, rec.size, extraFlags)
                if (trail in 0 until rec.size) rec = rec.copyOf(rec.size - trail)
            }
            val chunk = if (compression == 1) rec else PalmDoc.lz77Decompress(rec)
            val take = minOf(chunk.size.toLong(), remaining).toInt()
            if (take <= 0) break
            html.append(String(chunk, 0, take, charset))
            remaining -= take
        }
        if (html.isBlank()) error("MOBI 正文为空")

        val text = XhtmlText.extract(html.toString())
        return MobiBook(
            title = title.trim(),
            author = author.trim(),
            charsetName = charset.name(),
            text = text,
        )
    }

    // ---- EXTH ----

    private fun parseExth(r0: ByteArray, pos: Int, charset: Charset): Map<Int, String> {
        val out = HashMap<Int, String>()
        val count = u32(r0, pos + 8).toInt()
        var p = pos + 12
        repeat(count) {
            if (p + 8 > r0.size) return out
            val recType = u32(r0, p).toInt()
            val recLen = u32(r0, p + 4).toInt()
            if (recLen < 8 || p + recLen > r0.size) return out
            when (recType) {
                100, 503 -> out[recType] = String(r0, p + 8, recLen - 8, charset)
            }
            p += recLen
        }
        return out
    }

    // ---- trailing entries（KindleUnpack 同款算法）----

    private fun sizeOfTrailingEntries(data: ByteArray, size: Int, flags: Int): Int {
        var num = 0
        var f = flags ushr 1
        while (f != 0) {
            if (f and 1 != 0) num += trailingEntrySize(data, size - num)
            f = f ushr 1
        }
        return num
    }

    private fun trailingEntrySize(data: ByteArray, size: Int): Int {
        var bitpos = 0
        var s = size
        if (s <= 0) return 0
        while (true) {
            val v = data[s - 1].toInt() and 0xFF
            s--
            bitpos += 7
            if ((v and 0x80) != 0 || bitpos >= 28 || s == 0) return size - s
        }
    }

    // ---- 小工具 ----

    private fun u16(b: ByteArray, off: Int): Int =
        ((b[off].toInt() and 0xFF) shl 8) or (b[off + 1].toInt() and 0xFF)

    private fun u32(b: ByteArray, off: Int): Long =
        ((b[off].toLong() and 0xFF) shl 24) or ((b[off + 1].toLong() and 0xFF) shl 16) or
            ((b[off + 2].toLong() and 0xFF) shl 8) or (b[off + 3].toLong() and 0xFF)

    private fun str(b: ByteArray, off: Int, len: Int): String {
        val sb = StringBuilder(len)
        for (i in 0 until len) {
            val c = b[off + i].toInt() and 0xFF
            if (c == 0) break
            sb.append(c.toChar())
        }
        return sb.toString()
    }

    companion object {
        private const val HUFF_CDIC = 17480
    }
}

/** PalmDOC LZ77 解压（MOBI compression=2）。字节语义：
 *  0x00=转义下一字节；0x01-0x08=后跟 N 个字面字节；0x09-0x7F=字面；
 *  0x80-0xBF=两字节回引（11 位距离/3 位长度+3）；0xC0-0xFF=空格 + 低 7 位。 */
object PalmDoc {

    fun lz77Decompress(input: ByteArray): ByteArray {
        val out = ByteBuf(input.size * 3 / 2 + 16)
        var p = 0
        val n = input.size
        while (p < n) {
            val c = input[p++].toInt() and 0xFF
            when {
                c == 0x00 -> if (p < n) out.write(input[p++].toInt() and 0xFF)

                c <= 0x08 -> {
                    val take = minOf(c, n - p)
                    for (i in 0 until take) out.write(input[p++].toInt() and 0xFF)
                }

                c < 0x80 -> out.write(c)

                c >= 0xC0 -> {
                    out.write(0x20)
                    out.write(c xor 0x80)
                }

                else -> {
                    if (p >= n) break
                    val pair = (c shl 8) or (input[p++].toInt() and 0xFF)
                    val dist = (pair shr 3) and 0x7FF
                    val len = (pair and 7) + 3
                    if (dist in 1..out.size) {
                        out.back(dist, len)
                    }
                }
            }
        }
        return out.toByteArray()
    }

    private class ByteBuf(cap: Int) {
        private var arr = ByteArray(cap)
        var size = 0
            private set

        private fun ensure(extra: Int) {
            if (size + extra > arr.size) {
                arr = arr.copyOf(maxOf(arr.size * 2, size + extra))
            }
        }

        fun write(b: Int) {
            ensure(1)
            arr[size++] = b.toByte()
        }

        /** LZ77 回引：允许 dist < n 的重叠拷贝（逐字节前向）。 */
        fun back(dist: Int, n: Int) {
            ensure(n)
            for (i in 0 until n) {
                arr[size] = arr[size - dist]
                size++
            }
        }

        fun toByteArray(): ByteArray = arr.copyOf(size)
    }
}
