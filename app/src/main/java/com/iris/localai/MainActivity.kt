package com.iris.localai

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.iris.localai.data.resolveDark
import com.iris.localai.motion.MotionEnvironment
import com.iris.localai.notify.AppForeground
import com.iris.localai.ui.MainScreen
import com.iris.localai.ui.theme.IrisTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val graph = (application as IrisApp).graph
        if (graph.settings.current.motionServiceAutoStart) {
            val imageId = graph.code.settings.value.imageId
            if (imageId.isNotBlank()) {
                lifecycleScope.launch(Dispatchers.IO) {
                    val environment = MotionEnvironment(applicationContext)
                    runCatching {
                        if (environment.rootfsReady(imageId)) environment.start(imageId)
                    }
                }
            }
        }
        setContent {
            val settings by graph.settings.settings.collectAsStateWithLifecycle()
            val keepScreenOn = settings.keepScreenOn
            DisposableEffect(keepScreenOn) {
                if (keepScreenOn) {
                    window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                } else {
                    window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                }
                onDispose {
                    window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                }
            }
            IrisTheme(
                darkTheme = settings.themeMode.resolveDark(isSystemInDarkTheme()),
                themeStyle = settings.themeStyle,
                accent = settings.themeAccent,
            ) {
                MainScreen()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        AppForeground.visible = true
    }

    override fun onPause() {
        AppForeground.visible = false
        super.onPause()
    }
}
