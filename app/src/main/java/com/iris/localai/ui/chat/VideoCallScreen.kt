package com.iris.localai.ui.chat

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
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
import com.iris.localai.character.CharacterCardData
import com.iris.localai.character.CharacterModelEntry
import com.iris.localai.data.CharacterModel
import com.iris.localai.data.VideoCallStage
import com.iris.localai.scene.SceneConfig
import com.iris.localai.voice.VoiceRecorder
import java.io.File
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

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
    val running by viewModel.running.collectAsStateWithLifecycle()
    val appSettings by viewModel.settings.collectAsStateWithLifecycle()
    val ttsAmplitude by viewModel.ttsAmplitude.collectAsStateWithLifecycle()
    val stage = appSettings.videoCallStage
    val context = LocalContext.current
    val recorder = remember { VoiceRecorder() }
    val cameraController = remember { CameraPreviewController() }
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

    LaunchedEffect(muted, audioGranted, running) {
        if (muted || !audioGranted) {
            if (muted) listenStatus = "麦克风已静音"
            return@LaunchedEffect
        }
        while (isActive) {
            if (running || viewModel.ttsSpeaking.value) {
                listenStatus = if (viewModel.ttsSpeaking.value) "正在说话…" else "正在思考…"
                delay(300)
                continue
            }
            listenStatus = "正在聆听…"
            val file = recorder.record(maxMillis = 5_000)
            if (file == null) {
                listenStatus = "没有听清，请再说一次"
                continue
            }
            listenStatus = "正在识别…"
            val result = viewModel.transcribeVoice(file)
            file.delete()
            result.onSuccess { text ->
                if (text.isNotBlank()) {
                    listenStatus = "你说：$text"
                    val frame = if (cameraOn) cameraController.captureJpeg() else null
                    viewModel.sendCallTurn(text, frame)
                }
            }.onFailure {
                listenStatus = "识别失败：${it.message ?: "未知错误"}"
                delay(800)
            }
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
                        cameraController = cameraController,
                        height = appSettings.videoCallArHeight,
                        offsetX = appSettings.videoCallArOffsetX,
                        offsetY = appSettings.videoCallArOffsetY,
                        showGrid = appSettings.videoCallArGrid,
                        cameraHeight = appSettings.videoCallArCameraHeight,
                        visualTracking = appSettings.videoCallArVisualTracking,
                        feetFraction = when {
                            model == CharacterModel.TWO_D -> 0.99f
                            mmdFile != null -> 0.70f
                            else -> 0.82f
                        },
                        footOffset = appSettings.videoCallArFootOffset,
                        onPlaceSaved = viewModel::updateVideoCallPlacement,
                    ) {
                        CallAvatar(
                            character = character,
                            model = model,
                            activeModel = activeModel,
                            modelFile = modelFile,
                            live2dFile = live2dFile,
                            mmdFile = mmdFile,
                            avatarControl = avatarControl,
                            mouthAmplitude = ttsAmplitude,
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
                        "长按屏幕可查看通话信息"
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
                motionEndpoint = motionEndpoint,
                motionIdle = motionIdle,
                motionPack = motionPack,
                motionAxisFix = motionAxisFix,
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
    feetFraction: Float,
    footOffset: Float,
    onPlaceSaved: (Float, Float) -> Unit,
    avatar: @Composable () -> Unit,
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
    // 姿态投影按竖屏推导；横屏时退回静态网格，避免坐标轴错位。
    val portrait = LocalConfiguration.current.orientation == Configuration.ORIENTATION_PORTRAIT
    val rotation = if (portrait) tracker.rotation else null

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

        // 实验性视觉位移：ImageReader 的 Y 平面直接进光流，约 10–15fps。
        DisposableEffect(visualTracking, cameraOn, cameraHeight) {
            if (visualTracking && cameraOn && flowTracker.available) {
                cameraController.onYuvFrame = { bytes, width, height, degrees ->
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
                            cameraHeightEstimate = (cameraHeightEstimate + motion.dHeight * 0.6f)
                                .coerceIn(0.5f, 2.5f)
                        }
                    }
                }
            }
            onDispose {
                cameraController.onYuvFrame = null
                if (!visualTracking) flowTracker.reset()
            }
        }

        if (projector != null && anchor != null) {
            if (showGrid) {
                ArGroundCanvas(
                    projector = projector,
                    anchor = anchor!!,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        } else if (showGrid) {
            ArGridCanvas(modifier = Modifier.fillMaxSize())
        }

        when {
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

        val base = if (projector != null && anchor != null) projector.project(anchor!!) else null
        val nominalHeight = heightPx * (0.66f * (height / 1.6f)).coerceIn(0.3f, 0.95f)
        val avatarHeight = if (avatarHeightPx > 0f) avatarHeightPx else nominalHeight
        val offsetY = if (base == null) 0f else base.y - heightPx + avatarHeight * (1f - effectiveFeet)
        val offsetX = if (base == null) 0f else base.x - widthPx / 2f
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(with(LocalDensity.current) { avatarHeight.toDp() })
                .offset {
                    IntOffset(offsetX.roundToInt(), offsetY.roundToInt())
                },
        ) {
            avatar()
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectDragGestures(
                        onDragStart = { position ->
                            currentProjector?.let { ready ->
                                lastFinger = ScreenPoint(position.x, position.y)
                                ready.groundPoint(position.x, position.y)?.let { anchor = it }
                            }
                        },
                        onDrag = { change, _ ->
                            change.consume()
                            currentProjector?.let { ready ->
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
                            }
                        },
                    )
                },
        )

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
            modifier = Modifier.padding(top = 116.dp),
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
private fun ArGroundCanvas(
    projector: ArWorldProjector,
    anchor: FloatArray,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier) {
        val amber = Color(0xFFFFCC40)

        fun drawWorldLine(from: FloatArray, to: FloatArray, alpha: Float) {
            val start = projector.project(from) ?: return
            val end = projector.project(to) ?: return
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
            if (previous != null && point != null) {
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
        if (base != null) {
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
    private var imageReader: ImageReader? = null
    private var analysisExecutor: ExecutorService? = null
    private val analysisBusy = AtomicBoolean(false)

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

    private fun open(view: TextureView) {
        val context = view.context
        val manager = context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager ?: return
        val cameraId = runCatching {
            manager.cameraIdList.firstOrNull { id ->
                manager.getCameraCharacteristics(id)
                    .get(CameraCharacteristics.LENS_FACING) == lensFacing
            } ?: manager.cameraIdList.first()
        }.getOrNull() ?: return
        val characteristics = runCatching { manager.getCameraCharacteristics(cameraId) }.getOrNull()
        val map = characteristics?.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        val chosenPreview = choosePreviewSize(map, view.width, view.height)
        val chosenAnalysis = chooseAnalysisSize(map)
        previewSize = chosenPreview
        horizontalFov = computeHorizontalFov(characteristics) ?: ArWorldProjector.FOV_DEGREES.toFloat()

        val thread = HandlerThread("iris-camera").also { it.start() }
        this.thread = thread
        val handler = Handler(thread.looper)
        this.handler = handler
        val texture = view.surfaceTexture ?: return
        texture.setDefaultBufferSize(chosenPreview.width, chosenPreview.height)
        val previewSurface = Surface(texture)
        configureTransform(view, chosenPreview)

        val reader = ImageReader.newInstance(
            chosenAnalysis.width,
            chosenAnalysis.height,
            ImageFormat.YUV_420_888,
            2,
        )
        imageReader = reader
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
                                                .apply { addTarget(previewSurface) }
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

    private fun choosePreviewSize(map: StreamConfigurationMap?, viewWidth: Int, viewHeight: Int): AndroidSize {
        val sizes = map?.getOutputSizes(SurfaceTexture::class.java)?.toList().orEmpty()
        if (sizes.isEmpty()) return AndroidSize(1280, 720)
        val portrait = viewWidth <= 0 || viewHeight >= viewWidth
        val target = if (portrait) 9f / 16f else 16f / 9f
        val capped = sizes.filter { it.width.toLong() * it.height <= 1280L * 720L }
        val pool = capped.ifEmpty {
            sizes.filter { it.width.toLong() * it.height <= 1920L * 1080L }
        }.ifEmpty { sizes }
        return pool.minByOrNull { abs(it.width.toFloat() / it.height - target) } ?: sizes.first()
    }

    private fun chooseAnalysisSize(map: StreamConfigurationMap?): AndroidSize {
        val sizes = map?.getOutputSizes(ImageFormat.YUV_420_888)?.toList().orEmpty()
        if (sizes.isEmpty()) return AndroidSize(480, 360)
        for ((width, height) in listOf(640 to 480, 480 to 360, 320 to 240)) {
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

    private fun configureTransform(view: TextureView, preview: AndroidSize) {
        val viewWidth = view.width
        val viewHeight = view.height
        if (viewWidth == 0 || viewHeight == 0) return
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
        view.setTransform(matrix)
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
