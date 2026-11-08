package com.iris.localai.permission

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.core.content.ContextCompat
import com.iris.localai.accessibility.IrisAccessibilityService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class OperationChannel(val label: String) {
    ACCESSIBILITY("无障碍"),
    SHIZUKU("Shizuku"),
    BACKGROUND("后台（Shizuku）"),
    NONE("未就绪"),
}

data class DevicePermissionState(
    val accessibilityEnabled: Boolean = false,
    val accessibilityConnected: Boolean = false,
    val overlayGranted: Boolean = false,
    val microphoneGranted: Boolean = false,
    val notificationGranted: Boolean = false,
    val shizukuInstalled: Boolean = false,
    val shizukuBinderConnected: Boolean = false,
    val shizukuAuthorized: Boolean = false,
) {
    val shizukuReady: Boolean get() = shizukuBinderConnected && shizukuAuthorized
    val coreReady: Boolean get() = accessibilityConnected && overlayGranted && microphoneGranted
    val readyCount: Int get() = listOf(accessibilityConnected, overlayGranted, microphoneGranted).count { it }

    fun channel(backgroundMode: Boolean, preferShizuku: Boolean): OperationChannel = when {
        backgroundMode -> if (shizukuReady) OperationChannel.BACKGROUND else OperationChannel.NONE
        preferShizuku && shizukuReady -> OperationChannel.SHIZUKU
        accessibilityConnected -> OperationChannel.ACCESSIBILITY
        else -> OperationChannel.NONE
    }

    fun ready(backgroundMode: Boolean, preferShizuku: Boolean): Boolean =
        channel(backgroundMode, preferShizuku) != OperationChannel.NONE
}

/** 操作空间与设置›权限页共享的权限/运行状态。 */
class DevicePermissionStore(private val context: Context) {

    private val appContext = context.applicationContext
    private val serviceComponent = IrisAccessibilityService::class.java.name

    private val _state = MutableStateFlow(read())
    val state: StateFlow<DevicePermissionState> = _state.asStateFlow()

    init {
        IrisAccessibilityService.onConnectionChanged = { refresh() }
        ShizukuBridge.addBinderReceivedListener { refresh() }
        ShizukuBridge.addBinderDeadListener { refresh() }
        ShizukuBridge.addRequestPermissionResultListener { _, _ -> refresh() }
    }

    fun refresh() {
        _state.value = read()
    }

    fun requestShizukuPermission(): Boolean = ShizukuBridge.requestPermission()

    fun enableAccessibilityViaShizuku(): Boolean {
        val enabled = ShizukuBridge.enableAccessibility(appContext, IrisAccessibilityService::class.java)
        refresh()
        return enabled
    }

    fun openShizuku(): Boolean {
        val manager = appContext.packageManager
        val intent = SHIZUKU_PACKAGES.asSequence()
            .mapNotNull { manager.getLaunchIntentForPackage(it) }
            .firstOrNull()
            ?: return false
        runCatching {
            appContext.startActivity(intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        return true
    }

    val accessibilityServiceId: String
        get() = "$appContext.packageName/$serviceComponent"

    private fun read(): DevicePermissionState {
        val secureEnabled = runCatching {
            Settings.Secure.getString(
                appContext.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            )
        }.getOrNull().orEmpty()
        val accessibilityEnabled = secureEnabled.contains(accessibilityServiceId) ||
            secureEnabled.contains(
                "$appContext.packageName/.accessibility.IrisAccessibilityService",
            ) ||
            runCatching {
                val manager = appContext.getSystemService(Context.ACCESSIBILITY_SERVICE)
                    as? android.view.accessibility.AccessibilityManager
                manager?.getEnabledAccessibilityServiceList(
                    android.accessibilityservice.AccessibilityServiceInfo.FEEDBACK_ALL_MASK,
                )?.any { it.resolveInfo?.serviceInfo?.packageName == appContext.packageName } == true
            }.getOrDefault(false)
        val notification = if (Build.VERSION.SDK_INT >= 33) {
            ContextCompat.checkSelfPermission(appContext, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
        return DevicePermissionState(
            accessibilityEnabled = accessibilityEnabled,
            accessibilityConnected = IrisAccessibilityService.instance != null,
            overlayGranted = runCatching { Settings.canDrawOverlays(appContext) }.getOrDefault(false),
            microphoneGranted = ContextCompat.checkSelfPermission(
                appContext,
                Manifest.permission.RECORD_AUDIO,
            ) == PackageManager.PERMISSION_GRANTED,
            notificationGranted = notification,
            shizukuInstalled = SHIZUKU_PACKAGES.any { installed(it) },
            shizukuBinderConnected = ShizukuBridge.pingBinder(),
            shizukuAuthorized = ShizukuBridge.hasPermission(),
        )
    }

    private fun installed(packageName: String): Boolean = runCatching {
        @Suppress("DEPRECATION")
        appContext.packageManager.getPackageInfo(packageName, 0)
        true
    }.getOrDefault(false)

    private companion object {
        val SHIZUKU_PACKAGES = listOf(
            "moe.shizuku.privileged.api",
            "moe.shizuku.manager",
        )
    }
}
