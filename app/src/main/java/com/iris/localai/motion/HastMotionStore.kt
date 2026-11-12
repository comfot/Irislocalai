package com.iris.localai.motion

import android.content.Context
import com.iris.localai.data.DownloadSources
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * HAST-SEC 动作库：下载官方仓库里的 skills 清单与 20fps 轨迹，
 * 解析成 53 骨骼四元数轨道，供 WebView 通过虚拟 URL 播放。
 * 只读取动作数据，不运行其 0.8B 语言模型或 three-vrm 运行时。
 */
class HastMotionStore(
    context: Context,
    private val mirrorsEnabled: () -> Boolean = { true },
) {

    private val appContext = context.applicationContext
    private val root = File(appContext.filesDir, "hast")
    private val skillsDir = File(root, "skills")
    private val trajectoriesDir = File(root, "trajectories")
    private val cache = LinkedHashMap<String, String>()
    private val trajectoryCache = HashMap<String, java.nio.FloatBuffer>()

    @Volatile
    private var bones: List<String> = emptyList()

    @Volatile
    private var rate: Int = 20

    fun isReady(): Boolean =
        File(root, "manifest.json").isFile && skillsDir.isDirectory && trajectoriesDir.isDirectory

    fun boneNames(): List<String> = bones

    suspend fun install(onProgress: (String) -> Unit = {}): String = withContext(Dispatchers.IO) {
        skillsDir.mkdirs()
        trajectoriesDir.mkdirs()
        val manifest = runCatching { fetch("models/skills/manifest.json") }
            .getOrElse { return@withContext "清单下载失败：${it.message}" }
        File(root, "manifest.json").writeText(manifest)
        val manifestJson = JSONObject(manifest)
        bones = manifestJson.getJSONArray("bones").let { array ->
            List(array.length()) { array.getString(it) }
        }
        rate = manifestJson.optInt("rate", 20)
        val classes = manifestJson.getJSONArray("classes")
        val classFiles = List(classes.length()) { classes.getJSONObject(it).getString("file") }

        var done = 1
        val total = classFiles.size + 1
        val sources = LinkedHashSet<String>()
        classFiles.forEachIndexed { index, name ->
            val target = File(skillsDir, name)
            val body = if (target.isFile && target.length() > 0) {
                target.readText()
            } else {
                val downloaded = runCatching { fetch("models/skills/$name") }
                    .getOrElse { return@withContext "技能 $name 下载失败：${it.message}" }
                target.writeText(downloaded)
                downloaded
            }
            runCatching {
                val skills = JSONObject(body).getJSONArray("skills")
                for (i in 0 until skills.length()) sources.add(skills.getJSONObject(i).getString("source"))
            }
            done += 1
            onProgress("技能 $done/${classFiles.size}")
        }
        val allSources = sources.toList()
        allSources.forEachIndexed { index, source ->
            val target = File(trajectoriesDir, "$source.bin")
            if (!target.isFile || target.length() == 0L) {
                val bytes = runCatching { fetchBytes("models/trajectories/$source.bin") }
                    .getOrElse { return@withContext "轨迹 $source 下载失败：${it.message}" }
                target.writeBytes(bytes)
            }
            onProgress("轨迹 ${index + 1}/${allSources.size}")
        }
        val controlDir = File(root, "control").apply { mkdirs() }
        runCatching {
            File(controlDir, "idle.json").writeBytes(fetchBytes("models/control/idle.json"))
            File(controlDir, "idle.bin").writeBytes(fetchBytes("models/control/idle.bin"))
        }.onFailure { onProgress("待机数据下载失败：${it.message}") }
        cache.clear()
        trajectoryCache.clear()
        warmUp()
        "HAST-SEC 动作库已就绪：${classFiles.size} 个技能、${allSources.size} 段轨迹"
    }

    /** 预先解析全部技能，避免 WebView 请求时在拦截线程里读盘。 */
    @Synchronized
    fun warmUp() {
        if (!isReady()) return
        val manifest = runCatching { JSONObject(File(root, "manifest.json").readText()) }.getOrNull() ?: return
        bones = manifest.getJSONArray("bones").let { array -> List(array.length()) { array.getString(it) } }
        rate = manifest.optInt("rate", 20)
        cache.clear()
        buildIdleMotion()?.let { cache["idle"] = it }
        val classes = manifest.getJSONArray("classes")
        for (i in 0 until classes.length()) {
            val entry = classes.getJSONObject(i)
            val key = entry.getString("key")
            val file = entry.getString("file")
            runCatching { buildMotion(key, File(skillsDir, file)) }.getOrNull()?.let { cache[key] = it }
        }
    }

    @Synchronized
    fun motionJson(name: String): String? {
        if (cache.isEmpty() && isReady()) warmUp()
        if (name.isBlank()) return cache.values.firstOrNull()
        cache[name]?.let { return it }
        val lowered = name.lowercase()
        return cache.entries.firstOrNull { it.key.lowercase().contains(lowered) }?.value
            ?: cache.entries.firstOrNull { lowered.contains(it.key.lowercase()) }?.value
    }

    @Synchronized
    fun catalog(): List<String> = cache.keys.toList()

    /** 官方待机轨迹：models/control/idle.{json,bin}，每段 61 帧，offset 以 float 为单位。 */
    private fun buildIdleMotion(): String? {
        val metaFile = File(root, "control/idle.json")
        val binFile = File(root, "control/idle.bin")
        if (!metaFile.isFile || !binFile.isFile) return null
        val meta = runCatching { JSONObject(metaFile.readText()) }.getOrNull() ?: return null
        val entries = meta.optJSONArray("entries") ?: return null
        var best: JSONObject? = null
        var bestVelocity = Double.MAX_VALUE
        for (i in 0 until entries.length()) {
            val entry = entries.getJSONObject(i)
            val velocity = entry.optJSONObject("quality")?.optDouble("velocity", 0.0) ?: 0.0
            if (velocity < bestVelocity) {
                bestVelocity = velocity
                best = entry
            }
        }
        val entry = best ?: return null
        val offset = entry.optInt("offset", 0)
        val frames = entry.optInt("frames", 0)
        if (frames < 2) return null
        val floats = runCatching {
            ByteBuffer.wrap(binFile.readBytes()).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
        }.getOrNull() ?: return null
        return renderMotion("idle", floats, offset, frames, true)
    }

    private fun buildMotion(key: String, classFile: File): String? {
        if (!classFile.isFile) return null
        val obj = JSONObject(classFile.readText())
        val skills = obj.optJSONArray("skills") ?: return null
        var chosen: JSONObject? = null
        var bestScore = -Double.MAX_VALUE
        for (i in 0 until skills.length()) {
            val skill = skills.getJSONObject(i)
            val score = skill.optDouble("score", 0.0)
            if (score > bestScore) {
                bestScore = score
                chosen = skill
            }
        }
        val skill = chosen ?: return null
        val source = skill.getString("source")
        val start = skill.optInt("start", 0)
        val end = skill.optInt("end", start + 1)
        val floats = readFloats(source, File(trajectoriesDir, "$source.bin")) ?: return null
        val stride = bones.size * 4
        val frames = floats.capacity() / stride
        if (frames <= 0) return null
        val from = start.coerceIn(0, frames - 1)
        val to = end.coerceIn(from + 1, frames)
        val count = to - from
        if (count < 2) return null
        return renderMotion(key, floats, from * stride, count, key.startsWith("body-"))
    }

    private fun renderMotion(
        name: String,
        floats: java.nio.FloatBuffer,
        startOffset: Int,
        count: Int,
        loop: Boolean,
    ): String {
        val boneCount = bones.size
        val stride = boneCount * 4
        val totalFrames = floats.capacity() / stride
        if (totalFrames <= 0) return ""
        val first = (startOffset / stride).coerceIn(0, totalFrames - 1)
        val duration = count.toDouble() / rate
        val builder = StringBuilder(count * boneCount * 40)
        builder.append("{\"name\":").append(JSONObject.quote(name))
            .append(",\"fps\":").append(rate)
            .append(",\"duration\":").append(String.format(Locale.US, "%.3f", duration))
            .append(",\"loop\":").append(loop)
            .append(",\"tracks\":[")
        for (boneIndex in 0 until boneCount) {
            if (boneIndex > 0) builder.append(',')
            builder.append("{\"bone\":").append(JSONObject.quote(bones[boneIndex])).append(",\"times\":[")
            for (frame in 0..count) {
                if (frame > 0) builder.append(',')
                builder.append(String.format(Locale.US, "%.3f", frame.toDouble() / rate))
            }
            builder.append("],\"quaternions\":[")
            for (frame in 0..count) {
                val sourceFrame = (first + frame).coerceAtMost(totalFrames - 1)
                val offset = sourceFrame * stride + boneIndex * 4
                if (frame > 0) builder.append(',')
                builder.append(String.format(Locale.US, "%.5f", floats.get(offset))).append(',')
                builder.append(String.format(Locale.US, "%.5f", floats.get(offset + 1))).append(',')
                builder.append(String.format(Locale.US, "%.5f", floats.get(offset + 2))).append(',')
                builder.append(String.format(Locale.US, "%.5f", floats.get(offset + 3)))
            }
            builder.append("]}")
        }
        builder.append("]}")
        return builder.toString()
    }

    private fun readFloats(source: String, file: File): java.nio.FloatBuffer? {
        trajectoryCache[source]?.let { return it }
        if (!file.isFile) return null
        return runCatching {
            ByteBuffer.wrap(file.readBytes()).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
                .also { trajectoryCache[source] = it }
        }.getOrNull()
    }

    private fun fetch(path: String): String = String(fetchBytes(path), Charsets.UTF_8)

    private fun fetchBytes(path: String): ByteArray {
        val hosts = DownloadSources.hfHosts(mirrorsEnabled())
        var lastError: Exception? = null
        for (host in hosts) {
            try {
                val connection = (URL("$host/sola1039/HAST-SEC/resolve/main/$path").openConnection() as HttpURLConnection).apply {
                    connectTimeout = 20_000
                    readTimeout = 60_000
                    instanceFollowRedirects = true
                    setRequestProperty("User-Agent", "IRIS-LocalAI")
                }
                try {
                    if (connection.responseCode !in 200..299) error("HTTP ${connection.responseCode}")
                    return connection.inputStream.use { it.readBytes() }
                } finally {
                    connection.disconnect()
                }
            } catch (error: Exception) {
                lastError = error
            }
        }
        throw lastError ?: IllegalStateException("下载失败")
    }

    companion object {
        @Volatile
        private var instance: HastMotionStore? = null

        /** 首个调用方（AppGraph）决定镜像策略，后续调用复用单例。 */
        fun get(
            context: Context,
            mirrorsEnabled: (() -> Boolean)? = null,
        ): HastMotionStore =
            instance ?: synchronized(this) {
                instance ?: HastMotionStore(
                    context.applicationContext,
                    mirrorsEnabled ?: { true },
                ).also { store ->
                    instance = store
                    if (store.isReady() && store.catalog().isEmpty()) {
                        Thread { store.warmUp() }.apply { isDaemon = true }.start()
                    }
                }
            }
    }
}
