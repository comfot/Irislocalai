package com.iris.localai.motion

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

enum class Model3DKind(val label: String) {
    VRM("VRM"),
    MMD("MMD"),
}

data class MotionBindingSet(
    val actions: List<String> = emptyList(),
    val bindings: Map<String, String> = emptyMap(),
)

/**
 * VRM 与 MMD 各自独立的动作绑定：别名（wave/dance/...）映射到实际动作名。
 * VRM 的实际动作是 HAST-SEC 技能名，MMD 的实际动作是 VMD 素材名。
 */
class MotionBindingStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val _sets = MutableStateFlow(load())
    val sets: StateFlow<Map<Model3DKind, MotionBindingSet>> = _sets.asStateFlow()

    fun actions(kind: Model3DKind): List<String> = _sets.value[kind]?.actions ?: defaults(kind).actions

    fun bindings(kind: Model3DKind): Map<String, String> =
        _sets.value[kind]?.bindings ?: defaults(kind).bindings

    fun resolve(kind: Model3DKind, requested: String): String? {
        val key = requested.trim().lowercase()
        if (key.isBlank()) return null
        bindings(kind)[key]?.takeIf { it.isNotBlank() }?.let { return it }
        return actions(kind).firstOrNull { it.equals(requested.trim(), ignoreCase = true) }
    }

    fun addAction(kind: Model3DKind, name: String) {
        val clean = name.trim().take(80)
        if (clean.isBlank()) return
        update(kind) { set ->
            if (set.actions.any { it.equals(clean, ignoreCase = true) }) set
            else set.copy(actions = set.actions + clean)
        }
    }

    fun removeAction(kind: Model3DKind, name: String) {
        update(kind) { set ->
            set.copy(
                actions = set.actions.filterNot { it.equals(name, ignoreCase = true) },
                bindings = set.bindings.filterNot { (_, value) -> value.equals(name, ignoreCase = true) },
            )
        }
    }

    fun bind(kind: Model3DKind, alias: String, action: String) {
        val key = alias.trim().lowercase().take(40)
        val value = action.trim().take(80)
        if (key.isBlank() || value.isBlank()) return
        update(kind) { it.copy(bindings = it.bindings + (key to value)) }
    }

    fun unbind(kind: Model3DKind, alias: String) {
        update(kind) { it.copy(bindings = it.bindings - alias.trim().lowercase()) }
    }

    fun reset(kind: Model3DKind) {
        _sets.value = _sets.value + (kind to defaults(kind))
        persist()
    }

    fun save() {
        persist()
    }

    fun ensureActions(kind: Model3DKind, extra: Collection<String>) {
        val current = actions(kind).toMutableList()
        var changed = false
        for (name in extra) {
            val clean = name.trim()
            if (clean.isNotBlank() && current.none { it.equals(clean, ignoreCase = true) }) {
                current += clean
                changed = true
            }
        }
        if (changed) update(kind) { it.copy(actions = current) }
    }

    private fun update(kind: Model3DKind, transform: (MotionBindingSet) -> MotionBindingSet) {
        val current = _sets.value[kind] ?: defaults(kind)
        _sets.value = _sets.value + (kind to transform(current))
        persist()
    }

    private fun defaults(kind: Model3DKind): MotionBindingSet = when (kind) {
        Model3DKind.VRM -> MotionBindingSet(
            actions = VRM_ACTIONS,
            bindings = mapOf(
                "idle" to "idle",
                "wave" to "right-摆动手臂",
                "dance" to "body-自然活动",
                "nod" to "body-自然活动",
                "greet" to "right-摆动手臂",
            ),
        )
        Model3DKind.MMD -> MotionBindingSet(
            actions = listOf("idle", "wave", "dance", "nod", "greet"),
            bindings = mapOf(
                "idle" to "idle",
                "wave" to "wave",
                "dance" to "dance",
                "nod" to "nod",
                "greet" to "wave",
            ),
        )
    }

    private fun load(): Map<Model3DKind, MotionBindingSet> {
        val raw = prefs.getString(KEY_SETS, null) ?: return emptyMap()
        return runCatching {
            val root = Json.parseToJsonElement(raw).jsonObject
            Model3DKind.entries.mapNotNull { kind ->
                val obj = root[kind.name] as? JsonObject ?: return@mapNotNull null
                val actions = (obj["actions"] as? kotlinx.serialization.json.JsonArray)
                    ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
                    .orEmpty()
                val bindings = (obj["bindings"] as? JsonObject)
                    ?.mapValues { (_, value) -> (value as? JsonPrimitive)?.contentOrNull.orEmpty() }
                    .orEmpty()
                kind to MotionBindingSet(actions, bindings)
            }.toMap()
        }.getOrDefault(emptyMap())
    }

    private fun persist() {
        val json = buildJsonObject {
            _sets.value.forEach { (kind, set) ->
                putJsonObject(kind.name) {
                    put("actions", kotlinx.serialization.json.JsonArray(set.actions.map { JsonPrimitive(it) }))
                    putJsonObject("bindings") {
                        set.bindings.forEach { (key, value) -> put(key, value) }
                    }
                }
            }
        }.toString()
        prefs.edit().putString(KEY_SETS, json).apply()
    }

    private companion object {
        const val PREFS = "iris_motion_bindings"
        const val KEY_SETS = "sets"

        val VRM_ACTIONS = listOf(
            "idle",
            "left-向右移动", "right-小幅自然活动", "left-向左移动", "right-向上抬高",
            "left-向下放低", "left-向上抬高", "right-向下放低", "right-向前移动",
            "left-小幅自然活动", "right-向后移动", "right-向左移动", "left-向前移动",
            "right-向右移动", "left-向后移动", "right-向上抬到头部高度",
            "left-向上抬到头部高度", "left-向上举过头部", "left-摆动手臂",
            "right-向上举过头部", "right-摆动手臂", "body-自然静止", "body-自然活动",
        )
    }
}

data class ExpressionBindingSet(
    val actions: List<String> = emptyList(),
    val bindings: Map<String, String> = emptyMap(),
)

/** 表情绑定：别名（开心/难过/...）映射到模型实际表情或情绪名。 */
class ExpressionBindingStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val _sets = MutableStateFlow(load())
    val sets: StateFlow<Map<Model3DKind, ExpressionBindingSet>> = _sets.asStateFlow()

    fun actions(kind: Model3DKind): List<String> = _sets.value[kind]?.actions ?: defaults(kind).actions

    fun bindings(kind: Model3DKind): Map<String, String> = _sets.value[kind]?.bindings ?: defaults(kind).bindings

    fun resolve(kind: Model3DKind, requested: String): String? {
        val key = requested.trim().lowercase()
        if (key.isBlank()) return null
        bindings(kind)[key]?.takeIf { it.isNotBlank() }?.let { return it }
        return actions(kind).firstOrNull { it.equals(requested.trim(), ignoreCase = true) }
    }

    fun addAction(kind: Model3DKind, name: String) {
        val clean = name.trim().take(60)
        if (clean.isBlank()) return
        update(kind) { set ->
            if (set.actions.any { it.equals(clean, ignoreCase = true) }) set
            else set.copy(actions = set.actions + clean)
        }
    }

    fun removeAction(kind: Model3DKind, name: String) {
        update(kind) { set ->
            set.copy(
                actions = set.actions.filterNot { it.equals(name, ignoreCase = true) },
                bindings = set.bindings.filterNot { (_, value) -> value.equals(name, ignoreCase = true) },
            )
        }
    }

    fun bind(kind: Model3DKind, alias: String, expression: String) {
        val key = alias.trim().lowercase().take(40)
        val value = expression.trim().take(60)
        if (key.isBlank() || value.isBlank()) return
        update(kind) { it.copy(bindings = it.bindings + (key to value)) }
    }

    fun unbind(kind: Model3DKind, alias: String) {
        update(kind) { it.copy(bindings = it.bindings - alias.trim().lowercase()) }
    }

    fun reset(kind: Model3DKind) {
        _sets.value = _sets.value + (kind to defaults(kind))
        persist()
    }

    fun save() = persist()

    private fun update(kind: Model3DKind, transform: (ExpressionBindingSet) -> ExpressionBindingSet) {
        val current = _sets.value[kind] ?: defaults(kind)
        _sets.value = _sets.value + (kind to transform(current))
        persist()
    }

    private fun defaults(kind: Model3DKind): ExpressionBindingSet {
        val bindings = mapOf(
            "开心" to "happy",
            "高兴" to "happy",
            "难过" to "sad",
            "伤心" to "sad",
            "生气" to "angry",
            "惊讶" to "surprised",
            "思考" to "think",
        )
        return when (kind) {
            Model3DKind.VRM -> ExpressionBindingSet(
                actions = listOf("happy", "sad", "angry", "surprised", "relaxed", "neutral", "think"),
                bindings = bindings + ("放松" to "relaxed"),
            )
            Model3DKind.MMD -> ExpressionBindingSet(
                actions = listOf("happy", "sad", "angry", "surprised", "think"),
                bindings = bindings,
            )
        }
    }

    private fun load(): Map<Model3DKind, ExpressionBindingSet> {
        val raw = prefs.getString(KEY_SETS, null) ?: return emptyMap()
        return runCatching {
            val root = Json.parseToJsonElement(raw).jsonObject
            Model3DKind.entries.mapNotNull { kind ->
                val obj = root[kind.name] as? JsonObject ?: return@mapNotNull null
                val actions = (obj["actions"] as? kotlinx.serialization.json.JsonArray)
                    ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
                    .orEmpty()
                val bindings = (obj["bindings"] as? JsonObject)
                    ?.mapValues { (_, value) -> (value as? JsonPrimitive)?.contentOrNull.orEmpty() }
                    .orEmpty()
                kind to ExpressionBindingSet(actions, bindings)
            }.toMap()
        }.getOrDefault(emptyMap())
    }

    private fun persist() {
        val json = buildJsonObject {
            _sets.value.forEach { (kind, set) ->
                putJsonObject(kind.name) {
                    put("actions", kotlinx.serialization.json.JsonArray(set.actions.map { JsonPrimitive(it) }))
                    putJsonObject("bindings") {
                        set.bindings.forEach { (key, value) -> put(key, value) }
                    }
                }
            }
        }.toString()
        prefs.edit().putString(KEY_SETS, json).apply()
    }

    private companion object {
        const val PREFS = "iris_expression_bindings"
        const val KEY_SETS = "sets"
    }
}
