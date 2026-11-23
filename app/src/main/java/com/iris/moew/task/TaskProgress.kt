package com.iris.moew.task

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * 统一任务进度：下载、生图/生成、模型加载各开一槽，灵动岛与通知读同一份。
 * 纯 JVM 可单测；stop 回调由任务方注册（下载=cancel、生成=stop）。
 */
object TaskProgress {

    enum class Kind { IMAGE_GEN, CHAT, DOWNLOAD, LOAD, OTHER }
    enum class Phase { RUNNING, DONE, STOPPED, FAILED }

    data class Task(
        val id: String,
        val kind: Kind,
        val title: String,
        val subtitle: String = "",
        val progress: Float? = null,
        val phase: Phase = Phase.RUNNING,
        val startedAt: Long = System.currentTimeMillis(),
    )

    interface Surface {
        fun onTasksChanged(tasks: List<Task>)
    }

    private val slots = HashMap<String, MutableStateFlow<Task>>()
    private val surfaces = ArrayList<Surface>()
    private val lock = Any()

    private val _active = MutableStateFlow<List<Task>>(emptyList())

    /** 正在进行的任务（不含已完成/失败）。 */
    val active: StateFlow<List<Task>> = _active.asStateFlow()

    fun addSurface(surface: Surface) = synchronized(lock) {
        surfaces += surface
        surface.onTasksChanged(_active.value)
    }

    fun removeSurface(surface: Surface) = synchronized(lock) {
        surfaces -= surface
    }

    fun begin(id: String, kind: Kind, title: String, subtitle: String = "", stop: (() -> Unit)? = null) {
        if (stop != null) stopCallbacks[id] = stop
        val task = Task(id = id, kind = kind, title = title, subtitle = subtitle)
        slot(id).value = task
        publish()
    }

    fun progress(id: String, progress: Float?, subtitle: String? = null) {
        val flow = synchronized(lock) { slots[id] } ?: return
        flow.value = flow.value.copy(
            progress = progress,
            subtitle = subtitle ?: flow.value.subtitle,
        )
        publish()
    }

    fun finish(id: String, phase: Phase, subtitle: String = "") {
        val flow = synchronized(lock) { slots[id] } ?: return
        flow.value = flow.value.copy(phase = phase, subtitle = subtitle)
        stopCallbacks.remove(id)
        publish()
        // 完成条目短暂保留后清理，避免列表增长。
        cleaner.schedule({
            synchronized(lock) {
                val task = slots[id]?.value
                if (task != null && task.phase != Phase.RUNNING) {
                    slots.remove(id)
                    publishLocked()
                }
            }
        }, 3, TimeUnit.SECONDS)
    }

    fun requestStop(id: String): Boolean {
        val stop = stopCallbacks[id] ?: return false
        stop()
        return true
    }

    private val stopCallbacks = HashMap<String, () -> Unit>()
    private val cleaner = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "task-progress-cleaner").apply { isDaemon = true }
    }

    private fun slot(id: String): MutableStateFlow<Task> = synchronized(lock) {
        slots.getOrPut(id) { MutableStateFlow(Task(id = id, kind = Kind.OTHER, title = "")) }
    }

    private fun publish() = synchronized(lock) {
        publishLocked()
    }

    private fun publishLocked() {
        val running = slots.values.map { it.value }.filter { it.phase == Phase.RUNNING }
        _active.value = running
        surfaces.forEach { it.onTasksChanged(running) }
    }
}
