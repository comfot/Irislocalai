package com.iris.localai.engine

import com.iris.localai.data.SettingsRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

sealed interface EngineState {
    data object Idle : EngineState
    data class Loading(val progress: Float) : EngineState
    data class Ready(val details: ModelDetails) : EngineState
    data class Failed(val message: String) : EngineState
}

/**
 * 引擎的唯一入口：负责加载/卸载、串行化请求与状态广播。
 * 与 IRIDIS 的 EngineManager 一样，UI 只观察 [state]，不直接持有引擎。
 */
class EngineManager(
    private val settings: SettingsRepository,
    private val engineFactory: (Backend) -> InferenceEngine = { StubEngine() },
    private val modelPathProvider: () -> String = { settings.current.modelPath },
) {

    private val loadMutex = Mutex()

    private var engine: InferenceEngine? = null

    private val _state = MutableStateFlow<EngineState>(EngineState.Idle)
    val state: StateFlow<EngineState> = _state.asStateFlow()

    val isReady: Boolean get() = engine?.isLoaded == true

    suspend fun ensureLoaded(): Boolean = loadMutex.withLock {
        if (engine?.isLoaded == true) return@withLock true
        val config = settings.current
        val modelPath = modelPathProvider()
        _state.value = EngineState.Loading(0f)
        try {
            val created = engineFactory(config.backend)
            val details = created.load(
                modelPath = modelPath,
                config = LoadConfig(
                    contextLength = config.contextLength,
                    threads = config.threads,
                    backend = config.backend,
                    flashAttention = config.flashAttention,
                    kvCacheType = config.kvCacheType,
                    batchSize = config.batchSize,
                    useMmap = config.useMmap,
                    fallbackToCpu = config.backendFallback,
                ),
            ) { progress ->
                _state.value = EngineState.Loading(progress.coerceIn(0f, 1f))
            }
            engine = created
            _state.value = EngineState.Ready(details)
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _state.value = EngineState.Failed(e.message ?: "引擎加载失败")
            false
        }
    }

    fun generate(history: List<PromptMessage>, params: SamplingConfig): Flow<GenEvent> {
        val current = engine
        if (current == null || !current.isLoaded) return flowOf(GenEvent.Failed("引擎尚未加载"))
        return current.generate(history, params)
    }

    fun cancel() {
        engine?.cancel()
    }

    suspend fun unload() = loadMutex.withLock {
        try {
            engine?.unload()
        } finally {
            engine = null
            _state.value = EngineState.Idle
        }
    }
}
