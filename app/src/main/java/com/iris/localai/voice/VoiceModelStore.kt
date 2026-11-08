package com.iris.localai.voice

import android.content.Context
import com.iris.localai.data.VoiceModelPack
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

class VoiceModelStore(context: Context) {

    private val root = File(context.filesDir, "voice_models").apply { mkdirs() }

    private val _installedIds = MutableStateFlow(scan())
    val installedIds: StateFlow<Set<String>> = _installedIds.asStateFlow()

    fun dirFor(pack: VoiceModelPack): File =
        File(root, pack.id).apply { mkdirs() }

    fun isInstalled(id: String): Boolean = id in _installedIds.value

    fun markInstalled(id: String) {
        val dir = File(root, id).apply { mkdirs() }
        File(dir, MARKER).writeText(System.currentTimeMillis().toString())
        refresh()
    }

    fun delete(id: String): Boolean {
        if (id.contains('/') || id.contains("..")) return false
        val deleted = File(root, id).deleteRecursively()
        refresh()
        return deleted
    }

    fun bytesOf(id: String): Long {
        val dir = File(root, id)
        if (!dir.isDirectory) return 0L
        return dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
    }

    fun refresh() {
        _installedIds.value = scan()
    }

    private fun scan(): Set<String> =
        root.listFiles { file -> file.isDirectory && File(file, MARKER).isFile }
            ?.map { it.name }
            ?.toSet()
            ?: emptySet()

    private companion object {
        const val MARKER = ".installed"
    }
}
