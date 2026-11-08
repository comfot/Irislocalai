package com.iris.localai.ui.chat

import android.content.Context
import android.graphics.Color
import android.net.Uri
import android.util.Log
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import org.json.JSONObject
import java.io.File

/**
 * MMD（PMX/PMD）渲染：Babylon.js + babylon-mmd WASM 运行时，
 * 支持 VMD 动作、IK、表情和 WASM 物理；模型与贴图通过虚拟 https 源拦截读取。
 */
@Composable
internal fun MMDView(
    modelFile: File,
    accentHex: String?,
    dark: Boolean,
    mood: String = "neutral",
    speaking: Boolean = false,
    fullscreen: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val key = "${modelFile.absolutePath}|$accentHex|$dark"
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
                            "MMDView",
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

                    override fun onRenderProcessGone(
                        view: WebView,
                        detail: android.webkit.RenderProcessGoneDetail,
                    ): Boolean {
                        Log.e("MMDView", "renderer gone crash=${detail.didCrash()}")
                        return true
                    }
                }
                loadMmd(this, context, modelFile, accentHex, dark, mood, speaking)
                tag = key
            }
        },
        update = { view ->
            if (view.tag != key) {
                view.tag = key
                loadMmd(view, view.context, modelFile, accentHex, dark, mood, speaking)
            }
            applyMmdState(view, mood, speaking)
            view.postDelayed({
                view.evaluateJavascript("window.irisMmd&&window.irisMmd.resize()", null)
            }, if (fullscreen) 180 else 80)
        },
        onRelease = { view -> releaseModelWebView(view) },
        modifier = modifier,
    )
}

private fun loadMmd(
    view: WebView,
    context: Context,
    modelFile: File,
    accentHex: String?,
    dark: Boolean,
    mood: String,
    speaking: Boolean,
) {
    val root = File(context.filesDir, "character_models").canonicalFile
    val relative = runCatching {
        modelFile.canonicalFile.relativeTo(root).invariantSeparatorsPath
    }.getOrNull()
    if (relative.isNullOrBlank() || !modelFile.isFile) {
        view.loadDataWithBaseURL(
            "https://iris.local/",
            "<html><body style='background:transparent;color:#fff;font:13px sans-serif;display:grid;place-items:center'>MMD 模型文件不存在</body></html>",
            "text/html",
            "UTF-8",
            null,
        )
        return
    }
    val modelUrl = "https://iris.local/models/${relative.split('/').joinToString("/") { Uri.encode(it) }}"
    val config = JSONObject()
        .put("model", modelUrl)
        .put("accent", accentHex ?: "#0D84FF")
        .put("dark", dark)
        .put("mood", mood)
        .put("speaking", speaking)
    findMmdMotion(modelFile, context)?.let { config.put("motion", it) }
    view.loadDataWithBaseURL(
        "https://iris.local/",
        mmdHtml(context, config.toString()),
        "text/html",
        "UTF-8",
        null,
    )
}

private fun applyMmdState(view: WebView, mood: String, speaking: Boolean) {
    val safeMood = mood.replace("'", "").replace("\\", "")
    view.evaluateJavascript(
        "window.irisMmd&&window.irisMmd.setMood('$safeMood');" +
            "window.irisMmd&&window.irisMmd.setMouthOpen(${if (speaking) 0.28 else 0.0});",
        null,
    )
}

private fun findMmdMotion(modelFile: File, context: Context): String? {
    val root = File(context.filesDir, "character_models").canonicalFile
    val motions = modelFile.parentFile?.listFiles()
        ?.filter { it.isFile && it.extension.equals("vmd", ignoreCase = true) }
        ?.sortedBy { it.name.lowercase() }
        .orEmpty()
    val motion = motions.firstOrNull { it.name.contains("idle", ignoreCase = true) } ?: motions.firstOrNull()
        ?: return null
    val relative = runCatching { motion.canonicalFile.relativeTo(root).invariantSeparatorsPath }.getOrNull()
        ?: return null
    return "https://iris.local/models/${relative.split('/').joinToString("/") { Uri.encode(it) }}"
}

private var cachedMmdTemplate: String? = null

private fun mmdHtml(context: Context, configJson: String): String {
    val template = cachedMmdTemplate ?: context.assets.open("mmd.html")
        .bufferedReader()
        .use { it.readText() }
        .also { cachedMmdTemplate = it }
    return template.replace("__MMD_CONFIG_JSON__", configJson)
}
