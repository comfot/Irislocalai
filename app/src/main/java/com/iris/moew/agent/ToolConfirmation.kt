package com.iris.moew.agent

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class ToolConfirmationRequest(
    val seq: Long,
    val tool: String,
    val arguments: String,
)

/**
 * 工具"询问"权限的确认通道：Agent 在执行前挂起等待，
 * 界面收集 [request] 显示确认卡片，用户放行/拒绝后恢复。
 * 没有接入界面的调用方（子代理等）不传本对象，ASK 维持原阻止行为。
 */
class ToolConfirmation {

    private val mutex = Mutex()
    private var seqCounter = 0L
    private var pending: Pair<ToolConfirmationRequest, CompletableDeferred<Boolean>>? = null

    private val _request = MutableStateFlow<ToolConfirmationRequest?>(null)
    val request: StateFlow<ToolConfirmationRequest?> = _request.asStateFlow()

    suspend fun await(tool: String, arguments: String): Boolean {
        val deferred = CompletableDeferred<Boolean>()
        val request: ToolConfirmationRequest
        mutex.withLock {
            request = ToolConfirmationRequest(++seqCounter, tool, arguments)
            pending = request to deferred
        }
        _request.value = request
        try {
            return deferred.await()
        } finally {
            mutex.withLock {
                if (pending?.first?.seq == request.seq) {
                    pending = null
                    _request.value = null
                }
            }
        }
    }

    /** 由确认卡片调用；seq 校验避免过期请求的结果落到新请求上。 */
    fun confirm(allowed: Boolean) {
        val current = pending ?: return
        if (current.first.seq != _request.value?.seq) return
        current.second.complete(allowed)
    }
}
