package com.iris.localai.accessibility

import android.graphics.Rect

/** 无障碍节点快照：只保留可读或可操作的控件，供 Agent 定位。 */
data class UiNode(
    val text: String,
    val description: String,
    val viewId: String,
    val className: String,
    val bounds: Rect,
    val clickable: Boolean,
    val editable: Boolean,
    val scrollable: Boolean,
    val longClickable: Boolean,
) {
    fun label(): String = text.ifBlank { description.ifBlank { viewId.ifBlank { className } } }

    fun boundsText(): String =
        "[${bounds.left},${bounds.top}][${bounds.right},${bounds.bottom}]"
}

data class UiSnapshot(
    val width: Int,
    val height: Int,
    val packageName: String,
    val nodes: List<UiNode>,
)
