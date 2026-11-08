package com.iris.localai.data

/**
 * 参照 IRIDIS 的推荐模型目录整理的 GGUF 条目（仓库与文件信息核对于 2026-11-01）。
 * 本应用只加载 GGUF 路径，因此不收录 IRIDIS 中仅提供 MNN / LiteRT 权重的条目。
 */
data class RecommendedModel(
    val id: String,
    val name: String,
    val repoId: String,
    val purpose: String,
    val sizeBytes: Long,
    val quant: String? = null,
    val licence: String? = null,
    val fileName: String? = null,
    val mmprojFile: String? = null,
    val mmprojSize: Long = 0,
)

object RecommendedModels {

    private const val APACHE = "Apache-2.0"

    val items: List<RecommendedModel> = listOf(
        RecommendedModel(
            id = "qwen3.5-4b-3d-coder",
            name = "Qwen3.5 4B 3D Coder",
            repoId = "ccore/Qwen3.5-4B-3D-Coder",
            purpose = "把 3D / Three.js 场景需求写成可直接运行的单文件网页，可连同视觉投影器下载。",
            sizeBytes = 2_708_804_096L,
            quant = "Q4_K_M",
            licence = APACHE,
            fileName = "Qwen3.5-4B-3D-Coder-Q4_K_M.gguf",
            mmprojFile = "mmproj-Qwen3.5-4B-BF16.gguf",
            mmprojSize = 675_568_768L,
        ),
        RecommendedModel(
            id = "spark-x2.6-4b",
            name = "Spark X2.6 4B",
            repoId = "XHToken/Spark-X2.6-4B-GGUF",
            purpose = "面向手机端的 4B 对话模型，仓库内含多个 GGUF 量化档，适合日常对话与轻量工具调用。",
            sizeBytes = 3_500_000_000L,
            quant = "Q4",
        ),
        RecommendedModel(
            id = "spark-x2.6-1.7b",
            name = "Spark X2.6 1.7B",
            repoId = "XHToken/Spark-X2.6-1.7B-GGUF",
            purpose = "小内存设备可运行的 1.7B 对话模型，速度优先。",
            sizeBytes = 1_800_000_000L,
            quant = "Q4",
        ),
        RecommendedModel(
            id = "ternary-bonsai-2-2b",
            name = "Ternary Bonsai 2 2B",
            repoId = "prism-ml/Ternary-Bonsai-2-2B-gguf",
            purpose = "三元量化实验模型，2B 权重仅约 0.55 GB，适合低内存设备。",
            sizeBytes = 546_689_504L,
            quant = "Q2_0",
            licence = APACHE,
            fileName = "Ternary-Bonsai-2-2B-Q2_0.gguf",
        ),
        RecommendedModel(
            id = "ternary-bonsai-2-4b",
            name = "Ternary Bonsai 2 4B",
            repoId = "prism-ml/Ternary-Bonsai-2-4B-gguf",
            purpose = "三元量化 4B 模型，约 1.2 GB，兼顾体积与表达能力。",
            sizeBytes = 1_212_103_520L,
            quant = "Q2_0",
            licence = APACHE,
            fileName = "Ternary-Bonsai-2-4B-Q2_0.gguf",
        ),
    )

    fun byRepo(repoId: String): RecommendedModel? =
        items.firstOrNull { it.repoId.equals(repoId, ignoreCase = true) }

    fun byId(id: String): RecommendedModel? = items.firstOrNull { it.id == id }
}
