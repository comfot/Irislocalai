package com.iris.localai.ui.chat

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.dp
import com.iris.localai.data.BubbleStyle
import com.iris.localai.data.ThemeStyle
import com.iris.localai.ui.theme.LocalIrisThemeStyle

internal data class BubbleVisual(
    val shape: Shape,
    val color: Color,
    val contentColor: Color,
    val border: BorderStroke?,
    val padding: PaddingValues,
)

@Composable
internal fun bubbleVisual(isUser: Boolean, style: BubbleStyle): BubbleVisual {
    val scheme = MaterialTheme.colorScheme
    val themeStyle = LocalIrisThemeStyle.current
    val glass = themeStyle == ThemeStyle.LIQUID_GLASS
    val dark = scheme.onBackground.luminance() > 0.5f
    val shape = when (style) {
        BubbleStyle.ROUNDED -> RoundedCornerShape(18.dp)
        BubbleStyle.SQUARE -> RoundedCornerShape(6.dp)
        BubbleStyle.OUTLINED, BubbleStyle.MINIMAL -> RoundedCornerShape(18.dp)
    }
    val color = when {
        style == BubbleStyle.MINIMAL || style == BubbleStyle.OUTLINED -> Color.Transparent
        isUser -> if (glass) scheme.primary.copy(alpha = 0.40f) else scheme.primary
        else -> if (glass) scheme.surface.copy(alpha = 0.55f) else scheme.surfaceVariant
    }
    val contentColor = when {
        style == BubbleStyle.MINIMAL || style == BubbleStyle.OUTLINED ->
            if (isUser) scheme.primary else scheme.onSurface
        glass -> scheme.onSurface
        isUser -> scheme.onPrimary
        else -> scheme.onSurface
    }
    val border = when {
        style == BubbleStyle.OUTLINED ->
            BorderStroke(1.dp, if (isUser) scheme.primary else scheme.outline.copy(alpha = 0.6f))
        glass -> BorderStroke(1.dp, Color.White.copy(alpha = if (dark) 0.26f else 0.55f))
        else -> null
    }
    val padding = if (style == BubbleStyle.MINIMAL) {
        PaddingValues(horizontal = 2.dp, vertical = 4.dp)
    } else {
        PaddingValues(horizontal = 14.dp, vertical = 10.dp)
    }
    return BubbleVisual(shape, color, contentColor, border, padding)
}
