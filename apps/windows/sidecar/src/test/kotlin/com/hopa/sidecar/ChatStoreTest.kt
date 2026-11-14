package com.hopa.sidecar

import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Test

class ChatStoreTest {
    @Test
    fun conversations_survive_a_reload() {
        val directory = Files.createTempDirectory("hopa-chat")
        val path = directory.resolve("conversations.json")

        val store = ChatStore(path)
        val conversation = store.create()
        store.append(conversation.id, "user", "hello")
        store.append(conversation.id, "assistant", "hi")

        val reloaded = ChatStore(path)
        val items = reloaded.list()
        assertEquals(1, items.size)
        assertEquals("hello", items[0].title)
        assertEquals(2, items[0].messages.size)
        assertEquals("assistant", items[0].messages[1].role)
    }

    @Test
    fun a_second_conversation_does_not_touch_the_first() {
        val directory = Files.createTempDirectory("hopa-chat-two")
        val store = ChatStore(directory.resolve("conversations.json"))
        val first = store.create()
        val second = store.create()
        store.append(first.id, "user", "one")
        store.append(second.id, "user", "two")

        assertEquals(2, store.list().size)
        assertEquals("one", store.get(first.id)?.title)
        assertEquals("two", store.get(second.id)?.title)
    }

    @Test
    fun pin_and_archive_change_the_list() {
        val directory = Files.createTempDirectory("hopa-chat-four")
        val path = directory.resolve("conversations.json")
        val store = ChatStore(path)
        val first = store.create()
        val second = store.create()
        store.append(first.id, "user", "one")
        store.append(second.id, "user", "two")

        store.setPinned(first.id, true)
        assertEquals(first.id, ChatStore(path).list()[0].id)

        store.setArchived(second.id, true)
        assertEquals(1, store.list().size)
        assertEquals(2, store.list(includeArchived = true).size)

        store.setArchived(second.id, false)
        assertEquals(2, store.list().size)
    }

    @Test
    fun rename_search_and_delete_work_on_disk() {
        val directory = Files.createTempDirectory("hopa-chat-three")
        val path = directory.resolve("conversations.json")
        val store = ChatStore(path)
        val conversation = store.create()
        store.append(conversation.id, "user", "pay the electricity bill")

        store.rename(conversation.id, "Bills")
        assertEquals("Bills", ChatStore(path).get(conversation.id)?.title)

        assertEquals(1, store.search("electricity").size)
        assertEquals(1, store.search("bills").size)
        assertEquals(0, store.search("nothing here").size)

        assertEquals(true, store.delete(conversation.id))
        assertEquals(0, ChatStore(path).list().size)
    }
}
