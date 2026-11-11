package com.iris.localai.agent.tools

import com.iris.localai.agent.Tool
import com.iris.localai.browser.BrowserController
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

object BrowserToolNames {
    const val OPEN = "browser_open"
    const val READ = "browser_read"
    const val SNAPSHOT = "browser_snapshot"
    const val CLICK = "browser_click"
    const val INPUT = "browser_input"
    const val SCROLL = "browser_scroll"
    const val WAIT = "browser_wait"
    const val NAVIGATE = "browser_navigate"
    const val BACK = "browser_back"
    const val SCREENSHOT = "browser_screenshot"

    val all = setOf(OPEN, READ, SNAPSHOT, CLICK, INPUT, SCROLL, WAIT, NAVIGATE, BACK, SCREENSHOT)
}

class BrowserOpenTool(private val browser: BrowserController) : Tool {
    override val name = BrowserToolNames.OPEN
    override val description = "在 AI 浏览器中打开网页。参数 url；未写协议时自动补 https 或搜索。"

    override suspend fun execute(arguments: JsonObject): String =
        "已打开：${browser.openAndWait(arguments.requireText("url"))}"
}

class BrowserReadTool(private val browser: BrowserController) : Tool {
    override val name = BrowserToolNames.READ
    override val description = "读取 AI 浏览器当前页面的标题、URL、正文（最多 6000 字）和可点击文字列表。"

    override suspend fun execute(arguments: JsonObject): String = browser.read()
}

class BrowserSnapshotTool(private val browser: BrowserController) : Tool {
    override val name = BrowserToolNames.SNAPSHOT
    override val description =
        "获取页面快照：标题/URL、正文（4000 字）和带索引 [n] 的可点击元素。优先用它，再用 browser_click index=n 或 browser_input index=n。"

    override suspend fun execute(arguments: JsonObject): String = browser.snapshot()
}

class BrowserClickTool(private val browser: BrowserController) : Tool {
    override val name = BrowserToolNames.CLICK
    override val description = "点击页面元素。参数 index（来自 browser_snapshot）或 text（按可见文字）。"

    override suspend fun execute(arguments: JsonObject): String {
        val index = arguments["index"]?.jsonPrimitive?.intOrNull
        return if (index != null) {
            browser.clickIndex(index)
        } else {
            browser.click(arguments.requireText("text"))
        }
    }
}

class BrowserInputTool(private val browser: BrowserController) : Tool {
    override val name = BrowserToolNames.INPUT
    override val description =
        "向输入框填写文字。参数 index（来自 snapshot）或 target（placeholder/aria-label/name 片段），text；可选 submit=true。"

    override suspend fun execute(arguments: JsonObject): String {
        val text = arguments.requireText("text")
        val submit = arguments["submit"]?.jsonPrimitive?.booleanOrNull ?: false
        val index = arguments["index"]?.jsonPrimitive?.intOrNull
        return if (index != null) {
            browser.typeIndex(index, text, submit)
        } else {
            browser.input(arguments.requireText("target"), text)
        }
    }
}

class BrowserScrollTool(private val browser: BrowserController) : Tool {
    override val name = BrowserToolNames.SCROLL
    override val description = "滚动页面。参数 dy（正数向下，负数向上），单位像素。"

    override suspend fun execute(arguments: JsonObject): String =
        browser.scroll(arguments["dy"]?.jsonPrimitive?.intOrNull ?: 600)
}

class BrowserWaitTool(private val browser: BrowserController) : Tool {
    override val name = BrowserToolNames.WAIT
    override val description = "等待页面出现指定文字。参数 text；可选 timeout_ms（默认 8000，最多 30000）。"

    override suspend fun execute(arguments: JsonObject): String = browser.waitForText(
        arguments.requireText("text"),
        arguments["timeout_ms"]?.jsonPrimitive?.intOrNull ?: 8_000,
    )
}

class BrowserNavigateTool(private val browser: BrowserController) : Tool {
    override val name = BrowserToolNames.NAVIGATE
    override val description = "浏览器导航。参数 action：back / forward / reload。"

    override suspend fun execute(arguments: JsonObject): String =
        browser.navigate(arguments.requireText("action"))
}

class BrowserBackTool(private val browser: BrowserController) : Tool {
    override val name = BrowserToolNames.BACK
    override val description = "AI 浏览器返回上一页。"

    override suspend fun execute(arguments: JsonObject): String = browser.back()
}

class BrowserScreenshotTool(private val browser: BrowserController) : Tool {
    override val name = BrowserToolNames.SCREENSHOT
    override val description = "截取 AI 浏览器画面并保存为 PNG，返回文件路径（不包含图片内容）。"

    override suspend fun execute(arguments: JsonObject): String = browser.screenshot()
}

private fun JsonObject.requireText(key: String): String =
    (this[key]?.jsonPrimitive?.contentOrNull)?.takeIf { it.isNotBlank() }
        ?: error("缺少参数：$key")
