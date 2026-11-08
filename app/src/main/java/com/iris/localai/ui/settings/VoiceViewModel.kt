package com.iris.localai.ui.settings

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import com.iris.localai.IrisApp
import com.iris.localai.data.AppSettings
import com.iris.localai.data.VoiceModelPack

class VoiceViewModel(application: Application) : AndroidViewModel(application) {

    private val graph = (application as IrisApp).graph

    val settings = graph.settings.settings
    val installedIds = graph.voiceModels.installedIds
    val tasks = graph.voiceDownloads.tasks

    fun update(transform: (AppSettings) -> AppSettings) = graph.settings.update(transform)

    fun download(pack: VoiceModelPack) {
        graph.voiceDownloads.enqueue(pack, graph.settings.current.downloadSource)
    }

    fun cancel(pack: VoiceModelPack) = graph.voiceDownloads.cancel(pack.id)

    fun delete(pack: VoiceModelPack) {
        graph.voiceModels.delete(pack.id)
    }
}
