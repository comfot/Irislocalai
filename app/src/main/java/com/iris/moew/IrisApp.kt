package com.iris.moew

import android.app.Application
import android.os.Build
import org.lsposed.hiddenapibypass.HiddenApiBypass

class IrisApp : Application() {

    lateinit var graph: AppGraph
        private set

    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= 28) {
            runCatching { HiddenApiBypass.addHiddenApiExemptions("L") }
        }
        graph = AppGraph(this)
        graph.plugins.installBundled()
        graph.api.syncEnabled()
        graph.mcpServer.syncEnabled()
        graph.integrations.startAll()
        graph.memory.onAppOpened(graph.settings.current.memoryAutoDiary)
        graph.pluginRuntime.notifyAppLifecycle("application_on_create")
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (::graph.isInitialized) graph.pluginRuntime.notifyAppLifecycle("application_on_trim_memory")
    }

    override fun onLowMemory() {
        super.onLowMemory()
        if (::graph.isInitialized) graph.pluginRuntime.notifyAppLifecycle("application_on_low_memory")
    }
}
