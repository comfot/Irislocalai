package com.iris.localai.data

/** 3D 动作模型来源。两者都需在电脑/服务器上运行，App 通过接口接入。 */
enum class MotionSource(val label: String, val summary: String) {
    NONE("未启用", "只播放模型自带的动画"),
    KIMODO("Kimodo", "NVIDIA 运动扩散模型，需 PyTorch/GPU，导出 BVH"),
    HAST_SEC("HAST-SEC", "本地中文动作服务，需 Node.js，非商用研究许可"),
    CUSTOM("自定义服务", "自建 HTTP 动作接口"),
}
