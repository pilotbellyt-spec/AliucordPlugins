package com.github.pilotbellytspec.messagebookmarks

import com.aliucord.Http
import com.aliucord.Utils
import com.aliucord.api.SettingsAPI
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

class BookmarkSync(
    private val settings: SettingsAPI,
    private val store: BookmarkStore,
    private val toast: (String) -> Unit,
) {
    val enabled: Boolean
        get() = settings.getInt("mode", MODE_LOCAL) == MODE_SYNC

    fun fetch() {
        if (!enabled) return
        Utils.threadPool.execute {
            runCatching {
                Http.Request.newDiscordRNRequest("/users/@me/saved-messages").execute().use { response ->
                    val root = JSONObject(response.text())
                    val results = root.optJSONArray("results") ?: return@use
                    var index = 0
                    while (index < results.length()) {
                        parseSavedMessage(results.optJSONObject(index))?.let(store::upsert)
                        index++
                    }
                }
            }.onFailure { handleFailure("Could not load synced bookmarks", it) }
        }
    }

    fun create(record: BookmarkRecord) {
        if (!enabled) return
        Utils.threadPool.execute {
            runCatching {
                val body = JSONObject()
                record.dueAt?.let { body.put("due_at", isoDate(it)) }
                Http.Request.newDiscordRNRequest(
                    "/users/@me/saved-messages/${record.channelId}/${record.messageId}",
                    "PUT",
                ).executeWithBody(body.toString()).use { it.assertOk() }
            }.onFailure { handleFailure("Could not sync bookmark", it) }
        }
    }

    fun delete(channelId: Long, messageId: Long) {
        if (!enabled) return
        Utils.threadPool.execute {
            runCatching {
                Http.Request.newDiscordRNRequest(
                    "/users/@me/saved-messages/$channelId/$messageId",
                    "DELETE",
                ).execute().use { it.assertOk() }
            }.onFailure { handleFailure("Could not sync bookmark removal", it) }
        }
    }

    fun applyGatewayCreate(raw: JSONObject) {
        parseSavedMessage(raw)?.let(store::upsert)
    }

    fun applyGatewayDelete(raw: JSONObject) {
        val data = raw.optJSONObject("save_data") ?: raw
        val channelId = data.optString("channel_id").toLongOrNull() ?: return
        val messageId = data.optString("message_id").toLongOrNull() ?: return
        store.remove(channelId, messageId)
    }

    private fun parseSavedMessage(root: JSONObject?): BookmarkRecord? {
        root ?: return null
        val message = root.optJSONObject("message")
        val data = root.optJSONObject("save_data") ?: root
        val channelId = data.optString("channel_id").toLongOrNull()
            ?: message?.optString("channel_id")?.toLongOrNull()
            ?: return null
        val messageId = data.optString("message_id").toLongOrNull()
            ?: message?.optString("id")?.toLongOrNull()
            ?: return null
        val author = message?.optJSONObject("author")
        return BookmarkRecord(
            channelId = channelId,
            messageId = messageId,
            guildId = data.optString("guild_id").toLongOrNull(),
            authorId = data.optString("author_id").toLongOrNull() ?: author?.optString("id")?.toLongOrNull(),
            authorName = author?.optString("global_name").clean() ?: author?.optString("username").clean(),
            channelName = data.optJSONObject("channel_summary")?.optString("name").clean(),
            content = message?.optString("content").clean()
                ?: data.optJSONObject("message_summary")?.optString("content").clean(),
            savedAt = parseIso(data.optString("saved_at")) ?: System.currentTimeMillis(),
            dueAt = parseIso(data.optString("due_at")),
        )
    }

    private fun handleFailure(prefix: String, error: Throwable) {
        val message = error.message.orEmpty()
        val text = when {
            "30074" in message -> "Bookmarks are full (200 max)."
            "401" in message || "403" in message || "404" in message -> "$prefix. Discord rejected Sync Mode, so try Local Mode."
            else -> prefix
        }
        Utils.mainThread.post { toast(text) }
    }

    companion object {
        const val MODE_LOCAL = 0
        const val MODE_SYNC = 1

        private val isoFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }

        fun isoDate(timestamp: Long): String = synchronized(isoFormat) {
            isoFormat.format(Date(timestamp))
        }

        fun parseIso(value: String?): Long? {
            val clean = value.clean() ?: return null
            return synchronized(isoFormat) {
                runCatching { isoFormat.parse(clean)?.time }.getOrNull()
            }
        }
    }
}

private fun String?.clean(): String? =
    this?.trim()?.takeIf { it.isNotEmpty() && it != "null" }
