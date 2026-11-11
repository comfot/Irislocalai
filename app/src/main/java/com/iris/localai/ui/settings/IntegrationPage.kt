package com.iris.localai.ui.settings

import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.iris.localai.IrisApp
import com.iris.localai.integration.IntegrationSettings
import com.iris.localai.integration.WeixinLoginClient
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** 设置 › 接入与自动化：Telegram / QQ / 微信 / AI 浏览器。 */
@Composable
internal fun IntegrationPage() {
    val context = LocalContext.current
    val graph = remember(context) { (context.applicationContext as IrisApp).graph }
    val store = graph.integrationStore
    val settings by store.settings.collectAsStateWithLifecycle()
    val telegramStatus by graph.integrations.telegram.status.collectAsStateWithLifecycle()
    val qqStatus by graph.integrations.qq.status.collectAsStateWithLifecycle()
    val weixinStatus by graph.integrations.weixin.status.collectAsStateWithLifecycle()

    var telegramToken by remember(settings.telegram.token) { mutableStateOf(settings.telegram.token) }
    var qqAppId by remember(settings.qq.appId) { mutableStateOf(settings.qq.appId) }
    var qqSecret by remember(settings.qq.appSecret) { mutableStateOf(settings.qq.appSecret) }
    var weixinBase by remember(settings.weixin.baseUrl) { mutableStateOf(settings.weixin.baseUrl) }
    var weixinToken by remember(settings.weixin.botToken) { mutableStateOf(settings.weixin.botToken) }
    var weixinBotId by remember(settings.weixin.botId) { mutableStateOf(settings.weixin.botId) }
    var browserHome by remember(settings.browser.homeUrl) { mutableStateOf(settings.browser.homeUrl) }
    var browserBlock by remember(settings.browser.blockDomains) { mutableStateOf(settings.browser.blockDomains) }
    var browserAllow by remember(settings.browser.allowDomains) { mutableStateOf(settings.browser.allowDomains) }
    var browserShot by remember(settings.browser.allowScreenshot) { mutableStateOf(settings.browser.allowScreenshot) }
    val scope = rememberCoroutineScope()
    var loginDialog by remember { mutableStateOf(false) }
    var qrImage by remember { mutableStateOf<Bitmap?>(null) }
    var loginStatus by remember { mutableStateOf("") }
    var loginJob by remember { mutableStateOf<Job?>(null) }

    fun startWeixinLogin() {
        val base = weixinBase.trim().ifBlank { "https://ilinkai.weixin.qq.com" }
        loginDialog = true
        qrImage = null
        loginStatus = "正在获取二维码…"
        loginJob?.cancel()
        loginJob = scope.launch {
            try {
                val client = WeixinLoginClient()
                var qr = client.requestQr(base)
                qrImage = makeQrBitmap(qr.content)
                loginStatus = "请使用微信扫码"
                var refreshes = 0
                val deadline = System.currentTimeMillis() + 5 * 60_000
                while (isActive && System.currentTimeMillis() < deadline) {
                    val status = client.pollStatus(qr.qrcode, base)
                    when (status.status) {
                        "confirmed" -> {
                            val token = status.botToken
                            if (token.isNullOrBlank()) {
                                loginStatus = "登录成功但未返回 bot_token"
                                return@launch
                            }
                            store.update {
                                it.copy(
                                    weixin = it.weixin.copy(
                                        baseUrl = status.baseUrl ?: base,
                                        botToken = token,
                                        botId = status.botId.orEmpty(),
                                        enabled = true,
                                    ),
                                )
                            }
                            graph.integrations.apply()
                            weixinBase = status.baseUrl ?: base
                            weixinToken = token
                            weixinBotId = status.botId.orEmpty()
                            loginStatus = "登录成功"
                            qrImage = null
                            delay(800)
                            loginDialog = false
                            return@launch
                        }
                        "scaned" -> loginStatus = "已扫码，请在手机上确认"
                        "expired" -> {
                            if (refreshes++ >= 3) {
                                loginStatus = "二维码已过期，请重试"
                                qrImage = null
                                return@launch
                            }
                            qr = client.requestQr(base)
                            qrImage = makeQrBitmap(qr.content)
                            loginStatus = "二维码已刷新，请重新扫码"
                        }
                        else -> loginStatus = "等待扫码…"
                    }
                    if (status.status != "confirmed") delay(1_000)
                }
                loginStatus = "登录超时，请重试"
            } catch (error: kotlinx.coroutines.CancellationException) {
                throw error
            } catch (error: Exception) {
                loginStatus = "登录失败：${error.message ?: error.javaClass.simpleName}"
            }
        }
    }

    fun apply(transform: (IntegrationSettings) -> IntegrationSettings) {
        store.update(transform)
        graph.integrations.apply()
    }

    SettingsPage {
        SettingsCard("Telegram Bot") {
            Text(
                text = "在 @BotFather 创建机器人并填入 token。机器人收到私聊文字后交给 Agent，只启用安全工具。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = telegramToken,
                onValueChange = { telegramToken = it },
                label = { Text("Bot Token") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "状态：${telegramStatus.label()}",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.weight(1f),
                )
                OutlinedButton(
                    onClick = {
                        apply {
                            it.copy(
                                telegram = it.telegram.copy(
                                    token = telegramToken.trim(),
                                    enabled = true,
                                ),
                            )
                        }
                    },
                ) { Text("保存并启用") }
                Spacer(Modifier.width(8.dp))
                OutlinedButton(
                    onClick = {
                        apply { it.copy(telegram = it.telegram.copy(enabled = false)) }
                    },
                ) { Text("停止") }
            }
        }

        SettingsCard("QQ Bot") {
            Text(
                text = "QQ 开放平台 Bot API v2（q.qq.com）：填写机器人 AppID 与 Secret，仅处理 C2C 私聊。" +
                    "需要机器人已获得消息权限，且当前网络可访问 api.sgroup.qq.com。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = qqAppId,
                onValueChange = { qqAppId = it },
                label = { Text("AppID") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = qqSecret,
                onValueChange = { qqSecret = it },
                label = { Text("AppSecret") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "状态：${qqStatus.label()}",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.weight(1f),
                )
                OutlinedButton(
                    onClick = {
                        apply {
                            it.copy(
                                qq = it.qq.copy(
                                    appId = qqAppId.trim(),
                                    appSecret = qqSecret.trim(),
                                    enabled = true,
                                ),
                            )
                        }
                    },
                ) { Text("保存并启用") }
                Spacer(Modifier.width(8.dp))
                OutlinedButton(
                    onClick = { apply { it.copy(qq = it.qq.copy(enabled = false)) } },
                ) { Text("停止") }
            }
        }

        SettingsCard("微信 Bot（iLink）") {
            Text(
                text = "微信 iLink 协议：需要扫码登录获取 bot_token 与 ilink_bot_id（可先用参考工具获取，再粘贴到这里）。" +
                    "该协议不是微信官方公开 API，可能随服务端调整，请只在本人账号上使用。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = weixinBase,
                onValueChange = { weixinBase = it },
                label = { Text("iLink Base URL") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = weixinToken,
                onValueChange = { weixinToken = it },
                label = { Text("bot_token") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = weixinBotId,
                onValueChange = { weixinBotId = it },
                label = { Text("ilink_bot_id（可选，仅显示）") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedButton(
                onClick = { startWeixinLogin() },
                enabled = !loginDialog,
            ) { Text("扫码登录") }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "状态：${weixinStatus.label()}",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.weight(1f),
                )
                OutlinedButton(
                    onClick = {
                        apply {
                            it.copy(
                                weixin = it.weixin.copy(
                                    baseUrl = weixinBase.trim().ifBlank { "https://ilinkai.weixin.qq.com" },
                                    botToken = weixinToken.trim(),
                                    botId = weixinBotId.trim(),
                                    enabled = true,
                                ),
                            )
                        }
                    },
                ) { Text("保存并启用") }
                Spacer(Modifier.width(8.dp))
                OutlinedButton(
                    onClick = { apply { it.copy(weixin = it.weixin.copy(enabled = false)) } },
                ) { Text("停止") }
            }
        }

        SettingsCard("AI 浏览器") {
            Text(
                text = "内置 WebView 浏览器：Agent 先用 browser_snapshot 取带索引的正文和元素，再按 index 点击/输入；支持滚动、等待、前进后退与域名策略。" +
                    "截图只返回文件路径，登录/支付页或含密码框时禁止截图。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = browserHome,
                onValueChange = { browserHome = it },
                label = { Text("首页 URL") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = browserBlock,
                onValueChange = { browserBlock = it },
                label = { Text("阻断域名（逗号或换行分隔）") },
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = browserAllow,
                onValueChange = { browserAllow = it },
                label = { Text("允许域名（留空表示不限制）") },
                modifier = Modifier.fillMaxWidth(),
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "允许截图（含密码框或登录/支付页会拒绝）",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.weight(1f),
                )
                Switch(checked = browserShot, onCheckedChange = { browserShot = it })
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = {
                        apply {
                            it.copy(
                                browser = it.browser.copy(
                                    homeUrl = browserHome.trim(),
                                    blockDomains = browserBlock.trim(),
                                    allowDomains = browserAllow.trim(),
                                    allowScreenshot = browserShot,
                                ),
                            )
                        }
                        graph.browser.open(browserHome)
                    },
                ) { Text("打开 AI 浏览器") }
                OutlinedButton(
                    onClick = {
                        graph.browser.open("https://www.bing.com")
                    },
                ) { Text("打开 Bing") }
            }
        }

        Text(
            text = "消息通道在应用进程内运行；切换到后台后系统可能暂停网络，建议保持应用前台或配合电池优化白名单。",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 2.dp),
        )
    }

    if (loginDialog) {
        AlertDialog(
            onDismissRequest = {
                loginDialog = false
                loginJob?.cancel()
            },
            title = { Text("微信扫码登录") },
            text = {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    qrImage?.let {
                        Image(
                            bitmap = it.asImageBitmap(),
                            contentDescription = "微信登录二维码",
                            modifier = Modifier.size(240.dp),
                        )
                        Spacer(Modifier.height(12.dp))
                    }
                    Text(
                        text = loginStatus,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        loginDialog = false
                        loginJob?.cancel()
                    },
                ) { Text("关闭") }
            },
        )
    }
}

private fun makeQrBitmap(content: String, size: Int = 320): Bitmap? = runCatching {
    val matrix = QRCodeWriter().encode(
        content,
        BarcodeFormat.QR_CODE,
        size,
        size,
        mapOf(EncodeHintType.MARGIN to 1),
    )
    val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    for (x in 0 until size) {
        for (y in 0 until size) {
            bitmap.setPixel(x, y, if (matrix[x, y]) AndroidColor.BLACK else AndroidColor.WHITE)
        }
    }
    bitmap
}.getOrNull()
