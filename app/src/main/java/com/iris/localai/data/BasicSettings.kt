package com.iris.localai.data

enum class DownloadSource(val label: String) {
    AUTO("自动"),
    HUGGING_FACE("Hugging Face"),
    MIRROR("hf-mirror"),
}

enum class ToolPermission(val label: String) {
    ALLOW("允许"),
    ASK("询问"),
    DENY("拒绝"),
}

enum class WorkspaceLocation(val label: String) {
    APP_PRIVATE("应用私有目录"),
    DOWNLOADS("下载目录"),
    CUSTOM("自定义位置"),
}

enum class ContextStrategy(val label: String, val hint: String) {
    SLIDING_WINDOW("滑动窗口", "只保留最近若干轮对话"),
    TOKEN_BUDGET("Token 预算", "按上下文预算裁剪历史"),
    SUMMARY("摘要压缩", "压缩较早内容（占位，暂按预算裁剪）"),
    KV_SHIFT("KV 位移", "交由引擎丢弃最旧 KV（占位）"),
}

enum class CharacterLayout(val label: String) {
    GRID("缩略图"),
    LIST("列表"),
}

/** 视频通话的舞台形式：房间背景或相机透视网格。 */
enum class VideoCallStage(val label: String) {
    ROOM("房间背景"),
    AR_GRID("AR 网格"),
}
