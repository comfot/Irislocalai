package com.iris.localai.permission

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import com.iris.localai.accessibility.IrisAccessibilityService
import com.iris.localai.data.OperationSurfaceMode
import com.iris.localai.data.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** 操作通道执行器：Shizuku 优先，失败回退无障碍；没有虚拟屏隔离。 */
class DeviceActionExecutor(
    private val context: Context,
    private val permissions: DevicePermissionStore,
    private val settings: SettingsRepository,
    private val virtualDisplay: VirtualDisplayController,
) {

    suspend fun openApp(packageName: String): String {
        val target = AppResolver.resolve(context, packageName)
            ?: error("未找到应用：$packageName")
        Log.i("IrisOperate", "openApp input=$packageName resolved=$target mode=${settings.current.operationSurfaceMode} background=${settings.current.operateBackgroundMode}")
        if (settings.current.operateBackgroundMode) {
            when (settings.current.operationSurfaceMode) {
                OperationSurfaceMode.ARIES_VIRTUAL_DISPLAY -> {
                    ensureVirtualDisplay()
                    if (withContext(Dispatchers.IO) { virtualDisplay.launchApp(target) }) {
                        return "已在虚拟屏（display ${virtualDisplay.currentDisplayId()}）打开 $target"
                    }
                    error("虚拟屏启动应用失败：请确认 Shizuku 已授权且系统支持副屏")
                }
                OperationSurfaceMode.DEVICE_FREEFORM -> {
                    if (virtualDisplay.launchFreeform(target)) {
                        return "已尝试用小窗打开 $target"
                    }
                    error("小窗启动失败：该 ROM 可能未启用 freeform，请改用普通模式")
                }
                OperationSurfaceMode.NORMAL -> Unit
            }
        }
        if (channel() != OperationChannel.NONE && shizuku("monkey -p '$target' -c android.intent.category.LAUNCHER 1")) {
            return "打开应用 $target：Shizuku 成功"
        }
        val intent = context.packageManager.getLaunchIntentForPackage(target)
            ?: error("未找到应用：$target")
        context.startActivity(intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
        return "已打开 $target"
    }

    suspend fun tap(x: Int, y: Int): String {
        if (useVirtualDisplay()) {
            ensureVirtualDisplay()
            if (withContext(Dispatchers.IO) { virtualDisplay.tap(x, y) }) return "点击 ($x, $y)：虚拟屏成功"
            error("虚拟屏点击失败")
        }
        return run("点击 ($x, $y)", {
            shizuku("input tap $x $y")
        }, {
            IrisAccessibilityService.instance?.tap(x.toFloat(), y.toFloat()) == true
        })
    }

    suspend fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Int): String {
        if (useVirtualDisplay()) {
            ensureVirtualDisplay()
            if (withContext(Dispatchers.IO) { virtualDisplay.swipe(x1, y1, x2, y2, durationMs) }) {
                return "滑动 ($x1,$y1) → ($x2,$y2)：虚拟屏成功"
            }
            error("虚拟屏滑动失败")
        }
        return run("滑动 ($x1,$y1) → ($x2,$y2)", {
            shizuku("input swipe $x1 $y1 $x2 $y2 ${durationMs.coerceIn(80, 3000)}")
        }, {
            IrisAccessibilityService.instance?.swipe(
                x1.toFloat(), y1.toFloat(), x2.toFloat(), y2.toFloat(), durationMs.toLong(),
            ) == true
        })
    }

    suspend fun back(): String {
        if (useVirtualDisplay()) {
            ensureVirtualDisplay()
            return if (withContext(Dispatchers.IO) { virtualDisplay.key(android.view.KeyEvent.KEYCODE_BACK) }) {
                "返回：虚拟屏成功"
            } else {
                "虚拟屏返回失败"
            }
        }
        return run("返回", {
            shizuku("input keyevent 4")
        }, {
            IrisAccessibilityService.instance?.back() == true
        })
    }

    suspend fun home(): String {
        if (useVirtualDisplay()) {
            ensureVirtualDisplay()
            return if (withContext(Dispatchers.IO) { virtualDisplay.key(android.view.KeyEvent.KEYCODE_HOME) }) {
                "回到桌面：虚拟屏成功"
            } else {
                "虚拟屏 Home 失败"
            }
        }
        return run("回到桌面", {
            shizuku("input keyevent 3")
        }, {
            IrisAccessibilityService.instance?.home() == true
        })
    }

    suspend fun inputText(text: String): String {
        if (useVirtualDisplay()) {
            error("虚拟屏文本输入暂未实现，请改用普通屏幕或本机小窗")
        }
        return run("输入文本", {
            // Android input 使用 %s 表示空格。
            val escaped = text.replace("'", "'\\''").replace(" ", "%s")
            shizuku("input text '$escaped'")
        }, {
            IrisAccessibilityService.instance?.inputText(text) == true
        })
    }

    suspend fun screenshot(): String = withContext(Dispatchers.IO) {
        val bitmap = if (useVirtualDisplay()) {
            ensureVirtualDisplay()
            virtualDisplay.capture()
        } else {
            captureBitmap()
        } ?: error("截图失败：请开启无障碍/Shizuku，或确认虚拟屏已启动")
        saveScreenshot(bitmap)
    }

    private fun saveScreenshot(bitmap: Bitmap): String {
        val dir = File(context.filesDir, "screenshots").apply { mkdirs() }
        val file = File(dir, "shot-${System.currentTimeMillis()}.png")
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        return file.absolutePath
    }

    private suspend fun captureBitmap(): Bitmap? {
        val channel = channel()
        if (channel == OperationChannel.SHIZUKU || channel == OperationChannel.BACKGROUND) {
            val bytes = withContext(Dispatchers.IO) { ShizukuBridge.execResult("screencap -p").stdout }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.let { return it }
        }
        return IrisAccessibilityService.instance?.screenshot()
    }

    private suspend fun shizuku(command: String): Boolean = withContext(Dispatchers.IO) {
        ShizukuBridge.execResult(command).exitCode == 0
    }

    private fun channel(): OperationChannel {
        val state = permissions.state.value
        val config = settings.current
        return state.channel(config.operateBackgroundMode, config.operateShizukuMode)
    }

    fun releaseVirtualDisplay() {
        virtualDisplay.stop()
    }

    fun virtualDisplayRunning(): Boolean = virtualDisplay.isRunning()

    private fun useVirtualDisplay(): Boolean =
        settings.current.operateBackgroundMode &&
            settings.current.operationSurfaceMode == OperationSurfaceMode.ARIES_VIRTUAL_DISPLAY

    private suspend fun ensureVirtualDisplay() = withContext(Dispatchers.IO) {
        if (virtualDisplay.isRunning()) return@withContext
        val metrics = context.resources.displayMetrics
        virtualDisplay.start(metrics.widthPixels, metrics.heightPixels, metrics.densityDpi)
            .getOrElse { error("虚拟屏启动失败：${it.message}") }
    }

    private suspend fun run(
        label: String,
        shell: suspend () -> Boolean,
        accessibility: (() -> Boolean)? = null,
    ): String {
        val channel = channel()
        val state = permissions.state.value
        if (channel == OperationChannel.NONE) {
            error(
                if (settings.current.operateBackgroundMode) {
                    "后台执行需要 Shizuku 授权，请先在操作空间完成授权"
                } else {
                    "未检测到可用操作通道，请开启无障碍或授权 Shizuku"
                },
            )
        }
        if (channel == OperationChannel.ACCESSIBILITY) {
            if (accessibility?.invoke() == true) return "$label：无障碍通道成功"
            if (state.shizukuReady && shell()) return "$label：已回退 Shizuku"
            error("$label：无障碍通道执行失败")
        }
        if (shell()) return "$label：${if (channel == OperationChannel.BACKGROUND) "后台" else "Shizuku"}通道成功"
        if (accessibility?.invoke() == true) return "$label：已回退无障碍"
        error("$label：Shizuku 与无障碍通道均执行失败")
    }
}
