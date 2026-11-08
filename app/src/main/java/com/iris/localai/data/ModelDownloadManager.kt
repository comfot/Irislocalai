package com.iris.localai.data

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.util.UUID

enum class DownloadStatus(val label: String) {
    QUEUED("排队中"),
    RUNNING("下载中"),
    COMPLETED("已完成"),
    FAILED("失败"),
    CANCELLED("已取消"),
}

data class DownloadTask(
    val id: String,
    val repoId: String,
    val fileName: String,
    val source: DownloadSource = DownloadSource.AUTO,
    val origin: ModelSource = ModelSource.HUGGING_FACE,
    val totalBytes: Long = 0,
    val downloadedBytes: Long = 0,
    val status: DownloadStatus = DownloadStatus.QUEUED,
    val message: String = "",
    val path: String = "",
) {
    val progress: Float
        get() = if (totalBytes > 0) {
            (downloadedBytes.toFloat() / totalBytes).coerceIn(0f, 1f)
        } else {
            0f
        }
}

class ModelDownloadManager(
    context: Context,
    private val scope: CoroutineScope,
    private val client: HuggingFaceClient,
    private val modelScope: ModelScopeClient = ModelScopeClient(),
) {

    private val modelsDir = File(context.filesDir, "models").apply { mkdirs() }

    private val _tasks = MutableStateFlow<List<DownloadTask>>(emptyList())
    val tasks: StateFlow<List<DownloadTask>> = _tasks.asStateFlow()

    private val jobs = mutableMapOf<String, Job>()

    fun modelsDir(): File = modelsDir

    fun enqueue(
        repoId: String,
        fileName: String,
        size: Long,
        source: DownloadSource = DownloadSource.AUTO,
        origin: ModelSource = ModelSource.HUGGING_FACE,
    ): DownloadTask {
        val existing = _tasks.value.firstOrNull { task ->
            task.repoId == repoId && task.fileName == fileName && task.origin == origin &&
                (task.status == DownloadStatus.QUEUED || task.status == DownloadStatus.RUNNING)
        }
        if (existing != null) return existing
        val id = "dl-${UUID.randomUUID()}"
        val task = DownloadTask(
            id = id,
            repoId = repoId,
            fileName = fileName,
            source = source,
            origin = origin,
            totalBytes = size,
        )
        _tasks.update { it + task }

        jobs[id] = scope.launch {
            update(id) { it.copy(status = DownloadStatus.RUNNING) }
            val result = if (origin == ModelSource.MODELSCOPE) {
                modelScope.download(repoId, fileName, modelsDir) { done, total ->
                    update(id) {
                        it.copy(
                            downloadedBytes = done,
                            totalBytes = if (total > 0) total else it.totalBytes,
                        )
                    }
                }
            } else {
                client.download(repoId, fileName, modelsDir, source) { done, total ->
                    update(id) {
                        it.copy(
                            downloadedBytes = done,
                            totalBytes = if (total > 0) total else it.totalBytes,
                        )
                    }
                }
            }
            result.onSuccess { file ->
                update(id) {
                    it.copy(
                        status = DownloadStatus.COMPLETED,
                        downloadedBytes = file.length(),
                        totalBytes = file.length(),
                        path = file.absolutePath,
                    )
                }
            }.onFailure { error ->
                val cancelled = jobs[id]?.isCancelled == true
                if (cancelled) {
                    update(id) { it.copy(status = DownloadStatus.CANCELLED) }
                } else {
                    update(id) {
                        it.copy(
                            status = DownloadStatus.FAILED,
                            message = error.message ?: "下载失败",
                        )
                    }
                }
            }
            jobs.remove(id)
        }
        return task
    }

    fun cancel(id: String) {
        jobs[id]?.cancel()
        update(id) { it.copy(status = DownloadStatus.CANCELLED) }
    }

    private fun update(id: String, transform: (DownloadTask) -> DownloadTask) {
        _tasks.update { list ->
            list.map { if (it.id == id) transform(it) else it }
        }
    }
}
