package com.iris.localai.ui.settings

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import com.iris.localai.IrisApp
import com.iris.localai.scene.SceneBackgroundType
import com.iris.localai.scene.SceneConfig

class SceneViewModel(application: Application) : AndroidViewModel(application) {

    private val store = (application as IrisApp).graph.scenes

    val scenes = store.scenes
    val activeId = store.activeId

    fun create(): SceneConfig = store.create()

    fun update(scene: SceneConfig) = store.update(scene)

    fun delete(id: String) = store.delete(id)

    fun setActive(id: String) = store.setActive(id)

    fun importBackground(
        uri: Uri,
        sceneId: String,
        type: SceneBackgroundType,
    ): Result<String> = store.importBackground(getApplication<Application>(), uri, sceneId, type)
}
