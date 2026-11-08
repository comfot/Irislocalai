package com.iris.localai.permission

import android.os.IBinder
import android.util.Log
import rikka.shizuku.ShizukuBinderWrapper

/** 通过 Shizuku 包装的 system_server binder 获取隐藏系统服务接口。 */
object ShizukuServices {

    private const val TAG = "IrisVdServices"

    fun displayManager(): Any? = service(
        "display",
        "android.hardware.display.IDisplayManager\$Stub",
    )

    fun inputManager(): Any? = service(
        "input",
        "android.hardware.input.IInputManager\$Stub",
    )

    fun windowManager(): Any? = service(
        "window",
        "android.view.IWindowManager\$Stub",
    )

    private fun service(name: String, stubClass: String): Any? = runCatching {
        val binder = rawService(name) ?: error("service not found: $name")
        val stub = Class.forName(stubClass)
        val asInterface = stub.getMethod("asInterface", IBinder::class.java)
        asInterface.invoke(null, ShizukuBinderWrapper(binder))
    }.onFailure { Log.w(TAG, "get $name failed: ${it.message}") }.getOrNull()

    private fun rawService(name: String): IBinder? = runCatching {
        val manager = Class.forName("android.os.ServiceManager")
        val getService = manager.getMethod("getService", String::class.java)
        getService.invoke(null, name) as? IBinder
    }.getOrNull()
}
