package com.iris.localai.ui.settings

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.iris.localai.IrisApp
import com.iris.localai.character.CharacterModelEntry
import com.iris.localai.character.CharacterModelType
import com.iris.localai.data.AppSettings
import com.iris.localai.data.VrmMotionEngine
import com.iris.localai.motion.HastMotionStore
import com.iris.localai.motion.MotionAsset
import com.iris.localai.motion.MotionBridge
import com.iris.localai.motion.MotionCategory
import com.iris.localai.motion.MotionEnvironment
import com.iris.localai.motion.Model3DKind
import com.iris.localai.motion.VrmaAsset
import com.iris.localai.motion.VrmaMotionStore
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
    val localMotions by graph.motionAssets.assets.collectAsStateWithLifecycle()
    val vrmaAssets by graph.vrmaMotions.assets.collectAsStateWithLifecycle()
    val motionSets by graph.motionBindings.sets.collectAsStateWithLifecycle()
    val expressionSets by graph.expressionBindings.sets.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    val detectedKind = active.toModelKind()
    var kind by rememberSaveable { mutableStateOf(detectedKind) }
    var section by rememberSaveable { mutableIntStateOf(0) }
    LaunchedEffect(detectedKind) { kind = detectedKind }

    val motionEnv = remember(context) { MotionEnvironment(context) }
    val vrmaStore = remember(context) { graph.vrmaMotions }
    val hastStore = remember(context) { HastMotionStore.get(context) }
    var hastSkills by remember { mutableStateOf<List<String>>(emptyList()) }
    LaunchedEffect(Unit) {
        if (hastStore.isReady()) {
            withContext(Dispatchers.IO) { hastStore.warmUp() }
            hastSkills = hastStore.catalog()
        }
    }
    LaunchedEffect(kind, localMotions, hastSkills, vrmaAssets) {
        when (kind) {
            Model3DKind.MMD -> graph.motionBindings.ensureActions(Model3DKind.MMD, localMotions.map { it.name })
            Model3DKind.VRM -> graph.motionBindings.ensureActions(
                Model3DKind.VRM,
                hastSkills + vrmaAssets.map { it.name },
            )
        }
    }

    val imageId = graph.code.settings.value.imageId
    var envStatus by remember { mutableStateOf<String?>(null) }
    var envBusy by remember { mutableStateOf(false) }

    LaunchedEffect(imageId) { envStatus = motionEnv.status(imageId) }

    fun runEnv(block: suspend () -> String) {
        if (envBusy) return
        envBusy = true
        scope.launch {
            envStatus = block()
            envBusy = false
        }
    }

    fun toast(message: String) {
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
    }

    SettingsPage {
        TabRow(selectedTabIndex = section) {
            listOf("控制底层", "绑定配置").forEachIndexed { index, label ->
                Tab(
                    selected = section == index,
                    onClick = { section = index },
                    text = { Text(label) },
                )
            }
        }

        if (section == 0) {
            ControlFoundation(
                active = active,
                kind = kind,
                settings = settings,
                viewModel = viewModel,
                graph = graph,
                localMotions = localMotions,
                vrmaAssets = vrmaAssets,
                vrmaStore = vrmaStore,
                hastSkills = hastSkills,
                hastStore = hastStore,
                motionEnv = motionEnv,
                imageId = imageId,
                envStatus = envStatus,
                envBusy = envBusy,
                onEnv = ::runEnv,
                onHastSkills = { hastSkills = it },
                motionBindings = motionSets[kind]?.bindings ?: graph.motionBindings.bindings(kind),
                onToast = ::toast,
            )
        } else {
            BindingConfig(
                kind = kind,
                onKindChange = { kind = it },
                motionActions = motionSets[kind]?.actions ?: graph.motionBindings.actions(kind),
                motionBindings = motionSets[kind]?.bindings ?: graph.motionBindings.bindings(kind),
                expressionActions = expressionSets[kind]?.actions ?: graph.expressionBindings.actions(kind),
                expressionBindings = expressionSets[kind]?.bindings
                    ?: graph.expressionBindings.bindings(kind),
                onAddAction = { graph.motionBindings.addAction(kind, it) },
                onRemoveAction = { graph.motionBindings.removeAction(kind, it) },
                onBind = { alias, action -> graph.motionBindings.bind(kind, alias, action) },
                onUnbind = { graph.motionBindings.unbind(kind, it) },
                onReset = {
                    graph.motionBindings.reset(kind)
                    when (kind) {
                        Model3DKind.MMD ->
                            graph.motionBindings.ensureActions(kind, localMotions.map { it.name })
                        Model3DKind.VRM ->
                            graph.motionBindings.ensureActions(kind, hastSkills + vrmaAssets.map { it.name })
                    }
                },
                onSave = {
                    graph.motionBindings.save()
                    graph.expressionBindings.save()
                    toast("动作与表情绑定已保存")
                },
                onAddExpression = { graph.expressionBindings.addAction(kind, it) },
                onRemoveExpression = { graph.expressionBindings.removeAction(kind, it) },
                onBindExpression = { alias, expression ->
                    graph.expressionBindings.bind(kind, alias, expression)
                },
                onUnbindExpression = { graph.expressionBindings.unbind(kind, it) },
                onResetExpressions = { graph.expressionBindings.reset(kind) },
            )
        }
    }
}

@Composable
private fun ControlFoundation(
    active: CharacterModelEntry?,
    kind: Model3DKind,
    settings: AppSettings,
    viewModel: SettingsViewModel,
    graph: com.iris.localai.AppGraph,
    localMotions: List<MotionAsset>,
    vrmaAssets: List<VrmaAsset>,
    vrmaStore: VrmaMotionStore,
    hastSkills: List<String>,
    hastStore: HastMotionStore,
    motionEnv: MotionEnvironment,
    imageId: String,
    envStatus: String?,
    envBusy: Boolean,
    onEnv: (suspend () -> String) -> Unit,
    onHastSkills: (List<String>) -> Unit,
    motionBindings: Map<String, String>,
    onToast: (String) -> Unit,
) {
    val context = LocalContext.current
    val modelType = active?.type?.label ?: "未选择"
    SettingsCard(title = "自动控制方式") {
        Text(
            text = "当前模型：$modelType。已根据模型类型自动切换到 ${kind.label} 控制与绑定；" +
                "打开本页或切换当前模型后会重新匹配。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.primary,
        )
        Text(
            text = if (kind == Model3DKind.MMD) {
                "MMD 使用素材库中的 VMD 动作，不支持 HAST-SEC 或 VRMA。"
            } else {
                "VRM/GLB 使用 HAST-SEC 或 VRMA，二者互斥；PRoot 服务作为独立本地服务保留。"
            },
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    if (kind == Model3DKind.MMD) {
        MmdRenderQualityCard(settings, viewModel)
        MmdControlCard(localMotions, onToast)
    } else {
        VrmControlCard(
            settings = settings,
            viewModel = viewModel,
            hastSkills = hastSkills,
            hastStore = hastStore,
            vrmaAssets = vrmaAssets,
            vrmaStore = vrmaStore,
            motionBindings = motionBindings,
            onHastSkills = onHastSkills,
        )
    }

    SettingsCard(title = "内置动作服务（PRoot）") {
        Text(
            text = "在已有 Linux 容器里运行零依赖 Node 动作服务，通过 127.0.0.1:8780 提供 VRM 骨骼轨道。" +
                "仅对 VRM/GLB 生效。",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = envStatus ?: "检查中…",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.primary,
        )
        SettingsSwitchRow(
            title = "启动软件后自启动",
            summary = "应用启动时自动尝试启动 PRoot 动作服务（需已安装镜像与 Node）",
            checked = settings.motionServiceAutoStart,
            onCheckedChange = { enabled ->
                viewModel.update { it.copy(motionServiceAutoStart = enabled) }
            },
        )
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            TextButton(onClick = { onEnv { motionEnv.installNode(imageId) } }, enabled = !envBusy) {
                Text("安装 Node")
            }
            TextButton(
                onClick = {
                    onEnv { motionEnv.start(imageId) }
                },
                enabled = !envBusy,
            ) { Text("启动服务") }
            TextButton(onClick = { onEnv { motionEnv.stop(imageId) } }, enabled = !envBusy) {
                Text("停止")
            }
            TextButton(onClick = { onEnv { motionEnv.status(imageId) } }, enabled = !envBusy) {
                Text("刷新")
            }
        }
        Text(
            text = "首次使用需在「编程管理」安装 Alpine/Ubuntu rootfs，并保证容器能联网下载 Node。",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    ModelPositionCard(active, graph)
    SettingsCard(title = "使用说明") {
        Text(
            text = "HAST-SEC 与 MMD 动作下载已移动到「市场 › 模型市场 › 动作模型」。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = "Kimodo（PyTorch/GPU）：pip 安装后可用 kimodo_gen 生成动作，再导出 BVH。移动端无法直接推理，本页保留 HTTP 动作服务接口。",
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
}

@Composable
private fun MmdRenderQualityCard(settings: AppSettings, viewModel: SettingsViewModel) {
    SettingsCard(title = "MMD 渲染清晰度") {
        Text(
            text = "按屏幕像素比渲染舞台模型，高清档位可减轻放大带来的模糊，但会增加 GPU 负载；" +
                "帧率持续偏低时会自动降到当前档位的 70%。",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(
                0.75f to "省电",
                1f to "标准",
                1.25f to "高清",
                1.5f to "超清",
            ).forEach { (scale, label) ->
                FilterChip(
                    selected = settings.mmdRenderScale == scale,
                    onClick = { viewModel.update { it.copy(mmdRenderScale = scale) } },
                    label = { Text("$label ${(scale * 100).toInt()}%") },
                )
            }
        }
    }
}

@Composable
private fun MmdControlCard(localMotions: List<MotionAsset>, onToast: (String) -> Unit) {
    SettingsCard(title = "MMD 控制") {
        Text(
            text = "已导入 ${localMotions.size} 个 VMD 动作/镜头。VMD 只对 PMX/MMD 模型生效，播放舞蹈时会自动配对同名 BGM。",
            style = MaterialTheme.typography.bodySmall,
        )
        localMotions.take(8).forEach { asset ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = asset.name,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.weight(1f),
                )
                TextButton(
                    onClick = {
                        MotionBridge.playAsset(
                            path = asset.id,
                            loop = asset.category == MotionCategory.DANCE,
                            bgmPath = asset.bgm?.absolutePath,
                        )
                        onToast("正在播放：${asset.name}")
                    },
                ) { Text("播放") }
            }
        }
        if (localMotions.isEmpty()) {
            Text(
                text = "还没有 VMD 动作，请到「市场 › 模型市场 › 动作模型 › MMD 动作」下载或导入。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun VrmControlCard(
    settings: AppSettings,
    viewModel: SettingsViewModel,
    hastSkills: List<String>,
    hastStore: HastMotionStore,
    vrmaAssets: List<VrmaAsset>,
    vrmaStore: VrmaMotionStore,
    motionBindings: Map<String, String>,
    onHastSkills: (List<String>) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val ready = hastStore.isReady()
    val engine = settings.vrmMotionEngine
    var vrmaStatus by remember { mutableStateOf<String?>(null) }
    val importVrma = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val result = withContext(Dispatchers.IO) { vrmaStore.import(uri) }
            result.onSuccess { vrmaStatus = "已导入：${it.name}" }
                .onFailure { vrmaStatus = it.message ?: "导入失败" }
        }
    }

    SettingsCard(title = "VRM 控制") {
        Text(
            text = "HAST-SEC 与 VRMA 二选一，只能同时启用一个。当前：" +
                (if (engine == VrmMotionEngine.HAST_SEC) "HAST-SEC" else "VRMA"),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.primary,
        )
        OptionRow(
            selected = engine == VrmMotionEngine.HAST_SEC,
            title = VrmMotionEngine.HAST_SEC.label,
            summary = VrmMotionEngine.HAST_SEC.summary,
            onClick = { viewModel.update { it.copy(vrmMotionEngine = VrmMotionEngine.HAST_SEC) } },
        )
        OptionRow(
            selected = engine == VrmMotionEngine.VRMA,
            title = VrmMotionEngine.VRMA.label,
            summary = VrmMotionEngine.VRMA.summary,
            onClick = { viewModel.update { it.copy(vrmMotionEngine = VrmMotionEngine.VRMA) } },
        )

        if (engine == VrmMotionEngine.HAST_SEC) {
            Text(
                text = if (ready) {
                    "HAST-SEC 已就绪：${hastSkills.size} 个动作。下载与更新在「市场 › 模型市场 › 动作模型 › VRM动作」。"
                } else {
                    "HAST-SEC 未下载。请到「市场 › 模型市场 › 动作模型 › VRM动作」下载动作库。"
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TextButton(
                onClick = {
                    hastStore.warmUp()
                    onHastSkills(hastStore.catalog())
                },
                enabled = ready,
            ) { Text("刷新动作列表") }
            hastSkills.take(8).forEach { skill ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        skill,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = { MotionBridge.play(skill) }) { Text("播放") }
                }
            }
            SettingsSwitchRow(
                title = "自动待机动作",
                summary = "空闲时播放 HAST-SEC 待机动作",
                checked = settings.motionAutoIdle,
                onCheckedChange = { enabled ->
                    viewModel.update { it.copy(motionAutoIdle = enabled) }
                },
            )
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = { importVrma.launch("*/*") }) { Text("导入 VRMA") }
                TextButton(onClick = { vrmaStore.refresh() }) { Text("刷新") }
                TextButton(onClick = { MotionBridge.stopVrma() }) { Text("停止") }
            }
            vrmaStatus?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                text = "VRMA 文件会保留人形骨骼、表情与 lookAt 轨道；动作可在绑定配置里添加并交给 motion_control。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (vrmaAssets.isEmpty()) {
                Text(
                    text = "还没有 VRMA 动作，点击「导入 VRMA」选择 .vrma 文件。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            vrmaAssets.forEach { asset ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(asset.name, style = MaterialTheme.typography.bodySmall)
                        Text(
                            text = formatVrmaSize(asset.sizeBytes),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    TextButton(
                        onClick = {
                            MotionBridge.playVrma(vrmaStore.virtualUrl(asset))
                            vrmaStatus = "正在播放：${asset.name}"
                        },
                    ) { Text("播放") }
                    TextButton(
                        onClick = {
                            if (vrmaStore.delete(asset)) vrmaStatus = "已删除：${asset.name}"
                        },
                    ) { Text("删除") }
                }
            }
        }

        SettingsSwitchRow(
            title = "视频通话中启用",
            summary = "通话界面也应用当前 VRM 动作引擎",
            checked = settings.motionInCall,
            onCheckedChange = { enabled ->
                viewModel.update { it.copy(motionInCall = enabled) }
            },
        )
        if (motionBindings.isNotEmpty()) {
            Text(
                text = "当前绑定：" + motionBindings.entries.take(4).joinToString("，") { "${it.key}→${it.value}" },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    SettingsCard(title = "动作轴向修正（仅 GLB）") {
        Text(
            text = "VRM 已按规范自动处理坐标；此开关只影响按原始骨骼播放动作的 GLB 模型。",
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
}

private fun formatVrmaSize(bytes: Long): String = when {
    bytes >= 1L shl 20 -> String.format(java.util.Locale.US, "%.1f MB", bytes / (1L shl 20).toDouble())
    bytes >= 1L shl 10 -> String.format(java.util.Locale.US, "%.0f KB", bytes / (1L shl 10).toDouble())
    else -> "$bytes B"
}

@Composable
private fun ModelPositionCard(active: CharacterModelEntry?, graph: com.iris.localai.AppGraph) {
    SettingsCard(title = "模型位置") {
        if (active == null) {
            Text(
                text = "还没有设为当前的 3D 模型。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@SettingsCard
        }
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
        TextButton(onClick = { graph.characterModels.updateOffset(active.id, 0f, 0f) }) {
            Text("重置位置")
        }
    }
}

@Composable
private fun BindingConfig(
    kind: Model3DKind,
    onKindChange: (Model3DKind) -> Unit,
    motionActions: List<String>,
    motionBindings: Map<String, String>,
    expressionActions: List<String>,
    expressionBindings: Map<String, String>,
    onAddAction: (String) -> Unit,
    onRemoveAction: (String) -> Unit,
    onBind: (String, String) -> Unit,
    onUnbind: (String) -> Unit,
    onReset: () -> Unit,
    onSave: () -> Unit,
    onAddExpression: (String) -> Unit,
    onRemoveExpression: (String) -> Unit,
    onBindExpression: (String, String) -> Unit,
    onUnbindExpression: (String) -> Unit,
    onResetExpressions: () -> Unit,
) {
    var selectedAction by remember(kind) { mutableStateOf<String?>(null) }
    var selectedExpression by remember(kind) { mutableStateOf<String?>(null) }
    var showAddAction by remember { mutableStateOf(false) }
    var showAddBinding by remember { mutableStateOf(false) }
    var showAddExpression by remember { mutableStateOf(false) }
    var showAddExpressionBinding by remember { mutableStateOf(false) }

    TabRow(selectedTabIndex = kind.ordinal) {
        Model3DKind.entries.forEach { item ->
            Tab(
                selected = kind == item,
                onClick = { onKindChange(item) },
                text = { Text("${item.label} 绑定") },
            )
        }
    }

    SettingsCard(title = "${kind.label} 动作") {
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            TextButton(onClick = { showAddAction = true }) { Text("添加 +") }
            TextButton(
                onClick = {
                    selectedAction?.let { onRemoveAction(it) }
                    selectedAction = null
                },
                enabled = selectedAction != null,
            ) { Text("删除 -") }
            TextButton(
                onClick = {
                    onReset()
                    selectedAction = null
                },
            ) { Text("恢复默认") }
            TextButton(onClick = onSave) { Text("保存") }
        }
        Text(
            text = if (kind == Model3DKind.VRM) {
                "动作列表来自 HAST-SEC 技能，可手动添加新动作；motion_control 会按这里的绑定播放。"
            } else {
                "动作列表来自本地 VMD 素材，可手动添加；motion_control 会按 MMD 绑定播放。"
            },
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 320.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            motionActions.forEach { action ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { selectedAction = action }
                        .padding(vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(
                        selected = selectedAction == action,
                        onClick = { selectedAction = action },
                    )
                    Text(action, style = MaterialTheme.typography.bodySmall)
                }
            }
            if (motionActions.isEmpty()) {
                Text(
                    text = "动作列表为空，点击「添加 +」新建动作。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }

    SettingsCard(title = "动作绑定") {
        Text(
            text = "别名（wave/dance/...）→ 实际动作；motion_control 收到别名时按当前模型类型查这张表。",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        motionBindings.entries.forEach { (alias, action) ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(alias, style = MaterialTheme.typography.bodySmall)
                    Text(
                        action,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = { onUnbind(alias) }) {
                    Icon(
                        imageVector = Icons.Outlined.Delete,
                        contentDescription = "删除绑定",
                    )
                }
            }
        }
        TextButton(
            onClick = { showAddBinding = true },
            enabled = motionActions.isNotEmpty(),
        ) { Text("选择动作添加绑定") }
    }

    SettingsCard(title = "${kind.label} 表情") {
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            TextButton(onClick = { showAddExpression = true }) { Text("添加表情 +") }
            TextButton(
                onClick = {
                    selectedExpression?.let { onRemoveExpression(it) }
                    selectedExpression = null
                },
                enabled = selectedExpression != null,
            ) { Text("删除表情 -") }
            TextButton(onClick = onResetExpressions) { Text("恢复默认") }
        }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 180.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            expressionActions.forEach { expression ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { selectedExpression = expression }
                        .padding(vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(
                        selected = selectedExpression == expression,
                        onClick = { selectedExpression = expression },
                    )
                    Text(expression, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        Text(
            text = "表情绑定（别名 → 表情）；expression_control 工具按当前模型类型查表。",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        expressionBindings.entries.forEach { (alias, expression) ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(alias, style = MaterialTheme.typography.bodySmall)
                    Text(
                        expression,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = { onUnbindExpression(alias) }) {
                    Icon(
                        imageVector = Icons.Outlined.Delete,
                        contentDescription = "删除表情绑定",
                    )
                }
            }
        }
        TextButton(
            onClick = { showAddExpressionBinding = true },
            enabled = expressionActions.isNotEmpty(),
        ) { Text("选择表情添加绑定") }
    }

    if (showAddAction) {
        TextEntryDialog(
            title = "添加动作",
            label = "动作名",
            onDismiss = { showAddAction = false },
            onConfirm = {
                onAddAction(it)
                showAddAction = false
            },
        )
    }
    if (showAddExpression) {
        TextEntryDialog(
            title = "添加表情",
            label = "表情名",
            onDismiss = { showAddExpression = false },
            onConfirm = {
                onAddExpression(it)
                showAddExpression = false
            },
        )
    }
    if (showAddBinding) {
        BindingPickDialog(
            title = "添加动作绑定",
            actions = motionActions,
            onDismiss = { showAddBinding = false },
            onConfirm = { alias, action ->
                onBind(alias, action)
                showAddBinding = false
            },
        )
    }
    if (showAddExpressionBinding) {
        BindingPickDialog(
            title = "添加表情绑定",
            actions = expressionActions,
            onDismiss = { showAddExpressionBinding = false },
            onConfirm = { alias, expression ->
                onBindExpression(alias, expression)
                showAddExpressionBinding = false
            },
        )
    }
}

@Composable
private fun TextEntryDialog(
    title: String,
    label: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var value by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = { value = it },
                label = { Text(label) },
                singleLine = true,
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(value) },
                enabled = value.isNotBlank(),
            ) { Text("确定") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
private fun BindingPickDialog(
    title: String,
    actions: List<String>,
    onDismiss: () -> Unit,
    onConfirm: (String, String) -> Unit,
) {
    var alias by remember { mutableStateOf("") }
    var selected by remember(actions) { mutableStateOf(actions.firstOrNull().orEmpty()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = alias,
                    onValueChange = { alias = it },
                    label = { Text("别名，如 wave / dance") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text("选择动作", style = MaterialTheme.typography.labelLarge)
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 220.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    actions.forEach { action ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { selected = action }
                                .padding(vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(
                                selected = selected == action,
                                onClick = { selected = action },
                            )
                            Text(action, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(alias, selected) },
                enabled = alias.isNotBlank() && selected.isNotBlank(),
            ) { Text("添加") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

private fun CharacterModelEntry?.toModelKind(): Model3DKind = when (this?.type) {
    CharacterModelType.MMD -> Model3DKind.MMD
    CharacterModelType.VRM, CharacterModelType.GLB -> Model3DKind.VRM
    else -> Model3DKind.VRM
}
