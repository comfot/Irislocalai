package com.iris.localai.motion

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicInteger

data class MotionCommand(val seq: Long, val action: String)

data class MotionAssetCommand(
    val seq: Long,
    val path: String,
    val loop: Boolean,
    val bgmPath: String? = null,
)

data class CameraCommand(val seq: Long, val path: String, val loop: Boolean)

data class ExpressionCommand(val seq: Long, val name: String)

data class VrmaCommand(val seq: Long, val url: String, val loop: Boolean = false)

/** Agent 工具与 3D 渲染页面之间的动作指令通道。 */
object MotionBridge {

    private val _command = MutableStateFlow<MotionCommand?>(null)
    val command: StateFlow<MotionCommand?> = _command.asStateFlow()

    private val _assetCommand = MutableStateFlow<MotionAssetCommand?>(null)
    val assetCommand: StateFlow<MotionAssetCommand?> = _assetCommand.asStateFlow()

    private val _cameraCommand = MutableStateFlow<CameraCommand?>(null)
    val cameraCommand: StateFlow<CameraCommand?> = _cameraCommand.asStateFlow()

    private val _expressionCommand = MutableStateFlow<ExpressionCommand?>(null)
    val expressionCommand: StateFlow<ExpressionCommand?> = _expressionCommand.asStateFlow()

    private val _vrmaCommand = MutableStateFlow<VrmaCommand?>(null)
    val vrmaCommand: StateFlow<VrmaCommand?> = _vrmaCommand.asStateFlow()

    private var seq = 0L
    private val activeViews = AtomicInteger(0)
    private val motionViews = AtomicInteger(0)
    private val mmdViews = AtomicInteger(0)

    fun registerView(motionEnabled: Boolean) {
        activeViews.incrementAndGet()
        if (motionEnabled) motionViews.incrementAndGet()
    }

    fun unregisterView(motionEnabled: Boolean) {
        activeViews.decrementAndGet()
        if (motionEnabled) motionViews.decrementAndGet()
    }

    fun hasActiveView(): Boolean = activeViews.get() > 0

    fun hasMotionView(): Boolean = motionViews.get() > 0

    fun hasMmdView(): Boolean = mmdViews.get() > 0

    /** 当前舞台上唯一 3D 模型的类型；同时存在时优先 MMD。 */
    fun activeKind(): Model3DKind? = when {
        hasMmdView() -> Model3DKind.MMD
        hasMotionView() -> Model3DKind.VRM
        else -> null
    }

    fun registerMmdView() {
        activeViews.incrementAndGet()
        mmdViews.incrementAndGet()
    }

    fun unregisterMmdView() {
        activeViews.decrementAndGet()
        mmdViews.decrementAndGet()
    }

    fun play(action: String) {
        seq += 1
        _command.value = MotionCommand(seq, action)
    }

    /** 播放本地动作素材；path 为 motion_assets 下的相对路径。 */
    fun playAsset(path: String, loop: Boolean = false, bgmPath: String? = null) {
        if (path.isBlank()) return
        seq += 1
        _assetCommand.value = MotionAssetCommand(seq, path, loop, bgmPath)
    }

    fun stopAsset() {
        seq += 1
        _assetCommand.value = MotionAssetCommand(seq, "", false, null)
    }

    fun playCamera(path: String, loop: Boolean = true) {
        if (path.isBlank()) return
        seq += 1
        _cameraCommand.value = CameraCommand(seq, path, loop)
    }

    fun stopCamera() {
        seq += 1
        _cameraCommand.value = CameraCommand(seq, "", false)
    }

    fun playExpression(name: String) {
        if (name.isBlank()) return
        seq += 1
        _expressionCommand.value = ExpressionCommand(seq, name)
    }

    fun playVrma(url: String, loop: Boolean = false) {
        if (url.isBlank()) return
        seq += 1
        _vrmaCommand.value = VrmaCommand(seq, url, loop)
    }

    fun stopVrma() {
        seq += 1
        _vrmaCommand.value = VrmaCommand(seq, "", false)
    }
}
