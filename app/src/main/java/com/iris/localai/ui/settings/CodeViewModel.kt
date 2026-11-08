package com.iris.localai.ui.settings

import android.app.Application
import android.os.Build
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.iris.localai.IrisApp
import com.iris.localai.code.CodeImageCatalog
import com.iris.localai.code.CodeSettings
import com.iris.localai.proot.ProotPaths
import com.iris.localai.proot.ProotRuntime
import com.iris.localai.proot.RootfsInstaller
import com.iris.localai.proot.RootfsProgress
import com.iris.localai.proot.RootfsSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

data class ProotUiState(
    val loaded: Boolean = false,
    val installed: Boolean = false,
    val prootAvailable: Boolean = false,
    val arch: String = "",
    val rootfsPath: String = "",
    val rootfsShell: String = "",
    val rootfsBytes: Long = 0L,
    val installing: Boolean = false,
    val progressText: String = "",
    val error: String? = null,
)

class CodeViewModel(application: Application) : AndroidViewModel(application) {

    private val store = (application as IrisApp).graph.code

    val settings = store.settings

    private val prootPaths = ProotPaths(File(application.filesDir, "proot"))
    private val installer = RootfsInstaller(prootPaths)
    private val runtime = ProotRuntime(application)

    private val _prootState = MutableStateFlow(ProotUiState())
    val prootState: StateFlow<ProotUiState> = _prootState.asStateFlow()
    private var installJob: Job? = null

    init {
        refreshProot()
    }

    fun update(transform: (CodeSettings) -> CodeSettings) = store.update(transform)

    fun refreshProot() {
        val imageId = store.current.imageId
        viewModelScope.launch {
            val status = withContext(Dispatchers.IO) { runtime.status(imageId) }
            _prootState.update {
                it.copy(
                    loaded = true,
                    installed = status.rootfsInstalled,
                    prootAvailable = status.prootAvailable,
                    arch = status.arch,
                    rootfsPath = status.rootfsPath,
                    rootfsShell = status.rootfsShell,
                    rootfsBytes = status.rootfsBytes,
                    error = null,
                )
            }
        }
    }

    fun installProot() {
        if (_prootState.value.installing) return
        if (!_prootState.value.prootAvailable) {
            _prootState.update { it.copy(error = "PRoot 启动器缺失：当前安装包不支持 ${it.arch.ifBlank { "此 ABI" }}") }
            return
        }
        val current = store.current
        val source = resolveSource(current) ?: return
        installJob = viewModelScope.launch {
            _prootState.update {
                it.copy(installing = true, progressText = "准备安装…", error = null)
            }
            try {
                runInterruptible(Dispatchers.IO) {
                    installer.install(current.imageId, source) { progress ->
                        _prootState.update { state ->
                            state.copy(progressText = progress.describe())
                        }
                    }
                }
                _prootState.update { it.copy(installing = false, progressText = "") }
                refreshProot()
            } catch (e: CancellationException) {
                _prootState.update {
                    it.copy(installing = false, progressText = "", error = "安装已取消")
                }
                throw e
            } catch (e: Exception) {
                _prootState.update {
                    it.copy(
                        installing = false,
                        progressText = "",
                        error = e.message ?: "rootfs 安装失败",
                    )
                }
            }
        }
    }

    fun cancelInstall() {
        installJob?.cancel()
        installJob = null
    }

    override fun onCleared() {
        installJob?.cancel()
        super.onCleared()
    }

    private fun resolveSource(settings: CodeSettings): RootfsSource? {
        val localPath = settings.imagePath.trim()
        if (localPath.isNotEmpty()) {
            val file = File(localPath)
            if (!file.isFile) {
                _prootState.update { it.copy(error = "本地 rootfs 文件不存在：$localPath") }
                return null
            }
            return RootfsSource.LocalFile(file)
        }
        val url = settings.imageUrl.trim().ifBlank {
            CodeImageCatalog.resolveUrl(CodeImageCatalog.of(settings.imageId), abi())
        }
        if (url.isBlank()) {
            _prootState.update { it.copy(error = "请填写镜像下载地址，或指定本地 tar.gz 文件") }
            return null
        }
        return RootfsSource.Url(url)
    }

    private fun abi(): String = Build.SUPPORTED_ABIS.firstOrNull().orEmpty()

    private fun RootfsProgress.describe(): String = when (this) {
        is RootfsProgress.Downloading -> if (totalBytes != null && totalBytes > 0) {
            "下载 rootfs ${percent(bytes, totalBytes)}%（${formatBytes(bytes)} / ${formatBytes(totalBytes)}）"
        } else {
            "下载 rootfs ${formatBytes(bytes)}"
        }

        is RootfsProgress.Extracting -> "解压 rootfs（$entries 个条目）"
        RootfsProgress.Installed -> "安装完成"
    }

    private fun percent(done: Long, total: Long): Int =
        ((done * 100) / total.coerceAtLeast(1)).toInt()

    private fun formatBytes(bytes: Long): String = when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "${bytes / 1024} KB"
        else -> String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0))
    }
}
