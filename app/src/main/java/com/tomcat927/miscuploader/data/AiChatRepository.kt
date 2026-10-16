package com.tomcat927.miscuploader.data

import com.tomcat927.miscuploader.core.AiMessage
import com.tomcat927.miscuploader.core.AiToolCall
import com.tomcat927.miscuploader.data.db.AiConversationDao
import com.tomcat927.miscuploader.data.db.AiConversationEntity
import com.tomcat927.miscuploader.data.db.AiMessageEntity
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.Json

/**
 * AI 助手会话仓库(拍板 2026-10-16):多会话 + 消息持久化。
 * 图片不落库:带图消息仅会话内有效(见 [AiMessageEntity])。
 */
@Singleton
class AiChatRepository @Inject constructor(
    private val dao: AiConversationDao,
) {

    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }

    fun conversations(): Flow<List<AiConversationEntity>> = dao.observeConversations()

    suspend fun createConversation(title: String): Long {
        val now = System.currentTimeMillis()
        return dao.insertConversation(AiConversationEntity(title = title, createdAt = now, updatedAt = now))
    }

    suspend fun rename(id: Long, title: String) = dao.updateTitle(id, title, System.currentTimeMillis())

    suspend fun delete(id: Long) {
        dao.deleteConversation(id)
        dao.deleteMessages(id)
    }

    suspend fun loadMessages(id: Long): List<AiMessage> =
        dao.messages(id).map { it.toAiMessage() }

    suspend fun clearMessages(id: Long) = dao.deleteMessages(id)

    /** 追加一条消息(带图消息不落库);并刷新会话 updatedAt */
    suspend fun appendMessage(conversationId: Long, message: AiMessage) {
        if (message.images.isEmpty()) {
            dao.insertMessage(message.toEntity(conversationId))
        }
        dao.touch(conversationId, System.currentTimeMillis())
    }

    private fun AiMessage.toEntity(conversationId: Long) = AiMessageEntity(
        conversationId = conversationId,
        role = role,
        text = text,
        toolCallsJson = if (toolCalls.isEmpty()) null else json.encodeToString(toolCalls),
        toolCallId = toolCallId,
        createdAt = System.currentTimeMillis(),
    )

    private fun AiMessageEntity.toAiMessage() = AiMessage(
        role = role,
        text = text,
        toolCalls = toolCallsJson?.let { runCatching { json.decodeFromString<List<AiToolCall>>(it) }.getOrNull() }
            .orEmpty(),
        toolCallId = toolCallId,
    )
}
