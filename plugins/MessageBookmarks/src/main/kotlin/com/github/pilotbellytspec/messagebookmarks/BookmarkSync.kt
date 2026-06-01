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
    private val stash: BookmarkStore,
    private val toast: (String) -> Unit,
) {
    val enabled: Boolean
        get() = settings.getInt("mode", MODE_LOCAL) == MODE_SYNC

    fun fetch() {
        if (!enabled) return
        Utils.threadPool.execute {
            try {
                Http.Request.newDiscordRNRequest("/users/@me/saved-messages").execute().use { response ->
                    val savedMessages = JSONObject(response.text()).optJSONArray("results") ?: return@use
                    for (index in 0 until savedMessages.length()) {
                        parseSavedMessage(savedMessages.optJSONObject(index))?.let(stash::upsert)
                    }
                }
            } catch (error: Throwable) {
                syncToast("Could not load synced bookmarks", error)
            }
        }
    }

    fun create(record: BookmarkRecord) {
        if (!enabled) return
        Utils.threadPool.execute {
            try {
                val payload = JSONObject()
                record.dueAt?.let { payload.put("due_at", isoDate(it)) }
                Http.Request.newDiscordRNRequest(
                    "/users/@me/saved-messages/${record.channelId}/${record.messageId}",
                    "PUT",
                ).executeWithBody(payload.toString()).use { it.assertOk() }
            } catch (error: Throwable) {
                syncToast("Could not sync bookmark", error)
            }
        }
    }

    fun delete(channelId: Long, messageId: Long) {
        if (!enabled) return
        Utils.threadPool.execute {
            try {
                Http.Request.newDiscordRNRequest(
                    "/users/@me/saved-messages/$channelId/$messageId",
                    "DELETE",
                ).execute().use { it.assertOk() }
            } catch (error: Throwable) {
                syncToast("Could not sync bookmark removal", error)
            }
        }
    }

    fun applyGatewayCreate(raw: JSONObject) {
        parseSavedMessage(raw)?.let(stash::upsert)
    }

    fun applyGatewayDelete(raw: JSONObject) {
        val saveData = raw.optJSONObject("save_data") ?: raw
        val channelId = saveData.optString("channel_id").toLongOrNull() ?: return
        val messageId = saveData.optString("message_id").toLongOrNull() ?: return
        stash.remove(channelId, messageId)
    }

    private fun parseSavedMessage(savedMessage: JSONObject?): BookmarkRecord? {
        savedMessage ?: return null
        val messageJson = savedMessage.optJSONObject("message")
        val saveData = savedMessage.optJSONObject("save_data") ?: savedMessage
        val channelId = saveData.optString("channel_id").toLongOrNull()
            ?: messageJson?.optString("channel_id")?.toLongOrNull()
            ?: return null
        val messageId = saveData.optString("message_id").toLongOrNull()
            ?: messageJson?.optString("id")?.toLongOrNull()
            ?: return null
        val author = messageJson?.optJSONObject("author")
        return BookmarkRecord(
            channelId = channelId,
            messageId = messageId,
            guildId = saveData.optString("guild_id").toLongOrNull(),
            authorId = saveData.optString("author_id").toLongOrNull() ?: author?.optString("id")?.toLongOrNull(),
            authorName = author?.optString("global_name").usableText() ?: author?.optString("username").usableText(),
            channelName = saveData.optJSONObject("channel_summary")?.optString("name").usableText(),
            content = messageJson?.optString("content").usableText()
                ?: saveData.optJSONObject("message_summary")?.optString("content").usableText(),
            savedAt = parseIso(saveData.optString("saved_at")) ?: System.currentTimeMillis(),
            dueAt = parseIso(saveData.optString("due_at")),
        )
    }

    private fun syncToast(prefix: String, error: Throwable) {
        val detail = error.message.orEmpty()
        val toastText = when {
            "30074" in detail -> "Bookmarks are full (200 max)."
            "401" in detail || "403" in detail || "404" in detail -> "$prefix. Discord rejected Sync Mode, so try Local Mode."
            else -> prefix
        }
        Utils.mainThread.post { toast(toastText) }
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
            val clean = value.usableText() ?: return null
            return synchronized(isoFormat) {
                try {
                    isoFormat.parse(clean)?.time
                } catch (_: Throwable) {
                    null
                }
            }
        }
    }
}

private fun String?.usableText(): String? =
    this?.trim()?.takeIf { it.isNotEmpty() && it != "null" }
