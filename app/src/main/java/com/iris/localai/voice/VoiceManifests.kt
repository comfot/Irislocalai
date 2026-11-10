package com.iris.localai.voice

import android.content.Context
import com.iris.localai.data.VoiceModelPack
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

data class VoiceManifestFile(
    val path: String,
    val size: Long = 0L,
)

data class VoiceManifest(
    val repo: String,
    val revision: String = "main",
    val files: List<VoiceManifestFile> = emptyList(),
) {
    val totalBytes: Long get() = files.sumOf { it.size }
}

object VoiceManifests {

    fun read(context: Context, pack: VoiceModelPack): VoiceManifest {
        val text = context.assets.open(pack.manifestAsset).use { input ->
            input.readBytes().decodeToString()
        }
        val obj = Json.parseToJsonElement(text).jsonObject
        val files = obj["files"]?.jsonArray?.mapNotNull { element ->
            val file = element.jsonObject
            val path = file["path"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            VoiceManifestFile(
                path = path,
                size = file["size"]?.jsonPrimitive?.longOrNull ?: 0L,
            )
        }.orEmpty()
        return VoiceManifest(
            repo = obj["repo"]?.jsonPrimitive?.contentOrNull ?: pack.repo,
            revision = obj["revision"]?.jsonPrimitive?.contentOrNull ?: pack.revision,
            files = files,
        )
    }
}
