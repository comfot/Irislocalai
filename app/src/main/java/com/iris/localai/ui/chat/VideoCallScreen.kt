package com.iris.localai.ui.chat

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import android.graphics.SurfaceTexture
import java.io.ByteArrayOutputStream
import android.util.Base64
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import android.view.TextureView
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
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
import com.iris.localai.scene.SceneConfig
import com.iris.localai.voice.VoiceRecorder
import java.io.File
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
                SceneBackground(scene = scene, modifier = Modifier.fillMaxSize())
                when {
                    model == CharacterModel.THREE_D && modelFile != null -> CharacterModel3DView(
                        modelFile = modelFile,
                        modelType = activeModel?.type?.name.orEmpty(),
                        accentHex = String.format("#%06X", (character.accent ?: 0xFF7DB3FFL) and 0xFFFFFFL),
                        dark = true,
                        portrait = true,
                        interactive = false,
                        motionEndpoint = motionEndpoint,
                        motionIdle = motionIdle,
                        motionPack = motionPack,
                        motionAxisFix = motionAxisFix,
                        modifier = Modifier.fillMaxSize(),
                    )
                    model == CharacterModel.THREE_D && mmdFile != null -> MMDView(
                        modelFile = mmdFile,
                        accentHex = String.format("#%06X", (character.accent ?: 0xFF7DB3FFL) and 0xFFFFFFL),
                        dark = true,
                        mood = avatarControl.mood,
                        speaking = avatarControl.speaking,
                        portrait = true,
                        interactive = false,
                        modifier = Modifier.fillMaxSize(),
                    )
                    model == CharacterModel.TWO_D && live2dFile != null -> Live2DView(
                        modelFile = live2dFile,
                        accentHex = String.format("#%06X", (character.accent ?: 0xFF7DB3FFL) and 0xFFFFFFL),
                        dark = true,
                        speaking = avatarControl.speaking,
                        mood = avatarControl.mood,
                        portrait = true,
                        interactive = false,
                        expressionsEnabled = l2dExpressions,
                        motionsEnabled = l2dMotions,
                        idleMotionEnabled = l2dIdleMotion,
                        tapMotionEnabled = l2dTapMotion,
                        mouthSyncEnabled = l2dMouthSync,
                        modifier = Modifier.fillMaxSize(),
                    )
                    else -> CallPlaceholder(character = character, accent = accent)
                }
                // 通话中模型不接受用户手势：这一层盖在渲染器之上。
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

            // 右上角摄像头小窗
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

            // 摄像头关闭时的提示
            if (!cameraOn) {
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
                    text = "长按屏幕可查看通话信息",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White.copy(alpha = 0.55f),
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                )
            }
        }
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
        AndroidView(
            modifier = modifier,
            factory = { ctx ->
                TextureView(ctx).apply {
                    controller.attach(this)
                }
            },
            onRelease = { controller.release() },
        )
    } else {
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

    fun attach(view: TextureView) {
        textureView = view
        if (view.isAvailable) {
            open(view)
        } else {
            view.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                override fun onSurfaceTextureAvailable(texture: SurfaceTexture, width: Int, height: Int) {
                    open(view)
                }

                override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, width: Int, height: Int) = Unit

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
                    .get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_FRONT
            } ?: manager.cameraIdList.first()
        }.getOrNull() ?: return

        val thread = HandlerThread("iris-camera").also { it.start() }
        this.thread = thread
        val handler = Handler(thread.looper)
        this.handler = handler
        val texture = view.surfaceTexture ?: return
        val surface = Surface(texture)

        runCatching {
            manager.openCamera(
                cameraId,
                object : CameraDevice.StateCallback() {
                    override fun onOpened(device: CameraDevice) {
                        camera = device
                        runCatching {
                            device.createCaptureSession(
                                listOf(surface),
                                object : CameraCaptureSession.StateCallback() {
                                    override fun onConfigured(configured: CameraCaptureSession) {
                                        session = configured
                                        val request = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
                                            .apply { addTarget(surface) }
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

    fun release() {
        runCatching { session?.close() }
        runCatching { camera?.close() }
        session = null
        camera = null
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

private fun formatDuration(seconds: Int): String {
    val minutes = seconds / 60
    val rest = seconds % 60
    return String.format("%02d:%02d", minutes, rest)
}
