package com.moread.app.core.parser

import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import java.io.File

/**
 * PDF → 纯文本：PDFBox 按位置抽取视觉行，[PdfText.reflow] 把断行重组回段落，
 * 之后复用整条 TXT 管线（分章/排版/进度）。
 */
object PdfParser {

    fun extractText(file: File, onProgress: ((Int, Int) -> Unit)? = null): String {
        PDDocument.load(file).use { doc ->
            val total = doc.numberOfPages
            val stripper = PDFTextStripper()
            stripper.sortByPosition = true
            val sb = StringBuilder()
            var page = 1
            while (page <= total) {
                val end = minOf(page + 19, total)
                stripper.startPage = page
                stripper.endPage = end
                sb.append(stripper.getText(doc))
                sb.append('\n')
                onProgress?.invoke(end, total)
                page = end + 1
            }
            return PdfText.reflow(sb.toString())
        }
    }
}

/**
 * PDF 视觉行 → 段落回流。PDF 没有段落概念，只有每页排好的行：
 * - 满行（≥85% 主流行宽）与下一行拼接（中文不断词，直接续接；拉丁字母补空格）
 * - 短行、空行、带缩进的行视为段落边界
 * - 重复 ≥10 次的行（页眉/页脚）与纯页码剔除
 */
object PdfText {

    private val PAGE_NO = Regex("^[-–—·\\s]*[0-9０-９]{1,4}[-–—·\\s]*$")

    fun reflow(text: String): String {
        val rawLines = text.lines().map { it.trimEnd() }
        val freq = HashMap<String, Int>()
        for (l in rawLines) {
            val t = l.trim()
            if (t.isNotEmpty()) freq[t] = (freq[t] ?: 0) + 1
        }
        val junk = freq.filterValues { it >= 10 }.keys
        val lines = rawLines.filter { l ->
            val t = l.trim()
            t.isEmpty() || (t !in junk && !PAGE_NO.matches(t))
        }

        val lens = lines.filter { it.isNotBlank() }.map { it.trim().length }.sorted()
        val width = if (lens.isEmpty()) 0 else lens[lens.size * 9 / 10]
        val full = width * 85 / 100

        val paras = ArrayList<String>()
        val cur = StringBuilder()
        var prevShort = true
        fun flush() {
            if (cur.isNotEmpty()) {
                paras.add(cur.toString())
                cur.setLength(0)
            }
        }
        for (l in lines) {
            val t = l.trim()
            if (t.isEmpty()) {
                flush()
                prevShort = true
                continue
            }
            val startsNew = cur.isEmpty() || prevShort || l.first() == ' ' || l.first() == '　'
            if (startsNew) flush()
            if (cur.isNotEmpty() &&
                cur.last().isLetterOrDigit() && t.first().isLetterOrDigit() &&
                cur.last().code < 128 && t.first().code < 128
            ) {
                cur.append(' ')
            }
            cur.append(t)
            prevShort = t.length < full
        }
        flush()
        return paras.joinToString("\n")
    }
}
