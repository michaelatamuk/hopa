package com.hopa.sidecar

import com.hopa.data.sessions.ChatMessage
import com.hopa.data.sessions.Conversation
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/** W1: one JSON file of conversations; SQLite comes when it hurts. */
class ChatStore(path: Path) {
    private val file: Path = path
    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    }
    private var conversations: MutableList<Conversation> = load()

    fun list(includeArchived: Boolean = false): List<Conversation> =
        conversations
            .filter { it.deletedAt == 0L && (includeArchived || it.archivedAt == 0L) }
            .sortedWith(
                compareByDescending<Conversation> { it.pinned }
                    .thenByDescending { it.updatedAt },
            )

    fun get(id: String): Conversation? = conversations.firstOrNull { it.id == id }

    fun create(): Conversation {
        val now = System.currentTimeMillis()
        val conversation = Conversation(id = "c-${UUID.randomUUID()}", title = "New chat", updatedAt = now)
        conversations.add(conversation)
        save()
        return conversation
    }

    fun append(conversationId: String, role: String, text: String): Conversation? {        val index = conversations.indexOfFirst { it.id == conversationId }
        if (index < 0) {
            return null
        }

        val conversation = conversations[index]
        val now = System.currentTimeMillis()
        val message = ChatMessage(
            id = "m-$now-${UUID.randomUUID().toString().take(6)}",
            role = role,
            text = text,
            timestamp = now,
        )
        val title = if (conversation.title == "New chat" && role == "user" && text.isNotBlank()) {
            text.take(40)
        } else {
            conversation.title
        }
        val updated = conversation.copy(
            title = title,
            messages = conversation.messages + message,
            updatedAt = now,
        )
        conversations[index] = updated
        save()
        return updated
    }

    fun rename(conversationId: String, title: String): Conversation? {
        val index = conversations.indexOfFirst { it.id == conversationId }
        if (index < 0) {
            return null
        }
        val updated = conversations[index].copy(title = title.trim().take(80), updatedAt = System.currentTimeMillis())
        conversations[index] = updated
        save()
        return updated
    }

    fun delete(conversationId: String): Boolean {
        val index = conversations.indexOfFirst { it.id == conversationId }
        if (index < 0) {
            return false
        }
        conversations[index] = conversations[index].copy(
            deletedAt = System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis(),
        )
        save()
        return true
    }

    fun setPinned(conversationId: String, pinned: Boolean): Conversation? {
        val index = conversations.indexOfFirst { it.id == conversationId }
        if (index < 0) {
            return null
        }
        val updated = conversations[index].copy(pinned = pinned, updatedAt = System.currentTimeMillis())
        conversations[index] = updated
        save()
        return updated
    }

    fun setArchived(conversationId: String, archived: Boolean): Conversation? {
        val index = conversations.indexOfFirst { it.id == conversationId }
        if (index < 0) {
            return null
        }
        val updated = conversations[index].copy(
            archivedAt = if (archived) System.currentTimeMillis() else 0L,
            updatedAt = System.currentTimeMillis(),
        )
        conversations[index] = updated
        save()
        return updated
    }

    fun search(query: String): List<Conversation> {
        val needle = query.trim()
        if (needle.isEmpty()) {
            return list()
        }
        return list().filter { conversation ->
            conversation.title.contains(needle, ignoreCase = true) ||
                conversation.messages.any { it.text.contains(needle, ignoreCase = true) }
        }
    }

    private fun load(): MutableList<Conversation> {
        if (!Files.exists(file)) {
            return mutableListOf()
        }
        return runCatching {
            json.decodeFromString(ListSerializer(Conversation.serializer()), Files.readString(file))
                .toMutableList()
        }.getOrElse { mutableListOf() }
    }

    private fun save() {
        runCatching {
            Files.createDirectories(file.parent)
            Files.writeString(file, json.encodeToString(ListSerializer(Conversation.serializer()), conversations))
        }
    }
}
