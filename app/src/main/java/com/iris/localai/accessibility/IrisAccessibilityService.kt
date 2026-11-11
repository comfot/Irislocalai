package com.iris.localai.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.Rect
import android.os.Bundle
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.Executor
import kotlin.coroutines.resume

/** 无障碍通道：全局操作、手势与文本输入。 */
class IrisAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        onConnectionChanged?.invoke()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        if (instance === this) instance = null
        onConnectionChanged?.invoke()
        super.onDestroy()
    }

    fun tap(x: Float, y: Float): Boolean {
        val path = Path().apply { moveTo(x, y) }
        return dispatch(path, 60)
    }

    fun swipe(x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Long): Boolean {
        val path = Path().apply {
            moveTo(x1, y1)
            lineTo(x2, y2)
        }
        return dispatch(path, durationMs.coerceIn(80L, 3000L))
    }

    fun back(): Boolean = performGlobalAction(GLOBAL_ACTION_BACK)

    fun home(): Boolean = performGlobalAction(GLOBAL_ACTION_HOME)

    fun longPress(x: Float, y: Float): Boolean {
        val path = Path().apply { moveTo(x, y) }
        return dispatch(path, 650)
    }

    fun pressEnter(): Boolean {
        val root = rootInActiveWindow ?: return false
        val focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return false
        return focused.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
    }

    fun recents(): Boolean = performGlobalAction(GLOBAL_ACTION_RECENTS)

    fun inputText(text: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return false
        val arguments = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        return focused.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
    }

    /** 当前界面的可读/可操作控件快照，供 Agent 按文字定位。displayId 可指定虚拟屏。 */
    fun captureUi(
        maxNodes: Int = 200,
        maxDepth: Int = 24,
        displayId: Int = Display.DEFAULT_DISPLAY,
    ): UiSnapshot {
        val metrics = resources.displayMetrics
        val root = rootForDisplay(displayId)
        val nodes = mutableListOf<UiNode>()
        fun walk(node: AccessibilityNodeInfo?, depth: Int) {
            if (node == null || depth > maxDepth || nodes.size >= maxNodes) return
            val bounds = Rect().also { node.getBoundsInScreen(it) }
            val text = node.text?.toString()?.trim().orEmpty()
            val description = node.contentDescription?.toString()?.trim().orEmpty()
            val viewId = node.viewIdResourceName.orEmpty()
            val actionable = node.isClickable || node.isEditable || node.isScrollable || node.isLongClickable
            if (text.isNotBlank() || description.isNotBlank() || viewId.isNotBlank() || actionable) {
                nodes.add(
                    UiNode(
                        text = text,
                        description = description,
                        viewId = viewId,
                        className = node.className?.toString().orEmpty(),
                        bounds = bounds,
                        clickable = node.isClickable,
                        editable = node.isEditable,
                        scrollable = node.isScrollable,
                        longClickable = node.isLongClickable,
                    ),
                )
            }
            for (index in 0 until node.childCount) walk(node.getChild(index), depth + 1)
        }
        walk(root, 0)
        return UiSnapshot(
            width = metrics.widthPixels,
            height = metrics.heightPixels,
            packageName = root?.packageName?.toString().orEmpty(),
            nodes = nodes,
        )
    }

    fun hasText(text: String, displayId: Int = Display.DEFAULT_DISPLAY): Boolean {
        val query = text.trim()
        if (query.isBlank()) return false
        val root = rootForDisplay(displayId) ?: return false
        return findTextNodes(root, query, 1).isNotEmpty()
    }

    /** 按文字/描述点击；优先对可点击祖先执行 ACTION_CLICK，否则回退到坐标手势。 */
    fun tapText(
        text: String,
        matchIndex: Int = 0,
        displayId: Int = Display.DEFAULT_DISPLAY,
    ): Boolean {
        val query = text.trim()
        if (query.isBlank()) return false
        val root = rootForDisplay(displayId) ?: return false
        val target = findTextNodes(root, query, 50).getOrNull(matchIndex.coerceAtLeast(0)) ?: return false
        var current: AccessibilityNodeInfo? = target
        while (current != null) {
            if (current.isClickable && current.isEnabled) {
                if (current.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
            }
            current = current.parent
        }
        if (displayId != Display.DEFAULT_DISPLAY) return false
        val bounds = Rect().also { target.getBoundsInScreen(it) }
        return tap(bounds.centerX().toFloat(), bounds.centerY().toFloat())
    }

    fun findTextBounds(
        text: String,
        matchIndex: Int = 0,
        displayId: Int = Display.DEFAULT_DISPLAY,
    ): Rect? {
        val query = text.trim()
        if (query.isBlank()) return null
        val root = rootForDisplay(displayId) ?: return null
        val target = findTextNodes(root, query, 50).getOrNull(matchIndex.coerceAtLeast(0)) ?: return null
        return Rect().also { target.getBoundsInScreen(it) }
    }

    /** direction：down（查看后续内容）/ up / left / right。 */
    fun scrollUi(direction: String, displayId: Int = Display.DEFAULT_DISPLAY): Boolean {
        val root = rootForDisplay(displayId) ?: return false
        val scrollable = findScrollable(root, 0)
        if (scrollable != null) {
            val action = when (direction.lowercase()) {
                "up", "back", "backward" -> AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
                "left" -> AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_LEFT.id
                "right" -> AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_RIGHT.id
                else -> AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
            }
            if (scrollable.performAction(action)) return true
        }
        if (displayId != Display.DEFAULT_DISPLAY) return false
        val metrics = resources.displayMetrics
        val cx = metrics.widthPixels / 2f
        val cy = metrics.heightPixels / 2f
        val distance = minOf(metrics.widthPixels, metrics.heightPixels) * 0.32f
        return when (direction.lowercase()) {
            "up", "back", "backward" -> swipe(cx, cy - distance, cx, cy + distance, 260)
            "left" -> swipe(cx + distance, cy, cx - distance, cy, 260)
            "right" -> swipe(cx - distance, cy, cx + distance, cy, 260)
            else -> swipe(cx, cy + distance, cx, cy - distance, 260)
        }
    }

    fun notifications(): Boolean = performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS)

    fun frontPackage(displayId: Int = Display.DEFAULT_DISPLAY): String =
        rootForDisplay(displayId)?.packageName?.toString().orEmpty()

    private fun rootForDisplay(displayId: Int): AccessibilityNodeInfo? {
        if (displayId == Display.DEFAULT_DISPLAY) return rootInActiveWindow
        return runCatching {
            windows.asSequence()
                .filter { it.displayId == displayId }
                .sortedByDescending { it.layer }
                .mapNotNull { it.root }
                .firstOrNull()
        }.getOrNull()
    }

    private fun findTextNodes(
        root: AccessibilityNodeInfo,
        query: String,
        limit: Int,
    ): List<AccessibilityNodeInfo> {
        val matches = mutableListOf<AccessibilityNodeInfo>()
        fun walk(node: AccessibilityNodeInfo?, depth: Int) {
            if (node == null || depth > 24 || matches.size >= limit) return
            val text = node.text?.toString()?.trim().orEmpty()
            val description = node.contentDescription?.toString()?.trim().orEmpty()
            if (text.contains(query, ignoreCase = true) || description.contains(query, ignoreCase = true)) {
                matches.add(node)
            }
            for (index in 0 until node.childCount) walk(node.getChild(index), depth + 1)
        }
        walk(root, 0)
        return matches
    }

    private fun findScrollable(node: AccessibilityNodeInfo?, depth: Int): AccessibilityNodeInfo? {
        if (node == null || depth > 24) return null
        if (node.isScrollable && node.isEnabled) return node
        for (index in 0 until node.childCount) {
            findScrollable(node.getChild(index), depth + 1)?.let { return it }
        }
        return null
    }

    suspend fun screenshot(): Bitmap? = suspendCancellableCoroutine { continuation ->
        takeScreenshot(
            Display.DEFAULT_DISPLAY,
            Executor { it.run() },
            object : TakeScreenshotCallback {
                override fun onSuccess(result: ScreenshotResult) {
                    val bitmap = runCatching {
                        Bitmap.wrapHardwareBuffer(result.hardwareBuffer, null)?.copy(Bitmap.Config.ARGB_8888, false)
                    }.getOrNull()
                    result.hardwareBuffer.close()
                    if (continuation.isActive) continuation.resume(bitmap)
                }

                override fun onFailure(errorCode: Int) {
                    if (continuation.isActive) continuation.resume(null)
                }
            },
        )
    }

    private fun dispatch(path: Path, durationMs: Long): Boolean {
        val stroke = GestureDescription.StrokeDescription(path, 0, durationMs)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        return dispatchGesture(gesture, null, null)
    }

    companion object {
        @Volatile
        var instance: IrisAccessibilityService? = null
            private set

        @Volatile
        var onConnectionChanged: (() -> Unit)? = null
    }
}
