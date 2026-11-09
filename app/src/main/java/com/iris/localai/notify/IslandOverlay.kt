package com.iris.localai.notify

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

/**
 * 「灵动岛」式胶囊：顶部居中的悬浮窗，短暂显示最近一条回复。
 * 用应用上下文直接挂 overlay 窗口，不依赖前台服务，权限不足时静默跳过。
 */
object IslandOverlay {

    private val handler = Handler(Looper.getMainLooper())
    private var view: View? = null
    private var windowManager: WindowManager? = null
    private val hideRunnable = Runnable { hide() }

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
