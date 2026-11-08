package com.iris.localai.proot

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Files
import java.util.Locale
import java.util.zip.GZIPInputStream

sealed interface RootfsSource {
    data class Url(val url: String) : RootfsSource
    data class LocalFile(val file: File) : RootfsSource
}

sealed interface RootfsProgress {
    data class Downloading(val bytes: Long, val totalBytes: Long?) : RootfsProgress
    data class Extracting(val entries: Int, val currentEntry: String) : RootfsProgress
    data object Installed : RootfsProgress
}

/**
 * 下载或读取 tar/tar.gz 格式的 rootfs，解压到 `ProotPaths.rootfsDir`。
 * 解压在临时目录完成并通过 /bin/sh 校验后才替换旧镜像，失败不会破坏已安装的 rootfs。
 */
class RootfsInstaller(
    private val paths: ProotPaths,
    private val patcher: RootfsPatcher = RootfsPatcher(),
) {

    fun install(
        imageId: String,
        source: RootfsSource,
        onProgress: (RootfsProgress) -> Unit = {},
    ): File {
        val tempDir = paths.tempDir(imageId).apply { mkdirs() }
        val rootfs = paths.rootfsDir(imageId)
        val staging = File(tempDir, STAGING_DIR).apply { deleteRecursively(); mkdirs() }
        val archive = File(tempDir, "rootfs.tar.gz")

        try {
            when (source) {
                is RootfsSource.Url -> download(source.url, archive, onProgress)
                is RootfsSource.LocalFile -> {
                    require(source.file.isFile) { "本地 rootfs 文件不存在：${source.file.path}" }
                    require(source.file.length() > 0) { "本地 rootfs 文件为空" }
                    source.file.copyTo(archive, overwrite = true)
                }
            }
            require(archive.length() > 0) { "rootfs 压缩包为空" }
            extractTar(archive, staging, onProgress)
            require(ProotPaths.isUsableRootfs(staging)) {
                "rootfs 中缺少 /bin/sh，可能不是有效的 Linux rootfs"
            }

            rootfs.deleteRecursively()
            rootfs.parentFile?.mkdirs()
            require(staging.renameTo(rootfs)) { "无法把 rootfs 移动到 ${rootfs.absolutePath}" }
            patcher.patch(rootfs)
            onProgress(RootfsProgress.Installed)
            return rootfs
        } finally {
            archive.delete()
            staging.deleteRecursively()
        }
    }

    private fun download(
        rawUrl: String,
        target: File,
        onProgress: (RootfsProgress) -> Unit,
    ) {
        val url = runCatching { URL(rawUrl) }.getOrNull()
            ?: throw IllegalArgumentException("下载地址格式不正确")
        require(url.protocol.lowercase() in setOf("http", "https")) { "仅支持 http/https 下载地址" }

        val connection = (url.openConnection() as HttpURLConnection).apply {
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "IRIS-LocalAI/0.1")
        }
        try {
            val code = connection.responseCode
            require(code in 200..299) { "rootfs 下载失败：HTTP $code" }
            val totalBytes = connection.contentLengthLong.takeIf { it > 0 }
            require(totalBytes == null || totalBytes <= MAX_ARCHIVE_BYTES) {
                "rootfs 压缩包超过 ${MAX_ARCHIVE_BYTES / (1024 * 1024)} MB 上限"
            }
            target.parentFile?.mkdirs()
            connection.inputStream.use { input ->
                target.outputStream().use { output ->
                    val buffer = ByteArray(BUFFER_SIZE)
                    var bytesRead = 0L
                    var lastReport = 0L
                    while (true) {
                        checkInterrupted()
                        val read = input.read(buffer)
                        if (read < 0) break
                        bytesRead += read
                        require(bytesRead <= MAX_ARCHIVE_BYTES) {
                            "rootfs 压缩包超过 ${MAX_ARCHIVE_BYTES / (1024 * 1024)} MB 上限"
                        }
                        output.write(buffer, 0, read)
                        if (bytesRead - lastReport >= PROGRESS_STEP_BYTES || bytesRead == totalBytes) {
                            lastReport = bytesRead
                            onProgress(RootfsProgress.Downloading(bytesRead, totalBytes))
                        }
                    }
                    onProgress(RootfsProgress.Downloading(bytesRead, totalBytes))
                }
            }
        } finally {
            connection.disconnect()
        }
    }

    internal fun extractTar(
        archive: File,
        targetDir: File,
        onProgress: (RootfsProgress) -> Unit = {},
    ) {
        openTarStream(archive).use { input ->
            var entries = 0
            var pendingName: String? = null
            var pendingLinkName: String? = null
            while (true) {
                checkInterrupted()
                val rawHeader = input.readTarHeader() ?: break
                val header = rawHeader.copy(
                    name = pendingName ?: rawHeader.name,
                    linkName = pendingLinkName ?: rawHeader.linkName,
                )
                pendingName = null
                pendingLinkName = null
                if (header.name.isBlank()) {
                    input.skipFully(header.size.paddedTarSize())
                    continue
                }
                if (header.type == TarEntryType.LONG_NAME) {
                    pendingName = input.readExactly(header.size)
                        .toString(Charsets.UTF_8)
                        .trimEnd('\u0000', '\n')
                    input.skipFully(header.size.paddingSize())
                    continue
                }
                if (header.type == TarEntryType.LONG_LINK) {
                    pendingLinkName = input.readExactly(header.size)
                        .toString(Charsets.UTF_8)
                        .trimEnd('\u0000', '\n')
                    input.skipFully(header.size.paddingSize())
                    continue
                }
                if (header.type == TarEntryType.PAX) {
                    val pax = parsePax(input.readExactly(header.size).toString(Charsets.UTF_8))
                    pendingName = pax["path"]
                    pendingLinkName = pax["linkpath"]
                    input.skipFully(header.size.paddingSize())
                    continue
                }

                val target = targetDir.safeResolve(header.name)
                target.parentFile?.mkdirs()
                when (header.type) {
                    TarEntryType.DIRECTORY -> target.mkdirs()
                    TarEntryType.SYMLINK -> createSymlink(targetDir, target, header.linkName)
                    TarEntryType.HARDLINK -> createHardLink(targetDir, target, header.linkName)
                    TarEntryType.FILE -> {
                        target.outputStream().use { output ->
                            input.copyExactly(output, header.size)
                        }
                        target.applyMode(header.mode)
                    }
                    TarEntryType.LONG_NAME,
                    TarEntryType.LONG_LINK,
                    TarEntryType.PAX,
                    TarEntryType.OTHER -> Unit
                }
                if (header.type != TarEntryType.FILE) {
                    input.skipFully(header.size)
                }
                input.skipFully(header.size.paddingSize())
                if (header.modTime > 0 && header.type != TarEntryType.SYMLINK) {
                    target.setLastModified(header.modTime * 1000)
                }
                entries++
                if (entries % 25 == 0) {
                    onProgress(RootfsProgress.Extracting(entries, header.name))
                }
            }
        }
    }

    private fun openTarStream(archive: File): InputStream {
        val buffered = BufferedInputStream(archive.inputStream())
        buffered.mark(4)
        val first = buffered.read()
        val second = buffered.read()
        val third = buffered.read()
        buffered.reset()
        if (first == 0x1f && second == 0x8b) return GZIPInputStream(buffered)
        if (first == 0xfd && second == 0x37 && third == 0x7a) {
            throw IllegalArgumentException("暂不支持 tar.xz 镜像，请使用 tar.gz 或 tar")
        }
        return buffered
    }

    private fun createSymlink(root: File, target: File, linkName: String) {
        if (linkName.isBlank()) return
        val linkTarget = if (File(linkName).isAbsolute) {
            File(linkName)
        } else {
            val resolved = File(target.parentFile ?: root, linkName).canonicalFile
            val rootFile = root.canonicalFile
            require(resolved.path == rootFile.path || resolved.path.startsWith(rootFile.path + File.separator)) {
                "rootfs 软链越界：${target.name}"
            }
            (target.parentFile ?: root).toPath().relativize(resolved.toPath()).toFile()
        }
        target.delete()
        Files.createSymbolicLink(target.toPath(), linkTarget.toPath())
    }

    private fun createHardLink(root: File, target: File, linkName: String) {
        if (linkName.isBlank()) return
        val source = root.safeResolve(linkName)
        if (!source.exists()) return
        target.delete()
        runCatching {
            Files.createLink(target.toPath(), source.toPath())
        }.recoverCatching { error ->
            if (error !is IOException &&
                error !is UnsupportedOperationException &&
                error !is SecurityException
            ) {
                throw error
            }
            source.copyTo(target, overwrite = true)
            target.setReadable(source.canRead(), false)
            target.setWritable(source.canWrite(), true)
            target.setExecutable(source.canExecute(), false)
        }.getOrThrow()
    }

    private fun InputStream.readTarHeader(): TarHeader? {
        val header = ByteArray(TAR_BLOCK_SIZE)
        val read = readFullyOrEnd(header)
        if (read == 0) return null
        if (read < TAR_BLOCK_SIZE) throw EOFException("rootfs 压缩包在 tar 头处意外结束")
        if (header.all { it == 0.toByte() }) return null

        val name = header.string(0, 100)
        val prefix = header.string(345, 155)
        val fullName = listOf(prefix, name)
            .filter { it.isNotBlank() }
            .joinToString("/")
        return TarHeader(
            name = normalizeTarPath(fullName),
            mode = header.octal(100, 8).toInt(),
            size = header.octal(124, 12),
            modTime = header.octal(136, 12),
            type = when (header[156].toInt().toChar()) {
                '0', '\u0000' -> TarEntryType.FILE
                '5' -> TarEntryType.DIRECTORY
                '2' -> TarEntryType.SYMLINK
                '1' -> TarEntryType.HARDLINK
                'L' -> TarEntryType.LONG_NAME
                'K' -> TarEntryType.LONG_LINK
                'x' -> TarEntryType.PAX
                else -> TarEntryType.OTHER
            },
            linkName = header.string(157, 100),
        )
    }

    private fun parsePax(text: String): Map<String, String> {
        val result = mutableMapOf<String, String>()
        var index = 0
        while (index < text.length) {
            val space = text.indexOf(' ', index)
            if (space < 0) break
            val length = text.substring(index, space).toIntOrNull() ?: break
            val end = (index + length).coerceAtMost(text.length)
            val record = text.substring(space + 1, end).trimEnd('\n')
            val equals = record.indexOf('=')
            if (equals > 0) {
                result[record.substring(0, equals)] = record.substring(equals + 1)
            }
            index += length
        }
        return result
    }

    private fun checkInterrupted() {
        if (Thread.currentThread().isInterrupted) {
            throw InterruptedException("rootfs 安装已取消")
        }
    }

    private fun InputStream.copyExactly(output: java.io.OutputStream, bytes: Long) {
        val buffer = ByteArray(BUFFER_SIZE)
        var remaining = bytes
        while (remaining > 0) {
            checkInterrupted()
            val read = read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
            if (read < 0) throw EOFException("rootfs 条目数据意外结束")
            output.write(buffer, 0, read)
            remaining -= read
        }
    }

    private fun InputStream.readExactly(bytes: Long): ByteArray {
        require(bytes <= MAX_ENTRY_BYTES) { "tar 条目过大：$bytes" }
        val buffer = ByteArray(bytes.toInt())
        val read = readFullyOrEnd(buffer)
        if (read != buffer.size) throw EOFException("rootfs 条目数据意外结束")
        return buffer
    }

    private fun InputStream.skipFully(bytes: Long) {
        var remaining = bytes
        while (remaining > 0) {
            checkInterrupted()
            val skipped = skip(remaining)
            if (skipped > 0) {
                remaining -= skipped
            } else if (read() >= 0) {
                remaining--
            } else {
                throw EOFException("rootfs 数据意外结束")
            }
        }
    }

    private fun InputStream.readFullyOrEnd(buffer: ByteArray): Int {
        var offset = 0
        while (offset < buffer.size) {
            val read = read(buffer, offset, buffer.size - offset)
            if (read < 0) break
            offset += read
        }
        return offset
    }

    private fun File.safeResolve(path: String): File {
        val normalized = normalizeTarPath(path)
        val root = canonicalFile
        val target = File(root, normalized).canonicalFile
        require(target.path == root.path || target.path.startsWith(root.path + File.separator)) {
            "rootfs 条目越界：$path"
        }
        return target
    }

    private fun File.applyMode(mode: Int) {
        setReadable(mode and 0b100_000_000 != 0, false)
        setWritable(mode and 0b010_000_000 != 0, true)
        setExecutable(mode and 0b001_000_000 != 0, false)
    }

    private fun normalizeTarPath(path: String): String {
        require(!path.contains('\u0000')) { "rootfs 条目路径包含非法字符" }
        val segments = path
            .replace('\\', '/')
            .trim()
            .split('/')
            .filter { it.isNotEmpty() && it != "." }
        require(segments.none { it == ".." }) { "rootfs 条目越界：$path" }
        return segments.joinToString("/")
    }

    private fun ByteArray.string(offset: Int, length: Int): String {
        val end = (offset until offset + length).firstOrNull { this[it] == 0.toByte() }
            ?: (offset + length)
        return copyOfRange(offset, end).toString(Charsets.UTF_8).trim()
    }

    private fun ByteArray.octal(offset: Int, length: Int): Long {
        val value = string(offset, length).trim().lowercase(Locale.US).trimEnd('\u0000')
        return if (value.isBlank()) 0L else value.toLong(8)
    }

    private fun Long.paddingSize(): Long = (TAR_BLOCK_SIZE - (this % TAR_BLOCK_SIZE)).let {
        if (it == TAR_BLOCK_SIZE.toLong()) 0L else it
    }

    private fun Long.paddedTarSize(): Long = this + paddingSize()

    private data class TarHeader(
        val name: String,
        val mode: Int,
        val size: Long,
        val modTime: Long,
        val type: TarEntryType,
        val linkName: String,
    )

    private enum class TarEntryType {
        FILE,
        DIRECTORY,
        SYMLINK,
        HARDLINK,
        LONG_NAME,
        LONG_LINK,
        PAX,
        OTHER,
    }

    private companion object {
        const val STAGING_DIR = "rootfs-staging"
        const val TAR_BLOCK_SIZE = 512
        const val BUFFER_SIZE = 64 * 1024
        const val PROGRESS_STEP_BYTES = 512 * 1024
        const val CONNECT_TIMEOUT_MS = 30_000
        const val READ_TIMEOUT_MS = 120_000
        const val MAX_ARCHIVE_BYTES = 1024L * 1024 * 1024
        const val MAX_ENTRY_BYTES = 64L * 1024 * 1024
    }
}
