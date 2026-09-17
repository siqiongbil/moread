package com.moread.app.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val InkPrimary = Color(0xFF4A5A78)
private val LightScheme = lightColorScheme(
    primary = InkPrimary,
    secondary = Color(0xFF7986CB),
    surface = Color(0xFFFAFAF7),
    background = Color(0xFFFAFAF7),
)
private val DarkScheme = darkColorScheme(
    primary = Color(0xFFA8B4CC),
    secondary = Color(0xFF5C6BC0),
)

@Composable
fun MoReadTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    // 不用系统动态取色：阅读应用需要稳定、低饱和的墨色系，不随壁纸漂移
    val scheme = if (darkTheme) DarkScheme else LightScheme
    MaterialTheme(colorScheme = scheme, content = content)
}
