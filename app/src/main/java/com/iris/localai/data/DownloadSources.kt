package com.iris.localai.data

/**
 * 应用内所有下载与抓取入口，以及可选的备用源。
 * 备用源是否参与回退由 设置 › 网络 › 启用备用下载源 决定。
 */
object DownloadSources {

    const val HUGGING_FACE = "https://huggingface.co"
    const val HUGGING_FACE_MIRROR = "https://hf-mirror.com"
    const val GITHUB_RAW = "https://raw.githubusercontent.com"
    const val GITHUB_CDN = "https://cdn.jsdelivr.net"
    const val GITHUB_API = "https://api.github.com"
    const val GITHUB = "https://github.com"

    /** 自动模式在启用备用源时才会带上镜像；显式选择镜像不受开关影响。 */
    fun hfBases(source: DownloadSource, mirrorsEnabled: Boolean): List<String> = when (source) {
        DownloadSource.AUTO ->
            if (mirrorsEnabled) listOf(HUGGING_FACE, HUGGING_FACE_MIRROR) else listOf(HUGGING_FACE)
        DownloadSource.HUGGING_FACE -> listOf(HUGGING_FACE)
        DownloadSource.MIRROR -> listOf(HUGGING_FACE_MIRROR)
    }

    fun hfHosts(mirrorsEnabled: Boolean): List<String> =
        if (mirrorsEnabled) listOf(HUGGING_FACE, HUGGING_FACE_MIRROR) else listOf(HUGGING_FACE)

    /** GitHub 单文件：raw 优先，备用 jsDelivr 与 Contents API。 */
    fun githubContentCandidates(
        repo: String,
        branch: String,
        path: String,
        mirrorsEnabled: Boolean,
    ): List<String> = buildList {
        add("$GITHUB_RAW/$repo/$branch/$path")
        if (mirrorsEnabled) add("$GITHUB_CDN/gh/$repo@$branch/$path")
        add("$GITHUB_API/repos/$repo/contents/$path")
    }

    data class Endpoint(
        val name: String,
        val url: String,
        val usage: String,
        val backup: String? = null,
    )

    data class Group(val title: String, val endpoints: List<Endpoint>)

    val groups: List<Group> = listOf(
        Group(
            title = "模型与语音",
            endpoints = listOf(
                Endpoint(
                    name = "Hugging Face",
                    url = HUGGING_FACE,
                    usage = "GGUF 模型、语音包与 HAST-SEC 动作轨迹",
                    backup = "hf-mirror.com",
                ),
                Endpoint(
                    name = "hf-mirror",
                    url = HUGGING_FACE_MIRROR,
                    usage = "Hugging Face 的国内镜像，官方源失败时自动回退",
                    backup = "huggingface.co",
                ),
                Endpoint(
                    name = "ModelScope",
                    url = "https://modelscope.cn",
                    usage = "模型搜索的备用检索与文件列表",
                ),
            ),
        ),
        Group(
            title = "GitHub 内容",
            endpoints = listOf(
                Endpoint(
                    name = "GitHub Raw",
                    url = GITHUB_RAW,
                    usage = "角色广场配置、舞台素材与技能模板",
                    backup = "cdn.jsdelivr.net",
                ),
                Endpoint(
                    name = "jsDelivr",
                    url = "$GITHUB_CDN/gh",
                    usage = "GitHub 内容 CDN 镜像，仅在启用备用源时使用",
                    backup = "raw.githubusercontent.com",
                ),
                Endpoint(
                    name = "GitHub API",
                    url = GITHUB_API,
                    usage = "素材库列表、插件市场搜索与角色广场兜底",
                ),
                Endpoint(
                    name = "GitHub 归档",
                    url = "$GITHUB/<owner>/<repo>/archive/refs/heads/<branch>.zip",
                    usage = "插件仓库 ZIP 安装",
                ),
            ),
        ),
        Group(
            title = "索引与服务",
            endpoints = listOf(
                Endpoint(
                    name = "Operit 市场",
                    url = "https://static.operit.app/market/v2",
                    usage = "插件市场索引与资产清单",
                ),
                Endpoint(
                    name = "JitPack",
                    url = "https://jitpack.io",
                    usage = "sherpa-onnx AAR，仅在构建时由 Gradle 下载",
                ),
            ),
        ),
    )
}
