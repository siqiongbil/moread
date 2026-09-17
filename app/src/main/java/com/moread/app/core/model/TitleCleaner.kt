package com.moread.app.core.model

/**
 * 书名显示清洁（只影响展示，不改数据库）：
 * 1. 剥离站点水印前缀（域名样式，可叠加多个）："sxsy.org  sxsy.org 高冷青梅…" → "高冷青梅…"
 * 2. 《》包裹优先取内层："《仙子的修行》作者：karma085" → "仙子的修行"
 */
object TitleCleaner {

    private val SITE_PREFIX =
        Regex("^[a-zA-Z0-9.\\-]+\\.(?:org|com|net|cc|xyz|top|me|site|club|info|vip|fun|io)(?:[\\s　\\-]+|$)")

    fun clean(raw: String): String {
        var s = raw.trim()
        // 连续剥域名前缀（水印常叠加两三层）
        var guard = 0
        while (guard++ < 4) {
            val m = SITE_PREFIX.find(s) ?: break
            if (m.value.length >= s.length) break
            s = s.removePrefix(m.value).trim('　', ' ', '	', '-', '—')
            if (s.isEmpty()) return raw.trim()
        }
        // 书名号包裹：取内层
        Regex("《([^《》]{1,40})》").find(s)?.let { m ->
            if (m.range.first <= 1) s = m.groupValues[1]
        }
        return s.trim()
    }
}
