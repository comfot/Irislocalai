package com.iris.localai.code

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class CodeRuntime(val label: String) {
    APP_SHELL("应用 Shell"),
    PROOT("PRoot 容器"),
    SSH("远程 SSH"),
}

enum class ToolAuthorization(val label: String) {
    ALLOW("允许"),
    ASK("询问"),
    DENY("拒绝"),
}

data class CodeSettings(
    val enabled: Boolean = false,
    val runtime: CodeRuntime = CodeRuntime.APP_SHELL,
    val imageId: String = "alpine-3.21",
    val imagePath: String = "",
    val imageUrl: String = "",
    val timeoutSeconds: Int = 60,
    val outputLimit: Int = 20_000,
    val networkEnabled: Boolean = true,
    val shellAuthorization: ToolAuthorization = ToolAuthorization.ASK,
    val fileAuthorization: ToolAuthorization = ToolAuthorization.ASK,
    val networkAuthorization: ToolAuthorization = ToolAuthorization.ASK,
    val codeAuthorization: ToolAuthorization = ToolAuthorization.ASK,
    val allowedPrefixes: String = "",
    val deniedPrefixes: String = "rm -rf /,su,sudo",
    val sshHost: String = "",
    val sshPort: Int = 22,
    val sshUser: String = "",
    val sshKeyPath: String = "",
)

data class CodeImage(
    val id: String,
    val name: String,
    val version: String,
    val approximateMb: Int,
    val note: String,
    val rootfsUrl: String = "",
)

object CodeImageCatalog {
    val images = listOf(
        CodeImage(
            id = "alpine-3.21",
            name = "Alpine Linux",
            version = "3.21",
            approximateMb = 8,
            note = "轻量，适合日常命令与脚本",
            rootfsUrl = "https://dl-cdn.alpinelinux.org/alpine/v3.21/releases/{arch}/" +
                "alpine-minirootfs-3.21.9-{arch}.tar.gz",
        ),
        CodeImage("ubuntu-24.04", "Ubuntu", "24.04", 80, "完整用户态，构建工具较多，需填写下载地址或本地 tar.gz"),
        CodeImage("debian-12", "Debian", "12", 70, "稳定基础镜像，需填写下载地址或本地 tar.gz"),
        CodeImage("custom", "自定义镜像", "本地", 0, "使用本地 rootfs 压缩包或下载地址"),
    )

    fun of(id: String?): CodeImage = images.firstOrNull { it.id == id } ?: images.first()

    /** Alpine 的 aarch64/x86_64 命名与 Android ABI 不同，这里统一转换。 */
    fun resolveUrl(image: CodeImage, abi: String): String =
        image.rootfsUrl.replace("{arch}", if (abi == "x86_64") "x86_64" else "aarch64")
}

class CodeSettingsStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private val _settings = MutableStateFlow(read())
    val settings: StateFlow<CodeSettings> = _settings.asStateFlow()

    val current: CodeSettings get() = _settings.value

    fun update(transform: (CodeSettings) -> CodeSettings) {
        val next = transform(current)
        _settings.value = next
        prefs.edit()
            .putBoolean(KEY_ENABLED, next.enabled)
            .putString(KEY_RUNTIME, next.runtime.name)
            .putString(KEY_IMAGE_ID, next.imageId)
            .putString(KEY_IMAGE_PATH, next.imagePath)
            .putString(KEY_IMAGE_URL, next.imageUrl)
            .putInt(KEY_TIMEOUT, next.timeoutSeconds)
            .putInt(KEY_OUTPUT, next.outputLimit)
            .putBoolean(KEY_NETWORK, next.networkEnabled)
            .putString(KEY_SHELL_AUTH, next.shellAuthorization.name)
            .putString(KEY_FILE_AUTH, next.fileAuthorization.name)
            .putString(KEY_NETWORK_AUTH, next.networkAuthorization.name)
            .putString(KEY_CODE_AUTH, next.codeAuthorization.name)
            .putString(KEY_ALLOW_PREFIX, next.allowedPrefixes)
            .putString(KEY_DENY_PREFIX, next.deniedPrefixes)
            .putString(KEY_SSH_HOST, next.sshHost)
            .putInt(KEY_SSH_PORT, next.sshPort)
            .putString(KEY_SSH_USER, next.sshUser)
            .putString(KEY_SSH_KEY, next.sshKeyPath)
            .apply()
    }

    private fun read(): CodeSettings = CodeSettings(
        enabled = prefs.getBoolean(KEY_ENABLED, false),
        runtime = enumOf(prefs.getString(KEY_RUNTIME, null), CodeRuntime.APP_SHELL),
        imageId = prefs.getString(KEY_IMAGE_ID, "alpine-3.21") ?: "alpine-3.21",
        imagePath = prefs.getString(KEY_IMAGE_PATH, "") ?: "",
        imageUrl = prefs.getString(KEY_IMAGE_URL, "") ?: "",
        timeoutSeconds = prefs.getInt(KEY_TIMEOUT, 60),
        outputLimit = prefs.getInt(KEY_OUTPUT, 20_000),
        networkEnabled = prefs.getBoolean(KEY_NETWORK, true),
        shellAuthorization = enumOf(
            prefs.getString(KEY_SHELL_AUTH, null),
            ToolAuthorization.ASK,
        ),
        fileAuthorization = enumOf(
            prefs.getString(KEY_FILE_AUTH, null),
            ToolAuthorization.ASK,
        ),
        networkAuthorization = enumOf(
            prefs.getString(KEY_NETWORK_AUTH, null),
            ToolAuthorization.ASK,
        ),
        codeAuthorization = enumOf(
            prefs.getString(KEY_CODE_AUTH, null),
            ToolAuthorization.ASK,
        ),
        allowedPrefixes = prefs.getString(KEY_ALLOW_PREFIX, "") ?: "",
        deniedPrefixes = prefs.getString(KEY_DENY_PREFIX, "rm -rf /,su,sudo") ?: "",
        sshHost = prefs.getString(KEY_SSH_HOST, "") ?: "",
        sshPort = prefs.getInt(KEY_SSH_PORT, 22),
        sshUser = prefs.getString(KEY_SSH_USER, "") ?: "",
        sshKeyPath = prefs.getString(KEY_SSH_KEY, "") ?: "",
    )

    private inline fun <reified T : Enum<T>> enumOf(raw: String?, fallback: T): T =
        enumValues<T>().firstOrNull { it.name == raw } ?: fallback

    private companion object {
        const val PREFS = "iris_code"
        const val KEY_ENABLED = "enabled"
        const val KEY_RUNTIME = "runtime"
        const val KEY_IMAGE_ID = "image_id"
        const val KEY_IMAGE_PATH = "image_path"
        const val KEY_IMAGE_URL = "image_url"
        const val KEY_TIMEOUT = "timeout"
        const val KEY_OUTPUT = "output_limit"
        const val KEY_NETWORK = "network"
        const val KEY_SHELL_AUTH = "shell_auth"
        const val KEY_FILE_AUTH = "file_auth"
        const val KEY_NETWORK_AUTH = "network_auth"
        const val KEY_CODE_AUTH = "code_auth"
        const val KEY_ALLOW_PREFIX = "allow_prefix"
        const val KEY_DENY_PREFIX = "deny_prefix"
        const val KEY_SSH_HOST = "ssh_host"
        const val KEY_SSH_PORT = "ssh_port"
        const val KEY_SSH_USER = "ssh_user"
        const val KEY_SSH_KEY = "ssh_key"
    }
}
