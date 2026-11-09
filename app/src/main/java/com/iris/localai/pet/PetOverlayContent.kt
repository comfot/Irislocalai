package com.iris.localai.pet

import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.iris.localai.AppGraph
import com.iris.localai.agent.AssistantMessageParser
import com.iris.localai.agent.ChatRole
import com.iris.localai.character.CharacterEntry
import com.iris.localai.character.CharacterModelEntry
import com.iris.localai.character.CharacterModelType
import com.iris.localai.data.AppSettings
import com.iris.localai.data.Space
import com.iris.localai.ui.chat.CharacterModel3DView
import com.iris.localai.ui.chat.Live2DView
import com.iris.localai.ui.chat.MMDView
import java.io.File
import kotlin.math.abs
import kotlinx.coroutines.delay

/**
 * 桌宠悬浮窗内容：萌化开启时渲染独立桌宠模型库，关闭时渲染角色绑定的模型，
 * 两者都走与生活空间相同的渲染器；不再使用缩略图。
 */
@Composable
internal fun PetOverlayContent(
    graph: AppGraph,
    bubble: String?,
    menuVisible: Boolean,
    chatBarVisible: Boolean,
    speaking: Boolean,
    onTap: () -> Unit,
    onLongPress: () -> Unit,
    onDrag: (Float, Float) -> Unit,
    onFeed: () -> Unit,
    onSing: () -> Unit,
    onWake: () -> Unit,
    onOpenChat: () -> Unit,
    onOpenConfig: () -> Unit,
    onHide: () -> Unit,
    onSend: (String) -> Unit,
    onCloseChatBar: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val settings by graph.settings.settings.collectAsStateWithLifecycle()
    val characters by graph.characters.cards.collectAsStateWithLifecycle()
    val charModels by graph.characterModels.models.collectAsStateWithLifecycle()
    val charBindings by graph.characterModels.bindings.collectAsStateWithLifecycle()
    val charActive by graph.characterModels.activeId.collectAsStateWithLifecycle()
    val petModels by graph.petModels.models.collectAsStateWithLifecycle()
    val petActive by graph.petModels.activeId.collectAsStateWithLifecycle()
    val conversations by graph.conversations.conversations.collectAsStateWithLifecycle()

    val entry = characters.firstOrNull { it.id == settings.characterId } ?: characters.firstOrNull()
    val usingPetModel = settings.petCuteAvatar
    val model: CharacterModelEntry? = if (usingPetModel) {
        petModels.firstOrNull { it.id == settings.petModelId }
            ?: petModels.firstOrNull { it.id == petActive }
            ?: petModels.firstOrNull()
    } else {
        charModels.firstOrNull { it.id == charBindings[entry?.id] }
            ?: charModels.firstOrNull { it.id == charActive }
    }
    val modelRoot = if (usingPetModel) "pet_models" else "character_models"
    val modelFile = remember(model?.id, modelRoot) {
        model?.let { item ->
            File(
                context.filesDir,
                "$modelRoot/${item.directory}/${item.entryFile}",
            ).takeIf { it.isFile }
        }
    }
    val accentHex = String.format("#%06X", (entry?.card?.accent ?: 0xFF7DB3FFL) and 0xFFFFFFL)

    val lifeConversation = conversations.firstOrNull { it.space == Space.LIFE }
    val lastReply = lifeConversation?.messages?.lastOrNull { it.role == ChatRole.ASSISTANT }
    var replyBubble by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(lastReply?.id, lastReply?.text) {
        val raw = lastReply?.text?.takeIf { it.isNotBlank() } ?: return@LaunchedEffect
        val text = AssistantMessageParser.mainText(raw).ifBlank { return@LaunchedEffect }
        replyBubble = text.take(120)
        delay(6000)
        replyBubble = null
    }

    Column(modifier = modifier.fillMaxSize()) {
        Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
            if (model != null && modelFile != null) {
                ModelView(
                    model = model,
                    file = modelFile,
                    modelRoot = modelRoot,
                    accentHex = accentHex,
                    speaking = speaking,
                    l2dExpressions = settings.l2dExpressionsEnabled,
                    l2dMotions = settings.l2dMotionsEnabled,
                    l2dIdleMotion = settings.l2dIdleMotionEnabled,
                    l2dTapMotion = settings.l2dTapMotionEnabled,
                    l2dMouthSync = settings.l2dMouthSyncEnabled,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                CharacterAvatar(entry)
            }

            // 手势层盖在 WebView 之上，菜单与输入栏在它之后，保证可点击。
            PetGestureLayer(
                draggable = settings.petDraggable,
                onDrag = onDrag,
                onTap = onTap,
                onLongPress = onLongPress,
                modifier = Modifier.fillMaxSize(),
            )

            val shownBubble = bubble ?: replyBubble
            if (shownBubble != null) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(horizontal = 4.dp, vertical = 2.dp)
                        .background(Color(0xCC202028), RoundedCornerShape(12.dp))
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                ) {
                    Text(text = shownBubble, color = Color.White, fontSize = 12.sp, maxLines = 3)
                }
            }

            if (menuVisible) {
                Column(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 4.dp)
                        .background(Color(0xDD282834), RoundedCornerShape(12.dp))
                        .padding(4.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    if (settings.petFeedEnabled) MenuItem("喂食", onFeed)
                    if (settings.petSingEnabled) MenuItem("唱歌", onSing)
                    if (settings.petWakeEnabled) MenuItem("叫醒", onWake)
                    MenuItem("打开对话", onOpenChat)
                    MenuItem("配置", onOpenConfig)
                    MenuItem("隐藏桌宠", onHide)
                }
            }
        }

        if (chatBarVisible) {
            PetChatBar(
                characterName = entry?.card?.charName ?: "我",
                onSend = onSend,
                onClose = onCloseChatBar,
            )
        }
    }
}

@Composable
private fun ModelView(
    model: CharacterModelEntry,
    file: File,
    modelRoot: String,
    accentHex: String,
    speaking: Boolean,
    l2dExpressions: Boolean,
    l2dMotions: Boolean,
    l2dIdleMotion: Boolean,
    l2dTapMotion: Boolean,
    l2dMouthSync: Boolean,
    modifier: Modifier = Modifier,
) {
    when (model.type) {
        CharacterModelType.LIVE2D -> Live2DView(
            modelFile = file,
            accentHex = accentHex,
            dark = false,
            speaking = speaking,
            mood = "neutral",
            interactive = false,
            modelRoot = modelRoot,
            expressionsEnabled = l2dExpressions,
            motionsEnabled = l2dMotions,
            idleMotionEnabled = l2dIdleMotion,
            tapMotionEnabled = l2dTapMotion,
            mouthSyncEnabled = l2dMouthSync,
            modifier = modifier,
        )
        CharacterModelType.MMD -> MMDView(
            modelFile = file,
            accentHex = accentHex,
            dark = false,
            speaking = speaking,
            interactive = false,
            modelRoot = modelRoot,
            modifier = modifier,
        )
        CharacterModelType.VRM, CharacterModelType.GLB -> CharacterModel3DView(
            modelFile = file,
            modelType = model.type.name,
            accentHex = accentHex,
            dark = false,
            interactive = false,
            modelRoot = modelRoot,
            modifier = modifier,
        )
    }
}

@Composable
private fun PetChatBar(
    characterName: String,
    onSend: (String) -> Unit,
    onClose: () -> Unit,
) {
    var text by remember { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    val shape = RoundedCornerShape(22.dp)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 4.dp)
            .background(
                Brush.verticalGradient(
                    listOf(Color.White.copy(alpha = 0.30f), Color.White.copy(alpha = 0.14f)),
                ),
                shape,
            )
            .border(1.dp, Color.White.copy(alpha = 0.42f), shape)
            .padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BasicTextField(
            value = text,
            onValueChange = { text = it },
            modifier = Modifier.weight(1f).focusRequester(focus),
            textStyle = TextStyle(color = Color.White, fontSize = 13.sp),
            cursorBrush = SolidColor(Color.White),
            maxLines = 3,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(
                onSend = {
                    if (text.isNotBlank()) {
                        onSend(text)
                        text = ""
                    }
                },
            ),
            decorationBox = { inner ->
                Box {
                    if (text.isBlank()) {
                        Text(
                            text = "和${characterName}说点什么",
                            color = Color.White.copy(alpha = 0.62f),
                            fontSize = 12.sp,
                        )
                    }
                    inner()
                }
            },
        )
        Icon(
            imageVector = Icons.AutoMirrored.Filled.Send,
            contentDescription = "发送",
            tint = Color.White,
            modifier = Modifier
                .padding(start = 4.dp)
                .size(22.dp)
                .clickable {
                    if (text.isNotBlank()) {
                        onSend(text)
                        text = ""
                    }
                },
        )
        Icon(
            imageVector = Icons.Default.Close,
            contentDescription = "收起",
            tint = Color.White.copy(alpha = 0.72f),
            modifier = Modifier
                .padding(start = 8.dp)
                .size(20.dp)
                .clickable(onClick = onClose),
        )
    }
}

@Composable
private fun MenuItem(label: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clickable(onClick = onClick)
            .background(Color(0x33202028), RoundedCornerShape(8.dp))
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Text(text = label, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun CharacterAvatar(entry: CharacterEntry?) {
    val path = entry?.avatar?.absolutePath
    val bitmap = remember(path) {
        path?.let { file -> runCatching { BitmapFactory.decodeFile(file) }.getOrNull() }
    }
    if (bitmap != null) {
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = entry?.card?.charName,
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize(),
        )
    } else {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("未导入形象模型", color = Color.White.copy(alpha = 0.7f), fontSize = 11.sp)
        }
    }
}

/**
 * 手势层用 pointerInteropFilter 直接读屏幕坐标：窗口随拖动移动时，
 * Compose 局部坐标会跟着窗口跳动，只有 rawX/rawY 能保持稳定。
 */
@Composable
private fun PetGestureLayer(
    draggable: Boolean,
    onDrag: (Float, Float) -> Unit,
    onTap: () -> Unit,
    onLongPress: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val slop = with(density) { 8.dp.toPx() }
    val handler = remember { Handler(Looper.getMainLooper()) }
    val tapAction = rememberUpdatedState(onTap)
    val longPressAction = rememberUpdatedState(onLongPress)
    var dragging by remember { mutableStateOf(false) }
    var longFired by remember { mutableStateOf(false) }
    var lastRawX by remember { mutableFloatStateOf(0f) }
    var lastRawY by remember { mutableFloatStateOf(0f) }
    val longPressRunnable = remember {
        Runnable {
            if (!dragging) {
                longFired = true
                longPressAction.value()
            }
        }
    }
    DisposableEffect(Unit) {
        onDispose { handler.removeCallbacksAndMessages(null) }
    }
    Box(
        modifier = modifier.pointerInteropFilter { event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    lastRawX = event.rawX
                    lastRawY = event.rawY
                    dragging = false
                    longFired = false
                    handler.postDelayed(longPressRunnable, 500L)
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - lastRawX
                    val dy = event.rawY - lastRawY
                    if (!dragging && (abs(dx) > slop || abs(dy) > slop)) dragging = true
                    if (dragging) {
                        lastRawX = event.rawX
                        lastRawY = event.rawY
                        if (draggable) onDrag(dx, dy)
                    }
                }
                MotionEvent.ACTION_UP -> {
                    handler.removeCallbacks(longPressRunnable)
                    if (!dragging && !longFired) tapAction.value()
                }
                MotionEvent.ACTION_CANCEL -> handler.removeCallbacks(longPressRunnable)
            }
            true
        },
    )
}

/** 桌宠窗口需要的最小状态：气泡、菜单与输入栏由 Service 持有。 */
internal class PetOverlayUiState {
    var bubble by mutableStateOf<String?>(null)
    var menuVisible by mutableStateOf(false)
    var chatBarVisible by mutableStateOf(false)
    var speaking by mutableStateOf(false)
}
