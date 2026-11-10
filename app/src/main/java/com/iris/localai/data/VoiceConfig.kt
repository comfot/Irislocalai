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
    MIMO("小米 MiMo", "https://api.xiaomimimo.com/v1"),
    VOLCENGINE("火山引擎", "https://ark.cn-beijing.volces.com/api/v3"),
    CUSTOM("自定义", ""),
}

fun CloudVoiceProvider.defaultTtsModel(): String = when (this) {
    CloudVoiceProvider.MIMO -> "mimo-v2.5-tts-voiceclone"
    CloudVoiceProvider.OPENAI -> "gpt-4o-mini-tts"
    else -> ""
}

fun CloudVoiceProvider.defaultAsrModel(): String = when (this) {
    CloudVoiceProvider.MIMO -> "mimo-v2.5-asr"
    CloudVoiceProvider.OPENAI -> "whisper-1"
    else -> ""
}

data class VoiceModelPack(
    val id: String,
    val kind: VoiceKind,
    val title: String,
    val description: String,
    val repo: String,
    val revision: String,
    val manifestAsset: String,
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
            id = "kokoro",
            kind = VoiceKind.TTS,
            title = "Kokoro 多语言",
            description = "sherpa-onnx Kokoro int8，中英日等多语言离线 TTS",
            repo = "csukuangfj/kokoro-int8-multi-lang-v1_1",
            revision = "155831f1b4ba23b1f5c058be6a61df90cefb2a37",
            manifestAsset = "voice/kokoro-int8-multi-lang-v1_1.json",
            approximateBytes = 215_000_000L,
        ),
        VoiceModelPack(
            id = "melo",
            kind = VoiceKind.TTS,
            title = "MeloTTS 中英混读",
            description = "sherpa-onnx VITS MeloTTS，中文/英文离线合成",
            repo = "csukuangfj/vits-melo-tts-zh_en",
            revision = "a0d5c6a264c0ef92d70d8661d8cc502d79627cd6",
            manifestAsset = "voice/vits-melo-tts-zh_en.json",
            approximateBytes = 192_000_000L,
        ),
        VoiceModelPack(
            id = "sensevoice",
            kind = VoiceKind.STT,
            title = "SenseVoice Small",
            description = "中英日韩粤离线识别，sherpa-onnx int8",
            repo = "csukuangfj/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-2024-07-17",
            revision = "2365baeacb507f821a0c8120fcee3d484dba7a07",
            manifestAsset = "voice/sense-voice-int8.json",
            approximateBytes = 240_000_000L,
        ),
        VoiceModelPack(
            id = "paraformer",
            kind = VoiceKind.STT,
            title = "Paraformer 中文",
            description = "中文离线识别，sherpa-onnx int8",
            repo = "csukuangfj/sherpa-onnx-paraformer-zh-2024-03-09",
            revision = "906992d326ebf0c5171cde675aa0902be9e5bc6c",
            manifestAsset = "voice/paraformer-zh-int8.json",
            approximateBytes = 228_000_000L,
        ),
    )

    fun of(id: String?): VoiceModelPack? = packs.firstOrNull { it.id == id }

    fun first(kind: VoiceKind): VoiceModelPack? = packs.firstOrNull { it.kind == kind }
}
