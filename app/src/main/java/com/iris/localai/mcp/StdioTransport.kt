package com.iris.localai.mcp

import android.content.Context
import com.iris.localai.code.CodeSettingsStore
import com.iris.localai.proot.ProotRuntime
import com.iris.localai.workspace.WorkspaceStore
import java.io.BufferedWriter
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject

/**
 * MCP stdio：在已安装的 PRoot rootfs 内启动长驻命令，stdin/stdout 走一行一条的 JSON-RPC，
 * stderr 只做排空避免阻塞。命令与参数来自 MCP 配置，工作区沿用当前应用内工作区。
 */
class StdioTransport(
    private val context: Context,
    private val config: McpServerConfig,
    private val code: CodeSettingsStore,
    private val workspaces: WorkspaceStore,
) : McpTransport {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val startMutex = Mutex()
    private val writeMutex = Mutex()
    private val pending = ConcurrentHashMap<Long, CompletableDeferred<JsonObject>>()
    private val idCounter = AtomicLong(0)

    @Volatile
    private var process: Process? = null

    @Volatile
    private var writer: BufferedWriter? = null

    @Volatile
    private var closed = false

    override suspend fun request(method: String, params: JsonObject): JsonObject {
        ensureStarted()
        val id = idCounter.incrementAndGet()
        val deferred = CompletableDeferred<JsonObject>()
        pending[id] = deferred
        try {
            writeLine(jsonRpcRequest(id, method, params))
        } catch (e: Exception) {
            pending.remove(id)
            throw e
        }
        val response = withTimeoutOrNull(REQUEST_TIMEOUT_MS) { deferred.await() }
            ?: run {
                pending.remove(id)
                throw McpTransportException("$method 等待 stdio 响应超时")
            }
        return validateRpcObject(response, method)
    }

    override suspend fun notify(method: String, params: JsonObject) {
        ensureStarted()
        writeLine(jsonRpcNotification(method, params))
    }

    override fun close() {
        if (closed) return
        closed = true
        runCatching { writer?.close() }
        runCatching { process?.destroy() }
        writer = null
        process = null
        scope.cancel()
        val error = McpTransportException("stdio 传输已关闭")
        pending.values.forEach { it.completeExceptionally(error) }
        pending.clear()
    }

    private suspend fun ensureStarted() = startMutex.withLock {
        if (process != null) return@withLock
        if (closed) throw McpTransportException("stdio 传输已关闭")
        val settings = code.settings.value
        val active = workspaces.active()
        if (active != null && !active.appFolder) {
            throw McpTransportException("stdio MCP 需要应用内工作区；当前是 SAF 文件夹，请切换工作区或改用 HTTP")
        }
        val workspace = File(
            active?.path?.takeIf { it.isNotBlank() }
                ?: File(context.filesDir, "workspace").path,
        )
        if (!workspace.isDirectory && !workspace.mkdirs()) {
            throw McpTransportException("无法创建工作区目录：${workspace.absolutePath}")
        }

        val runtime = ProotRuntime(context)
        val status = runCatching { runtime.status(settings.imageId, includeSize = false) }
            .getOrElse { throw McpTransportException("PRoot 状态检查失败：${it.message}") }
        if (!status.rootfsInstalled) {
            throw McpTransportException("PRoot 镜像未安装（${settings.imageId}）：请先到「编程管理 › 容器与镜像」安装")
        }
        if (!status.prootAvailable) {
            throw McpTransportException("PRoot 启动器缺失：${status.arch}")
        }

        val command = buildCommandLine()
        val env = parseEnv(config.env)
        val started = runCatching {
            withContext(Dispatchers.IO) {
                runtime.openProcess(settings.imageId, workspace, command, env)
            }
        }.getOrElse { throw McpTransportException("启动 stdio 进程失败：${it.message}", cause = it) }

        process = started
        writer = started.outputStream.bufferedWriter()
        scope.launch { readLoop(started) }
        scope.launch { drainStderr(started) }
    }

    private suspend fun writeLine(line: String) = writeMutex.withLock {
        val output = writer ?: throw McpTransportException("stdio 进程未启动")
        withContext(Dispatchers.IO) {
            output.write(line)
            output.write("\n")
            output.flush()
        }
    }

    private fun readLoop(started: Process) {
        try {
            started.inputStream.bufferedReader().use { reader ->
                while (true) {
                    val line = reader.readLine() ?: break
                    if (line.isBlank()) continue
                    val obj = runCatching { MCP_JSON.parseToJsonElement(line).jsonObject }.getOrNull()
                        ?: continue
                    if (obj.containsKey("method")) continue
                    val id = (obj["id"] as? JsonPrimitive)?.contentOrNull?.toLongOrNull() ?: continue
                    pending.remove(id)?.complete(obj)
                }
            }
        } catch (_: Exception) {
            // 进程退出或传输关闭，统一在下方失败所有等待项
        } finally {
            val error = McpTransportException("stdio 进程已退出")
            pending.values.forEach { it.completeExceptionally(error) }
            pending.clear()
        }
    }

    private fun drainStderr(started: Process) {
        runCatching {
            started.errorStream.bufferedReader().use { reader ->
                while (reader.readLine() != null) Unit
            }
        }
    }

    private fun buildCommandLine(): String {
        val command = config.command.trim()
        if (command.isBlank()) throw McpTransportException("stdio 命令为空")
        return (listOf(command) + config.args).joinToString(" ") { shellQuote(it) }
    }

    private fun parseEnv(raw: String): Map<String, String> = raw.lineSequence().mapNotNull { line ->
        val index = line.indexOf('=')
        if (index <= 0) return@mapNotNull null
        line.substring(0, index).trim() to line.substring(index + 1).trim()
    }.toMap()

    private fun shellQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

    private companion object {
        const val REQUEST_TIMEOUT_MS = 120_000L
    }
}
