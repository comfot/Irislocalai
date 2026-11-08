package com.iris.localai.data

enum class VoiceEngineType(val label: String) {
    SYSTEM("系统"),
    LOCAL("本地模型"),
    CLOUD("云端"),
}

enum class VoiceKind(val label: String) {
    TTS("语音合成"),
    STT("语音识别"),
}

enum class CloudVoiceProvider(val label: String, val defaultEndpoint: String) {
    OPENAI("OpenAI", "https://api.openai.com/v1"),
    VOLCENGINE("火山引擎", "https://ark.cn-beijing.volces.com/api/v3"),
    CUSTOM("自定义", ""),
}

data class VoiceModelPack(
    val id: String,
    val kind: VoiceKind,
    val title: String,
    val description: String,
    val repo: String,
    val approximateBytes: Long,
) {
    val sizeLabel: String
        get() = when {
            approximateBytes >= 1_000_000_000L ->
                "%.1f GB".format(approximateBytes / 1_000_000_000.0)
            approximateBytes >= 1_000_000L ->
                "%.0f MB".format(approximateBytes / 1_000_000.0)
            else -> "%.0f KB".format(approximateBytes / 1000.0)
        }
}

object VoiceModelCatalog {
    val packs = listOf(
        VoiceModelPack(
            id = "indextts2",
            kind = VoiceKind.TTS,
            title = "IndexTTS-2",
            description = "零样本、情感与时长可控 TTS；本地推理待接入",
            repo = "IndexTeam/IndexTTS-2",
            approximateBytes = 5_900_000_000L,
        ),
        VoiceModelPack(
            id = "kokoro",
            kind = VoiceKind.TTS,
            title = "Kokoro 多语言",
            description = "sherpa-onnx Kokoro int8，轻量离线 TTS",
            repo = "csukuangfj/kokoro-int8-multi-lang-v1_1",
            approximateBytes = 380_000_000L,
        ),
        VoiceModelPack(
            id = "sensevoice",
            kind = VoiceKind.STT,
            title = "SenseVoice Small",
            description = "中英日韩粤离线识别，sherpa-onnx int8",
            repo = "csukuangfj/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-2024-07-17",
            approximateBytes = 240_000_000L,
        ),
        VoiceModelPack(
            id = "paraformer",
            kind = VoiceKind.STT,
            title = "Paraformer 中文",
            description = "中文离线识别，sherpa-onnx int8",
            repo = "csukuangfj/sherpa-onnx-paraformer-zh-2024-03-09",
            approximateBytes = 230_000_000L,
        ),
    )

    fun of(id: String?): VoiceModelPack? = packs.firstOrNull { it.id == id }
}
