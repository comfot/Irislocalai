package com.iris.moew.code

import android.content.Context
import com.iris.moew.proot.ProotRuntime
import com.iris.moew.workspace.WorkspaceStore
import java.io.File
import java.io.InputStream
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * 常驻终端会话池。与一次性 [ShellTool] 不同，会话里的进程由本单例持有，
 * 不会在工具调用返回时被 `--kill-on-exit` 清理，适合构建、转写等长时间任务。
 */
class TerminalSessions(
    private val context: Context,
    private val code: CodeSettingsStore,
    private val workspaces: WorkspaceStore,
) {

    private class Session(
        val id: String,
        val title: String,
        val command: String,
        val process: Process,
    ) {
        private val output = StringBuilder()

        val isAlive: Boolean get() = process.isAlive

        init {
            drain(process.inputStream)
            drain(process.errorStream)
        }

        private fun drain(stream: InputStream) {
            thread(isDaemon = true) {
                runCatching {
                    stream.bufferedReader().use { reader ->
                        while (true) {
                            val line = reader.readLine() ?: break
                            synchronized(output) {
                                if (output.length > MAX_OUTPUT_CHARS / 2) {
                                    output.delete(0, output.length - MAX_OUTPUT_CHARS / 2)
                                }
                                output.appendLine(line)
                            }
                        }
                    }
                }
            }
        }

        fun log(): String = synchronized(output) { output.toString() }

        fun exitCode(): Int? = runCatching { process.exitValue() }.getOrNull()

        fun stop() {
            runCatching { process.destroy() }
            runCatching {
                if (!process.waitFor(2, TimeUnit.SECONDS)) process.destroyForcibly()
            }
        }
    }

    private val sessions = LinkedHashMap<String, Session>()
    private var counter = 0

    fun start(command: String, title: String?): String {
        val settings = code.current
        if (!settings.enabled) return "命令执行未启用：请到「编程管理」开启"
        when (settings.shellAuthorization) {
            ToolAuthorization.DENY -> return "Shell 工具授权为拒绝"
            ToolAuthorization.ASK -> return "Shell 工具需要授权：请在「编程管理 › 工具授权」设为允许"
            ToolAuthorization.ALLOW -> Unit
        }
        val denied = splitPrefixes(settings.deniedPrefixes)
        if (denied.any { command.startsWith(it, ignoreCase = true) }) {
            return "命令被拒绝：命中禁止前缀"
        }
        val allowed = splitPrefixes(settings.allowedPrefixes)
        if (allowed.isNotEmpty() && allowed.none { command.startsWith(it, ignoreCase = true) }) {
            return "命令被拒绝：不在允许前缀内"
        }

        val workspace = resolveWorkspace() ?: return "当前工作区是 SAF 文件夹，终端会话无法直接进入"
        workspace.mkdirs()
        val process = try {
            when (settings.runtime) {
                CodeRuntime.PROOT -> {
                    val runtime = ProotRuntime(context)
                    val status = runtime.status(settings.imageId, includeSize = false)
                    if (!status.rootfsInstalled) {
                        return "PRoot 镜像未安装（${settings.imageId}）：请到「编程管理 › 容器与镜像」安装"
                    }
                    if (!status.prootAvailable) {
                        return "PRoot 启动器缺失：当前安装包未包含 libproot_exec.so"
                    }
                    runtime.openProcess(settings.imageId, workspace, command)
                }

                CodeRuntime.APP_SHELL -> ProcessBuilder("/system/bin/sh", "-c", command)
                    .directory(workspace)
                    .redirectErrorStream(false)
                    .start()

                CodeRuntime.SSH -> return "SSH 运行时尚未接入：请切换到应用 Shell 或 PRoot 容器"
            }
        } catch (e: Exception) {
            return "启动终端会话失败：${e.message ?: e.javaClass.simpleName}"
        }

        val session = synchronized(this) {
            val id = "term-" + (++counter)
            val started = Session(
                id = id,
                title = title?.trim()?.takeIf { it.isNotEmpty() } ?: command.lineSequence().first().take(40),
                command = command,
                process = process,
            )
            sessions[id] = started
            while (sessions.size > MAX_SESSIONS) {
                val oldest = sessions.entries.first()
                sessions.remove(oldest.key)
                oldest.value.stop()
            }
            started
        }

        Thread.sleep(START_CAPTURE_MS)
        val state = if (session.isAlive) {
            "运行中"
        } else {
            "已结束（exit=${session.exitCode() ?: "?"}）"
        }
        val initial = session.log().takeLast(2000).trim()
        return buildString {
            append("已在常驻终端启动 ${session.id}：$state")
            if (initial.isNotEmpty()) {
                appendLine()
                append("初始输出：")
                appendLine()
                append(initial)
            }
            appendLine()
            append("用 terminal action=read session_id=${session.id} 查看后续输出，action=stop 结束。")
        }
    }

    fun read(sessionId: String?): String {
        if (sessionId.isNullOrBlank()) {
            val snapshot = synchronized(this) { sessions.values.toList() }
            if (snapshot.isEmpty()) return "当前没有终端会话。"
            return snapshot.joinToString("\n") { session ->
                "${session.id} · ${if (session.isAlive) "运行中" else "已结束"} · ${session.title}"
            }
        }
        val session = synchronized(this) { sessions[sessionId] } ?: return "会话不存在：$sessionId"
        val state = if (session.isAlive) "运行中" else "已结束（exit=${session.exitCode() ?: "?"}）"
        val log = session.log().takeLast(MAX_READ_CHARS).trim()
        return "${session.id} · $state · ${session.title}\n" +
            if (log.isEmpty()) "（暂无输出）" else log
    }

    fun stop(sessionId: String): String {
        val session = synchronized(this) { sessions.remove(sessionId) } ?: return "会话不存在：$sessionId"
        session.stop()
        return "已停止 ${session.id}。"
    }

    fun stopAll() {
        val all = synchronized(this) {
            val values = sessions.values.toList()
            sessions.clear()
            values
        }
        all.forEach { it.stop() }
    }

    private fun resolveWorkspace(): File? {
        val active = workspaces.active() ?: return File(context.filesDir, "workspace")
        if (!active.appFolder) return null
        return if (active.path.isNotBlank()) File(active.path) else File(context.filesDir, "workspace")
    }

    private fun splitPrefixes(raw: String): List<String> =
        raw.split('\n', ',').map { it.trim() }.filter { it.isNotEmpty() }

    private companion object {
        const val MAX_SESSIONS = 6
        const val MAX_OUTPUT_CHARS = 40_000
        const val MAX_READ_CHARS = 8_000
        const val START_CAPTURE_MS = 600L
    }
}
