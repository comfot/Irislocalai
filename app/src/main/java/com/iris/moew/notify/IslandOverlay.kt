package com.iris.moew.notify

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import com.iris.moew.task.TaskProgress

/**
 * 「灵动岛」式胶囊：顶部居中的悬浮窗。
 * - [show]：短暂显示最近一条回复（一次性，自动隐藏）；
 * - [start]：订阅 TaskProgress.active，有进行中的下载/生成/加载时显示常驻任务胶囊，
 *   可直接停止任务。权限不足时静默跳过。
 */
object IslandOverlay {

    private const val REFRESH_MS = 500L

    private val handler = Handler(Looper.getMainLooper())
    private var view: View? = null
    private var windowManager: WindowManager? = null
    private val hideRunnable = Runnable { hide() }

    private var taskView: View? = null
    private var taskWindowManager: WindowManager? = null
    private var taskContext: Context? = null
    private var taskTitle: TextView? = null
    private var taskSubtitle: TextView? = null
    private var taskStop: TextView? = null
    private var taskBadge: TextView? = null
    private var started = false

    /** 应用启动时调用一次；权限不足时轮询也不会挂窗口。 */
    fun start(context: Context) {
        if (started) return
        started = true
        taskContext = context.applicationContext
        handler.postDelayed(object : Runnable {
            override fun run() {
                refreshTasks()
                handler.postDelayed(this, REFRESH_MS)
            }
        }, REFRESH_MS)
    }

    private fun refreshTasks() {
        val context = taskContext ?: return
        if (!Settings.canDrawOverlays(context)) return
        val tasks = TaskProgress.active.value
        if (tasks.isEmpty()) {
            removeTaskView()
            return
        }
        val current = tasks.first()
        handler.post {
            val manager = context.getSystemService(WindowManager::class.java) ?: return@post
            val density = context.resources.displayMetrics.density
            fun dp(value: Int) = (value * density).toInt()

            if (taskView == null) {
                buildTaskView(context, manager, ::dp)
            }
            taskBadge?.text = when (current.kind) {
                TaskProgress.Kind.DOWNLOAD -> "↓"
                TaskProgress.Kind.IMAGE_GEN -> "🎨"
                TaskProgress.Kind.CHAT -> "✎"
                TaskProgress.Kind.LOAD -> "🧠"
                TaskProgress.Kind.OTHER -> "•"
            }
            taskTitle?.text = current.title
            taskSubtitle?.text = when {
                current.subtitle.isNotBlank() -> current.subtitle
                current.progress != null -> "${(current.progress.coerceIn(0f, 1f) * 100).toInt()}%"
                else -> "进行中…"
            }
            taskStop?.visibility = if (tasks.size == 1 && current.progress != null) View.VISIBLE else View.GONE
            taskStop?.setOnClickListener { TaskProgress.requestStop(current.id) }
        }
    }

    private fun buildTaskView(context: Context, manager: WindowManager, dp: (Int) -> Int) {
        val capsule = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(8), dp(12), dp(8))
            background = GradientDrawable().apply {
                cornerRadius = dp(26).toFloat()
                setColor(Color.argb(238, 18, 19, 26))
            }
        }
        val badge = TextView(context).apply {
            setTextColor(Color.WHITE)
            textSize = 13f
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.argb(255, 64, 110, 200))
            }
        }
        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), 0, 0, 0)
        }
        val title = TextView(context).apply {
            setTextColor(Color.WHITE)
            textSize = 12f
            setSingleLine(true)
            ellipsize = TextUtils.TruncateAt.END
            maxWidth = dp(200)
        }
        val subtitle = TextView(context).apply {
            setTextColor(Color.parseColor("#D0D4DD"))
            textSize = 11f
            setSingleLine(true)
            ellipsize = TextUtils.TruncateAt.END
            maxWidth = dp(200)
        }
        column.addView(title)
        column.addView(subtitle)
        val stop = TextView(context).apply {
            text = "停止"
            setTextColor(Color.parseColor("#FF9A8B"))
            textSize = 12f
            setPadding(dp(8), dp(4), dp(8), dp(4))
        }
        capsule.addView(badge, LinearLayout.LayoutParams(dp(26), dp(26)))
        capsule.addView(column)
        capsule.addView(stop)
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = dp(10)
        }
        runCatching {
            manager.addView(capsule, params)
            taskView = capsule
            taskWindowManager = manager
            taskTitle = title
            taskSubtitle = subtitle
            taskStop = stop
            taskBadge = badge
        }
    }

    private fun removeTaskView() {
        val current = taskView ?: return
        taskWindowManager?.let { manager -> runCatching { manager.removeView(current) } }
        taskView = null
        taskWindowManager = null
        taskTitle = null
        taskSubtitle = null
        taskStop = null
        taskBadge = null
    }

    fun show(context: Context, title: String, body: String) {
        if (!Settings.canDrawOverlays(context)) return
        val appContext = context.applicationContext
        val manager = appContext.getSystemService(WindowManager::class.java) ?: return
        handler.post {
            hide()
            val density = appContext.resources.displayMetrics.density
            fun dp(value: Int) = (value * density).toInt()

            val capsule = LinearLayout(appContext).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(12), dp(8), dp(16), dp(8))
                background = GradientDrawable().apply {
                    cornerRadius = dp(26).toFloat()
                    setColor(Color.argb(238, 18, 19, 26))
                }
            }
            val badge = TextView(appContext).apply {
                text = title.take(1)
                setTextColor(Color.WHITE)
                textSize = 13f
                gravity = Gravity.CENTER
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(Color.argb(255, 64, 110, 200))
                }
            }
            val column = LinearLayout(appContext).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(10), 0, 0, 0)
            }
            column.addView(
                TextView(appContext).apply {
                    text = title
                    setTextColor(Color.WHITE)
                    textSize = 12f
                    setSingleLine(true)
                    ellipsize = TextUtils.TruncateAt.END
                },
            )
            column.addView(
                TextView(appContext).apply {
                    text = body
                    setTextColor(Color.parseColor("#D0D4DD"))
                    textSize = 11f
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                    maxWidth = dp(220)
                },
            )
            capsule.addView(badge, LinearLayout.LayoutParams(dp(26), dp(26)))
            capsule.addView(column)

            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT,
            ).apply {
                gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                y = dp(10)
            }
            runCatching {
                manager.addView(capsule, params)
                view = capsule
                windowManager = manager
                handler.postDelayed(hideRunnable, 4200)
            }
        }
    }

    private fun hide() {
        handler.removeCallbacks(hideRunnable)
        view?.let { windowManager?.let { manager -> runCatching { manager.removeView(it) } } }
        view = null
        windowManager = null
    }
}
