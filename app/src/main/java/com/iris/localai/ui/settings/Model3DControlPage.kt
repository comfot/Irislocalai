package com.iris.localai.ui.settings

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.iris.localai.IrisApp
import com.iris.localai.data.AppSettings
import com.iris.localai.data.MotionSource
import com.iris.localai.motion.HastMotionStore
import com.iris.localai.motion.MotionEnvironment
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun Model3DControlPage(settings: AppSettings, viewModel: SettingsViewModel) {
    val context = LocalContext.current
    val graph = remember(context) { (context.applicationContext as IrisApp).graph }
    val models by graph.characterModels.models.collectAsStateWithLifecycle()
    val activeId by graph.characterModels.activeId.collectAsStateWithLifecycle()
    val active = models.firstOrNull { it.id == activeId }
    val scope = rememberCoroutineScope()
    var testState by remember { mutableStateOf<String?>(null) }
    val motionEnv = remember(context) { MotionEnvironment(context) }
    val hastStore = remember(context) { HastMotionStore.get(context) }
    var hastStatus by remember {
        mutableStateOf(
            if (hastStore.isReady()) {
                "动作库已就绪：${hastStore.catalog().size} 个技能"
            } else {
                "未下载动作库（约 21 MB）"
            },
        )
    }
    var hastProgress by remember { mutableStateOf<String?>(null) }
    var hastBusy by remember { mutableStateOf(false) }
    val imageId = graph.code.settings.value.imageId
    var envStatus by remember { mutableStateOf<String?>(null) }
    var envBusy by remember { mutableStateOf(false) }

    androidx.compose.runtime.LaunchedEffect(imageId) {
        envStatus = motionEnv.status(imageId)
    }

    fun runEnv(block: suspend () -> String) {
        if (envBusy) return
        envBusy = true
        scope.launch {
            envStatus = block()
            envBusy = false
        }
    }

    SettingsPage {
        SettingsCard(title = "HAST-SEC 动作库") {
            Text(
                text = "从 Hugging Face 下载官方动作清单与 20fps 轨迹（53 骨骼四元数），本地解析后直接驱动 VRM/GLB；不运行其 0.8B 中文模型。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = hastStatus,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
            )
            hastProgress?.let { progress ->
                Text(
                    text = progress,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(
                    onClick = {
                        if (hastBusy) return@TextButton
                        hastBusy = true
                        hastProgress = "准备下载…"
                        scope.launch {
                            val message = hastStore.install { progress ->
                                scope.launch { hastProgress = progress }
                            }
                            hastBusy = false
                            hastProgress = null
                            hastStatus = message
                            if (hastStore.isReady()) {
                                viewModel.update {
                                    it.copy(
                                        motionSource = MotionSource.HAST_SEC,
                                        motionEndpoint = "https://iris.local/hast",
                                        motionAutoIdle = true,
                                    )
                                }
                            }
                        }
                    },
                    enabled = !hastBusy,
                ) { Text(if (hastBusy) "下载中…" else "下载/更新动作库") }
                TextButton(
                    onClick = {
                        hastStore.warmUp()
                        hastStatus = if (hastStore.isReady()) {
                            "动作库已就绪：${hastStore.catalog().size} 个技能"
                        } else {
                            "未下载动作库"
                        }
                    },
                    enabled = !hastBusy,
                ) { Text("刷新") }
            }
            Text(
                text = "HAST-SEC 采用非商用研究许可；本项目为非商用，可直接使用。模型需为 VRM/GLB，MMD 不支持这套骨骼轨道。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        SettingsCard(title = "内置动作服务（PRoot）") {
            Text(
                text = "在已有 Linux 容器里运行零依赖 Node 动作服务，返回 VRM 骨骼轨道；应用通过 127.0.0.1:8780 直接获取并播放。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = envStatus ?: "检查中…",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(
                    onClick = { runEnv { motionEnv.installNode(imageId) } },
                    enabled = !envBusy,
                ) { Text("安装 Node") }
                TextButton(
                    onClick = {
                        runEnv {
                            val message = motionEnv.start(imageId)
                            if (message.contains("已启动")) {
                                viewModel.update {
                                    it.copy(
                                        motionSource = MotionSource.CUSTOM,
                                        motionEndpoint = "http://127.0.0.1:8780",
                                    )
                                }
                            }
                            message
                        }
                    },
                    enabled = !envBusy,
                ) { Text("启动服务") }
                TextButton(
                    onClick = { runEnv { motionEnv.stop(imageId) } },
                    enabled = !envBusy,
                ) { Text("停止") }
                TextButton(
                    onClick = { runEnv { motionEnv.status(imageId) } },
                    enabled = !envBusy,
                ) { Text("刷新") }
            }
            Text(
                text = "首次使用需在「编程管理」安装 Alpine/Ubuntu rootfs，并保证容器能联网下载 Node。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        SettingsCard(title = "动作模型") {
            MotionSource.entries.forEach { source ->
                OptionRow(
                    selected = settings.motionSource == source,
                    title = source.label,
                    summary = source.summary,
                    onClick = { viewModel.update { it.copy(motionSource = source) } },
                )
            }
            if (settings.motionSource != MotionSource.NONE) {
                OutlinedTextField(
                    value = settings.motionEndpoint,
                    onValueChange = { value ->
                        viewModel.update { it.copy(motionEndpoint = value.trim()) }
                    },
                    label = { Text("动作服务地址") },
                    placeholder = { Text("http://192.168.1.10:8780/motion") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = settings.motionApiKey,
                    onValueChange = { value ->
                        viewModel.update { it.copy(motionApiKey = value.trim()) }
                    },
                    label = { Text("API Key（可选）") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                )
                SliderRow(
                    title = "生成时长",
                    value = settings.motionDurationSeconds.toFloat(),
                    range = 1f..30f,
                    steps = 28,
                    format = { "${it.toInt()} 秒" },
                    onCommit = { value ->
                        viewModel.update { it.copy(motionDurationSeconds = value.toInt()) }
                    },
                )
                SettingsSwitchRow(
                    title = "自动待机动作",
                    summary = "空闲时向服务请求短动作循环播放",
                    checked = settings.motionAutoIdle,
                    onCheckedChange = { enabled ->
                        viewModel.update { it.copy(motionAutoIdle = enabled) }
                    },
                )
                SettingsSwitchRow(
                    title = "视频通话中启用",
                    summary = "通话界面也应用动作服务",
                    checked = settings.motionInCall,
                    onCheckedChange = { enabled ->
                        viewModel.update { it.copy(motionInCall = enabled) }
                    },
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    TextButton(
                        onClick = {
                            testState = "正在连接…"
                            scope.launch {
                                testState = testMotionEndpoint(settings.motionEndpoint, settings.motionApiKey)
                            }
                        },
                        enabled = settings.motionEndpoint.isNotBlank(),
                    ) {
                        Text("测试连接")
                    }
                }
                testState?.let { state ->
                    Text(
                        text = state,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        SettingsCard(title = "动作轴向修正（仅 GLB）") {
            Text(
                text = "VRM 已按 VRM 0.x / 1.0 规范自动处理坐标；此开关只影响按原始骨骼播放动作的 GLB 模型。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            listOf(
                0 to "不修正",
                1 to "前后翻转（默认）",
                2 to "左右翻转",
                3 to "前后 + 左右",
            ).forEach { (value, label) ->
                OptionRow(
                    selected = settings.motionAxisFix == value,
                    title = label,
                    summary = when (value) {
                        0 -> "GLB 轨迹四元数原样应用"
                        1 -> "z 分量取反，适用于 GLB 手跑到脸前、腿向后弯"
                        2 -> "x 分量取反，适用于 GLB 左右手脚互换"
                        else -> "GLB 的 x、z 同时取反"
                    },
                    onClick = { viewModel.update { it.copy(motionAxisFix = value) } },
                )
            }
        }

        SettingsCard(title = "使用说明") {
            Text(
                text = "Kimodo（PyTorch/GPU）：pip 安装后可用 kimodo_gen \"走路\" --model Kimodo-SOMA-RP-v1.2 --duration 5 --output out 生成动作，再通过 kimodo_convert 导出 BVH。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = "HAST-SEC（Node.js）：npm ci && npm run setup && npm start，默认在 127.0.0.1:8769 提供本地动作服务；许可证为非商用研究版。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = "两者都无法在手机端直接推理，App 侧预留 HTTP 动作服务接口；配置地址后可在后续版本接入动作生成结果。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = {
                    context.startActivity(
                        Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/nv-tlabs/kimodo")),
                    )
                }) { Text("Kimodo") }
                TextButton(onClick = {
                    context.startActivity(
                        Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/sola1039/HAST-SEC")),
                    )
                }) { Text("HAST-SEC") }
            }
        }

        SettingsCard(title = "模型位置") {
            if (active == null) {
                Text(
                    text = "还没有设为当前的 3D 模型。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Text(
                    text = "调整「${active.name}」在舞台中的位置。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                SliderRow(
                    title = "左右偏移",
                    value = active.offsetX,
                    range = -0.5f..0.5f,
                    steps = 19,
                    format = { "${(it * 100).toInt()}%" },
                    onCommit = { value ->
                        graph.characterModels.updateOffset(active.id, value, active.offsetY)
                    },
                )
                SliderRow(
                    title = "上下偏移",
                    value = active.offsetY,
                    range = -0.5f..0.5f,
                    steps = 19,
                    format = { "${(it * 100).toInt()}%" },
                    onCommit = { value ->
                        graph.characterModels.updateOffset(active.id, active.offsetX, value)
                    },
                )
                TextButton(
                    onClick = {
                        graph.characterModels.updateOffset(active.id, 0f, 0f)
                    },
                ) {
                    Text("重置位置")
                }
            }
        }
    }
}

private suspend fun testMotionEndpoint(endpoint: String, apiKey: String): String =
    withContext(Dispatchers.IO) {
        runCatching {
            val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 6000
                readTimeout = 6000
                if (apiKey.isNotBlank()) setRequestProperty("Authorization", "Bearer $apiKey")
            }
            try {
                val code = connection.responseCode
                if (code in 200..299) "连接成功（HTTP $code）" else "服务返回 HTTP $code"
            } finally {
                connection.disconnect()
            }
        }.getOrElse { "连接失败：${it.message ?: "无法访问"}" }
    }
