package com.iris.localai.agent.tools

import com.iris.localai.agent.Tool
import com.iris.localai.code.TerminalSessions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull

/**
 * 常驻终端：把长任务放进由应用单例持有的会话，跨工具调用继续运行。
 * shell 工具每次调用都会结束并清理容器内子进程，不能用来跑后台任务。
 */
class TerminalTool(private val sessions: TerminalSessions) : Tool {

    override val name: String = "terminal"

    override val description: String =
        "管理跨工具调用存活的常驻终端会话。耗时任务、构建、转写、服务进程用 action=start，" +
            "不要在 shell 里用 nohup 或结尾 &。参数：" +
            "{\"action\":\"start\",\"command\":\"python3 long_task.py\",\"title\":\"转写\"}；" +
            "{\"action\":\"read\",\"session_id\":\"term-1\"}；{\"action\":\"list\"}；" +
            "{\"action\":\"stop\",\"session_id\":\"term-1\"}。"

    override suspend fun execute(arguments: JsonObject): String {
        val command = arguments.text("command").orEmpty()
        val sessionId = arguments.text("session_id") ?: arguments.text("sessionId")
        val action = arguments.text("action")?.lowercase()
            ?: if (command.isNotBlank()) "start" else "list"
        return withContext(Dispatchers.IO) {
            when (action) {
                "start", "run", "background" -> {
                    if (command.isBlank()) error("缺少 command 参数")
                    sessions.start(command, arguments.text("title"))
                }
                "read", "output", "log" -> sessions.read(sessionId)
                "list", "status" -> sessions.read(null)
                "stop", "close", "kill" -> {
                    if (sessionId.isNullOrBlank()) error("缺少 session_id 参数")
                    sessions.stop(sessionId)
                }
                else -> "不支持的 action：$action（可用 start/read/list/stop）"
            }
        }
    }

    private fun JsonObject.text(key: String): String? =
        (this[key] as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull?.trim()
            ?.takeIf { it.isNotEmpty() }
}
