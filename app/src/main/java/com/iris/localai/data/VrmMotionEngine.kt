package com.iris.localai.data

enum class VrmMotionEngine(val label: String, val summary: String) {
    HAST_SEC("HAST-SEC", "使用内置 HAST-SEC 骨骼轨迹，适合快速待机与舞蹈动作"),
    VRMA("VRMA", "播放本地 .vrma 文件，保留动作内的人形骨骼与表情轨道"),
}
