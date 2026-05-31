package com.github.pilotbellytspec.ignorefeature

import com.aliucord.Http
import com.aliucord.Logger
import com.aliucord.Utils
import org.json.JSONArray
import org.json.JSONObject

class IgnoreSync(
    private val store: IgnoreStore,
    private val toast: (String) -> Unit,
) {
    private val logger = Logger("IgnoreFeature")
    @Volatile
    private var hasGatewaySnapshot = false

    fun fetch(onDone: (() -> Unit)? = null) {
        Utils.threadPool.execute {
            runCatching {
                val snapshot = fetchRelationships(API_V9_RELATIONSHIPS, "v9")
                if (snapshot != null && snapshot.sawIgnoreState && shouldApplyRestSnapshot(snapshot)) {
                    store.replace(snapshot.ignored)
                }
            }.onFailure {
                handleFailure("Could not load ignored users", it)
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
        store.set(userId, ignored)
        Utils.threadPool.execute {
            runCatching {
                val method = if (ignored) "PUT" else "DELETE"
                Http.Request.newDiscordRNRequest("/users/@me/relationships/$userId/ignore", method)
                    .execute()
                    .use { it.assertOk() }
                Utils.mainThread.post(onDone)
            }.onFailure {
                store.set(userId, !ignored)
                handleFailure(if (ignored) "Could not ignore user" else "Could not unignore user", it)
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
                store.set(userId, false)
            }
            return
        }
        store.set(userId, ignored)
    }

    fun applyConnectionOpen(data: JSONObject) {
        val relationships = data.optJSONArray("relationships") ?: return
        val snapshot = parseRelationshipArray(relationships)
        if (snapshot != null) {
            logger.info("Relationship gateway snapshot fetched ${snapshot.total} rows; saw ignore state=${snapshot.sawIgnoreState}; ignored rows=${snapshot.ignored.size}")
        }
        if (snapshot != null && snapshot.sawIgnoreState) {
            hasGatewaySnapshot = true
            store.replace(snapshot.ignored)
        }
    }

    private fun parseRelationships(raw: String): RelationshipSnapshot? {
        val root = raw.trim()
        val array = when {
            root.startsWith("[") -> JSONArray(root)
            root.startsWith("{") -> JSONObject(root).optJSONArray("relationships")
            else -> null
        } ?: return null

        return parseRelationshipArray(array)
    }

    private fun parseRelationshipArray(array: JSONArray): RelationshipSnapshot? {
        val ignored = mutableSetOf<Long>()
        var sawIgnoreState = false
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            val isIgnored = item.ignoreValue() ?: continue
            sawIgnoreState = true
            if (!isIgnored) continue
            val userId = item.optString("id").toLongOrNull()
                ?: item.optJSONObject("user")?.optString("id")?.toLongOrNull()
            userId?.let(ignored::add)
        }
        return RelationshipSnapshot(ignored, sawIgnoreState, array.length())
    }

    private fun handleFailure(prefix: String, error: Throwable) {
        val message = error.message.orEmpty()
        val text = when {
            "401" in message || "403" in message || "404" in message -> "$prefix. Discord rejected the ignore API for this account."
            else -> prefix
        }
        Utils.mainThread.post { toast(text) }
    }

    private fun shouldApplyRestSnapshot(snapshot: RelationshipSnapshot): Boolean {
        if (snapshot.ignored.isNotEmpty()) return true
        if (!hasGatewaySnapshot || store.all().isEmpty()) return true

        logger.info("Relationship sync REST snapshot omitted ignored-only rows; keeping gateway ignore state.")
        return false
    }

    private fun fetchRelationships(route: String, source: String): RelationshipSnapshot? {
        Http.Request.newDiscordRNRequest(route).execute().use { response ->
            response.assertOk()
            val snapshot = parseRelationships(response.text())
            if (snapshot != null) {
                logger.info("Relationship sync $source fetched ${snapshot.total} rows; saw ignore state=${snapshot.sawIgnoreState}; ignored rows=${snapshot.ignored.size}")
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
