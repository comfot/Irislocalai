package com.iris.localai.ui.chat

import android.content.Context
import android.graphics.Color
import android.util.Log
import android.webkit.ConsoleMessage
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

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

private class StudioBridge(
    private val controller: Studio3DController,
    private val onPlaceSelected: (String) -> Unit,
) {
    @JavascriptInterface
    fun onPlaceSelected(place: String) {
        onPlaceSelected(place)
    }

    @JavascriptInterface
    fun onReady() {
        controller.markReady()
    }

    @JavascriptInterface
    fun onError(message: String) {
        controller.markFailed(message)
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
                        Log.d(
                            "Studio3D",
                            "${message.message()} (${message.sourceId()}:${message.lineNumber()})",
                        )
                        return true
                    }
                }
                webViewClient = object : WebViewClient() {
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
                    StudioBridge(controller) { id -> latestCallback.value(id) },
                    "StudioBridge",
                )
                fun load() {
                    val cached = studioFile(context)
                    if (cached != null) {
                        loadUrl("file://$cached")
                    } else {
                        val url = "file:///android_asset/studio.html"
                        loadDataWithBaseURL(url, studioHtml(context), "text/html", "UTF-8", url)
                    }
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

private fun studioFile(context: Context): String? = runCatching {
    val file = File(context.cacheDir, "studio-3d.html")
    file.writeText(studioHtml(context))
    file.absolutePath
}.getOrNull()

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
