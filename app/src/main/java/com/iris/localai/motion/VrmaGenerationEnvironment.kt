package com.iris.localai.motion

import android.content.Context
import android.net.Uri
import com.iris.localai.code.CodeSettingsStore
import com.iris.localai.data.CloudVendor
import com.iris.localai.data.ModelProfileStore
import com.iris.localai.data.ModelRole
import com.iris.localai.data.ProviderKind
import com.iris.localai.motion.VrmaAsset
import com.iris.localai.proot.ProotRuntime
import com.iris.localai.proot.ProotSession
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Text-To-VRMA 的内置运行时：
 * - 源码与 node_modules 随 APK 放在 assets/vrma_runtime；
 * - 首次启动复制到 filesDir/vrma_generation，通过 PRoot 内置 Node 常驻执行 tools/api-server.mjs；
 * - 云端模型沿用 IRIS 当前语言模型 provider，写入 .env 后由 Node 请求 OpenAI/Claude。
 */
data class PendingVrma(
    val id: String,
    val displayName: String,
    val file: File,
    val sizeBytes: Long,
) {
    fun previewUrl(): String = "https://iris.local/vrma_pending/" + Uri.encode(file.name)
}

class VrmaGenerationEnvironment(
    context: Context,
    private val models: ModelProfileStore,
    private val code: CodeSettingsStore,
    private val vrmaStore: VrmaMotionStore,
) {

    private val appContext = context.applicationContext
    private val runtime = ProotRuntime(appContext)
    private val workspace = File(appContext.filesDir, "vrma_generation").apply { mkdirs() }
    private val pendingDir = File(workspace, "pending").apply { mkdirs() }
    private var pendingSeq = 0
    private val token = appContext.getSharedPreferences("vrma_generation", Context.MODE_PRIVATE)
        .let { prefs ->
            prefs.getString("token", null) ?: UUID.randomUUID().toString().also { value ->
                prefs.edit().putString("token", value).apply()
            }
        }
    @Volatile
    private var session: ProotSession? = null
    @Volatile
    private var engine = "openai"

    fun imageId(): String = code.settings.value.imageId

    fun rootfsReady(): Boolean = runtime.status(imageId(), includeSize = false).rootfsInstalled

    suspend fun status(): String = withContext(Dispatchers.IO) {
        if (health()) return@withContext "VRMA 生成服务运行中 · 127.0.0.1:8787"
        if (!rootfsReady()) return@withContext "需要先在 编程管理 › 容器与镜像 安装 rootfs"
        val node = runCatching {
            runtime.execute(imageId(), workspace, "node -v", 20, 4000)
        }.getOrNull()
        when {
            node == null || node.exitCode != 0 -> "PRoot 中未找到 Node（需要 Node 20+）"
            else -> "Node 已就绪：${node.stdout.trim()}，服务未启动"
        }
    }

    suspend fun start(): String = withContext(Dispatchers.IO) {
        if (health()) return@withContext "VRMA 生成服务已在运行"
        if (!rootfsReady()) return@withContext "请先安装 PRoot rootfs"
        val node = runCatching {
            runtime.execute(imageId(), workspace, "node -v", 20, 4000)
        }.getOrNull()
        if (node == null || node.exitCode != 0) return@withContext "PRoot 中没有 Node，请先安装 Node 20+"
        val version = node.stdout.trim().removePrefix("v").substringBefore('.')
        if ((version.toIntOrNull() ?: 0) < 20) return@withContext "Node 版本过低（${node.stdout.trim()}），需要 Node 20+"
        seedRuntime()
        val provider = providerInfo() ?: return@withContext "请先在模型设置中配置云端语言模型（OpenAI/Claude）"
        writeEnv(provider)
        session?.takeIf { it.isAlive }?.stop()
        session = null
        // 上一次 App 进程留下的 Node 服务可能仍占用 8787，且 token 已变化。
        runCatching {
            runtime.execute(
                imageId(),
                workspace,
                "pkill -f 'tools/api-server.mjs' || true",
                10,
                4000,
            )
        }
        val result = runCatching {
            runtime.openSession(imageId(), workspace, "cd /workspace && exec node tools/api-server.mjs")
        }.getOrElse { return@withContext "启动失败：${it.message}" }
        session = result
        repeat(30) {
            if (health()) return@withContext "VRMA 生成服务已启动 · ${engine}"
            if (!result.isAlive) {
                val log = result.log().trim().lines().takeLast(2).joinToString(" / ")
                result.stop()
                session = null
                return@withContext "启动失败：${log.ifBlank { "进程已退出" }}"
            }
            delay(500)
        }
        val log = result.log().trim().lines().takeLast(2).joinToString(" / ")
        result.stop()
        session = null
        "启动超时：${log.ifBlank { "没有健康检查响应" }}"
    }

    suspend fun stop(): String = withContext(Dispatchers.IO) {
        val current = session
        session = null
        if (current == null || !current.isAlive) return@withContext "VRMA 生成服务未运行"
        current.stop()
        "VRMA 生成服务已停止"
    }

    suspend fun generate(
        prompt: String,
        duration: Double = 0.0,
    ): VrmaAsset = withContext(Dispatchers.IO) {
        val pending = generatePending(prompt, duration)
        try {
            savePending(pending, "generated-${System.currentTimeMillis()}")
        } finally {
            deletePending(pending)
        }
    }

    suspend fun generatePending(
        prompt: String,
        duration: Double = 0.0,
    ): PendingVrma = withContext(Dispatchers.IO) {
        require(prompt.isNotBlank()) { "动作描述不能为空" }
        val bytes = requestVrma(prompt, duration)
        require(bytes.size >= 12 && bytes[0] == 'g'.code.toByte() && bytes[1] == 'l'.code.toByte() &&
            bytes[2] == 'T'.code.toByte() && bytes[3] == 'F'.code.toByte()) {
            "生成结果不是有效的 GLB/VRMA 文件"
        }
        pendingDir.mkdirs()
        val file = File(pendingDir, "pending-${System.currentTimeMillis()}-${pendingSeq++}.vrma")
        file.writeBytes(bytes)
        PendingVrma(
            id = file.nameWithoutExtension,
            displayName = prompt.trim().take(30).ifBlank { "未命名动作" },
            file = file,
            sizeBytes = bytes.size.toLong(),
        )
    }

    fun savePending(pending: PendingVrma, name: String): VrmaAsset {
        val asset = vrmaStore.importFile(name.ifBlank { pending.displayName }, pending.file)
        deletePending(pending)
        return asset
    }

    fun deletePending(pending: PendingVrma) {
        runCatching { pending.file.delete() }
    }

    fun pendingFile(fileName: String): File? {
        val file = File(pendingDir, fileName).canonicalFile
        val root = pendingDir.canonicalFile
        return file.takeIf { it.isFile && it.path.startsWith(root.path + File.separator) }
    }

    private suspend fun requestVrma(prompt: String, duration: Double): ByteArray {
        val started = start()
        if (!health()) error(started)
        val payload = buildJsonObject {
            put("prompt", prompt.trim().take(4000))
            put("engine", engine)
            put("format", "vrma")
            put("refine", false)
            if (duration > 0) put("duration", duration)
            put("use_jev", false)
        }.toString()
        val connection = (URL("http://127.0.0.1:8787/v1/motions").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 20_000
            readTimeout = 180_000
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Authorization", "Bearer $token")
        }
        try {
            connection.outputStream.use { it.write(payload.toByteArray()) }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val bytes = stream?.use { it.readBytes() } ?: ByteArray(0)
            if (code !in 200..299) {
                val message = runCatching {
                    Json.parseToJsonElement(String(bytes)).jsonObject["error"]
                        ?.jsonObject?.get("message")?.jsonPrimitive?.content
                }.getOrNull()
                error("生成失败（HTTP $code）：${message ?: String(bytes).take(300)}")
            }
            if (bytes.size > 64 * 1024 * 1024) error("生成结果超过 64 MB")
            return bytes
        } finally {
            connection.disconnect()
        }
    }

    private data class ProviderInfo(val key: String, val endpoint: String, val model: String, val anthropic: Boolean)

    private fun providerInfo(): ProviderInfo? {
        val profile = models.active()
        val provider = models.providerFor(profile, ModelRole.LANGUAGE)
        if (provider.kind != ProviderKind.CLOUD || provider.apiKey.isBlank()) return null
        val model = models.modelNameFor(profile, ModelRole.LANGUAGE)
        return ProviderInfo(provider.apiKey, provider.endpoint, model, provider.vendor == CloudVendor.ANTHROPIC)
    }

    private fun writeEnv(info: ProviderInfo) {
        engine = if (info.anthropic) "claude" else "openai"
        val lines = mutableListOf(
            "PORT=8787",
            "HOST=127.0.0.1",
            "TEXT_TO_MOTION_API_TOKEN=$token",
        )
        if (info.anthropic) {
            lines += "ANTHROPIC_API_KEY=${envValue(info.key)}"
            lines += "ANTHROPIC_MODEL=${envValue(info.model)}"
        } else {
            lines += "OPENAI_API_KEY=${envValue(info.key)}"
            lines += "OPENAI_BASE_URL=${envValue(info.endpoint)}"
            lines += "OPENAI_MODEL=${envValue(info.model)}"
        }
        File(workspace, ".env").writeText(lines.joinToString("\n") + "\n")
    }

    private fun envValue(value: String): String =
        "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "") + "\""

    private fun seedRuntime() {
        if (File(workspace, "tools/api-server.mjs").isFile && File(workspace, "node_modules").isDirectory) return
        copyAssetDir("vrma_runtime", workspace)
    }

    private fun copyAssetDir(assetPath: String, target: File) {
        target.mkdirs()
        contextAssets().list(assetPath).orEmpty().forEach { child ->
            val childAsset = "$assetPath/$child"
            val childTarget = File(target, child)
            val grandChildren = runCatching { contextAssets().list(childAsset) }.getOrNull().orEmpty()
            if (grandChildren.isNotEmpty()) {
                copyAssetDir(childAsset, childTarget)
            } else {
                childTarget.parentFile?.mkdirs()
                contextAssets().open(childAsset).use { input ->
                    childTarget.outputStream().use { output -> input.copyTo(output) }
                }
            }
        }
    }

    private fun contextAssets() = appContext.assets

    private fun health(): Boolean = runCatching {
        val connection = (URL("http://127.0.0.1:8787/health").openConnection() as HttpURLConnection).apply {
            connectTimeout = 1200
            readTimeout = 1200
            requestMethod = "GET"
            setRequestProperty("Authorization", "Bearer $token")
        }
        try {
            connection.responseCode == 200
        } finally {
            connection.disconnect()
        }
    }.getOrDefault(false)
}
