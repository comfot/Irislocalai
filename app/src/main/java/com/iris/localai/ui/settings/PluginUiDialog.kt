package com.iris.localai.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.material3.Card
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import android.content.res.Configuration
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
import com.iris.localai.plugin.InstalledPlugin
import com.iris.localai.ui.chat.WorkMarkdown
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
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

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.96f)
                .fillMaxHeight(0.9f),
            shape = RoundedCornerShape(20.dp),
            tonalElevation = 4.dp,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = title.ifBlank { plugin.name },
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = { load() }, enabled = !busy) { Text("刷新") }
                    TextButton(onClick = onDismiss) { Text("关闭") }
                }
                Text(
                    text = "Operit Compose DSL 兼容渲染 · ${plugin.name}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (busy) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
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
                    ) {
                        PluginUiNodeView(it, ::dispatch)
                    }
                }
            }
        }
    }
}

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
    val obj = element as? JsonObject ?: return null
    val type = obj.string("type") ?: return null
    val props = obj["props"] as? JsonObject ?: JsonObject(emptyMap())
    val children = (obj["children"] as? JsonArray)
        ?.mapNotNull(::parsePluginUiNode)
        .orEmpty()
    return PluginUiNode(type, props, children)
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
    val fixedSize = props.number("size")
    val clipRadius = (props["clip"] as? JsonObject)?.number("cornerRadius")
    val hasWebViewChild = node.children.any { it.type == "WebView" }
    val modifier = Modifier
        .then(if (props.bool("fillMaxSize") == true) Modifier.fillMaxSize() else Modifier)
        .then(if (props.bool("fillMaxWidth") == true) Modifier.fillMaxWidth() else Modifier)
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
        "Column", "LazyColumn", "FlowColumn" -> Column(
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

        "Row", "LazyRow", "FlowRow" -> Row(
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
            text = props.string("text").orEmpty(),
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
            val text = props.string("text") ?: node.children.firstOrNull()?.props?.string("text")
            Button(
                onClick = { onEvent(props.handler("onClick"), null) },
                modifier = modifier,
            ) { Text(text.orEmpty()) }
        }

        "TextField", "OutlinedTextField" -> OutlinedTextField(
            value = props.string("value").orEmpty(),
            onValueChange = { value ->
                onEvent(props.handler("onValueChange"), JsonPrimitive(value).toString())
            },
            modifier = modifier,
            label = props.string("label")?.let { { Text(it) } },
            placeholder = props.string("placeholder")?.let { { Text(it) } },
            singleLine = props.bool("singleLine") != false,
        )

        "Switch" -> Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
            if (props.string("label") != null) {
                Text(props.string("label").orEmpty(), modifier = Modifier.weight(1f))
            }
            Switch(
                checked = props.bool("checked") == true,
                onCheckedChange = { value ->
                    onEvent(props.handler("onCheckedChange"), JsonPrimitive(value).toString())
                },
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
            )
        }

        "RadioButton" -> androidx.compose.material3.RadioButton(
            selected = props.bool("selected") == true,
            onClick = { onEvent(props.handler("onClick"), null) },
            modifier = modifier,
        )

        "Checkbox" -> Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
            Text(props.string("label").orEmpty(), modifier = Modifier.weight(1f))
            androidx.compose.material3.Checkbox(
                checked = props.bool("checked") == true,
                onCheckedChange = { value ->
                    onEvent(props.handler("onCheckedChange"), JsonPrimitive(value).toString())
                },
            )
        }

        "IconButton" -> IconButton(
            onClick = { onEvent(props.handler("onClick"), null) },
            modifier = modifier,
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

        "AlertDialog", "Dialog" -> Card(modifier = modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                props.string("title")?.let {
                    Text(it, style = MaterialTheme.typography.titleMedium)
                }
                props.string("text")?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall)
                }
                node.children.forEach { PluginUiNodeView(it, onEvent) }
            }
        }

        "Image" -> Text(
            text = "[图片${props.string("url")?.let { "：$it" }.orEmpty()}]",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = modifier,
        )

        "AiChat" -> Text(
            text = "AiChat 组件暂不支持内嵌渲染",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = modifier,
        )

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
