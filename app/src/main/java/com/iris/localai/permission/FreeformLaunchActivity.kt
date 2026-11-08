package com.iris.localai.permission

import android.app.Activity
import android.app.ActivityOptions
import android.content.Intent
import android.graphics.Rect
import android.os.Bundle
import android.util.Log
import android.widget.Toast

/** 透明代理 Activity：以 freeform 窗口模式启动目标应用。 */
class FreeformLaunchActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val packageName = intent.getStringExtra(EXTRA_PACKAGE).orEmpty()
        if (packageName.isNotBlank()) {
            val launch = packageManager.getLaunchIntentForPackage(packageName)
            if (launch != null) {
                launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
                val metrics = resources.displayMetrics
                val left = metrics.widthPixels / 12
                val top = metrics.heightPixels / 7
                val bounds = Rect(left, top, metrics.widthPixels - left, metrics.heightPixels - top)
                val options = ActivityOptions.makeBasic()
                runCatching {
                    ActivityOptions::class.java
                        .getMethod("setLaunchWindowingMode", Int::class.javaPrimitiveType)
                        .invoke(options, WINDOWING_MODE_FREEFORM)
                }
                options.setLaunchBounds(bounds)
                runCatching { startActivity(launch, options.toBundle()) }
                    .onFailure {
                        Log.w(TAG, "freeform launch failed, fallback normal", it)
                        runCatching { startActivity(launch) }
                    }
            } else {
                val result = ShizukuBridge.execResult("monkey -p '$packageName' 1")
                if (result.exitCode != 0) {
                    Log.w(TAG, "launch $packageName failed: ${result.stderrText()}")
                    Toast.makeText(this, "未找到可启动的应用：$packageName", Toast.LENGTH_SHORT).show()
                }
            }
        }
        finish()
        overridePendingTransition(0, 0)
    }

    companion object {
        private const val TAG = "IrisFreeform"
        const val EXTRA_PACKAGE = "package_name"
        private const val WINDOWING_MODE_FREEFORM = 5
    }
}
