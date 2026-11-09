package com.iris.localai.data

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap

enum class AssetDownloadState(val label: String) {
    IDLE("未下载"),
    RUNNING("下载中"),
    DONE("已下载"),
    FAILED("失败"),
    CANCELED("已取消"),
}

data class AssetDownloadTask(
    val key: String,
    val title: String,
    val state: AssetDownloadState = AssetDownloadState.IDLE,
    val downloadedBytes: Long = 0,
    val totalBytes: Long = 0,
    val error: String? = null,
) {
    val progress: Float
        get() = if (totalBytes > 0) {
            (downloadedBytes.toFloat() / totalBytes).coerceIn(0f, 1f)
        } else {
            0f
        }
}

/**
 * 通用素材下载：支持断点续传、进度、取消与自定义请求头（私有 GitHub 仓库需要 Token）。
 * 下载先写 `.part`，完成后原子改名，避免半包被当作可用素材。
 */
class AssetDownloadManager(
    context: Context,
    private val scope: CoroutineScope,
) {
    private val appContext = context.applicationContext
    private val _tasks = MutableStateFlow<List<AssetDownloadTask>>(emptyList())
    val tasks: StateFlow<List<AssetDownloadTask>> = _tasks.asStateFlow()
    private val jobs = ConcurrentHashMap<String, Job>()

    fun task(key: String): AssetDownloadTask? = _tasks.value.firstOrNull { it.key == key }

    fun isDownloaded(destDir: File, fileName: String): Boolean {
        val file = File(destDir, fileName)
        return file.isFile && file.length() > 0
    }

    fun enqueue(
        key: String,
        title: String,
        url: String,
        destDir: File,
        fileName: String,
        headers: Map<String, String> = emptyMap(),
        force: Boolean = false,
    ) {
        if (jobs[key]?.isActive == true) return
        val target = File(destDir, fileName)
        if (!force && target.isFile && target.length() > 0) {
            upsert(AssetDownloadTask(key, title, AssetDownloadState.DONE, target.length(), target.length()))
            return
        }
        destDir.mkdirs()
        upsert(AssetDownloadTask(key, title, AssetDownloadState.RUNNING))
        jobs[key] = scope.launch {
            runCatching {
                download(url, target, headers) { done, total ->
                    upsert(AssetDownloadTask(key, title, AssetDownloadState.RUNNING, done, total))
                }
            }.onSuccess {
                upsert(
                    AssetDownloadTask(
                        key,
                        title,
                        AssetDownloadState.DONE,
                        it,
                        it,
                    ),
                )
            }.onFailure { error ->
                val canceled = error is kotlinx.coroutines.CancellationException
                upsert(
                    AssetDownloadTask(
                        key,
                        title,
                        if (canceled) AssetDownloadState.CANCELED else AssetDownloadState.FAILED,
                        error = error.message?.take(160) ?: "下载失败",
                    ),
                )
            }
            jobs.remove(key, coroutineContext[Job])
        }
    }

    fun cancel(key: String) {
        jobs[key]?.cancel()
        jobs.remove(key)
        _tasks.update { list ->
            list.map {
                if (it.key == key && it.state == AssetDownloadState.RUNNING) {
                    it.copy(state = AssetDownloadState.CANCELED)
                } else {
                    it
                }
            }
        }
    }

    fun removeTask(key: String) {
        cancel(key)
        _tasks.update { list -> list.filterNot { it.key == key } }
    }

    private fun upsert(task: AssetDownloadTask) {
        _tasks.update { list ->
            val index = list.indexOfFirst { it.key == task.key }
            if (index < 0) list + task else list.toMutableList().also { it[index] = task }
        }
    }

    private suspend fun download(
        url: String,
        target: File,
        headers: Map<String, String>,
        onProgress: (Long, Long) -> Unit,
    ): Long = withContext(Dispatchers.IO) {
        val part = File(target.parentFile, target.name + ".part")
        var existing = if (part.isFile) part.length() else 0L
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 20_000
            readTimeout = 60_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "IRIS-LocalAI")
            headers.forEach { (name, value) -> setRequestProperty(name, value) }
            if (existing > 0) setRequestProperty("Range", "bytes=$existing-")
        }
        try {
            val code = connection.responseCode
            if (code !in 200..299) {
                val message = connection.errorStream?.bufferedReader()?.use { it.readText() }
                    ?.take(300)?.trim()
                error("HTTP $code${message?.takeIf { it.isNotBlank() }?.let { "：$it" }.orEmpty()}")
            }
            val append = code == 206 && existing > 0
            if (!append) {
                existing = 0
                part.delete()
            }
            val length = connection.contentLengthLong.coerceAtLeast(0)
            val total = if (length > 0) existing + length else 0
            connection.inputStream.use { input ->
                java.io.FileOutputStream(part, append).use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var done = existing
                    var lastReport = 0L
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val read = input.read(buffer)
                        if (read <= 0) break
                        output.write(buffer, 0, read)
                        done += read
                        val now = System.currentTimeMillis()
                        if (now - lastReport > 180) {
                            lastReport = now
                            onProgress(done, total)
                        }
                    }
                    onProgress(done, total)
                    done
                }
            }.also { written ->
                if (target.exists()) target.delete()
                if (!part.renameTo(target)) {
                    part.copyTo(target, overwrite = true)
                    part.delete()
                }
                written
            }
        } finally {
            connection.disconnect()
        }
    }
}
