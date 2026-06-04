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
        if (channel.has("is_message_request")) {
            if (channel.isReq()) add(id) else drop(id)
            return
        }
        when {
            channel.isClear() -> drop(id)
            channel.isReq() -> add(id)
        }
    }

    fun loadChannels(channels: JSONArray) {
        var changed = false
        for (i in 0 until channels.length()) {
            val item = channels.optJSONObject(i) ?: continue
            val id = item.optString("id").toLongOrNull() ?: continue
            if (item.has("is_message_request")) {
                if (item.isReq()) {
                    if (ids.add(id)) changed = true
                } else if (ids.remove(id)) {
                    changed = true
                }
            } else if (item.isClear()) {
                if (ids.remove(id)) changed = true
            }
        }
        if (changed) save()
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

private fun JSONObject.isClear(): Boolean {
    return when (state()) {
        "0", "2", "accepted", "unspecified" -> true
        else -> false
    }
}

private fun JSONObject.state(): String {
    val raw = opt("consent_status") ?: return ""
    return if (raw is Number) raw.toInt().toString() else raw.toString().lowercase()
}

private fun JSONObject.isReq(): Boolean {
    return optBoolean("is_message_request", false)
}

private fun JSONArray.eachObj(block: (JSONObject) -> Unit) {
    for (i in 0 until length()) optJSONObject(i)?.let(block)
}
