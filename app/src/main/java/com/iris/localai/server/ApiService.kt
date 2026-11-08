package com.iris.localai.server

import com.iris.localai.data.SettingsRepository
import com.iris.localai.engine.EngineManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.net.Inet4Address
import java.net.NetworkInterface

sealed interface ApiState {
    data object Stopped : ApiState
    data class Running(
        val port: Int,
        val lan: Boolean,
        val addresses: List<String>,
    ) : ApiState

    data class Failed(val message: String) : ApiState
}

/**
 * 本地 API 生命周期：默认只绑定 127.0.0.1；开启 LAN 且设置了 API Key 时绑定 0.0.0.0。
 * 启动/重启会读取当前设置，供设置页即时应用。
 */
class ApiService(
    private val engines: EngineManager,
    private val settings: SettingsRepository,
    private val modelIdProvider: () -> String = { "iris-local" },
) {

    private var server: HttpServer? = null

    private val _state = MutableStateFlow<ApiState>(ApiState.Stopped)
    val state: StateFlow<ApiState> = _state.asStateFlow()

    fun syncEnabled() {
        if (settings.current.apiEnabled) start() else stop()
    }

    fun toggle() {
        if (server == null) start() else stop()
    }

    fun start() {
        if (server != null) return
        val config = settings.current
        val lan = config.apiAllowLan && config.apiKey.isNotBlank()
        val host = if (lan) "0.0.0.0" else "127.0.0.1"
        try {
            val created = HttpServer(
                port = config.apiPort,
                host = host,
                handler = ApiRoutes(
                    engines = engines,
                    modelIdProvider = modelIdProvider,
                    keyProvider = { settings.current.apiKey },
                )::handle,
            )
            created.start()
            server = created
            _state.value = ApiState.Running(
                port = config.apiPort,
                lan = lan,
                addresses = addresses(config.apiPort, lan),
            )
        } catch (e: Exception) {
            _state.value = ApiState.Failed(e.message ?: "API 启动失败")
        }
    }

    fun stop() {
        server?.stop()
        server = null
        _state.value = ApiState.Stopped
    }

    fun restart() {
        stop()
        start()
    }

    private fun addresses(port: Int, lan: Boolean): List<String> {
        val list = mutableListOf("http://127.0.0.1:$port/v1", "http://localhost:$port/v1")
        if (lan) {
            localIpv4()?.let { list += "http://$it:$port/v1" }
        }
        return list
    }

    private fun localIpv4(): String? = runCatching {
        java.util.Collections.list(NetworkInterface.getNetworkInterfaces())
            .filter { it.isUp && !it.isLoopback }
            .flatMap { java.util.Collections.list(it.inetAddresses) }
            .filterIsInstance<Inet4Address>()
            .firstOrNull { it.isSiteLocalAddress }
            ?.hostAddress
    }.getOrNull()
}
