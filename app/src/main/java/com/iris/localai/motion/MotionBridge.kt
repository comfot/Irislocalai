package com.iris.localai.motion

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicInteger

data class MotionCommand(val seq: Long, val action: String)

/** Agent 工具与 3D 渲染页面之间的动作指令通道。 */
object MotionBridge {

    private val _command = MutableStateFlow<MotionCommand?>(null)
    val command: StateFlow<MotionCommand?> = _command.asStateFlow()

    private var seq = 0L
    private val activeViews = AtomicInteger(0)
    private val motionViews = AtomicInteger(0)

    fun registerView(motionEnabled: Boolean) {
        activeViews.incrementAndGet()
        if (motionEnabled) motionViews.incrementAndGet()
    }

    fun unregisterView(motionEnabled: Boolean) {
        activeViews.decrementAndGet()
        if (motionEnabled) motionViews.decrementAndGet()
    }

    fun hasActiveView(): Boolean = activeViews.get() > 0

    fun hasMotionView(): Boolean = motionViews.get() > 0

    fun play(action: String) {
        seq += 1
        _command.value = MotionCommand(seq, action)
    }
}
