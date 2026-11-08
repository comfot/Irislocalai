package com.iris.localai.data

enum class OperationSurfaceMode(
    val label: String,
    val description: String,
) {
    NORMAL("普通屏幕", "直接使用当前主屏幕，兼容性最好"),
    ARIES_VIRTUAL_DISPLAY("虚拟屏", "Aries 风格副屏：应用在后台 display 运行，支持定向点击与截图"),
    DEVICE_FREEFORM("本机小窗", "用系统 freeform 小窗口启动应用，失败时回退普通启动"),
}
