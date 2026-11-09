package com.iris.localai.notify

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationManagerCompat
import com.iris.localai.MainActivity
import com.iris.localai.R
import com.iris.localai.data.SettingsRepository

/** 应用是否在前台；前台时不重复弹通知。 */
object AppForeground {
    @Volatile
    var visible: Boolean = false
}

/**
 * 助手回复完成后的通知：对话栏通知、锁屏可见性、悬浮（高优先级）与灵动岛胶囊。
 * 通道拆成「普通」与「悬浮」两个，因为通道重要性创建后不可修改。
 */
class ChatNotifier(
    private val context: Context,
    private val settings: SettingsRepository,
) {

    private val manager = context.getSystemService(NotificationManager::class.java)
    private var nextId = 1000

    init {
        createChannels()
    }

    private fun createChannels() {
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_QUIET, "对话通知", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "助手回复完成提醒"
            },
        )
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ALERT, "对话通知（悬浮）", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "以悬浮横幅显示助手回复"
            },
        )
    }

    fun notifyReply(title: String, body: String) {
        if (AppForeground.visible) return
        val current = settings.current
        if (!current.notifyReplies) return
        val text = body.trim()
        if (text.isEmpty()) return
        val channel = if (current.notifyHeadsUp) CHANNEL_ALERT else CHANNEL_QUIET
        val intent = Intent(context, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
        }
        val pending = PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = Notification.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setContentIntent(pending)
            .setAutoCancel(true)
            .setVisibility(
                if (current.notifyLockScreen) {
                    Notification.VISIBILITY_PUBLIC
                } else {
                    Notification.VISIBILITY_SECRET
                },
            )
            .build()
        runCatching { NotificationManagerCompat.from(context).notify(nextId++, notification) }
        if (current.notifyIsland) {
            IslandOverlay.show(context, title, text)
        }
    }

    private companion object {
        const val CHANNEL_QUIET = "iris_chat_quiet"
        const val CHANNEL_ALERT = "iris_chat_alert"
    }
}
