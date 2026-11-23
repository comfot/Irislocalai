package com.iris.moew.agent.tools

import android.content.Context
import java.io.File

/**
 * 应用自身目录访问：只暴露应用私有 filesDir 与应用外置目录，
 * 使用 app://private/... 与 app://external/... 前缀。
 * 共享偏好、数据库、缓存等敏感目录不开放。
 */
class AppFiles(context: Context) {

    private val privateRoot = context.applicationContext.filesDir
    private val externalRoot = context.applicationContext.getExternalFilesDir(null)

    fun list(path: String): String {
        val clean = normalize(path)
        if (clean.isEmpty()) {
            return buildString {
                appendLine("app:// 根目录")
                appendLine("- private/  ${privateRoot.absolutePath}")
                externalRoot?.let { appendLine("- external/ ${it.absolutePath}") }
            }.trim()
        }
        val dir = resolve(clean) ?: return "路径不在应用目录：$path"
        if (!dir.isDirectory) return "目录不存在：$path"
        val children = dir.listFiles()?.sortedBy { it.name.lowercase() }.orEmpty()
        if (children.isEmpty()) return "目录为空：$path"
        return buildString {
            appendLine("目录：app://$clean（${children.size} 项）")
            children.take(500).forEach { file ->
                val suffix = if (file.isDirectory) "/" else " (${file.length()} B)"
                appendLine("- ${file.name}$suffix")
            }
        }.trim()
    }

    fun readNumbered(path: String, offset: Int, limit: Int): String {
        val file = resolve(normalize(path)) ?: return "路径不在应用目录：$path"
        if (!file.isFile) return "文件不存在：$path"
        if (file.length() > MAX_FILE_BYTES) return "文件超过 4 MB：$path"
        val lines = file.readText().replace("\r\n", "\n").split('\n')
        val start = offset.coerceAtLeast(1) - 1
        if (start >= lines.size) return "起始行超出文件行数（共 ${lines.size} 行）"
        val count = if (limit > 0) limit.coerceIn(1, MAX_LINES) else lines.size
        val end = (start + count).coerceAtMost(lines.size)
        return buildString {
            appendLine("文件：app://${normalize(path)}（共 ${lines.size} 行，显示 ${start + 1}-$end）")
            lines.subList(start, end).forEachIndexed { index, line ->
                appendLine("${(start + index + 1).toString().padStart(5)}  $line")
            }
            if (end < lines.size) append("（还有 ${lines.size - end} 行）")
        }.trimEnd()
    }

    fun write(path: String, content: String): String {
        val file = resolveForWrite(normalize(path)) ?: return "路径不在应用目录或不可写：$path"
        if (content.toByteArray().size > MAX_WRITE_BYTES) return "写入内容超过 2 MB"
        file.parentFile?.mkdirs()
        file.writeText(content)
        return "已写入 app://${normalize(path)}"
    }

    fun edit(path: String, oldText: String, newText: String, replaceAll: Boolean): String {
        val file = resolveForWrite(normalize(path)) ?: return "路径不在应用目录或不可写：$path"
        if (!file.isFile) return "文件不存在：$path"
        val content = file.readText()
        val count = Regex(Regex.escape(oldText)).findAll(content).count()
        if (count == 0) return "未找到 old_string，请先读取确认原文"
        if (count > 1 && !replaceAll) return "old_string 匹配到 $count 处，请提供更长上下文或 replace_all=true"
        val updated = if (replaceAll) content.replace(oldText, newText)
        else content.replaceFirst(oldText, newText)
        file.writeText(updated)
        return "已修改 app://${normalize(path)}（替换 $count 处）"
    }

    fun search(query: String): String {
        if (query.isBlank()) return "缺少 query"
        val results = mutableListOf<String>()
        roots().forEach { root -> walk(root, query, results) }
        if (results.isEmpty()) return "没有匹配：$query"
        return buildString {
            appendLine("应用目录搜索结果（最多 100 条）：")
            results.take(100).forEach { appendLine(it) }
        }.trim()
    }

    private fun walk(file: File, query: String, out: MutableList<String>) {
        if (out.size >= 100 || !file.exists()) return
        if (file.isDirectory) {
            if (isBlocked(file)) return
            file.listFiles()?.forEach { child -> walk(child, query, out) }
            return
        }
        if (file.length() > MAX_SEARCH_BYTES) return
        val text = runCatching { file.readText() }.getOrNull() ?: return
        text.lineSequence().forEachIndexed { index, line ->
            if (out.size < 100 && line.contains(query, ignoreCase = true)) {
                out += "${display(file)}:${index + 1}: ${line.trim().take(200)}"
            }
        }
    }

    private fun roots(): List<File> = listOfNotNull(privateRoot, externalRoot)

    private fun resolve(relative: String): File? {
        val (root, rest) = rootFor(relative) ?: return null
        if (isBlocked(root, rest)) return null
        val base = runCatching { root.canonicalFile }.getOrNull() ?: return null
        val file = runCatching { File(base, rest).canonicalFile }.getOrNull() ?: return null
        return file.takeIf { it.path == base.path || it.path.startsWith(base.path + File.separator) }
    }

    private fun resolveForWrite(relative: String): File? = resolve(relative)

    private fun rootFor(relative: String): Pair<File, String>? {
        val clean = relative.trim('/')
        val first = clean.substringBefore('/')
        val rest = clean.substringAfter('/', "")
        return when (first) {
            "private", "files" -> privateRoot to rest
            "external" -> externalRoot?.let { it to rest }
            else -> null
        }
    }

    private fun isBlocked(file: File): Boolean =
        file.name in BLOCKED_NAMES && (file.parentFile == privateRoot || file.parentFile == externalRoot)

    private fun isBlocked(root: File, rest: String): Boolean {
        val first = rest.substringBefore('/')
        return first in BLOCKED_NAMES
    }

    private fun display(file: File): String {
        val root = roots().firstOrNull { file.path.startsWith(it.absolutePath + File.separator) }
        return root?.let { "app://${if (it == privateRoot) "private" else "external"}/${file.relativeTo(it).invariantSeparatorsPath}" }
            ?: file.absolutePath
    }

    private fun normalize(path: String): String =
        path.trim().removePrefix("app://").replace('\\', '/').trim('/')

    companion object {
        private const val MAX_LINES = 2000
        private const val MAX_FILE_BYTES = 4L * 1024 * 1024
        private const val MAX_WRITE_BYTES = 2L * 1024 * 1024
        private const val MAX_SEARCH_BYTES = 2L * 1024 * 1024
        private val BLOCKED_NAMES = setOf("shared_prefs", "databases", "cache", "no_backup")
    }
}
