package com.tomcat927.miscuploader

import com.tomcat927.miscuploader.core.AiImage
import com.tomcat927.miscuploader.core.AiMessage
import com.tomcat927.miscuploader.core.AiToolCall
import com.tomcat927.miscuploader.data.AiChatRepository
import com.tomcat927.miscuploader.data.db.AiConversationDao
import com.tomcat927.miscuploader.data.db.AiConversationEntity
import com.tomcat927.miscuploader.data.db.AiMessageEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** AiChatRepository 的实体↔消息映射(用内存 fake DAO,不依赖 Room/Android) */
class AiChatRepositoryTest {

    private class FakeDao : AiConversationDao {
        val conversations = mutableListOf<AiConversationEntity>()
        val messages = mutableListOf<AiMessageEntity>()
        private val flow = MutableStateFlow<List<AiConversationEntity>>(emptyList())
        private var convSeq = 0L
        private var msgSeq = 0L

        override suspend fun insertConversation(conversation: AiConversationEntity): Long {
            convSeq++
            conversations += conversation.copy(id = convSeq)
            flow.value = conversations.sortedByDescending { it.updatedAt }
            return convSeq
        }

        override fun observeConversations(): Flow<List<AiConversationEntity>> = flow

        override suspend fun conversation(id: Long): AiConversationEntity? =
            conversations.firstOrNull { it.id == id }

        override suspend fun updateTitle(id: Long, title: String, updatedAt: Long) {
            val i = conversations.indexOfFirst { it.id == id }
            if (i >= 0) conversations[i] = conversations[i].copy(title = title, updatedAt = updatedAt)
        }

        override suspend fun touch(id: Long, updatedAt: Long) {
            val i = conversations.indexOfFirst { it.id == id }
            if (i >= 0) conversations[i] = conversations[i].copy(updatedAt = updatedAt)
        }

        override suspend fun deleteConversation(id: Long) {
            conversations.removeAll { it.id == id }
        }

        override suspend fun insertMessage(message: AiMessageEntity) {
            msgSeq++
            messages += message.copy(id = msgSeq)
        }

        override suspend fun messages(conversationId: Long): List<AiMessageEntity> =
            messages.filter { it.conversationId == conversationId }.sortedBy { it.id }

        override suspend fun deleteMessages(conversationId: Long) {
            messages.removeAll { it.conversationId == conversationId }
        }
    }

    @Test
    fun `tool calls round-trip through persistence`() = runTest {
        val dao = FakeDao()
        val repo = AiChatRepository(dao)
        val id = repo.createConversation("测试")

        repo.appendMessage(
            id,
            AiMessage(
                role = "assistant",
                text = null,
                toolCalls = listOf(AiToolCall("call_1", "list_local", """{"path":"/sdcard"}""")),
            ),
        )
        repo.appendMessage(id, AiMessage(role = "tool", text = "12 项", toolCallId = "call_1"))

        val loaded = repo.loadMessages(id)
        assertEquals(2, loaded.size)
        assertEquals("assistant", loaded[0].role)
        assertEquals(1, loaded[0].toolCalls.size)
        assertEquals("list_local", loaded[0].toolCalls[0].name)
        assertEquals("""{"path":"/sdcard"}""", loaded[0].toolCalls[0].arguments)
        assertEquals("call_1", loaded[1].toolCallId)
        assertEquals("12 项", loaded[1].text)
    }

    @Test
    fun `image messages are not persisted`() = runTest {
        val dao = FakeDao()
        val repo = AiChatRepository(dao)
        val id = repo.createConversation("测试")

        repo.appendMessage(id, AiMessage(role = "user", text = "hi"))
        repo.appendMessage(id, AiMessage(role = "user", text = null, images = listOf(AiImage("AAAA", "image/png"))))

        val loaded = repo.loadMessages(id)
        assertEquals(1, loaded.size)
        assertEquals("hi", loaded[0].text)
    }

    @Test
    fun `clearMessages empties only the target conversation`() = runTest {
        val dao = FakeDao()
        val repo = AiChatRepository(dao)
        val a = repo.createConversation("A")
        val b = repo.createConversation("B")
        repo.appendMessage(a, AiMessage(role = "user", text = "a1"))
        repo.appendMessage(b, AiMessage(role = "user", text = "b1"))

        repo.clearMessages(a)

        assertTrue(repo.loadMessages(a).isEmpty())
        assertEquals(1, repo.loadMessages(b).size)
    }

    @Test
    fun `delete removes conversation and messages`() = runTest {
        val dao = FakeDao()
        val repo = AiChatRepository(dao)
        val id = repo.createConversation("X")
        repo.appendMessage(id, AiMessage(role = "user", text = "x"))

        repo.delete(id)

        assertTrue(repo.loadMessages(id).isEmpty())
        assertNull(dao.conversation(id))
    }
}
