package com.hopa.sidecar

import com.hopa.data.sessions.Attachment
import com.hopa.data.sessions.AttachmentRules
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * W4: a dropped file is copied into the data directory, capped like the phone
 * (4 MiB), and handed to the chat as an attachment.
 */
class Attachments(private val directory: Path) {
    fun accept(source: Path): Attachment {
        if (!Files.isRegularFile(source)) {
            throw IllegalArgumentException("only files can be attached")
        }

        val size = Files.size(source)
        if (size > AttachmentRules.MAX_BYTES) {
            throw IllegalArgumentException(AttachmentRules.TOO_LARGE)
        }

        Files.createDirectories(directory)
        val name = source.fileName?.toString() ?: "shared-file"
        val safe = name.replace(Regex("[^A-Za-z0-9._-]"), "_").take(60).ifBlank { "shared-file" }
        val target = directory.resolve("${System.currentTimeMillis()}-$safe")
        Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING)
        return Attachment(
            name = name,
            mime = mimeFor(name),
            bytes = Files.size(target),
            path = target.toString(),
        )
    }

    fun delete(attachment: Attachment) {
        runCatching { Files.deleteIfExists(Path.of(attachment.path)) }
    }

    fun remove(path: String) {
        runCatching { Files.deleteIfExists(Path.of(path)) }
    }

    private fun mimeFor(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
        "png" -> "image/png"
        "jpg", "jpeg" -> "image/jpeg"
        "gif" -> "image/gif"
        "webp" -> "image/webp"
        "pdf" -> "application/pdf"
        "txt", "md" -> "text/plain"
        "json" -> "application/json"
        "zip" -> "application/zip"
        else -> "application/octet-stream"
    }
}
