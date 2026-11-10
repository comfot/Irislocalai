package com.iris.localai.ui.chat

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.iris.localai.IrisApp
import com.iris.localai.data.VrmMotionEngine
import com.iris.localai.motion.HastMotionStore
import com.iris.localai.motion.MotionBridge
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.util.concurrent.ConcurrentHashMap

private const val MODEL_HOST = "iris.local"

/**
 * 使用本地 three.js + GLTFLoader 渲染 GLB / VRM 角色模型。
 * 页面使用虚拟 https 源，模型请求由 WebViewClient 从 filesDir 直接拦截返回，
 * 既避免 file:// 下 fetch 被禁止，也不启动本地端口。
 */
@Composable
internal fun CharacterModel3DView(
    modelFile: File,
    modelType: String,
    accentHex: String?,
    dark: Boolean,
    portrait: Boolean = false,
    interactive: Boolean = true,
    modelRoot: String = "character_models",
    motionEndpoint: String = "",
    motionIdle: Boolean = false,
    motionPack: String = "",
    motionAxisFix: Int = 1,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val appSettings by remember(context) {
        (context.applicationContext as IrisApp).graph.settings.settings
    }.collectAsStateWithLifecycle()
    val vrmaEnabled = appSettings.vrmMotionEngine == VrmMotionEngine.VRMA
    val resolvedEndpoint = if (vrmaEnabled) "" else motionEndpoint
    val resolvedPack = if (vrmaEnabled) "vrma" else motionPack
    val key = "${modelFile.absolutePath}|$modelType|$accentHex|$dark|$portrait|$interactive|$modelRoot|$resolvedEndpoint|$motionIdle|$resolvedPack|$motionAxisFix|$vrmaEnabled"
    var webView by remember { mutableStateOf<WebView?>(null) }
    val motionEnabled = resolvedEndpoint.isNotBlank() || vrmaEnabled
    DisposableEffect(motionEnabled) {
        MotionBridge.registerView(motionEnabled)
        onDispose { MotionBridge.unregisterView(motionEnabled) }
    }
    val motionCommand by MotionBridge.command.collectAsState()
    val expressionCommand by MotionBridge.expressionCommand.collectAsState()
    val vrmaCommand by MotionBridge.vrmaCommand.collectAsState()
    LaunchedEffect(motionCommand?.seq) {
        val command = motionCommand ?: return@LaunchedEffect
        webView?.evaluateJavascript(
            "window.irisMotion&&window.irisMotion.play(${JSONObject.quote(command.action)})",
            null,
        )
    }
    LaunchedEffect(vrmaCommand?.seq) {
        val command = vrmaCommand ?: return@LaunchedEffect
        if (command.url.isBlank()) {
            webView?.evaluateJavascript("window.irisMotion&&window.irisMotion.stopVrma()", null)
        } else {
            webView?.evaluateJavascript(
                "window.irisMotion&&window.irisMotion.playVrma(${JSONObject.quote(command.url)}, ${command.loop})",
                null,
            )
        }
    }
    LaunchedEffect(expressionCommand?.seq) {
        val name = expressionCommand?.name ?: return@LaunchedEffect
        webView?.evaluateJavascript(
            "window.irisMotion&&window.irisMotion.setExpression(${JSONObject.quote(name)})",
            null,
        )
    }
    AndroidView(
        factory = { context ->
            WebView(context).apply {
                webView = this
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
                            "Character3D",
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
                }
                loadCharacter(this, context, modelFile, modelType, accentHex, dark, portrait, interactive, modelRoot, resolvedEndpoint, motionIdle, resolvedPack, motionAxisFix, vrmaEnabled)
                tag = key
            }
        },
        update = { view ->
            if (view.tag != key) {
                view.tag = key
                loadCharacter(view, view.context, modelFile, modelType, accentHex, dark, portrait, interactive, modelRoot, resolvedEndpoint, motionIdle, resolvedPack, motionAxisFix, vrmaEnabled)
            }
        },
        onRelease = { view ->
            webView = null
            releaseModelWebView(view)
        },
        modifier = modifier,
    )
}

private fun loadCharacter(
    view: WebView,
    context: Context,
    modelFile: File,
    modelType: String,
    accentHex: String?,
    dark: Boolean,
    portrait: Boolean,
    interactive: Boolean,
    modelRoot: String,
    motionEndpoint: String,
    motionIdle: Boolean,
    motionPack: String,
    motionAxisFix: Int,
    vrmaEnabled: Boolean,
) {
    val storage = (context.applicationContext as IrisApp).graph.storage
    val root = storage.containingRoot(modelRoot, modelFile)
        ?: File(context.filesDir, modelRoot).canonicalFile
    val relative = runCatching {
        modelFile.canonicalFile.relativeTo(root).invariantSeparatorsPath
    }.getOrNull()
    if (relative.isNullOrBlank() || !modelFile.isFile) {
        view.loadDataWithBaseURL(
            "https://$MODEL_HOST/",
            "<html><body style='background:transparent;color:#fff;font:13px sans-serif;display:grid;place-items:center'>角色模型文件不存在</body></html>",
            "text/html",
            "UTF-8",
            null,
        )
        return
    }
    val prefix = if (modelRoot == "character_models") "/models/" else "/petmodels/"
    val modelUrl = "https://$MODEL_HOST$prefix${relative.split('/').joinToString("/") { Uri.encode(it) }}"
    val config = JSONObject()
        .put("model", modelUrl)
        .put("type", modelType)
        .put("accent", accentHex ?: "#0D84FF")
        .put("dark", dark)
        .put("portrait", portrait)
        .put("interactive", interactive)
        .put("motion", motionEndpoint)
        .put("motionIdle", motionIdle)
        .put("pack", motionPack)
        .put("axisFix", motionAxisFix)
        .put("vrmaEnabled", vrmaEnabled)
        .toString()
    view.loadDataWithBaseURL(
        "https://$MODEL_HOST/",
        characterHtml(context, config),
        "text/html",
        "UTF-8",
        null,
    )
}

internal fun interceptCharacterModel(context: Context, request: WebResourceRequest): WebResourceResponse? {
    val url = request.url
    if (url.host != MODEL_HOST) return null
    val path = url.path ?: return null
    if (path.startsWith("/assets/")) return assetResponse(context, Uri.decode(path.removePrefix("/assets/")))
    if (path == "/hast/motion") {
        val name = Uri.decode(url.getQueryParameter("name").orEmpty())
        val json = HastMotionStore.get(context).motionJson(name) ?: return notFound()
        return WebResourceResponse(
            "application/json",
            "UTF-8",
            200,
            "OK",
            mapOf(
                "Access-Control-Allow-Origin" to "*",
                "Cache-Control" to "no-store",
            ),
            ByteArrayInputStream(json.toByteArray(Charsets.UTF_8)),
        )
    }
    val scene = path.startsWith("/scene/")
    val petModels = path.startsWith("/petmodels/")
    val motionAssets = path.startsWith("/motionassets/")
    if (!scene && !motionAssets && !path.startsWith("/models/") && !petModels) return null
    val relative = Uri.decode(
        path.removePrefix(
            when {
                scene -> "/scene/"
                motionAssets -> "/motionassets/"
                petModels -> "/petmodels/"
                else -> "/models/"
            },
        ),
    ).replace('\\', '/')
    val rootFolder = when {
        scene -> "scenes"
        motionAssets -> "motion_assets"
        petModels -> "pet_models"
        else -> "character_models"
    }
    val storage = (context.applicationContext as IrisApp).graph.storage
    val file = storage.readDirs(rootFolder).asSequence()
        .mapNotNull { root ->
            val canonical = runCatching { root.canonicalFile }.getOrNull() ?: return@mapNotNull null
            resolveModelFile(canonical, relative, scene || motionAssets)
                ?.takeIf { it.isFile && it.path.startsWith(canonical.path + File.separator) }
        }
        .firstOrNull()
        ?: return notFound()
    if (!scene && !motionAssets) {
        optimizedTexture(context, file)?.let { return it }
    }
    return runCatching {
        WebResourceResponse(
            mimeOf(file.name),
            null,
            200,
            "OK",
            mapOf(
                "Content-Length" to file.length().toString(),
                "Cache-Control" to "no-store",
                "Access-Control-Allow-Origin" to "*",
            ),
            file.inputStream(),
        )
    }.getOrNull() ?: notFound()
}

private val resolvedModelPaths = ConcurrentHashMap<String, String>()

private fun resolveModelFile(root: File, relative: String, scene: Boolean): File? {
    val exact = runCatching { File(root, relative).canonicalFile }.getOrNull()
    if (exact != null && exact.isFile) return exact
    if (scene) return exact
    val key = root.path + "|" + relative
    resolvedModelPaths[key]?.let { cached ->
        val file = File(cached)
        if (file.isFile) return file
        resolvedModelPaths.remove(key)
    }
    val wanted = relative.substringAfterLast('/')
    if (wanted.isBlank()) return exact
    val found = root.walkTopDown()
        .take(6000)
        .firstOrNull { it.isFile && it.name.equals(wanted, ignoreCase = true) }
        ?.canonicalFile
    if (found != null) resolvedModelPaths[key] = found.absolutePath
    return found ?: exact
}

private fun optimizedTexture(context: Context, file: File, maxSide: Int = 1024): WebResourceResponse? {
    val extension = file.extension.lowercase()
    if (extension !in setOf("png", "jpg", "jpeg", "webp")) return null
    if (file.length() < 400_000) return null
    val cacheDir = File(context.cacheDir, "model-textures").apply { mkdirs() }
    val cache = File(
        cacheDir,
        "${file.absolutePath.hashCode()}-${file.lastModified()}-${file.length()}-$maxSide.webp",
    )
    if (cache.isFile) return imageResponse(cache)
    return runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        if (bounds.outWidth <= maxSide && bounds.outHeight <= maxSide && file.length() < 1_500_000) return null
        var sample = 1
        while (bounds.outWidth / sample > maxSide || bounds.outHeight / sample > maxSide) sample *= 2
        val decoded = BitmapFactory.decodeFile(
            file.absolutePath,
            BitmapFactory.Options().apply { inSampleSize = sample },
        ) ?: return null
        val decodedSide = maxOf(decoded.width, decoded.height)
        val scaled = if (decodedSide > maxSide) {
            val ratio = maxSide.toFloat() / decodedSide
            Bitmap.createScaledBitmap(
                decoded,
                (decoded.width * ratio).toInt().coerceAtLeast(1),
                (decoded.height * ratio).toInt().coerceAtLeast(1),
                true,
            )
        } else {
            decoded
        }
        val output = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.WEBP_LOSSY, 90, output)
        if (scaled !== decoded) scaled.recycle()
        decoded.recycle()
        val bytes = output.toByteArray()
        if (bytes.isEmpty() || bytes.size >= file.length()) return null
        val temp = File(cacheDir, "${cache.name}.tmp")
        temp.writeBytes(bytes)
        if (!temp.renameTo(cache)) {
            cache.writeBytes(bytes)
            temp.delete()
        }
        imageResponse(cache)
    }.getOrNull()
}

private fun assetResponse(context: Context, relative: String): WebResourceResponse? {
    if (relative.isBlank() || relative.contains("..")) return null
    return runCatching {
        val headers = mapOf(
            "Access-Control-Allow-Origin" to "*",
            "Cache-Control" to if (relative.endsWith(".wasm")) "max-age=604800" else "no-cache",
        )
        if (relative.endsWith(".wasm")) {
            val bytes = context.assets.open(relative).use { it.readBytes() }
            WebResourceResponse(
                "application/wasm",
                null,
                200,
                "OK",
                headers + ("Content-Length" to bytes.size.toString()),
                ByteArrayInputStream(bytes),
            )
        } else {
            WebResourceResponse(
                mimeOf(relative),
                null,
                200,
                "OK",
                headers,
                context.assets.open(relative),
            )
        }
    }.getOrNull()
}

private fun imageResponse(file: File): WebResourceResponse = WebResourceResponse(
    "image/webp",
    null,
    200,
    "OK",
    mapOf(
        "Content-Length" to file.length().toString(),
        "Cache-Control" to "max-age=86400",
        "Access-Control-Allow-Origin" to "*",
    ),
    FileInputStream(file),
)

private fun notFound(): WebResourceResponse = WebResourceResponse(
    "text/plain",
    "UTF-8",
    404,
    "Not Found",
    emptyMap(),
    ByteArrayInputStream("not found".toByteArray()),
)

private fun mimeOf(name: String): String = when (name.substringAfterLast('.').lowercase()) {
    "glb", "vrm" -> "model/gltf-binary"
    "vrma" -> "model/gltf-binary"
    "gltf" -> "model/gltf+json"
    "json" -> "application/json"
    "bin" -> "application/octet-stream"
    "png" -> "image/png"
    "jpg", "jpeg" -> "image/jpeg"
    "webp" -> "image/webp"
    "ktx2" -> "image/ktx2"
    "wasm" -> "application/wasm"
    "js" -> "text/javascript"
    "vmd" -> "application/octet-stream"
    "html" -> "text/html"
    "gif" -> "image/gif"
    "mp4", "m4v" -> "video/mp4"
    "webm" -> "video/webm"
    else -> "application/octet-stream"
}

private fun characterHtml(context: Context, configJson: String): String {
    val template = context.assets.open("character.html").bufferedReader().use { it.readText() }
    return template.replace("__CHARACTER_CONFIG_JSON__", configJson)
}
