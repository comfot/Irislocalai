package com.iris.localai.agent

import com.iris.localai.data.Space

enum class ChatRole { USER, ASSISTANT }

enum class AttachmentKind { TEXT, IMAGE }

/** 随用户消息一起发送的附件；文本内容拼入提示词，图片以多模态内容发送。 */
data class ChatAttachment(
    val name: String,
    val path: String = "",
    val content: String = "",
    val truncated: Boolean = false,
    val kind: AttachmentKind = AttachmentKind.TEXT,
    val mimeType: String = "",
    val imagePath: String = "",
)

data class ChatMessage(
    val id: Long,
    val role: ChatRole,
    val text: String,
    val attachments: List<ChatAttachment> = emptyList(),
    val reasoning: String = "",
    val steps: List<AgentStep> = emptyList(),
)

data class Conversation(
    val id: Long,
    val title: String,
    val space: Space,
    val workspaceId: String = "",
    /** 生活空间按角色卡隔离会话；其他空间为空。 */
    val characterId: String = "",
    val messages: List<ChatMessage> = emptyList(),
    val updatedAt: Long = System.currentTimeMillis(),
)
