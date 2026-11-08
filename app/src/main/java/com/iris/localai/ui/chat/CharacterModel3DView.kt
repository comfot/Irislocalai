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
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
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
    modifier: Modifier = Modifier,
) {
    val key = "${modelFile.absolutePath}|$modelType|$accentHex|$dark"
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
                loadCharacter(this, context, modelFile, modelType, accentHex, dark)
                tag = key
            }
        },
        update = { view ->
            if (view.tag != key) {
                view.tag = key
                loadCharacter(view, view.context, modelFile, modelType, accentHex, dark)
            }
        },
        onRelease = { view -> releaseModelWebView(view) },
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
) {
    val root = File(context.filesDir, "character_models").canonicalFile
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
    val modelUrl = "https://$MODEL_HOST/models/${relative.split('/').joinToString("/") { Uri.encode(it) }}"
    val config = JSONObject()
        .put("model", modelUrl)
        .put("type", modelType)
        .put("accent", accentHex ?: "#0D84FF")
        .put("dark", dark)
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
    val scene = path.startsWith("/scene/")
    if (!scene && !path.startsWith("/models/")) return null
    val relative = Uri.decode(path.removePrefix(if (scene) "/scene/" else "/models/"))
        .replace('\\', '/')
    val root = File(context.filesDir, if (scene) "scenes" else "character_models").canonicalFile
    val file = resolveModelFile(root, relative, scene) ?: return notFound()
    if (!file.path.startsWith(root.path + File.separator) || !file.isFile) return notFound()
    if (!scene) {
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

private fun optimizedTexture(context: Context, file: File): WebResourceResponse? {
    val extension = file.extension.lowercase()
    if (extension !in setOf("png", "jpg", "jpeg", "webp")) return null
    if (file.length() < 400_000) return null
    val cacheDir = File(context.cacheDir, "model-textures").apply { mkdirs() }
    val cache = File(
        cacheDir,
        "${file.absolutePath.hashCode()}-${file.lastModified()}-${file.length()}.webp",
    )
    if (cache.isFile) return imageResponse(cache)
    return runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        if (bounds.outWidth <= 1024 && bounds.outHeight <= 1024 && file.length() < 1_500_000) return null
        var sample = 1
        while (bounds.outWidth / sample > 1024 || bounds.outHeight / sample > 1024) sample *= 2
        val decoded = BitmapFactory.decodeFile(
            file.absolutePath,
            BitmapFactory.Options().apply { inSampleSize = sample },
        ) ?: return null
        val maxSide = maxOf(decoded.width, decoded.height)
        val scaled = if (maxSide > 1024) {
            val ratio = 1024f / maxSide
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

private var cachedCharacterTemplate: String? = null
private var cachedCharacterScript: String? = null

private fun characterHtml(context: Context, configJson: String): String {
    val template = cachedCharacterTemplate ?: run {
        val html = context.assets.open("character.html").bufferedReader().use { it.readText() }
        val three = context.assets.open("model/three.min.js").bufferedReader().use { it.readText() }
        val geometry = context.assets.open("model/BufferGeometryUtils.js").bufferedReader().use { it.readText() }
        val loader = context.assets.open("model/GLTFLoader.js").bufferedReader().use { it.readText() }
        val scene = context.assets.open("character.js").bufferedReader().use { it.readText() }
        cachedCharacterScript = scene
        html
            .replace(
                "<script src=\"model/three.min.js\"></script>",
                "<script>\n" + three.replace("</script", "<\\/script") + "\n</script>",
            )
            .replace(
                "<script src=\"model/BufferGeometryUtils.js\"></script>",
                "<script>\n" + geometry.replace("</script", "<\\/script") + "\n</script>",
            )
            .replace(
                "<script src=\"model/GLTFLoader.js\"></script>",
                "<script>\n" + loader.replace("</script", "<\\/script") + "\n</script>",
            )
            .replace("<script src=\"character.js\"></script>", "__CHARACTER_SCRIPT__")
            .also { cachedCharacterTemplate = it }
    }
    val script = cachedCharacterScript.orEmpty().replace("</script", "<\\/script")
    return template.replace(
        "__CHARACTER_SCRIPT__",
        "<script>window.__CHARACTER_CONFIG__ = $configJson;</script><script>\n$script\n</script>",
    )
}
