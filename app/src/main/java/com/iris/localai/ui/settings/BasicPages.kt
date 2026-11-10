package com.iris.localai.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.iris.localai.agent.Tool
import com.iris.localai.data.AppSettings
import com.iris.localai.data.ContextStrategy
import com.iris.localai.data.ToolPermission
import com.iris.localai.engine.Backend
import com.iris.localai.engine.llama.LlamaNative
import java.io.File

@Composable
internal fun BackendPage(settings: AppSettings, viewModel: SettingsViewModel) {
    val runtimeStatus = remember {
        LlamaNative.unavailableReason ?: "可用 · llama.cpp（预编译 libiris_llama.so）"
    }
    val gpuAvailable = remember {
        listOf(
            "/vendor/lib64/libOpenCL.so",
            "/system/vendor/lib64/libOpenCL.so",
            "/system/lib64/libOpenCL.so",
        ).any { File(it).exists() }
    }
    SettingsPage {
        SettingsCard("推理后端") {
            Backend.entries
                .filter { it != Backend.NPU || settings.backend == Backend.NPU }
                .forEach { backend ->
                    OptionRow(
                        selected = settings.backend == backend,
                        title = backend.name,
                        summary = backendSummary(backend),
                        onClick = { viewModel.update { it.copy(backend = backend) } },
                    )
                }
            if (settings.backend == Backend.NPU) {
                Text(
                    text = "当前预编译运行库不支持 NPU，请改选 CPU 或 GPU。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            BasicSwitchRow(
                title = "GPU 失败时回退 CPU",
                summary = "GPU 加载失败时自动降级，避免模型无法使用",
                checked = settings.backendFallback,
            ) { on -> viewModel.update { it.copy(backendFallback = on) } }
        }

        SettingsCard("运行状态") {
            InfoRow("GGUF / llama.cpp", runtimeStatus)
            InfoRow("OpenCL GPU", if (gpuAvailable) "已检测到驱动" else "未检测到，仅 CPU")
            InfoRow("当前本地模型", settings.modelPath.ifBlank { "未选择" })
        }
    }
}

@Composable
internal fun GenerationPage(settings: AppSettings, viewModel: SettingsViewModel) {
    var showPrompt by remember { mutableStateOf(false) }
    var promptText by remember(settings.systemPrompt) { mutableStateOf(settings.systemPrompt) }

    SettingsPage {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
        ) {
            TextButton(onClick = viewModel::resetGeneration) { Text("恢复默认") }
        }

        SettingsCard("提示词与思考") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "系统提示词",
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { showPrompt = true }) { Text("编辑") }
            }
            Text(
                text = settings.systemPrompt.ifBlank { "未设置，使用内置提示词" },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            BasicSwitchRow(
                title = "深度思考",
                summary = "让模型先推理再回答",
                checked = settings.deepThinking,
            ) { on -> viewModel.update { it.copy(deepThinking = on) } }
            if (settings.deepThinking) {
                SliderRow(
                    title = "思考预算",
                    value = settings.thinkingBudget.toFloat(),
                    range = 0f..8192f,
                    steps = 31,
                    format = { if (it <= 0f) "自动" else "${it.toInt()} tokens" },
                    onCommit = { value ->
                        viewModel.update { it.copy(thinkingBudget = (value / 256).toInt() * 256) }
                    },
                )
            }
        }

        SettingsCard("输出") {
            BasicSwitchRow(
                title = "使用模型档案参数",
                summary = "云端对话优先使用语言模型的温度与输出上限",
                checked = settings.useProfileGenerationParams,
            ) { on -> viewModel.update { it.copy(useProfileGenerationParams = on) } }
            SliderRow(
                title = "最大输出",
                value = settings.maxNewTokens.toFloat(),
                range = 256f..8192f,
                steps = 30,
                format = { "${it.toInt()} tokens" },
                onCommit = { value ->
                    viewModel.update { it.copy(maxNewTokens = (value / 256).toInt() * 256) }
                },
            )
            SliderRow(
                title = "温度",
                value = settings.temperature,
                range = 0f..2f,
                steps = 39,
                format = { "%.2f".format(it) },
                onCommit = { value ->
                    viewModel.update { it.copy(temperature = (value * 20).toInt() / 20f) }
                },
            )
        }

        ExpandableSettingsCard(
            title = "高级采样",
            summary = "Top-P、Top-K、重复惩罚与随机种子",
        ) {
            SliderRow(
                title = "Top-P",
                value = settings.topP,
                range = 0.05f..1f,
                steps = 18,
                format = { "%.2f".format(it) },
                onCommit = { value ->
                    viewModel.update { it.copy(topP = (value * 20).toInt() / 20f) }
                },
            )
            SliderRow(
                title = "Top-K",
                value = settings.topK.toFloat(),
                range = 0f..100f,
                steps = 19,
                format = { if (it <= 0f) "关闭" else it.toInt().toString() },
                onCommit = { value -> viewModel.update { it.copy(topK = (value / 5).toInt() * 5) } },
            )
            SliderRow(
                title = "Min-P",
                value = settings.minP,
                range = 0f..0.3f,
                steps = 14,
                format = { "%.2f".format(it) },
                onCommit = { value ->
                    viewModel.update { it.copy(minP = (value * 100).toInt() / 100f) }
                },
            )
            SliderRow(
                title = "重复惩罚",
                value = settings.repeatPenalty,
                range = 1f..1.5f,
                steps = 9,
                format = { "%.2f".format(it) },
                onCommit = { value ->
                    viewModel.update { it.copy(repeatPenalty = (value * 100).toInt() / 100f) }
                },
            )
            SliderRow(
                title = "重复窗口",
                value = settings.repeatLastN.toFloat(),
                range = 0f..512f,
                steps = 31,
                format = { "${it.toInt()} tokens" },
                onCommit = { value ->
                    viewModel.update { it.copy(repeatLastN = (value / 16).toInt() * 16) }
                },
            )
            SliderRow(
                title = "存在惩罚",
                value = settings.presencePenalty,
                range = -2f..2f,
                steps = 39,
                format = { "%.2f".format(it) },
                onCommit = { value ->
                    viewModel.update { it.copy(presencePenalty = (value * 20).toInt() / 20f) }
                },
            )
            SliderRow(
                title = "频率惩罚",
                value = settings.frequencyPenalty,
                range = -2f..2f,
                steps = 39,
                format = { "%.2f".format(it) },
                onCommit = { value ->
                    viewModel.update { it.copy(frequencyPenalty = (value * 20).toInt() / 20f) }
                },
            )
            BasicSwitchRow(
                title = "固定随机种子",
                summary = "关闭时为每次请求使用随机种子",
                checked = settings.seedEnabled,
            ) { on -> viewModel.update { it.copy(seedEnabled = on) } }
            if (settings.seedEnabled) {
                IntField(
                    label = "种子",
                    value = settings.seedValue,
                    maxLength = 6,
                    onCommit = { value -> viewModel.update { it.copy(seedValue = value) } },
                )
            }
        }
    }

    if (showPrompt) {
        AlertDialog(
            onDismissRequest = { showPrompt = false },
            title = { Text("系统提示词") },
            text = {
                OutlinedTextField(
                    value = promptText,
                    onValueChange = { promptText = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("留空则只使用内置提示词") },
                    minLines = 4,
                    maxLines = 8,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.update { it.copy(systemPrompt = promptText.trim()) }
                        showPrompt = false
                    },
                ) { Text("保存") }
            },
            dismissButton = {
                TextButton(onClick = { showPrompt = false }) { Text("取消") }
            },
        )
    }
}

@Composable
internal fun PerformancePage(settings: AppSettings, viewModel: SettingsViewModel) {
    SettingsPage {
        SettingsCard("运行") {
            SliderRow(
                title = "线程数",
                value = settings.threads.toFloat(),
                range = 0f..12f,
                steps = 11,
                format = { if (it <= 0f) "自动" else it.toInt().toString() },
                onCommit = { value -> viewModel.update { it.copy(threads = value.toInt()) } },
            )
            Text(
                text = "线程数在下次加载模型时生效。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            BasicSwitchRow(
                title = "屏幕常亮",
                summary = "应用在前台时不自动息屏",
                checked = settings.keepScreenOn,
            ) { on -> viewModel.update { it.copy(keepScreenOn = on) } }
        }
    }
}


@Composable
internal fun AgentPage(settings: AppSettings, viewModel: SettingsViewModel) {
    val skills by viewModel.skills.collectAsStateWithLifecycle()
    SettingsPage {
        SettingsCard("智能体") {
            BasicSwitchRow(
                title = "智能体模式",
                summary = "关闭后只对话，不调用工具",
                checked = settings.agentMode,
            ) { on -> viewModel.update { it.copy(agentMode = on) } }
            SliderRow(
                title = "最大工具步数（生活/操作）",
                value = settings.agentMaxSteps.toFloat(),
                range = 1f..20f,
                steps = 18,
                format = { "${it.toInt()} 步" },
                onCommit = { value -> viewModel.update { it.copy(agentMaxSteps = value.toInt()) } },
            )
            Text(
                text = "工作空间与综合空间不限制工具步数；此设置仅作用于生活/操作空间。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            BasicSwitchRow(
                title = "计划优先",
                summary = "调用工具前先说明计划",
                checked = settings.planFirst,
            ) { on -> viewModel.update { it.copy(planFirst = on) } }
        }

        ExpandableSettingsCard(
            title = "工具（${viewModel.tools.size}）",
            summary = "开关与每个工具的权限",
        ) {
            viewModel.tools.forEach { tool ->
                ToolSettingRow(
                    tool = tool,
                    enabled = tool.name in settings.enabledToolIds,
                    permission = settings.toolPermissions[tool.name] ?: ToolPermission.ALLOW,
                    onToggle = { enabled ->
                        viewModel.update {
                            it.copy(
                                enabledToolIds = if (enabled) {
                                    it.enabledToolIds + tool.name
                                } else {
                                    it.enabledToolIds - tool.name
                                },
                            )
                        }
                    },
                    onPermission = { permission ->
                        viewModel.update {
                            it.copy(toolPermissions = it.toolPermissions + (tool.name to permission))
                        }
                    },
                )
            }
        }

        SettingsCard("技能（${skills.size}）") {
            if (skills.isEmpty()) {
                Text("暂无技能，可在「Skill 技能」页导入。", style = MaterialTheme.typography.bodySmall)
            }
            skills.forEach { skill ->
                BasicSwitchRow(
                    title = skill.name,
                    summary = skill.description,
                    checked = skill.enabled,
                ) { enabled -> viewModel.setSkillEnabled(skill.id, enabled) }
            }
        }

    }
}

@Composable
internal fun ContextPage(settings: AppSettings, viewModel: SettingsViewModel) {
    val displayedStrategy = if (settings.contextStrategy == ContextStrategy.TOKEN_BUDGET) {
        ContextStrategy.TOKEN_BUDGET
    } else {
        ContextStrategy.SLIDING_WINDOW
    }
    SettingsPage {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
        ) {
            TextButton(onClick = viewModel::resetContext) { Text("恢复默认") }
        }

        SettingsCard("上下文窗口") {
            SliderRow(
                title = "上下文长度",
                value = settings.contextLength.coerceIn(1024, 131072).toFloat(),
                range = 1024f..131072f,
                steps = 126,
                format = { "${it.toInt()} tokens" },
                onCommit = { value ->
                    viewModel.update { it.copy(contextLength = (value / 1024).toInt() * 1024) }
                },
            )
            Text(
                text = "实际可用长度还受模型档案与引擎限制。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        SettingsCard("上下文策略") {
            ContextStrategy.entries
                .filter { it == ContextStrategy.SLIDING_WINDOW || it == ContextStrategy.TOKEN_BUDGET }
                .forEach { strategy ->
                    OptionRow(
                        selected = displayedStrategy == strategy,
                        title = strategy.label,
                        summary = strategy.hint,
                        onClick = { viewModel.update { it.copy(contextStrategy = strategy) } },
                    )
                }
            if (displayedStrategy == ContextStrategy.TOKEN_BUDGET) {
                SliderRow(
                    title = "预算占比",
                    value = settings.summaryTriggerPercent.toFloat(),
                    range = 40f..95f,
                    steps = 54,
                    format = { "上下文窗口的 ${it.toInt()}%" },
                    onCommit = { value ->
                        viewModel.update { it.copy(summaryTriggerPercent = value.toInt()) }
                    },
                )
            } else {
                SliderRow(
                    title = "保留轮数",
                    value = settings.contextKeepTurns.toFloat(),
                    range = 1f..32f,
                    steps = 30,
                    format = { "${it.toInt()} 轮" },
                    onCommit = { value -> viewModel.update { it.copy(contextKeepTurns = value.toInt()) } },
                )
            }
            BasicSwitchRow(
                title = "自动压缩历史",
                summary = "超出策略限制时裁剪较早的消息",
                checked = settings.autoCompact,
            ) { on -> viewModel.update { it.copy(autoCompact = on) } }
        }

        SettingsCard("记忆与知识库") {
            BasicSwitchRow(
                title = "启用记忆",
                summary = "长期记忆总开关",
                checked = settings.memoryEnabled,
            ) { on -> viewModel.update { it.copy(memoryEnabled = on) } }
            BasicSwitchRow(
                title = "注入对话",
                summary = "对话时携带相关记忆",
                checked = settings.memoryInjectChat,
                enabled = settings.memoryEnabled,
            ) { on -> viewModel.update { it.copy(memoryInjectChat = on) } }
            BasicSwitchRow(
                title = "注入智能体",
                summary = "智能体任务携带相关记忆",
                checked = settings.memoryInjectAgent,
                enabled = settings.memoryEnabled,
            ) { on -> viewModel.update { it.copy(memoryInjectAgent = on) } }
            BasicSwitchRow(
                title = "自动生成日记",
                summary = "按打开应用的日期整理当天对话，未打开的日子跳过",
                checked = settings.memoryAutoDiary,
                enabled = settings.memoryEnabled,
            ) { on -> viewModel.update { it.copy(memoryAutoDiary = on) } }
            BasicSwitchRow(
                title = "归档时自动沉淀记忆",
                summary = "归档会话时提取稳定事实、偏好与事件；模型不可用时回退规则提取",
                checked = settings.memoryAutoSave,
                enabled = settings.memoryEnabled,
            ) { on -> viewModel.update { it.copy(memoryAutoSave = on) } }
            BasicSwitchRow(
                title = "对话中检索知识库",
                summary = "回答前检索本地文档",
                checked = settings.knowledgeInChat,
            ) { on -> viewModel.update { it.copy(knowledgeInChat = on) } }
            Text(
                text = "常驻对话只注入记忆摘要；需要详细回忆时由 memory 工具检索。日记不进入常规提示词。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ToolSettingRow(
    tool: Tool,
    enabled: Boolean,
    permission: ToolPermission,
    onToggle: (Boolean) -> Unit,
    onPermission: (ToolPermission) -> Unit,
) {
    var menu by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(tool.name, style = MaterialTheme.typography.bodyMedium)
            Text(
                text = tool.description,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Box {
            TextButton(onClick = { menu = true }) { Text(permission.label) }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                ToolPermission.entries.forEach { item ->
                    DropdownMenuItem(
                        text = { Text(item.label) },
                        onClick = {
                            onPermission(item)
                            menu = false
                        },
                    )
                }
            }
        }
        Switch(checked = enabled, onCheckedChange = onToggle)
    }
}

@Composable
private fun BasicSwitchRow(
    title: String,
    summary: String,
    checked: Boolean,
    enabled: Boolean = true,
    onChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                color = if (enabled) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
            Text(
                text = summary,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = checked, onCheckedChange = onChange, enabled = enabled)
    }
}

@Composable
private fun IntField(
    label: String,
    value: Int,
    maxLength: Int,
    onCommit: (Int) -> Unit,
) {
    var text by remember(value) { mutableStateOf(value.toString()) }

    OutlinedTextField(
        value = text,
        onValueChange = { input ->
            if (input.length <= maxLength && input.all { it.isDigit() }) {
                text = input
                input.toIntOrNull()?.let(onCommit)
            }
        },
        modifier = Modifier.fillMaxWidth(),
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
    )
}

private fun backendSummary(backend: Backend): String = when (backend) {
    Backend.CPU -> "兼容性最好，速度取决于线程数"
    Backend.GPU -> "OpenCL / Vulkan 加速，需要驱动支持"
    Backend.NPU -> "需要 Hexagon/NPU 运行库，当前预编译版未接入"
}
