package com.iris.localai.permission

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.PixelFormat
import android.media.Image
import android.media.ImageReader
import android.os.Binder
import android.os.Handler
import android.os.HandlerThread
import android.os.IInterface
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.Surface
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.lang.reflect.Method
import java.lang.reflect.Proxy

/**
 * Aries 风格的精简虚拟屏：
 * Shizuku 获取 IDisplayManager -> 创建 VirtualDisplay -> ImageReader 收帧 -> 指定 displayId 注入输入。
 * 不包含 GL 预览、悬浮窗预览和完整 IME 策略，属于可运行的第一版。
 */
class VirtualDisplayController(private val context: Context) {

    @Volatile private var displayId: Int = -1
    @Volatile private var active = false
    private var callback: Any? = null
    private var reader: ImageReader? = null
    private var outputSurface: Surface? = null
    private var width = 0
    private var height = 0
    @Volatile private var lastLaunchedPackage: String? = null
    private var latestFrame: Bitmap? = null
    private val frameLock = Any()
    private var frameThread: HandlerThread? = null

    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running.asStateFlow()

    fun isRunning(): Boolean = active && displayId > 0
    fun currentDisplayId(): Int = displayId
    fun contentSize(): Pair<Int, Int> = width to height

    @Synchronized
    fun start(width: Int = 1080, height: Int = 1920, dpi: Int = 160): Result<Int> {
        if (isRunning()) return Result.success(displayId)
        stop()
        val w = width.coerceAtLeast(320)
        val h = height.coerceAtLeast(480)
        return runCatching {
            val displayManager = ShizukuServices.displayManager()
                ?: error("无法获取 IDisplayManager，请检查 Shizuku 授权")
            val imageReader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2)
            val surface = imageReader.surface
            val cb = createCallback()
            val flags = buildFlags()
            val config = buildConfig(w, h, dpi, surface, flags)
            val method = displayManager.javaClass.methods
                .filter { it.name == "createVirtualDisplay" }
                .let { list -> list.ifEmpty { displayManager.javaClass.methods.filter { it.name.contains("createVirtualDisplay", ignoreCase = true) } } }
                .firstOrNull { it.parameterTypes.size == 4 }
                ?: displayManager.javaClass.methods
                    .filter { it.name.contains("createVirtualDisplay", ignoreCase = true) }
                    .firstOrNull { it.parameterTypes.size == 3 }
                ?: displayManager.javaClass.methods
                    .filter { it.name.contains("createVirtualDisplay", ignoreCase = true) }
                    .firstOrNull()
                ?: run {
                    val signatures = displayManager.javaClass.methods
                        .filter { it.name.contains("display", ignoreCase = true) }
                        .joinToString("\n") { m -> "${m.name}(${m.parameterTypes.joinToString { it.name }})" }
                    Log.e(TAG, "createVirtualDisplay candidates:\n$signatures")
                    error("系统没有可用的 createVirtualDisplay 方法")
                }
            val id = try {
                invokeCreate(displayManager, method, config, cb)
            } catch (t: Throwable) {
                val cause = (t as? java.lang.reflect.InvocationTargetException)?.targetException ?: t
                if (cause is SecurityException) {
                    val noTrusted = buildConfig(w, h, dpi, surface, flags and TRUSTED_FLAG.inv())
                    invokeCreate(displayManager, method, noTrusted, cb)
                } else {
                    throw cause
                }
            }
            if (id <= 0) error("createVirtualDisplay 返回无效 displayId")
            val thread = HandlerThread("iris-vd-frames").apply { start() }
            frameThread = thread
            val handler = Handler(thread.looper)
            imageReader.setOnImageAvailableListener({ r ->
                val image = runCatching { r.acquireLatestImage() }.getOrNull()
                    ?: return@setOnImageAvailableListener
                val bitmap = runCatching { imageToBitmap(image) }.getOrNull()
                runCatching { image.close() }
                if (bitmap != null) {
                    synchronized(frameLock) {
                        latestFrame?.recycle()
                        latestFrame = bitmap
                    }
                }
            }, handler)
            displayId = id
            callback = cb
            reader = imageReader
            outputSurface = surface
            this.width = w
            this.height = h
            active = true
            _running.value = true
            Log.i(TAG, "virtual display started: id=$id ${w}x$h dpi=$dpi")
            id
        }.onFailure {
            stop()
            Log.w(TAG, "virtual display start failed: ${it.message}")
        }
    }

    @Synchronized
    fun stop() {
        active = false
        _running.value = false
        val cb = callback
        callback = null
        val id = displayId
        displayId = -1
        val displayManager = runCatching { ShizukuServices.displayManager() }.getOrNull()
        if (displayManager != null && cb != null) {
            releaseDisplay(displayManager, cb, id)
        }
        runCatching { reader?.close() }
        runCatching { outputSurface?.release() }
        frameThread?.quitSafely()
        frameThread = null
        synchronized(frameLock) {
            latestFrame?.recycle()
            latestFrame = null
        }
        reader = null
        outputSurface = null
        width = 0
        height = 0
    }

    fun capture(): Bitmap? {
        val cached = synchronized(frameLock) {
            latestFrame?.copy(Bitmap.Config.ARGB_8888, false)
        }
        if (cached != null && !isLikelyBlack(cached)) return cached
        val direct = readLatestImage()
        if (direct != null && !isLikelyBlack(direct)) {
            cached?.recycle()
            return direct
        }
        val shell = captureViaShell()
        if (shell != null && !isLikelyBlack(shell)) {
            cached?.recycle()
            direct?.recycle()
            return shell
        }
        return cached ?: direct ?: shell
    }

    private fun readLatestImage(): Bitmap? {
        val r = reader ?: return null
        val image = runCatching { r.acquireLatestImage() }.getOrNull() ?: return null
        return try {
            imageToBitmap(image)
        } finally {
            runCatching { image.close() }
        }
    }

    private fun imageToBitmap(image: Image): Bitmap? {
        val plane = image.planes.firstOrNull() ?: return null
        val buffer = plane.buffer
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        val imageWidth = image.width.coerceAtLeast(1)
        val imageHeight = image.height.coerceAtLeast(1)
        val rowPadding = rowStride - pixelStride * imageWidth
        val paddedWidth = imageWidth + if (pixelStride > 0) rowPadding / pixelStride else 0
        val padded = Bitmap.createBitmap(paddedWidth, imageHeight, Bitmap.Config.ARGB_8888)
        padded.copyPixelsFromBuffer(buffer)
        return if (paddedWidth == imageWidth) {
            padded
        } else {
            Bitmap.createBitmap(padded, 0, 0, imageWidth, imageHeight).also { padded.recycle() }
        }
    }

    private fun isLikelyBlack(bitmap: Bitmap): Boolean {
        if (bitmap.width <= 1 || bitmap.height <= 1) return true
        val points = listOf(
            bitmap.width / 4 to bitmap.height / 4,
            bitmap.width / 2 to bitmap.height / 2,
            bitmap.width * 3 / 4 to bitmap.height * 3 / 4,
        )
        return points.all { (x, y) ->
            val color = bitmap.getPixel(x, y)
            (color ushr 24) == 0 || (color and 0x00FFFFFF) == 0
        }
    }

    fun launchApp(packageName: String): Boolean {
        val id = displayId
        if (id <= 0) return false
        lastLaunchedPackage = packageName
        val component = resolveComponent(packageName)
        val commands = buildList {
            if (component != null) {
                add("cmd activity start-activity --display $id -n $component")
                add("am start --display $id -n $component")
            }
            add("am start --display $id -a android.intent.action.MAIN -c android.intent.category.LAUNCHER -p $packageName")
        }
        return runCommands(commands)
    }

    fun launchFreeform(packageName: String): Boolean {
        lastLaunchedPackage = packageName
        val proxy = android.content.Intent(context, FreeformLaunchActivity::class.java)
            .putExtra(FreeformLaunchActivity.EXTRA_PACKAGE, packageName)
            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        if (runCatching { context.startActivity(proxy); true }.getOrDefault(false)) {
            return true
        }
        val metrics = context.resources.displayMetrics
        val left = metrics.widthPixels / 12
        val top = metrics.heightPixels / 7
        val right = metrics.widthPixels - left
        val bottom = metrics.heightPixels - top
        val bounds = "$left,$top,$right,$bottom"
        val component = resolveComponent(packageName)
        val commands = buildList {
            if (component != null) {
                add("cmd activity start-activity --windowingMode 5 --bounds $bounds -n $component")
                add("am start --windowingMode 5 --bounds $bounds -n $component")
                add("am start --windowMode 5 --bounds $bounds -n $component")
            }
            add("am start -a android.intent.action.MAIN -c android.intent.category.LAUNCHER -p $packageName")
        }
        return runCommands(commands) || launchViaContext(packageName)
    }
    fun tap(x: Int, y: Int): Boolean = touchTap(x.toFloat(), y.toFloat())

    /** 把虚拟屏上的任务带回主屏并释放虚拟屏。 */
    fun bringLaunchedAppToMain(): Boolean {
        val id = displayId
        if (id <= 0) return false
        val stackId = findStackId(id)
        var moved = false
        if (stackId != null) {
            moved = runCommands(
                listOf(
                    "am display move-stack $stackId 0",
                    "cmd activity display move-stack $stackId 0",
                ),
            )
        }
        val packageName = lastLaunchedPackage
        stop()
        if (!moved && packageName != null) {
            runCommands(
                listOf(
                    "am start -a android.intent.action.MAIN -c android.intent.category.LAUNCHER -p $packageName",
                    "monkey -p $packageName 1",
                ),
            )
        }
        return true
    }

    private fun captureViaShell(): Bitmap? {
        if (displayId <= 0) return null
        for (command in listOf("screencap -d $displayId -p", "screencap -p -d $displayId")) {
            val result = runCatching { ShizukuBridge.execResult(command) }.getOrNull() ?: continue
            if (result.exitCode != 0 || result.stdout.isEmpty()) continue
            val bitmap = runCatching {
                BitmapFactory.decodeByteArray(result.stdout, 0, result.stdout.size)
            }.getOrNull()
            if (bitmap != null) return bitmap
        }
        return null
    }

    private fun findStackId(targetDisplayId: Int): Int? {
        val output = ShizukuBridge.execText("am stack list")
        var current: Int? = null
        for (line in output.lineSequence()) {
            val trimmed = line.trim()
            val root = Regex("RootTask\\s+id=(\\d+).*displayId=(\\d+)").find(trimmed)
            if (root != null) {
                val id = root.groupValues[1].toIntOrNull()
                val display = root.groupValues[2].toIntOrNull()
                current = if (display == targetDisplayId) id else null
                continue
            }
            val stack = Regex("Stack\\s+id=(\\d+).*displayId=(\\d+)").find(trimmed)
            if (stack != null) {
                val id = stack.groupValues[1].toIntOrNull()
                val display = stack.groupValues[2].toIntOrNull()
                current = if (display == targetDisplayId) id else null
                continue
            }
            if (current != null && trimmed.startsWith("taskId=")) return current
        }
        return current
    }

    fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Int): Boolean {
        if (!isRunning()) return false
        val down = SystemClock.uptimeMillis()
        if (!injectMotion(down, down, MotionEvent.ACTION_DOWN, x1.toFloat(), y1.toFloat())) return false
        val steps = (durationMs.coerceIn(80, 3000) / 16).coerceAtLeast(2)
        for (i in 1..steps) {
            val f = i.toFloat() / steps
            injectMotion(
                down,
                SystemClock.uptimeMillis(),
                MotionEvent.ACTION_MOVE,
                x1 + (x2 - x1) * f,
                y1 + (y2 - y1) * f,
            )
            Thread.sleep(16L)
        }
        return injectMotion(down, SystemClock.uptimeMillis(), MotionEvent.ACTION_UP, x2.toFloat(), y2.toFloat())
    }

    fun key(keyCode: Int): Boolean {
        if (!isRunning()) return false
        val now = SystemClock.uptimeMillis()
        val down = KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0)
        val up = KeyEvent(now, now, KeyEvent.ACTION_UP, keyCode, 0)
        setDisplayId(down)
        setDisplayId(up)
        return injectKey(down) && injectKey(up)
    }

    private fun touchTap(x: Float, y: Float): Boolean {
        if (!isRunning()) return false
        val now = SystemClock.uptimeMillis()
        return injectMotion(now, now, MotionEvent.ACTION_DOWN, x, y) &&
            injectMotion(now, SystemClock.uptimeMillis(), MotionEvent.ACTION_UP, x, y)
    }

    private fun injectMotion(downTime: Long, eventTime: Long, action: Int, x: Float, y: Float): Boolean {
        val properties = MotionEvent.PointerProperties().apply {
            id = 0
            toolType = MotionEvent.TOOL_TYPE_FINGER
        }
        val coords = MotionEvent.PointerCoords().apply {
            this.x = x
            this.y = y
            pressure = 1f
            size = 1f
        }
        val event = MotionEvent.obtain(
            downTime,
            eventTime,
            action,
            1,
            arrayOf(properties),
            arrayOf(coords),
            0,
            0,
            1f,
            1f,
            0,
            0,
            InputDevice.SOURCE_TOUCHSCREEN,
            0,
        )
        setDisplayId(event)
        return injectEvent(event).also { event.recycle() }
    }

    private fun injectKey(event: KeyEvent): Boolean = injectEvent(event)

    private fun injectEvent(event: android.view.InputEvent): Boolean {
        val manager = ShizukuServices.inputManager() ?: return false
        val method = manager.javaClass.methods
            .filter { it.name == "injectInputEvent" }
            .filter { it.parameterTypes.isNotEmpty() && android.view.InputEvent::class.java.isAssignableFrom(it.parameterTypes[0]) }
            .minByOrNull { it.parameterTypes.size }
            ?: return false
        return runCatching {
            when (method.parameterTypes.size) {
                3 -> method.invoke(manager, event, displayId, 0)
                else -> method.invoke(manager, event, 0)
            }
            true
        }.getOrDefault(false)
    }

    private fun setDisplayId(event: android.view.InputEvent) {
        runCatching {
            event.javaClass.methods
                .firstOrNull { it.name == "setDisplayId" && it.parameterTypes.size == 1 && it.parameterTypes[0] == Int::class.javaPrimitiveType }
                ?.invoke(event, displayId)
        }
    }

    private fun resolveComponent(packageName: String): String? {
        val output = ShizukuBridge.execText("cmd package resolve-activity --brief $packageName")
        return output.lineSequence().map { it.trim() }.lastOrNull { it.contains('/') }
            ?: runCatching { context.packageManager.getLaunchIntentForPackage(packageName)?.component?.flattenToString() }.getOrNull()
    }

    private fun launchViaContext(packageName: String): Boolean = runCatching {
        val intent = context.packageManager.getLaunchIntentForPackage(packageName) ?: return false
        context.startActivity(intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    }.getOrDefault(false)

    private fun runCommands(commands: List<String>): Boolean {
        for (command in commands) {
            val result = ShizukuBridge.execResult(command)
            if (result.exitCode == 0) {
                Log.i(TAG, "command ok: $command")
                return true
            }
            Log.w(TAG, "command failed: $command exit=${result.exitCode} ${result.stderrText().take(160)}")
        }
        return false
    }

    private fun invokeCreate(
        displayManager: Any,
        method: Method,
        config: Any,
        callback: Any,
    ): Int {
        val args = method.parameterTypes.map { type ->
            val name = type.name
            when {
                name.contains("VirtualDisplayConfig") -> config
                name.contains("IMediaProjection") -> null
                name.contains("IVirtualDisplayCallback") -> callback
                IInterface::class.java.isAssignableFrom(type) -> callback
                type == String::class.java -> "com.android.shell"
                type == Int::class.javaPrimitiveType || type == Integer::class.java -> 0
                type == Boolean::class.javaPrimitiveType || type == java.lang.Boolean::class.java -> false
                else -> null
            }
        }.toTypedArray()
        Log.i(TAG, "createVirtualDisplay via ${method.name}(${method.parameterTypes.joinToString { it.name }})")
        val result = method.invoke(displayManager, *args)
        return (result as? Number)?.toInt() ?: (result as? Int) ?: -1
    }

    private fun buildFlags(): Int {
        var flags = VIRTUAL_DISPLAY_FLAG_PUBLIC or (1 shl 1) or (1 shl 3) // PUBLIC + PRESENTATION + OWN_CONTENT_ONLY
        flags = flags or TRUSTED_FLAG
        flags = flags or (1 shl 6) // SUPPORTS_TOUCH
        flags = flags or (1 shl 7) // ROTATES_WITH_CONTENT
        flags = flags or (1 shl 8) // DESTROY_CONTENT_ON_REMOVAL
        return flags
    }

    private fun buildConfig(width: Int, height: Int, dpi: Int, surface: Surface, flags: Int): Any {
        val builderClass = Class.forName("android.hardware.display.VirtualDisplayConfig\$Builder")
        val constructor = builderClass.getConstructor(
            String::class.java,
            Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType,
        )
        val builder = constructor.newInstance("IRIS-Virtual", width, height, dpi)
        builderClass.getMethod("setSurface", Surface::class.java).invoke(builder, surface)
        builderClass.getMethod("setFlags", Int::class.javaPrimitiveType).invoke(builder, flags)
        return builderClass.getMethod("build").invoke(builder)
    }

    private fun createCallback(): Any {
        val callbackInterface = Class.forName("android.hardware.display.IVirtualDisplayCallback")
        val binder = Binder()
        return Proxy.newProxyInstance(
            callbackInterface.classLoader,
            arrayOf(callbackInterface, IInterface::class.java),
        ) { _, method, _ ->
            if (method.name == "asBinder") binder else Unit
        }
    }

    private fun releaseDisplay(displayManager: Any, callback: Any, id: Int) {
        val methods = displayManager.javaClass.methods.filter {
            it.name == "releaseVirtualDisplay" || it.name == "destroyVirtualDisplay" || it.name == "removeVirtualDisplay"
        }
        val binder = runCatching {
            callback.javaClass.methods.firstOrNull {
                it.name == "asBinder" && it.parameterTypes.isEmpty()
            }?.invoke(callback)
        }.getOrNull()
        for (method in methods) {
            val ok = runCatching {
                when (method.parameterTypes.size) {
                    1 -> when {
                        method.parameterTypes[0].name == "android.os.IBinder" && binder != null ->
                            method.invoke(displayManager, binder)
                        else -> method.invoke(displayManager, callback)
                    }
                    2 -> method.invoke(displayManager, callback, id)
                    else -> method.invoke(displayManager, callback)
                }
                true
            }.getOrDefault(false)
            if (ok) return
        }
    }

    private companion object {
        const val TAG = "IrisVirtualDisplay"
        const val VIRTUAL_DISPLAY_FLAG_PUBLIC = 1
        const val TRUSTED_FLAG = 1 shl 10
    }
}
