package com.github.pilotbellytspec.messagerequests

import com.aliucord.Http
import com.aliucord.Logger
import com.aliucord.Utils
import org.json.JSONArray
import org.json.JSONObject

class RequestApi(private val stash: RequestStore, private val toast: (String) -> Unit) {
    private val log = Logger("MessageRequests")

    fun sync(done: () -> Unit = {}) {
        Utils.threadPool.execute {
            try {
                Http.Request.newDiscordRNRequest("/users/@me/channels").execute().use { response ->
                    response.assertOk()
                    val body = response.text().trim { it <= ' ' }
                    if (body.startsWith("[")) {
                        stash.loadChannels(JSONArray(body))
                    } else {
                        val root = JSONObject(body)
                        root.optJSONArray("private_channels")?.let(stash::loadChannels)
                        root.optJSONArray("channels")?.let(stash::loadChannels)
                    }
                }
            } catch (err: Throwable) {
                log.warn("message request sync failed", err)
            } finally {
                Utils.mainThread.post(done)
            }
        }
    }

    fun accept(channelId: Long, done: () -> Unit = {}) {
        set(channelId, 2, "Request accepted.", done)
    }

    fun deny(channelId: Long, done: () -> Unit = {}) {
        Utils.threadPool.execute {
            try {
                Http.Request.newDiscordRNRequest("/channels/$channelId/recipients/@me", "DELETE")
                    .execute()
                    .use { it.assertOk() }
                stash.drop(channelId)
                Utils.mainThread.post {
                    toast("Request denied.")
                    done()
                }
            } catch (err: Throwable) {
                Utils.mainThread.post {
                    toast("Could not deny request.")
                    done()
                }
            }
        }
    }

    private fun set(channelId: Long, state: Int, ok: String, done: () -> Unit) {
        Utils.threadPool.execute {
            try {
                val body = JSONObject().put("consent_status", state)
                Http.Request.newDiscordRNRequest("/channels/$channelId/recipients/@me", "PUT")
                    .setHeader("Content-Type", "application/json")
                    .executeWithBody(body.toString())
                    .use { it.assertOk() }
                if (state == 2) stash.drop(channelId) else stash.add(channelId)
                Utils.mainThread.post {
                    toast(ok)
                    done()
                }
            } catch (err: Throwable) {
                log.warn("message request action rejected", err)
                Utils.mainThread.post {
                    toast("Discord rejected the request action.")
                    done()
                }
            }
        }
    }
}
