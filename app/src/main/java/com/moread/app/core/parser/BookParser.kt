package com.moread.app.core.parser

import com.moread.app.core.model.BookFormat
import java.io.File

/**
 * 格式解析 SPI。阶段 2 新增 EPUB/MOBI 时实现本接口并在
 * [com.moread.app.AppContainer] 的解析器注册表中登记即可接入书架与阅读器。
 */
interface BookParser {
    val format: BookFormat

    /** 扫描全书结构，产出目录（标题 + 边界），不把正文驻留内存。 */
    suspend fun parseToc(file: File): TocParseOutput
}

data class TocParseOutput(
    val charset: String,
    val totalChars: Int,
    /** 引擎分章结果之外附带的章节字符量，便于调用方入库。 */
    val chapterCharCounts: List<Int>,
)
