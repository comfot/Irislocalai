package com.iris.localai.ui.settings

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.iris.localai.memory.MemoryGraphData
import com.iris.localai.memory.MemoryGraphNode
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

private data class GraphNodeBox(
    val node: MemoryGraphNode,
    val center: Offset,
    val width: Float,
    val height: Float,
) {
    val rect: Rect get() = Rect(
        left = center.x - width / 2f,
        top = center.y - height / 2f,
        right = center.x + width / 2f,
        bottom = center.y + height / 2f,
    )
}

@Composable
internal fun MemoryGraphView(
    graph: MemoryGraphData,
    selectedNodeId: String?,
    onNodeClick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val textMeasurer = rememberTextMeasurer()
    val primary = MaterialTheme.colorScheme.primary
    val outline = MaterialTheme.colorScheme.outline
    val surface = MaterialTheme.colorScheme.surface
    val surfaceVariant = MaterialTheme.colorScheme.surfaceVariant
    val onSurface = MaterialTheme.colorScheme.onSurface
    val onPrimary = MaterialTheme.colorScheme.onPrimary
    val onSurfaceVariant = MaterialTheme.colorScheme.onSurfaceVariant
    var scale by remember { mutableFloatStateOf(1f) }
    var pan by remember { mutableStateOf(Offset.Zero) }
    val scaleState = rememberUpdatedState(scale)
    val panState = rememberUpdatedState(pan)

    LaunchedEffect(graph.nodes.size, graph.edges.size) {
        scale = 1f
        pan = Offset.Zero
    }

    if (graph.nodes.isEmpty()) {
        Box(
            modifier = modifier
                .fillMaxWidth()
                .height(220.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "还没有可展示的记忆节点",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .height(360.dp),
    ) {
        val widthPx = constraints.maxWidth.toFloat()
        val heightPx = constraints.maxHeight.toFloat()
        val layout = remember(graph.nodes, widthPx, heightPx) {
            layoutGraphNodes(graph.nodes, widthPx, heightPx)
        }
        val nodeMap = remember(layout) { layout.associateBy { it.node.memoryId } }
        val tapModifier = Modifier.pointerInput(layout) {
            detectTapGestures { tap ->
                val currentScale = scaleState.value
                val currentPan = panState.value
                val world = Offset(
                    (tap.x - currentPan.x) / currentScale,
                    (tap.y - currentPan.y) / currentScale,
                )
                layout.firstOrNull { it.rect.contains(world) }
                    ?.let { onNodeClick(it.node.memoryId) }
            }
        }
        val transformModifier = Modifier.pointerInput(Unit) {
            detectTransformGestures { centroid, panChange, zoom, _ ->
                val currentScale = scaleState.value
                val currentPan = panState.value
                val nextScale = (currentScale * zoom).coerceIn(0.45f, 2.6f)
                val ratio = nextScale / currentScale
                pan = centroid + (currentPan - centroid) * ratio + panChange
                scale = nextScale
            }
        }
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .then(tapModifier)
                .then(transformModifier),
        ) {
            withTransform({
                translate(pan.x, pan.y)
                scale(scale, scale, pivot = Offset.Zero)
            }) {
                graph.edges.forEach { edge ->
                    val source = nodeMap[edge.sourceId] ?: return@forEach
                    val target = nodeMap[edge.targetId] ?: return@forEach
                    val crossFolder = source.node.folder != target.node.folder
                    drawLine(
                        color = if (crossFolder) outline.copy(alpha = 0.55f) else primary.copy(alpha = 0.45f),
                        start = source.center,
                        end = target.center,
                        strokeWidth = (0.8f + edge.weight * 2.2f),
                        pathEffect = if (crossFolder) {
                            PathEffect.dashPathEffect(floatArrayOf(9f, 7f))
                        } else {
                            null
                        },
                    )
                }
                layout.forEach { box ->
                    val selected = box.node.memoryId == selectedNodeId
                    val fill = when {
                        !box.node.enabled -> surfaceVariant
                        selected -> primary
                        else -> surface
                    }
                    val textColor = if (selected) onPrimary else onSurface
                    val border = if (selected) primary else outline.copy(alpha = 0.45f)
                    drawRoundRect(
                        color = fill,
                        topLeft = Offset(box.rect.left, box.rect.top),
                        size = Size(box.width, box.height),
                        cornerRadius = CornerRadius(12f, 12f),
                    )
                    drawRoundRect(
                        color = border,
                        topLeft = Offset(box.rect.left, box.rect.top),
                        size = Size(box.width, box.height),
                        cornerRadius = CornerRadius(12f, 12f),
                        style = Stroke(width = if (selected) 2.5f else 1f),
                    )
                    val textLayout = textMeasurer.measure(
                        text = AnnotatedString(box.node.label),
                        style = TextStyle(fontSize = 11.sp, color = textColor),
                        constraints = Constraints(maxWidth = (box.width - 18f).toInt().coerceAtLeast(1)),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    drawText(
                        textLayoutResult = textLayout,
                        topLeft = Offset(
                            box.rect.left + (box.width - textLayout.size.width) / 2f,
                            box.rect.top + (box.height - textLayout.size.height) / 2f,
                        ),
                    )
                }
            }
        }
    }
}

private fun layoutGraphNodes(
    nodes: List<MemoryGraphNode>,
    width: Float,
    height: Float,
): List<GraphNodeBox> {
    val ordered = nodes.sortedWith(compareBy({ it.folder }, { it.label }))
    val count = ordered.size
    val radius = min(width, height) * if (count <= 2) 0.18f else 0.34f
    val center = Offset(width / 2f, height / 2f)
    return ordered.mapIndexed { index, node ->
        val angle = if (count == 1) {
            0.0
        } else {
            -Math.PI / 2.0 + (2.0 * Math.PI * index / count)
        }
        val labelLength = node.label.length.coerceIn(2, 18)
        val boxWidth = (labelLength * 13f + 26f).coerceIn(72f, 220f)
        GraphNodeBox(
            node = node,
            center = Offset(
                center.x + (cos(angle) * radius).toFloat(),
                center.y + (sin(angle) * radius).toFloat(),
            ),
            width = boxWidth,
            height = if (node.label.length > 9) 44f else 34f,
        )
    }
}
