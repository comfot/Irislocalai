package com.iris.localai.ui.chat

import android.content.Context
import android.graphics.Color
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.ConsoleMessage
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.iris.localai.IrisApp
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileInputStream
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

private const val PLACE_CHANNEL = "IRIS_PLACE:"
private val STUDIO_PLACES = setOf("studio", "home", "campus", "library", "site", "duty", "desk")

internal sealed interface StudioStatus {
    data object Loading : StudioStatus
    data object Ready : StudioStatus
    data class Failed(val message: String) : StudioStatus
}

/**
 * 综合空间的离线 3D 场景。脚本在运行时内联进 HTML，避免 file:// 页面加载子资源时
 * 被 WebView 的同源策略拦截；Compose 只负责切换场所与注入统计数据。
 */
internal class Studio3DController {
    private var webView: WebView? = null
    private var stateJson: String = "{}"
    private var paused: Boolean = false
    private val _status = MutableStateFlow<StudioStatus>(StudioStatus.Loading)
    val status: StateFlow<StudioStatus> = _status.asStateFlow()
    internal var reloadAction: (() -> Unit)? = null

    internal fun markReady() {
        _status.value = StudioStatus.Ready
    }

    internal fun markFailed(message: String) {
        _status.value = StudioStatus.Failed(message)
    }

    fun reload() {
        _status.value = StudioStatus.Loading
        reloadAction?.invoke()
    }

    internal fun attach(view: WebView) {
        webView = view
    }

    fun resetView() {
        webView?.evaluateJavascript("window.Studio&&window.Studio.resetView()", null)
    }

    internal fun apply(view: WebView, stateJson: String, paused: Boolean) {
        this.stateJson = stateJson
        this.paused = paused
        view.evaluateJavascript("window.Studio&&window.Studio.setState($stateJson)", null)
        view.evaluateJavascript(
            "window.Studio&&window.Studio.setPaused(${if (paused) "true" else "false"})",
            null,
        )
    }

    internal fun reapply(view: WebView) {
        view.evaluateJavascript("window.Studio&&window.Studio.setState($stateJson)", null)
        view.evaluateJavascript(
            "window.Studio&&window.Studio.setPaused(${if (paused) "true" else "false"})",
            null,
        )
    }
}

internal class StudioBridge(
    private val controller: Studio3DController,
    private val onPlaceSelected: (String) -> Unit,
) {
    private val main = Handler(Looper.getMainLooper())

    @JavascriptInterface
    fun onPlaceSelected(place: String) {
        if (place.isBlank()) return
        main.post {
            runCatching { onPlaceSelected(place) }
                .onFailure { Log.e("Studio3D", "place select failed: $place", it) }
        }
    }

    @JavascriptInterface
    fun onReady() {
        main.post { runCatching { controller.markReady() } }
    }

    @JavascriptInterface
    fun onError(message: String) {
        main.post { runCatching { controller.markFailed(message) } }
    }
}

@Composable
internal fun Studio3DView(
    place: String,
    accentHex: String?,
    dark: Boolean,
    stateJson: String,
    paused: Boolean,
    controller: Studio3DController,
    onPlaceSelected: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val latestCallback = rememberUpdatedState(onPlaceSelected)
    val callbackRef = remember { AtomicReference(onPlaceSelected) }
    val mainHandler = remember { Handler(Looper.getMainLooper()) }
    SideEffect { callbackRef.set(latestCallback.value) }

    AndroidView(
        factory = { context ->
            WebView(context).apply {
                setBackgroundColor(Color.TRANSPARENT)
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.allowFileAccess = true
                settings.allowContentAccess = true
                @Suppress("DEPRECATION")
                settings.allowFileAccessFromFileURLs = true
                @Suppress("DEPRECATION")
                settings.allowUniversalAccessFromFileURLs = true
                webChromeClient = object : WebChromeClient() {
                    override fun onConsoleMessage(message: ConsoleMessage): Boolean {
                        val text = message.message()
                        if (text.startsWith(PLACE_CHANNEL)) {
                            val id = text.removePrefix(PLACE_CHANNEL).trim()
                            if (id in STUDIO_PLACES) {
                                mainHandler.post { callbackRef.get()(id) }
                            }
                            return true
                        }
                        Log.d(
                            "Studio3D",
                            "$text (${message.sourceId()}:${message.lineNumber()})",
                        )
                        return true
                    }
                }
                webViewClient = object : WebViewClient() {
                    override fun shouldInterceptRequest(
                        view: WebView,
                        request: WebResourceRequest,
                    ): WebResourceResponse? = interceptStudioModel(context, request)

                    override fun onPageFinished(view: WebView, url: String?) {
                        controller.attach(view)
                        controller.reapply(view)
                        view.postDelayed({
                            view.evaluateJavascript("typeof window.Studio") { result ->
                                if (result == "\"undefined\"" || result == "null") {
                                    controller.markFailed(
                                        "3D 脚本未执行：请确认 WebView JavaScript 已启用，或检查 Logcat 中的 Studio3D 日志",
                                    )
                                }
                            }
                        }, 600)
                    }

                    override fun onReceivedError(
                        view: WebView,
                        request: WebResourceRequest,
                        error: WebResourceError,
                    ) {
                        Log.w(
                            "Studio3D",
                            "resource error ${request.url}: ${error.description}",
                        )
                    }
                }
                controller.attach(this)
                addJavascriptInterface(
                    StudioBridge(controller) { id -> callbackRef.get()(id) },
                    "StudioBridge",
                )
                fun load() {
                    loadDataWithBaseURL(
                        "https://iris.local/studio/",
                        studioHtml(context),
                        "text/html",
                        "UTF-8",
                        null,
                    )
                }
                controller.reloadAction = { load() }
                load()
            }
        },
        update = { view ->
            controller.attach(view)
            controller.apply(view, stateJson, paused)
        },
        onRelease = { view ->
            runCatching { view.removeJavascriptInterface("StudioBridge") }
            runCatching { view.destroy() }
        },
        modifier = modifier,
    )
}

/** 把 three.js 与场景脚本内联到页面里；资源只读取一次。 */
private var cachedStudioHtml: String? = null

private fun studioHtml(context: Context): String {
    cachedStudioHtml?.let { return it }
    val html = context.assets.open("studio.html").bufferedReader().use { it.readText() }
    val three = context.assets.open("three.min.js").bufferedReader().use { it.readText() }
    val scene = context.assets.open("studio.js").bufferedReader().use { it.readText() }
    val bundled = html
        .replace(
            "<script src=\"three.min.js\"></script>",
            "<script>\n" + three.replace("</script", "<\\/script") + "\n</script>",
        )
        .replace(
            "<script src=\"studio.js\"></script>",
            "<script>\n" + scene.replace("</script", "<\\/script") + "\n</script>",
        )
    cachedStudioHtml = bundled
    return bundled
}

/** 综合空间小人的 YSM 模型文件：https://iris.local/studio-models/<id>/<相对路径>。 */
internal fun interceptStudioModel(context: Context, request: WebResourceRequest): WebResourceResponse? {
    val url = request.url
    if (url.host != "iris.local") return null
    val path = url.path ?: return null
    if (path.startsWith("/studio-assets/")) {
        val relative = Uri.decode(path.removePrefix("/studio-assets/")).replace('\\', '/')
        if (relative.isBlank() || relative.contains("..")) return studioNotFound()
        return runCatching {
            WebResourceResponse(
                studioMime(relative),
                null,
                200,
                "OK",
                mapOf(
                    "Cache-Control" to "max-age=86400",
                    "Access-Control-Allow-Origin" to "*",
                ),
                context.assets.open(relative),
            )
        }.getOrNull() ?: studioNotFound()
    }
    if (!path.startsWith("/studio-models/")) return null
    val rest = Uri.decode(path.removePrefix("/studio-models/")).replace('\\', '/')
    val separator = rest.indexOf('/')
    if (separator <= 0) return studioNotFound()
    val modelId = rest.substring(0, separator)
    val relative = rest.substring(separator + 1)
    if (relative.isBlank() || relative.contains("..")) return studioNotFound()
    val dir = (context.applicationContext as IrisApp).graph.studioModels.directory(modelId)
        ?: return studioNotFound()
    val canonicalRoot = runCatching { dir.canonicalFile }.getOrNull() ?: return studioNotFound()
    val file = runCatching { File(canonicalRoot, relative).canonicalFile }.getOrNull()
        ?: return studioNotFound()
    if (!file.isFile || !file.path.startsWith(canonicalRoot.path + File.separator)) {
        return studioNotFound()
    }
    return runCatching {
        WebResourceResponse(
            studioMime(file.name),
            null,
            200,
            "OK",
            mapOf(
                "Content-Length" to file.length().toString(),
                "Cache-Control" to "no-store",
                "Access-Control-Allow-Origin" to "*",
            ),
            FileInputStream(file),
        )
    }.getOrNull()
}

private fun studioNotFound(): WebResourceResponse = WebResourceResponse(
    "text/plain",
    "UTF-8",
    404,
    "Not Found",
    emptyMap(),
    ByteArrayInputStream("not found".toByteArray()),
)

private fun studioMime(name: String): String = when (name.substringAfterLast('.').lowercase()) {
    "json", "mcmeta" -> "application/json"
    "js", "mjs" -> "text/javascript"
    "wasm" -> "application/wasm"
    "png" -> "image/png"
    "jpg", "jpeg" -> "image/jpeg"
    "ogg" -> "audio/ogg"
    "wav" -> "audio/wav"
    "mp3" -> "audio/mpeg"
    else -> "application/octet-stream"
}
