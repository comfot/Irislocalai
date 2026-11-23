package com.iris.moew.agent.tools

import com.iris.moew.agent.Tool
import com.iris.moew.character.CharacterModelStore
import com.iris.moew.character.CharacterModelType
import com.iris.moew.data.SettingsRepository
import com.iris.moew.data.VrmMotionEngine
import com.iris.moew.motion.HastMotionStore
import com.iris.moew.motion.MotionAsset
import com.iris.moew.motion.MotionAssetStore
import com.iris.moew.motion.MotionBindingStore
import com.iris.moew.motion.MotionBridge
import com.iris.moew.motion.MotionCategory
import com.iris.moew.motion.Model3DKind
import com.iris.moew.motion.VrmaMotionStore
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * 按当前 3D 模型类型（VRM/MMD）选择对应绑定，再播放动作。
 */
class MotionControlTool(
    private val clips: HastMotionStore,
    private val assets: MotionAssetStore,
    private val bindings: MotionBindingStore,
    private val characters: CharacterModelStore,
    private val vrma: VrmaMotionStore,
    private val settings: SettingsRepository,
) : Tool {

    override val name: String = "motion_control"

    override val description: String =
        "让 3D 角色播放动作或舞蹈，按当前模型类型自动使用 VRM 或 MMD 绑定。" +
            "必须带 arguments.action，调用示例：" +
            "<tool_call>{\"name\":\"motion_control\",\"arguments\":{\"action\":\"wave\"}}</tool_call>。" +
            "action 可为绑定别名或实际动作名；不确定时先传 action=list 查看当前绑定，不要假设 dance/wave 一定存在。" +
            "VRM 根据当前引擎播放 HAST-SEC 技能或本地 VRMA 动作，MMD 播放 VMD 素材。"

    override suspend fun execute(arguments: JsonObject): String {
        val raw = listOf("action", "motion", "gesture", "name", "动作", "名称")
            .asSequence()
            .mapNotNull { key -> arguments[key]?.jsonPrimitive?.contentOrNull }
            .firstOrNull { it.isNotBlank() }
            ?.trim()
            .orEmpty()
        val requested = raw.ifBlank { "wave" }
        val kind = currentKind()
        if (requested.equals("list", ignoreCase = true) || requested == "列表") {
            return catalogSummary(kind)
        }
        return when (kind) {
            Model3DKind.MMD -> playMmd(requested)
            Model3DKind.VRM -> playVrm(requested)
        }
    }

    /** 与生活空间一致：角色卡绑定的模型优先，其次全局激活项，最后看舞台上注册的视图。 */
    private fun currentKind(): Model3DKind =
        characters.boundModel(settings.current.characterId)?.type.toKind()
            ?: characters.active()?.type.toKind()
            ?: MotionBridge.activeKind()
            ?: Model3DKind.VRM

    private fun CharacterModelType?.toKind(): Model3DKind? = when (this) {
        CharacterModelType.MMD -> Model3DKind.MMD
        CharacterModelType.VRM, CharacterModelType.GLB -> Model3DKind.VRM
        else -> null
    }

    private fun playMmd(requested: String): String {
        val resolved = bindings.resolve(Model3DKind.MMD, requested)
            ?: looseBinding(Model3DKind.MMD, requested)
            ?: requested
        val asset = resolveMmdAsset(resolved)
            ?: resolveMmdAsset(requested)
            ?: assets.assets.value.firstOrNull {
                it.name.equals(resolved, ignoreCase = true) || it.id.equals(resolved, ignoreCase = true)
            }
        if (asset == null) {
            val hint = if (!MotionBridge.hasMmdView()) {
                "；当前没有显示中的 MMD 模型，请切到生活空间"
            } else {
                "；请在「设置 › 3D 控制 › 绑定配置 › MMD」添加该 VMD 动作并绑定"
            }
            val names = assets.assets.value.take(8).joinToString("、") { it.name }
            val available = if (names.isBlank()) "" else "；可用动作：$names"
            return "MMD 动作未找到：$resolved$hint$available"
        }
        MotionBridge.playAsset(
            path = asset.id,
            loop = asset.category == MotionCategory.DANCE,
            bgmPath = asset.bgm?.absolutePath,
            holdIdle = asset.name.contains("坐"),
        )
        val bgm = asset.bgm?.let { "，配乐 ${it.name}" }.orEmpty()
        val suffix = if (MotionBridge.hasMmdView()) "" else "（当前没有显示中的 MMD 模型）"
        return "已播放 MMD 动作：${asset.name}（${asset.category.label}$bgm）$suffix"
    }

    private fun playVrm(requested: String): String {
        if (settings.current.vrmMotionEngine == VrmMotionEngine.VRMA) {
            return playVrma(requested)
        }
        val resolved = bindings.resolve(Model3DKind.VRM, requested)
            ?: looseBinding(Model3DKind.VRM, requested)
            ?: requested
        val catalog = runCatching { clips.catalog() }.getOrDefault(emptyList())
        val action = when {
            catalog.isEmpty() -> resolved
            resolved in catalog -> resolved
            else -> catalog.firstOrNull { it.contains(resolved, ignoreCase = true) } ?: resolved
        }
        MotionBridge.play(action)
        val hints = buildList {
            if (catalog.isEmpty()) add("VRM 动作库尚未就绪，请在「市场 › 动作模型 › VRM 动作」下载")
            if (!MotionBridge.hasActiveView()) {
                add("当前没有显示中的 3D 角色，请切到生活空间")
            } else if (!MotionBridge.hasMotionView()) {
                add("当前不是 VRM/GLB 模型，请切换到 VRM 或使用 MMD 动作")
            }
        }
        val suffix = if (hints.isEmpty()) "" else "（${hints.joinToString("；")}）"
        return "已向角色发送 VRM 动作：$action$suffix"
    }

    private fun playVrma(requested: String): String {
        val resolved = bindings.resolve(Model3DKind.VRM, requested)
            ?: looseBinding(Model3DKind.VRM, requested)
            ?: requested
        val asset = vrma.findByName(resolved)
            ?: vrma.findByName(requested)
            ?: vrma.assets.value.firstOrNull {
                val lower = it.name.lowercase()
                when (requested.lowercase()) {
                    "dance", "跳舞", "舞蹈" -> lower.contains("舞") || lower.contains("dance")
                    "wave", "挥手", "摆手", "打招呼" ->
                        lower.contains("挥") || lower.contains("wave") || lower.contains("招手")
                    "nod", "点头" -> lower.contains("点头") || lower.contains("nod")
                    "idle", "待机", "stand" -> lower.contains("待机") || lower.contains("idle")
                    else -> false
                }
            }
        if (asset == null) {
            return "VRMA 动作未找到：$resolved；请在「设置 › 3D 控制 › 控制底层 › VRM 控制」导入 .vrma 并添加绑定"
        }
        MotionBridge.playVrma(vrma.virtualUrl(asset), holdIdle = asset.name.contains("坐"))
        val suffix = if (MotionBridge.hasMotionView() || MotionBridge.hasActiveView()) ""
        else "（当前没有显示中的 VRM 角色，请切到生活空间）"
        return "已播放 VRMA 动作：${asset.name}$suffix"
    }

    private fun looseBinding(kind: Model3DKind, requested: String): String? {
        val wanted = requested.trim().lowercase()
        val keywords = when (wanted) {
            "dance", "跳舞", "舞蹈" -> listOf("舞", "dance")
            "wave", "挥手", "摆手", "打招呼", "greet" -> listOf("挥", "摆手", "招呼", "wave", "greet")
            "nod", "点头" -> listOf("点头", "nod")
            "idle", "待机", "stand" -> listOf("待机", "idle", "站")
            else -> listOf(wanted)
        }
        return bindings.bindings(kind).entries.firstOrNull { (alias, action) ->
            keywords.any { alias.contains(it, ignoreCase = true) || action.contains(it, ignoreCase = true) }
        }?.value
    }

    private fun resolveMmdAsset(value: String): MotionAsset? {
        assets.findByName(value)?.let { return it }
        val pool = when (value.lowercase()) {
            "dance", "跳舞", "舞蹈" -> assets.assets.value.filter { it.category == MotionCategory.DANCE }
            "idle", "待机", "stand" -> assets.assets.value.filter { it.category == MotionCategory.IDLE }
            "wave", "挥手", "摆手", "打招呼", "greet" ->
                assets.assets.value.filter { it.category == MotionCategory.GREET }
            "nod", "点头" -> assets.assets.value.filter { it.category == MotionCategory.INTERACT }
            else -> emptyList()
        }
        return pool.randomOrNull()
    }

    private fun catalogSummary(kind: Model3DKind): String {
        val actions = bindings.actions(kind)
        val maps = bindings.bindings(kind)
        val actual = when (kind) {
            Model3DKind.VRM -> runCatching { clips.catalog() }.getOrDefault(emptyList()) +
                vrma.assets.value.map { it.name }
            Model3DKind.MMD -> assets.assets.value.map { it.name }
        }
        return buildString {
            appendLine("当前模型类型：${kind.label}")
            if (kind == Model3DKind.VRM) {
                appendLine("当前 VRM 引擎：${settings.current.vrmMotionEngine.label}")
            }
            appendLine("绑定（别名 → 动作）：")
            maps.entries.take(30).forEach { (alias, action) -> appendLine("- $alias → $action") }
            appendLine("可添加动作（${actions.size}）：${actions.take(30).joinToString("、")}")
            if (actual.isNotEmpty()) {
                appendLine("实际可用（${actual.size}）：${actual.take(30).joinToString("、")}")
            }
        }.trim()
    }
}
