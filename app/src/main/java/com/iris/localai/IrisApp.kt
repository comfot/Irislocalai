package com.iris.localai

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
        graph.api.syncEnabled()
        graph.mcpServer.syncEnabled()
        graph.integrations.startAll()
        graph.memory.onAppOpened(graph.settings.current.memoryAutoDiary)
    }
}
