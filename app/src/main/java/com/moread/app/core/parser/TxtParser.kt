package com.moread.app.core.parser

import com.moread.app.core.chapter.ChapterSplitter
import com.moread.app.core.model.BookFormat
import com.moread.app.core.model.LineInfo
import com.moread.app.core.model.ParsedToc
import com.moread.app.core.model.RulePattern
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileInputStream
import java.io.InputStreamReader
import java.io.Reader
import java.io.SequenceInputStream
import java.nio.charset.Charset

/**
 * TXT 解析器：检测编码 → 流式逐行扫描（记录精确字符偏移）→ 交给分章引擎。
 * 全程只有行缓冲驻留内存，可处理上百 MB 文件。
 */
class TxtParser(private val splitter: ChapterSplitter = ChapterSplitter()) : BookParser {

    override val format: BookFormat = BookFormat.TXT

    suspend fun parse(
        file: File,
        rules: List<RulePattern>,
    ): Pair<Charset, ParsedToc> = withContext(Dispatchers.IO) {
        val charset = detectCharset(file)
        openBomSkipped(file, charset).use { reader ->
            val (lines, totalChars) = LineScanner.scan(reader)
            val result = splitter.split(lines.asSequence(), totalChars, rules)
            charset to ParsedToc(totalChars, result)
        }
    }

    override suspend fun parseToc(file: File): TocParseOutput {
        val (charset, toc) = parse(file, emptyList())
        return TocParseOutput(charset.name(), toc.totalChars, toc.result.chapters.map { it.charCount })
    }

    /** 只读行序列（规则预览等场景）。 */
    fun lineSequence(file: File, charset: Charset): List<LineInfo> =
        openBomSkipped(file, charset).use { LineScanner.scan(it).first }

    private fun detectCharset(file: File): Charset {
        FileInputStream(file).use { fis ->
            val head = ByteArray(PROBE_SIZE)
            var read = 0
            while (read < PROBE_SIZE) {
                val n = fis.read(head, read, PROBE_SIZE - read)
                if (n < 0) break
                read += n
            }
            return CharsetDetector.detectAndOpen(head.copyOf(read))
        }
    }

    companion object {
        private const val PROBE_SIZE = 64 * 1024

        /** 打开文件并跳过 BOM（UTF-8/UTF-16）。分章偏移与取章文本必须共用本入口。 */
        fun openBomSkipped(file: File, charset: Charset): BufferedReader {
            val fis = FileInputStream(file)
            val head = ByteArray(4)
            val n = fis.read(head)
            return if (n <= 0) {
                fis.close()
                BufferedReader(InputStreamReader(FileInputStream(file), charset))
            } else {
                val bom = CharsetDetector.bomLength(head.copyOf(n))
                val stream = SequenceInputStream(ByteArrayInputStream(head, bom, n - bom), fis)
                BufferedReader(InputStreamReader(stream, charset))
            }
        }
    }
}

/** 按需加载某一章正文：按字符偏移跳过（与 LineScanner 同一计数口径）后读取区间。 */
object ChapterTextLoader {

    fun load(path: String, charsetName: String, startOffset: Int, endOffset: Int): String {
        val file = File(path)
        TxtParser.openBomSkipped(file, Charset.forName(charsetName)).use { reader ->
            reader.skip(startOffset.toLong())
            val buf = StringBuilder((endOffset - startOffset).coerceAtLeast(0) + 16)
            var remaining = endOffset - startOffset
            val chunk = CharArray(8192)
            while (remaining > 0) {
                val want = minOf(chunk.size, remaining)
                val got = reader.read(chunk, 0, want)
                if (got < 0) break
                buf.append(chunk, 0, got)
                remaining -= got
            }
            return normalize(buf)
        }
    }

    /** 标准中文排版：删除全部空行与不可见假段落；段首两个全角空格缩进，章节标题不缩进。 */
    private fun normalize(sb: StringBuilder): String {
        val heading = Regex("^第.{0,6}[章节卷回]|^[序楔引终尾后番]")
        val out = StringBuilder(sb.length)
        var first = true
        for (line in sb.toString().lineSequence()) {
            val t = line.trim()
            // 空行与只含零宽/软连接等不可见字符的“假段落”一并跳过
            if (!t.any { ch -> !ch.isWhitespace() && ch.code !in 0x200B..0x200F && ch.code != 0xFEFF && ch.code != 0xAD }) continue
            if (first || heading.containsMatchIn(t)) {
                out.append(t).append('\n')
                first = false
            } else {
                out.append("　　").append(t).append('\n')
            }
        }
        return out.toString()
    }
}
