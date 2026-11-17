package com.iris.localai.ui.settings

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.*
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Divider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import android.util.Log
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import java.io.ByteArrayInputStream
import java.io.File
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.iris.localai.data.RemoteFetch
import com.iris.localai.plugin.InstalledPlugin
import com.iris.localai.ui.chat.PluginAiChatBridge
import com.iris.localai.ui.chat.WorkMarkdown
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

@Composable
internal fun PluginUiDialog(
    plugin: InstalledPlugin,
    moduleId: String,
    title: String,
    viewModel: PluginViewModel,
    onDismiss: () -> Unit,
) = PluginUiDialogHost(
    plugin = plugin,
    moduleId = moduleId,
    title = title,
    renderUi = { viewModel.renderPluginUi(plugin, moduleId) },
    dispatchUiEvent = { eventId, value ->
        viewModel.dispatchPluginUiEvent(plugin, moduleId, eventId, value)
    },
    callUiController = { controllerId, interfaceName, methodName, args ->
        viewModel.callPluginUiController(plugin, moduleId, controllerId, interfaceName, methodName, args)
    },
    callUiHandler = { eventId, payload ->
        viewModel.callPluginUiHandler(plugin, moduleId, eventId, payload)
    },
    uiInvalidations = viewModel.uiInvalidations,
    onDismiss = onDismiss,
)

private val LocalPluginUiControllerCall =
    staticCompositionLocalOf<suspend (String, String, String, String) -> String> {
        { _, _, _, _ -> "" }
    }

private val LocalPluginUiHandlerCall =
    staticCompositionLocalOf<suspend (String, String) -> String> {
        { _, _ -> "null" }
    }

private val LocalPluginUiAssetRoots = staticCompositionLocalOf<List<File>> { emptyList() }

@Composable
internal fun PluginWidgetSurface(
    plugin: InstalledPlugin,
    moduleId: String,
    title: String,
    renderUi: suspend () -> Result<String>,
    dispatchUiEvent: suspend (String, String?) -> Result<String>,
    callUiController: suspend (String, String, String, String) -> String = { _, _, _, _ -> "" },
    callUiHandler: suspend (String, String) -> String = { _, _ -> "null" },
    onClose: () -> Unit,
    onDrag: (Float, Float) -> Unit = { _, _ -> },
) {
    val context = LocalContext.current
    val assetRoots = remember(context, plugin.directory, plugin.id) {
        listOfNotNull(
            safePluginUiAssetDir(File(context.filesDir, "plugins"), plugin.directory),
            safePluginUiAssetDir(File(context.filesDir, "plugin_config"), plugin.id),
        )
    }
    val scope = rememberCoroutineScope()
    var tree by remember(plugin.id, moduleId) { mutableStateOf<PluginUiNode?>(null) }
    var error by remember(plugin.id, moduleId) { mutableStateOf<String?>(null) }
    var busy by remember(plugin.id, moduleId) { mutableStateOf(false) }
    var onLoadStarted by remember(plugin.id, moduleId) { mutableStateOf(false) }
    val dragModifier = Modifier.pointerInput(Unit) {
        detectDragGestures { change, drag ->
            change.consume()
            onDrag(drag.x, drag.y)
        }
    }

    fun apply(raw: String) {
        runCatching { parsePluginUiTree(raw) }
            .onSuccess { tree = it; error = null }
            .onFailure { error = it.message ?: "UI 解析失败" }
    }

    fun load() {
        busy = true
        scope.launch {
            renderUi().onSuccess(::apply).onFailure { error = it.message ?: "UI 渲染失败" }
            busy = false
        }
    }

    fun dispatch(handler: String?, valueJson: String? = null) {
        if (handler.isNullOrBlank()) return
        busy = true
        scope.launch {
            dispatchUiEvent(handler, valueJson).onSuccess(::apply)
                .onFailure { error = it.message ?: "UI 事件执行失败" }
            busy = false
        }
    }

    LaunchedEffect(plugin.id, moduleId) { load() }
    LaunchedEffect(tree) {
        if (!onLoadStarted) {
            val handler = tree?.let { findUiHandler(it, "onLoad") }
            if (handler != null) {
                onLoadStarted = true
                dispatch(handler)
            }
        }
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 6.dp,
        shadowElevation = 18.dp,
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .then(dragModifier)
                    .padding(start = 12.dp, end = 4.dp, top = 8.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = title.ifBlank { plugin.name },
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = "插件浮窗 · ${plugin.name}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                IconButton(onClick = { load() }, enabled = !busy) {
                    Icon(Icons.Outlined.Refresh, contentDescription = "刷新")
                }
                IconButton(onClick = onClose) {
                    Icon(Icons.Outlined.Close, contentDescription = "关闭浮窗")
                }
            }
            if (busy) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            error?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(horizontal = 12.dp),
                )
            }
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                tree?.let {
                    CompositionLocalProvider(
                        LocalPluginUiControllerCall provides callUiController,
                        LocalPluginUiHandlerCall provides callUiHandler,
                        LocalPluginUiAssetRoots provides assetRoots,
                    ) { PluginUiNodeView(it, ::dispatch) }
                }
            }
        }
    }
}

@Composable
internal fun PluginInlineUi(raw: String, modifier: Modifier = Modifier) {
    val node = remember(raw) { parsePluginUiTree(raw) }
    Box(modifier = modifier) { PluginUiNodeView(node, { _, _ -> }) }
}

@Composable
internal fun PluginUiDialogHost(
    plugin: InstalledPlugin,
    moduleId: String,
    title: String,
    renderUi: suspend () -> Result<String>,
    dispatchUiEvent: suspend (String, String?) -> Result<String>,
    callUiController: suspend (String, String, String, String) -> String = { _, _, _, _ -> "" },
    callUiHandler: suspend (String, String) -> String = { _, _ -> "null" },
    onDismiss: () -> Unit,
    uiInvalidations: Flow<String> = emptyFlow(),
) {
    val scope = rememberCoroutineScope()
    var tree by remember(plugin.id, moduleId) { mutableStateOf<PluginUiNode?>(null) }
    var error by remember(plugin.id, moduleId) { mutableStateOf<String?>(null) }
    var busy by remember(plugin.id, moduleId) { mutableStateOf(false) }
    var onLoadStarted by remember(plugin.id, moduleId) { mutableStateOf(false) }

    fun apply(raw: String) {
        runCatching { parsePluginUiTree(raw) }
            .onSuccess { tree = it; error = null }
            .onFailure { error = it.message ?: "UI 解析失败" }
    }

    fun load() {
        busy = true
        scope.launch {
            renderUi()
                .onSuccess(::apply)
                .onFailure { error = it.message ?: "UI 渲染失败" }
            busy = false
        }
    }

    fun dispatch(handler: String?, valueJson: String? = null) {
        if (handler.isNullOrBlank()) return
        busy = true
        scope.launch {
            dispatchUiEvent(handler, valueJson)
                .onSuccess(::apply)
                .onFailure { error = it.message ?: "UI 事件执行失败" }
            busy = false
        }
    }

    LaunchedEffect(plugin.id, moduleId) { load() }

    LaunchedEffect(tree) {
        if (!onLoadStarted) {
            val handler = tree?.let { findUiHandler(it, "onLoad") }
            if (handler != null) {
                onLoadStarted = true
                dispatch(handler)
            }
        }
    }

    LaunchedEffect(plugin.id, moduleId) {
        uiInvalidations.collect { id ->
            if (id == moduleId) {
                delay(60)
                load()
            }
        }
    }

    val context = LocalContext.current
    val assetRoots = remember(context, plugin.directory, plugin.id) {
        listOfNotNull(
            safePluginUiAssetDir(File(context.filesDir, "plugins"), plugin.directory),
            safePluginUiAssetDir(File(context.filesDir, "plugin_config"), plugin.id),
        )
    }
    var panelFraction by rememberSaveable { mutableStateOf(0.92f) }
    val displayTitle = title.ifBlank { plugin.name }
    val boundedContent = tree?.let(::containsBoundedContent) == true
    val contentScrollState = rememberScrollState()

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            val screenHeightPx = with(LocalDensity.current) { maxHeight.toPx() }.coerceAtLeast(1f)
            val dragState = rememberDraggableState { delta ->
                panelFraction = (panelFraction - delta / screenHeightPx).coerceIn(0.3f, 1f)
            }
            val finishDrag: suspend () -> Unit = {
                if (panelFraction < 0.6f) {
                    onDismiss()
                } else {
                    panelFraction = if (panelFraction > 0.92f) 1f else panelFraction.coerceIn(0.6f, 1f)
                }
            }
            val dragModifier = Modifier.draggable(
                state = dragState,
                orientation = Orientation.Vertical,
                onDragStopped = { _ -> finishDrag() },
            )
            Surface(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .fillMaxHeight(panelFraction.coerceAtLeast(0.3f)),
                shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 6.dp,
                shadowElevation = 24.dp,
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .then(if (panelFraction > 0.98f) Modifier.statusBarsPadding() else Modifier)
                        .navigationBarsPadding(),
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(24.dp)
                            .then(dragModifier),
                        contentAlignment = Alignment.Center,
                    ) {
                        Box(
                            modifier = Modifier
                                .width(44.dp)
                                .height(4.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)),
                        )
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .then(dragModifier)
                            .padding(start = 16.dp, end = 8.dp, top = 2.dp, bottom = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = displayTitle,
                                style = MaterialTheme.typography.titleMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = "Operit Compose DSL · ${plugin.name}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        IconButton(onClick = { load() }, enabled = !busy) {
                            Icon(
                                imageVector = Icons.Outlined.Refresh,
                                contentDescription = "刷新插件界面",
                                tint = if (busy) {
                                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                            )
                        }
                        IconButton(onClick = onDismiss) {
                            Icon(
                                imageVector = Icons.Outlined.Close,
                                contentDescription = "关闭插件界面",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    if (busy) {
                        LinearProgressIndicator(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp),
                        )
                    }
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .then(if (boundedContent) Modifier else Modifier.verticalScroll(contentScrollState))
                            .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        error?.let {
                            Text(
                                text = it,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                        tree?.let {
                            CompositionLocalProvider(
                                LocalPluginUiControllerCall provides callUiController,
                                LocalPluginUiHandlerCall provides callUiHandler,
                                LocalPluginUiAssetRoots provides assetRoots,
                            ) {
                                PluginUiNodeView(it, ::dispatch)
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun containsBoundedContent(node: PluginUiNode): Boolean =
    node.type == "LazyColumn" ||
        (node.type == "WebView" && node.props.bool("fillMaxSize") == true) ||
        node.children.any(::containsBoundedContent)

private data class PluginUiNode(
    val type: String,
    val props: JsonObject,
    val children: List<PluginUiNode>,
)

private fun parsePluginUiTree(raw: String): PluginUiNode {
    val root = Json.parseToJsonElement(raw).jsonObject
    val tree = root["tree"] ?: root
    return parsePluginUiNode(tree) ?: PluginUiNode("Column", JsonObject(emptyMap()), emptyList())
}

private fun parsePluginUiNode(element: kotlinx.serialization.json.JsonElement?): PluginUiNode? {
    if (element is JsonPrimitive && element.isString) {
        val text = element.content
        return text.takeIf { it.isNotBlank() }
            ?.let { PluginUiNode("Text", buildJsonObject { put("text", it) }, emptyList()) }
    }
    val obj = element as? JsonObject ?: return null
    val type = obj.string("type") ?: return null
    val props = when (val rawProps = obj["props"]) {
        is JsonObject -> rawProps
        is JsonPrimitive -> JsonObject(mapOf("text" to rawProps))
        else -> JsonObject(emptyMap())
    }
    val topLevelText = obj["text"] as? JsonPrimitive
    val mergedProps = if (props["text"] == null && topLevelText != null) {
        JsonObject(props + ("text" to topLevelText))
    } else {
        props
    }
    val children = (obj["children"] as? JsonArray)
        ?.mapNotNull(::parsePluginUiNode)
        .orEmpty()
    return PluginUiNode(type, mergedProps, children)
}

private fun findUiHandler(node: PluginUiNode, key: String): String? {
    (node.props[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }?.let { return it }
    node.children.forEach { child ->
        findUiHandler(child, key)?.let { return it }
    }
    return null
}

private fun JsonObject.string(key: String): String? =
    (this[key] as? JsonPrimitive)?.contentOrNull

private fun JsonObject.bool(key: String): Boolean? =
    (this[key] as? JsonPrimitive)?.booleanOrNull

private fun JsonObject.number(key: String): Float? =
    (this[key] as? JsonPrimitive)?.floatOrNull

private fun JsonObject.int(key: String): Int? =
    (this[key] as? JsonPrimitive)?.intOrNull

private fun JsonObject.handler(key: String): String? =
    (this[key] as? JsonPrimitive)?.contentOrNull

@Composable
private fun PluginUiNodeView(
    node: PluginUiNode,
    onEvent: (String?, String?) -> Unit,
) {
    val props = node.props
    val clickableHandler = props.handler("clickable")
    val fixedHeight = props.number("height")
    val fixedWidth = props.number("width")
    val fixedSize = props.number("size")
    val clipRadius = (props["clip"] as? JsonObject)?.number("cornerRadius")
    val hasWebViewChild = node.children.any { it.type == "WebView" }
    val modifier = Modifier
        .then(if (props.bool("fillMaxSize") == true) Modifier.fillMaxSize() else Modifier)
        .then(if (props.bool("fillMaxWidth") == true) Modifier.fillMaxWidth() else Modifier)
        .then(if (props.bool("fillMaxHeight") == true) Modifier.fillMaxHeight() else Modifier)
        .then(
            if (fixedWidth != null && fixedWidth > 0f && node.type != "Spacer" && node.type != "WebView") {
                Modifier.width(fixedWidth.dp)
            } else {
                Modifier
            },
        )
        .then(
            if (clickableHandler != null) {
                Modifier.clickable { onEvent(clickableHandler, null) }
            } else {
                Modifier
            },
        )
        .then(
            if (node.type != "Spacer" && node.type != "WebView" && fixedHeight != null) {
                Modifier.height(fixedHeight.dp)
            } else {
                Modifier
            },
        )
        .then(if (fixedSize != null) Modifier.size(fixedSize.dp) else Modifier)
        .then(if (clipRadius != null && !hasWebViewChild) Modifier.clip(RoundedCornerShape(clipRadius.dp)) else Modifier)
        .padding(paddingOf(props))
    when (node.type) {
        "Column", "FlowColumn" -> Column(
            modifier = modifier,
            verticalArrangement = spacingOf(props),
            horizontalAlignment = horizontalAlignmentOf(props.string("horizontalAlignment")),
        ) {
            node.children.forEach { child ->
                val weight = child.props.number("weight")
                if (weight != null && weight > 0f) {
                    Box(modifier = Modifier.weight(weight)) { PluginUiNodeView(child, onEvent) }
                } else {
                    PluginUiNodeView(child, onEvent)
                }
            }
        }

        "LazyColumn" -> {
            val listState = rememberLazyListState()
            val autoScrollToEnd = props.bool("autoScrollToEnd") == true
            LaunchedEffect(node.children.size, autoScrollToEnd) {
                if (autoScrollToEnd && node.children.isNotEmpty()) {
                    listState.scrollToItem(node.children.lastIndex)
                }
            }
            LazyColumn(
                modifier = modifier,
                state = listState,
                verticalArrangement = spacingOf(props),
                horizontalAlignment = horizontalAlignmentOf(props.string("horizontalAlignment")),
                contentPadding = PaddingValues(0.dp),
            ) {
                items(node.children) { child -> PluginUiNodeView(child, onEvent) }
            }
        }

        "Row", "FlowRow" -> Row(
            modifier = modifier,
            horizontalArrangement = horizontalSpacingOf(props),
            verticalAlignment = verticalAlignmentOf(props.string("verticalAlignment")),
        ) {
            node.children.forEach { child ->
                val weight = child.props.number("weight")
                if (weight != null && weight > 0f) {
                    Box(modifier = Modifier.weight(weight)) { PluginUiNodeView(child, onEvent) }
                } else {
                    PluginUiNodeView(child, onEvent)
                }
            }
        }

        "LazyRow" -> LazyRow(
            modifier = modifier,
            horizontalArrangement = Arrangement.Start,
            verticalAlignment = verticalAlignmentOf(props.string("verticalAlignment")),
            contentPadding = PaddingValues(0.dp),
        ) {
            items(node.children) { child -> PluginUiNodeView(child, onEvent) }
        }

        "Box" -> Box(
            modifier = modifier,
            contentAlignment = boxAlignmentOf(props.string("contentAlignment") ?: props.string("alignment")),
        ) { node.children.forEach { PluginUiNodeView(it, onEvent) } }

        "Spacer" -> Spacer(
            modifier = Modifier
                .width((props.number("width") ?: 0f).dp)
                .height((props.number("height") ?: 8f).dp),
        )

        "Text" -> Text(
            text = props.string("text")
                ?: props.string("content")
                ?: node.children.firstOrNull()?.props?.string("text").orEmpty(),
            modifier = modifier,
            style = textStyleOf(props.string("style")),
            color = colorOfElement(props["color"]) ?: MaterialTheme.colorScheme.onSurface,
            fontSize = props.number("fontSize")?.sp ?: TextUnit.Unspecified,
            fontWeight = when (props.string("fontWeight")) {
                "bold" -> FontWeight.Bold
                "medium", "semiBold" -> FontWeight.SemiBold
                else -> null
            },
            maxLines = props.int("maxLines") ?: Int.MAX_VALUE,
            overflow = TextOverflow.Ellipsis,
        )

        "Button", "TextButton" -> {
            val text = props.string("text")
                ?: props.string("content")
                ?: props.string("label")
                ?: node.children.firstOrNull()?.props?.string("text")
            val enabled = props.bool("enabled") != false
            val onClick = { onEvent(props.handler("onClick"), null) }
            if (node.type == "TextButton") {
                TextButton(onClick = onClick, modifier = modifier, enabled = enabled) {
                    Text(text.orEmpty())
                }
            } else {
                Button(onClick = onClick, modifier = modifier, enabled = enabled) {
                    Text(text.orEmpty())
                }
            }
        }

        "TextField", "OutlinedTextField" -> {
            val declaredMaxLines = props.int("maxLines")
            val singleLine = props.bool("singleLine")
                ?: (props.bool("multiLine") != true && (declaredMaxLines == null || declaredMaxLines <= 1))
            OutlinedTextField(
                value = props.string("value").orEmpty(),
                onValueChange = { value ->
                    onEvent(props.handler("onValueChange"), JsonPrimitive(value).toString())
                },
                modifier = modifier,
                enabled = props.bool("enabled") != false,
                label = props.string("label")?.let { { Text(it) } },
                placeholder = props.string("placeholder")?.let { { Text(it) } },
                singleLine = singleLine,
                minLines = if (singleLine) 1 else props.int("minLines") ?: 1,
                maxLines = if (singleLine) 1 else declaredMaxLines ?: Int.MAX_VALUE,
            )
        }

        "Switch" -> Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
            if (props.string("label") != null) {
                Text(props.string("label").orEmpty(), modifier = Modifier.weight(1f))
            }
            Switch(
                checked = props.bool("checked") == true,
                onCheckedChange = { value ->
                    onEvent(props.handler("onCheckedChange"), JsonPrimitive(value).toString())
                },
                enabled = props.bool("enabled") != false,
            )
        }

        "Slider" -> {
            val range = props["valueRange"] as? JsonArray
            val start = (range?.getOrNull(0) as? JsonPrimitive)?.floatOrNull ?: 0f
            val end = (range?.getOrNull(1) as? JsonPrimitive)?.floatOrNull ?: 1f
            androidx.compose.material3.Slider(
                value = (props.number("value") ?: start).coerceIn(start, end),
                onValueChange = { value ->
                    onEvent(props.handler("onValueChange"), JsonPrimitive(value).toString())
                },
                valueRange = start..end,
                steps = props.int("steps") ?: 0,
                modifier = modifier,
                enabled = props.bool("enabled") != false,
            )
        }

        "RadioButton" -> androidx.compose.material3.RadioButton(
            selected = props.bool("selected") == true,
            onClick = { onEvent(props.handler("onClick"), null) },
            modifier = modifier,
            enabled = props.bool("enabled") != false,
        )

        "Checkbox" -> Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
            Text(props.string("label").orEmpty(), modifier = Modifier.weight(1f))
            androidx.compose.material3.Checkbox(
                checked = props.bool("checked") == true,
                onCheckedChange = { value ->
                    onEvent(props.handler("onCheckedChange"), JsonPrimitive(value).toString())
                },
                enabled = props.bool("enabled") != false,
            )
        }

        "IconButton" -> IconButton(
            onClick = { onEvent(props.handler("onClick"), null) },
            modifier = modifier,
            enabled = props.bool("enabled") != false,
        ) {
            val child = node.children.firstOrNull()
            if (child != null) {
                PluginUiNodeView(child, onEvent)
            } else {
                Icon(iconOf(props.string("name")), contentDescription = props.string("contentDescription"))
            }
        }

        "Icon" -> Icon(
            imageVector = iconOf(props.string("name")),
            contentDescription = props.string("contentDescription"),
            tint = colorOfElement(props["tint"]) ?: MaterialTheme.colorScheme.primary,
            modifier = modifier.size((props.number("size") ?: 20f).dp),
        )

        "Surface", "Card" -> {
            val base = colorOfElement(props["containerColor"]) ?: MaterialTheme.colorScheme.surfaceVariant
            val alpha = props.number("alpha")
            Surface(
                modifier = modifier
                    .then(
                        if (props.handler("onClick") != null) {
                            Modifier.clickable { onEvent(props.handler("onClick"), null) }
                        } else {
                            Modifier
                        },
                    ),
                shape = shapeOf(props),
                color = if (alpha != null) base.copy(alpha = alpha.coerceIn(0f, 1f)) else base,
                tonalElevation = 1.dp,
            ) {
                Column(
                    modifier = Modifier.padding(10.dp),
                    verticalArrangement = spacingOf(props),
                ) { node.children.forEach { PluginUiNodeView(it, onEvent) } }
            }
        }

        "Divider", "HorizontalDivider" -> Divider(modifier = modifier.fillMaxWidth())
        "VerticalDivider" -> Divider(modifier = Modifier.width(1.dp).height(24.dp))

        "LinearProgressIndicator" -> LinearProgressIndicator(modifier = modifier.fillMaxWidth())
        "CircularProgressIndicator" -> CircularProgressIndicator(modifier = modifier.size(24.dp))

        "AlertDialog", "Dialog" -> PluginDialogNode(node, onEvent)

        "Image" -> PluginImage(props, modifier)

        "AiChat" -> PluginAiChat(props, modifier)

        "Markdown" -> WorkMarkdown(
            text = props.string("text").orEmpty(),
            modifier = modifier,
        )

        "WebView" -> {
            val url = props.string("url") ?: props.string("src").orEmpty()
            val html = props.string("html")
            val controllerId = props.string("controllerId").orEmpty()
            val interfaces = (props["controllerInterfaces"] as? JsonArray)
                ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
                .orEmpty()
            val baseUrl = props.string("baseUrl") ?: "about:blank"
            val shim = controllerShim(controllerId, interfaces)
            val controllerCall = LocalPluginUiControllerCall.current
            val handlerCall = LocalPluginUiHandlerCall.current
            val interceptHandler = props.handler("onInterceptRequest")
            val pageFinishedHandler = props.handler("onPageFinished")
            val overrideHandler = props.handler("onShouldOverrideUrlLoading")
            val lifecycleHandler = props.handler("onLifecycleEvent")
            val scope = rememberCoroutineScope()
            var lifecycleFired by remember { mutableStateOf(false) }
            LaunchedEffect(lifecycleHandler) {
                if (!lifecycleFired && lifecycleHandler != null) {
                    lifecycleFired = true
                    runCatching { handlerCall(lifecycleHandler, "{\"type\":\"created\"}") }
                }
            }
            AndroidView(
                factory = { context ->
                    @Suppress("SetJavaScriptEnabled")
                    val lightConfig = Configuration(context.resources.configuration).apply {
                        uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or Configuration.UI_MODE_NIGHT_NO
                    }
                    val lightContext = context.createConfigurationContext(lightConfig)
                    val webView = WebView(lightContext)
                    webView.setLayerType(android.view.View.LAYER_TYPE_SOFTWARE, null)
                    webView.tag = null
                    webView.webViewClient = object : WebViewClient() {
                        override fun onPageFinished(view: WebView, url: String?) {
                            Log.d("PluginWebView", "finished ${view.url} html=${view.tag != null} h=${view.height} content=${view.contentHeight}")
                            if (pageFinishedHandler != null) {
                                scope.launch {
                                    runCatching { handlerCall(pageFinishedHandler, "{}") }
                                }
                            }
                            view.evaluateJavascript(
                                "(function(){var b=document.querySelector('.bar');var r=b?b.getBoundingClientRect():null;return document.body ? document.body.innerHTML.length + ':' + document.body.innerText.slice(0,80) + ':' + document.body.clientHeight + '/' + innerHeight + ':bar=' + (r?r.top+'x'+r.height:'none') : 'NO_BODY'})()",
                            ) { value -> Log.d("PluginWebView", "body=$value") }
                        }

                        override fun onReceivedError(
                            view: WebView,
                            request: WebResourceRequest,
                            error: android.webkit.WebResourceError,
                        ) {
                            Log.e("PluginWebView", "error ${error.errorCode} ${error.description} ${request.url}")
                        }
                        override fun shouldInterceptRequest(
                            view: WebView,
                            request: WebResourceRequest,
                        ): WebResourceResponse? {
                            val handler = interceptHandler ?: return null
                            val payload = buildJsonObject {
                                put("url", request.url?.toString().orEmpty())
                                put("method", request.method.orEmpty())
                                put("isForMainFrame", request.isForMainFrame)
                                putJsonObject("headers") {
                                    request.requestHeaders.forEach { (key, value) -> put(key, value) }
                                }
                            }.toString()
                            val raw = runCatching {
                                runBlocking { handlerCall(handler, payload) }
                            }.getOrNull() ?: return null
                            return webResponseOf(raw)
                        }

                        override fun shouldOverrideUrlLoading(
                            view: WebView,
                            request: WebResourceRequest,
                        ): Boolean {
                            val handler = overrideHandler ?: return false
                            val payload = buildJsonObject {
                                put("url", request.url?.toString().orEmpty())
                                put("method", request.method.orEmpty())
                                put("isForMainFrame", request.isForMainFrame)
                            }.toString()
                            val raw = runCatching {
                                runBlocking { handlerCall(handler, payload) }
                            }.getOrNull() ?: return false
                            val action = runCatching {
                                Json.parseToJsonElement(raw).jsonObject["action"]?.jsonPrimitive?.contentOrNull
                            }.getOrNull()
                            return action == "cancel"
                        }
                    }
                    webView.addJavascriptInterface(
                        object {
                            @JavascriptInterface
                            fun call(
                                requestId: String,
                                calledController: String,
                                interfaceName: String,
                                methodName: String,
                                argsJson: String,
                            ): String {
                                scope.launch {
                                    val result = runCatching {
                                        controllerCall(calledController, interfaceName, methodName, argsJson)
                                    }.getOrElse { error ->
                                        """{"error":${JsonPrimitive(error.message ?: "UI 接口调用失败")}}"""
                                    }
                                    webView.evaluateJavascript(
                                        "window.__irisPluginUiResolve && window.__irisPluginUiResolve(" +
                                            JsonPrimitive(requestId) + ", " + JsonPrimitive(result) + ")",
                                        null,
                                    )
                                }
                                return ""
                            }
                        },
                        "IrisPluginUiHost",
                    )
                    webView.apply {
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.useWideViewPort = false
                        settings.loadWithOverviewMode = false
                        settings.setSupportZoom(false)
                        settings.builtInZoomControls = false
                        settings.allowFileAccess = props.bool("allowFileAccess") == true
                        settings.allowContentAccess = true
                        settings.allowFileAccessFromFileURLs = props.bool("allowFileAccessFromFileURLs") == true
                        settings.allowUniversalAccessFromFileURLs = props.bool("allowUniversalAccessFromFileURLs") == true
                        settings.mediaPlaybackRequiresUserGesture = props.bool("mediaPlaybackRequiresUserGesture") != false
                        settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                        @Suppress("DEPRECATION")
                        settings.forceDark = WebSettings.FORCE_DARK_OFF
                        setBackgroundColor(android.graphics.Color.TRANSPARENT)
                    }
                },
                update = { view ->
                    if (!html.isNullOrBlank()) {
                        if (view.tag != html) {
                            view.tag = html
                            val effectiveBase = if (baseUrl.startsWith("file://")) "http://localhost/" else baseUrl
                            view.post {
                                val htmlFix = """
                                    <style>
                                      html,body{height:100%!important;min-height:0!important;margin:0!important;overflow:hidden!important;}
                                      .bar{position:absolute!important;top:0!important;left:0!important;right:0!important;bottom:auto!important;width:100%!important;height:64px!important;max-height:64px!important;}
                                    </style>
                                """.trimIndent()
                                view.loadDataWithBaseURL(effectiveBase, shim + htmlFix + html, "text/html", "utf-8", null)
                            }
                        }
                    } else if (url.isNotBlank() && view.tag != url) {
                        view.tag = url
                        view.post { view.loadUrl(url) }
                    }
                },
                modifier = if (props.bool("fillMaxSize") == true) {
                    modifier.fillMaxSize()
                } else if (props.number("height") != null) {
                    modifier.fillMaxWidth().height(props.number("height")!!.dp)
                } else {
                    modifier.fillMaxWidth().height(64.dp)
                },
            )
        }

        else -> Column(modifier = modifier, verticalArrangement = spacingOf(props)) {
            node.children.forEach { PluginUiNodeView(it, onEvent) }
        }
    }
}

private const val MAX_PLUGIN_IMAGE_BYTES = 12L * 1024L * 1024L

private sealed interface PluginImageState {
    data object Loading : PluginImageState
    data class Ready(val bitmap: Bitmap) : PluginImageState
    data class Failed(val message: String) : PluginImageState
}

@Composable
private fun PluginImage(props: JsonObject, modifier: Modifier) {
    val source = props.string("url")
        ?: props.string("src")
        ?: props.string("uri")
        ?: props.string("path")
        ?: props.string("fileUri")
        ?: props.string("file")
        ?: props.string("data")
        ?: props.string("icon")
        ?: props.string("name")
        ?: ""
    val contentDescription = props.string("contentDescription") ?: props.string("description")
    val roots = LocalPluginUiAssetRoots.current
    val state by produceState<PluginImageState>(PluginImageState.Loading, source, roots) {
        value = withContext(Dispatchers.IO) {
            try {
                PluginImageState.Ready(loadPluginImage(source, roots))
            } catch (error: Throwable) {
                if (error is kotlinx.coroutines.CancellationException) throw error
                PluginImageState.Failed(error.message ?: "图片加载失败")
            }
        }
    }
    val hasExplicitHeight = props.number("height") != null || props.number("size") != null
    val hasExplicitWidth = props.number("width") != null
    when (val current = state) {
        PluginImageState.Loading -> Box(
            modifier = when {
                hasExplicitHeight -> modifier
                hasExplicitWidth -> modifier.height(160.dp)
                else -> modifier.fillMaxWidth().height(160.dp)
            },
            contentAlignment = Alignment.Center,
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                CircularProgressIndicator(modifier = Modifier.size(24.dp))
                Text(
                    text = "图片加载中…",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        is PluginImageState.Failed -> Box(
            modifier = when {
                hasExplicitHeight -> modifier
                hasExplicitWidth -> modifier.height(120.dp)
                else -> modifier.fillMaxWidth().height(120.dp)
            },
            contentAlignment = Alignment.Center,
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Icon(
                    imageVector = Icons.Outlined.Warning,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(20.dp),
                )
                Text(
                    text = current.message,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        is PluginImageState.Ready -> {
            val bitmap = current.bitmap
            val ratio = bitmap.width.toFloat() / bitmap.height.coerceAtLeast(1).toFloat()
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = contentDescription,
                contentScale = ContentScale.Fit,
                modifier = when {
                    hasExplicitHeight -> modifier
                    hasExplicitWidth -> modifier.aspectRatio(ratio.coerceAtLeast(0.1f))
                    else -> modifier.fillMaxWidth().heightIn(max = 420.dp).aspectRatio(ratio.coerceAtLeast(0.1f))
                },
            )
        }
    }
}

private suspend fun loadPluginImage(source: String, roots: List<File>): Bitmap {
    val trimmed = source.trim()
    require(trimmed.isNotEmpty()) { "图片地址为空" }
    val bytes = when {
        trimmed.startsWith("data:", ignoreCase = true) -> decodePluginImageDataUri(trimmed)
        trimmed.startsWith("http://", ignoreCase = true) ||
            trimmed.startsWith("https://", ignoreCase = true) -> RemoteFetch.bytes(trimmed, MAX_PLUGIN_IMAGE_BYTES)
        else -> {
            val file = resolvePluginImageFile(trimmed, roots)
                ?: throw IllegalArgumentException("本地图片仅允许插件目录内的文件")
            require(file.length() <= MAX_PLUGIN_IMAGE_BYTES) { "图片超过 12MB 限制" }
            file.readBytes()
        }
    }
    require(bytes.size <= MAX_PLUGIN_IMAGE_BYTES) { "图片超过 12MB 限制" }
    return decodePluginBitmap(bytes) ?: throw IllegalArgumentException("无法解码图片")
}

private fun decodePluginImageDataUri(source: String): ByteArray {
    val comma = source.indexOf(',')
    require(comma > 5) { "图片 data URI 格式无效" }
    val meta = source.substring(5, comma)
    val payload = source.substring(comma + 1)
    val bytes = if (meta.contains("base64", ignoreCase = true)) {
        Base64.decode(payload, Base64.DEFAULT)
    } else {
        java.net.URLDecoder.decode(payload, "UTF-8").toByteArray()
    }
    require(bytes.size <= MAX_PLUGIN_IMAGE_BYTES) { "图片超过 12MB 限制" }
    return bytes
}

private fun safePluginUiAssetDir(base: File, child: String): File? {
    val name = child.trim()
    if (name.isEmpty() || name == "." || name == ".." || name.contains('/') || name.contains('\\')) {
        return null
    }
    val baseCanonical = runCatching { base.canonicalFile }.getOrNull() ?: return null
    val dirCanonical = runCatching { File(base, name).canonicalFile }.getOrNull() ?: return null
    return dirCanonical.takeIf { it.path.startsWith(baseCanonical.path + File.separator) }
}

private fun resolvePluginImageFile(path: String, roots: List<File>): File? {
    if (roots.isEmpty()) return null
    val cleaned = path.removePrefix("file://")
    val requested = File(cleaned)
    val candidates = if (requested.isAbsolute) listOf(requested) else roots.map { File(it, cleaned) }
    for (candidate in candidates) {
        val canonical = runCatching { candidate.canonicalFile }.getOrNull() ?: continue
        val allowed = roots.any { root ->
            val base = runCatching { root.canonicalFile }.getOrNull() ?: return@any false
            canonical.path == base.path || canonical.path.startsWith(base.path + File.separator)
        }
        if (allowed && canonical.isFile) return canonical
    }
    return null
}

private fun decodePluginBitmap(bytes: ByteArray): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    var sample = 1
    while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= 2048) {
        sample *= 2
    }
    return BitmapFactory.decodeByteArray(
        bytes,
        0,
        bytes.size,
        BitmapFactory.Options().apply { inSampleSize = sample },
    )
}

private data class PluginChatMessage(val role: String, val text: String)

private fun pluginChatMessages(props: JsonObject): List<PluginChatMessage> {
    val array = props["messages"] as? JsonArray ?: return emptyList()
    return array.mapNotNull { element ->
        when (element) {
            is JsonPrimitive -> element.contentOrNull
                ?.takeIf { it.isNotBlank() }
                ?.let { PluginChatMessage("assistant", it) }

            is JsonObject -> {
                val role = (element["role"] as? JsonPrimitive)?.contentOrNull
                    ?: (element["sender"] as? JsonPrimitive)?.contentOrNull
                    ?: "assistant"
                pluginChatMessageText(element)
                    .takeIf { it.isNotBlank() }
                    ?.let { PluginChatMessage(role, it) }
            }

            else -> null
        }
    }
}

private fun pluginChatMessageText(message: JsonObject): String {
    val content = message["content"] ?: message["text"] ?: message["message"] ?: return ""
    return when (content) {
        is JsonPrimitive -> content.contentOrNull.orEmpty()
        is JsonArray -> content.mapNotNull { part ->
            when (part) {
                is JsonPrimitive -> part.contentOrNull
                is JsonObject -> (part["text"] as? JsonPrimitive)?.contentOrNull
                else -> null
            }
        }.joinToString("\n")

        else -> ""
    }
}

@Composable
private fun PluginAiChat(props: JsonObject, modifier: Modifier) {
    val hostState by PluginAiChatBridge.state.collectAsStateWithLifecycle()
    val host = hostState
    if (host == null) {
        Surface(
            modifier = modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
            color = MaterialTheme.colorScheme.surfaceVariant,
        ) {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text("AiChat", style = MaterialTheme.typography.titleSmall)
                Text(
                    text = "当前没有可嵌入的主聊天会话，请在聊天页打开插件面板后再使用 AiChat。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        return
    }
    var input by remember(host.title) { mutableStateOf("") }
    val listState = rememberLazyListState()
    LaunchedEffect(host.messages.size) {
        if (host.messages.isNotEmpty()) listState.scrollToItem(host.messages.lastIndex)
    }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .height(360.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = host.title,
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = "宿主主聊天",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (host.messages.isEmpty()) {
            Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text(
                    text = "还没有消息",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                state = listState,
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                items(host.messages) { message ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = if (message.fromUser) Arrangement.End else Arrangement.Start,
                    ) {
                        Surface(
                            modifier = Modifier.fillMaxWidth(0.9f),
                            shape = RoundedCornerShape(12.dp),
                            color = if (message.fromUser) {
                                MaterialTheme.colorScheme.primaryContainer
                            } else {
                                MaterialTheme.colorScheme.surfaceVariant
                            },
                        ) {
                            Column(
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                Text(
                                    text = if (message.fromUser) "我" else "AI",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                if (message.fromUser) {
                                    Text(
                                        text = message.text,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                                    )
                                } else {
                                    WorkMarkdown(text = message.text)
                                }
                            }
                        }
                    }
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text("输入消息") },
                maxLines = 3,
            )
            TextButton(
                enabled = input.isNotBlank(),
                onClick = {
                    host.onSend(input)
                    input = ""
                },
            ) { Text("发送") }
        }
    }
}

private fun pluginNodeText(element: JsonElement?): String? = when (element) {
    is JsonPrimitive -> element.contentOrNull?.takeIf { it.isNotBlank() }
    is JsonObject -> {
        val nodeProps = element["props"] as? JsonObject
        val direct = nodeProps?.string("text")
            ?: nodeProps?.string("content")
            ?: nodeProps?.string("value")
            ?: element.string("text")
            ?: element.string("content")
        direct?.takeIf { it.isNotBlank() }
            ?: (element["children"] as? JsonArray)
                ?.firstOrNull()
                ?.let { pluginNodeText(it) }
    }
    else -> null
}

@Composable
private fun PluginDialogNode(node: PluginUiNode, onEvent: (String?, String?) -> Unit) {
    val props = node.props
    var open by remember { mutableStateOf(props.bool("open") != false) }
    if (!open) return
    val dismissHandler = props.handler("onDismissRequest")
        ?: props.handler("onDismiss")
        ?: props.handler("onCancel")
    val confirmHandler = props.handler("onConfirm") ?: props.handler("onConfirmClick")
    val titleText = pluginNodeText(props["title"]) ?: "对话框"
    val bodyText = pluginNodeText(props["text"]) ?: pluginNodeText(props["message"])
    val markdownText = props.string("markdown")

    fun closeWith(handler: String?) {
        open = false
        onEvent(handler, null)
    }

    Dialog(
        onDismissRequest = { closeWith(dismissHandler) },
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(24.dp),
            contentAlignment = Alignment.Center,
        ) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth(0.92f)
                    .widthIn(max = 560.dp),
                shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 6.dp,
                shadowElevation = 24.dp,
            ) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(
                        text = titleText,
                        style = MaterialTheme.typography.titleMedium,
                    )
                    if (!bodyText.isNullOrBlank()) {
                        Text(
                            text = bodyText,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (!markdownText.isNullOrBlank()) {
                        WorkMarkdown(text = markdownText)
                    }
                    if (node.children.isNotEmpty()) {
                        Column(verticalArrangement = spacingOf(props)) {
                            node.children.forEach { PluginUiNodeView(it, onEvent) }
                        }
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        TextButton(onClick = { closeWith(dismissHandler) }) { Text("取消") }
                        Button(
                            onClick = { closeWith(confirmHandler ?: dismissHandler) },
                        ) { Text("确定") }
                    }
                }
            }
        }
    }
}

private fun webResponseOf(raw: String): WebResourceResponse? {
    val root = runCatching { Json.parseToJsonElement(raw).jsonObject }.getOrNull() ?: return null
    if (root["action"]?.jsonPrimitive?.contentOrNull != "respond") return null
    val response = root["response"] as? JsonObject ?: return null
    val mime = response["mimeType"]?.jsonPrimitive?.contentOrNull ?: "application/json"
    val encoding = response["encoding"]?.jsonPrimitive?.contentOrNull ?: "utf-8"
    val status = response["statusCode"]?.jsonPrimitive?.intOrNull ?: 200
    val reason = response["reasonPhrase"]?.jsonPrimitive?.contentOrNull ?: "OK"
    val text = response["text"]?.jsonPrimitive?.contentOrNull.orEmpty()
    val filePath = response["filePath"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
    val bytes = when {
        filePath != null -> runCatching { File(filePath).readBytes() }.getOrNull()
        else -> null
    } ?: text.toByteArray()
    val htmlPrefix = if (mime.contains("html", ignoreCase = true)) {
        "<meta name=\"color-scheme\" content=\"light\"><style>:root{color-scheme:light!important}</style>".toByteArray()
    } else {
        ByteArray(0)
    }
    val headers = (response["headers"] as? JsonObject)
        ?.mapValues { (_, value) -> value.jsonPrimitive.contentOrNull.orEmpty() }
        ?: mapOf("Access-Control-Allow-Origin" to "*")
    return WebResourceResponse(
        mime,
        encoding,
        status,
        reason,
        headers,
        ByteArrayInputStream(htmlPrefix + bytes),
    )
}

private fun controllerShim(controllerId: String, interfaces: List<String>): String {
    if (controllerId.isBlank() || interfaces.isEmpty()) return ""
    val idJson = JsonPrimitive(controllerId).toString()
    val namesJson = interfaces.joinToString(",") { JsonPrimitive(it).toString() }
    return """
        <script>(function(){
          var names=[$namesJson];
          window.__irisPluginUiPending = window.__irisPluginUiPending || {};
          window.__irisPluginUiResolve = function(id, value){
            var pending = window.__irisPluginUiPending[id];
            if (pending) {
              delete window.__irisPluginUiPending[id];
              pending.resolve(value);
            }
          };
          var seq = 0;
          names.forEach(function(name){
            window[name]=new Proxy({}, { get: function(target, method){
              return function(){
                var args=Array.prototype.slice.call(arguments);
                var requestId = 'iris-ui-' + (++seq) + '-' + Date.now();
                var promise = new Promise(function(resolve){
                  window.__irisPluginUiPending[requestId] = { resolve: resolve };
                });
                try {
                  window.IrisPluginUiHost.call(requestId, $idJson, name, String(method), JSON.stringify(args));
                } catch (error) {
                  delete window.__irisPluginUiPending[requestId];
                  return Promise.resolve(JSON.stringify({ error: String(error && error.message || error) }));
                }
                return promise;
              };
            }});
          });
        })();</script>
    """.trimIndent()
}

private fun spacingOf(props: JsonObject): Arrangement.Vertical =
    Arrangement.spacedBy((props.number("spacing") ?: 0f).dp)

private fun horizontalSpacingOf(props: JsonObject): Arrangement.Horizontal =
    Arrangement.spacedBy((props.number("spacing") ?: 0f).dp)

private fun horizontalAlignmentOf(value: String?): Alignment.Horizontal = when (value) {
    "center", "centerHorizontally" -> Alignment.CenterHorizontally
    "end" -> Alignment.End
    else -> Alignment.Start
}

private fun verticalAlignmentOf(value: String?): Alignment.Vertical = when (value) {
    "center", "centerVertically" -> Alignment.CenterVertically
    "bottom" -> Alignment.Bottom
    else -> Alignment.Top
}

private fun boxAlignmentOf(value: String?): Alignment = when (value) {
    "center" -> Alignment.Center
    "topStart", "start" -> Alignment.TopStart
    "topEnd", "end" -> Alignment.TopEnd
    "bottomStart" -> Alignment.BottomStart
    "bottomEnd" -> Alignment.BottomEnd
    else -> Alignment.TopStart
}

private fun paddingOf(props: JsonObject): PaddingValues {
    val raw = props["padding"] ?: return PaddingValues(0.dp)
    if (raw is JsonPrimitive) {
        val value = raw.floatOrNull ?: 0f
        return PaddingValues(value.dp)
    }
    val obj = raw as? JsonObject ?: return PaddingValues(0.dp)
    val all = obj.number("all")
    if (all != null) return PaddingValues(all.dp)
    return PaddingValues(
        start = (obj.number("start") ?: obj.number("horizontal") ?: 0f).dp,
        top = (obj.number("top") ?: obj.number("vertical") ?: 0f).dp,
        end = (obj.number("end") ?: obj.number("horizontal") ?: 0f).dp,
        bottom = (obj.number("bottom") ?: obj.number("vertical") ?: 0f).dp,
    )
}

private fun shapeOf(props: JsonObject): androidx.compose.ui.graphics.Shape {
    val shape = props["shape"] as? JsonObject
    val radius = shape?.number("cornerRadius") ?: 8f
    return RoundedCornerShape(radius.dp)
}

@Composable
private fun textStyleOf(style: String?): TextStyle = when (style) {
    "displayLarge" -> MaterialTheme.typography.displayLarge
    "displayMedium" -> MaterialTheme.typography.displayMedium
    "headlineLarge" -> MaterialTheme.typography.headlineLarge
    "headlineMedium" -> MaterialTheme.typography.headlineMedium
    "headlineSmall" -> MaterialTheme.typography.headlineSmall
    "titleLarge" -> MaterialTheme.typography.titleLarge
    "titleMedium" -> MaterialTheme.typography.titleMedium
    "titleSmall" -> MaterialTheme.typography.titleSmall
    "bodyLarge" -> MaterialTheme.typography.bodyLarge
    "bodySmall" -> MaterialTheme.typography.bodySmall
    "labelLarge" -> MaterialTheme.typography.labelLarge
    "labelMedium" -> MaterialTheme.typography.labelMedium
    "labelSmall" -> MaterialTheme.typography.labelSmall
    else -> MaterialTheme.typography.bodyMedium
}

@Composable
private fun colorOfElement(element: JsonElement?): Color? {
    val primitive = element as? JsonPrimitive
    if (primitive != null) return tokenColor(primitive.contentOrNull)
    val obj = element as? JsonObject ?: return null
    val name = (obj["__irisColor"] as? JsonPrimitive)?.contentOrNull
    val alpha = (obj["alpha"] as? JsonPrimitive)?.floatOrNull
    val base = tokenColor(name) ?: return null
    return if (alpha != null) base.copy(alpha = alpha.coerceIn(0f, 1f)) else base
}

@Composable
private fun tokenColor(value: String?): Color? {
    if (value.isNullOrBlank()) return null
    val scheme = MaterialTheme.colorScheme
    return when (value) {
        "primary" -> scheme.primary
        "onPrimary" -> scheme.onPrimary
        "primaryContainer" -> scheme.primaryContainer
        "onPrimaryContainer" -> scheme.onPrimaryContainer
        "secondary" -> scheme.secondary
        "onSecondary" -> scheme.onSecondary
        "secondaryContainer" -> scheme.secondaryContainer
        "onSecondaryContainer" -> scheme.onSecondaryContainer
        "tertiary" -> scheme.tertiary
        "surface" -> scheme.surface
        "surfaceVariant" -> scheme.surfaceVariant
        "onSurface" -> scheme.onSurface
        "onSurfaceVariant" -> scheme.onSurfaceVariant
        "background" -> scheme.background
        "onBackground" -> scheme.onBackground
        "error" -> scheme.error
        "errorContainer" -> scheme.errorContainer
        "onErrorContainer" -> scheme.onErrorContainer
        "outline" -> scheme.outline
        else -> if (value.startsWith("#")) {
            runCatching { Color(android.graphics.Color.parseColor(value)) }.getOrNull()
        } else {
            null
        }
    }
}

private fun iconOf(name: String?): ImageVector = when (name?.trim()?.lowercase()) {
    "add", "plus" -> Icons.Outlined.Add
    "check", "done" -> Icons.Outlined.Check
    "checkcircle" -> Icons.Outlined.CheckCircle
    "close", "clear" -> Icons.Outlined.Close
    "delete", "trash" -> Icons.Outlined.Delete
    "folder" -> Icons.Outlined.Folder
    "info" -> Icons.Outlined.Info
    "lock", "shield" -> Icons.Outlined.Lock
    "refresh", "reload" -> Icons.Outlined.Refresh
    "restore" -> Icons.Outlined.Restore
    "search" -> Icons.Outlined.Search
    "settings", "tune" -> Icons.Outlined.Settings
    "warning", "alert", "reportproblem" -> Icons.Outlined.Warning
    "edit", "editnote" -> Icons.Outlined.Edit
    "bookmark" -> Icons.Filled.Bookmark
    "bookmarkborder" -> Icons.Outlined.BookmarkBorder
    "bookmarkremove" -> Icons.Outlined.BookmarkRemove
    "bookmarks" -> Icons.Outlined.Bookmarks
    "favorite" -> Icons.Filled.Favorite
    "favoriteborder" -> Icons.Outlined.FavoriteBorder
    "star" -> Icons.Filled.Star
    "starborder" -> Icons.Outlined.StarBorder
    "flag", "outlinedflag" -> Icons.Outlined.Flag
    "thumbup" -> Icons.Outlined.ThumbUp
    "thumbdown" -> Icons.Outlined.ThumbDown
    "thumbdownoffalt" -> Icons.Outlined.ThumbDownOffAlt
    "airlineseatflat" -> Icons.Outlined.AirlineSeatFlat
    "alarm" -> Icons.Outlined.Alarm
    "assistantphoto" -> Icons.Outlined.AssistantPhoto
    "bedtime" -> Icons.Outlined.Bedtime
    "block" -> Icons.Outlined.Block
    "bubblechart" -> Icons.Outlined.BubbleChart
    "build" -> Icons.Outlined.Build
    "cake" -> Icons.Outlined.Cake
    "catchingpokemon" -> Icons.Outlined.CatchingPokemon
    "celebration" -> Icons.Outlined.Celebration
    "diamond" -> Icons.Outlined.Diamond
    "donotdisturbon" -> Icons.Outlined.DoNotDisturbOn
    "emojievents" -> Icons.Outlined.EmojiEvents
    "emojifoodbeverage" -> Icons.Outlined.EmojiFoodBeverage
    "graphiceq" -> Icons.Outlined.GraphicEq
    "headphones" -> Icons.Outlined.Headphones
    "heartbroken" -> Icons.Outlined.HeartBroken
    "hourglassempty" -> Icons.Outlined.HourglassEmpty
    "icecream" -> Icons.Outlined.Icecream
    "localcafe" -> Icons.Outlined.LocalCafe
    "localfiredepartment" -> Icons.Outlined.LocalFireDepartment
    "markchatread" -> Icons.Outlined.MarkChatRead
    "markunread" -> Icons.Outlined.Markunread
    "menubook" -> Icons.Outlined.MenuBook
    "gavel" -> Icons.Outlined.Gavel
    "lightbulb" -> Icons.Outlined.Lightbulb
    "morehoriz" -> Icons.Outlined.MoreHoriz
    "expandmore" -> Icons.Outlined.ExpandMore
    "expandless" -> Icons.Outlined.ExpandLess
    "sortbyalpha" -> Icons.Outlined.SortByAlpha
    "category" -> Icons.Outlined.Category
    "nightlight" -> Icons.Outlined.Nightlight
    "outlet" -> Icons.Outlined.Outlet
    "palette" -> Icons.Outlined.Palette
    "pantool" -> Icons.Outlined.PanTool
    "psychology" -> Icons.Outlined.Psychology
    "qrcode" -> Icons.Outlined.QrCode
    "questionmark" -> Icons.Outlined.QuestionMark
    "radiobuttonunchecked" -> Icons.Outlined.RadioButtonUnchecked
    "redeem" -> Icons.Outlined.Redeem
    "rocket" -> Icons.Outlined.Rocket
    "sentimentdissatisfied" -> Icons.Outlined.SentimentDissatisfied
    "sentimentneutral" -> Icons.Outlined.SentimentNeutral
    "sentimentsatisfied" -> Icons.Outlined.SentimentSatisfied
    "sentimentsatisfiedalt" -> Icons.Outlined.SentimentSatisfiedAlt
    "sentimentverydissatisfied" -> Icons.Outlined.SentimentVeryDissatisfied
    "sentimentverysatisfied" -> Icons.Outlined.SentimentVerySatisfied
    "sick" -> Icons.Outlined.Sick
    "smokingrooms" -> Icons.Outlined.SmokingRooms
    "spa" -> Icons.Outlined.Spa
    "visibility" -> Icons.Outlined.Visibility
    "volumeup" -> Icons.Outlined.VolumeUp
    "waterdrop" -> Icons.Outlined.WaterDrop
    "weekend" -> Icons.Outlined.Weekend
    "winebar" -> Icons.Outlined.WineBar
    else -> Icons.Outlined.Extension
}
