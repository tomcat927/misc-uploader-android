package com.tomcat927.miscuploader.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface AiConversationDao {

    @Insert
    suspend fun insertConversation(conversation: AiConversationEntity): Long

    @Query("SELECT * FROM ai_conversation ORDER BY updatedAt DESC")
    fun observeConversations(): Flow<List<AiConversationEntity>>

    @Query("SELECT * FROM ai_conversation WHERE id = :id")
    suspend fun conversation(id: Long): AiConversationEntity?

    @Query("UPDATE ai_conversation SET title = :title, updatedAt = :updatedAt WHERE id = :id")
    suspend fun updateTitle(id: Long, title: String, updatedAt: Long)

    @Query("UPDATE ai_conversation SET updatedAt = :updatedAt WHERE id = :id")
    suspend fun touch(id: Long, updatedAt: Long)

    @Query("DELETE FROM ai_conversation WHERE id = :id")
    suspend fun deleteConversation(id: Long)

    @Insert
    suspend fun insertMessage(message: AiMessageEntity)

    @Query("SELECT * FROM ai_message WHERE conversationId = :conversationId ORDER BY id ASC")
    suspend fun messages(conversationId: Long): List<AiMessageEntity>

    @Query("DELETE FROM ai_message WHERE conversationId = :conversationId")
    suspend fun deleteMessages(conversationId: Long)
}
