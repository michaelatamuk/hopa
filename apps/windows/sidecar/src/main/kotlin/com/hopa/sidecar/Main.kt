package com.hopa.sidecar

import com.hopa.sidecar.Protocol.asObject
import com.hopa.sidecar.Protocol.text
import com.hopa.sidecar.Protocol.type
import java.nio.file.Path

fun main() {
    val io = Stdio()
    val device = HostDevice(io)
    val dataDir = System.getenv("HOPA_DATA_DIR")
        ?.let { Path.of(it) }
        ?: Path.of(System.getProperty("user.home"), ".hopa", "windows")
    val store = ChatStore(dataDir.resolve("conversations.json"))
    val approvals = PendingApprovals(dataDir.resolve("approvals.json"))
    val records = RecordStore(dataDir.resolve("records.json"))
    val config = RuntimeConfig()
    val approval = HostApproval(io, approvals)
    val engine = Engine(io, device, approval, store, records, config)
    io.send(Protocol.ready())
    val denied = approvals.restoreAndDeny()
    if (denied > 0) {
        io.log("denied $denied pending approval(s) on restart")
    }
    io.log("ready (protocol v${Protocol.VERSION}), conversations at $dataDir, runs=${records.count()}")
    while (true) {
        val line = readLine() ?: break
        if (line.isBlank()) continue
        val message = line.asObject() ?: continue
        when (message.type()) {
            "hello" -> io.send(Protocol.ready())
            "conversations.list" ->
                io.send(Protocol.conversationList(
                    message.text("id"),
                    store.list(message.text("includeArchived").toBoolean()),
                ))

            "conversation.pin" ->
                io.send(Protocol.conversationOpened(
                    message.text("id"),
                    store.setPinned(message.text("conversationId"), message.text("pinned").toBoolean()),
                ))

            "conversation.archive" ->
                io.send(Protocol.conversationOpened(
                    message.text("id"),
                    store.setArchived(message.text("conversationId"), message.text("archived").toBoolean()),
                ))

            "conversation.create" ->
                io.send(Protocol.conversationOpened(message.text("id"), store.create()))

            "conversation.open" ->
                io.send(Protocol.conversationOpened(message.text("id"), store.get(message.text("conversationId"))))

            "conversation.search" ->
                io.send(Protocol.conversationList(message.text("id"), store.search(message.text("query"))))

            "conversation.rename" ->
                io.send(Protocol.conversationOpened(
                    message.text("id"),
                    store.rename(message.text("conversationId"), message.text("title")),
                ))

            "conversation.delete" ->
                io.send(Protocol.conversationDeleted(
                    message.text("id"),
                    store.delete(message.text("conversationId")),
                ))

            "conversation.export" ->
                io.send(Protocol.conversationExported(
                    message.text("id"),
                    store.get(message.text("conversationId")),
                ))

            "configure" -> {
                config.update(message)
                io.log("configured: ${config.summary()}")
                io.send(Protocol.configured(message.text("id"), config.useBridge, config.modelProvider))
            }

            "turn.start" -> engine.startTurn(
                id = message.text("id"),
                goal = message.text("goal"),
                conversationId = message.text("conversationId").ifBlank { null },
            )

            "turn.cancel" -> engine.cancel()

            "device.result" -> device.resolve(message.text("id"), message)
            "approval.result" -> approval.resolve(message.text("id"), message.text("allow").toBoolean())
            "shutdown" -> return
            else -> io.log("unknown message: ${message.type()}")
        }
    }
}
