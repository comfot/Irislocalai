package com.iris.localai.ui.chat

import android.content.Context
import android.graphics.Color
import android.media.MediaPlayer
import android.net.Uri
import android.util.Log
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.iris.localai.IrisApp
import com.iris.localai.motion.MotionBridge
import com.iris.localai.motion.MotionCategory
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
    moodIntensity: Float = 0.85f,
    speaking: Boolean = false,
    mouthAmplitude: Float = 0f,
    fullscreen: Boolean = false,
    portrait: Boolean = false,
    interactive: Boolean = true,
    modelRoot: String = "character_models",
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val graph = remember(context) { (context.applicationContext as IrisApp).graph }
    val appSettings by graph.settings.settings.collectAsStateWithLifecycle()
    val idleMotionAsset = appSettings.idleMotionAsset
    val key = "${modelFile.absolutePath}|$accentHex|$dark|$portrait|$interactive|$modelRoot|$idleMotionAsset"
    var webView by remember { mutableStateOf<WebView?>(null) }
    var bgmPlayer by remember { mutableStateOf<MediaPlayer?>(null) }
    val assetCommand by MotionBridge.assetCommand.collectAsStateWithLifecycle()
    val cameraCommand by MotionBridge.cameraCommand.collectAsStateWithLifecycle()
    val motionCommand by MotionBridge.command.collectAsStateWithLifecycle()
    val expressionCommand by MotionBridge.expressionCommand.collectAsStateWithLifecycle()
    DisposableEffect(Unit) {
        MotionBridge.registerMmdView()
        onDispose {
            MotionBridge.unregisterMmdView()
            bgmPlayer?.release()
            bgmPlayer = null
        }
    }
    LaunchedEffect(assetCommand?.seq) {
        val command = assetCommand ?: return@LaunchedEffect
        if (command.path.isBlank()) {
            webView?.evaluateJavascript("window.irisMmd&&window.irisMmd.stop()", null)
            bgmPlayer?.release()
            bgmPlayer = null
            return@LaunchedEffect
        }
        webView?.evaluateJavascript(
            "window.irisMmd&&window.irisMmd.playAsset(${JSONObject.quote(command.path)}, ${command.loop})",
            null,
        )
        bgmPlayer?.release()
        bgmPlayer = null
        val bgm = command.bgmPath
        if (appSettings.danceBgmEnabled && !bgm.isNullOrBlank() && File(bgm).isFile) {
            runCatching {
                MediaPlayer.create(context, Uri.fromFile(File(bgm)))
            }.onSuccess { player ->
                player.setOnCompletionListener {
                    it.release()
                    bgmPlayer = null
                    webView?.evaluateJavascript("window.irisMmd&&window.irisMmd.stop()", null)
                }
                player.start()
                bgmPlayer = player
            }
        }
    }
    LaunchedEffect(motionCommand?.seq) {
        val action = motionCommand?.action ?: return@LaunchedEffect
        val assets = graph.motionAssets
        val asset = when (action.lowercase()) {
            "wave", "挥手", "摆手", "打招呼" ->
                assets.assets.value.firstOrNull { it.category == MotionCategory.GREET }
            "dance", "跳舞" ->
                assets.assets.value.firstOrNull { it.category == MotionCategory.DANCE }
            "idle", "待机" ->
                assets.assets.value.firstOrNull { it.category == MotionCategory.IDLE }
            else -> assets.findByName(action)
        }
        if (asset != null) {
            webView?.evaluateJavascript(
                "window.irisMmd&&window.irisMmd.playAsset(${JSONObject.quote(asset.id)}, ${asset.category == MotionCategory.DANCE})",
                null,
            )
        }
    }
    LaunchedEffect(expressionCommand?.seq) {
        val name = expressionCommand?.name ?: return@LaunchedEffect
        webView?.evaluateJavascript(
            "window.irisMmd&&window.irisMmd.setMood(${JSONObject.quote(name)}, 1.0)",
            null,
        )
    }
    LaunchedEffect(cameraCommand?.seq) {
        val command = cameraCommand ?: return@LaunchedEffect
        if (command.path.isBlank()) {
            webView?.evaluateJavascript("window.irisMmd&&window.irisMmd.stopCamera()", null)
        } else {
            webView?.evaluateJavascript(
                "window.irisMmd&&window.irisMmd.playCamera(${JSONObject.quote(command.path)}, ${command.loop})",
                null,
            )
        }
    }
    AndroidView(
        factory = { ctx ->
            WebView(ctx).apply {
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
                loadMmd(this, ctx, modelFile, accentHex, dark, mood, speaking, portrait, interactive, modelRoot, idleMotionAsset)
                tag = key
                webView = this
            }
        },
        update = { view ->
            if (view.tag != key) {
                view.tag = key
                loadMmd(view, view.context, modelFile, accentHex, dark, mood, speaking, portrait, interactive, modelRoot, idleMotionAsset)
            }
            applyMmdState(view, mood, speaking, moodIntensity, mouthAmplitude)
            view.postDelayed({
                view.evaluateJavascript("window.irisMmd&&window.irisMmd.resize()", null)
            }, if (fullscreen) 180 else 80)
        },
        onRelease = { view ->
            webView = null
            releaseModelWebView(view)
        },
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
    portrait: Boolean,
    interactive: Boolean,
    modelRoot: String,
    idleMotionAsset: String,
) {
    val storage = (context.applicationContext as IrisApp).graph.storage
    val root = storage.containingRoot(modelRoot, modelFile)
        ?: File(context.filesDir, modelRoot).canonicalFile
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
    val prefix = if (modelRoot == "character_models") "/models/" else "/petmodels/"
    val modelUrl = "https://iris.local$prefix${relative.split('/').joinToString("/") { Uri.encode(it) }}"
    val config = JSONObject()
        .put("model", modelUrl)
        .put("accent", accentHex ?: "#0D84FF")
        .put("dark", dark)
        .put("mood", mood)
        .put("speaking", speaking)
        .put("portrait", portrait)
        .put("interactive", interactive)
    Log.d("MMDView", "load portrait=$portrait root=$modelRoot file=${modelFile.name} mood=$mood")
    findMmdMotion(modelFile, context, modelRoot)?.let { config.put("motion", it) }
    if (idleMotionAsset.isNotBlank()) config.put("idleMotion", motionAssetUrl(idleMotionAsset))
    view.loadDataWithBaseURL(
        "https://iris.local/",
        mmdHtml(context, config.toString()),
        "text/html",
        "UTF-8",
        null,
    )
}

private fun applyMmdState(
    view: WebView,
    mood: String,
    speaking: Boolean,
    moodIntensity: Float,
    mouthAmplitude: Float,
) {
    val safeMood = mood.replace("'", "").replace("\\", "")
    val intensity = moodIntensity.coerceIn(0f, 1f)
    val mouth = if (mouthAmplitude > 0.02f) {
        mouthAmplitude.coerceIn(0f, 1f)
    } else if (speaking) {
        0.28f
    } else {
        0f
    }
    view.evaluateJavascript(
        "window.irisMmd&&window.irisMmd.setMood('$safeMood', $intensity);" +
            "window.irisMmd&&window.irisMmd.setMouthOpen($mouth);",
        null,
    )
}

private fun findMmdMotion(modelFile: File, context: Context, modelRoot: String): String? {
    val storage = (context.applicationContext as IrisApp).graph.storage
    val root = storage.containingRoot(modelRoot, modelFile)
        ?: File(context.filesDir, modelRoot).canonicalFile
    val motions = modelFile.parentFile?.listFiles()
        ?.filter { it.isFile && it.extension.equals("vmd", ignoreCase = true) }
        ?.sortedBy { it.name.lowercase() }
        .orEmpty()
    val motion = motions.firstOrNull { it.name.contains("idle", ignoreCase = true) } ?: motions.firstOrNull()
        ?: return null
    val relative = runCatching { motion.canonicalFile.relativeTo(root).invariantSeparatorsPath }.getOrNull()
        ?: return null
    val prefix = if (modelRoot == "character_models") "/models/" else "/petmodels/"
    return "https://iris.local$prefix${relative.split('/').joinToString("/") { Uri.encode(it) }}"
}

private fun motionAssetUrl(relative: String): String =
    "https://iris.local/motionassets/" +
        relative.split('/').joinToString("/") { Uri.encode(it) }

private var cachedMmdTemplate: String? = null

private fun mmdHtml(context: Context, configJson: String): String {
    val template = cachedMmdTemplate ?: context.assets.open("mmd.html")
        .bufferedReader()
        .use { it.readText() }
        .also { cachedMmdTemplate = it }
    return template.replace("__MMD_CONFIG_JSON__", configJson)
}
