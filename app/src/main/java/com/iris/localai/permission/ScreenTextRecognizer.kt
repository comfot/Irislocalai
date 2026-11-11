package com.iris.localai.permission

import android.graphics.Bitmap
import android.graphics.Rect
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

data class ScreenTextLine(
    val text: String,
    val bounds: Rect,
    val confidence: Float = 0f,
)

/** 截图文字识别：虚拟屏窗口无法通过无障碍读取时，用 OCR 提供文字和坐标。 */
object ScreenTextRecognizer {

    private val client: TextRecognizer by lazy {
        TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
    }

    suspend fun recognize(bitmap: Bitmap): List<ScreenTextLine> =
        suspendCancellableCoroutine { continuation ->
            val image = InputImage.fromBitmap(bitmap, 0)
            client.process(image)
                .addOnSuccessListener { result ->
                    val lines = result.textBlocks.flatMap { block ->
                        block.lines.mapNotNull { line ->
                            val text = line.text.trim()
                            if (text.isBlank()) {
                                null
                            } else {
                                ScreenTextLine(text, line.boundingBox ?: Rect())
                            }
                        }
                    }
                    if (continuation.isActive) continuation.resume(lines)
                }
                .addOnFailureListener {
                    if (continuation.isActive) continuation.resume(emptyList())
                }
        }
}
