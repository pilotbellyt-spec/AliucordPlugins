package com.github.pilotbellytspec.ignorefeature

import com.aliucord.Http
import com.aliucord.Logger
import com.aliucord.Utils
import org.json.JSONArray
import org.json.JSONObject

class IgnoreSync(
    private val quietList: IgnoreStore,
    private val toast: (String) -> Unit,
) {
    private val log = Logger("IgnoreFeature")
    @Volatile
    private var hasGatewaySnapshot = false

    fun fetch(onDone: (() -> Unit)? = null) {
        Utils.threadPool.execute {
            try {
                val snapshot = fetchRelationships(API_V9_RELATIONSHIPS, "v9")
                if (snapshot != null && snapshot.sawIgnoreState && shouldApplyRestSnapshot(snapshot)) {
                    quietList.replace(snapshot.ignored)
                }
            } catch (error: Throwable) {
                warnUser("Could not load ignored users", error)
            }
            onDone?.let { Utils.mainThread.post(it) }
        }
    }

    fun fetchDelayed(delayMs: Long) {
        Utils.threadPool.execute {
            Thread.sleep(delayMs)
            fetch()
        }
    }

    fun setIgnored(userId: Long, ignored: Boolean, onDone: () -> Unit) {
        quietList.set(userId, ignored)
        Utils.threadPool.execute {
            try {
                val method = if (ignored) "PUT" else "DELETE"
                Http.Request.newDiscordRNRequest("/users/@me/relationships/$userId/ignore", method)
                    .execute()
                    .use { it.assertOk() }
                Utils.mainThread.post(onDone)
            } catch (error: Throwable) {
                quietList.set(userId, !ignored)
                warnUser(if (ignored) "Could not ignore user" else "Could not unignore user", error)
                Utils.mainThread.post(onDone)
            }
        }
    }

    fun applyRelationshipEvent(data: JSONObject, removed: Boolean) {
        val relationship = data.optJSONObject("relationship") ?: data
        val userId = relationship.optString("id").toLongOrNull()
            ?: relationship.optJSONObject("user")?.optString("id")?.toLongOrNull()
            ?: return
        val ignored = relationship.ignoreValue() ?: return

        if (removed) {
            if (!ignored) {
                quietList.set(userId, false)
            }
            return
        }
        quietList.set(userId, ignored)
    }

    fun applyConnectionOpen(data: JSONObject) {
        val relationships = data.optJSONArray("relationships") ?: return
        val snapshot = parseRelationshipArray(relationships)
        if (snapshot != null) {
            log.info("Relationship gateway snapshot fetched ${snapshot.total} rows; saw ignore state=${snapshot.sawIgnoreState}; ignored rows=${snapshot.ignored.size}")
        }
        if (snapshot != null && snapshot.sawIgnoreState) {
            hasGatewaySnapshot = true
            quietList.replace(snapshot.ignored)
        }
    }

    private fun parseRelationships(responseBody: String): RelationshipSnapshot? {
        val trimmed = responseBody.trim()
        val relationships = when {
            trimmed.startsWith("[") -> JSONArray(trimmed)
            trimmed.startsWith("{") -> JSONObject(trimmed).optJSONArray("relationships")
            else -> null
        } ?: return null

        return parseRelationshipArray(relationships)
    }

    private fun parseRelationshipArray(relationships: JSONArray): RelationshipSnapshot? {
        val ignored = mutableSetOf<Long>()
        var sawIgnoreState = false
        for (index in 0 until relationships.length()) {
            val relationship = relationships.optJSONObject(index) ?: continue
            val isIgnored = relationship.ignoreValue() ?: continue
            sawIgnoreState = true
            if (!isIgnored) continue
            val userId = relationship.optString("id").toLongOrNull()
                ?: relationship.optJSONObject("user")?.optString("id")?.toLongOrNull()
            userId?.let(ignored::add)
        }
        return RelationshipSnapshot(ignored, sawIgnoreState, relationships.length())
    }

    private fun warnUser(prefix: String, error: Throwable) {
        val detail = error.message.orEmpty()
        val toastText = when {
            "401" in detail || "403" in detail || "404" in detail -> "$prefix. Discord rejected the ignore API for this account."
            else -> prefix
        }
        Utils.mainThread.post { toast(toastText) }
    }

    private fun shouldApplyRestSnapshot(snapshot: RelationshipSnapshot): Boolean {
        if (snapshot.ignored.isNotEmpty()) return true
        if (!hasGatewaySnapshot || quietList.all().isEmpty()) return true

        log.info("Relationship sync REST snapshot omitted ignored-only rows; keeping gateway ignore state.")
        return false
    }

    private fun fetchRelationships(route: String, source: String): RelationshipSnapshot? {
        Http.Request.newDiscordRNRequest(route).execute().use { response ->
            response.assertOk()
            val snapshot = parseRelationships(response.text())
            if (snapshot != null) {
                log.info("Relationship sync $source fetched ${snapshot.total} rows; saw ignore state=${snapshot.sawIgnoreState}; ignored rows=${snapshot.ignored.size}")
            }
            return snapshot
        }
    }

    private fun JSONObject.ignoreValue(): Boolean? =
        when {
            has("user_ignored") -> optBoolean("user_ignored", false)
            has("userIgnored") -> optBoolean("userIgnored", false)
            else -> null
        }

    private data class RelationshipSnapshot(
        val ignored: Set<Long>,
        val sawIgnoreState: Boolean,
        val total: Int,
    )

    private companion object {
        const val API_V9_RELATIONSHIPS = "/users/@me/relationships"
    }
}
