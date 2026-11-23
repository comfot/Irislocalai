package com.iris.moew.ui.chat

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import android.graphics.Rect
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.RectF
import android.graphics.SurfaceTexture
import android.content.res.Configuration
import java.io.ByteArrayOutputStream
import android.util.Base64
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.params.MeteringRectangle
import android.hardware.camera2.params.StreamConfigurationMap
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import android.view.TextureView
import android.widget.Toast
import android.util.Size as AndroidSize
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.VideocamOff
import androidx.compose.material.icons.outlined.GraphicEq
import androidx.compose.material.icons.outlined.VolumeUp
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.iris.moew.character.CharacterCardData
import com.iris.moew.character.CharacterModelEntry
import com.iris.moew.data.CharacterModel
import com.iris.moew.data.VideoCallStage
import com.iris.moew.scene.SceneConfig
import com.iris.moew.voice.VoiceRecorder
import java.io.File
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 视频通话里角色形象的运行时控制接口。
 * 当前由通话界面内部驱动；后续接入 AI 时可从对话事件调用
 * [updateMood] / [updateSpeaking]，不必改动渲染层。
 */
class VideoCallAvatarControl {
    var mood by mutableStateOf("neutral")
        private set
    var speaking by mutableStateOf(false)
        private set

    fun updateMood(value: String) {
        mood = value
    }

    fun updateSpeaking(value: Boolean) {
        speaking = value
    }
}

@Composable
internal fun VideoCallScreen(
    viewModel: ChatViewModel,
    character: CharacterCardData,
    activeModel: CharacterModelEntry?,
    modelFile: File?,
    live2dFile: File?,
    mmdFile: File?,
    model: CharacterModel,
    scene: SceneConfig?,
    l2dExpressions: Boolean,
    l2dMotions: Boolean,
    l2dIdleMotion: Boolean,
    l2dTapMotion: Boolean,
    l2dMouthSync: Boolean,
    motionEndpoint: String,
    motionIdle: Boolean,
    motionPack: String,
    motionAxisFix: Int,
    onDismiss: () -> Unit,
) {
    val accent = Color(character.accent ?: 0xFF7DB3FF)
    var muted by remember { mutableStateOf(false) }
    var cameraOn by remember { mutableStateOf(true) }
    var speakerOn by remember { mutableStateOf(true) }
    var seconds by remember { mutableIntStateOf(0) }
    val avatarControl = remember { VideoCallAvatarControl() }
    val callReply by viewModel.callReply.collectAsStateWithLifecycle()
    val ttsSpeaking by viewModel.ttsSpeaking.collectAsStateWithLifecycle()
    val appSettings by viewModel.settings.collectAsStateWithLifecycle()
    val ttsAmplitude by viewModel.ttsAmplitude.collectAsStateWithLifecycle()
    val stage = appSettings.videoCallStage
    val context = LocalContext.current
    val recorder = remember { VoiceRecorder(context) }
    val voiceScope = rememberCoroutineScope()
    val cameraController = remember { CameraPreviewController() }
    // AR 背景使用独立的后置相机控制器，避免与右上角前置小窗互相覆盖。
    val backCameraController = remember { CameraPreviewController() }
    var audioGranted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }
    var listenStatus by remember { mutableStateOf("正在接通…") }
    val audioPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        audioGranted = granted
        if (!granted) listenStatus = "未授予录音权限，通话只能播放不能收音"
    }

    LaunchedEffect(Unit) {
        viewModel.beginVideoCall()
        if (!audioGranted) audioPermission.launch(Manifest.permission.RECORD_AUDIO)
        viewModel.sendCallTurn("（视频通话已接通，请自然地跟我打个招呼）")
    }
    DisposableEffect(Unit) {
        onDispose {
            viewModel.endVideoCall()
            cameraController.release()
        }
    }
    LaunchedEffect(ttsSpeaking) {
        avatarControl.updateSpeaking(ttsSpeaking)
    }

    // 不把 running 作为 key：插话打断后 sendCallTurn 会立即置 running，
    // 重启协程会取消正在进行的那轮识别。
    LaunchedEffect(muted, audioGranted) {
        if (muted || !audioGranted) {
            if (muted) listenStatus = "麦克风已静音"
            return@LaunchedEffect
        }
        suspend fun listenTurn(bargeIn: Boolean): String? {
            // 开发者选项开启且本地流式模型可用时，用内置流式内核边录边识别。
            val session = viewModel.startStreamingAsr()
            var lastPartial = ""
            var partialAt = 0L
            val onFrame: ((ShortArray, Int) -> Unit)? = session?.let { stream ->
                { samples: ShortArray, size: Int ->
                    stream.accept(samples, size)
                    val now = System.currentTimeMillis()
                    if (now - partialAt >= 300) {
                        partialAt = now
                        val text = runCatching { stream.text() }.getOrDefault("")
                        if (text.isNotBlank()) {
                            lastPartial = text
                            voiceScope.launch { listenStatus = "你说：$text" }
                        }
                    }
                }
            }
            val speechStart: (() -> Unit)? = if (bargeIn) ({ viewModel.interruptSpeaking() }) else null
            val stopWhen: (() -> Boolean)? = if (bargeIn) ({ !viewModel.ttsSpeaking.value }) else null
            val file = recorder.record(
                maxMillis = if (bargeIn) 20_000 else 30_000,
                bargeIn = bargeIn,
                onSpeechStart = speechStart,
                stopWhen = stopWhen,
                onFrame = onFrame,
            )
            val streamed = session?.let { runCatching { it.finishAndGetText(1_200) }.getOrDefault("") }
                ?.ifBlank { lastPartial }
                .orEmpty()
            session?.close()
            if (streamed.isNotBlank()) {
                file?.delete()
                return streamed
            }
            if (file == null) return null
            listenStatus = "正在识别…"
            val result = viewModel.transcribeVoice(file)
            file.delete()
            return result.getOrThrow()
        }

        while (isActive) {
            if (viewModel.ttsSpeaking.value) {
                // 播放期间也持续收音：听到人声就立刻停掉 TTS，把这句话当作输入。
                listenStatus = "正在说话 · 可直接打断"
                val text = try {
                    listenTurn(bargeIn = true)
                } catch (e: Exception) {
                    listenStatus = "识别失败：${e.message ?: "未知错误"}"
                    delay(800)
                    null
                }
                if (text.isNullOrBlank()) {
                    delay(120)
                    continue
                }
                listenStatus = "你说：$text"
                val frame = if (cameraOn) cameraController.captureJpeg() else null
                viewModel.sendCallTurn(text, frame)
                continue
            }
            if (viewModel.running.value) {
                listenStatus = "正在思考…"
                delay(300)
                continue
            }
            listenStatus = "正在聆听…"
            val text = try {
                listenTurn(bargeIn = false)
            } catch (e: Exception) {
                listenStatus = "识别失败：${e.message ?: "未知错误"}"
                delay(800)
                null
            }
            if (text.isNullOrBlank()) continue
            listenStatus = "你说：$text"
            val frame = if (cameraOn) cameraController.captureJpeg() else null
            viewModel.sendCallTurn(text, frame)
        }
    }

    LaunchedEffect(model, activeModel?.id, mmdFile, live2dFile, modelFile) {
        android.util.Log.d(
            "VideoCall",
            "mode=$model active=${activeModel?.type} vrm=${modelFile?.name} " +
                "live2d=${live2dFile?.name} mmd=${mmdFile?.name}",
        )
    }

    LaunchedEffect(Unit) {
        while (true) {
            delay(1000)
            seconds += 1
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        listOf(Color(0xFF14161C), Color(0xFF0A0B0F)),
                    ),
                ),
        ) {
            // 角色上半身：渲染器按 portrait 取景，只露头肩与胸口，不再整体缩放。
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clipToBounds(),
            ) {
                if (stage == VideoCallStage.AR_GRID) {
                    ArCallStage(
                        cameraOn = cameraOn,
                        cameraController = backCameraController,
                        height = appSettings.videoCallArHeight,
                        offsetX = appSettings.videoCallArOffsetX,
                        offsetY = appSettings.videoCallArOffsetY,
                        showGrid = appSettings.videoCallArGrid,
                        cameraHeight = appSettings.videoCallArCameraHeight,
                        visualTracking = appSettings.videoCallArVisualTracking,
                        slamEnabled = appSettings.videoCallArSlam,
                        debugOverlay = appSettings.videoCallArDebug,
                        feetFraction = when {
                            model == CharacterModel.TWO_D -> 0.99f
                            mmdFile != null -> 0.70f
                            else -> 0.82f
                        },
                        footOffset = appSettings.videoCallArFootOffset,
                        supportsWorldCamera = model == CharacterModel.THREE_D,
                        onPlaceSaved = viewModel::updateVideoCallPlacement,
                    ) { look, world ->
                        CallAvatar(
                            character = character,
                            model = model,
                            activeModel = activeModel,
                            modelFile = modelFile,
                            live2dFile = live2dFile,
                            mmdFile = mmdFile,
                            avatarControl = avatarControl,
                            mouthAmplitude = ttsAmplitude,
                            arLook = look,
                            arWorld = world,
                            l2dExpressions = l2dExpressions,
                            l2dMotions = l2dMotions,
                            l2dIdleMotion = l2dIdleMotion,
                            l2dTapMotion = l2dTapMotion,
                            l2dMouthSync = l2dMouthSync,
                            motionEndpoint = motionEndpoint,
                            motionIdle = motionIdle,
                            motionPack = motionPack,
                            motionAxisFix = motionAxisFix,
                            portrait = stage != VideoCallStage.AR_GRID,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                } else {
                    SceneBackground(scene = scene, modifier = Modifier.fillMaxSize())
                    CallAvatar(
                        character = character,
                        model = model,
                        activeModel = activeModel,
                        modelFile = modelFile,
                        live2dFile = live2dFile,
                        mmdFile = mmdFile,
                        avatarControl = avatarControl,
                        mouthAmplitude = ttsAmplitude,
                        arLook = null,
                        arWorld = null,
                        l2dExpressions = l2dExpressions,
                        l2dMotions = l2dMotions,
                        l2dIdleMotion = l2dIdleMotion,
                        l2dTapMotion = l2dTapMotion,
                        l2dMouthSync = l2dMouthSync,
                        motionEndpoint = motionEndpoint,
                        motionIdle = motionIdle,
                        motionPack = motionPack,
                        motionAxisFix = motionAxisFix,
                        portrait = true,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
                // 通话中模型不接受用户手势；AR 模式的拖动由 ArCallStage 自己处理。
                if (stage != VideoCallStage.AR_GRID) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .pointerInput(Unit) {
                                awaitPointerEventScope {
                                    while (true) {
                                        awaitPointerEvent().changes.forEach { it.consume() }
                                    }
                                }
                            },
                    )
                }
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            Brush.verticalGradient(
                                0f to Color.Black.copy(alpha = 0.45f),
                                0.28f to Color.Transparent,
                                0.72f to Color.Transparent,
                                1f to Color.Black.copy(alpha = 0.62f),
                            ),
                        ),
                )
            }

            // 顶部通话状态
            Row(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 28.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = character.charName,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = Color.White,
                    )
                    Text(
                        text = "视频通话中 · ${formatDuration(seconds)}" +
                            (activeModel?.type?.let { " · ${it.name}" } ?: ""),
                        style = MaterialTheme.typography.labelMedium,
                        color = Color.White.copy(alpha = 0.72f),
                    )
                }
                Surface(
                    shape = CircleShape,
                    color = Color.White.copy(alpha = 0.12f),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.GraphicEq,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(16.dp),
                        )
                        Text(
                            text = if (avatarControl.speaking) "说话中" else "已连接",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.White,
                        )
                    }
                }
            }

            // 右上角摄像头小窗（AR 模式下相机本身就是全屏背景）
            if (stage != VideoCallStage.AR_GRID) {
                Column(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(top = 92.dp, end = 16.dp),
                    horizontalAlignment = Alignment.End,
                ) {
                    CameraPreviewPane(
                        enabled = cameraOn,
                        controller = cameraController,
                        modifier = Modifier
                            .width(104.dp)
                            .height(150.dp)
                            .clip(RoundedCornerShape(16.dp))
                            .border(1.dp, Color.White.copy(alpha = 0.25f), RoundedCornerShape(16.dp)),
                    )
                    Text(
                        text = if (cameraOn) "关闭摄像头" else "开启摄像头",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White.copy(alpha = 0.75f),
                        modifier = Modifier
                            .padding(top = 6.dp)
                            .clip(CircleShape)
                            .clickable { cameraOn = !cameraOn }
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                    )
                }
            }

            // 底部控制条
            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(bottom = 40.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Text(
                    text = callReply.takeLast(120).ifBlank { listenStatus },
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White.copy(alpha = 0.92f),
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(horizontal = 24.dp),
                )
                if (callReply.isNotBlank()) {
                    Text(
                        text = listenStatus,
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White.copy(alpha = 0.55f),
                    )
                }
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(30.dp))
                        .background(Color.White.copy(alpha = 0.10f))
                        .border(1.dp, Color.White.copy(alpha = 0.18f), RoundedCornerShape(30.dp))
                        .padding(horizontal = 18.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(18.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CallControl(
                        icon = if (muted) Icons.Default.MicOff else Icons.Default.Mic,
                        label = if (muted) "已静音" else "麦克风",
                        active = !muted,
                        onClick = { muted = !muted },
                    )
                    CallControl(
                        icon = if (cameraOn) Icons.Default.Videocam else Icons.Default.VideocamOff,
                        label = "摄像头",
                        active = cameraOn,
                        onClick = { cameraOn = !cameraOn },
                    )
                    CallControl(
                        icon = Icons.Outlined.VolumeUp,
                        label = "扬声器",
                        active = speakerOn,
                        onClick = { speakerOn = !speakerOn },
                    )
                    Surface(
                        shape = CircleShape,
                        color = Color(0xFFE5484D),
                        modifier = Modifier
                            .size(52.dp)
                            .clip(CircleShape)
                            .clickable(onClick = onDismiss),
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.Default.CallEnd,
                                contentDescription = "挂断",
                                tint = Color.White,
                            )
                        }
                    }
                }
            }

            // 摄像头关闭时的提示（仅房间模式，AR 模式下关闭相机就是黑底网格）
            if (!cameraOn && stage != VideoCallStage.AR_GRID) {
                Surface(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(top = 92.dp, end = 16.dp)
                        .width(104.dp)
                        .height(150.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .clickable { cameraOn = true },
                    color = Color.White.copy(alpha = 0.08f),
                ) {
                    Column(
                        modifier = Modifier.fillMaxSize(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Icon(
                            imageVector = Icons.Default.VideocamOff,
                            contentDescription = null,
                            tint = Color.White.copy(alpha = 0.65f),
                            modifier = Modifier.size(26.dp),
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = "已关闭",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.White.copy(alpha = 0.65f),
                        )
                    }
                }
            }

            Surface(
                shape = CircleShape,
                color = Color.Black.copy(alpha = 0.35f),
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(start = 16.dp, bottom = 16.dp),
            ) {
                Text(
                    text = if (stage == VideoCallStage.AR_GRID) {
                        "拖动画面可移动人物 · 设置 › 交互 可调大小"
                    } else {
                        "通话中模型不响应触摸操作"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White.copy(alpha = 0.55f),
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                )
            }
        }
    }
}

@Composable
private fun CallAvatar(
    character: CharacterCardData,
    model: CharacterModel,
    activeModel: CharacterModelEntry?,
    modelFile: File?,
    live2dFile: File?,
    mmdFile: File?,
    avatarControl: VideoCallAvatarControl,
    mouthAmplitude: Float,
    arLook: Pair<Float, Float>?,
    arWorld: ArWorldPose?,
    l2dExpressions: Boolean,
    l2dMotions: Boolean,
    l2dIdleMotion: Boolean,
    l2dTapMotion: Boolean,
    l2dMouthSync: Boolean,
    motionEndpoint: String,
    motionIdle: Boolean,
    motionPack: String,
    motionAxisFix: Int,
    portrait: Boolean,
    modifier: Modifier = Modifier,
) {
    val accentHex = String.format("#%06X", (character.accent ?: 0xFF7DB3FFL) and 0xFFFFFFL)
    Box(modifier) {
        when {
            model == CharacterModel.THREE_D && modelFile != null -> CharacterModel3DView(
                modelFile = modelFile,
                modelType = activeModel?.type?.name.orEmpty(),
                accentHex = accentHex,
                dark = true,
                portrait = portrait,
                interactive = false,
                arLook = arLook,
                arWorld = arWorld,
                motionEndpoint = motionEndpoint,
                motionIdle = motionIdle,
                motionPack = motionPack,
                motionAxisFix = motionAxisFix,
                speaking = avatarControl.speaking,
                mouthAmplitude = mouthAmplitude,
                modifier = Modifier.fillMaxSize(),
            )
            model == CharacterModel.THREE_D && mmdFile != null -> MMDView(
                modelFile = mmdFile,
                accentHex = accentHex,
                dark = true,
                mood = avatarControl.mood,
                speaking = avatarControl.speaking,
                mouthAmplitude = mouthAmplitude,
                portrait = portrait,
                interactive = false,
                arLook = arLook,
                arWorld = arWorld,
                modifier = Modifier.fillMaxSize(),
            )
            model == CharacterModel.TWO_D && live2dFile != null -> Live2DView(
                modelFile = live2dFile,
                accentHex = accentHex,
                dark = true,
                speaking = avatarControl.speaking,
                mood = avatarControl.mood,
                mouthAmplitude = mouthAmplitude,
                portrait = portrait,
                interactive = false,
                expressionsEnabled = l2dExpressions,
                motionsEnabled = l2dMotions,
                idleMotionEnabled = l2dIdleMotion,
                tapMotionEnabled = l2dTapMotion,
                mouthSyncEnabled = l2dMouthSync,
                modifier = Modifier.fillMaxSize(),
            )
            else -> CallPlaceholder(character = character, accent = Color(character.accent ?: 0xFF7DB3FF))
        }
    }
}

/**
 * AR 舞台：后置相机 + IMU 姿态驱动的世界坐标网格。
 * 相机固定在 (0, 0, cameraHeight)，地面 z=0；锚点由拖动或保存的屏幕比例反投影得到，
 * 手机转动时网格与人物会随真实地面移动（3DoF，不含位移追踪）。
 */
private const val AR_RING_RADIUS = 1.2f
private const val AR_RING_DIAMETER = AR_RING_RADIUS * 2f

/** SLAM 模型空间的地面环半径（假设 ORB 地图尺度近似米，可通过人物高度设置微调观感）。 */
private const val AR_SLAM_RING_RADIUS = 0.6f

/** 校准阶段的俯视高度角（弧度）：从上方看模型头部。 */
private const val AR_PLACEMENT_TOP_DOWN = 1.15f
private const val AR_PLACEMENT_TIMEOUT_MS = 45_000L

/**
 * 地图坐标系里的“上”方向。
 * 先用 IMU 重力：设备旋转的第三列就是世界向上在设备坐标里的表示，
 * 后置相机轴向为 (x, -y, -z)，再用 SLAM 相机旋转把它转到地图坐标。
 * 拿不到姿态时退回“相机在锚点上方”。
 */
private fun slamWorldUp(
    deviceRotation: FloatArray?,
    view: FloatArray,
    camera: FloatArray,
    anchor: FloatArray,
): FloatArray {
    // 定锚点时相机在锚点上方，这个方向就是地图里的“上”，用来校正 IMU 结果的符号。
    val gx = camera[0] - anchor[0]
    val gy = camera[1] - anchor[1]
    val gz = camera[2] - anchor[2]
    val geometric = sqrt(gx * gx + gy * gy + gz * gz).coerceAtLeast(0.0001f)
    if (deviceRotation != null && deviceRotation.size >= 9) {
        // 设备坐标下的世界上方向 = 设备旋转第三行（R[2][0..2]，列主序下标 2/5/8）
        val dx = deviceRotation[2]
        val dy = deviceRotation[5]
        val dz = deviceRotation[8]
        // 转到相机坐标（后置相机 x/右，y/下，z/前）
        val cx = dx
        val cy = -dy
        val cz = -dz
        val x = view[0] * cx + view[1] * cy + view[2] * cz
        val y = view[4] * cx + view[5] * cy + view[6] * cz
        val z = view[8] * cx + view[9] * cy + view[10] * cz
        val length = sqrt(x * x + y * y + z * z)
        if (length > 0.0001f) {
            var ux = x / length
            var uy = y / length
            var uz = z / length
            // 相机轴向/传感器坐标的符号约定可能与 SLAM 视图矩阵不同，用几何方向定符号。
            if (ux * gx + uy * gy + uz * gz < 0f) {
                ux = -ux
                uy = -uy
                uz = -uz
            }
            return floatArrayOf(ux, uy, uz)
        }
    }
    return floatArrayOf(gx / geometric, gy / geometric, gz / geometric)
}

/**
 * SLAM 地图的空间尺度：相机到锚点平面的垂距（地图单位）除以真实相机高度（米）。
 * 用平面法向而不是相机到锚点的直线距离，避免锚点偏到画面一侧时把尺度算大。
 */
private fun slamUnitsPerMeter(camera: FloatArray, pose: SlamPose, cameraHeightMeters: Float): Float {
    val nx = pose.model[4]
    val ny = pose.model[5]
    val nz = pose.model[6]
    val length = sqrt(nx * nx + ny * ny + nz * nz).coerceAtLeast(0.0001f)
    val dx = camera[0] - pose.model[12]
    val dy = camera[1] - pose.model[13]
    val dz = camera[2] - pose.model[14]
    val height = abs((dx * nx + dy * ny + dz * nz) / length)
    return (height / cameraHeightMeters.coerceAtLeast(0.1f)).coerceIn(0.01f, 100f)
}

@Composable
private fun ArCallStage(
    cameraOn: Boolean,
    cameraController: CameraPreviewController,
    height: Float,
    offsetX: Float,
    offsetY: Float,
    showGrid: Boolean,
    cameraHeight: Float,
    visualTracking: Boolean,
    slamEnabled: Boolean,
    debugOverlay: Boolean,
    feetFraction: Float,
    footOffset: Float,
    supportsWorldCamera: Boolean,
    onPlaceSaved: (Float, Float) -> Unit,
    avatar: @Composable (Pair<Float, Float>?, ArWorldPose?) -> Unit,
) {
    val context = LocalContext.current
    val tracker = remember { ArPoseTracker(context) }
    var anchor by remember { mutableStateOf<FloatArray?>(null) }
    var lastFinger by remember { mutableStateOf<ScreenPoint?>(null) }
    var cameraPosition by remember { mutableStateOf(floatArrayOf(0f, 0f)) }
    var cameraHeightEstimate by remember { mutableFloatStateOf(cameraHeight) }
    var avatarHeightPx by remember { mutableFloatStateOf(0f) }
    var placementGeneration by remember { mutableIntStateOf(0) }
    val flowTracker = remember { VisualFlowTracker() }
    val slamTracker = remember { ArSlamTracker(context) }
    var slamPose by remember { mutableStateOf<SlamPose?>(null) }
    val trackingLog = remember { ArTrackingLog(context) }
    var logging by remember { mutableStateOf(false) }
    LaunchedEffect(cameraHeight) {
        cameraHeightEstimate = cameraHeight
    }
    DisposableEffect(Unit) {
        tracker.start()
        onDispose {
            tracker.stop()
            flowTracker.captureDebug = false
            trackingLog.stop()
        }
    }
    DisposableEffect(slamEnabled) {
        if (slamEnabled) {
            slamTracker.onPose = { pose -> slamPose = pose }
            slamTracker.start()
        }
        onDispose {
            slamTracker.stop()
            slamPose = null
        }
    }
    // 姿态投影按竖屏推导；横屏时退回静态网格，避免坐标轴错位。
    val portrait = LocalConfiguration.current.orientation == Configuration.ORIENTATION_PORTRAIT
    val rotation = if (portrait) tracker.rotation else null
    var arLook by remember { mutableStateOf<Pair<Float, Float>?>(null) }
    var lookBase by remember { mutableStateOf<Pair<Float, Float>?>(null) }
    var currentLook by remember { mutableStateOf<Pair<Float, Float>?>(null) }
    var lookingDown by remember { mutableStateOf(false) }
    var placementLocked by remember { mutableStateOf(false) }
    var lockedToSlam by remember { mutableStateOf(false) }
    var lockedMapId by remember { mutableIntStateOf(-1) }
    var worldAnchorYaw by remember { mutableStateOf(0f) }
    // 定锚点时相机在锚点上方，这段方向就是地图里的真实竖直方向；平面法向可能是竖面，不能直接用。
    var worldAnchorUp by remember { mutableStateOf(floatArrayOf(0f, 1f, 0f)) }
    // 锁定后锚点沿竖直方向的高度：SLAM 平面会缓慢漂高漂低，不冻结会让模型上下浮动。
    var frozenAnchorHeight by remember { mutableFloatStateOf(Float.NaN) }
    var worldUnitsPerMeter by remember { mutableStateOf(1f) }
    var lastGoodWorld by remember { mutableStateOf<ArWorldPose?>(null) }
    var lastGoodImu by remember { mutableStateOf<FloatArray?>(null) }
    var lastGoodAt by remember { mutableStateOf(0L) }
    LaunchedEffect(rotation) {
        val current = rotation ?: return@LaunchedEffect
        val yaw = atan2(-current[2], -current[5])
        val pitch = asin(current[8].coerceIn(-1f, 1f))
        currentLook = yaw to pitch
        // 后置相机朝设备 -Z；设备 +Z 指向世界上方时（rotation[8]≈1）镜头正对地面。
        lookingDown = current[8] > 0.45f
        if (!placementLocked) {
            arLook = 0f to AR_PLACEMENT_TOP_DOWN
            return@LaunchedEffect
        }
        val base = lookBase ?: (yaw to 0f).also { lookBase = it }
        var deltaYaw = yaw - base.first
        while (deltaYaw > Math.PI) deltaYaw -= (Math.PI * 2).toFloat()
        while (deltaYaw < -Math.PI) deltaYaw += (Math.PI * 2).toFloat()
        // 设备俯仰直接映射为模型相机高度角：朝地时俯视，抬到水平时连续过渡到正面。
        val elevation = pitch.coerceIn(0f, AR_PLACEMENT_TOP_DOWN)
        arLook = deltaYaw to elevation
    }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(Color(0xFF0B1018), Color(0xFF131B28)))),
    ) {
        val widthPx = constraints.maxWidth.toFloat()
        val heightPx = constraints.maxHeight.toFloat()
        val projector = rotation?.let {
            ArWorldProjector(
                widthPx,
                heightPx,
                cameraHeightEstimate,
                it,
                cameraPosition[0],
                cameraPosition[1],
            )
        }
        val currentProjector by rememberUpdatedState(projector)
        LaunchedEffect(placementLocked) {
            if (placementLocked) return@LaunchedEffect
            // 校准阶段每 2 秒重新对焦网格中心，避免移动手机后失焦。
            while (true) {
                delay(2000)
                cameraController.focusAt(widthPx / 2f, heightPx * 0.75f, widthPx, heightPx)
            }
        }

        CameraPreviewPane(
            enabled = cameraOn,
            controller = cameraController,
            lensFacing = CameraCharacteristics.LENS_FACING_BACK,
            showFallbackIcon = false,
            modifier = Modifier.fillMaxSize(),
        )
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.30f)),
        )

        // 等姿态可用后再把保存的屏幕位置反投影成地面锚点；相机未朝下看时继续等。
        LaunchedEffect(placementGeneration) {
            while (anchor == null) {
                val current = tracker.rotation
                if (current != null) {
                    val ready = ArWorldProjector(
                        widthPx,
                        heightPx,
                        cameraHeightEstimate,
                        current,
                        cameraPosition[0],
                        cameraPosition[1],
                    )
                    anchor = ready.groundFromFraction(offsetX, offsetY)
                }
                if (anchor == null) delay(120)
            }
        }

        // 实验性视觉跟踪：同一份 Y 平面可同时喂 SLAM 与 OpenCV 光流。
        DisposableEffect(slamEnabled, visualTracking, cameraOn, cameraHeight) {
            val needsFrames = cameraOn && (slamEnabled || (visualTracking && flowTracker.available))
            if (needsFrames) {
                cameraController.onYuvFrame = { bytes, width, height, degrees ->
                    if (slamEnabled) {
                        slamTracker.submitFrame(bytes, width, height, degrees)
                    }
                    if (visualTracking && flowTracker.available) {
                        val currentRotation = tracker.rotation
                        if (currentRotation != null) {
                            val motion = flowTracker.updateYuv(
                                yPlane = bytes,
                                width = width,
                                height = height,
                                rotationDegrees = degrees,
                                rotation = currentRotation,
                                cameraHeight = cameraHeightEstimate,
                                horizontalFov = cameraController.horizontalFov,
                            )
                            if (trackingLog.active) {
                                flowTracker.lastDebug?.let { debug ->
                                    trackingLog.append(
                                        buildTrackingRow(
                                            timestamp = System.currentTimeMillis(),
                                            width = width,
                                            height = height,
                                            rotationDegrees = degrees,
                                            fov = cameraController.horizontalFov,
                                            cameraHeightSet = cameraHeight,
                                            cameraHeightEstimate = cameraHeightEstimate,
                                            cameraX = cameraPosition[0],
                                            cameraY = cameraPosition[1],
                                            imu = currentRotation,
                                            debug = debug,
                                            motion = motion,
                                        ),
                                    )
                                }
                            }
                            if (motion != null) {
                                cameraPosition = floatArrayOf(
                                    cameraPosition[0] + motion.dx,
                                    cameraPosition[1] + motion.dy,
                                )
                                // SLAM 自己按平面法线估算尺度，光流的高度漂移只会把模型缩放弄乱。
                                if (!slamEnabled) {
                                    cameraHeightEstimate = (cameraHeightEstimate + motion.dHeight * 0.6f)
                                        .coerceIn(0.1f, 2.5f)
                                }
                            }
                        }
                    }
                }
            }
            onDispose {
                cameraController.onYuvFrame = null
                if (!visualTracking) flowTracker.reset()
            }
        }

        val rawSlam = slamPose
        // 锁定后只保留水平方向的跟随，竖直方向冻结在定锚点时的位置。
        val slam = if (rawSlam != null && lockedToSlam && !frozenAnchorHeight.isNaN()) {
            val up = worldAnchorUp
            val height = rawSlam.model[12] * up[0] +
                rawSlam.model[13] * up[1] +
                rawSlam.model[14] * up[2]
            val delta = frozenAnchorHeight - height
            if (abs(delta) < 0.0005f) {
                rawSlam
            } else {
                val model = rawSlam.model.copyOf()
                model[12] += up[0] * delta
                model[13] += up[1] * delta
                model[14] += up[2] * delta
                rawSlam.copy(model = model)
            }
        } else {
            rawSlam
        }
        // 锁定后以 native 的 drawable/tracked 为准：切图时 native 会恢复对应地图的 AR 锚点，
        // 此时 hasPlane 可能暂时为 false，但模型仍应保持在世界锚点。
        val useSlam = slamEnabled && slam != null && slam.tracked && (placementLocked || slam.hasPlane)
        // 投影矩阵必须按预览画面实际使用的变换矫正，否则网格/锚点会和相机画面错位。
        val previewFrame = cameraController.previewFrameSize
        val slamProjection = if (useSlam && slam != null && previewFrame != null) {
            slamDisplayProjection(
                projection = slam.projection,
                previewWidth = previewFrame.width,
                previewHeight = previewFrame.height,
                screenWidth = widthPx,
                screenHeight = heightPx,
            )
        } else {
            null
        }
        val slamProjector = if (useSlam && slam != null && slamProjection != null) {
            SlamProjector(widthPx, heightPx, slam, slamProjection)
        } else {
            null
        }
        LaunchedEffect(placementLocked, slamEnabled, useSlam, anchor, lookingDown) {
            if (placementLocked) return@LaunchedEffect
            if (lookingDown && useSlam) {
                val camera = slamCameraPosition(slam!!.view)
                val anchor = floatArrayOf(slam.model[12], slam.model[13], slam.model[14])
                worldAnchorYaw = atan2(camera[0] - anchor[0], camera[2] - anchor[2])
                val up = slamWorldUp(tracker.rotation, slam.view, camera, anchor)
                worldAnchorUp = up
                frozenAnchorHeight = anchor[0] * up[0] + anchor[1] * up[1] + anchor[2] * up[2]
                worldUnitsPerMeter = slamUnitsPerMeter(camera, slam, cameraHeightEstimate)
                lookBase = currentLook?.first?.let { it to 0f }
                lockedToSlam = true
                lockedMapId = slam.mapId
                placementLocked = true
                withContext(Dispatchers.IO) { slamTracker.saveAnchorMap() }
            } else if (!slamEnabled && anchor != null) {
                lookBase = currentLook?.first?.let { it to 0f }
                lockedToSlam = false
                placementLocked = true
            }
        }
        LaunchedEffect(placementLocked, lockedToSlam, slam?.mapId) {
            val pose = slam ?: return@LaunchedEffect
            if (placementLocked && lockedToSlam) {
                if (pose.mapId != lockedMapId) {
                    // 新地图没有锚点：重新加载最初保存的锚点地图，避免模型跟到新地图原点。
                    withContext(Dispatchers.IO) { slamTracker.reloadAnchorMap(pose.mapId) }
                    return@LaunchedEffect
                }
                // 已回到锚点地图时重新估算该地图的尺度，不重置定位。
                if (pose.tracked) {
                    val camera = slamCameraPosition(pose.view)
                    worldUnitsPerMeter = slamUnitsPerMeter(camera, pose, cameraHeightEstimate)
                    lockedMapId = pose.mapId
                }
            }
        }
        LaunchedEffect(slamEnabled) {
            if (!slamEnabled) return@LaunchedEffect
            delay(AR_PLACEMENT_TIMEOUT_MS)
            if (!placementLocked) {
                lookBase = currentLook?.first?.let { it to 0f }
                lockedToSlam = false
                placementLocked = true
            }
        }

        val useWorldGrid = supportsWorldCamera && placementLocked && lockedToSlam && slamProjector != null
        if (useWorldGrid && showGrid) {
            SlamGroundCanvas(projector = slamProjector!!, modifier = Modifier.fillMaxSize())
        } else if (projector != null && anchor != null && showGrid) {
            ArGroundCanvas(
                projector = projector,
                anchor = anchor!!,
                modifier = Modifier.fillMaxSize(),
            )
        }

        when {
            !placementLocked && !lookingDown -> ArStageHint("将镜头正对地面，确定网格位置")
            !placementLocked && slamEnabled && slam != null && slam.tracked != true ->
                ArStageHint("SLAM 初始化中：缓慢左右移动手机，对准有纹理的地面")
            !placementLocked -> ArStageHint("保持镜头对准地面，正在确定网格位置…")
            slamEnabled && !lockedToSlam -> ArStageHint("SLAM 未就绪，已使用 IMU + 光流定位")
            slamEnabled && lockedToSlam && slam?.tracked != true ->
                ArStageHint("SLAM 正在找回画面，失败会回退 IMU + 光流")
            !tracker.available -> ArStageHint("设备没有姿态传感器，AR 网格使用静态视角")
            visualTracking && !flowTracker.available -> ArStageHint("OpenCV 初始化失败，视觉位移不可用")
            projector == null -> ArStageHint("正在等待姿态数据…")
            anchor == null -> ArStageHint("把手机朝下对准桌面，等待地面锚定")
        }

        // 模型按锚点到头顶的投影高度缩放（约 8Hz 更新，避免频繁重排 WebView）。
        val effectiveFeet = (feetFraction - footOffset).coerceIn(0.5f, 0.99f)
        LaunchedEffect(feetFraction, footOffset, height) {
            while (true) {
                delay(120)
                val currentRotation = tracker.rotation ?: continue
                val currentAnchor = anchor ?: continue
                val geometry = ArWorldProjector(
                    widthPx,
                    heightPx,
                    cameraHeightEstimate,
                    currentRotation,
                    cameraPosition[0],
                    cameraPosition[1],
                )
                // 模型尺寸直接绑定地面环的屏幕直径：远近变化时与网格严格同比例。
                val rightX = currentRotation[0]
                val rightY = currentRotation[3]
                val rightLength = hypot(rightX, rightY).coerceAtLeast(0.1f)
                val offsetX = rightX / rightLength * AR_RING_RADIUS
                val offsetY = rightY / rightLength * AR_RING_RADIUS
                val ringLeft = geometry.project(
                    floatArrayOf(currentAnchor[0] - offsetX, currentAnchor[1] - offsetY, 0f),
                ) ?: continue
                val ringRight = geometry.project(
                    floatArrayOf(currentAnchor[0] + offsetX, currentAnchor[1] + offsetY, 0f),
                ) ?: continue
                val ringWidth = hypot(ringRight.x - ringLeft.x, ringRight.y - ringLeft.y)
                val modelHeightPx = ringWidth * (height / AR_RING_DIAMETER)
                val targetHeight = (modelHeightPx / effectiveFeet)
                    .coerceIn(heightPx * 0.2f, heightPx * 0.98f)
                if (avatarHeightPx <= 0f || abs(targetHeight - avatarHeightPx) > heightPx * 0.02f) {
                    avatarHeightPx = targetHeight
                }
            }
        }

        val activeSlamProjector = if (placementLocked && lockedToSlam) slamProjector else null
        val worldPose = if (
            supportsWorldCamera && placementLocked && lockedToSlam && useSlam &&
            slam != null && slamProjection != null
        ) {
            val cameraPosition = slamCameraPosition(slam.view)
            val anchorPosition = floatArrayOf(slam.model[12], slam.model[13], slam.model[14])
            val distance = sqrt(
                (cameraPosition[0] - anchorPosition[0]) * (cameraPosition[0] - anchorPosition[0]) +
                    (cameraPosition[1] - anchorPosition[1]) * (cameraPosition[1] - anchorPosition[1]) +
                    (cameraPosition[2] - anchorPosition[2]) * (cameraPosition[2] - anchorPosition[2]),
            ).coerceAtLeast(0.001f)
            // 模型比相机到锚点还高时相机会落进网格内部，背面剔除后就什么都看不到；
            // 0.5 倍距离让整个人落在锚点上方的可见区域，不会被画面上边切掉。
            val modelHeight = (height * worldUnitsPerMeter)
                .coerceAtMost(distance * 0.5f)
                .coerceAtLeast(0.02f)
            val fovY = if (slam.projection[5] > 0.0001f) {
                2f * atan(1f / slam.projection[5])
            } else {
                Math.toRadians(60.0).toFloat()
            }
            ArWorldPose(
                model = slam.model.copyOf(),
                view = slam.view.copyOf(),
                projection = slamProjection.copyOf(),
                modelHeightMeters = modelHeight,
                yaw = worldAnchorYaw,
                cameraPosition = cameraPosition,
                cameraRotation = cameraQuaternion(slam.view),
                anchorPosition = anchorPosition,
                anchorRotation = modelQuaternion(slam.model),
                fovY = fovY,
                worldUp = worldAnchorUp.copyOf(),
            )
        } else {
            null
        }
        val imuQuaternion = rotation?.let { deviceQuaternion(it) }
        LaunchedEffect(worldPose, imuQuaternion) {
            if (worldPose != null) {
                lastGoodWorld = worldPose
                lastGoodImu = imuQuaternion
                lastGoodAt = System.currentTimeMillis()
            }
        }
        // SLAM 短暂丢失时，冻结最后一次位置，用陀螺仪旋转增量继续驱动相机，避免模型瞬移/消失。
        val heldWorld = if (
            worldPose == null && placementLocked && lockedToSlam &&
            lastGoodWorld != null && lastGoodImu != null && imuQuaternion != null &&
            System.currentTimeMillis() - lastGoodAt < 3000L
        ) {
            val delta = quaternionMultiply(imuQuaternion, quaternionInverse(lastGoodImu!!))
            lastGoodWorld!!.copy(
                view = viewRotatedBy(lastGoodWorld!!.view, delta),
                cameraRotation = quaternionMultiply(delta, lastGoodWorld!!.cameraRotation),
            )
        } else {
            null
        }
        val effectiveWorldPose = worldPose ?: heldWorld
        val slamBase = activeSlamProjector?.project(0f, 0f, 0f)
        val slamLeft = activeSlamProjector?.project(-AR_SLAM_RING_RADIUS, 0f, 0f)
        val slamRight = activeSlamProjector?.project(AR_SLAM_RING_RADIUS, 0f, 0f)
        val slamWidthPx = if (slamLeft != null && slamRight != null) {
            hypot(slamRight.x - slamLeft.x, slamRight.y - slamLeft.y)
        } else {
            0f
        }
        val base = when {
            !placementLocked -> ScreenPoint(widthPx / 2f, heightPx * 0.72f)
            slamBase != null -> slamBase
            projector != null && anchor != null -> projector.project(anchor!!)
            else -> null
        }
        val nominalHeight = heightPx * (0.66f * (height / 1.6f)).coerceIn(0.3f, 0.95f)
        val slamHeightPx = if (slamWidthPx > 1f) {
            (slamWidthPx * (height / (AR_SLAM_RING_RADIUS * 2f)))
                .coerceIn(heightPx * 0.2f, heightPx * 0.98f)
        } else {
            0f
        }
        val avatarHeight = when {
            !placementLocked -> heightPx * 0.5f
            slamHeightPx > 0f -> slamHeightPx
            avatarHeightPx > 0f -> avatarHeightPx
            else -> nominalHeight
        }
        LaunchedEffect(placementLocked) {
            if (placementLocked) {
                val focus = base ?: ScreenPoint(widthPx / 2f, heightPx * 0.75f)
                cameraController.focusAt(focus.x, focus.y, widthPx, heightPx)
            }
        }
        val offsetY = if (base == null) 0f else base.y - heightPx + avatarHeight * (1f - effectiveFeet)
        val offsetX = if (base == null) 0f else base.x - widthPx / 2f
        Box(
            modifier = if (effectiveWorldPose != null) {
                // 世界相机模式：由渲染器直接用 SLAM 的 view/projection 绘制模型。
                Modifier.fillMaxSize()
            } else {
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .height(with(LocalDensity.current) { avatarHeight.toDp() })
                    .offset {
                        IntOffset(offsetX.roundToInt(), offsetY.roundToInt())
                    }
            },
        ) {
            // 保持同一个 composition 槽位，避免锁定前后重建 WebView 导致模型重新加载。
            avatar(arLook, effectiveWorldPose)
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectDragGestures(
                        onDragStart = { position ->
                            if (placementLocked) currentProjector?.let { ready ->
                                lastFinger = ScreenPoint(position.x, position.y)
                                ready.groundPoint(position.x, position.y)?.let { anchor = it }
                            }
                        },
                        onDrag = { change, _ ->
                            change.consume()
                            if (placementLocked) currentProjector?.let { ready ->
                                lastFinger = ScreenPoint(change.position.x, change.position.y)
                                ready.groundPoint(change.position.x, change.position.y)?.let { anchor = it }
                            }
                        },
                        onDragEnd = {
                            val finger = lastFinger
                            if (finger != null) {
                                onPlaceSaved(
                                    (finger.x / widthPx - 0.5f).coerceIn(-0.35f, 0.35f),
                                    (0.82f - finger.y / heightPx).coerceIn(-0.35f, 0.35f),
                                )
                                cameraController.focusAt(finger.x, finger.y, widthPx, heightPx)
                            }
                        },
                    )
                },
        )

        if (slamEnabled && debugOverlay) {
            val anchorPx = activeSlamProjector?.project(0f, 0f, 0f)
            Text(
                text = buildString {
                    append("SLAM ").append(slam?.let { if (it.tracked) "tracked" else "lost" } ?: "wait")
                    append(" map=").append(slam?.mapId ?: -1)
                    append(" lock=").append(if (placementLocked) "1" else "0")
                    append(if (lockedToSlam) "S" else "I")
                    append(" u/m=").append(String.format("%.2f", worldUnitsPerMeter))
                    append(" h=").append(String.format("%.2f", cameraHeightEstimate))
                    slam?.let { append(" frame=").append(if (it.projection[0] < it.projection[5]) "L" else "P") }
                    if (anchorPx != null) {
                        append(" anchor=").append(anchorPx.x.toInt()).append(",").append(anchorPx.y.toInt())
                    }
                },
                style = MaterialTheme.typography.labelSmall,
                color = Color.White.copy(alpha = 0.45f),
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(top = 200.dp, start = 16.dp),
            )
        }

        if (visualTracking && flowTracker.available) {
            Row(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(top = 116.dp, start = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ArStageChip("重新定位") {
                    cameraPosition = floatArrayOf(0f, 0f)
                    cameraHeightEstimate = cameraHeight
                    flowTracker.reset()
                    anchor = null
                    placementLocked = false
                    lockedToSlam = false
                    lookBase = null
                    arLook = 0f to AR_PLACEMENT_TOP_DOWN
                    placementGeneration += 1
                }
                ArStageChip(if (logging) "停止记录" else "记录日志") {
                    if (logging) {
                        flowTracker.captureDebug = false
                        val file = trackingLog.stop()
                        logging = false
                        Toast.makeText(
                            context,
                            file?.let { "日志已保存：${it.absolutePath}" } ?: "日志未保存",
                            Toast.LENGTH_LONG,
                        ).show()
                    } else {
                        val file = trackingLog.start()
                        flowTracker.captureDebug = file != null
                        logging = file != null
                        if (file != null) {
                            Toast.makeText(context, "开始记录：${file.name}", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ArStageHint(text: String) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Surface(
            shape = CircleShape,
            color = Color.Black.copy(alpha = 0.42f),
            // 提示条位于顶部按钮行（top 116dp，高约 28dp）下方，避免与“重新定位/记录日志”重叠。
            modifier = Modifier.padding(top = 160.dp),
        ) {
            Text(
                text = text,
                style = MaterialTheme.typography.labelSmall,
                color = Color.White.copy(alpha = 0.8f),
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            )
        }
    }
}

/** 把地平面上的 5×5 网格、半径 1.2 地面环和接触阴影投影到屏幕。 */
@Composable
private fun SlamGroundCanvas(
    projector: SlamProjector,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier) {
        val amber = Color(0xFFFFCC40)

        fun visible(point: ScreenPoint): Boolean =
            point.x in 0f..size.width && point.y in 0f..size.height

        fun anchorMarker(point: ScreenPoint?) {
            if (point == null || !visible(point)) return
            val center = Offset(point.x, point.y)
            val accent = Color(0xFF4FC3F7)
            drawCircle(accent, 5f, center)
            drawCircle(accent.copy(alpha = 0.75f), 13f, center, style = Stroke(2f))
            drawLine(accent, Offset(center.x, center.y - 46f), Offset(center.x, center.y - 13f), 3f)
            drawCircle(Color.White, 4f, Offset(center.x, center.y - 48f))
            drawLine(Color.White, Offset(center.x - 6f, center.y), Offset(center.x + 6f, center.y), 2f)
            drawLine(Color.White, Offset(center.x, center.y - 6f), Offset(center.x, center.y + 6f), 2f)
        }

        fun line(from: FloatArray, to: FloatArray, alpha: Float) {
            val start = projector.project(from[0], from[1], from[2]) ?: return
            val end = projector.project(to[0], to[1], to[2]) ?: return
            if (!visible(start) || !visible(end)) return
            drawLine(
                color = amber.copy(alpha = alpha),
                start = Offset(start.x, start.y),
                end = Offset(end.x, end.y),
                strokeWidth = 3f,
            )
        }

        for (index in -2..2) {
            val offset = index * 0.5f
            line(floatArrayOf(-1f, 0f, offset), floatArrayOf(1f, 0f, offset), 0.55f)
            line(floatArrayOf(offset, 0f, -1f), floatArrayOf(offset, 0f, 1f), 0.55f)
        }

        var previous: ScreenPoint? = null
        for (index in 0..48) {
            val angle = (index / 48f) * (Math.PI * 2).toFloat()
            val point = projector.project(
                cos(angle) * AR_SLAM_RING_RADIUS,
                0f,
                sin(angle) * AR_SLAM_RING_RADIUS,
            )
            if (previous != null && point != null && visible(previous) && visible(point)) {
                drawLine(
                    color = amber.copy(alpha = 0.65f),
                    start = Offset(previous.x, previous.y),
                    end = Offset(point.x, point.y),
                    strokeWidth = 3f,
                )
            }
            previous = point
        }
        anchorMarker(projector.project(0f, 0f, 0f))
    }
}

@Composable
private fun ArGroundCanvas(
    projector: ArWorldProjector,
    anchor: FloatArray,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier) {
        val amber = Color(0xFFFFCC40)

        fun visible(point: ScreenPoint): Boolean =
            point.x in 0f..size.width && point.y in 0f..size.height

        fun anchorMarker(point: ScreenPoint?) {
            if (point == null || !visible(point)) return
            val center = Offset(point.x, point.y)
            val accent = Color(0xFF4FC3F7)
            drawCircle(accent, 5f, center)
            drawCircle(accent.copy(alpha = 0.75f), 13f, center, style = Stroke(2f))
            drawLine(accent, Offset(center.x, center.y - 46f), Offset(center.x, center.y - 13f), 3f)
            drawCircle(Color.White, 4f, Offset(center.x, center.y - 48f))
            drawLine(Color.White, Offset(center.x - 6f, center.y), Offset(center.x + 6f, center.y), 2f)
            drawLine(Color.White, Offset(center.x, center.y - 6f), Offset(center.x, center.y + 6f), 2f)
        }

        fun drawWorldLine(from: FloatArray, to: FloatArray, alpha: Float) {
            val start = projector.project(from) ?: return
            val end = projector.project(to) ?: return
            if (!visible(start) || !visible(end)) return
            drawLine(
                color = amber.copy(alpha = alpha),
                start = Offset(start.x, start.y),
                end = Offset(end.x, end.y),
                strokeWidth = 3f,
            )
        }

        for (index in -2..2) {
            val offset = index * 0.5f
            drawWorldLine(
                floatArrayOf(anchor[0] - 1f, anchor[1] + offset, 0f),
                floatArrayOf(anchor[0] + 1f, anchor[1] + offset, 0f),
                0.55f,
            )
            drawWorldLine(
                floatArrayOf(anchor[0] + offset, anchor[1] - 1f, 0f),
                floatArrayOf(anchor[0] + offset, anchor[1] + 1f, 0f),
                0.55f,
            )
        }

        var previous: ScreenPoint? = null
        for (index in 0..48) {
            val angle = (index / 48f) * (Math.PI * 2).toFloat()
            val point = projector.project(
                floatArrayOf(
                    anchor[0] + cos(angle) * AR_RING_RADIUS,
                    anchor[1] + sin(angle) * AR_RING_RADIUS,
                    0f,
                ),
            )
            if (previous != null && point != null && visible(previous) && visible(point)) {
                drawLine(
                    color = amber.copy(alpha = 0.6f),
                    start = Offset(previous.x, previous.y),
                    end = Offset(point.x, point.y),
                    strokeWidth = 3f,
                )
            }
            previous = point
        }

        val base = projector.project(anchor)
        if (base != null && visible(base)) {
            val radius = size.width * 0.16f
            drawOval(
                brush = Brush.radialGradient(
                    colors = listOf(Color.Black.copy(alpha = 0.45f), Color.Transparent),
                    center = Offset(base.x, base.y),
                    radius = radius,
                ),
                topLeft = Offset(base.x - radius, base.y - radius * 0.32f),
                size = Size(radius * 2f, radius * 0.64f),
            )
        }
        anchorMarker(base)
    }
}

@Composable
private fun ArGridCanvas(modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val width = size.width
        val height = size.height
        val horizon = height * 0.58f
        val centerX = width / 2f
        // 反编译版 ArGroundLayer 的常量：琥珀色 #FFCC40、±1 范围 0.5 步长的 5×5 网格、半径 1.2 的地面环。
        val grid = Color(0xFFFFCC40)

        drawRect(
            brush = Brush.verticalGradient(
                colors = listOf(Color.Transparent, grid.copy(alpha = 0.10f)),
                startY = horizon,
                endY = height,
            ),
            topLeft = Offset(0f, horizon),
            size = Size(width, height - horizon),
        )
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(grid.copy(alpha = 0.16f), Color.Transparent),
                center = Offset(centerX, horizon),
                radius = width * 0.7f,
            ),
            radius = width * 0.7f,
            center = Offset(centerX, horizon),
        )
        for (index in 0..4) {
            val t = index / 4f
            val y = horizon + (height - horizon) * (t * t)
            drawLine(
                color = grid.copy(alpha = 0.20f + t * 0.35f),
                start = Offset(0f, y),
                end = Offset(width, y),
                strokeWidth = 3f,
            )
        }
        for (index in 0..4) {
            val offset = index - 2
            drawLine(
                color = grid.copy(alpha = 0.55f),
                start = Offset(centerX + offset * width * 0.012f, horizon),
                end = Offset(centerX + offset * width * 0.16f, height),
                strokeWidth = 3f,
            )
        }
        drawOval(
            color = grid.copy(alpha = 0.6f),
            topLeft = Offset(centerX - width * 0.30f, height * 0.80f),
            size = Size(width * 0.60f, height * 0.10f),
            style = Stroke(width = 3f),
        )
    }
}

@Composable
private fun CallPlaceholder(character: CharacterCardData, accent: Color) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Surface(
            shape = CircleShape,
            color = accent.copy(alpha = 0.85f),
            modifier = Modifier.size(180.dp),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Text(
                    text = character.charName.take(1),
                    fontSize = 68.sp,
                    color = Color.White,
                )
            }
        }
    }
}

@Composable
private fun CallControl(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    active: Boolean,
    onClick: () -> Unit,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 4.dp),
    ) {
        Box(
            modifier = Modifier
                .size(42.dp)
                .clip(CircleShape)
                .background(
                    if (active) Color.White.copy(alpha = 0.18f) else Color.White.copy(alpha = 0.05f),
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = if (active) Color.White else Color.White.copy(alpha = 0.45f),
                modifier = Modifier.size(20.dp),
            )
        }
        Spacer(Modifier.height(4.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = Color.White.copy(alpha = if (active) 0.85f else 0.45f),
        )
    }
}

@Composable
private fun CameraPreviewPane(
    enabled: Boolean,
    controller: CameraPreviewController,
    modifier: Modifier = Modifier,
    showFallbackIcon: Boolean = true,
    lensFacing: Int = CameraCharacteristics.LENS_FACING_FRONT,
) {
    val context = LocalContext.current
    var granted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }
    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { result -> granted = result }

    LaunchedEffect(enabled) {
        if (enabled && !granted) launcher.launch(Manifest.permission.CAMERA)
        if (!enabled) controller.release()
    }

    if (enabled && granted) {
        key(lensFacing) {
            AndroidView(
                modifier = modifier,
                factory = { ctx ->
                    TextureView(ctx).apply {
                        controller.attach(this, lensFacing)
                    }
                },
                onRelease = { view -> controller.release(view) },
            )
        }
    } else if (showFallbackIcon) {
        Box(
            modifier = modifier.background(Color.White.copy(alpha = 0.08f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Default.VideocamOff,
                contentDescription = null,
                tint = Color.White.copy(alpha = 0.5f),
                modifier = Modifier.size(24.dp),
            )
        }
    } else {
        Box(modifier = modifier.background(Color.White.copy(alpha = 0.03f)))
    }
}

/**
 * 最小的 Camera2 预览封装：只取前置摄像头与预览流，
 * 不涉及拍照/录像，避免为此引入 CameraX 依赖。
 */
private class CameraPreviewController {

    private var camera: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private var textureView: TextureView? = null

    private var lensFacing = CameraCharacteristics.LENS_FACING_FRONT
    private var previewSize: AndroidSize? = null
    private var analysisSize: AndroidSize? = null

    /** 预览流尺寸，AR 层据此把投影矫正到预览裁剪。 */
    val previewFrameSize: AndroidSize? get() = previewSize
    var frameRotationDegrees: Int = 0
        private set
    private var imageReader: ImageReader? = null
    private var analysisExecutor: ExecutorService? = null
    private val analysisBusy = AtomicBoolean(false)
    private var previewTargets: List<Surface> = emptyList()
    private var afPoint = 0.5f to 0.75f
    private var activeArrayWidth = 0
    private var activeArrayHeight = 0
    private var sensorOrientation = 90
    private var afModes = IntArray(0)

    /** 相机水平视场角（度），由焦距与传感器尺寸估算。 */
    var horizontalFov: Float = ArWorldProjector.FOV_DEGREES.toFloat()
        private set

    /** Y 平面灰度帧：bytes、宽、高、需要顺时针旋转的角度。 */
    var onYuvFrame: ((ByteArray, Int, Int, Int) -> Unit)? = null

    fun attach(view: TextureView, facing: Int = CameraCharacteristics.LENS_FACING_FRONT) {
        if (lensFacing != facing || textureView !== view) release()
        lensFacing = facing
        textureView = view
        if (view.isAvailable) {
            open(view)
        } else {
            view.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                override fun onSurfaceTextureAvailable(texture: SurfaceTexture, width: Int, height: Int) {
                    open(view)
                }

                override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, width: Int, height: Int) {
                    previewSize?.let { configureTransform(view, it) }
                }

                override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean = true

                override fun onSurfaceTextureUpdated(texture: SurfaceTexture) = Unit
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun open(view: TextureView) {
        val context = view.context
        val manager = context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager ?: return
        val cameraId = runCatching {
            pickCameraId(manager, lensFacing)
        }.getOrNull() ?: return
        val characteristics = runCatching { manager.getCameraCharacteristics(cameraId) }.getOrNull()
        val activeArray = characteristics?.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
        activeArrayWidth = activeArray?.width() ?: 0
        activeArrayHeight = activeArray?.height() ?: 0
        sensorOrientation = characteristics?.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
        afModes = characteristics?.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES) ?: IntArray(0)
        afPoint = screenToSensor(0.5f, 0.75f)
        val map = characteristics?.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        val chosenPreview = choosePreviewSize(map, view.width, view.height)
        val chosenAnalysis = chooseAnalysisSize(map, chosenPreview)
        previewSize = chosenPreview
        analysisSize = chosenAnalysis
        frameRotationDegrees = analysisRotationDegrees(characteristics, view)
        android.util.Log.d(
            "VideoCall",
            "camera id=$cameraId facing=$lensFacing preview=${chosenPreview.width}x${chosenPreview.height} " +
                "analysis=${chosenAnalysis.width}x${chosenAnalysis.height} rotation=$frameRotationDegrees " +
                "afModes=${afModes.joinToString()}",
        )
        horizontalFov = computeHorizontalFov(characteristics) ?: ArWorldProjector.FOV_DEGREES.toFloat()

        val thread = HandlerThread("iris-camera").also { it.start() }
        this.thread = thread
        val handler = Handler(thread.looper)
        this.handler = handler
        val texture = view.surfaceTexture ?: return
        texture.setDefaultBufferSize(chosenPreview.width, chosenPreview.height)
        val previewSurface = Surface(texture)
        previewTargets = listOf(previewSurface)
        configureTransform(view, chosenPreview)

        val reader = ImageReader.newInstance(
            chosenAnalysis.width,
            chosenAnalysis.height,
            ImageFormat.YUV_420_888,
            2,
        )
        imageReader = reader
        previewTargets = listOf(previewSurface, reader.surface)
        val executor = Executors.newSingleThreadExecutor()
        analysisExecutor = executor
        analysisBusy.set(false)
        reader.setOnImageAvailableListener({ source ->
            val image = runCatching { source.acquireLatestImage() }.getOrNull()
            if (image == null) {
                return@setOnImageAvailableListener
            }
            if (onYuvFrame == null) {
                image.close()
                return@setOnImageAvailableListener
            }
            if (!analysisBusy.compareAndSet(false, true)) {
                image.close()
                return@setOnImageAvailableListener
            }
            try {
                val plane = image.planes.firstOrNull()
                if (plane == null || plane.pixelStride != 1) {
                    analysisBusy.set(false)
                    return@setOnImageAvailableListener
                }
                val width = image.width
                val height = image.height
                val data = ByteArray(width * height)
                val buffer = plane.buffer
                buffer.rewind()
                val rowStride = plane.rowStride
                if (rowStride == width) {
                    buffer.get(data)
                } else {
                    for (row in 0 until height) {
                        buffer.position(row * rowStride)
                        buffer.get(data, row * width, width)
                    }
                }
                val degrees = analysisRotationDegrees(characteristics, view)
                image.close()
                runCatching {
                    executor.execute {
                        try {
                            onYuvFrame?.invoke(data, width, height, degrees)
                        } finally {
                            analysisBusy.set(false)
                        }
                    }
                }.onFailure { analysisBusy.set(false) }
            } catch (_: Throwable) {
                analysisBusy.set(false)
            } finally {
                runCatching { image.close() }
            }
        }, handler)

        runCatching {
            manager.openCamera(
                cameraId,
                object : CameraDevice.StateCallback() {
                    override fun onOpened(device: CameraDevice) {
                        camera = device
                        runCatching {
                            device.createCaptureSession(
                                listOf(previewSurface, reader.surface),
                                object : CameraCaptureSession.StateCallback() {
                                    override fun onConfigured(configured: CameraCaptureSession) {
                                        session = configured
                                        val request = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
                                            .apply {
                                                addTarget(previewSurface)
                                                addTarget(reader.surface)
                                                applyFocus(this)
                                            }
                                            .build()
                                        runCatching {
                                            configured.setRepeatingRequest(request, null, handler)
                                            focusAt(0.5f, 0.75f, 1f, 1f)
                                        }
                                    }

                                    override fun onConfigureFailed(configured: CameraCaptureSession) = Unit
                                },
                                handler,
                            )
                        }.onFailure {
                            // 双 Surface 会话失败时退回只预览，保证画面至少可用。
                            runCatching { imageReader?.close() }
                            imageReader = null
                            runCatching {
                                device.createCaptureSession(
                                    listOf(previewSurface),
                                    object : CameraCaptureSession.StateCallback() {
                                        override fun onConfigured(configured: CameraCaptureSession) {
                                            session = configured
                                            val request = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
                                                .apply {
                                                    addTarget(previewSurface)
                                                    applyFocus(this)
                                                }
                                                .build()
                                            runCatching {
                                                configured.setRepeatingRequest(request, null, handler)
                                            }
                                        }

                                        override fun onConfigureFailed(configured: CameraCaptureSession) = Unit
                                    },
                                    handler,
                                )
                            }
                        }
                    }

                    override fun onDisconnected(device: CameraDevice) {
                        device.close()
                        camera = null
                    }

                    override fun onError(device: CameraDevice, error: Int) {
                        device.close()
                        camera = null
                    }
                },
                handler,
            )
        }.onFailure { release() }
    }

    /** 按屏幕坐标（像素）设置连续对焦/测光区域；用于网格锁定与拖动改位置。 */
    fun focusAt(viewX: Float, viewY: Float, viewWidth: Float, viewHeight: Float) {
        if (viewWidth <= 0f || viewHeight <= 0f) return
        afPoint = screenToSensor(
            (viewX / viewWidth).coerceIn(0f, 1f),
            (viewY / viewHeight).coerceIn(0f, 1f),
        )
        val device = camera ?: return
        val activeSession = session ?: return
        val targets = previewTargets
        if (targets.isEmpty()) return
        runCatching {
            val autoMode = afModes.contains(CaptureRequest.CONTROL_AF_MODE_AUTO)
            if (autoMode) {
                val focusRequest = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
                    .apply {
                        targets.forEach { addTarget(it) }
                        applyFocus(this)
                        set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_AUTO)
                        set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_START)
                    }
                    .build()
                activeSession.capture(focusRequest, null, handler)
            }
            val repeating = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
                .apply {
                    targets.forEach { addTarget(it) }
                    applyFocus(this)
                    set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_IDLE)
                }
                .build()
            activeSession.setRepeatingRequest(repeating, null, handler)
        }
    }

    private fun screenToSensor(u: Float, v: Float): Pair<Float, Float> = when (sensorOrientation % 360) {
        90 -> v to (1f - u)
        270 -> (1f - v) to u
        180 -> (1f - u) to (1f - v)
        else -> u to v
    }

    private fun applyFocus(builder: CaptureRequest.Builder) {
        if (activeArrayWidth <= 0 || activeArrayHeight <= 0 || afModes.isEmpty()) return
        val centerX = (afPoint.first * activeArrayWidth).toInt().coerceIn(0, activeArrayWidth - 1)
        val centerY = (afPoint.second * activeArrayHeight).toInt().coerceIn(0, activeArrayHeight - 1)
        val half = (minOf(activeArrayWidth, activeArrayHeight) * 0.12f).toInt().coerceAtLeast(24)
        val rect = Rect(
            (centerX - half).coerceAtLeast(0),
            (centerY - half).coerceAtLeast(0),
            (centerX + half).coerceAtMost(activeArrayWidth),
            (centerY + half).coerceAtMost(activeArrayHeight),
        )
        val metering = MeteringRectangle(rect, MeteringRectangle.METERING_WEIGHT_MAX)
        builder.set(CaptureRequest.CONTROL_AF_REGIONS, arrayOf(metering))
        builder.set(CaptureRequest.CONTROL_AE_REGIONS, arrayOf(metering))
        // AIDL region 只在 AUTO/MACRO 下生效，连续对焦会忽略自定义区域。
        val mode = when {
            afModes.contains(CaptureRequest.CONTROL_AF_MODE_AUTO) -> CaptureRequest.CONTROL_AF_MODE_AUTO
            afModes.contains(CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE) ->
                CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE
            else -> return
        }
        builder.set(CaptureRequest.CONTROL_AF_MODE, mode)
    }

    /** 同方向可能有主摄/超广/微距/景深多颗，优先选支持 AF 且传感器面积最大的主摄。 */
    private fun pickCameraId(manager: CameraManager, facing: Int): String? {
        data class Candidate(val id: String, val autofocus: Boolean, val area: Long, val focal: Float)
        val candidates = manager.cameraIdList.mapNotNull { id ->
            val characteristics = runCatching { manager.getCameraCharacteristics(id) }.getOrNull()
                ?: return@mapNotNull null
            if (characteristics.get(CameraCharacteristics.LENS_FACING) != facing) return@mapNotNull null
            val array = characteristics.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
            val area = (array?.width()?.toLong() ?: 0L) * (array?.height()?.toLong() ?: 0L)
            val modes = characteristics.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES) ?: IntArray(0)
            val autofocus = modes.any {
                it == CaptureRequest.CONTROL_AF_MODE_AUTO ||
                    it == CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE
            }
            val focal = characteristics.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
                ?.maxOrNull() ?: 0f
            Candidate(id, autofocus, area, focal)
        }
        android.util.Log.d(
            "VideoCall",
            "cameras facing=$facing -> " + candidates.joinToString { "${it.id}(af=${it.autofocus},area=${it.area},f=${it.focal})" },
        )
        return candidates.maxWithOrNull(
            compareBy<Candidate> { it.autofocus }.thenBy { it.area }.thenBy { it.focal },
        )?.id ?: manager.cameraIdList.firstOrNull()
    }

    private fun choosePreviewSize(map: StreamConfigurationMap?, viewWidth: Int, viewHeight: Int): AndroidSize {
        val sizes = map?.getOutputSizes(SurfaceTexture::class.java)?.toList().orEmpty()
        if (sizes.isEmpty()) return AndroidSize(1280, 720)
        val capped = sizes.filter { it.width.toLong() * it.height <= 1920L * 1080L }
        val pool = capped.ifEmpty { sizes }
        // 档位都是传感器横向尺寸，屏幕竖屏只是个显示角度：一律按 16:9 的各向异性比较，
        // 否则 4:3 档位会被误判为更接近目标，导致预览被裁掉大半宽度。
        val target = 16f / 9f
        // 先保留面积足够大的档位，再在这些档位里选最接近屏幕比例的，避免为了比例选到 352x288。
        val maxArea = pool.maxOfOrNull { it.width.toLong() * it.height } ?: 0L
        val large = pool.filter { it.width.toLong() * it.height >= maxArea * 3 / 5 }
        return (large.ifEmpty { pool })
            .minByOrNull { abs(max(it.width.toFloat() / it.height, it.height.toFloat() / it.width) - target) }
            ?: pool.maxByOrNull { it.width.toLong() * it.height }
            ?: sizes.first()
    }

    private fun chooseAnalysisSize(map: StreamConfigurationMap?, preview: AndroidSize): AndroidSize {
        val sizes = map?.getOutputSizes(ImageFormat.YUV_420_888)?.toList().orEmpty()
        if (sizes.isEmpty()) return AndroidSize(480, 360)
        // 分析流与预览流必须是同一视场，否则两边的裁剪比例无法对齐。
        val targetAspect = preview.width.toFloat() / preview.height
        sizes
            .filter { abs(it.width.toFloat() / it.height - targetAspect) < 0.02f }
            .filter { it.width.toLong() * it.height <= 1280L * 960L }
            .maxByOrNull { it.width.toLong() * it.height }
            ?.let { return it }
        for ((width, height) in listOf(1280 to 720, 960 to 720, 640 to 480, 480 to 360, 320 to 240)) {
            sizes.firstOrNull { it.width == width && it.height == height }?.let { return it }
        }
        return sizes.filter { it.width.toLong() * it.height <= 640L * 480L }
            .maxByOrNull { it.width.toLong() * it.height }
            ?: sizes.first()
    }

    private fun computeHorizontalFov(characteristics: CameraCharacteristics?): Float? {
        val focal = characteristics?.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
            ?.firstOrNull() ?: return null
        val sensor = characteristics?.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE) ?: return null
        if (focal <= 0f || sensor.width <= 0f) return null
        val fov = Math.toDegrees(2.0 * atan(sensor.width / (2.0 * focal))).toFloat()
        return fov.takeIf { it in 30f..120f }
    }

    private fun analysisRotationDegrees(characteristics: CameraCharacteristics?, view: TextureView): Int {
        val sensor = characteristics?.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
        val display = when (view.display?.rotation ?: Surface.ROTATION_0) {
            Surface.ROTATION_90 -> 90
            Surface.ROTATION_180 -> 180
            Surface.ROTATION_270 -> 270
            else -> 0
        }
        val front = characteristics?.get(CameraCharacteristics.LENS_FACING) ==
            CameraCharacteristics.LENS_FACING_FRONT
        return if (front) (sensor + display) % 360 else (sensor - display + 360) % 360
    }

    private fun previewTransform(view: TextureView, preview: AndroidSize): Matrix? {
        val viewWidth = view.width
        val viewHeight = view.height
        if (viewWidth == 0 || viewHeight == 0) return null
        val rotation = view.display?.rotation ?: Surface.ROTATION_0
        val matrix = Matrix()
        val viewRect = RectF(0f, 0f, viewWidth.toFloat(), viewHeight.toFloat())
        val bufferRect = RectF(0f, 0f, preview.height.toFloat(), preview.width.toFloat())
        val centerX = viewRect.centerX()
        val centerY = viewRect.centerY()
        bufferRect.offset(centerX - bufferRect.centerX(), centerY - bufferRect.centerY())
        matrix.setRectToRect(viewRect, bufferRect, Matrix.ScaleToFit.FILL)
        val scale = max(
            viewHeight.toFloat() / preview.height,
            viewWidth.toFloat() / preview.width,
        )
        matrix.postScale(scale, scale, centerX, centerY)
        if (rotation == Surface.ROTATION_90 || rotation == Surface.ROTATION_270) {
            matrix.postRotate(90f * (rotation - 2), centerX, centerY)
        } else if (rotation == Surface.ROTATION_180) {
            matrix.postRotate(180f, centerX, centerY)
        }
        return matrix
    }

    private fun configureTransform(view: TextureView, preview: AndroidSize) {
        previewTransform(view, preview)?.let { view.setTransform(it) }
    }

    fun release(view: TextureView? = null) {
        if (view != null && textureView !== view) return
        runCatching { session?.close() }
        runCatching { camera?.close() }
        runCatching { imageReader?.close() }
        session = null
        camera = null
        textureView = null
        previewSize = null
        imageReader = null
        analysisExecutor?.shutdown()
        analysisExecutor = null
        analysisBusy.set(false)
        thread?.quitSafely()
        thread = null
        handler = null
    }

    fun captureJpeg(): String? {
        val bitmap = textureView?.bitmap ?: return null
        return runCatching {
            val largest = maxOf(bitmap.width, bitmap.height)
            val scaled = if (largest > 1024) {
                val ratio = 1024f / largest
                Bitmap.createScaledBitmap(
                    bitmap,
                    (bitmap.width * ratio).toInt().coerceAtLeast(1),
                    (bitmap.height * ratio).toInt().coerceAtLeast(1),
                    true,
                )
            } else {
                bitmap
            }
            try {
                val output = ByteArrayOutputStream()
                scaled.compress(Bitmap.CompressFormat.JPEG, 82, output)
                "data:image/jpeg;base64," + Base64.encodeToString(output.toByteArray(), Base64.NO_WRAP)
            } finally {
                if (scaled !== bitmap) scaled.recycle()
                bitmap.recycle()
            }
        }.getOrNull()
    }
}

@Composable
private fun ArStageChip(text: String, onClick: () -> Unit) {
    Surface(
        shape = CircleShape,
        color = Color.Black.copy(alpha = 0.42f),
        modifier = Modifier.clickable(onClick = onClick),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = Color.White.copy(alpha = 0.85f),
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
        )
    }
}

private fun buildTrackingRow(
    timestamp: Long,
    width: Int,
    height: Int,
    rotationDegrees: Int,
    fov: Float,
    cameraHeightSet: Float,
    cameraHeightEstimate: Float,
    cameraX: Float,
    cameraY: Float,
    imu: FloatArray?,
    debug: FlowDebug,
    motion: VisualMotion?,
): String {
    val fields = ArrayList<String>(41)
    fields.add(timestamp.toString())
    fields.add(width.toString())
    fields.add(height.toString())
    fields.add(rotationDegrees.toString())
    fields.add(formatFloat(fov))
    fields.add(formatFloat(cameraHeightSet))
    fields.add(formatFloat(cameraHeightEstimate))
    fields.add(formatFloat(cameraX))
    fields.add(formatFloat(cameraY))
    for (index in 0 until 9) {
        fields.add(imu?.getOrNull(index)?.let(::formatFloat).orEmpty())
    }
    fields.add(debug.tracked.toString())
    fields.add(debug.inliers.toString())
    fields.add(formatFloat(debug.scale))
    fields.add(formatFloat(debug.imageRotation))
    for (index in 0 until 6) {
        fields.add(debug.affine?.getOrNull(index)?.let(::formatDouble).orEmpty())
    }
    for (index in 0 until 9) {
        fields.add(debug.homography?.getOrNull(index)?.let(::formatDouble).orEmpty())
    }
    fields.add(formatFloat(motion?.dx ?: 0f))
    fields.add(formatFloat(motion?.dy ?: 0f))
    fields.add(formatFloat(motion?.dHeight ?: 0f))
    fields.add(
        buildString {
            var index = 0
            while (index + 3 < debug.points.size) {
                if (isNotEmpty()) append(';')
                append(formatFloat(debug.points[index]))
                append(':')
                append(formatFloat(debug.points[index + 1]))
                append(':')
                append(formatFloat(debug.points[index + 2]))
                append(':')
                append(formatFloat(debug.points[index + 3]))
                index += 4
            }
        },
    )
    fields.add(debug.note)
    return fields.joinToString(",")
}

private fun formatFloat(value: Float): String = String.format(Locale.US, "%.4f", value)

private fun formatDouble(value: Double): String = String.format(Locale.US, "%.6f", value)

private fun formatDuration(seconds: Int): String {
    val minutes = seconds / 60
    val rest = seconds % 60
    return String.format("%02d:%02d", minutes, rest)
}
