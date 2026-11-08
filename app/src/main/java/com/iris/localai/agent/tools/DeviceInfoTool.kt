package com.iris.localai.agent.tools

import android.os.Build
import com.iris.localai.agent.Tool
import kotlinx.serialization.json.JsonObject

class DeviceInfoTool : Tool {

    override val name: String = "device_info"
    override val description: String = "返回设备型号、Android 版本与 SDK，参数为空对象"

    override suspend fun execute(arguments: JsonObject): String =
        "${Build.MANUFACTURER} ${Build.MODEL}\nAndroid ${Build.VERSION.RELEASE}（SDK ${Build.VERSION.SDK_INT}）"
}
