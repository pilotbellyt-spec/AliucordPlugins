package com.github.pilotbellytspec.messagerequests

import com.aliucord.api.SettingsAPI
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.CopyOnWriteArraySet

class RequestStore(private val settings: SettingsAPI) {
    private val ids = CopyOnWriteArraySet<Long>()

    init {
        load()
    }

    fun has(channelId: Long) = ids.contains(channelId)

    fun all(): Set<Long> = ids.toSet()

    fun add(channelId: Long) {
        if (channelId == 0L || !ids.add(channelId)) return
        save()
    }

    fun drop(channelId: Long) {
        if (!ids.remove(channelId)) return
        save()
    }

    fun update(channel: JSONObject?) {
        channel ?: return
        val id = channel.optString("id").toLongOrNull() ?: return
        val state = channel.opt("consent_status")
        val stateText = state?.toString()?.lowercase().orEmpty()
        val pending = state == 1 || stateText == "1" || stateText == "pending" || stateText == "untrusted" || stateText == "request"
        val clear = state == 0 || state == 2 || stateText == "0" || stateText == "2" || stateText == "accepted" || stateText == "unspecified"
        if (pending || channel.isReq()) add(id) else if (clear) drop(id)
    }

    fun loadChannels(channels: JSONArray) {
        val next = linkedSetOf<Long>()
        for (i in 0 until channels.length()) {
            val item = channels.optJSONObject(i) ?: continue
            val id = item.optString("id").toLongOrNull() ?: continue
            if (item.isReq() || item.opt("consent_status") == 1) {
                next.add(id)
            }
        }
        if (next == ids) return
        ids.clear()
        ids.addAll(next)
        save()
    }

    fun ingest(raw: JSONObject) {
        raw.optJSONArray("private_channels")?.eachObj(::update)
        raw.optJSONArray("channels")?.eachObj(::update)
        update(raw.optJSONObject("channel"))
        if (raw.has("id")) update(raw)
    }

    private fun load() {
        val saved = settings.getString("pendingChannels", "[]") ?: "[]"
        val arr = runCatching { JSONArray(saved) }.getOrNull() ?: return
        for (i in 0 until arr.length()) {
            arr.optString(i).toLongOrNull()?.let(ids::add)
        }
    }

    private fun save() {
        val arr = JSONArray()
        ids.forEach { arr.put(it.toString()) }
        settings.setString("pendingChannels", arr.toString())
    }
}

private fun JSONObject.isReq(): Boolean {
    return optBoolean("is_message_request", false) ||
        optBoolean("is_spam", false) ||
        optBoolean("is_message_request_spam", false) ||
        hasTime("is_message_request_timestamp") ||
        hasTime("message_request_timestamp")
}

private fun JSONObject.hasTime(name: String): Boolean {
    val raw = opt(name) ?: return false
    if (raw == JSONObject.NULL) return false
    val txt = raw.toString().trim { it <= ' ' }
    return txt.isNotEmpty() && txt != "null" && txt != "0"
}

private fun JSONArray.eachObj(block: (JSONObject) -> Unit) {
    for (i in 0 until length()) optJSONObject(i)?.let(block)
}
