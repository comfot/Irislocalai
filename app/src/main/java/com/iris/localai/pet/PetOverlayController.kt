package com.iris.localai.pet

import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** 桌宠服务的启停入口；UI 只观察 [running]，不直接持有悬浮窗。 */
object PetOverlayController {

    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running.asStateFlow()

    fun start(context: Context) {
        val intent = Intent(context, PetOverlayService::class.java)
            .setAction(PetOverlayService.ACTION_SHOW)
        if (Build.VERSION.SDK_INT >= 26) {
            ContextCompat.startForegroundService(context, intent)
        } else {
            context.startService(intent)
        }
    }

    fun stop(context: Context) {
        context.startService(
            Intent(context, PetOverlayService::class.java)
                .setAction(PetOverlayService.ACTION_HIDE),
        )
    }

    internal fun setRunning(value: Boolean) {
        _running.value = value
    }
}
