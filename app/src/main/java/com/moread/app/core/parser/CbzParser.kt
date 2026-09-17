package com.moread.app.core.parser

import java.io.File

/** CBZ（图片 zip）解析：页清单 + 按页取图。 */
object CbzParser {

    private val IMG = Regex("(?i)\\.(jpe?g|png|gif|webp|bmp)$")

    /** 数字按数值比较的自然排序（page2 < page10）。 */
    fun naturalCompare(a: String, b: String): Int {
        var i = 0
        var j = 0
        while (i < a.length && j < b.length) {
            val ca = a[i]
            val cb = b[j]
            if (ca.isDigit() && cb.isDigit()) {
                var i2 = i
                while (i2 < a.length && a[i2].isDigit()) i2++
                var j2 = j
                while (j2 < b.length && b[j2].isDigit()) j2++
                val na = a.substring(i, i2).trimStart('0')
                val nb = b.substring(j, j2).trimStart('0')
                val c = na.length.compareTo(nb.length).takeIf { it != 0 } ?: na.compareTo(nb)
                if (c != 0) return c
                i = i2
                j = j2
            } else {
                val c = ca.compareTo(cb)
                if (c != 0) return c
                i++
                j++
            }
        }
        return (a.length - i).compareTo(b.length - j)
    }

    fun listPages(file: File): List<String> = java.util.zip.ZipFile(file).use { zip ->
        zip.entries().asSequence()
            .filter { !it.isDirectory && IMG.containsMatchIn(it.name) }
            .map { it.name }
            .toList()
            .sortedWith(::naturalCompare)
    }

    fun pageBytes(file: File, name: String): ByteArray? =
        java.util.zip.ZipFile(file).use { zip ->
            zip.getEntry(name)?.let { zip.getInputStream(it).readBytes() }
        }
}
