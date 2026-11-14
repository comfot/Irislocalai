package com.iris.localai.ui.chat

import android.graphics.BitmapFactory
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.outlined.EmojiEmotions
import androidx.compose.material.icons.outlined.Videocam
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.iris.localai.IrisApp
import com.iris.localai.agent.AssistantMessageParser
import com.iris.localai.agent.ChatRole
import com.iris.localai.character.CharacterCardData
import com.iris.localai.character.CharacterEntry
import com.iris.localai.character.CharacterModelEntry
import com.iris.localai.character.CharacterModelType
import com.iris.localai.data.CharacterModel
import com.iris.localai.data.VrmMotionEngine
import com.iris.localai.scene.SceneConfig
import com.iris.localai.scene.StageBaseStyle
import java.io.File

@Composable
fun LifeSpaceScreen(
    viewModel: ChatViewModel,
    onFullscreenChange: (Boolean) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val messages by viewModel.messages.collectAsStateWithLifecycle()
    val input by viewModel.input.collectAsStateWithLifecycle()
    val running by viewModel.running.collectAsStateWithLifecycle()
    val ttsSpeaking by viewModel.ttsSpeaking.collectAsStateWithLifecycle()
    val ttsAmplitude by viewModel.ttsAmplitude.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val performanceMood by viewModel.performanceMood.collectAsStateWithLifecycle()
    val performanceIntensity by viewModel.performanceIntensity.collectAsStateWithLifecycle()
    val activeCharacter by viewModel.activeCharacter.collectAsStateWithLifecycle()
    val characterModels by viewModel.characterModels.collectAsStateWithLifecycle()
    val activeModelId by viewModel.activeCharacterModelId.collectAsStateWithLifecycle()
    val bindings by viewModel.characterModelBindings.collectAsStateWithLifecycle()
    val scene by viewModel.activeScene.collectAsStateWithLifecycle()
    val entry = activeCharacter
    val context = LocalContext.current
    val storage = remember(context) { (context.applicationContext as IrisApp).graph.storage }
    val boundModelId = entry?.let { bindings[it.id] }
    val activeModel = characterModels.firstOrNull { it.id == boundModelId }
        ?: characterModels.firstOrNull { it.id == activeModelId }
    val activeModelFile = remember(activeModel?.id) {
        activeModel?.let {
            storage.modelFile("character_models", it.directory, it.entryFile)
        }
    }
    val modelFile = if (settings.characterModel == CharacterModel.THREE_D &&
        activeModel != null &&
        (activeModel.type == CharacterModelType.VRM || activeModel.type == CharacterModelType.GLB)
    ) {
        activeModelFile
    } else {
        null
    }
    val live2dFile = if (settings.characterModel == CharacterModel.TWO_D &&
        activeModel?.type == CharacterModelType.LIVE2D
    ) {
        activeModelFile
    } else {
        null
    }
    val mmdFile = if (settings.characterModel == CharacterModel.THREE_D &&
        activeModel?.type == CharacterModelType.MMD
    ) {
        activeModelFile
    } else {
        null
    }
    val modelThumbnail = remember(activeModel?.thumbnail) {
        activeModel?.thumbnail?.let { File(it) }?.takeIf { it.isFile }
    }
    val lastAssistantText = messages.lastOrNull { it.role == ChatRole.ASSISTANT }
        ?.text
        ?.let { AssistantMessageParser.mainText(it) }
        .orEmpty()
    val live2dMood = remember(running, lastAssistantText, performanceMood) {
        when {
            performanceMood != "neutral" -> performanceMood
            running -> "thinking"
            else -> detectMood(lastAssistantText)
        }
    }
    val live2dSpeaking = running || ttsSpeaking

    if (entry == null) {
        Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("没有可用角色卡")
        }
        return
    }
    val character = entry.card
    var fullscreen by remember { mutableStateOf(false) }
    LaunchedEffect(fullscreen) { onFullscreenChange(fullscreen) }
    BackHandler(enabled = fullscreen) { fullscreen = false }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .then(if (fullscreen) Modifier else Modifier.imePadding()),
        ) {
            CharacterStage(
                entry = entry,
                activeModel = activeModel,
                modelFile = modelFile,
                live2dFile = live2dFile,
                mmdFile = mmdFile,
                modelAvailable = activeModelFile != null,
                modelThumbnail = modelThumbnail,
                scene = scene,
                live2dMood = live2dMood,
                moodIntensity = performanceIntensity,
                live2dSpeaking = live2dSpeaking,
                mouthAmplitude = ttsAmplitude,
                model = settings.characterModel,
                fullscreen = fullscreen,
                l2dExpressions = settings.l2dExpressionsEnabled,
                l2dMotions = settings.l2dMotionsEnabled,
                l2dIdleMotion = settings.l2dIdleMotionEnabled,
                l2dTapMotion = settings.l2dTapMotionEnabled,
                l2dMouthSync = settings.l2dMouthSyncEnabled,
                motionEndpoint = if (settings.vrmMotionEngine == VrmMotionEngine.HAST_SEC) {
                    "https://iris.local/hast"
                } else {
                    ""
                },
                motionIdle = settings.motionAutoIdle,
                motionPack = if (settings.vrmMotionEngine == VrmMotionEngine.HAST_SEC) "hast" else "vrma",
                motionAxisFix = settings.motionAxisFix,
                onEnterFullscreen = { fullscreen = true },
                onExitFullscreen = { fullscreen = false },
                onModelChange = { model ->
                    viewModel.updateSettings { it.copy(characterModel = model) }
                },
                modifier = if (fullscreen) {
                    Modifier.fillMaxSize()
                } else {
                    Modifier.fillMaxWidth().fillMaxHeight(0.66f)
                },
            )

            if (!fullscreen) {
                if (messages.isEmpty()) {
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .padding(24.dp),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(
                            text = character.greeting,
                            style = MaterialTheme.typography.titleMedium,
                            textAlign = TextAlign.Center,
                        )
                        if (!scene?.intro.isNullOrBlank()) {
                            Spacer(Modifier.height(6.dp))
                            Text(
                                text = scene?.intro.orEmpty(),
                                style = MaterialTheme.typography.bodySmall,
                                textAlign = TextAlign.Center,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = "文字、语音、视频通话与表情都在下方输入栏。",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                } else {
                    ChatMessageList(
                        messages = messages,
                        running = running,
                        modifier = Modifier.weight(1f),
                        bubbleStyle = settings.bubbleStyle,
                        showRoleChatCard = settings.roleChatCardEnabled,
                        renderPluginXml = viewModel::renderPluginXml,
                    )
                }

                LifeComposer(
                    viewModel = viewModel,
                    character = character,
                    input = input,
                    running = running,
                    activeModel = activeModel,
                    modelFile = modelFile,
                    live2dFile = live2dFile,
                    mmdFile = mmdFile,
                    model = settings.characterModel,
                    scene = scene,
                    l2dExpressions = settings.l2dExpressionsEnabled,
                    l2dMotions = settings.l2dMotionsEnabled,
                    l2dIdleMotion = settings.l2dIdleMotionEnabled,
                    l2dTapMotion = settings.l2dTapMotionEnabled,
                    l2dMouthSync = settings.l2dMouthSyncEnabled,
                    motionEndpoint = if (settings.vrmMotionEngine == VrmMotionEngine.HAST_SEC) {
                        "https://iris.local/hast"
                    } else {
                        ""
                    },
                    motionIdle = settings.motionAutoIdle,
                    motionPack = if (settings.vrmMotionEngine == VrmMotionEngine.HAST_SEC) "hast" else "vrma",
                    motionAxisFix = settings.motionAxisFix,
                )
            }
        }

        if (fullscreen) {
            FullscreenGlassComposer(
                viewModel = viewModel,
                character = character,
                input = input,
                running = running,
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CharacterStage(
    entry: CharacterEntry,
    activeModel: CharacterModelEntry?,
    modelFile: File?,
    live2dFile: File?,
    mmdFile: File?,
    modelAvailable: Boolean,
    modelThumbnail: File?,
    scene: SceneConfig?,
    live2dMood: String,
    moodIntensity: Float,
    live2dSpeaking: Boolean,
    mouthAmplitude: Float,
    model: CharacterModel,
    fullscreen: Boolean,
    l2dExpressions: Boolean,
    l2dMotions: Boolean,
    l2dIdleMotion: Boolean,
    l2dTapMotion: Boolean,
    l2dMouthSync: Boolean,
    motionEndpoint: String,
    motionIdle: Boolean,
    motionPack: String,
    motionAxisFix: Int,
    onEnterFullscreen: () -> Unit,
    onExitFullscreen: () -> Unit,
    onModelChange: (CharacterModel) -> Unit,
    modifier: Modifier = Modifier,
) {
    val character = entry.card
    val accent = Color(character.accent ?: 0xFF7DB3FF)
    val accentHex = String.format("#%06X", (character.accent ?: 0xFF7DB3FFL) and 0xFFFFFFL)
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val show3dModel = model == CharacterModel.THREE_D && modelFile != null
    val showLive2d = model == CharacterModel.TWO_D && live2dFile != null
    val showMmd = model == CharacterModel.THREE_D && mmdFile != null
    val modelOffset = Modifier
        .fillMaxSize()
        .graphicsLayer {
            translationX = (activeModel?.offsetX ?: 0f) * size.width
            translationY = (activeModel?.offsetY ?: 0f) * size.height
        }
    val fallbackFile = when {
        activeModel != null &&
            (activeModel.type == CharacterModelType.LIVE2D || activeModel.type == CharacterModelType.MMD) ->
            entry.avatar
        model == CharacterModel.TWO_D -> entry.avatar
        else -> modelThumbnail ?: entry.avatar
    }
    val modelLabel = activeModel?.let { item ->
        val suffix = when {
            !modelAvailable -> " · 文件缺失"
            item.type == CharacterModelType.LIVE2D && !showLive2d -> " · 切换 2D 查看"
            else -> ""
        }
        "${item.type.label} · ${item.name}$suffix"
    }
    val backdrop = Brush.verticalGradient(
        colors = listOf(accent.copy(alpha = 0.45f), Color.Transparent),
    )
    val transition = rememberInfiniteTransition(label = "lifeStage")
    val bob by transition.animateFloat(
        initialValue = -5f,
        targetValue = 5f,
        animationSpec = infiniteRepeatable(tween(1600), RepeatMode.Reverse),
        label = "bob",
    )
    val spin by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(6000, easing = LinearEasing)),
        label = "spin",
    )

    Box(
        modifier = modifier.then(
            if (fullscreen) Modifier else Modifier
                .padding(horizontal = 12.dp, vertical = 8.dp)
                .clip(RoundedCornerShape(24.dp)),
        ).clipToBounds(),
    ) {
        SceneBackground(scene = scene, modifier = Modifier.fillMaxSize())
        val fallbackBitmap = remember(fallbackFile?.absolutePath) {
            fallbackFile?.absolutePath?.let { path ->
                runCatching { BitmapFactory.decodeFile(path) }.getOrNull()?.asImageBitmap()
            }
        }
        if (model == CharacterModel.THREE_D && modelFile != null) {
            StageBase(scene)
            CharacterModel3DView(
                modelFile = modelFile,
                modelType = activeModel?.type?.name.orEmpty(),
                accentHex = accentHex,
                dark = dark,
                motionEndpoint = motionEndpoint,
                motionIdle = motionIdle,
                motionPack = motionPack,
                motionAxisFix = motionAxisFix,
                modifier = modelOffset,
            )
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            listOf(Color.Transparent, Color.Black.copy(alpha = 0.35f)),
                        ),
                    ),
            )
        } else if (model == CharacterModel.THREE_D && mmdFile != null) {
            StageBase(scene)
            MMDView(
                modelFile = mmdFile,
                accentHex = accentHex,
                dark = dark,
                mood = live2dMood,
                moodIntensity = moodIntensity,
                speaking = live2dSpeaking,
                mouthAmplitude = mouthAmplitude,
                fullscreen = fullscreen,
                modifier = modelOffset,
            )
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            listOf(Color.Transparent, Color.Black.copy(alpha = 0.35f)),
                        ),
                    ),
            )
        } else if (model == CharacterModel.TWO_D && live2dFile != null) {
            StageBase(scene)
            Live2DView(
                modelFile = live2dFile,
                accentHex = accentHex,
                dark = dark,
                speaking = live2dSpeaking,
                mood = live2dMood,
                mouthAmplitude = mouthAmplitude,
                expressionsEnabled = l2dExpressions,
                motionsEnabled = l2dMotions,
                idleMotionEnabled = l2dIdleMotion,
                tapMotionEnabled = l2dTapMotion,
                mouthSyncEnabled = l2dMouthSync,
                modifier = modelOffset,
            )
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            listOf(Color.Transparent, Color.Black.copy(alpha = 0.35f)),
                        ),
                    ),
            )
        } else {
        Canvas(modifier = Modifier.fillMaxSize()) {
            drawRect(brush = backdrop)
            val cx = size.width / 2f
            val base = size.height * 0.84f

            if (model == CharacterModel.TWO_D) {
                drawOval(
                    color = Color.Black.copy(alpha = 0.10f),
                    topLeft = Offset(cx - 80f, base - 12f),
                    size = Size(160f, 26f),
                )
                translate(top = bob.dp.toPx()) {
                    drawRoundRect(
                        color = accent.copy(alpha = 0.92f),
                        topLeft = Offset(cx - 58f, base - 100f),
                        size = Size(116f, 104f),
                        cornerRadius = CornerRadius(38f, 38f),
                    )
                    drawCircle(
                        color = Color(0xFFFFE2CC),
                        radius = 58f,
                        center = Offset(cx, base - 152f),
                    )
                    drawArc(
                        color = accent,
                        startAngle = 180f,
                        sweepAngle = 180f,
                        useCenter = true,
                        topLeft = Offset(cx - 62f, base - 214f),
                        size = Size(124f, 120f),
                    )
                    drawCircle(
                        color = Color(0xFF2B2233),
                        radius = 6f,
                        center = Offset(cx - 22f, base - 158f),
                    )
                    drawCircle(
                        color = Color(0xFF2B2233),
                        radius = 6f,
                        center = Offset(cx + 22f, base - 158f),
                    )
                    drawArc(
                        color = Color(0xFF8E4B55),
                        startAngle = 10f,
                        sweepAngle = 160f,
                        useCenter = false,
                        topLeft = Offset(cx - 17f, base - 145f),
                        size = Size(34f, 20f),
                        style = Stroke(width = 4f),
                    )
                    drawCircle(
                        color = Color(0xFFFF8FA3).copy(alpha = 0.55f),
                        radius = 10f,
                        center = Offset(cx - 38f, base - 138f),
                    )
                    drawCircle(
                        color = Color(0xFFFF8FA3).copy(alpha = 0.55f),
                        radius = 10f,
                        center = Offset(cx + 38f, base - 138f),
                    )
                }
            } else {
                rotate(degrees = spin, pivot = Offset(cx, base - 24f)) {
                    drawOval(
                        color = accent.copy(alpha = 0.35f),
                        topLeft = Offset(cx - 92f, base - 30f),
                        size = Size(184f, 52f),
                        style = Stroke(width = 3.5f),
                    )
                    drawOval(
                        color = Color.White.copy(alpha = 0.30f),
                        topLeft = Offset(cx - 64f, base - 48f),
                        size = Size(128f, 36f),
                        style = Stroke(width = 2f),
                    )
                }
                translate(top = bob.dp.toPx()) {
                    drawCircle(
                        color = accent.copy(alpha = 0.20f),
                        radius = 86f,
                        center = Offset(cx, base - 120f),
                    )
                    drawRoundRect(
                        color = accent,
                        topLeft = Offset(cx - 44f, base - 118f),
                        size = Size(88f, 92f),
                        cornerRadius = CornerRadius(30f, 30f),
                    )
                    drawCircle(
                        color = Color(0xFFFFE2CC),
                        radius = 40f,
                        center = Offset(cx, base - 150f),
                    )
                    drawCircle(
                        color = Color(0xFF2B2233),
                        radius = 4.5f,
                        center = Offset(cx - 14f, base - 154f),
                    )
                    drawCircle(
                        color = Color(0xFF2B2233),
                        radius = 4.5f,
                        center = Offset(cx + 14f, base - 154f),
                    )
                }
            }
        }

        if (fallbackBitmap != null && !show3dModel && !showLive2d && !showMmd) {
            Image(
                bitmap = fallbackBitmap,
                contentDescription = character.charName,
                contentScale = ContentScale.Crop,
                modifier = modelOffset,
            )
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            listOf(Color.Transparent, Color.Black.copy(alpha = 0.35f)),
                        ),
                    ),
            )
        }
        }

        if (!show3dModel && !showLive2d && !showMmd) {
            StageBase(scene)
        }

        if (!fullscreen && scene != null && scene.name.isNotBlank()) {
            Surface(
                shape = CircleShape,
                color = Color.Black.copy(alpha = 0.32f),
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(14.dp),
            ) {
                Text(
                    text = scene.name,
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                )
            }
        }

        if (modelLabel != null) {
            Surface(
                shape = CircleShape,
                color = Color.Black.copy(alpha = 0.35f),
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(12.dp),
            ) {
                Text(
                    text = modelLabel,
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                )
            }
        }

        if (fullscreen) {
            StageGlassPill(
                text = "ESC",
                icon = Icons.Default.Close,
                contentDescription = "退出全屏",
                onClick = onExitFullscreen,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(16.dp),
            )
        } else {
            Row(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                StageGlassIconButton(
                    icon = Icons.Default.Fullscreen,
                    contentDescription = "全屏",
                    onClick = onEnterFullscreen,
                )
                CharacterModel.entries.forEach { item ->
                    FilterChip(
                        selected = model == item,
                        onClick = { onModelChange(item) },
                        label = { Text(item.label) },
                    )
                }
            }

            Column(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(16.dp),
            ) {
                Text(
                    text = character.charName,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White,
                )
                Text(
                    text = character.blurb(24),
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White.copy(alpha = 0.85f),
                )
            }
        }
    }
}

@Composable
private fun StageGlassIconButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(Color.White.copy(alpha = 0.16f))
            .border(1.dp, Color.White.copy(alpha = 0.32f), CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = Color.White,
            modifier = Modifier.size(20.dp),
        )
    }
}

@Composable
private fun StageGlassPill(
    text: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .clip(CircleShape)
            .background(Color.Black.copy(alpha = 0.38f))
            .border(1.dp, Color.White.copy(alpha = 0.30f), CircleShape)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = Color.White,
        )
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = Color.White,
            modifier = Modifier.size(16.dp),
        )
    }
}

@Composable
private fun FullscreenGlassComposer(
    viewModel: ChatViewModel,
    character: CharacterCardData,
    input: String,
    running: Boolean,
    modifier: Modifier = Modifier,
) {
    var showVoice by remember { mutableStateOf(false) }
    val accent = Color(character.accent ?: 0xFF7DB3FF)
    val shape = RoundedCornerShape(30.dp)

    Box(
        modifier = modifier
            .fillMaxWidth()
            .imePadding()
            .navigationBarsPadding()
            .padding(horizontal = 16.dp, vertical = 18.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .shadow(26.dp, shape)
                .clip(shape)
                .background(
                    Brush.verticalGradient(
                        listOf(Color.White.copy(alpha = 0.28f), Color.White.copy(alpha = 0.10f)),
                    ),
                )
                .border(1.dp, Color.White.copy(alpha = 0.42f), shape)
                .padding(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BasicTextField(
                value = input,
                onValueChange = viewModel::onInputChange,
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                textStyle = TextStyle(color = Color.White, fontSize = 16.sp),
                cursorBrush = SolidColor(accent),
                maxLines = 4,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { viewModel.send() }),
                decorationBox = { innerTextField ->
                    Box {
                        if (input.isBlank()) {
                            Text(
                                text = "和${character.charName}说点什么",
                                color = Color.White.copy(alpha = 0.65f),
                                fontSize = 14.sp,
                            )
                        }
                        innerTextField()
                    }
                },
            )
            IconButton(onClick = { showVoice = true }) {
                Icon(
                    imageVector = Icons.Default.Mic,
                    contentDescription = "语音输入",
                    tint = Color.White.copy(alpha = 0.85f),
                )
            }
            FilledIconButton(
                onClick = { if (running) viewModel.stop() else viewModel.send() },
                modifier = Modifier.size(44.dp),
            ) {
                Icon(
                    imageVector = if (running) Icons.Default.Stop else Icons.AutoMirrored.Filled.Send,
                    contentDescription = if (running) "停止" else "发送",
                )
            }
        }
    }

    if (showVoice) {
        VoiceInputDialog(
            onDismiss = { showVoice = false },
            onInsert = { text ->
                viewModel.onInputChange(text)
                showVoice = false
            },
            onTranscribe = viewModel::transcribeVoice,
        )
    }
}

private fun detectMood(text: String): String {
    val sample = text.takeLast(240)
    return when {
        Regex("哈哈|开心|高兴|太棒|喜欢|爱你|😊|😄|🥰|❤️").containsMatchIn(sample) -> "happy"
        Regex("生气|讨厌|愤怒|烦死|😡|😠").containsMatchIn(sample) -> "angry"
        Regex("难过|伤心|想哭|失落|😭|😢").containsMatchIn(sample) -> "sad"
        Regex("惊讶|居然|竟然|没想到|😮|😲").containsMatchIn(sample) -> "surprised"
        Regex("思考|我想想|让我想|嗯…|🤔").containsMatchIn(sample) -> "thinking"
        else -> "neutral"
    }
}

@Composable
private fun StageBase(scene: SceneConfig?) {
    if (scene == null || scene.baseStyle == StageBaseStyle.NONE) return
    val texture = remember(scene.baseTexturePath) {
        scene.baseTexturePath.takeIf { it.isNotBlank() && scene.baseStyle == StageBaseStyle.TEXTURE }
            ?.let { path -> runCatching { BitmapFactory.decodeFile(path) }.getOrNull()?.asImageBitmap() }
    }
    Canvas(modifier = Modifier.fillMaxSize()) {
        val centerX = size.width / 2f
        val baseY = size.height * 0.9f
        val base = Color(scene.baseColor)
        when (scene.baseStyle) {
            StageBaseStyle.ROUND -> drawOval(
                color = base.copy(alpha = 0.38f),
                topLeft = Offset(centerX - 275f, baseY - 45f),
                size = Size(550f, 90f),
            )
            StageBaseStyle.GRID -> {
                drawOval(
                    color = base.copy(alpha = 0.30f),
                    topLeft = Offset(centerX - 275f, baseY - 60f),
                    size = Size(550f, 120f),
                    style = Stroke(width = 2f),
                )
                for (i in -2..2) {
                    drawLine(
                        color = base.copy(alpha = 0.22f),
                        start = Offset(centerX + i * 95f, baseY - 50f),
                        end = Offset(centerX + i * 130f, baseY + 50f),
                        strokeWidth = 1.5f,
                    )
                }
            }
            StageBaseStyle.GLOW -> drawCircle(
                color = base.copy(alpha = 0.18f),
                radius = 230f,
                center = Offset(centerX, baseY),
            )
            StageBaseStyle.TEXTURE -> {
                val width = 650f
                val height = 160f
                val left = centerX - width / 2f
                val top = baseY - height / 2f
                if (texture != null) {
                    val oval = Path().apply { addOval(Rect(left, top, left + width, top + height)) }
                    clipPath(oval) {
                        drawImage(
                            image = texture,
                            dstOffset = IntOffset(left.toInt(), top.toInt()),
                            dstSize = IntSize(width.toInt(), height.toInt()),
                        )
                    }
                    drawOval(
                        color = base.copy(alpha = 0.5f),
                        topLeft = Offset(left, top),
                        size = Size(width, height),
                        style = Stroke(width = 2f),
                    )
                } else {
                    drawOval(
                        color = base.copy(alpha = 0.38f),
                        topLeft = Offset(left, top),
                        size = Size(width, height),
                    )
                }
            }
            StageBaseStyle.NONE -> Unit
        }
    }
}

@Composable
private fun LifeComposer(
    viewModel: ChatViewModel,
    character: CharacterCardData,
    input: String,
    running: Boolean,
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
) {
    var showVoice by remember { mutableStateOf(false) }
    var showVideo by remember { mutableStateOf(false) }
    var showEmoji by remember { mutableStateOf(false) }

    Surface(tonalElevation = 2.dp) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            AnimatedVisibility(visible = showEmoji) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                ) {
                    listOf("😊", "🥰", "😴", "🎉", "❤️", "🤔", "🌙").forEach { emoji ->
                        Text(
                            text = emoji,
                            fontSize = 24.sp,
                            modifier = Modifier
                                .clickable { viewModel.onInputChange(input + emoji) }
                                .padding(4.dp),
                        )
                    }
                }
            }

            Row(verticalAlignment = Alignment.Bottom) {
                OutlinedTextField(
                    value = input,
                    onValueChange = viewModel::onInputChange,
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("和${character.charName}说点什么") },
                    maxLines = 4,
                    shape = RoundedCornerShape(22.dp),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { viewModel.send() }),
                )
                IconButton(onClick = { showVoice = true }) {
                    Icon(
                        imageVector = Icons.Default.Mic,
                        contentDescription = "语音输入",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = { showVideo = true }) {
                    Icon(
                        imageVector = Icons.Outlined.Videocam,
                        contentDescription = "视频通话",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = { showEmoji = !showEmoji }) {
                    Icon(
                        imageVector = Icons.Outlined.EmojiEmotions,
                        contentDescription = "表情",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                FilledIconButton(
                    onClick = { if (running) viewModel.stop() else viewModel.send() },
                ) {
                    Icon(
                        imageVector = if (running) {
                            Icons.Default.Stop
                        } else {
                            Icons.AutoMirrored.Filled.Send
                        },
                        contentDescription = if (running) "停止" else "发送",
                    )
                }
            }
        }
    }

    if (showVoice) {
        VoiceInputDialog(
            onDismiss = { showVoice = false },
            onInsert = { text ->
                viewModel.onInputChange(text)
                showVoice = false
            },
            onTranscribe = viewModel::transcribeVoice,
        )
    }

    if (showVideo) {
        VideoCallScreen(
            viewModel = viewModel,
            character = character,
            activeModel = activeModel,
            modelFile = modelFile,
            live2dFile = live2dFile,
            mmdFile = mmdFile,
            model = model,
            scene = scene,
            l2dExpressions = l2dExpressions,
            l2dMotions = l2dMotions,
            l2dIdleMotion = l2dIdleMotion,
            l2dTapMotion = l2dTapMotion,
            l2dMouthSync = l2dMouthSync,
            motionEndpoint = motionEndpoint,
            motionIdle = motionIdle,
            motionPack = motionPack,
            motionAxisFix = motionAxisFix,
            onDismiss = { showVideo = false },
        )
    }
}

