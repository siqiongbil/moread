package com.moread.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/*
 * 设计系统：书卷棕 + 羊皮纸暖色（ui-ux-pro-max 设计系统生成）
 * Primary #78716C 书卷棕 / Secondary #92400E 焦糖棕 / Accent #D97706 琥珀
 * Background #FFFBEB 羊皮纸 / Card #FFFFFF
 */
private val BookBrown = Color(0xFF78716C)
private val Caramel = Color(0xFF92400E)
private val Amber = Color(0xFFD97706)

private val LightScheme = lightColorScheme(
    primary = BookBrown,
    onPrimary = Color(0xFFFFFFFF),
    secondary = Caramel,
    onSecondary = Color(0xFFFFFFFF),
    tertiary = Amber,
    onTertiary = Color(0xFFFFFFFF),
    background = Color(0xFFFFFBEB),
    surface = Color(0xFFFFFBEB),
    surfaceVariant = Color(0xFFF6F0E4),
    onSurfaceVariant = Color(0xFF78716C),
    primaryContainer = Color(0xFFF0E8D8),
    onPrimaryContainer = Color(0xFF57514B),
    outlineVariant = Color(0xFFEEEDED),
)

private val DarkScheme = darkColorScheme(
    primary = Color(0xFFC4BFB8),
    onPrimary = Color(0xFF2E2A25),
    secondary = Color(0xFFF0B361),
    onSecondary = Color(0xFF2E1A00),
    tertiary = Color(0xFFF2B25C),
    background = Color(0xFF1C1917),
    surface = Color(0xFF1C1917),
    surfaceVariant = Color(0xFF2A2622),
    onSurfaceVariant = Color(0xFFA8A29E),
    primaryContainer = Color(0xFF38322C),
    onPrimaryContainer = Color(0xFFE0DAD2),
    outlineVariant = Color(0xFF3A352F),
)

@Composable
fun MoReadTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    // 不用系统动态取色：阅读应用需要稳定的书卷暖色系，不随壁纸漂移
    val scheme = if (darkTheme) DarkScheme else LightScheme
    MaterialTheme(colorScheme = scheme, content = content)
}
