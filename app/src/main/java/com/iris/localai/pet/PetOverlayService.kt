package com.iris.localai.pet

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.iris.localai.AppGraph
import com.iris.localai.IrisApp
import com.iris.localai.MainActivity
import com.iris.localai.R
import com.iris.localai.data.AppSettings
import com.iris.localai.data.PetLongPressAction
import com.iris.localai.data.PetTapAction
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlin.random.Random

/**
 * 桌宠悬浮窗：窗口内是 Compose 树，Live2D / MMD / VRM 走与生活空间相同的渲染器；
 * 设置通过 StateFlow 直接参与组合。长按菜单的“配置”会弹出第二个窗口，
 * 固定在桌宠的相反方向。
 */
class PetOverlayService : Service() {

    private lateinit var windowManager: WindowManager
    private lateinit var graph: AppGraph
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val handler = Handler(Looper.getMainLooper())
    private val uiState = PetOverlayUiState()

    private var owner: OverlayViewOwner? = null
    private var root: FrameLayout? = null
    private var composeView: ComposeView? = null
    private var params: WindowManager.LayoutParams? = null
    private var configMenu: FrameLayout? = null
    private var settings: AppSettings = AppSettings()
    private var speakingToken = 0
    private var bubbleToken = 0
    private var lastInteractionAt = 0L
    private val overlayPrefs by lazy { getSharedPreferences("iris_pet_overlay", Context.MODE_PRIVATE) }
    private val saveRunnable = Runnable { savePosition() }
    private val wanderRunnable = object : Runnable {
        override fun run() {
            wander()
            handler.postDelayed(this, 9000L + Random.nextLong(6000L))
        }
    }

    override fun onCreate() {
        super.onCreate()
        graph = (application as IrisApp).graph
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        settings = graph.settings.current
        startForegroundWithNotification()
        scope.launch {
            graph.settings.settings.collect { updated ->
                settings = updated
                applyWindowSize(updated)
                if (configMenu != null) rebuildConfigMenu()
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_HIDE -> {
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_REFRESH -> applyWindowSize(settings)
            else -> runCatching { showOverlay() }.onFailure { stopSelf() }
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        savePosition()
        uiState.bubble = null
        handler.removeCallbacksAndMessages(null)
        hideConfigMenu()
        hideKeyboard()
        root?.let { view -> runCatching { windowManager.removeView(view) } }
        root = null
        composeView = null
        owner?.destroy()
        owner = null
        params = null
        scope.cancel()
        PetOverlayController.setRunning(false)
        super.onDestroy()
    }

    private fun startForegroundWithNotification() {
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, "桌宠", NotificationManager.IMPORTANCE_LOW).apply {
                description = "桌宠悬浮窗运行状态"
            },
        )
        val notification = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("桌宠已开启")
            .setContentText("拖动移动 · 单击互动 · 长按菜单")
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun showOverlay() {
        if (root != null) {
            applyWindowSize(settings)
            return
        }
        if (!Settings.canDrawOverlays(this)) {
            stopSelf()
            return
        }
        val metrics = resources.displayMetrics
        val size = windowSize(settings)
        val layout = WindowManager.LayoutParams(
            size.first,
            size.second,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            baseFlags(focusable = false),
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            val savedX = overlayPrefs.getInt("x", Int.MIN_VALUE)
            val savedY = overlayPrefs.getInt("y", Int.MIN_VALUE)
            x = if (savedX != Int.MIN_VALUE) savedX else (metrics.widthPixels - size.first - dp(8)).coerceAtLeast(0)
            y = if (savedY != Int.MIN_VALUE) savedY else metrics.heightPixels / 4
            clampPosition(this)
        }
        val viewOwner = OverlayViewOwner().also { it.create() }
        val container = FrameLayout(this)
        // ComposeView 在 attach 时会向上查找 ViewTree 宿主，必须挂在窗口根视图上。
        container.setViewTreeLifecycleOwner(viewOwner)
        container.setViewTreeViewModelStoreOwner(viewOwner)
        container.setViewTreeSavedStateRegistryOwner(viewOwner)
        val compose = ComposeView(this).apply {
            isFocusable = true
            isFocusableInTouchMode = true
            setViewTreeLifecycleOwner(viewOwner)
            setViewTreeViewModelStoreOwner(viewOwner)
            setViewTreeSavedStateRegistryOwner(viewOwner)
            setContent {
                PetOverlayContent(
                    graph = graph,
                    bubble = uiState.bubble,
                    menuVisible = uiState.menuVisible,
                    chatBarVisible = uiState.chatBarVisible,
                    speaking = uiState.speaking,
                    onTap = ::handleTap,
                    onLongPress = ::handleLongPress,
                    onDrag = ::handleDrag,
                    onFeed = ::feed,
                    onSing = ::sing,
                    onWake = ::wake,
                    onOpenChat = ::openLifeChat,
                    onOpenConfig = ::showConfigMenu,
                    onHide = { stopSelf() },
                    onSend = ::sendFromChatBar,
                    onCloseChatBar = { setChatBar(false) },
                )
            }
        }
        container.addView(
            compose,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            ),
        )
        root = container
        composeView = compose
        owner = viewOwner
        params = layout
        windowManager.addView(container, layout)
        PetOverlayController.setRunning(true)
        handler.removeCallbacks(wanderRunnable)
        handler.postDelayed(wanderRunnable, 9000L)
    }

    private fun baseFlags(focusable: Boolean): Int = buildFlags(focusable)

    private fun buildFlags(focusable: Boolean): Int {
        var flags = WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        if (!focusable) flags = flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        return flags
    }

    private fun windowSize(settings: AppSettings): Pair<Int, Int> {
        val scale = settings.petScale.coerceIn(0.6f, 1.8f)
        val width = (dp(150) * scale).toInt()
        val height = (dp(210) * scale).toInt() + if (uiState.chatBarVisible) dp(48) else 0
        return width to height
    }

    private fun applyWindowSize(settings: AppSettings) {
        val layout = params ?: return
        val container = root ?: return
        val size = windowSize(settings)
        if (layout.width == size.first && layout.height == size.second) return
        layout.width = size.first
        layout.height = size.second
        clampPosition(layout)
        runCatching { windowManager.updateViewLayout(container, layout) }
        scheduleSave()
    }

    private fun clampPosition(layout: WindowManager.LayoutParams) {
        val metrics = resources.displayMetrics
        layout.x = layout.x.coerceIn(0, (metrics.widthPixels - layout.width).coerceAtLeast(0))
        layout.y = layout.y.coerceIn(0, (metrics.heightPixels - layout.height).coerceAtLeast(0))
    }

    private fun handleDrag(dx: Float, dy: Float) {
        lastInteractionAt = SystemClock.elapsedRealtime()
        moveBy(dx.toInt(), dy.toInt())
    }

    private fun moveBy(dx: Int, dy: Int) {
        val layout = params ?: return
        val container = root ?: return
        if (dx == 0 && dy == 0) return
        layout.x += dx
        layout.y += dy
        clampPosition(layout)
        runCatching { windowManager.updateViewLayout(container, layout) }
        scheduleSave()
    }

    /** 空闲时小幅移动，模拟宠物在桌面上活动；拖动/打字/菜单期间不走动。 */
    private fun wander() {
        if (!settings.petIdleAnimation || !settings.petDraggable) return
        if (uiState.chatBarVisible || uiState.menuVisible) return
        if (SystemClock.elapsedRealtime() - lastInteractionAt < 5000) return
        moveBy(
            Random.nextInt(-dp(10), dp(11)),
            Random.nextInt(-dp(6), dp(7)),
        )
    }

    private fun scheduleSave() {
        handler.removeCallbacks(saveRunnable)
        handler.postDelayed(saveRunnable, 300)
    }

    private fun savePosition() {
        val layout = params ?: return
        overlayPrefs.edit().putInt("x", layout.x).putInt("y", layout.y).apply()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        handler.post {
            val layout = params ?: return@post
            val container = root ?: return@post
            clampPosition(layout)
            runCatching { windowManager.updateViewLayout(container, layout) }
            savePosition()
        }
    }

    private fun handleTap() {
        lastInteractionAt = SystemClock.elapsedRealtime()
        hideConfigMenu()
        if (uiState.chatBarVisible) return
        uiState.menuVisible = false
        when (settings.petTapAction) {
            PetTapAction.BUBBLE -> say(randomLine())
            PetTapAction.OPEN_APP -> setChatBar(true)
            PetTapAction.NONE -> Unit
        }
    }

    private fun handleLongPress() {
        lastInteractionAt = SystemClock.elapsedRealtime()
        hideConfigMenu()
        when (settings.petLongPressAction) {
            PetLongPressAction.MENU -> {
                uiState.chatBarVisible = false
                uiState.menuVisible = !uiState.menuVisible
            }
            PetLongPressAction.OPEN_APP -> openLifeChat()
            PetLongPressAction.HIDE -> stopSelf()
        }
    }

    private fun setChatBar(visible: Boolean) {
        lastInteractionAt = SystemClock.elapsedRealtime()
        if (uiState.chatBarVisible == visible) return
        uiState.chatBarVisible = visible
        uiState.menuVisible = false
        val layout = params ?: return
        val container = root ?: return
        val size = windowSize(settings)
        layout.width = size.first
        layout.height = size.second
        layout.flags = buildFlags(focusable = visible)
        layout.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or
            WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE
        clampPosition(layout)
        runCatching { windowManager.updateViewLayout(container, layout) }
        if (visible) {
            composeView?.post {
                composeView?.requestFocus()
                val imm = getSystemService(INPUT_METHOD_SERVICE) as? InputMethodManager
                imm?.showSoftInput(composeView, InputMethodManager.SHOW_FORCED)
            }
        } else {
            hideKeyboard()
        }
    }

    private fun sendFromChatBar(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return
        PetChatBus.send(trimmed)
        setChatBar(false)
        say("已发送，等回复…")
    }

    private fun hideKeyboard() {
        composeView?.let { view ->
            val imm = getSystemService(INPUT_METHOD_SERVICE) as? InputMethodManager
            imm?.hideSoftInputFromWindow(view.windowToken, 0)
        }
    }

    private fun feed() {
        uiState.menuVisible = false
        say("好吃！再来一口嘛～")
    }

    private fun sing() {
        uiState.menuVisible = false
        say("♪ 啦啦啦～")
        speak("啦啦啦～")
    }

    private fun wake() {
        uiState.menuVisible = false
        say("起床啦！太阳都晒到桌面了。")
        speak("起床啦！")
    }

    private fun speak(text: String) {
        uiState.speaking = true
        runCatching { graph.tts.speak(text, graph.settings.current.ttsSpeed) }
        val token = ++speakingToken
        handler.postDelayed({ if (token == speakingToken) uiState.speaking = false }, 2600)
    }

    private fun say(text: String) {
        uiState.bubble = text
        val token = ++bubbleToken
        handler.postDelayed({ if (token == bubbleToken) uiState.bubble = null }, 2800)
    }

    private fun openLifeChat() {
        uiState.menuVisible = false
        // 直接写入空间，不依赖界面是否已创建。
        graph.settings.update { it.copy(space = com.iris.localai.data.Space.LIFE) }
        PetNavigator.go(PetDestination.LIFE_CHAT)
        launchApp()
    }

    private fun launchApp() {
        val intent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
        }
        runCatching { startActivity(intent) }
    }

    private fun showConfigMenu() {
        uiState.menuVisible = false
        val layout = params ?: return
        if (configMenu != null) {
            hideConfigMenu()
            return
        }
        val container = FrameLayout(this)
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(4), dp(4), dp(4), dp(4))
            background = GradientDrawable().apply {
                cornerRadius = dp(14).toFloat()
                setColor(Color.argb(232, 30, 31, 40))
            }
        }
        column.addView(configItem("全局设置") {
            hideConfigMenu()
            PetNavigator.go(PetDestination.SETTINGS)
            launchApp()
        })
        column.addView(configItem(if (settings.petDraggable) "固定" else "解锁") {
            val fixed = settings.petDraggable
            graph.settings.update { it.copy(petDraggable = !fixed) }
            hideConfigMenu()
            say(if (fixed) "已固定位置" else "已解锁拖动")
        })
        column.addView(configItem("桌宠配置") {
            hideConfigMenu()
            PetNavigator.go(PetDestination.PET_SETTINGS)
            launchApp()
        })
        container.addView(column)

        val metrics = resources.displayMetrics
        val menuWidth = dp(132)
        val menuParams = WindowManager.LayoutParams(
            menuWidth,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            val petCenter = layout.x + layout.width / 2
            x = if (petCenter < metrics.widthPixels / 2) {
                (layout.x + layout.width + dp(6)).coerceAtMost(metrics.widthPixels - menuWidth - dp(6))
            } else {
                (layout.x - menuWidth - dp(6)).coerceAtLeast(dp(6))
            }
            y = layout.y.coerceIn(0, (metrics.heightPixels - dp(80)).coerceAtLeast(0))
        }
        configMenu = container
        runCatching { windowManager.addView(container, menuParams) }
    }

    private fun configItem(label: String, onClick: () -> Unit): TextView = TextView(this).apply {
        text = label
        setTextColor(Color.WHITE)
        textSize = 13f
        gravity = Gravity.CENTER
        setPadding(dp(12), dp(9), dp(12), dp(9))
        background = GradientDrawable().apply {
            cornerRadius = dp(10).toFloat()
            setColor(Color.argb(0, 0, 0, 0))
        }
        setOnClickListener { onClick() }
    }

    private fun rebuildConfigMenu() {
        hideConfigMenu()
    }

    private fun hideConfigMenu() {
        configMenu?.let { view -> runCatching { windowManager.removeView(view) } }
        configMenu = null
    }

    private fun randomLine(): String {
        val name = graph.characters.get(settings.characterId)?.card?.charName ?: "我"
        return listOf(
            "今天也要一起加油哦！",
            "要摸摸头吗？",
            "我一直在桌面陪着你～",
            "$name 有点饿了……",
            "休息一下眼睛吧。",
        ).random()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        const val ACTION_SHOW = "com.iris.localai.pet.SHOW"
        const val ACTION_HIDE = "com.iris.localai.pet.HIDE"
        const val ACTION_REFRESH = "com.iris.localai.pet.REFRESH"
        private const val CHANNEL = "iris_pet"
        private const val NOTIFICATION_ID = 0x1F15
    }
}

/** 悬浮窗里的 Compose 需要一个可用的生命周期/保存状态宿主。 */
private class OverlayViewOwner : LifecycleOwner, ViewModelStoreOwner, SavedStateRegistryOwner {

    private val registry = LifecycleRegistry(this)
    private val store = ViewModelStore()
    private val controller = SavedStateRegistryController.create(this)

    override val lifecycle: Lifecycle get() = registry
    override val viewModelStore: ViewModelStore get() = store
    override val savedStateRegistry: SavedStateRegistry get() = controller.savedStateRegistry

    fun create() {
        controller.performRestore(null)
        registry.currentState = Lifecycle.State.RESUMED
    }

    fun destroy() {
        registry.currentState = Lifecycle.State.DESTROYED
        store.clear()
    }
}
