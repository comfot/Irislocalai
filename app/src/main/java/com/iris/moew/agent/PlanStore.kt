package com.iris.moew.agent

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.io.File
import java.util.UUID

enum class PlanStatus { ACTIVE, DONE, CANCELLED }

enum class PlanStepStatus { PENDING, IN_PROGRESS, DONE, CANCELLED }

data class PlanStep(
    val id: String,
    val title: String,
    val status: PlanStepStatus = PlanStepStatus.PENDING,
    val note: String = "",
)

data class Plan(
    val conversationId: Long,
    val title: String,
    val status: PlanStatus = PlanStatus.ACTIVE,
    val steps: List<PlanStep> = emptyList(),
    val updatedAt: Long = System.currentTimeMillis(),
)

/** 按会话保存 AI 的计划；工作空间卡片和 plan 工具共用。 */
class PlanStore(context: Context) {

    private val file = File(context.applicationContext.filesDir, FILE_NAME)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _plans = MutableStateFlow(load())
    val plans: StateFlow<Map<Long, Plan>> = _plans.asStateFlow()

    private val _activeConversation = MutableStateFlow(0L)
    val activePlan: StateFlow<Plan?> = combine(_activeConversation, _plans) { id, plans ->
        plans[id]
    }.stateIn(scope, SharingStarted.Eagerly, null)

    fun setActive(conversationId: Long) {
        _activeConversation.value = conversationId
    }

    fun current(): Plan? = _plans.value[_activeConversation.value]

    fun create(title: String, stepTitles: List<String>): Plan {
        val conversationId = _activeConversation.value
        val plan = Plan(
            conversationId = conversationId,
            title = title.trim().ifBlank { "执行计划" },
            steps = stepTitles.map { title ->
                PlanStep(id = UUID.randomUUID().toString().take(8), title = title)
            },
        )
        save(plan)
        return plan
    }

    fun addStep(plan: Plan, title: String): Plan {
        val updated = plan.copy(
            steps = plan.steps + PlanStep(id = UUID.randomUUID().toString().take(8), title = title),
            updatedAt = System.currentTimeMillis(),
        )
        save(updated)
        return updated
    }

    fun updateStep(plan: Plan, stepIdOrIndex: String, status: PlanStepStatus, note: String = ""): Plan {
        val index = plan.steps.indexOfFirst { it.id == stepIdOrIndex }
            .takeIf { it >= 0 }
            ?: stepIdOrIndex.toIntOrNull()?.minus(1)
            ?: plan.steps.indexOfFirst { it.title == stepIdOrIndex }.takeIf { it >= 0 }
            ?: -1
        if (index !in plan.steps.indices) error("计划步骤不存在：$stepIdOrIndex")
        val nextSteps = plan.steps.mapIndexed { i, step ->
            if (i == index) step.copy(status = status, note = note.ifBlank { step.note }) else step
        }
        val updated = plan.copy(
            steps = nextSteps,
            status = when {
                status == PlanStepStatus.CANCELLED && plan.steps.size == 1 -> PlanStatus.CANCELLED
                nextSteps.all { it.status == PlanStepStatus.DONE || it.status == PlanStepStatus.CANCELLED } -> PlanStatus.DONE
                else -> plan.status
            },
            updatedAt = System.currentTimeMillis(),
        )
        save(updated)
        return updated
    }

    fun cancel(plan: Plan, stepIdOrIndex: String? = null): Plan {
        if (stepIdOrIndex.isNullOrBlank()) {
            val updated = plan.copy(status = PlanStatus.CANCELLED, updatedAt = System.currentTimeMillis())
            save(updated)
            return updated
        }
        return updateStep(plan, stepIdOrIndex, PlanStepStatus.CANCELLED)
    }

    fun clear() {
        val id = _activeConversation.value
        val next = _plans.value - id
        _plans.value = next
        persist(next)
    }

    private fun save(plan: Plan) {
        val next = _plans.value + (plan.conversationId to plan)
        _plans.value = next
        persist(next)
    }

    private fun persist(plans: Map<Long, Plan>) {
        file.writeText(
            buildJsonObject {
                put("plans", buildJsonArray {
                    plans.values.forEach { plan ->
                        addJsonObject {
                            put("conversationId", plan.conversationId)
                            put("title", plan.title)
                            put("status", plan.status.name)
                            put("updatedAt", plan.updatedAt)
                            putJsonArray("steps") {
                                plan.steps.forEach { step ->
                                    addJsonObject {
                                        put("id", step.id)
                                        put("title", step.title)
                                        put("status", step.status.name)
                                        put("note", step.note)
                                    }
                                }
                            }
                        }
                    }
                })
            }.toString(),
        )
    }

    private fun load(): Map<Long, Plan> = runCatching {
        val root = Json.parseToJsonElement(file.readText()).jsonObject
        (root["plans"] as? JsonArray).orEmpty().mapNotNull { element ->
            val obj = element as? JsonObject ?: return@mapNotNull null
            val id = (obj["conversationId"] as? JsonPrimitive)?.contentOrNull?.toLongOrNull()
                ?: return@mapNotNull null
            val steps = (obj["steps"] as? JsonArray).orEmpty().mapNotNull { stepElement ->
                val step = stepElement as? JsonObject ?: return@mapNotNull null
                val title = (step["title"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
                PlanStep(
                    id = (step["id"] as? JsonPrimitive)?.contentOrNull.orEmpty(),
                    title = title,
                    status = enumOr((step["status"] as? JsonPrimitive)?.contentOrNull, PlanStepStatus.PENDING),
                    note = (step["note"] as? JsonPrimitive)?.contentOrNull.orEmpty(),
                )
            }
            id to Plan(
                conversationId = id,
                title = (obj["title"] as? JsonPrimitive)?.contentOrNull.orEmpty(),
                status = enumOr((obj["status"] as? JsonPrimitive)?.contentOrNull, PlanStatus.ACTIVE),
                steps = steps,
                updatedAt = (obj["updatedAt"] as? JsonPrimitive)?.contentOrNull?.toLongOrNull() ?: 0L,
            )
        }.toMap()
    }.getOrDefault(emptyMap())

    private inline fun <reified T : Enum<T>> enumOr(value: String?, fallback: T): T =
        value?.let { runCatching { enumValueOf<T>(it) }.getOrNull() } ?: fallback

    companion object {
        private const val FILE_NAME = "plans.json"
    }
}
