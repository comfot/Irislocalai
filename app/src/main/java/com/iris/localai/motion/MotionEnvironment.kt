package com.iris.localai.motion

import android.content.Context
import com.iris.localai.proot.ProotRuntime
import com.iris.localai.proot.ProotSession
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * 内置动作服务：把零依赖 Node 服务复制到应用内工作区，通过 PRoot 常驻会话运行在 127.0.0.1:8780。
 * 不能用 execute() 启动：它带 --kill-on-exit，命令结束后容器内进程会被清理。
 */
class MotionEnvironment(context: Context) {

    private val appContext = context.applicationContext
    private val runtime = ProotRuntime(appContext)
    private val workspace = File(appContext.filesDir, "motion_runtime").apply { mkdirs() }

    fun rootfsReady(imageId: String): Boolean = runtime.status(imageId, includeSize = false).rootfsInstalled

    suspend fun status(imageId: String): String = withContext(Dispatchers.IO) {
        val info = runtime.status(imageId, includeSize = false)
        when {
            !info.prootAvailable -> "PRoot 启动器缺失，当前安装包不支持 ${info.arch}"
            !info.rootfsInstalled -> "PRoot 镜像未安装，请先在「编程管理 › 容器与镜像」安装"
            else -> {
                val running = health()
                val nodeReady = session?.isAlive == true || runCatching {
                    runtime.execute(imageId, workspace, "sh -c 'command -v node >/dev/null && node -v'", 20, 4000)
                }.getOrNull()?.let { it.exitCode == 0 && it.stdout.contains("v") } == true
                when {
                    running -> "服务运行中 · 127.0.0.1:8780"
                    nodeReady -> "Node 已安装，服务未启动"
                    else -> "Node 未安装"
                }
            }
        }
    }

    suspend fun installNode(imageId: String): String = withContext(Dispatchers.IO) {
        val command = "sh -c 'command -v node >/dev/null 2>&1 && { node -v; exit 0; }; " +
            "if command -v apk >/dev/null 2>&1; then apk add --no-cache nodejs; " +
            "elif command -v apt-get >/dev/null 2>&1; then apt-get update && " +
            "DEBIAN_FRONTEND=noninteractive apt-get install -y nodejs; " +
            "else echo NO_PACKAGER; fi'"
        val result = runCatching {
            runtime.execute(imageId, workspace, command, 300, 20_000)
        }.getOrElse { return@withContext "安装失败：${it.message}" }
        when {
            result.exitCode == 0 -> "Node 安装完成：${result.stdout.trim().lines().lastOrNull() ?: "ok"}"
            result.stdout.contains("NO_PACKAGER") -> "镜像里没有 apk/apt，无法自动安装 Node"
            else -> "安装失败（exit ${result.exitCode}）：${(result.stderr.ifBlank { result.stdout }).trim().take(200)}"
        }
    }

    suspend fun start(imageId: String): String = withContext(Dispatchers.IO) {
        if (health()) return@withContext "服务已在运行 · 127.0.0.1:8780"
        session?.takeIf { it.isAlive }?.stop()
        session = null
        copyServer()
        val result = runCatching {
            runtime.openSession(imageId, workspace, "cd motion && exec node server.js")
        }.getOrElse { return@withContext "启动失败：${it.message}" }
        session = result
        repeat(20) {
            if (health()) return@withContext "动作服务已启动 · 127.0.0.1:8780"
            if (!result.isAlive) {
                val log = result.log().trim().lines().lastOrNull() ?: "进程已退出"
                result.stop()
                session = null
                return@withContext "启动失败：$log"
            }
            delay(500)
        }
        val log = result.log().trim().lines().takeLast(3).joinToString(" / ")
        result.stop()
        session = null
        "健康检查超时：${log.ifBlank { "无输出" }}"
    }

    suspend fun stop(imageId: String): String = withContext(Dispatchers.IO) {
        val current = session
        session = null
        if (current == null || !current.isAlive) return@withContext "服务未在运行"
        current.stop()
        "动作服务已停止"
    }

    private fun copyServer() {
        val dir = File(workspace, "motion").apply { mkdirs() }
        appContext.assets.open("motion/server.js").use { input ->
            File(dir, "server.js").outputStream().use { output -> input.copyTo(output) }
        }
    }

    private fun health(): Boolean = runCatching {
        val connection = (URL("http://127.0.0.1:8780/health").openConnection() as HttpURLConnection).apply {
            connectTimeout = 1500
            readTimeout = 1500
            requestMethod = "GET"
        }
        try {
            connection.responseCode == 200
        } finally {
            connection.disconnect()
        }
    }.getOrDefault(false)

    private companion object {
        @Volatile
        var session: ProotSession? = null
    }
}
