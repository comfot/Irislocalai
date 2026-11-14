package com.iris.localai.plugin

import android.content.Context
import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.util.Log
import com.iris.localai.code.CodeSettingsStore
import com.iris.localai.data.RemoteFetch
import com.iris.localai.proot.ProotPaths
import com.iris.localai.proot.ProotRuntime
import com.iris.localai.proot.ProotSession
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.thread
import java.util.zip.ZipInputStream

/**
 * Operit `Tools.System.terminal` 的最小兼容实现。
 * 用 PRoot 承接 "linux" 环境；同一 session 保存 cwd 与 export，支持常驻后台进程。
 */
class PluginTerminalHost(
    context: Context,
    private val codeSettings: CodeSettingsStore,
) {

    private val appContext = context.applicationContext
    private val runtime = ProotRuntime(appContext)
    private val workspace = File(appContext.filesDir, "plugin_terminal").apply { mkdirs() }
    private val sessions = ConcurrentHashMap<String, Session>()
    private val preloadFile = File(workspace, "iris_loopback.js")

    private fun ensureLoopbackPreload() {
        val source = """
            const net = require('net');
            const fs = require('fs');
            const originalListen = net.Server.prototype.listen;
            net.Server.prototype.listen = function () {
              const result = originalListen.apply(this, arguments);
              const arg = arguments[0];
              const port = typeof arg === 'number' ? arg : (arg && typeof arg === 'object' ? Number(arg.port) : 0);
              if (port > 0) {
                setTimeout(function () {
                  const socketPath = '/workspace/loopback_' + port + '.sock';
                  try { fs.unlinkSync(socketPath); } catch (e) {}
                  const relay = net.createServer(function (client) {
                    const target = net.connect(port, '127.0.0.1');
                    client.pipe(target); target.pipe(client);
                    client.on('error', function () { target.destroy(); });
                    target.on('error', function () { client.destroy(); });
                  });
                  relay.on('error', function () {});
                  relay.listen(socketPath);
                }, 0);
              }
              return result;
            };
        """.trimIndent()
        runCatching {
            if (!preloadFile.isFile || preloadFile.readText() != source) preloadFile.writeText(source)
        }
    }

    private fun injectLoopbackPreload(command: String): String {
        if (!Regex("(^|[\\s;&|()])node([\\s]|$)").containsMatchIn(command)) return command
        ensureLoopbackPreload()
        return command.replace(
            Regex("(^|[\\s;&|()])node([\\s]|$)"),
        ) { match ->
            match.groupValues[1] + "env NODE_OPTIONS='--require /workspace/iris_loopback.js' node" + match.groupValues[2]
        }
    }

    fun create(name: String): String {
        sessions.values.firstOrNull { it.name == name }?.let { existing ->
            return buildJsonObject { put("sessionId", existing.id) }.toString()
        }
        val id = UUID.randomUUID().toString()
        sessions[id] = Session(id = id, name = name)
        return buildJsonObject { put("sessionId", id) }.toString()
    }

    fun exec(sessionId: String, command: String, timeoutMs: Int): String {
        val session = sessions[sessionId] ?: sessions.values.firstOrNull()
            ?: error("终端会话不存在，请重新打开插件 UI")
        val result = runCommand(session, command, timeoutMs)
        return result.toString()
    }

    fun execStreaming(sessionId: String, command: String, timeoutMs: Int): String = exec(sessionId, command, timeoutMs)

    private val loopbackLock = Any()
    private val loopbackPorts = mutableSetOf<Int>()
    private val loopbackDirectPorts = mutableSetOf<Int>()
    private val loopbackRelays = ConcurrentHashMap<Int, ProotSession>()

    /**
     * App 与 PRoot 可能处于不同 loopback 命名空间。把容器内的 127.0.0.1:port
     * 通过 /workspace 下的 Unix socket 引到 App 侧；App 侧再监听同名端口转发。
     * 若端口已在同一命名空间可用，ServerSocket 会绑定失败，调用方继续直连即可。
     */
    fun ensureLoopbackBridge(port: Int): Boolean {
        if (port !in 1..65535) return false
        synchronized(loopbackLock) {
            if (port in loopbackPorts) return true
            if (port in loopbackDirectPorts) return false
            val containerOpen = isContainerPortOpen(port)
            Log.d("PluginLoopback", "ensure port=$port containerOpen=$containerOpen (probe advisory only)")
            val imageId = codeSettings.settings.value.imageId
            val status = runCatching { runtime.status(imageId, includeSize = false) }.getOrNull()
                ?: return false
            if (!status.rootfsInstalled) return false
            val socketFile = File(workspace, "loopback_$port.sock")
            if (!socketFile.exists()) {
                loopbackRelays.remove(port)?.stop()
                runCatching { socketFile.delete() }
                val script = """
                    const net=require('net'),fs=require('fs');
                    const p=${socketFile.absolutePath.replace("'", "\\'")};
                    try{fs.unlinkSync(p)}catch(e){}
                    const server=net.createServer(c=>{
                      const t=net.connect($port,'127.0.0.1');
                      c.pipe(t); t.pipe(c);
                      c.on('error',()=>t.destroy()); t.on('error',()=>c.destroy());
                    });
                    server.listen(p);
                """.trimIndent()
                val guestSocket = "/workspace/${socketFile.name}"
                val relayScript = script.replace(socketFile.absolutePath, guestSocket)
                val relay = runCatching {
                    runtime.openSession(imageId, workspace, "node -e ${shellQuote(relayScript)}")
                }.getOrNull() ?: return false
                loopbackRelays[port] = relay
                val deadline = System.currentTimeMillis() + 3_000
                while (System.currentTimeMillis() < deadline && !socketFile.exists()) {
                    Thread.sleep(50)
                }
                if (!socketFile.exists()) {
                    relay.stop()
                    loopbackRelays.remove(port)
                    Log.e("PluginLoopback", "relay socket missing: ${socketFile.absolutePath}")
                    return false
                }
            }
            Log.d("PluginLoopback", "relay socket ready: ${socketFile.absolutePath}")
            val server = runCatching {
                ServerSocket(port, 50, InetAddress.getByName("127.0.0.1"))
            }.getOrNull() ?: run {
                loopbackDirectPorts += port
                Log.w("PluginLoopback", "host bind failed on $port; direct should work")
                return false
            }
            loopbackPorts += port
            Log.d("PluginLoopback", "host proxy listening on 127.0.0.1:$port")
            thread(isDaemon = true, name = "PluginLoopback-$port") {
                while (true) {
                    val client = runCatching { server.accept() }.getOrNull() ?: break
                    thread(isDaemon = true, name = "PluginLoopbackConn-$port") {
                        bridgeLoopback(client, socketFile)
                    }
                }
            }
            return true
        }
    }

    private fun isContainerPortOpen(port: Int): Boolean {
        val imageId = codeSettings.settings.value.imageId
        val script = """
            const http=require('http');
            const req=http.get({host:'127.0.0.1',port:$port,path:'/health',timeout:1200},res=>{console.log('OPEN');res.resume();process.exit(0)});
            req.on('error',()=>{console.log('CLOSED');process.exit(0)});
            req.on('timeout',()=>{req.destroy();console.log('CLOSED');process.exit(0)});
        """.trimIndent()
        val result = runCatching {
            runtime.execute(
                imageId = imageId,
                workspaceDir = workspace,
                command = "node -e ${shellQuote(script)}",
                timeoutSeconds = 4,
                outputLimit = 4096,
            )
        }.getOrNull() ?: return false
        return result.stdout.contains("OPEN")
    }

    private fun bridgeLoopback(client: java.net.Socket, socketFile: File) {
        val local = LocalSocket()
        try {
            local.connect(LocalSocketAddress(socketFile.absolutePath, LocalSocketAddress.Namespace.FILESYSTEM))
            val up = thread(isDaemon = true) {
                runCatching { client.inputStream.copyTo(local.outputStream) }
                runCatching { local.shutdownOutput() }
            }
            val down = thread(isDaemon = true) {
                runCatching { local.inputStream.copyTo(client.outputStream) }
                runCatching { client.shutdownOutput() }
            }
            up.join(120_000)
            down.join(120_000)
        } catch (_: Exception) {
            // 客户端断开或容器服务尚未启动，下一次连接重试。
        } finally {
            runCatching { local.close() }
            runCatching { client.close() }
        }
    }

    fun download(url: String, guestPath: String): String {
        val imageId = codeSettings.settings.value.imageId
        val rootfs = ProotPaths(File(appContext.filesDir, "proot")).rootfsDir(imageId)
        if (!ProotPaths.isUsableRootfs(rootfs)) return fail("PRoot 镜像未安装（$imageId）").toString()
        if (!RemoteFetch.isPublicHttpUrl(url)) return fail("仅允许公网 HTTP/HTTPS：$url").toString()
        val target = guestFile(rootfs, guestPath) ?: return fail("下载路径不合法：$guestPath").toString()
        target.parentFile?.mkdirs()
        val temp = File(target.parentFile, "${target.name}.part")
        return try {
            val connection = (java.net.URL(url).openConnection() as java.net.HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 30_000
                readTimeout = 120_000
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "IRIS-LocalAI/0.1")
            }
            connection.connect()
            val code = connection.responseCode
            if (code !in 200..299) {
                connection.disconnect()
                return fail("下载失败：HTTP $code").toString()
            }
            if (!RemoteFetch.isPublicHttpUrl(connection.url.toString())) {
                connection.disconnect()
                return fail("下载重定向到非公网地址").toString()
            }
            var total = 0L
            connection.inputStream.buffered().use { input ->
                temp.outputStream().buffered().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        total += read
                        if (total > 512L * 1024 * 1024) error("下载文件超过 512 MB 上限")
                        output.write(buffer, 0, read)
                    }
                }
            }
            connection.disconnect()
            if (total == 0L) {
                temp.delete()
                return fail("下载内容为空").toString()
            }
            if (target.exists()) target.delete()
            if (!temp.renameTo(target)) {
                temp.copyTo(target, overwrite = true)
                temp.delete()
            }
            ok("downloaded $total bytes").toString()
        } catch (error: Exception) {
            temp.delete()
            fail("下载失败：${error.message ?: error.javaClass.simpleName}").toString()
        }
    }

    fun unzip(archivePath: String, destinationPath: String): String {
        val imageId = codeSettings.settings.value.imageId
        val rootfs = ProotPaths(File(appContext.filesDir, "proot")).rootfsDir(imageId)
        if (!ProotPaths.isUsableRootfs(rootfs)) return fail("PRoot 镜像未安装（$imageId）").toString()
        val archive = guestFile(rootfs, archivePath) ?: return fail("归档路径不合法：$archivePath").toString()
        if (!archive.isFile || archive.length() == 0L) {
            return fail("归档不存在或为空：$archivePath（下载可能失败）").toString()
        }
        val destination = guestFile(rootfs, destinationPath) ?: return fail("解压目录路径不合法：$destinationPath").toString()
        val destinationRoot = runCatching { destination.canonicalFile }.getOrElse { destination }
        if (!destinationRoot.isDirectory && !destinationRoot.mkdirs()) {
            return fail("无法创建解压目录：$destinationPath").toString()
        }
        var count = 0
        var total = 0L
        val entryLimit = 20_000
        val sizeLimit = 512L * 1024 * 1024
        return try {
            ZipInputStream(archive.inputStream().buffered()).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    count++
                    if (count > entryLimit) error("归档条目过多，已停止解压")
                    val target = File(destinationRoot, entry.name).canonicalFile
                    val rootPath = destinationRoot.path + File.separator
                    if (target.path != destinationRoot.path && !target.path.startsWith(rootPath)) {
                        error("归档包含越界路径：${entry.name}")
                    }
                    if (entry.isDirectory) {
                        target.mkdirs()
                    } else {
                        target.parentFile?.mkdirs()
                        target.outputStream().buffered().use { output ->
                            val buffer = ByteArray(32 * 1024)
                            while (true) {
                                val read = zip.read(buffer)
                                if (read < 0) break
                                total += read
                                if (total > sizeLimit) error("归档解压后超过 512 MB 上限")
                                output.write(buffer, 0, read)
                            }
                        }
                    }
                    zip.closeEntry()
                }
            }
            ok("extracted $count entries").toString()
        } catch (error: Exception) {
            fail("解压失败：${error.message ?: error.javaClass.simpleName}").toString()
        }
    }

    private fun guestFile(rootfs: File, guestPath: String): File? {
        if (!guestPath.startsWith("/")) return null
        val relative = guestPath.trimStart('/')
        if (relative.split('/').any { it == ".." || it.isEmpty() }) return null
        return File(rootfs, relative)
    }

    private fun runCommand(session: Session, command: String, timeoutMs: Int): JsonObject {
        val trimmed = command.trim()
        if (trimmed.isEmpty()) return ok("")
        parseCwd(trimmed)?.let { path ->
            session.cwd = path
            return ok("")
        }
        parseExports(trimmed)?.let { values ->
            session.env.putAll(values)
            return ok("")
        }
        if (session.background != null && trimmed.contains("echo \$!")) {
            return ok("1")
        }
        val prepared = injectLoopbackPreload(trimmed)

        val imageId = codeSettings.settings.value.imageId
        val status = runCatching { runtime.status(imageId, includeSize = false) }
            .getOrElse { return fail("PRoot 状态检查失败：${it.message ?: it.javaClass.simpleName}") }
        if (!status.rootfsInstalled) {
            return fail("PRoot 镜像未安装（$imageId）：请到「编程管理 › 容器与镜像」安装，或在插件设置中切换可用镜像")
        }

        val script = unwrapBashCommand(prepared) ?: buildScript(session, prepared)
        tryHostDownload(script)?.let { return it }
        val isBackground = Regex("&\\s*$").containsMatchIn(prepared)
        if (isBackground) {
            val foreground = prepared.replace(Regex("&\\s*$"), "").trim()
            val sessionProcess = runCatching {
                runtime.openSession(
                    imageId = imageId,
                    workspaceDir = workspace,
                    command = buildScript(session, foreground),
                )
            }.getOrElse { return fail("后台命令启动失败：${it.message ?: it.javaClass.simpleName}") }
            session.background?.stop()
            session.background = sessionProcess
            return ok("started")
        }

        val result = runCatching {
            runtime.execute(
                imageId = imageId,
                workspaceDir = workspace,
                command = script,
                timeoutSeconds = (timeoutMs.coerceAtLeast(1000) / 1000).coerceIn(1, 300),
                outputLimit = 200_000,
            )
        }.getOrElse { return fail("命令执行失败：${it.message ?: it.javaClass.simpleName}") }
        val output = buildString {
            append(result.stdout)
            if (result.stderr.isNotBlank()) {
                if (isNotEmpty() && last() != '\n') append('\n')
                append(result.stderr)
            }
        }.trim()
        return buildJsonObject {
            put("exitCode", result.exitCode)
            put("output", output)
            if (result.timedOut) put("timeout", true)
        }
    }

    private fun tryHostDownload(command: String): JsonObject? {
        if (!command.contains("aria2c") && !command.contains("download_file")) return null
        val url = Regex("https?://[^\\s'\"\\]]+").findAll(command)
            .map { it.value.trimEnd(';', ',', ')') }
            .firstOrNull { it.contains("codeload.github.com") || it.contains("SillyTavern") }
            ?: return null
        val guestPath = Regex("['\"](/[^'\"]+\\.zip)['\"]").findAll(command)
            .map { it.groupValues[1] }
            .lastOrNull()
            ?: return null
        return runCatching { download(url, guestPath) }.getOrElse { fail(it.message ?: "下载失败") }
            .let { Json.parseToJsonElement(it.toString()).jsonObject }
    }

    private fun unwrapBashCommand(command: String): String? {
        val prefix = "bash -lc "
        if (!command.startsWith(prefix)) return null
        val raw = command.substring(prefix.length).trim()
        if (raw.length < 2 || raw.first() != '\'' || raw.last() != '\'') return null
        return raw.substring(1, raw.length - 1).replace("'\\''", "'")
    }

    private fun buildScript(session: Session, command: String): String = buildString {
        append("cd ")
        append(shellQuote(session.cwd))
        append(" || exit 1; ")
        session.env.forEach { (key, value) ->
            if (key.matches(Regex("[A-Za-z_][A-Za-z0-9_]*"))) {
                append("export ")
                append(key)
                append('=')
                append(shellQuote(value))
                append("; ")
            }
        }
        append(command)
    }

    private fun parseCwd(command: String): String? {
        val match = Regex("^cd\\s+(.+)$").find(command) ?: return null
        val raw = match.groupValues[1].trim()
        if (raw.contains("&&") || raw.contains(";")) return null
        return unquote(raw)
    }

    private fun parseExports(command: String): Map<String, String>? {
        if (!command.startsWith("export ")) return null
        val parts = command.split("&&").map { it.trim() }.filter { it.isNotEmpty() }
        if (parts.any { !it.startsWith("export ") }) return null
        val values = LinkedHashMap<String, String>()
        parts.forEach { part ->
            Regex("([A-Za-z_][A-Za-z0-9_]*)=(\"[^\"]*\"|'[^']*'|\\S+)").findAll(part).forEach { match ->
                values[match.groupValues[1]] = unquote(match.groupValues[2])
            }
        }
        return values.takeIf { it.isNotEmpty() }
    }

    private fun unquote(value: String): String {
        val trimmed = value.trim()
        if (trimmed.length >= 2 && (trimmed.first() == '\'' || trimmed.first() == '"') &&
            trimmed.last() == trimmed.first()
        ) {
            return trimmed.substring(1, trimmed.length - 1)
        }
        return trimmed
    }

    private fun shellQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

    private fun ok(output: String): JsonObject = buildJsonObject {
        put("exitCode", 0)
        put("output", output)
    }

    private fun fail(message: String): JsonObject = buildJsonObject {
        put("exitCode", 1)
        put("output", message)
        put("error", message)
    }

    private data class Session(
        val id: String,
        val name: String,
        var cwd: String = "/root",
        val env: MutableMap<String, String> = LinkedHashMap(),
        var background: ProotSession? = null,
    )
}
