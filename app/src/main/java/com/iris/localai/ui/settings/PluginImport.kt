package com.iris.localai.ui.settings

import android.content.Context
import android.net.Uri
import java.io.ByteArrayOutputStream

internal fun readPluginZip(context: Context, uri: Uri): Result<ByteArray> = runCatching {
    context.contentResolver.openInputStream(uri)?.use { input ->
        val buffer = ByteArray(64 * 1024)
        val output = ByteArrayOutputStream()
        var total = 0
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            total += read
            if (total > 64 * 1024 * 1024) error("插件包超过 64 MB")
            output.write(buffer, 0, read)
        }
        output.toByteArray()
    } ?: error("无法读取插件包")
}
