package com.iris.localai.ui.chat

import android.content.Context
import android.graphics.Color
import android.net.Uri
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.iris.localai.motion.MotionBridge
import org.json.JSONObject
import java.io.File

/**
 * Live2D Cubism 4 渲染：Pixi + pixi-live2d-display + 本地 Cubism Core。
 * 模型与贴图通过虚拟 https 源拦截，脚本运行时内联，完全离线。
 */
private class WebViewHolder {
    var view: WebView? = null
}

internal fun releaseModelWebView(view: WebView) {
    runCatching {
        view.stopLoading()
        view.visibility = View.GONE
        (view.parent as? ViewGroup)?.removeView(view)
    }
    runCatching { view.destroy() }
}

private class Live2DController {
    var speaking: Boolean = false
    var mood: String = "neutral"
    var mouthAmplitude: Float = 0f

    fun apply(view: WebView) {
        view.evaluateJavascript(
            "window.__live2dSetSpeaking&&window.__live2dSetSpeaking(${if (speaking) "true" else "false"})",
            null,
        )
        view.evaluateJavascript(
            "window.__live2dSetAmplitude&&window.__live2dSetAmplitude(${mouthAmplitude.coerceIn(0f, 1f)})",
            null,
        )
        view.evaluateJavascript(
            "window.__live2dSetMood&&window.__live2dSetMood('${mood.replace("'", "")}')",
            null,
        )
    }
}

@Composable
internal fun Live2DView(
    modelFile: File,
    accentHex: String?,
    dark: Boolean,
    speaking: Boolean,
    mood: String,
    mouthAmplitude: Float = 0f,
    portrait: Boolean = false,
    interactive: Boolean = true,
    modelRoot: String = "character_models",
    expressionsEnabled: Boolean = true,
    motionsEnabled: Boolean = true,
    idleMotionEnabled: Boolean = true,
    tapMotionEnabled: Boolean = true,
    mouthSyncEnabled: Boolean = true,
    modifier: Modifier = Modifier,
) {
    val controller = remember { Live2DController() }
    val holder = remember { WebViewHolder() }
    val expressionCommand by MotionBridge.expressionCommand.collectAsStateWithLifecycle()
    LaunchedEffect(expressionCommand?.seq) {
        val name = expressionCommand?.name ?: return@LaunchedEffect
        holder.view?.evaluateJavascript(
            "window.__live2dSetMood&&window.__live2dSetMood(${JSONObject.quote(name)})",
            null,
        )
    }
    controller.speaking = speaking
    controller.mood = mood
    controller.mouthAmplitude = mouthAmplitude
    DisposableEffect(holder) {
        onDispose { holder.view?.let { releaseModelWebView(it) } }
    }
    val key = "${modelFile.absolutePath}|$accentHex|$dark|$portrait|$interactive|$modelRoot"
    AndroidView(
        factory = { context ->
            WebView(context).apply {
                holder.view = this
                setBackgroundColor(Color.TRANSPARENT)
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.allowFileAccess = true
                settings.allowContentAccess = true
                settings.mediaPlaybackRequiresUserGesture = false
                @Suppress("DEPRECATION")
                settings.allowFileAccessFromFileURLs = true
                @Suppress("DEPRECATION")
                settings.allowUniversalAccessFromFileURLs = true
                webChromeClient = object : WebChromeClient() {
                    override fun onConsoleMessage(message: ConsoleMessage): Boolean {
                        Log.d(
                            "Live2DView",
                            "${message.message()} (${message.sourceId()}:${message.lineNumber()})",
                        )
                        return true
                    }
                }
                webViewClient = object : WebViewClient() {
                    override fun shouldInterceptRequest(
                        view: WebView,
                        request: WebResourceRequest,
                    ): WebResourceResponse? = interceptCharacterModel(context, request)

                    override fun onPageFinished(view: WebView, url: String?) {
                        view.postDelayed({ controller.apply(view) }, 300)
                    }
                }
                loadLive2d(
                    this,
                    context,
                    modelFile,
                    accentHex,
                    dark,
                    portrait,
                    interactive,
                    modelRoot,
                    expressionsEnabled,
                    motionsEnabled,
                    idleMotionEnabled,
                    tapMotionEnabled,
                    mouthSyncEnabled,
                )
                tag = key
            }
        },
        update = { view ->
            if (view.tag != key) {
                view.tag = key
                loadLive2d(
                    view,
                    view.context,
                    modelFile,
                    accentHex,
                    dark,
                    portrait,
                    interactive,
                    modelRoot,
                    expressionsEnabled,
                    motionsEnabled,
                    idleMotionEnabled,
                    tapMotionEnabled,
                    mouthSyncEnabled,
                )
            }
            controller.apply(view)
            view.evaluateJavascript(
                "window.irisLive2d&&window.irisLive2d.setOptions({" +
                    "expressions:$expressionsEnabled,motions:$motionsEnabled," +
                    "idle:$idleMotionEnabled,tap:$tapMotionEnabled,mouth:$mouthSyncEnabled})",
                null,
            )
        },
        onRelease = { view ->
            holder.view = null
            releaseModelWebView(view)
        },
        modifier = modifier,
    )
}

private fun loadLive2d(
    view: WebView,
    context: Context,
    modelFile: File,
    accentHex: String?,
    dark: Boolean,
    portrait: Boolean,
    interactive: Boolean,
    modelRoot: String,
    expressionsEnabled: Boolean,
    motionsEnabled: Boolean,
    idleMotionEnabled: Boolean,
    tapMotionEnabled: Boolean,
    mouthSyncEnabled: Boolean,
) {
    val root = File(context.filesDir, modelRoot).canonicalFile
    val relative = runCatching {
        modelFile.canonicalFile.relativeTo(root).invariantSeparatorsPath
    }.getOrNull()
    if (relative.isNullOrBlank() || !modelFile.isFile) {
        view.loadDataWithBaseURL(
            "https://iris.local/",
            "<html><body style='background:transparent;color:#fff;font:13px sans-serif;display:grid;place-items:center'>Live2D 模型文件不存在</body></html>",
            "text/html",
            "UTF-8",
            null,
        )
        return
    }
    val prefix = if (modelRoot == "character_models") "/models/" else "/petmodels/"
    val modelUrl = "https://iris.local$prefix${relative.split('/').joinToString("/") { Uri.encode(it) }}"
    val config = JSONObject()
        .put("model", modelUrl)
        .put("accent", accentHex ?: "#0D84FF")
        .put("dark", dark)
        .put("portrait", portrait)
        .put("interactive", interactive)
        .put("expressions", expressionsEnabled)
        .put("motions", motionsEnabled)
        .put("idle", idleMotionEnabled)
        .put("tap", tapMotionEnabled)
        .put("mouth", mouthSyncEnabled)
        .toString()
    view.loadDataWithBaseURL(
        "https://iris.local/",
        live2dHtml(context, config),
        "text/html",
        "UTF-8",
        null,
    )
}

private var cachedLive2dTemplate: String? = null
private var cachedLive2dScript: String? = null

private fun live2dHtml(context: Context, configJson: String): String {
    val template = cachedLive2dTemplate ?: run {
        val html = context.assets.open("live2d.html").bufferedReader().use { it.readText() }
        val core = context.assets.open("model/live2dcubismcore.min.js").bufferedReader().use { it.readText() }
        val pixi = context.assets.open("model/pixi.min.js").bufferedReader().use { it.readText() }
        val display = context.assets.open("model/pixi-live2d-display-cubism4.min.js")
            .bufferedReader().use { it.readText() }
        val script = context.assets.open("live2d.js").bufferedReader().use { it.readText() }
        cachedLive2dScript = script
        html
            .replace(
                "<script src=\"model/live2dcubismcore.min.js\"></script>",
                "<script>\n" + core.replace("</script", "<\\/script") + "\n</script>",
            )
            .replace(
                "<script src=\"model/pixi.min.js\"></script>",
                "<script>\n" + pixi.replace("</script", "<\\/script") + "\n</script>",
            )
            .replace(
                "<script src=\"model/pixi-live2d-display-cubism4.min.js\"></script>",
                "<script>\n" + display.replace("</script", "<\\/script") + "\n</script>",
            )
            .replace("<script src=\"live2d.js\"></script>", "__LIVE2D_SCRIPT__")
            .also { cachedLive2dTemplate = it }
    }
    val script = cachedLive2dScript.orEmpty().replace("</script", "<\\/script")
    return template.replace(
        "__LIVE2D_SCRIPT__",
        "<script>window.__LIVE2D_CONFIG__ = $configJson;</script><script>\n$script\n</script>",
    )
}
