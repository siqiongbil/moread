package com.moread.app.core.paginate

import android.text.StaticLayout
import android.text.TextPaint

/**
 * 章内分页：先用 StaticLayout 对整章排版拿到断行位置，再按页高把行切成页，
 * 每页用相同宽度/paint 对页文本子串重建 StaticLayout 供绘制（断行结果一致）。
 */
object Paginator {

    data class Metrics(
        val widthPx: Int,
        val heightPx: Int,
        val fontSizePx: Float,
        val lineSpacingMult: Float,
    ) {
        /** 分页缓存失效键 */
        val cacheKey: String get() = "$widthPx:$heightPx:$fontSizePx:$lineSpacingMult"
    }

    fun createPaint(m: Metrics, color: Int, typeface: android.graphics.Typeface? = null): TextPaint =
        TextPaint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            textSize = m.fontSizePx
            this.color = color
            this.typeface = typeface
        }

    fun buildPageLayout(text: String, metrics: Metrics, paint: TextPaint): StaticLayout =
        StaticLayout.Builder
            .obtain(text, 0, text.length, paint, metrics.widthPx)
            .setAlignment(android.text.Layout.Alignment.ALIGN_NORMAL)
            .setLineSpacing(0f, metrics.lineSpacingMult)
            .setIncludePad(false)
            .build()

    /**
     * 返回章内页面列表。页高以“本页第一行的 top”为基准；若单行本身超过页高
     * （超大字号），该行仍保留在本页，避免切出空页。
     */
    fun paginate(
        chapterIndex: Int,
        text: String,
        metrics: Metrics,
        paint: TextPaint,
    ): List<TextPage> {
        if (text.isEmpty()) {
            return listOf(TextPage(0, chapterIndex, 0, 0, text))
        }
        val full = buildPageLayout(text, metrics, paint)

        val pages = ArrayList<TextPage>(full.lineCount / 20 + 2)
        var pageStart = 0
        var pageFirstLine = 0
        for (i in 0 until full.lineCount) {
            val overflow = full.getLineBottom(i) - full.getLineTop(pageFirstLine) > metrics.heightPx
            if (overflow && i > pageFirstLine) {
                val lineEnd = full.getLineStart(i)
                if (lineEnd > pageStart) {
                    pages += TextPage(
                        index = pages.size,
                        chapterIndex = chapterIndex,
                        startOffset = pageStart,
                        endOffset = lineEnd,
                        text = text.substring(pageStart, lineEnd).trimEnd('\n'),
                    )
                    pageStart = lineEnd
                    pageFirstLine = i
                }
            }
        }
        if (pageStart < text.length || pages.isEmpty()) {
            pages += TextPage(
                index = pages.size,
                chapterIndex = chapterIndex,
                startOffset = pageStart,
                endOffset = text.length,
                text = if (pageStart < text.length) text.substring(pageStart).trimEnd('\n') else text,
            )
        }
        return pages
    }
}

/** 一页的内容。startOffset/endOffset 为章内（规范化文本）字符偏移。 */
data class TextPage(
    val index: Int,
    val chapterIndex: Int,
    val startOffset: Int,
    val endOffset: Int,
    val text: String,
)
