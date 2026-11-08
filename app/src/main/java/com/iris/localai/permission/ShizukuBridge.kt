package com.iris.localai.permission

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.provider.Settings
import android.util.Log
import rikka.shizuku.Shizuku
import java.io.ByteArrayOutputStream

/** Shizuku binder 桥：权限申请、shell 执行与通过 Shizuku 开启无障碍。 */
object ShizukuBridge {

    private const val TAG = "IrisShizuku"
    const val REQUEST_CODE = 0x4952

    data class ExecResult(
        val exitCode: Int,
        val stdout: ByteArray,
        val stderr: ByteArray,
    ) {
        fun stdoutText(): String = runCatching { stdout.toString(Charsets.UTF_8) }.getOrDefault("")
        fun stderrText(): String = runCatching { stderr.toString(Charsets.UTF_8) }.getOrDefault("")
    }

    fun pingBinder(): Boolean = runCatching { Shizuku.pingBinder() }.getOrDefault(false)

    fun hasPermission(): Boolean = runCatching {
        Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    }.getOrDefault(false)

    fun isAvailable(): Boolean = pingBinder() && hasPermission()

    fun requestPermission(): Boolean = runCatching {
        Shizuku.requestPermission(REQUEST_CODE)
        true
    }.getOrDefault(false)

    fun addBinderReceivedListener(listener: Shizuku.OnBinderReceivedListener) {
        runCatching { Shizuku.addBinderReceivedListenerSticky(listener) }
    }

    fun addBinderDeadListener(listener: Shizuku.OnBinderDeadListener) {
        runCatching { Shizuku.addBinderDeadListener(listener) }
    }

    fun addRequestPermissionResultListener(listener: Shizuku.OnRequestPermissionResultListener) {
        runCatching { Shizuku.addRequestPermissionResultListener(listener) }
    }

    fun execResult(command: String): ExecResult =
        execResultInternal(arrayOf("sh", "-c", command), command)

    fun execResultArgs(args: List<String>): ExecResult {
        if (args.isEmpty()) return ExecResult(-3, ByteArray(0), ByteArray(0))
        return execResultInternal(args.toTypedArray(), args.joinToString(" "))
    }

    fun execText(command: String): String {
        val result = execResult(command)
        return if (result.exitCode == 0) result.stdoutText() else ""
    }

    /** 通过 Shizuku 写入 secure settings，自动把本应用的无障碍服务加入启用列表。 */
    fun enableAccessibility(context: Context, serviceClass: Class<*>): Boolean {
        if (!isAvailable()) return false
        val serviceId = ComponentName(context.packageName, serviceClass.name).flattenToString()
        val current = runCatching {
            Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
        }.getOrDefault("").orEmpty()
        val services = current.split(':')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toMutableSet()
        services.add(serviceId)
        val list = services.joinToString(":")
        val setServices = execResultArgs(
            listOf("settings", "put", "secure", "enabled_accessibility_services", list),
        )
        val enable = execResultArgs(
            listOf("settings", "put", "secure", "accessibility_enabled", "1"),
        )
        return setServices.exitCode == 0 && enable.exitCode == 0
    }

    private fun execResultInternal(processArgs: Array<String>, label: String): ExecResult {
        return try {
            if (!pingBinder()) return ExecResult(-1, ByteArray(0), ByteArray(0))
            if (!hasPermission()) return ExecResult(-2, ByteArray(0), ByteArray(0))
            val process = newProcess(processArgs)
            val cls = process.javaClass
            val input = cls.getMethod("getInputStream").invoke(process) as java.io.InputStream
            val error = cls.getMethod("getErrorStream").invoke(process) as java.io.InputStream
            val stdout = readAll(input)
            val stderr = readAll(error)
            val exitCode = cls.getMethod("waitFor").invoke(process) as Int
            if (exitCode != 0) {
                Log.w(TAG, "exec exit=$exitCode cmd=$label stderr=${stderr.toString(Charsets.UTF_8).take(300)}")
            }
            ExecResult(exitCode, stdout, stderr)
        } catch (t: Throwable) {
            Log.w(TAG, "exec failed: ${t.message}")
            ExecResult(-3, ByteArray(0), ByteArray(0))
        }
    }

    private fun newProcess(command: Array<String>): Any {
        val method = Shizuku::class.java.getDeclaredMethod(
            "newProcess",
            Array<String>::class.java,
            Array<String>::class.java,
            String::class.java,
        )
        method.isAccessible = true
        return method.invoke(null, command, null, null)
            ?: error("Shizuku newProcess returned null")
    }

    private fun readAll(input: java.io.InputStream): ByteArray = try {
        input.use { stream ->
            ByteArrayOutputStream().use { output ->
                val buffer = ByteArray(16 * 1024)
                while (true) {
                    val read = stream.read(buffer)
                    if (read <= 0) break
                    output.write(buffer, 0, read)
                }
                output.toByteArray()
            }
        }
    } catch (_: Throwable) {
        ByteArray(0)
    }
}
