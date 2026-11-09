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
import com.iris.localai.data.resolveDark
import com.iris.localai.notify.AppForeground
import com.iris.localai.ui.MainScreen
import com.iris.localai.ui.theme.IrisTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val graph = (application as IrisApp).graph
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
            IrisTheme(darkTheme = settings.themeMode.resolveDark(isSystemInDarkTheme())) {
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
