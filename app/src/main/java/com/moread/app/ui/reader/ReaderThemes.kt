package com.moread.app.ui.reader

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb

/** 阅读主题（背景/正文色）。与 ReaderPrefs.themeId 对应。Argb 属性供自绘 Paint 使用。 */
data class ReaderTheme(
    val id: Int,
    val name: String,
    val background: Color,
    val text: Color,
    val dim: Color,
) {
    val bgArgb: Int get() = background.toArgb()
    val textArgb: Int get() = text.toArgb()
    val dimArgb: Int get() = dim.toArgb()
}

object ReaderThemes {
    val ALL = listOf(
        ReaderTheme(0, "纸张", Color(0xFFF7F4EC), Color(0xFF2B2B2B), Color(0xFF8A8578)),
        ReaderTheme(1, "夜间", Color(0xFF151515), Color(0xFFB8B8B8), Color(0xFF5A5A5A)),
        ReaderTheme(2, "护眼", Color(0xFFCBE5C6), Color(0xFF25402B), Color(0xFF5F7A66)),
        ReaderTheme(3, "羊皮纸", Color(0xFFEAD9B0), Color(0xFF4A3A22), Color(0xFF8C7A57)),
        ReaderTheme(4, "纯黑", Color(0xFF000000), Color(0xFF9C9C9C), Color(0xFF555555)),
    )

    val NIGHT_ID = 1

    fun byId(id: Int): ReaderTheme = ALL.firstOrNull { it.id == id } ?: ALL.first()
}
