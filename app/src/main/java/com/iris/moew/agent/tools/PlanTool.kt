package com.iris.moew.agent.tools

import com.iris.moew.agent.Plan
import com.iris.moew.agent.PlanStatus
import com.iris.moew.agent.PlanStepStatus
import com.iris.moew.agent.PlanStore
import com.iris.moew.agent.Tool
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** AI 的任务计划：列步骤、更新进度、取消。工作空间计划卡与本工具共用状态。 */
class PlanTool(private val plans: PlanStore) : Tool {

    override val name: String = "plan"

    override val description: String =
        "管理当前会话的执行计划。多步骤任务先用 op=create 列出计划；开始/完成/取消步骤用 op=update；" +
            "用户取消或放弃任务时用 op=cancel；op=list 查看当前计划；op=clear 删除计划；op=add 追加步骤。"

    override suspend fun execute(arguments: JsonObject): String = runCatching {
        val op = arguments.text("op")?.lowercase() ?: "list"
        when (op) {
            "create", "plan" -> {
                val title = arguments.text("title") ?: "执行计划"
                val steps = parseSteps(arguments)
                if (steps.isEmpty()) return "计划至少需要一个步骤。"
                plans.create(title, steps).let(::render)
            }
            "list", "status" -> plans.current()?.let(::render) ?: "当前没有计划。"
            "add" -> {
                val plan = requirePlan()
                val title = arguments.text("title") ?: arguments.text("step") ?: error("缺少步骤标题")
                plans.addStep(plan, title).let(::render)
            }
            "update" -> {
                val plan = requirePlan()
                val target = arguments.text("step") ?: arguments.text("step_id") ?: error("缺少 step")
                val status = when (arguments.text("status")?.lowercase()) {
                    "pending", "todo" -> PlanStepStatus.PENDING
                    "in_progress", "running", "doing" -> PlanStepStatus.IN_PROGRESS
                    "done", "completed", "complete", "ok" -> PlanStepStatus.DONE
                    "cancelled", "canceled", "cancel" -> PlanStepStatus.CANCELLED
                    else -> error("status 只能是 pending / in_progress / done / cancelled")
                }
                plans.updateStep(plan, target, status, arguments.text("note").orEmpty()).let(::render)
            }
            "cancel" -> {
                val plan = requirePlan()
                val target = arguments.text("step") ?: arguments.text("step_id")
                plans.cancel(plan, target).let(::render)
            }
            "clear", "delete" -> {
                plans.clear()
                "计划已清除。"
            }
            else -> error("不支持的 op：$op")
        }
    }.getOrElse { error ->
        "计划操作失败：${error.message ?: error.javaClass.simpleName}"
    }

    private fun requirePlan(): Plan = plans.current() ?: error("当前没有计划，请先 op=create")

    private fun parseSteps(arguments: JsonObject): List<String> {
        val element = arguments["steps"] ?: arguments["items"] ?: return emptyList()
        val list = element as? JsonArray ?: return listOfNotNull((element as? JsonPrimitive)?.contentOrNull)
        return list.mapNotNull { item ->
            when (item) {
                is JsonPrimitive -> item.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
                is JsonObject -> (item["title"] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
                else -> null
            }
        }
    }

    private fun render(plan: Plan): String = buildString {
        appendLine("计划：${plan.title}（${statusLabel(plan.status)}）")
        plan.steps.forEachIndexed { index, step ->
            appendLine("${index + 1}. [${stepStatusLabel(step.status)}] ${step.title}" +
                if (step.note.isNotBlank()) " — ${step.note}" else "")
        }
        appendLine("使用 op=update 更新进度，op=cancel 取消计划或步骤。")
    }.trim()

    private fun statusLabel(status: PlanStatus): String = when (status) {
        PlanStatus.ACTIVE -> "进行中"
        PlanStatus.DONE -> "已完成"
        PlanStatus.CANCELLED -> "已取消"
    }

    private fun stepStatusLabel(status: PlanStepStatus): String = when (status) {
        PlanStepStatus.PENDING -> "待办"
        PlanStepStatus.IN_PROGRESS -> "进行中"
        PlanStepStatus.DONE -> "完成"
        PlanStepStatus.CANCELLED -> "取消"
    }

    private fun JsonObject.text(key: String): String? =
        (this[key] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
}
