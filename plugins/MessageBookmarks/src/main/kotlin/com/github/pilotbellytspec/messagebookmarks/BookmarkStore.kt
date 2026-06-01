package com.github.pilotbellytspec.messagebookmarks

import com.aliucord.api.SettingsAPI
import com.discord.models.message.Message
import com.discord.stores.StoreStream
import org.json.JSONArray
import org.json.JSONObject

class BookmarkStore(private val settings: SettingsAPI) {
    private val listeners = mutableListOf<() -> Unit>()

    fun listen(listener: () -> Unit) {
        listeners += listener
    }

    fun all(): List<BookmarkRecord> =
        load().sortedWith(compareByDescending<BookmarkRecord> { it.dueAt ?: 0L }.thenByDescending { it.savedAt })

    fun get(channelId: Long, messageId: Long): BookmarkRecord? =
        load().firstOrNull { it.channelId == channelId && it.messageId == messageId }

    fun contains(channelId: Long, messageId: Long): Boolean = get(channelId, messageId) != null

    fun upsert(message: Message, dueAt: Long? = null): BookmarkRecord {
        val channel = try {
            StoreStream.getChannels().getChannel(message.channelId)
        } catch (_: Throwable) {
            null
        }
        val bookmark = BookmarkRecord(
            channelId = message.channelId,
            messageId = message.id,
            guildId = channel.readLong("guildId", "getGuildId", "i")?.takeIf { it != 0L },
            authorId = message.author?.id,
            authorName = message.author?.username,
            channelName = channel.readString("name", "getName", "p"),
            content = message.content.savedText(),
            savedAt = System.currentTimeMillis(),
            dueAt = dueAt,
        )
        upsert(bookmark)
        return bookmark
    }

    fun upsert(record: BookmarkRecord) {
        val savedBookmarks = load().filterNot { it.key == record.key }.toMutableList()
        savedBookmarks += record
        save(savedBookmarks)
    }

    fun setDueAt(channelId: Long, messageId: Long, dueAt: Long?) {
        val savedBookmarks = load().map {
            if (it.channelId == channelId && it.messageId == messageId) it.copy(dueAt = dueAt) else it
        }
        save(savedBookmarks)
    }

    fun remove(channelId: Long, messageId: Long) {
        save(load().filterNot { it.channelId == channelId && it.messageId == messageId })
    }

    private fun load(): List<BookmarkRecord> {
        val json = settings.getString(storageKey(), "[]")
        return try {
            val savedBookmarks = JSONArray(json)
            List(savedBookmarks.length()) { index ->
                savedBookmarks.getJSONObject(index).toRecord()
            }
        } catch (_: Throwable) {
            emptyList()
        }
    }

    private fun save(savedBookmarks: List<BookmarkRecord>) {
        val json = JSONArray()
        savedBookmarks.forEach { json.put(it.toJson()) }
        settings.setString(storageKey(), json.toString())
        listeners.forEach { it() }
    }

    private fun storageKey(): String {
        val userId = try {
            StoreStream.getUsers().me.id
        } catch (_: Throwable) {
            0L
        }
        return "savedMessages:$userId"
    }
}

private fun BookmarkRecord.toJson() = JSONObject()
    .put("channel_id", channelId.toString())
    .put("message_id", messageId.toString())
    .put("guild_id", guildId?.toString())
    .put("author_id", authorId?.toString())
    .put("author_name", authorName)
    .put("channel_name", channelName)
    .put("content", content)
    .put("saved_at", savedAt)
    .put("due_at", dueAt ?: JSONObject.NULL)

private fun JSONObject.toRecord() = BookmarkRecord(
    channelId = optString("channel_id").toLongOrNull() ?: optLong("channel_id"),
    messageId = optString("message_id").toLongOrNull() ?: optLong("message_id"),
    guildId = optString("guild_id").toLongOrNull().takeIf { !isNull("guild_id") },
    authorId = optString("author_id").toLongOrNull().takeIf { !isNull("author_id") },
    authorName = optCleanString("author_name"),
    channelName = optCleanString("channel_name"),
    content = optCleanString("content"),
    savedAt = optLong("saved_at", System.currentTimeMillis()),
    dueAt = if (isNull("due_at")) null else optLong("due_at"),
)

private fun JSONObject.optCleanString(key: String): String? =
    if (has(key) && !isNull(key)) optString(key).savedText() else null

private fun String?.savedText(): String? {
    val cleanText = this?.trim() ?: return null
    return if (cleanText.isEmpty() || cleanText == "null") null else cleanText
}

private fun Any?.readString(vararg names: String): String? =
    poke(*names) as? String

private fun Any?.readLong(vararg names: String): Long? =
    when (val reflectedValue = poke(*names)) {
        is Long -> reflectedValue
        is Number -> reflectedValue.toLong()
        else -> null
    }

private fun Any?.poke(vararg names: String): Any? {
    val target = this ?: return null
    names.forEach { name ->
        var cls: Class<*>? = target.javaClass
        while (cls != null) {
            val currentClass = cls
            try {
                val field = currentClass.getDeclaredField(name).apply { isAccessible = true }
                return field[target]
            } catch (_: Throwable) {
            }
            try {
                val method = currentClass.getDeclaredMethod(name).apply { isAccessible = true }
                return method.invoke(target)
            } catch (_: Throwable) {
            }
            cls = currentClass.superclass
        }
    }
    return null
}
