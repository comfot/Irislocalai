package com.iris.localai.ui.chat

import android.graphics.BitmapFactory
import android.widget.VideoView
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import com.iris.localai.scene.SceneBackgroundType
import com.iris.localai.scene.SceneConfig

/** 生活空间场景背景：图片直接绘制，视频用系统 VideoView 静音循环。 */
@Composable
internal fun SceneBackground(scene: SceneConfig?, modifier: Modifier = Modifier) {
    if (scene == null || scene.backgroundPath.isBlank()) return

    Box(modifier = modifier) {
        when (scene.backgroundType) {
            SceneBackgroundType.IMAGE -> {
                val bitmap = remember(scene.backgroundPath) {
                    val options = BitmapFactory.Options().apply { inSampleSize = 2 }
                    runCatching { BitmapFactory.decodeFile(scene.backgroundPath, options) }
                        .getOrNull()
                        ?.asImageBitmap()
                }
                if (bitmap != null) {
                    Image(
                        bitmap = bitmap,
                        contentDescription = scene.name,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }

            SceneBackgroundType.VIDEO -> {
                val context = LocalContext.current
                AndroidView(
                    factory = {
                        VideoView(context).apply {
                            setVideoPath(scene.backgroundPath)
                            setOnPreparedListener { player ->
                                player.isLooping = true
                                player.setVolume(0f, 0f)
                                start()
                            }
                        }
                    },
                    modifier = Modifier.fillMaxSize(),
                    onRelease = { view -> runCatching { view.stopPlayback() } },
                )
            }
        }
        // 不再叠加全局暗色遮罩，避免场景整体发灰；文字可读性由舞台底部渐变负责。
    }
}
