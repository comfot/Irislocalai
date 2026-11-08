package com.iris.localai.workspace

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

/** 工作区目录项，供文件选择器与以后的工作区浏览器使用。 */
data class WorkspaceEntry(
    val name: String,
    val path: String,
    val isDir: Boolean,
    val size: Long,
)

/**
 * 读写当前工作区文件：
 * - 应用内工作区使用 java.io.File，并以 canonicalFile 限制在根目录内；
 * - SAF 工作区使用 ContentResolver + DocumentsContract，按显示名逐级查找。
 * 所有公开方法返回可直接展示给用户的中文结果，失败时不抛出异常。
 */
class WorkspaceFs(
    context: Context,
    private val workspace: Workspace,
) {

    private val resolver: ContentResolver = context.contentResolver

    fun list(path: String = ""): String = operate {
        val parts = parsePath(path)
        if (workspace.appFolder) appList(parts) else safList(parts)
    }

    fun read(path: String): String = operate {
        readRaw(parsePath(path))
    }

    /** 带行号读取，[offset] 从 1 开始，[limit] <= 0 表示读到末尾。 */
    fun readNumbered(path: String, offset: Int, limit: Int): String = operate {
        val parts = parsePath(path)
        val content = readRaw(parts).replace("\r\n", "\n")
        val lines = content.split('\n')
        val start = offset.coerceAtLeast(1) - 1
        if (lines.isNotEmpty() && start >= lines.size) {
            fail("起始行超出文件行数（共 ${lines.size} 行）")
        }
        val count = if (limit > 0) limit.coerceIn(1, MAX_LINES) else lines.size
        val end = (start + count).coerceAtMost(lines.size)
        val selected = lines.subList(start, end)
        buildString {
            appendLine("文件：${displayPath(parts)}（共 ${lines.size} 行，显示 ${start + 1}-$end）")
            selected.forEachIndexed { index, line ->
                appendLine("${(start + index + 1).toString().padStart(5)}  $line")
            }
            if (end < lines.size) {
                append("（还有 ${lines.size - end} 行，可用 offset=${end + 1} 继续读取）")
            }
        }.trimEnd()
    }

    fun write(path: String, content: String): String = operate {
        writeRaw(parsePath(path), content)
    }

    /** 精确字符串替换；匹配到多处时必须显式允许全部替换，避免误改。 */
    fun edit(path: String, oldText: String, newText: String, replaceAll: Boolean): String = operate {
        if (oldText.isEmpty()) fail("old_string 不能为空")
        val parts = parsePath(path)
        val content = readRaw(parts)
        val count = countOccurrences(content, oldText)
        if (count == 0) fail("未找到 old_string，请先用 read_file 确认原文")
        if (count > 1 && !replaceAll) {
            fail("old_string 匹配到 $count 处，请提供更长的上下文或设置 replace_all=true")
        }
        val updated = if (replaceAll) {
            content.replace(oldText, newText)
        } else {
            content.replaceFirst(oldText, newText)
        }
        writeRaw(parts, updated)
        "已修改 ${displayPath(parts)}（替换 $count 处）"
    }

    private fun readRaw(parts: List<String>): String {
        if (parts.isEmpty()) fail("请指定要读取的文件路径")
        return if (workspace.appFolder) appRead(parts) else safRead(parts)
    }

    private fun writeRaw(parts: List<String>, content: String): String {
        if (parts.isEmpty()) fail("请指定要写入的文件路径")
        val bytes = content.toByteArray(Charsets.UTF_8)
        if (bytes.size > MAX_TEXT_BYTES) fail("写入内容超过 200 KB 上限")
        return if (workspace.appFolder) appWrite(parts, bytes) else safWrite(parts, bytes)
    }

    private fun countOccurrences(text: String, needle: String): Int {
        var count = 0
        var index = text.indexOf(needle)
        while (index >= 0) {
            count++
            index = text.indexOf(needle, index + needle.length)
        }
        return count
    }

    fun mkdir(path: String): String = operate {
        val parts = parsePath(path)
        if (parts.isEmpty()) fail("请指定要创建的目录路径")
        if (workspace.appFolder) appMkdir(parts) else safMkdir(parts)
    }

    fun delete(path: String): String = operate {
        val parts = parsePath(path)
        if (parts.isEmpty()) fail("不能删除工作区根目录")
        if (workspace.appFolder) appDelete(parts) else safDelete(parts)
    }

    fun search(query: String): String = operate {
        val keyword = query.trim()
        if (keyword.isEmpty()) fail("搜索关键词不能为空")
        if (workspace.appFolder) appSearch(keyword) else safSearch(keyword)
    }

    /** 列出目录项；路径相对于工作区根目录。 */
    fun entries(path: String = ""): Result<List<WorkspaceEntry>> = runCatching {
        val parts = parsePath(path)
        val list = if (workspace.appFolder) appEntries(parts) else safEntries(parts)
        list.sortedWith(compareByDescending<WorkspaceEntry> { it.isDir }.thenBy { it.name.lowercase() })
    }

    /** 读取文本文件原始内容；二进制/超限文件会返回失败。 */
    fun readTextFile(path: String): Result<String> = runCatching {
        readRaw(parsePath(path))
    }

    /** 读取原始字节，供图片附件使用。 */
    fun readBytes(path: String, maxBytes: Long): Result<ByteArray> = runCatching {
        val parts = parsePath(path)
        if (workspace.appFolder) appReadBytes(parts, maxBytes) else safReadBytes(parts, maxBytes)
    }

    private fun appReadBytes(parts: List<String>, maxBytes: Long): ByteArray {
        val file = appFile(parts)
        if (!file.exists()) fail("文件不存在：${displayPath(parts)}")
        if (!file.isFile) fail("不是文件：${displayPath(parts)}")
        if (file.length() > maxBytes) fail("文件超过 ${formatSize(maxBytes)} 上限")
        return file.readBytes()
    }

    private fun safReadBytes(parts: List<String>, maxBytes: Long): ByteArray {
        val document = safDocument(parts)
        if (document.isDir) fail("不能读取目录：${displayPath(parts)}")
        if (document.size > maxBytes) fail("文件超过 ${formatSize(maxBytes)} 上限")
        val input = runCatching { resolver.openInputStream(documentUri(document.id)) }.getOrNull()
            ?: fail("无法打开文件：${displayPath(parts)}")
        return input.use { stream ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            var total = 0L
            while (true) {
                val read = stream.read(buffer)
                if (read < 0) break
                total += read
                if (total > maxBytes) fail("文件超过 ${formatSize(maxBytes)} 上限")
                output.write(buffer, 0, read)
            }
            output.toByteArray()
        }
    }

    private fun appEntries(parts: List<String>): List<WorkspaceEntry> {
        val dir = appFile(parts)
        if (!dir.exists()) fail("目录不存在：${displayPath(parts)}")
        if (!dir.isDirectory) fail("不是目录：${displayPath(parts)}")
        return dir.listFiles()?.map { child ->
            WorkspaceEntry(
                name = child.name,
                path = (parts + child.name).joinToString("/"),
                isDir = child.isDirectory,
                size = if (child.isFile) child.length() else -1L,
            )
        }.orEmpty()
    }

    private fun safEntries(parts: List<String>): List<WorkspaceEntry> {
        val dir = safDocument(parts)
        if (!dir.isDir) fail("不是目录：${displayPath(parts)}")
        return childDocuments(dir.id).map { child ->
            WorkspaceEntry(
                name = child.name,
                path = (parts + child.name).joinToString("/"),
                isDir = child.isDir,
                size = child.size,
            )
        }
    }

    private fun operate(block: () -> String): String = try {
        block()
    } catch (e: WorkspaceException) {
        e.message ?: "操作失败"
    } catch (e: SecurityException) {
        "没有访问工作区文件的权限，请重新选择或授权工作区：${e.message ?: "权限不足"}"
    } catch (e: Exception) {
        "操作失败：${e.message ?: e.javaClass.simpleName}"
    }

    private fun parsePath(raw: String): List<String> {
        val text = raw.trim()
        if (text.isEmpty()) return emptyList()
        if (text.startsWith('/') || text.startsWith('\\')) fail("路径必须相对于工作区根目录")
        if (text.contains('\u0000')) fail("路径包含非法字符")
        val parts = ArrayList<String>()
        for (part in text.split('/', '\\')) {
            when (part) {
                "", "." -> Unit
                ".." -> fail("路径不能包含 ..")
                else -> parts += part
            }
        }
        return parts
    }

    private fun displayPath(parts: List<String>): String =
        if (parts.isEmpty()) "/" else parts.joinToString("/")

    private fun formatSize(bytes: Long): String = when {
        bytes < 0 -> "大小未知"
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "${bytes / 1024} KB"
        else -> "${bytes / (1024 * 1024)} MB"
    }

    private fun formatEntries(parts: List<String>, entries: List<Entry>): String {
        if (entries.isEmpty()) return "目录为空：${displayPath(parts)}"
        val ordered = entries.sortedWith(
            compareByDescending<Entry> { it.isDir }.thenBy { it.name.lowercase() },
        )
        return buildString {
            appendLine("目录：${displayPath(parts)}")
            ordered.forEach { entry ->
                if (entry.isDir) {
                    appendLine("- ${entry.name}/")
                } else {
                    appendLine("- ${entry.name}  ${formatSize(entry.size)}")
                }
            }
        }.trimEnd()
    }

    private fun formatSearch(keyword: String, results: List<String>, scanned: Int): String {
        if (results.isEmpty()) return "未找到包含「$keyword」的文件"
        return buildString {
            appendLine("搜索结果（${results.size} 条，上限 $SEARCH_LIMIT）：")
            results.forEach { appendLine("- $it") }
            if (results.size >= SEARCH_LIMIT) {
                append("（已达到 $SEARCH_LIMIT 条上限）")
            } else if (scanned >= SEARCH_SCAN_LIMIT) {
                append("（已扫描 $SEARCH_SCAN_LIMIT 个文件，未继续）")
            }
        }.trimEnd()
    }

    private fun firstMatchLine(text: String, keyword: String): Int? {
        var line = 0
        for (current in text.lineSequence()) {
            line++
            if (current.contains(keyword, ignoreCase = true)) return line
        }
        return null
    }

    private fun readText(input: InputStream): String {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            if (output.size() + count > MAX_TEXT_BYTES) {
                fail("文件超过 200 KB 上限，无法按文本读取")
            }
            output.write(buffer, 0, count)
        }
        val bytes = output.toByteArray()
        if (bytes.any { it == 0.toByte() }) fail("该文件不是文本文件（包含二进制内容）")
        return try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString()
        } catch (e: CharacterCodingException) {
            fail("无法按 UTF-8 读取，可能是二进制文件")
        }
    }

    private fun appRoot(): File {
        if (workspace.path.isBlank()) fail("工作区路径为空，请重新选择或创建工作区")
        val root = File(workspace.path).canonicalFile
        if (!root.exists() && !root.mkdirs()) fail("无法创建或访问工作区目录：${workspace.path}")
        if (!root.isDirectory) fail("工作区路径不是文件夹：${workspace.path}")
        return root
    }

    private fun appFile(parts: List<String>): File {
        val root = appRoot()
        val target = parts.fold(root) { file, name -> File(file, name) }.canonicalFile
        if (target != root && !target.path.startsWith(root.path + File.separator)) {
            fail("路径超出工作区范围")
        }
        return target
    }

    private fun appList(parts: List<String>): String {
        val dir = appFile(parts)
        if (!dir.exists()) fail("目录不存在：${displayPath(parts)}")
        if (!dir.isDirectory) fail("不是目录：${displayPath(parts)}")
        val entries = dir.listFiles()?.map { child ->
            Entry(
                name = child.name,
                isDir = child.isDirectory,
                size = if (child.isFile) child.length() else -1L,
            )
        }.orEmpty()
        return formatEntries(parts, entries)
    }

    private fun appRead(parts: List<String>): String {
        val file = appFile(parts)
        if (!file.exists()) fail("文件不存在：${displayPath(parts)}")
        if (!file.isFile) fail("不是文件：${displayPath(parts)}")
        if (file.length() > MAX_TEXT_BYTES) fail("文件超过 200 KB 上限，无法按文本读取")
        return file.inputStream().use { readText(it) }
    }

    private fun appWrite(parts: List<String>, bytes: ByteArray): String {
        val file = appFile(parts)
        if (file.isDirectory) fail("目标是目录，不能写入：${displayPath(parts)}")
        val parent = file.parentFile
        if (parent == null || !parent.isDirectory) {
            fail("上级目录不存在：${displayPath(parts.dropLast(1))}，请先创建目录")
        }
        file.writeBytes(bytes)
        return "已写入 ${displayPath(parts)}（${formatSize(bytes.size.toLong())}）"
    }

    private fun appMkdir(parts: List<String>): String {
        val dir = appFile(parts)
        if (dir.exists()) {
            if (dir.isDirectory) fail("目录已存在：${displayPath(parts)}")
            fail("同名文件已存在：${displayPath(parts)}")
        }
        val parent = dir.parentFile
        if (parent == null || !parent.isDirectory) {
            fail("上级目录不存在：${displayPath(parts.dropLast(1))}，请先创建目录")
        }
        if (!dir.mkdir()) fail("创建目录失败：${displayPath(parts)}")
        return "已创建目录 ${displayPath(parts)}"
    }

    private fun appDelete(parts: List<String>): String {
        val target = appFile(parts)
        if (!target.exists()) fail("路径不存在：${displayPath(parts)}")
        if (target.isDirectory) {
            val children = target.listFiles()
            if (children == null) fail("无法读取目录内容：${displayPath(parts)}")
            if (children.isNotEmpty()) fail("目录非空，仅支持删除空目录：${displayPath(parts)}")
        }
        if (!target.delete()) fail("删除失败：${displayPath(parts)}")
        return "已删除 ${displayPath(parts)}"
    }

    private fun appSearch(keyword: String): String {
        val root = appRoot()
        val results = ArrayList<String>()
        val visited = HashSet<String>()
        var scanned = 0

        fun visit(dir: File, prefix: String) {
            if (results.size >= SEARCH_LIMIT || scanned >= SEARCH_SCAN_LIMIT) return
            if (!visited.add(dir.canonicalPath)) return
            val children = dir.listFiles()?.sortedBy { it.name.lowercase() } ?: return
            for (child in children) {
                if (results.size >= SEARCH_LIMIT || scanned >= SEARCH_SCAN_LIMIT) return
                val canonical = runCatching { child.canonicalFile }.getOrNull() ?: continue
                if (canonical != root && !canonical.path.startsWith(root.path + File.separator)) {
                    continue
                }
                val relative = if (prefix.isEmpty()) child.name else "$prefix/${child.name}"
                if (canonical.isDirectory) {
                    visit(canonical, relative)
                    continue
                }
                if (!canonical.isFile) continue
                scanned++
                val nameMatch = child.name.contains(keyword, ignoreCase = true)
                if (nameMatch) {
                    results += "$relative（文件名）"
                } else {
                    val line = runCatching {
                        canonical.inputStream().use { firstMatchLine(readText(it), keyword) }
                    }.getOrNull()
                    if (line != null) results += "$relative:$line（内容）"
                }
            }
        }

        visit(root, "")
        return formatSearch(keyword, results, scanned)
    }

    private fun requireTree(): Uri {
        if (workspace.uri.isBlank()) fail("SAF 工作区缺少 URI，请重新选择文件夹")
        return Uri.parse(workspace.uri)
    }

    private fun rootDocumentId(): String {
        val tree = requireTree()
        return runCatching { DocumentsContract.getTreeDocumentId(tree) }
            .getOrElse { workspace.path.ifBlank { fail("无法解析工作区目录，请重新选择文件夹") } }
    }

    private fun documentUri(documentId: String): Uri =
        DocumentsContract.buildDocumentUriUsingTree(requireTree(), documentId)

    private fun childDocuments(parentId: String): List<DocumentInfo> {
        val uri = DocumentsContract.buildChildDocumentsUriUsingTree(requireTree(), parentId)
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
        )
        val documents = ArrayList<DocumentInfo>()
        val cursor = resolver.query(uri, projection, null, null, null)
            ?: fail("无法读取工作区目录，请确认文件夹权限")
        cursor.use { rows ->
            while (rows.moveToNext()) {
                val id = rows.getString(0) ?: continue
                val name = rows.getString(1) ?: continue
                val mime = rows.getString(2).orEmpty()
                val size = if (rows.isNull(3)) -1L else rows.getLong(3)
                documents += DocumentInfo(
                    id = id,
                    name = name,
                    isDir = mime == DocumentsContract.Document.MIME_TYPE_DIR,
                    size = size,
                )
            }
        }
        return documents
    }

    private fun safDocument(parts: List<String>): DocumentInfo {
        var current = DocumentInfo(rootDocumentId(), workspace.name, true, -1L)
        for ((index, name) in parts.withIndex()) {
            if (!current.isDir) {
                fail("路径不是目录：${displayPath(parts.take(index))}")
            }
            current = childDocuments(current.id).firstOrNull { it.name == name }
                ?: fail("路径不存在：${displayPath(parts)}")
        }
        return current
    }

    private fun safList(parts: List<String>): String {
        val dir = safDocument(parts)
        if (!dir.isDir) fail("不是目录：${displayPath(parts)}")
        val entries = childDocuments(dir.id).map { child ->
            Entry(name = child.name, isDir = child.isDir, size = child.size)
        }
        return formatEntries(parts, entries)
    }

    private fun safRead(parts: List<String>): String {
        val document = safDocument(parts)
        if (document.isDir) fail("不能读取目录：${displayPath(parts)}")
        if (document.size > MAX_TEXT_BYTES) fail("文件超过 200 KB 上限，无法按文本读取")
        val input = runCatching { resolver.openInputStream(documentUri(document.id)) }.getOrNull()
            ?: fail("无法打开文件：${displayPath(parts)}")
        return input.use { readText(it) }
    }

    private fun safWrite(parts: List<String>, bytes: ByteArray): String {
        val parent = safDocument(parts.dropLast(1))
        if (!parent.isDir) fail("上级路径不是目录：${displayPath(parts.dropLast(1))}")
        val name = parts.last()
        val existing = childDocuments(parent.id).firstOrNull { it.name == name }
        val uri = when {
            existing == null -> runCatching {
                DocumentsContract.createDocument(
                    resolver,
                    documentUri(parent.id),
                    "text/plain",
                    name,
                )
            }.getOrNull() ?: fail("无法创建文件：${displayPath(parts)}")
            existing.isDir -> fail("目标是目录，不能写入：${displayPath(parts)}")
            else -> documentUri(existing.id)
        }
        val output = runCatching { resolver.openOutputStream(uri, "wt") }.getOrNull()
            ?: fail("无法写入文件：${displayPath(parts)}")
        output.use {
            it.write(bytes)
            it.flush()
        }
        return "已写入 ${displayPath(parts)}（${formatSize(bytes.size.toLong())}）"
    }

    private fun safMkdir(parts: List<String>): String {
        val parent = safDocument(parts.dropLast(1))
        if (!parent.isDir) fail("上级路径不是目录：${displayPath(parts.dropLast(1))}")
        val name = parts.last()
        if (childDocuments(parent.id).any { it.name == name }) {
            fail("同名文件或目录已存在：${displayPath(parts)}")
        }
        val created = runCatching {
            DocumentsContract.createDocument(
                resolver,
                documentUri(parent.id),
                DocumentsContract.Document.MIME_TYPE_DIR,
                name,
            )
        }.getOrNull()
        if (created == null) fail("创建目录失败：${displayPath(parts)}")
        return "已创建目录 ${displayPath(parts)}"
    }

    private fun safDelete(parts: List<String>): String {
        val document = safDocument(parts)
        if (document.isDir && childDocuments(document.id).isNotEmpty()) {
            fail("目录非空，仅支持删除空目录：${displayPath(parts)}")
        }
        val deleted = runCatching {
            DocumentsContract.deleteDocument(resolver, documentUri(document.id))
        }.getOrDefault(false)
        if (!deleted) fail("删除失败：${displayPath(parts)}")
        return "已删除 ${displayPath(parts)}"
    }

    private fun safSearch(keyword: String): String {
        val root = DocumentInfo(rootDocumentId(), workspace.name, true, -1L)
        val results = ArrayList<String>()
        val visited = HashSet<String>()
        var scanned = 0

        fun visit(dir: DocumentInfo, prefix: String) {
            if (results.size >= SEARCH_LIMIT || scanned >= SEARCH_SCAN_LIMIT) return
            if (!visited.add(dir.id)) return
            val children = try {
                childDocuments(dir.id)
            } catch (e: Exception) {
                if (dir.id == root.id) throw e
                return
            }
            for (child in children.sortedBy { it.name.lowercase() }) {
                if (results.size >= SEARCH_LIMIT || scanned >= SEARCH_SCAN_LIMIT) return
                val relative = if (prefix.isEmpty()) child.name else "$prefix/${child.name}"
                if (child.isDir) {
                    visit(child, relative)
                    continue
                }
                scanned++
                val nameMatch = child.name.contains(keyword, ignoreCase = true)
                if (nameMatch) {
                    results += "$relative（文件名）"
                } else if (child.size <= MAX_TEXT_BYTES) {
                    val line = runCatching {
                        val input = resolver.openInputStream(documentUri(child.id))
                            ?: return@runCatching null
                        input.use { firstMatchLine(readText(it), keyword) }
                    }.getOrNull()
                    if (line != null) results += "$relative:$line（内容）"
                }
            }
        }

        visit(root, "")
        return formatSearch(keyword, results, scanned)
    }

    private class WorkspaceException(message: String) : Exception(message)

    private fun fail(message: String): Nothing = throw WorkspaceException(message)

    private data class Entry(val name: String, val isDir: Boolean, val size: Long)

    private data class DocumentInfo(
        val id: String,
        val name: String,
        val isDir: Boolean,
        val size: Long,
    )

    private companion object {
        const val MAX_TEXT_BYTES = 200 * 1024
        const val MAX_LINES = 2000
        const val SEARCH_LIMIT = 20
        const val SEARCH_SCAN_LIMIT = 2000
    }
}
