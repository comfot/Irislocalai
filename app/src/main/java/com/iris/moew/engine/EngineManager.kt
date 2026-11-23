package com.iris.moew.engine

import com.iris.moew.data.ModelProfile
import com.iris.moew.data.ModelRole
import com.iris.moew.data.SettingsRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
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
    private val engineFactory: (Backend, ModelProfile) -> InferenceEngine = { _, _ -> StubEngine() },
    private val modelPathProvider: (ModelProfile) -> String = { settings.current.modelPath },
) {

    private val loadMutex = Mutex()

    private var engine: InferenceEngine? = null

    private val _state = MutableStateFlow<EngineState>(EngineState.Idle)
    val state: StateFlow<EngineState> = _state.asStateFlow()

    val isReady: Boolean get() = engine?.isLoaded == true

    suspend fun ensureLoaded(profile: ModelProfile? = null): Boolean = loadMutex.withLock {
        if (engine?.isLoaded == true) return@withLock true
        val activeProfile = profile ?: return@withLock false
        val config = settings.current
        val modelPath = modelPathProvider(activeProfile)
        // 模型页的档案上下文长度与上下文页的全局值都可能调大：取较大者，避免任一处设置被静默忽略。
        val effectiveContextLength = maxOf(
            config.contextLength,
            activeProfile.role(ModelRole.LANGUAGE).contextLength,
        ).coerceAtLeast(1024)
        _state.value = EngineState.Loading(0f)
        try {
            val created = engineFactory(config.backend, activeProfile)
            val details = created.load(
                modelPath = modelPath,
                config = LoadConfig(
                    contextLength = effectiveContextLength,
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

    fun generate(history: List<PromptMessage>, params: SamplingConfig): Flow<GenEvent> = flow {
        val current = engine
        if (current == null || !current.isLoaded) { emit(GenEvent.Failed("引擎尚未加载")); return@flow }
        var gpuFailure = false
        current.generate(history, params).collect { event ->
            if (event is GenEvent.Failed) gpuFailure = true
            emit(event)
        }
        // Vulkan 驱动拒绝部分 pipeline（ErrorUnknown）时，推理会失败——自动切回 CPU，避免每次都失败。
        if (gpuFailure && settings.current.backend == Backend.GPU) {
            unload()
            settings.update { it.copy(backend = Backend.CPU) }
            emit(GenEvent.Failed("GPU 推理失败（驱动不支持该模型的 pipeline），已自动切回 CPU"))
        }
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
