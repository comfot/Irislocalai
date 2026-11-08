package com.iris.localai.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// 与 IRIDIS 一致的明暗基底：浅色 #F7F7F7 + 白卡片，深色纯黑 + #1C1C1E 卡片。
private val LightColors = lightColorScheme(
    primary = IrisBlueLight,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE5F0FF),
    onPrimaryContainer = Color(0xFF0B6FD9),
    background = Color(0xFFF7F7F7),
    onBackground = Color.Black,
    surface = Color.White,
    onSurface = Color.Black,
    surfaceVariant = Color(0xFFF0F0F2),
    onSurfaceVariant = Color(0xFF5E5E66),
    outline = Color(0xFF8C8C93),
    error = Color(0xFFF23B3B),
)

private val DarkColors = darkColorScheme(
    primary = IrisBlueDark,
    onPrimary = Color.White,
    primaryContainer = Color(0xFF13294D),
    onPrimaryContainer = Color(0xFF7DB3FF),
    background = Color.Black,
    onBackground = Color.White,
    surface = Color(0xFF1C1C1E),
    onSurface = Color.White,
    surfaceVariant = Color(0xFF2C2C2E),
    onSurfaceVariant = Color(0xFFADADB2),
    outline = Color(0xFF8A8A8F),
    error = Color(0xFFFF5C5C),
)

@Composable
fun IrisTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        typography = Typography,
        content = content,
    )
}
