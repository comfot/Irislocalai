package com.iris.localai.scene

enum class StageAssetKind(val label: String) {
    BACKGROUND("场景背景"),
    TEXTURE("舞台纹理"),
}

data class StageAssetItem(
    val id: String,
    val name: String,
    val kind: StageAssetKind,
    val fileName: String,
    val url: String,
    val bytes: Long,
)

/**
 * 舞台素材来自 xiaoke.ai 的公开仓库（Apache-2.0，项目生成素材），
 * 只内置目录与原始地址，图片按需下载，避免增加 APK 体积。
 */
object StageAssetCatalog {

    private const val BASE = "https://raw.githubusercontent.com/liupig/xiaokeai/master/companion/frontend/public"

    val backgrounds: List<StageAssetItem> = listOf(
        item("bg_alley", "巷口", "bg_alley.png", 2529423),
        item("bg_beach", "海边", "bg_beach.png", 1844801),
        item("bg_bedroom", "卧室", "bg_bedroom.png", 1751620),
        item("bg_cafe", "咖啡店", "bg_cafe.png", 2104198),
        item("bg_concert", "演唱会", "bg_concert.png", 2063296),
        item("bg_cyber", "赛博街道", "bg_cyber.png", 2610121),
        item("bg_flower", "花田", "bg_flower.png", 2010491),
        item("bg_forest", "森林", "bg_forest.png", 2504791),
        item("bg_guofeng", "国风庭院", "bg_guofeng.png", 1964720),
        item("bg_lab", "实验室", "bg_lab.png", 2392593),
        item("bg_library", "图书馆", "bg_library.png", 2211377),
        item("bg_matsuri", "夏日祭", "bg_matsuri.png", 2384607),
        item("bg_palace", "宫殿", "bg_palace.png", 2472665),
        item("bg_sakura", "樱花", "bg_sakura.png", 2689860),
        item("bg_skycity", "天空之城", "bg_skycity.png", 2224505),
        item("bg_snow", "雪原", "bg_snow.png", 2538549),
        item("bg_space", "太空", "bg_space.png", 2298137),
        item("bg_starry", "星空", "bg_starry.png", 2089975),
        item("bg_underwater", "海底", "bg_underwater.png", 2589624),
    )

    val textures: List<StageAssetItem> = listOf(
        texture("tex_circuit", "电路", "tex_circuit.png", 2438842),
        texture("tex_jade", "玉纹", "tex_jade.png", 2494060),
        texture("tex_petal", "花瓣", "tex_petal.png", 1515979),
        texture("tex_stagefloor", "舞台地板", "tex_stagefloor.png", 1799091),
        texture("tex_star", "星尘", "tex_star.png", 2116945),
        texture("tex_wood", "木纹", "tex_wood.png", 2260051),
    )

    val all: List<StageAssetItem> = backgrounds + textures

    fun find(id: String): StageAssetItem? = all.firstOrNull { it.id == id }

    private fun item(id: String, name: String, file: String, bytes: Long) = StageAssetItem(
        id = id,
        name = name,
        kind = StageAssetKind.BACKGROUND,
        fileName = file,
        url = "$BASE/backgrounds/$file",
        bytes = bytes,
    )

    private fun texture(id: String, name: String, file: String, bytes: Long) = StageAssetItem(
        id = id,
        name = name,
        kind = StageAssetKind.TEXTURE,
        fileName = file,
        url = "$BASE/textures/$file",
        bytes = bytes,
    )
}
