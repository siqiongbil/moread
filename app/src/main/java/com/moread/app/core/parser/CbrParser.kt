package com.moread.app.core.parser

import java.io.File

/** CBR（RAR 图片包）解析：junrar（Apache-2.0）。页清单与取图接口与 CbzParser 对齐。 */
object CbrParser {

    private val IMG = Regex("(?i)\\.(jpe?g|png|gif|webp|bmp)$")

    private fun normalize(name: String) = name.replace('\\', '/')

    fun listPages(file: File): List<String> = com.github.junrar.Archive(file).use { a ->
        a.fileHeaders.asSequence()
            .filter { !it.isDirectory }
            .map { normalize(it.fileNameString) }
            .filter { IMG.containsMatchIn(it) }
            .toList()
            .sortedWith(CbzParser::naturalCompare)
    }

    fun pageBytes(file: File, name: String): ByteArray? = com.github.junrar.Archive(file).use { a ->
        a.fileHeaders.firstOrNull { normalize(it.fileNameString) == name }
            ?.let { h -> a.getInputStream(h)?.use { it.readBytes() } }
    }
}
