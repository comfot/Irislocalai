package com.iris.localai.voice

import android.content.Context
import com.iris.localai.data.VoiceModelPack
import com.iris.localai.storage.AppStorage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

class VoiceModelStore(
    context: Context,
    private val storage: AppStorage,
) {

    private val root = storage.voiceModels

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
        val deleted = storage.readDirs("voice_models").fold(false) { acc, dirRoot ->
            val dir = File(dirRoot, id)
            val removed = dir.isDirectory && dir.deleteRecursively()
            acc || removed
        }
        refresh()
        return deleted
    }

    fun bytesOf(id: String): Long {
        val dir = storage.readDirs("voice_models")
            .asSequence()
            .map { File(it, id) }
            .firstOrNull { it.isDirectory }
            ?: return 0L
        return dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
    }

    fun refresh() {
        _installedIds.value = scan()
    }

    private fun scan(): Set<String> = storage.readDirs("voice_models")
        .asSequence()
        .flatMap { dirRoot ->
            dirRoot.listFiles { file -> file.isDirectory && File(file, MARKER).isFile }
                ?.asSequence()
                ?: emptySequence()
        }
        .map { it.name }
        .toSet()

    private companion object {
        const val MARKER = ".installed"
    }
}
