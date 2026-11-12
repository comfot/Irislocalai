package com.iris.localai.code

import android.content.Context
import com.iris.localai.agent.Tool
import com.iris.localai.proot.ProotRuntime
import com.iris.localai.workspace.WorkspaceStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * 工作区命令工具：应用 Shell 直接执行，PRoot 运行时通过随包的 proot 启动器进入 rootfs。
 * 仅在工作/综合空间、启用编程管理且授权为允许时可用；PRoot 只支持应用内工作区。
 */
class ShellTool(
    private val store: CodeSettingsStore,
    private val workspaces: WorkspaceStore,
    private val context: Context,
) : Tool {

    override val name: String = "shell"

    override val description: String =
        "在当前工作区执行 shell 命令（随当前运行时使用应用 Shell 或 PRoot 容器），" +
            "参数：{\"command\":\"ls -la\"}"

    override suspend fun execute(arguments: JsonObject): String {
        val settings = store.current
        if (!settings.enabled) return "命令执行未启用：请到「编程管理」开启"
        when (settings.shellAuthorization) {
            ToolAuthorization.DENY -> return "Shell 工具授权为拒绝"
            ToolAuthorization.ASK -> return "Shell 工具需要授权：请在「编程管理 › 工具授权」设为允许"
            ToolAuthorization.ALLOW -> Unit
        }

        val command = arguments["command"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        if (command.isEmpty()) return "缺少 command 参数"

        val denied = splitPrefixes(settings.deniedPrefixes)
        if (denied.any { command.startsWith(it, ignoreCase = true) }) {
            return "命令被拒绝：命中禁止前缀"
        }
        val allowed = splitPrefixes(settings.allowedPrefixes)
        if (allowed.isNotEmpty() && allowed.none { command.startsWith(it, ignoreCase = true) }) {
            return "命令被拒绝：不在允许前缀内"
        }

        return when (settings.runtime) {
            CodeRuntime.APP_SHELL -> executeAppShell(settings, command)
            CodeRuntime.PROOT -> executeProot(settings, command)
            CodeRuntime.SSH -> "SSH 运行时尚未接入：请在「编程管理」切换到应用 Shell 或 PRoot 容器"
        }
    }

    private suspend fun executeAppShell(settings: CodeSettings, command: String): String {
        val active = workspaces.active()
        val workspace = when {
            active == null -> File(context.filesDir, "workspace")
            !active.appFolder -> return "当前工作区是 SAF 文件夹，应用 Shell 无法直接进入，请切换到应用内工作区"
            active.path.isNotBlank() -> File(active.path)
            else -> File(context.filesDir, "workspace")
        }
        workspace.mkdirs()

        return try {
            runInterruptible(Dispatchers.IO) { runAppShell(settings, workspace, command) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            "执行失败：${e.message ?: e.javaClass.simpleName}"
        }
    }

    private fun runAppShell(settings: CodeSettings, workspace: File, command: String): String {
        val timeout = settings.timeoutSeconds.coerceIn(1, 300)
        val limit = settings.outputLimit.coerceIn(1024, 200_000)
        val process = ProcessBuilder("/system/bin/sh", "-c", command)
            .directory(workspace)
            .redirectErrorStream(true)
            .start()
        val output = StringBuilder()
        val reader = thread(start = true, isDaemon = true) {
            runCatching {
                process.inputStream.bufferedReader().use { stream ->
                    val buffer = CharArray(4096)
                    while (true) {
                        val count = stream.read(buffer)
                        if (count < 0) break
                        if (output.length < limit) {
                            output.append(buffer, 0, minOf(count, limit - output.length))
                        }
                    }
                }
            }
        }

        try {
            if (!process.waitFor(timeout.toLong(), TimeUnit.SECONDS)) {
                killProcessTree(process)
                reader.join(1000)
                return "执行超时（${timeout}s），已终止。目录很大时可缩小检索范围，或在「命令执行通道」提高超时。\n" +
                    output.toString()
            }
            reader.join(2000)
            val text = output.toString()
            val suffix = if (output.length >= limit) "\n…输出已截断" else ""
            return "exit=${process.exitValue()}\n$text$suffix"
        } finally {
            if (process.isAlive) killProcessTree(process)
        }
    }

    private fun killProcessTree(process: Process) {
        process.destroyForcibly()
    }

    private suspend fun executeProot(settings: CodeSettings, command: String): String {
        val active = workspaces.active()
        if (active != null && !active.appFolder) {
            return "PRoot 只能使用应用内工作区：当前为 SAF 文件夹，请切换到应用内工作区，或改用应用 Shell"
        }
        val workspace = File(
            active?.path?.takeIf { it.isNotBlank() }
                ?: File(context.filesDir, "workspace").path,
        )
        if (!workspace.isDirectory && !workspace.mkdirs()) {
            return "无法创建工作区目录：${workspace.absolutePath}"
        }

        val runtime = ProotRuntime(context)
        val status = runCatching { runtime.status(settings.imageId, includeSize = false) }
            .getOrElse { return "PRoot 状态检查失败：${it.message ?: it.javaClass.simpleName}" }
        if (!status.rootfsInstalled) {
            return "PRoot 镜像未安装（${settings.imageId}）：请到「编程管理 › 容器与镜像」点击安装"
        }
        if (!status.prootAvailable) {
            return "PRoot 启动器缺失（${status.arch}）：当前安装包未包含 libproot_exec.so"
        }

        val result = try {
            runInterruptible(Dispatchers.IO) {
                runtime.execute(
                    imageId = settings.imageId,
                    workspaceDir = workspace,
                    command = command,
                    timeoutSeconds = settings.timeoutSeconds,
                    outputLimit = settings.outputLimit,
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return "PRoot 执行失败：${e.message ?: e.javaClass.simpleName}"
        }

        return buildString {
            append("exit=${result.exitCode}")
            if (result.stdout.isNotBlank()) {
                append('\n')
                append(result.stdout.trimEnd())
            }
            if (result.stderr.isNotBlank()) {
                append("\n[stderr]\n")
                append(result.stderr.trimEnd())
            }
            if (result.timedOut) {
                append("\n执行超时（${settings.timeoutSeconds}s），已终止。目录很大时可缩小检索范围，")
                append("或在「命令执行通道」提高超时。")
            }
            if (result.truncated) append("\n…输出已截断")
        }
    }

    private fun splitPrefixes(raw: String): List<String> =
        raw.split('\n', ',').map { it.trim() }.filter { it.isNotEmpty() }
}
