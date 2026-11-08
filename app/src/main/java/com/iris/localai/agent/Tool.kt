package com.iris.localai.agent

import kotlinx.serialization.json.JsonObject

/** 一个可被模型调用的工具。实现方自己做参数校验，失败时抛出带可读信息的异常。 */
interface Tool {
    val name: String
    val description: String

    suspend fun execute(arguments: JsonObject): String
}
