package com.iris.localai.plugin

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.WindowManager
import android.widget.FrameLayout
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
import com.iris.localai.R
import com.iris.localai.ui.settings.PluginWidgetSurface

/**
 * 插件 desktop_widget 的宿主：以悬浮窗近似桌面小部件。
 * Android AppWidget 的 RemoteViews 无法渲染 Compose DSL，因此这里明确采用应用悬浮窗。
 */
class PluginWidgetService : Service() {

    private lateinit var graph: AppGraph
    private lateinit var windowManager: WindowManager
    private var root: FrameLayout? = null
    private var owner: WidgetViewOwner? = null
    private var params: WindowManager.LayoutParams? = null

    override fun onCreate() {
        super.onCreate()
        graph = (application as IrisApp).graph
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        startForegroundWithNotification()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val pluginId = intent?.getStringExtra(EXTRA_PLUGIN_ID).orEmpty()
        val moduleId = intent?.getStringExtra(EXTRA_MODULE_ID).orEmpty()
        val title = intent?.getStringExtra(EXTRA_TITLE).orEmpty()
        if (pluginId.isBlank() || moduleId.isBlank()) {
            stopSelf()
            return START_NOT_STICKY
        }
        runCatching { show(pluginId, moduleId, title) }.onFailure { stopSelf() }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        closeOverlay()
        super.onDestroy()
    }

    private fun closeOverlay() {
        root?.let { runCatching { windowManager.removeView(it) } }
        root = null
        owner?.destroy()
        owner = null
        params = null
    }

    private fun show(pluginId: String, moduleId: String, title: String) {
        if (!Settings.canDrawOverlays(this)) {
            stopSelf()
            return
        }
        val plugin = graph.plugins.plugins.value.firstOrNull { it.id == pluginId } ?: run {
            stopSelf()
            return
        }
        closeOverlay()
        val density = resources.displayMetrics.density
        val width = (340 * density).toInt()
        val height = (460 * density).toInt()
        val layout = WindowManager.LayoutParams(
            width,
            height,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = (48 * density).toInt()
            y = (160 * density).toInt()
        }
        val viewOwner = WidgetViewOwner().also { it.create() }
        val container = FrameLayout(this)
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
                PluginWidgetSurface(
                    plugin = plugin,
                    moduleId = moduleId,
                    title = title,
                    renderUi = { graph.pluginRuntime.renderUi(plugin, moduleId) },
                    dispatchUiEvent = { eventId, value ->
                        graph.pluginRuntime.dispatchUiEvent(plugin, moduleId, eventId, value)
                    },
                    callUiController = { controllerId, interfaceName, methodName, args ->
                        graph.pluginRuntime.callUiController(
                            plugin, moduleId, controllerId, interfaceName, methodName, args,
                        )
                    },
                    callUiHandler = { eventId, payload ->
                        graph.pluginRuntime.callUiHandler(plugin, moduleId, eventId, payload)
                    },
                    onClose = { stopSelf() },
                    onDrag = { dx, dy ->
                        val current = params ?: return@PluginWidgetSurface
                        current.x += dx.toInt()
                        current.y += dy.toInt()
                        root?.let { runCatching { windowManager.updateViewLayout(it, current) } }
                    },
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
        owner = viewOwner
        params = layout
        windowManager.addView(container, layout)
    }

    private fun startForegroundWithNotification() {
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, "插件浮窗", NotificationManager.IMPORTANCE_LOW).apply {
                description = "插件桌面组件以悬浮窗运行"
            },
        )
        val notification = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("插件浮窗已开启")
            .setContentText("拖动标题栏移动，右上角关闭")
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    companion object {
        private const val CHANNEL = "iris_plugin_widget"
        private const val NOTIFICATION_ID = 4812
        private const val EXTRA_PLUGIN_ID = "plugin_id"
        private const val EXTRA_MODULE_ID = "module_id"
        private const val EXTRA_TITLE = "title"

        fun start(context: Context, pluginId: String, moduleId: String, title: String) {
            val intent = Intent(context, PluginWidgetService::class.java).apply {
                putExtra(EXTRA_PLUGIN_ID, pluginId)
                putExtra(EXTRA_MODULE_ID, moduleId)
                putExtra(EXTRA_TITLE, title)
            }
            context.startService(intent)
        }
    }
}

private class WidgetViewOwner : LifecycleOwner, ViewModelStoreOwner, SavedStateRegistryOwner {

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
