package com.moread.app.core.text

/**
 * 带上限的 gzip 解压：防止损坏包/解压炸弹把内存打爆（OOM 不可恢复）。
 * 默认上限 200MB——足够任何单本书籍，超过即视为异常文件拒绝。
 */
object SafeGzip {
    private const val DEFAULT_MAX = 200L * 1024 * 1024

    fun gunzipToString(data: ByteArray, maxOutBytes: Long = DEFAULT_MAX): String {
        // 预留常见压缩比的空间，但不超过 16MB（避免预留本身放大攻击面）
        val initCap = (data.size * 4L).coerceAtLeast(1 shl 16).coerceAtMost(16L shl 20).toInt()
        val out = java.io.ByteArrayOutputStream(initCap)
        java.util.zip.GZIPInputStream(data.inputStream()).use { gz ->
            val buf = ByteArray(1 shl 16)
            var total = 0L
            while (true) {
                val n = gz.read(buf)
                if (n < 0) break
                out.write(buf, 0, n)
                total += n
                if (total > maxOutBytes) {
                    throw java.io.IOException("解压超限（>${maxOutBytes / 1048576}MB），文件损坏")
                }
            }
        }
        return out.toString("UTF-8")
    }
}
