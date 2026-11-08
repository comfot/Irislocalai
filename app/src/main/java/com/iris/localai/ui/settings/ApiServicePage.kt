package com.iris.localai.ui.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.iris.localai.server.ApiAuth
import com.iris.localai.server.ApiState
import java.util.UUID

@Composable
internal fun ApiServicePage(viewModel: SettingsViewModel = androidx.lifecycle.viewmodel.compose.viewModel()) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val apiState by viewModel.apiState.collectAsStateWithLifecycle()
    val profiles by viewModel.modelProfiles.collectAsStateWithLifecycle()
    val activeModelId by viewModel.activeModelId.collectAsStateWithLifecycle()

    val context = LocalContext.current
    val focus = LocalFocusManager.current
    var portText by remember(settings.apiPort) { mutableStateOf(settings.apiPort.toString()) }
    var keyText by remember(settings.apiKey) { mutableStateOf(settings.apiKey) }
    var showKey by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<String?>(null) }

    fun copy(text: String, label: String) {
        val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        manager.setPrimaryClip(ClipData.newPlainText(label, text))
        notice = "已复制$label"
    }

    fun applyKey(key: String, allowLan: Boolean) {
        keyText = key
        viewModel.applyApiConfig(settings.apiPort, allowLan, key)
    }

    fun generateKey() {
        val key = UUID.randomUUID().toString().replace("-", "").take(32)
        applyKey(key, settings.apiAllowLan)
        showKey = true
        notice = "已生成新的 API Key"
    }

    val portValid = portText.toIntOrNull()?.let { it in 1024..65535 } == true
    val running = apiState as? ApiState.Running
    val activeModel = profiles.firstOrNull { it.id == activeModelId }

    SettingsPage {
        SettingsCard("API 服务") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("对外提供模型 API", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        text = when (val state = apiState) {
                            ApiState.Stopped -> "已停止"
                            is ApiState.Running ->
                                "运行中 · ${state.port} · ${if (state.lan) "局域网" else "仅本机"}"
                            is ApiState.Failed -> "启动失败：${state.message}"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = if (apiState is ApiState.Failed) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
                Switch(
                    checked = settings.apiEnabled,
                    onCheckedChange = { enabled ->
                        viewModel.setApiEnabled(enabled)
                        notice = if (enabled) "API 已启动" else "API 已停止"
                    },
                )
            }
            Text(
                text = "OpenAI 兼容：/v1/models、/v1/chat/completions（含 SSE）、/health",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = "当前模型：${activeModel?.name ?: "默认配置"}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        SettingsCard("端口与网络") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = portText,
                    onValueChange = { input ->
                        if (input.length <= 5 && input.all { it.isDigit() }) portText = input
                    },
                    modifier = Modifier.weight(1f),
                    label = { Text("端口") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Number,
                        imeAction = androidx.compose.ui.text.input.ImeAction.Done,
                    ),
                    keyboardActions = KeyboardActions(onDone = { focus.clearFocus() }),
                )
                Spacer(Modifier.width(8.dp))
                Button(
                    onClick = {
                        val port = portText.toIntOrNull()
                        if (port != null && port in 1024..65535) {
                            viewModel.applyApiConfig(port, settings.apiAllowLan, keyText)
                            notice = "端口已应用"
                            focus.clearFocus()
                        }
                    },
                    enabled = portValid && portText != settings.apiPort.toString(),
                ) { Text("应用") }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("允许局域网访问", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        text = "必须设置 API Key；否则只监听 127.0.0.1",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = settings.apiAllowLan,
                    onCheckedChange = { on ->
                        if (on && keyText.isBlank()) {
                            val key = UUID.randomUUID().toString().replace("-", "").take(32)
                            applyKey(key, true)
                            showKey = true
                            notice = "已生成 API Key 并开启局域网"
                        } else {
                            viewModel.applyApiConfig(settings.apiPort, on, keyText)
                            notice = if (on) "已允许局域网访问" else "已恢复仅本机"
                        }
                    },
                )
            }
        }

        SettingsCard("API Key") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = keyText,
                    onValueChange = { keyText = ApiAuth.sanitize(it) },
                    modifier = Modifier.weight(1f),
                    label = { Text("Bearer / x-api-key") },
                    singleLine = true,
                    visualTransformation = if (showKey) {
                        VisualTransformation.None
                    } else {
                        PasswordVisualTransformation()
                    },
                )
                TextButton(onClick = { showKey = !showKey }) {
                    Text(if (showKey) "隐藏" else "显示")
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Button(onClick = { generateKey() }) { Text("生成") }
                TextButton(
                    onClick = {
                        viewModel.applyApiConfig(settings.apiPort, settings.apiAllowLan, keyText)
                        notice = "API Key 已保存"
                    },
                ) { Text("保存") }
                TextButton(
                    onClick = { if (keyText.isNotBlank()) copy(keyText, "API Key") },
                    enabled = keyText.isNotBlank(),
                ) { Text("复制") }
                TextButton(
                    onClick = {
                        applyKey("", false)
                        notice = "已清除 API Key，并关闭局域网"
                    },
                    enabled = keyText.isNotBlank() || settings.apiAllowLan,
                ) { Text("清除") }
            }
            Text(
                text = "无 Key 时拒绝浏览器 Origin 与非回环 Host 请求；有 Key 时校验 Authorization: Bearer 或 x-api-key。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if (running != null) {
            SettingsCard("访问地址") {
                running.addresses.forEach { address ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = address,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.weight(1f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        TextButton(onClick = { copy(address, "地址") }) { Text("复制") }
                    }
                }
                val base = running.addresses.firstOrNull()?.removeSuffix("/v1") ?: ""
                val auth = if (keyText.isNotBlank()) {
                    " \\\n  -H \"Authorization: Bearer ${keyText}\""
                } else {
                    ""
                }
                Text(
                    text = "curl $base/v1/chat/completions \\\n" +
                        "  -H \"Content-Type: application/json\"$auth \\\n" +
                        "  -d '{\"messages\":[{\"role\":\"user\",\"content\":\"你好\"}],\"stream\":true}'",
                    style = MaterialTheme.typography.bodySmall,
                )
                TextButton(onClick = { copy("$base/v1/models", "模型地址") }) {
                    Text("复制 /v1/models")
                }
            }
        }

        if (notice != null) {
            Text(
                text = notice.orEmpty(),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
        }
    }
}
