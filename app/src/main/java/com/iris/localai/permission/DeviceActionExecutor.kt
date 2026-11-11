package com.iris.localai.permission

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import com.iris.localai.accessibility.IrisAccessibilityService
import com.iris.localai.data.OperationSurfaceMode
import com.iris.localai.data.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
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

    /** 读取当前界面的控件树；虚拟屏窗口不可读时回退到截图 OCR。 */
    suspend fun uiSnapshot(maxNodes: Int = 200): String {
        val displayId = targetDisplayId()
        val service = IrisAccessibilityService.instance
        if (service != null) {
            val snapshot = withContext(Dispatchers.Default) {
                service.captureUi(maxNodes.coerceIn(20, 300), displayId = displayId)
            }
            if (snapshot.nodes.isNotEmpty()) {
                return buildJsonObject {
                    put("mode", "ui")
                    put("package", snapshot.packageName)
                    put("display", displayId)
                    put("width", snapshot.width)
                    put("height", snapshot.height)
                    putJsonArray("nodes") {
                        snapshot.nodes.forEach { node ->
                            addJsonObject {
                                if (node.text.isNotBlank()) put("text", node.text)
                                if (node.description.isNotBlank()) put("desc", node.description)
                                if (node.viewId.isNotBlank()) put("id", node.viewId)
                                put("class", node.className)
                                put("bounds", node.boundsText())
                                if (node.clickable) put("clickable", true)
                                if (node.editable) put("editable", true)
                                if (node.scrollable) put("scrollable", true)
                                if (node.longClickable) put("long_clickable", true)
                            }
                        }
                    }
                }.toString()
            }
        }
        val lines = ocrScreen()
        if (!lines.isNullOrEmpty()) return ocrJson(displayId, lines)
        if (displayId != android.view.Display.DEFAULT_DISPLAY || service == null) {
            error("虚拟屏窗口没有暴露给无障碍服务，截图 OCR 也没有识别到文字；请确认无障碍/截图权限，或切回主屏")
        }
        return buildJsonObject {
            put("mode", "empty")
            put("display", displayId)
            putJsonArray("nodes") {}
        }.toString()
    }

    suspend fun tapText(text: String, matchIndex: Int = 0): String {
        val displayId = targetDisplayId()
        val service = IrisAccessibilityService.instance
        var clicked = false
        if (service != null) {
            clicked = withContext(Dispatchers.Default) { service.tapText(text, matchIndex, displayId) }
            if (!clicked && useVirtualDisplay()) {
                val bounds = withContext(Dispatchers.Default) {
                    service.findTextBounds(text, matchIndex, displayId)
                }
                if (bounds != null) {
                    val tapped = withContext(Dispatchers.IO) {
                        virtualDisplay.tap(bounds.centerX(), bounds.centerY())
                    }
                    if (tapped) {
                        delay(250)
                        return "已点击「$text」（虚拟屏）"
                    }
                }
            }
        }
        if (!clicked) {
            val line = ocrFind(text, matchIndex)
            if (line != null) {
                val tapped = if (useVirtualDisplay()) {
                    withContext(Dispatchers.IO) {
                        virtualDisplay.tap(line.bounds.centerX(), line.bounds.centerY())
                    }
                } else {
                    tap(line.bounds.centerX(), line.bounds.centerY())
                    true
                }
                if (tapped) {
                    delay(250)
                    return "已点击「$text」（OCR）"
                }
            }
        }
        if (!clicked) error("未找到可点击的文字「$text」")
        delay(250)
        return "已点击「$text」"
    }

    suspend fun scroll(direction: String): String {
        val displayId = targetDisplayId()
        val service = IrisAccessibilityService.instance
        val ok = if (service != null) {
            withContext(Dispatchers.Default) { service.scrollUi(direction, displayId) }
        } else {
            false
        }
        if (!ok && useVirtualDisplay()) {
            val metrics = context.resources.displayMetrics
            val cx = metrics.widthPixels / 2f
            val cy = metrics.heightPixels / 2f
            val distance = minOf(metrics.widthPixels, metrics.heightPixels) * 0.32f
            val swiped = withContext(Dispatchers.IO) {
                when (direction.lowercase()) {
                    "up", "back", "backward" -> virtualDisplay.swipe(
                        cx.toInt(), (cy - distance).toInt(), cx.toInt(), (cy + distance).toInt(), 260,
                    )
                    "left" -> virtualDisplay.swipe(
                        (cx + distance).toInt(), cy.toInt(), (cx - distance).toInt(), cy.toInt(), 260,
                    )
                    "right" -> virtualDisplay.swipe(
                        (cx - distance).toInt(), cy.toInt(), (cx + distance).toInt(), cy.toInt(), 260,
                    )
                    else -> virtualDisplay.swipe(
                        cx.toInt(), (cy + distance).toInt(), cx.toInt(), (cy - distance).toInt(), 260,
                    )
                }
            }
            if (swiped) {
                delay(250)
                return "已向 $direction 滚动（虚拟屏）"
            }
        }
        if (!ok) error("滚动失败：当前界面没有可滚动区域")
        delay(250)
        return "已向 $direction 滚动"
    }

    suspend fun waitForText(text: String, timeoutMs: Int): String {
        val displayId = targetDisplayId()
        val service = IrisAccessibilityService.instance
        val deadline = System.currentTimeMillis() + timeoutMs.coerceIn(200, 15_000)
        while (System.currentTimeMillis() < deadline) {
            if (service != null && withContext(Dispatchers.Default) { service.hasText(text, displayId) }) {
                return "已出现「$text」"
            }
            if (ocrFind(text) != null) return "已出现「$text」（OCR）"
            delay(700)
        }
        error("等待超时：未出现「$text」")
    }

    suspend fun recents(): String {
        if (useVirtualDisplay()) error("虚拟屏暂不支持最近任务，请切回主屏")
        val service = IrisAccessibilityService.instance
        if (service != null && withContext(Dispatchers.Default) { service.recents() }) {
            delay(200)
            return "已打开最近任务"
        }
        if (shizuku("input keyevent 187")) {
            delay(200)
            return "已打开最近任务：Shizuku 成功"
        }
        error("打开最近任务失败：请开启无障碍或 Shizuku")
    }

    suspend fun notifications(): String {
        if (useVirtualDisplay()) error("虚拟屏暂不支持通知栏，请切回主屏")
        val service = IrisAccessibilityService.instance
        if (service != null && withContext(Dispatchers.Default) { service.notifications() }) {
            delay(200)
            return "已打开通知栏"
        }
        if (shizuku("cmd statusbar expand-notifications")) {
            delay(200)
            return "已打开通知栏：Shizuku 成功"
        }
        error("打开通知栏失败：请开启无障碍或 Shizuku")
    }

    private suspend fun ocrScreen(): List<ScreenTextLine>? {
        val bitmap = withContext(Dispatchers.IO) {
            if (useVirtualDisplay()) {
                ensureVirtualDisplay()
                virtualDisplay.capture()
            } else {
                captureBitmap()
            }
        } ?: return null
        return try {
            ScreenTextRecognizer.recognize(bitmap)
        } catch (_: Exception) {
            emptyList()
        } finally {
            bitmap.recycle()
        }
    }

    private suspend fun ocrFind(text: String, matchIndex: Int = 0): ScreenTextLine? {
        val query = text.trim()
        if (query.isBlank()) return null
        val lines = ocrScreen() ?: return null
        val matches = lines.filter { line ->
            line.text.contains(query, ignoreCase = true) || query.contains(line.text, ignoreCase = true)
        }
        return matches.getOrNull(matchIndex.coerceAtLeast(0))
    }

    private fun ocrJson(displayId: Int, lines: List<ScreenTextLine>): String = buildJsonObject {
        put("mode", "ocr")
        put("package", "")
        put("display", displayId)
        put("width", context.resources.displayMetrics.widthPixels)
        put("height", context.resources.displayMetrics.heightPixels)
        putJsonArray("lines") {
            lines.take(80).forEach { line ->
                addJsonObject {
                    put("text", line.text.take(120))
                    put(
                        "bounds",
                        "[${line.bounds.left},${line.bounds.top}][${line.bounds.right},${line.bounds.bottom}]",
                    )
                    put("cx", line.bounds.centerX())
                    put("cy", line.bounds.centerY())
                }
            }
        }
    }.toString()

    private fun targetDisplayId(): Int =
        if (useVirtualDisplay()) {
            virtualDisplay.currentDisplayId().takeIf { it > 0 }
                ?: android.view.Display.DEFAULT_DISPLAY
        } else {
            android.view.Display.DEFAULT_DISPLAY
        }

    suspend fun tapNormalized(x: Float, y: Float): String {
        val metrics = context.resources.displayMetrics
        return tap(
            (x.coerceIn(0f, 1f) * metrics.widthPixels).toInt(),
            (y.coerceIn(0f, 1f) * metrics.heightPixels).toInt(),
        )
    }

    suspend fun longPress(x: Int, y: Int): String = run("长按 ($x, $y)", {
        shizuku("input swipe $x $y $x $y 650")
    }, {
        IrisAccessibilityService.instance?.longPress(x.toFloat(), y.toFloat()) == true
    })

    suspend fun longPressNormalized(x: Float, y: Float): String {
        val metrics = context.resources.displayMetrics
        return longPress(
            (x.coerceIn(0f, 1f) * metrics.widthPixels).toInt(),
            (y.coerceIn(0f, 1f) * metrics.heightPixels).toInt(),
        )
    }

    suspend fun enter(): String = run("回车", {
        shizuku("input keyevent 66")
    }, {
        IrisAccessibilityService.instance?.pressEnter() == true
    })

    suspend fun swipeNormalized(
        x1: Float,
        y1: Float,
        x2: Float,
        y2: Float,
        durationMs: Int,
    ): String {
        val metrics = context.resources.displayMetrics
        return swipe(
            (x1.coerceIn(0f, 1f) * metrics.widthPixels).toInt(),
            (y1.coerceIn(0f, 1f) * metrics.heightPixels).toInt(),
            (x2.coerceIn(0f, 1f) * metrics.widthPixels).toInt(),
            (y2.coerceIn(0f, 1f) * metrics.heightPixels).toInt(),
            durationMs,
        )
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
