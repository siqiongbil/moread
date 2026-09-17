package com.moread.app.core.parser

import org.mozilla.universalchardet.UniversalDetector
import java.io.InputStream
import java.nio.charset.Charset

/**
 * 编码检测：juniversalchardet（Apache-2.0）识别 + BOM 优先 + GB18030 兜底。
 * GB18030 是 GBK/GB2312 的超集，检测出 GBK 系一律按 GB18030 解码最稳。
 */
object CharsetDetector {

    fun detectAndOpen(bytes: ByteArray): Charset {
        readBom(bytes)?.let { return it }

        val detector = UniversalDetector(null)
        detector.handleData(bytes, 0, bytes.size)
        detector.dataEnd()
        val name = detector.detectedCharset
        detector.reset()

        val charset = when {
            name == null -> null
            name.equals("GB2312", true) || name.equals("GBK", true) || name.equals("GB18030", true) ->
                Charset.forName("GB18030")
            name.equals("ASCII", true) || name.equals("US-ASCII", true) ->
                Charsets.UTF_8 // 纯 ASCII 时按 UTF-8 处理，兼容后续非 ASCII 混入
            else -> runCatching { Charset.forName(name) }.getOrNull()
        }
        return charset ?: Charsets.UTF_8
    }

    fun detectFrom(stream: InputStream, probeBytes: Int = PROBE_SIZE): Charset {
        val head = ByteArray(probeBytes)
        var read = 0
        while (read < probeBytes) {
            val n = stream.read(head, read, probeBytes - read)
            if (n < 0) break
            read += n
        }
        return detectAndOpen(head.copyOf(read))
    }

    private fun readBom(bytes: ByteArray): Charset? = when {
        bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte() ->
            Charsets.UTF_8
        bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte() ->
            Charsets.UTF_16LE
        bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte() ->
            Charsets.UTF_16BE
        else -> null
    }

    /** UTF-8/UTF-16 BOM 的字节长度，打开流读取前需跳过。 */
    fun bomLength(bytes: ByteArray): Int = when {
        bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte() -> 3
        bytes.size >= 2 && (bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte() ||
            bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()) -> 2
        else -> 0
    }

    private const val PROBE_SIZE = 64 * 1024
}
