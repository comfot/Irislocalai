package com.iris.localai.voice

import android.content.Context
import com.iris.localai.data.DownloadSource
import com.iris.localai.data.HuggingFaceClient
import com.iris.localai.data.VoiceModelPack
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File

enum class VoiceDownloadStatus(val label: String) {
    QUEUED("排队中"),
    RUNNING("下载中"),
    COMPLETED("已安装"),
    FAILED("失败"),
    CANCELLED("已取消"),
}

data class VoiceDownloadTask(
    val packId: String,
    val title: String,
    val totalBytes: Long = 0,
    val downloadedBytes: Long = 0,
    val status: VoiceDownloadStatus = VoiceDownloadStatus.QUEUED,
    val message: String = "",
    val filesDone: Int = 0,
    val filesTotal: Int = 0,
) {
    val progress: Float
        get() = if (totalBytes > 0) {
            (downloadedBytes.toFloat() / totalBytes).coerceIn(0f, 1f)
        } else {
            0f
        }
}

class VoiceDownloadManager(
    private val context: Context,
    private val scope: CoroutineScope,
    private val client: HuggingFaceClient,
    private val store: VoiceModelStore,
) {

    private val _tasks = MutableStateFlow<List<VoiceDownloadTask>>(emptyList())
    val tasks: StateFlow<List<VoiceDownloadTask>> = _tasks.asStateFlow()

    private val jobs = mutableMapOf<String, Job>()

    fun enqueue(pack: VoiceModelPack, source: DownloadSource = DownloadSource.AUTO) {
        if (store.isInstalled(pack.id)) return
        if (jobs.containsKey(pack.id)) return

        update(pack.id) {
            VoiceDownloadTask(
                packId = pack.id,
                title = pack.title,
                totalBytes = pack.approximateBytes,
                status = VoiceDownloadStatus.QUEUED,
            )
        }

        jobs[pack.id] = scope.launch {
            try {
                val manifest = VoiceManifests.read(context, pack)
                val files = manifest.files
                if (files.isEmpty()) throw IllegalStateException("模型清单为空：${pack.title}")
                val total = if (manifest.totalBytes > 0) manifest.totalBytes else pack.approximateBytes
                update(pack.id) {
                    it.copy(
                        status = VoiceDownloadStatus.RUNNING,
                        totalBytes = total,
                        filesTotal = files.size,
                    )
                }

                var base = 0L
                files.forEachIndexed { index, file ->
                    if (jobs[pack.id]?.isCancelled == true) {
                        throw CancellationException("cancelled")
                    }
                    client.download(
                        repoId = manifest.repo,
                        fileName = file.path,
                        destDir = store.dirFor(pack),
                        source = source,
                        preservePath = true,
                        revision = manifest.revision,
                    ) { done, _ ->
                        update(pack.id) {
                            it.copy(
                                downloadedBytes = base + done,
                                filesDone = index,
                            )
                        }
                    }.getOrThrow()
                    val downloadedFile = File(store.dirFor(pack), file.path)
                    if (file.size > 0 && downloadedFile.length() != file.size) {
                        throw IllegalStateException(
                            "${file.path} 大小不匹配（期望 ${file.size}，实际 ${downloadedFile.length()}）",
                        )
                    }
                    base += if (file.size > 0) file.size else downloadedFile.length()
                    update(pack.id) {
                        it.copy(downloadedBytes = base, filesDone = index + 1)
                    }
                }

                store.markInstalled(pack.id)
                update(pack.id) {
                    it.copy(
                        status = VoiceDownloadStatus.COMPLETED,
                        downloadedBytes = total,
                        filesDone = files.size,
                    )
                }
            } catch (e: CancellationException) {
                update(pack.id) {
                    it.copy(status = VoiceDownloadStatus.CANCELLED, message = "已取消")
                }
                throw e
            } catch (e: Exception) {
                val cancelled = jobs[pack.id]?.isCancelled == true
                update(pack.id) {
                    it.copy(
                        status = if (cancelled) VoiceDownloadStatus.CANCELLED else VoiceDownloadStatus.FAILED,
                        message = if (cancelled) "已取消" else (e.message ?: "下载失败"),
                    )
                }
            } finally {
                jobs.remove(pack.id)
            }
        }
    }

    fun cancel(packId: String) {
        jobs[packId]?.cancel()
        update(packId) { it.copy(status = VoiceDownloadStatus.CANCELLED, message = "已取消") }
    }

    fun taskOf(packId: String): VoiceDownloadTask? =
        _tasks.value.firstOrNull { it.packId == packId }

    private fun update(packId: String, transform: (VoiceDownloadTask) -> VoiceDownloadTask) {
        _tasks.update { list ->
            val existing = list.firstOrNull { it.packId == packId }
            if (existing == null) {
                list + transform(
                    VoiceDownloadTask(packId = packId, title = packId),
                )
            } else {
                list.map { if (it.packId == packId) transform(it) else it }
            }
        }
    }

    private fun isMetadata(name: String): Boolean {
        val lower = name.lowercase()
        return lower == ".gitattributes" ||
            lower.startsWith("readme") ||
            lower.startsWith("license") ||
            lower.startsWith(".git")
    }
}
