package com.iris.localai.agent.tools

import com.iris.localai.agent.Tool
import com.iris.localai.permission.DeviceActionExecutor
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
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
    const val UI = "device_ui"
    const val TAP_TEXT = "device_tap_text"
    const val SCROLL = "device_scroll"
    const val WAIT_TEXT = "device_wait_text"
    const val RECENTS = "device_recents"
    const val NOTIFICATIONS = "device_notifications"
    const val LONG_PRESS = "device_long_press"
    const val ENTER = "device_enter"

    val all = setOf(
        OPEN_APP, TAP, SWIPE, BACK, HOME, INPUT_TEXT, SCREENSHOT,
        UI, TAP_TEXT, SCROLL, WAIT_TEXT, RECENTS, NOTIFICATIONS, LONG_PRESS, ENTER,
    )
}

class DeviceOpenAppTool(private val executor: DeviceActionExecutor) : Tool {
    override val name = DeviceToolNames.OPEN_APP
    override val description = "打开指定应用。参数 package_name 可填包名或应用名称，例如 com.android.settings 或“设置”“微信”。"

    override suspend fun execute(arguments: JsonObject): String =
        executor.openApp(arguments.requireString("package_name"))
}

class DeviceTapTool(private val executor: DeviceActionExecutor) : Tool {
    override val name = DeviceToolNames.TAP
    override val description = "点击屏幕。参数 x、y 为像素坐标；normalized=true 时使用 0..1 相对坐标。建议优先使用 device_tap_text 按文字定位。"

    override suspend fun execute(arguments: JsonObject): String {
        val x = arguments.requireFloat("x")
        val y = arguments.requireFloat("y")
        return if (arguments.optionalBoolean("normalized", false)) {
            executor.tapNormalized(x, y)
        } else {
            executor.tap(x.toInt(), y.toInt())
        }
    }
}

class DeviceSwipeTool(private val executor: DeviceActionExecutor) : Tool {
    override val name = DeviceToolNames.SWIPE
    override val description = "在屏幕上滑动。参数 x1、y1、x2、y2（像素，或 normalized=true 时 0..1 相对坐标），可选 duration_ms（默认 300）。"

    override suspend fun execute(arguments: JsonObject): String {
        val x1 = arguments.requireFloat("x1")
        val y1 = arguments.requireFloat("y1")
        val x2 = arguments.requireFloat("x2")
        val y2 = arguments.requireFloat("y2")
        val duration = arguments.optionalInt("duration_ms", 300)
        return if (arguments.optionalBoolean("normalized", false)) {
            executor.swipeNormalized(x1, y1, x2, y2, duration)
        } else {
            executor.swipe(x1.toInt(), y1.toInt(), x2.toInt(), y2.toInt(), duration)
        }
    }
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
    override val description =
        "截取当前屏幕（主屏或虚拟屏）并把 PNG 保存到应用私有 screenshots 目录，返回文件路径。" +
            "注意：只返回路径，不包含图片内容；需要读界面文字请用 device_ui。"

    override suspend fun execute(arguments: JsonObject): String = executor.screenshot()
}

class DeviceUiTool(private val executor: DeviceActionExecutor) : Tool {
    override val name = DeviceToolNames.UI
    override val description =
        "读取当前界面的控件树（主屏或当前虚拟屏），返回 mode=ui 的 nodes（文字/描述/坐标/可点击/可滚动）；" +
            "虚拟屏窗口无法被无障碍读取时自动截图 OCR，返回 mode=ocr 的 lines（text/cx/cy）。先观察再操作；参数 max_nodes 可选（默认 200）。"

    override suspend fun execute(arguments: JsonObject): String =
        executor.uiSnapshot(arguments.optionalInt("max_nodes", 200))
}

class DeviceTapTextTool(private val executor: DeviceActionExecutor) : Tool {
    override val name = DeviceToolNames.TAP_TEXT
    override val description =
        "按控件文字或内容描述点击（主屏或当前虚拟屏）；优先无障碍 ACTION_CLICK，虚拟屏失败时用节点坐标注入，" +
            "仍找不到则截图 OCR 匹配文字后点击。参数 text；可选 match_index（默认 0）。"

    override suspend fun execute(arguments: JsonObject): String = executor.tapText(
        arguments.requireString("text"),
        arguments.optionalInt("match_index", 0),
    )
}

class DeviceScrollTool(private val executor: DeviceActionExecutor) : Tool {
    override val name = DeviceToolNames.SCROLL
    override val description =
        "滚动当前界面（主屏或当前虚拟屏）。参数 direction：down（查看后续内容）/ up / left / right。用于浏览列表或把目标滚动到屏幕内。"

    override suspend fun execute(arguments: JsonObject): String =
        executor.scroll(arguments.requireString("direction"))
}

class DeviceWaitTextTool(private val executor: DeviceActionExecutor) : Tool {
    override val name = DeviceToolNames.WAIT_TEXT
    override val description =
        "等待界面出现指定文字（无障碍或 OCR），用于操作后校验。参数 text；可选 timeout_ms（默认 5000，最多 15000）。"

    override suspend fun execute(arguments: JsonObject): String = executor.waitForText(
        arguments.requireString("text"),
        arguments.optionalInt("timeout_ms", 5000),
    )
}

class DeviceRecentsTool(private val executor: DeviceActionExecutor) : Tool {
    override val name = DeviceToolNames.RECENTS
    override val description = "打开最近任务（多任务）界面。"

    override suspend fun execute(arguments: JsonObject): String = executor.recents()
}

class DeviceNotificationsTool(private val executor: DeviceActionExecutor) : Tool {
    override val name = DeviceToolNames.NOTIFICATIONS
    override val description = "下拉打开通知栏。"

    override suspend fun execute(arguments: JsonObject): String = executor.notifications()
}

class DeviceLongPressTool(private val executor: DeviceActionExecutor) : Tool {
    override val name = DeviceToolNames.LONG_PRESS
    override val description =
        "长按屏幕。参数 x、y 为像素坐标；normalized=true 时使用 0..1 相对坐标。用于长按图标、文字或列表项。"

    override suspend fun execute(arguments: JsonObject): String {
        val x = arguments.requireFloat("x")
        val y = arguments.requireFloat("y")
        return if (arguments.optionalBoolean("normalized", false)) {
            executor.longPressNormalized(x, y)
        } else {
            executor.longPress(x.toInt(), y.toInt())
        }
    }
}

class DeviceEnterTool(private val executor: DeviceActionExecutor) : Tool {
    override val name = DeviceToolNames.ENTER
    override val description = "在当前输入框按下回车/搜索键，用于提交搜索或发送输入。"

    override suspend fun execute(arguments: JsonObject): String = executor.enter()
}

private fun JsonObject.requireString(key: String): String =
    (this[key]?.jsonPrimitive?.contentOrNull)?.takeIf { it.isNotBlank() }
        ?: error("缺少参数：$key")

private fun JsonObject.requireInt(key: String): Int =
    this[key]?.jsonPrimitive?.intOrNull ?: error("缺少整数参数：$key")

private fun JsonObject.requireFloat(key: String): Float =
    this[key]?.jsonPrimitive?.contentOrNull?.toFloatOrNull() ?: error("缺少数字参数：$key")

private fun JsonObject.optionalInt(key: String, fallback: Int): Int =
    this[key]?.jsonPrimitive?.intOrNull ?: fallback

private fun JsonObject.optionalBoolean(key: String, fallback: Boolean): Boolean =
    this[key]?.jsonPrimitive?.booleanOrNull ?: fallback
