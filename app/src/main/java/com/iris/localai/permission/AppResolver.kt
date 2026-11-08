package com.iris.localai.permission

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager

/** 把“设置”“微信”之类应用名解析成包名；已经是合法包名时直接验证。 */
object AppResolver {

    private val PACKAGE_PATTERN = Regex("[A-Za-z0-9._]+")

    fun resolve(context: Context, input: String): String? {
        val value = input.trim()
        if (value.isEmpty()) return null
        val manager = context.packageManager
        if (PACKAGE_PATTERN.matches(value) && installed(manager, value)) return value

        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val matches = runCatching {
            manager.queryIntentActivities(intent, 0)
        }.getOrDefault(emptyList())
        val exact = matches.firstOrNull { info ->
            info.loadLabel(manager).toString().equals(value, ignoreCase = true)
        }
        if (exact != null) return exact.activityInfo.packageName
        val contains = matches.firstOrNull { info ->
            info.loadLabel(manager).toString().contains(value, ignoreCase = true)
        }
        if (contains != null) return contains.activityInfo.packageName
        return if (PACKAGE_PATTERN.matches(value)) value else null
    }

    private fun installed(manager: PackageManager, packageName: String): Boolean = runCatching {
        @Suppress("DEPRECATION")
        manager.getPackageInfo(packageName, 0)
        true
    }.getOrDefault(false)
}
