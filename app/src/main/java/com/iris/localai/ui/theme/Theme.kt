package com.iris.localai.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.lerp
import com.iris.localai.data.ThemeAccent
import com.iris.localai.data.ThemeStyle

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

/** 当前界面风格，供卡片、气泡等组件选择材质。 */
val LocalIrisThemeStyle = staticCompositionLocalOf { ThemeStyle.STANDARD }

@Composable
fun IrisTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    themeStyle: ThemeStyle = ThemeStyle.STANDARD,
    accent: ThemeAccent = ThemeAccent.TEAL,
    content: @Composable () -> Unit,
) {
    val scheme = when (themeStyle) {
        ThemeStyle.STANDARD -> standardScheme(darkTheme, accent)
        ThemeStyle.LIQUID_GLASS -> glassScheme(darkTheme, accent)
        ThemeStyle.E_INK -> inkScheme(darkTheme)
    }
    CompositionLocalProvider(LocalIrisThemeStyle provides themeStyle) {
        MaterialTheme(
            colorScheme = scheme,
            typography = Typography,
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(backgroundBrush(themeStyle, accent, darkTheme)),
            ) {
                content()
            }
        }
    }
}

private fun standardScheme(dark: Boolean, accent: ThemeAccent): androidx.compose.material3.ColorScheme {
    val base = if (dark) DarkColors else LightColors
    return base.copy(
        primary = Color(accent.primary(dark)),
        onPrimary = Color(accent.onPrimary(dark)),
        primaryContainer = Color(accent.container(dark)),
        onPrimaryContainer = Color(accent.onContainer(dark)),
        surfaceTint = Color(accent.primary(dark)),
    )
}

private fun glassScheme(dark: Boolean, accent: ThemeAccent): androidx.compose.material3.ColorScheme {
    val primary = Color(accent.primary(dark))
    val onPrimary = Color(accent.onPrimary(dark))
    val container = Color(accent.container(dark))
    val onContainer = Color(accent.onContainer(dark))
    return if (dark) {
        darkColorScheme(
            primary = primary,
            onPrimary = onPrimary,
            primaryContainer = container,
            onPrimaryContainer = onContainer,
            secondary = primary,
            onSecondary = onPrimary,
            surfaceTint = primary,
            background = Color.Transparent,
            onBackground = Color(0xFFF0F7F5),
            surface = Color(0x66151C1B),
            onSurface = Color(0xFFF2F7F6),
            surfaceVariant = Color(0x4D26302E),
            onSurfaceVariant = Color(0xFFC6D2CF),
            surfaceDim = Color(0x66101514),
            surfaceBright = Color(0x802A3533),
            surfaceContainerLowest = Color(0x4D0A1110),
            surfaceContainerLow = Color(0x66121A19),
            surfaceContainer = Color(0x7318201F),
            surfaceContainerHigh = Color(0x80222C2A),
            surfaceContainerHighest = Color(0x8C2C3735),
            outline = Color(0x66FFFFFF),
            outlineVariant = Color(0x33FFFFFF),
            error = Color(0xFFFF8A80),
        )
    } else {
        lightColorScheme(
            primary = primary,
            onPrimary = onPrimary,
            primaryContainer = container,
            onPrimaryContainer = onContainer,
            secondary = primary,
            onSecondary = onPrimary,
            surfaceTint = primary,
            background = Color.Transparent,
            onBackground = Color(0xFF10201D),
            surface = Color(0x73FFFFFF),
            onSurface = Color(0xFF10201D),
            surfaceVariant = Color(0x4DFFFFFF),
            onSurfaceVariant = Color(0xFF3F514D),
            surfaceDim = Color(0x66E8F2EF),
            surfaceBright = Color(0x99FFFFFF),
            surfaceContainerLowest = Color(0x40FFFFFF),
            surfaceContainerLow = Color(0x59FFFFFF),
            surfaceContainer = Color(0x66FFFFFF),
            surfaceContainerHigh = Color(0x73FFFFFF),
            surfaceContainerHighest = Color(0x80FFFFFF),
            outline = Color(0x99FFFFFF),
            outlineVariant = Color(0x66FFFFFF),
            error = Color(0xFFB3261E),
        )
    }
}

private fun inkScheme(dark: Boolean): androidx.compose.material3.ColorScheme = if (dark) {
    darkColorScheme(
        primary = Color.White,
        onPrimary = Color.Black,
        primaryContainer = Color(0xFF1A1A1A),
        onPrimaryContainer = Color.White,
        secondary = Color(0xFFD0D0D0),
        onSecondary = Color.Black,
        secondaryContainer = Color(0xFF1F1F1F),
        onSecondaryContainer = Color.White,
        tertiary = Color(0xFFBDBDBD),
        onTertiary = Color.Black,
        surfaceTint = Color.White,
        background = Color.Black,
        onBackground = Color.White,
        surface = Color.Black,
        onSurface = Color.White,
        surfaceVariant = Color(0xFF161616),
        onSurfaceVariant = Color(0xFFD0D0D0),
        surfaceDim = Color(0xFF050505),
        surfaceBright = Color(0xFF2A2A2A),
        surfaceContainerLowest = Color.Black,
        surfaceContainerLow = Color(0xFF0A0A0A),
        surfaceContainer = Color(0xFF111111),
        surfaceContainerHigh = Color(0xFF1A1A1A),
        surfaceContainerHighest = Color(0xFF222222),
        outline = Color(0xFFB3B3B3),
        outlineVariant = Color(0xFF3D3D3D),
        error = Color(0xFFE0E0E0),
    )
} else {
    lightColorScheme(
        primary = Color.Black,
        onPrimary = Color.White,
        primaryContainer = Color(0xFFE8E8E8),
        onPrimaryContainer = Color.Black,
        secondary = Color(0xFF333333),
        onSecondary = Color.White,
        secondaryContainer = Color(0xFFEDEDED),
        onSecondaryContainer = Color.Black,
        tertiary = Color(0xFF4D4D4D),
        onTertiary = Color.White,
        surfaceTint = Color.Black,
        background = Color.White,
        onBackground = Color.Black,
        surface = Color.White,
        onSurface = Color.Black,
        surfaceVariant = Color(0xFFF2F2F2),
        onSurfaceVariant = Color(0xFF333333),
        surfaceDim = Color(0xFFF5F5F5),
        surfaceBright = Color.White,
        surfaceContainerLowest = Color.White,
        surfaceContainerLow = Color(0xFFFAFAFA),
        surfaceContainer = Color(0xFFF5F5F5),
        surfaceContainerHigh = Color(0xFFEFEFEF),
        surfaceContainerHighest = Color(0xFFE8E8E8),
        outline = Color(0xFF4D4D4D),
        outlineVariant = Color(0xFFCCCCCC),
        error = Color(0xFF222222),
    )
}

private fun backgroundBrush(themeStyle: ThemeStyle, accent: ThemeAccent, dark: Boolean): Brush = when (themeStyle) {
    ThemeStyle.LIQUID_GLASS -> if (dark) {
        Brush.linearGradient(
            listOf(
                lerp(Color(0xFF07100F), Color(accent.primary(true)), 0.18f),
                Color(0xFF0B1413),
                lerp(Color(0xFF0B1413), Color(accent.primary(true)), 0.10f),
            ),
        )
    } else {
        Brush.linearGradient(
            listOf(
                lerp(Color(0xFFF4FFFC), Color(accent.primary(false)), 0.08f),
                Color(0xFFF7FAFF),
                lerp(Color(0xFFF8F4FF), Color(accent.primary(false)), 0.06f),
            ),
        )
    }
    ThemeStyle.E_INK -> SolidColor(if (dark) Color.Black else Color.White)
    ThemeStyle.STANDARD -> SolidColor(if (dark) DarkColors.background else LightColors.background)
}
