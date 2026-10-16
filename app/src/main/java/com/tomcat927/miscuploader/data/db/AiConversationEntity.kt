package com.tomcat927.miscuploader.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** AI 助手会话(拍板 2026-10-16:多会话,本地持久化) */
@Entity(tableName = "ai_conversation")
data class AiConversationEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val createdAt: Long,
    val updatedAt: Long,
)

/**
 * AI 助手消息(会话内连续消息流)。
 * 图片(base64)不落库以防膨胀:带图消息仅在会话内有效,重启后丢失(可接受)。
 */
@Entity(
    tableName = "ai_message",
    indices = [Index(value = ["conversationId"])],
)
data class AiMessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val conversationId: Long,
    /** system/user/assistant/tool */
    val role: String,
    val text: String? = null,
    /** assistant 消息的工具调用(JSON 数组) */
    val toolCallsJson: String? = null,
    /** tool 消息对应的 tool_call_id */
    val toolCallId: String? = null,
    val createdAt: Long,
)
