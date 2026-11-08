package com.iris.localai.agent.tools

import com.iris.localai.agent.Tool
import com.iris.localai.permission.DeviceActionExecutor
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

object DeviceToolNames {
    const val OPEN_APP = "device_open_app"
    const val TAP = "device_tap"
    const val SWIPE = "device_swipe"
    const val BACK = "device_back"
    const val HOME = "device_home"
    const val INPUT_TEXT = "device_input_text"
    const val SCREENSHOT = "device_screenshot"

    val all = setOf(OPEN_APP, TAP, SWIPE, BACK, HOME, INPUT_TEXT, SCREENSHOT)
}

class DeviceOpenAppTool(private val executor: DeviceActionExecutor) : Tool {
    override val name = DeviceToolNames.OPEN_APP
    override val description = "打开指定应用。参数 package_name 可填包名或应用名称，例如 com.android.settings 或“设置”“微信”。"

    override suspend fun execute(arguments: JsonObject): String =
        executor.openApp(arguments.requireString("package_name"))
}

class DeviceTapTool(private val executor: DeviceActionExecutor) : Tool {
    override val name = DeviceToolNames.TAP
    override val description = "点击屏幕坐标。参数 x、y 为像素坐标，需先获取截图或无障碍信息确认位置。"

    override suspend fun execute(arguments: JsonObject): String =
        executor.tap(arguments.requireInt("x"), arguments.requireInt("y"))
}

class DeviceSwipeTool(private val executor: DeviceActionExecutor) : Tool {
    override val name = DeviceToolNames.SWIPE
    override val description = "在屏幕上滑动。参数 x1、y1、x2、y2，可选 duration_ms（默认 300）。"

    override suspend fun execute(arguments: JsonObject): String = executor.swipe(
        arguments.requireInt("x1"),
        arguments.requireInt("y1"),
        arguments.requireInt("x2"),
        arguments.requireInt("y2"),
        arguments.optionalInt("duration_ms", 300),
    )
}

class DeviceBackTool(private val executor: DeviceActionExecutor) : Tool {
    override val name = DeviceToolNames.BACK
    override val description = "执行系统返回操作。"

    override suspend fun execute(arguments: JsonObject): String = executor.back()
}

class DeviceHomeTool(private val executor: DeviceActionExecutor) : Tool {
    override val name = DeviceToolNames.HOME
    override val description = "回到系统桌面。"

    override suspend fun execute(arguments: JsonObject): String = executor.home()
}

class DeviceInputTextTool(private val executor: DeviceActionExecutor) : Tool {
    override val name = DeviceToolNames.INPUT_TEXT
    override val description = "向当前焦点输入文本；需要无障碍服务或 Shizuku。"

    override suspend fun execute(arguments: JsonObject): String =
        executor.inputText(arguments.requireString("text"))
}

class DeviceScreenshotTool(private val executor: DeviceActionExecutor) : Tool {
    override val name = DeviceToolNames.SCREENSHOT
    override val description = "截取当前屏幕并把 PNG 保存到应用私有 screenshots 目录，返回文件路径。"

    override suspend fun execute(arguments: JsonObject): String = executor.screenshot()
}

private fun JsonObject.requireString(key: String): String =
    (this[key]?.jsonPrimitive?.contentOrNull)?.takeIf { it.isNotBlank() }
        ?: error("缺少参数：$key")

private fun JsonObject.requireInt(key: String): Int =
    this[key]?.jsonPrimitive?.intOrNull ?: error("缺少整数参数：$key")

private fun JsonObject.optionalInt(key: String, fallback: Int): Int =
    this[key]?.jsonPrimitive?.intOrNull ?: fallback
