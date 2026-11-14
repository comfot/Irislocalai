package com.iris.localai.plugin

/**
 * Operit ToolPkg 的轻量源码转译：
 * - NativeInterface.callTool / callToolAsync / toolCall 改走宿主兼容桥；
 * - 保留原始源码其余部分，避免大范围重写带来不可控行为。
 *
 * UI Compose DSL 目前没有原生渲染器，不在本转译层处理。
 */
internal object OperitTranspiler {

    data class Result(
        val source: String,
        val rewrites: Int,
        val warnings: List<String>,
    )

    private val callToolPattern = Regex("(?<![\\w.])toolCall\\s*\\(")
    private val nativeCallPattern = Regex("NativeInterface\\s*\\.\\s*callTool\\s*\\(")
    private val nativeAsyncPattern = Regex("NativeInterface\\s*\\.\\s*callToolAsync\\s*\\(")
    private val nativeStreamingPattern = Regex("NativeInterface\\s*\\.\\s*callToolAsyncStreaming\\s*\\(")

    fun transpile(source: String): Result {
        var rewrites = 0
        val warnings = ArrayList<String>()

        var output = nativeCallPattern.replace(source) {
            rewrites++
            "__irisCompat.callTool("
        }
        output = nativeAsyncPattern.replace(output) {
            rewrites++
            "__irisCompat.callToolAsync("
        }
        output = nativeStreamingPattern.replace(output) {
            rewrites++
            "__irisCompat.callToolAsyncStreaming("
        }
        output = callToolPattern.replace(output) { match ->
            val before = output.substring(0, match.range.first)
            val lineStart = before.lastIndexOf('\n') + 1
            val head = before.substring(lineStart).trim()
            // 跳过插件自己声明的同名函数，避免把 function toolCall( 改成非法标识符。
            if (head.endsWith("function") || head.endsWith("function *")) {
                match.value
            } else {
                rewrites++
                "__irisCompat.callTool("
            }
        }

        if ("ctx.UI" in output || "registerToolboxUiModule" in output) {
            warnings += "包含 Compose DSL UI，当前只保留注册信息，无法原生渲染"
        }
        if (Regex("\\b(Java|Kotlin)\\s*\\.\\s*(use|importClass|newInstance|callStatic|type)\\s*\\(").containsMatchIn(output)) {
            warnings += "使用了 Java/Kotlin 桥，当前仅支持白名单类"
        }
        if ("android." in output) {
            warnings += "包含 Android API 调用，WebView 沙箱不支持"
        }
        return Result(output, rewrites, warnings)
    }
}
