package com.iris.moew.data

import android.content.Context
import com.iris.moew.storage.AppStorage
import com.iris.moew.task.TaskProgress
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
    private val storage: AppStorage,
    private val modelScope: ModelScopeClient = ModelScopeClient(),
) {

    private val modelsDir = storage.models

    private val _tasks = MutableStateFlow<List<DownloadTask>>(emptyList())
    val tasks: StateFlow<List<DownloadTask>> = _tasks.asStateFlow()

    private val jobs = mutableMapOf<String, Job>()

    fun modelsDir(): File = modelsDir

    fun modelsDirs(): List<File> = storage.readDirs("models")

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
            TaskProgress.begin(id, TaskProgress.Kind.DOWNLOAD, fileName) { cancel(id) }
            val report: (Long, Long) -> Unit = { done, total ->
                update(id) {
                    it.copy(
                        downloadedBytes = done,
                        totalBytes = if (total > 0) total else it.totalBytes,
                    )
                }
                val fraction = if (total > 0) done.toFloat() / total else null
                TaskProgress.progress(
                    id,
                    fraction?.takeIf { f -> f.isFinite() },
                    if (total > 0) "${if (fraction != null) "${(fraction * 100).toInt()}% · " else ""}${formatBytes(done)} / ${formatBytes(total)}" else formatBytes(done),
                )
            }
            val result = if (origin == ModelSource.MODELSCOPE) {
                modelScope.download(repoId, fileName, modelsDir, onProgress = report)
            } else {
                client.download(repoId, fileName, modelsDir, source, onProgress = report)
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
                TaskProgress.finish(id, TaskProgress.Phase.DONE, "已完成 ${formatBytes(file.length())}")
            }.onFailure { error ->
                val cancelled = jobs[id]?.isCancelled == true
                if (cancelled) {
                    update(id) { it.copy(status = DownloadStatus.CANCELLED) }
                    TaskProgress.finish(id, TaskProgress.Phase.STOPPED, "已取消")
                } else {
                    update(id) {
                        it.copy(
                            status = DownloadStatus.FAILED,
                            message = error.message ?: "下载失败",
                        )
                    }
                    TaskProgress.finish(id, TaskProgress.Phase.FAILED, error.message ?: "下载失败")
                }
            }
            jobs.remove(id)
        }
        return task
    }

    private fun formatBytes(bytes: Long): String = when {
        bytes >= 1024L * 1024 * 1024 -> "%.2f GB".format(bytes / 1024f / 1024 / 1024)
        bytes >= 1024L * 1024 -> "%.1f MB".format(bytes / 1024f / 1024)
        bytes >= 1024L -> "%.0f KB".format(bytes / 1024f)
        else -> "$bytes B"
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
