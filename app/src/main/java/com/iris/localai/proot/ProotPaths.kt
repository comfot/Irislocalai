package com.iris.localai.proot

import java.io.File
import java.nio.file.Files

/**
 * PRoot 镜像的目录布局：
 * `baseDir/<imageId>/linux` 是 rootfs，`baseDir/<imageId>/tmp` 是 proot 临时目录。
 * 与工作区文件分开存放，卸载/重装镜像不会影响用户文件。
 */
class ProotPaths(private val baseDir: File) {

    init {
        baseDir.mkdirs()
    }

    fun imageDir(imageId: String): File = File(baseDir, safeName(imageId))

    fun rootfsDir(imageId: String): File = File(imageDir(imageId), LINUX_DIR)

    fun tempDir(imageId: String): File = File(imageDir(imageId), TEMP_DIR)

    fun isInstalled(imageId: String): Boolean = isUsableRootfs(rootfsDir(imageId))

    fun rootfsShell(imageId: String): String = rootfsShell(rootfsDir(imageId))

    fun rootfsSizeBytes(imageId: String): Long = dirSizeBytes(rootfsDir(imageId))

    fun delete(imageId: String): Boolean = imageDir(imageId).deleteRecursively()

    private fun safeName(raw: String): String {
        val name = raw.trim()
        require(name.isNotEmpty() && name.matches(NAME_REGEX)) { "镜像 id 不合法：$raw" }
        return name
    }

    companion object {
        /** Rootfs 内工作区文件区的挂载点。 */
        const val ROOTFS_WORKSPACE_DIR = "/workspace"

        private val NAME_REGEX = Regex("[A-Za-z0-9._-]+")
        private const val LINUX_DIR = "linux"
        private const val TEMP_DIR = "tmp"

        /** 以 /bin/sh 存在为准；Alpine 的 /bin/sh 是软链，软链本身存在即可。 */
        fun isUsableRootfs(rootfsDir: File): Boolean =
            rootfsDir.isDirectory && hasRootfsEntry(rootfsDir, "bin/sh")

        /** 优先 bash，Alpine minirootfs 只有 /bin/sh。 */
        fun rootfsShell(rootfsDir: File): String =
            if (hasRootfsEntry(rootfsDir, "bin/bash")) "/bin/bash" else "/bin/sh"

        fun hasRootfsEntry(rootfsDir: File, path: String): Boolean {
            val entry = File(rootfsDir, path)
            return entry.isFile || Files.isSymbolicLink(entry.toPath())
        }

        fun dirSizeBytes(dir: File): Long {
            if (!dir.exists()) return 0L
            if (dir.isFile) return dir.length()
            var total = 0L
            val children = dir.listFiles() ?: return total
            for (child in children) {
                // 不跟随软链：rootfs 内的绝对软链在宿主机上会指向 rootfs 之外。
                if (Files.isSymbolicLink(child.toPath())) continue
                total += if (child.isDirectory) dirSizeBytes(child) else child.length()
            }
            return total
        }
    }
}
