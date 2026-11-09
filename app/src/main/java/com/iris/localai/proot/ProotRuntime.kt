package com.iris.localai.proot

import android.content.Context
import android.os.Build
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.TimeUnit

data class ProotStatus(
    val rootfsInstalled: Boolean,
    val prootAvailable: Boolean,
    val rootfsShell: String,
    val rootfsBytes: Long,
    val rootfsPath: String,
    val arch: String,
)

data class ProotResult(
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
    val timedOut: Boolean,
    val truncated: Boolean,
)

/**
 * 用随包的 `libproot_exec.so` 启动 PRoot，把当前应用内工作区挂载到 rootfs 的 /workspace。
 * 不依赖 root、Termux 或系统 proot。
 */
class ProotRuntime(
    private val context: Context,
    private val paths: ProotPaths = ProotPaths(File(context.filesDir, "proot")),
    private val patcher: RootfsPatcher = RootfsPatcher(),
) {

    private val nativeDir = File(context.applicationInfo.nativeLibraryDir)

    fun status(imageId: String, includeSize: Boolean = true): ProotStatus {
        val rootfs = paths.rootfsDir(imageId)
        val abi = Build.SUPPORTED_ABIS.firstOrNull().orEmpty()
        return ProotStatus(
            rootfsInstalled = ProotPaths.isUsableRootfs(rootfs),
            prootAvailable = File(nativeDir, PROOT_EXEC).isFile &&
                File(nativeDir, PROOT_LOADER).isFile,
            rootfsShell = ProotPaths.rootfsShell(rootfs),
            rootfsBytes = if (includeSize) ProotPaths.dirSizeBytes(rootfs) else 0L,
            rootfsPath = rootfs.absolutePath,
            arch = when (abi) {
                "arm64-v8a" -> "aarch64"
                "x86_64" -> "x86_64"
                else -> abi.ifBlank { "unknown" }
            },
        )
    }

    fun execute(
        imageId: String,
        workspaceDir: File,
        command: String,
        timeoutSeconds: Int,
        outputLimit: Int,
    ): ProotResult {
        val process = launch(imageId, workspaceDir, command)

        return process.readResult(
            timeoutMillis = timeoutSeconds.coerceIn(1, 300) * 1000L,
            maxChars = outputLimit.coerceIn(1024, 200_000),
        )
    }

    /** 启动常驻会话（如容器内 HTTP 服务）；调用方负责在不用时 [ProotSession.stop]。 */
    fun openSession(imageId: String, workspaceDir: File, command: String): ProotSession {
        val process = launch(imageId, workspaceDir, command)
        val session = ProotSession(process)
        session.drain(process.inputStream)
        session.drain(process.errorStream)
        return session
    }

    private fun launch(imageId: String, workspaceDir: File, command: String): Process {
        val rootfs = paths.rootfsDir(imageId)
        require(ProotPaths.isUsableRootfs(rootfs)) {
            "PRoot 镜像未安装：请先在「编程管理 › 容器与镜像」安装 rootfs"
        }
        val proot = File(nativeDir, PROOT_EXEC)
        val loader = File(nativeDir, PROOT_LOADER)
        require(proot.isFile) { "PRoot 启动器缺失：${proot.absolutePath}" }
        require(loader.isFile) { "PRoot loader 缺失：${loader.absolutePath}" }
        require(workspaceDir.isDirectory) { "工作区目录不存在：${workspaceDir.absolutePath}" }

        // PRoot 的 bind 目标需要在 guest 内存在
        File(rootfs, ProotPaths.ROOTFS_WORKSPACE_DIR).mkdirs()
        val tempDir = paths.tempDir(imageId).apply { mkdirs() }
        patcher.patch(rootfs)

        return ProcessBuilder(buildCommand(imageId, command, proot, rootfs, workspaceDir))
            .directory(context.filesDir)
            .redirectErrorStream(false)
            .apply {
                environment()["PROOT_LOADER"] = loader.absolutePath
                environment()["PROOT_TMP_DIR"] = tempDir.absolutePath
                environment()["TMPDIR"] = tempDir.absolutePath
                // Android 10+ 的 seccomp 会拦掉部分 proot 加速路径，兼容模式更稳。
                environment()["PROOT_NO_SECCOMP"] = "1"
            }
            .start()
    }

    private fun buildCommand(
        imageId: String,
        command: String,
        proot: File,
        rootfs: File,
        workspaceDir: File,
    ): List<String> = buildList {
        add(proot.absolutePath)
        add("--root-id")
        add("--link2symlink")
        add("--kill-on-exit")
        add("-r")
        add(rootfs.absolutePath)
        add("-w")
        add(ProotPaths.ROOTFS_WORKSPACE_DIR)
        add("-b")
        add("${workspaceDir.absolutePath}:${ProotPaths.ROOTFS_WORKSPACE_DIR}")
        listOf("/dev", "/proc", "/sys").forEach { path ->
            if (File(path).exists()) {
                add("-b")
                add(path)
            }
        }
        add("/usr/bin/env")
        add("-i")
        add("HOME=/root")
        add("PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin")
        add("TERM=xterm-256color")
        add("LANG=C.UTF-8")
        add("LC_ALL=C.UTF-8")
        add("CI=true")
        add("DEBIAN_FRONTEND=noninteractive")
        add("NO_COLOR=1")
        add("PAGER=cat")
        add(ProotPaths.rootfsShell(rootfs))
        add("-l")
        add("-c")
        // 命令通过位置参数传入，避免任何转义；eval 只对命令文本求值一次。
        add("cd -- \"\$1\" && eval \"\$2\"")
        add("iris")
        add(ProotPaths.ROOTFS_WORKSPACE_DIR)
        add(command)
    }

    private fun Process.readResult(timeoutMillis: Long, maxChars: Int): ProotResult {
        val stdout = StreamCollector(inputStream, maxChars)
        val stderr = StreamCollector(errorStream, maxChars)
        try {
            outputStream.close()
        } catch (_: IOException) {
            // 子进程已退出时管道可能已关闭
        }
        var finished = false
        try {
            finished = waitFor(timeoutMillis, TimeUnit.MILLISECONDS)
            if (!finished) destroyForcibly()
        } catch (e: InterruptedException) {
            destroyForcibly()
            throw e
        } finally {
            stdout.join(1_000)
            stderr.join(1_000)
        }
        return ProotResult(
            exitCode = if (finished) runCatching { exitValue() }.getOrDefault(-1) else -1,
            stdout = stdout.text(),
            stderr = stderr.text(),
            timedOut = !finished,
            truncated = stdout.truncated || stderr.truncated,
        )
    }

    private class StreamCollector(
        stream: InputStream,
        private val maxChars: Int,
    ) {
        private val builder = StringBuilder()

        @Volatile
        var truncated = false
            private set

        private val thread = Thread {
            try {
                stream.bufferedReader().use { reader ->
                    val buffer = CharArray(4096)
                    while (true) {
                        val read = reader.read(buffer)
                        if (read < 0) break
                        synchronized(builder) {
                            val remaining = maxChars - builder.length
                            if (remaining > 0) {
                                builder.append(buffer, 0, minOf(read, remaining))
                            }
                            if (read > remaining) truncated = true
                        }
                    }
                }
            } catch (_: IOException) {
                // 进程被超时/取消强杀时流会关闭，保留已读内容即可
            }
        }.apply {
            isDaemon = true
            start()
        }

        fun join(millis: Long) = thread.join(millis)

        fun text(): String = synchronized(builder) { builder.toString() }
    }

    private companion object {
        const val PROOT_EXEC = "libproot_exec.so"
        const val PROOT_LOADER = "libproot_loader.so"
    }
}
