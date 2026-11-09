package com.iris.localai.proot

import java.io.InputStream

/**
 * 常驻 PRoot 会话：根进程不退，容器内的服务才能持续运行。
 * `ProotRuntime.execute` 带 --kill-on-exit，命令结束后子进程会被清理，因此服务需要用它。
 */
class ProotSession internal constructor(
    private val process: Process,
) {

    private val output = StringBuilder()

    val isAlive: Boolean get() = process.isAlive

    fun log(): String = synchronized(output) { output.toString() }

    fun stop() {
        runCatching { process.destroy() }
        runCatching {
            if (!process.waitFor(2, java.util.concurrent.TimeUnit.SECONDS)) process.destroyForcibly()
        }
    }

    internal fun drain(input: InputStream) {
        val thread = Thread {
            runCatching {
                input.bufferedReader().use { reader ->
                    while (true) {
                        val line = reader.readLine() ?: break
                        synchronized(output) {
                            if (output.length < MAX_LOG_CHARS) output.appendLine(line)
                        }
                    }
                }
            }
        }
        thread.isDaemon = true
        thread.start()
    }

    private companion object {
        const val MAX_LOG_CHARS = 20_000
    }
}
