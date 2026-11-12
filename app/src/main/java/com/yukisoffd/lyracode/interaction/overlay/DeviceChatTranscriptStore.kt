package com.yukisoffd.lyracode.interaction.overlay

import android.content.Context
import android.util.JsonReader
import android.util.JsonWriter
import com.yukisoffd.lyracode.interaction.session.DeviceChatMessage
import java.io.File

/** One immutable, app-private snapshot per publication keeps long transcripts out of Binder. */
internal object DeviceChatTranscriptStore {
    private fun directory(context: Context) = File(context.cacheDir, "floating-transcripts").apply { mkdirs() }

    fun write(context: Context, messages: List<DeviceChatMessage>): String {
        val directory = directory(context)
        // Remove abandoned deliveries after process death without touching queued snapshots.
        val expired = System.currentTimeMillis() - 10 * 60_000L
        directory.listFiles()?.filter { it.lastModified() < expired }?.forEach { it.delete() }
        val file = File.createTempFile("transcript-", ".json", directory)
        try {
            JsonWriter(file.bufferedWriter()).use { writer ->
                writer.beginArray()
                for (message in messages) {
                    writer.beginObject()
                    writer.name("id").value(message.id)
                    writer.name("role").value(message.role)
                    writer.name("text").value(message.text)
                    writer.name("thinking").value(message.thinking)
                    writer.name("toolName").value(message.toolName)
                    writer.name("toolInput").value(message.toolInput)
                    writer.name("createdAt").value(message.createdAt)
                    writer.endObject()
                }
                writer.endArray()
            }
            return file.name
        } catch (error: Exception) {
            file.delete()
            throw error
        }
    }

    fun read(context: Context, name: String): List<DeviceChatMessage> {
        require(Regex("transcript-[a-zA-Z0-9-]+\\.json").matches(name))
        val file = File(directory(context), name)
        try {
            return JsonReader(file.bufferedReader()).use { reader ->
                buildList {
                    reader.beginArray()
                    while (reader.hasNext()) {
                        var id = 0L
                        var role = ""
                        var text = ""
                        var thinking = ""
                        var toolName = ""
                        var toolInput = ""
                        var createdAt = 0L
                        reader.beginObject()
                        while (reader.hasNext()) when (reader.nextName()) {
                            "id" -> id = reader.nextLong()
                            "role" -> role = reader.nextString()
                            "text" -> text = reader.nextString()
                            "thinking" -> thinking = reader.nextString()
                            "toolName" -> toolName = reader.nextString()
                            "toolInput" -> toolInput = reader.nextString()
                            "createdAt" -> createdAt = reader.nextLong()
                            else -> reader.skipValue()
                        }
                        reader.endObject()
                        add(DeviceChatMessage(id, role, text, thinking, toolName, createdAt, toolInput))
                    }
                    reader.endArray()
                }
            }
        } finally {
            file.delete()
        }
    }
}
