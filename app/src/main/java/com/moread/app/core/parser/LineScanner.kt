package com.moread.app.core.parser

import com.moread.app.core.model.LineInfo
import java.io.Reader

/**
 * 精确的行扫描器：逐字符识别 '\n'（兼容 \r\n），行首 charOffset 与流中真实
 * 字符位置一致。TxtParser 的分章偏移与 ChapterTextLoader 的 skip 必须使用
 * 同一套计数规则，否则 CRLF 文件会错位。
 */
object LineScanner {

    fun scan(reader: Reader): Pair<List<LineInfo>, Int> {
        val out = ArrayList<LineInfo>(16 * 1024)
        val buf = CharArray(64 * 1024)
        val sb = StringBuilder(256)
        var offset = 0
        var lineStart = 0
        var lineIndex = 0
        while (true) {
            val n = reader.read(buf)
            if (n < 0) break
            for (i in 0 until n) {
                val c = buf[i]
                if (c == '\n') {
                    var len = sb.length
                    if (len > 0 && sb[len - 1] == '\r') len--
                    val text = if (len == sb.length) sb.toString() else sb.substring(0, len)
                    out += LineInfo(lineIndex, lineStart, text)
                    lineIndex++
                    lineStart = offset + i + 1
                    sb.setLength(0)
                } else {
                    sb.append(c)
                }
            }
            offset += n
        }
        if (sb.isNotEmpty()) {
            out += LineInfo(lineIndex, lineStart, sb.toString().trimEnd('\r'))
        }
        return out to offset
    }

    /** 测试与预览辅助：直接从字符串构造行序列，计数规则与 scan 一致。 */
    fun fromString(content: String): Pair<List<LineInfo>, Int> =
        scan(content.reader())
}
