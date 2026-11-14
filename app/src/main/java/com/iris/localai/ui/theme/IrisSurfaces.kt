package com.iris.localai.ui.theme

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.dp
import com.iris.localai.data.ThemeStyle

/** 统一的卡片容器：标准实色、液态玻璃半透明亮边、墨水屏细描边。 */
@Composable
fun IrisCard(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(16.dp),
    content: @Composable () -> Unit,
) {
    when (LocalIrisThemeStyle.current) {
        ThemeStyle.LIQUID_GLASS -> {
            val dark = MaterialTheme.colorScheme.onBackground.luminance() > 0.5f
            val surface = MaterialTheme.colorScheme.surface
            Box(
                modifier = modifier
                    .shadow(14.dp, shape, clip = false)
                    .clip(shape)
                    .background(
                        Brush.verticalGradient(
                            listOf(
                                surface.copy(alpha = if (dark) 0.55f else 0.78f),
                                surface.copy(alpha = if (dark) 0.34f else 0.52f),
                            ),
                        ),
                    )
                    .border(1.dp, Color.White.copy(alpha = if (dark) 0.32f else 0.70f), shape),
            ) {
                content()
            }
        }
        ThemeStyle.E_INK -> Surface(
            modifier = modifier,
            shape = shape,
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            tonalElevation = 0.dp,
            content = content,
        )
        ThemeStyle.STANDARD -> Surface(
            modifier = modifier,
            shape = shape,
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 1.dp,
            content = content,
        )
    }
}
