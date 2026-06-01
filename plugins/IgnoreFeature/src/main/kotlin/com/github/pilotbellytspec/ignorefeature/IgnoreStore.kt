package com.github.pilotbellytspec.ignorefeature

import com.aliucord.api.SettingsAPI
import com.discord.stores.StoreStream
import org.json.JSONArray
import rx.Observable
import rx.subjects.BehaviorSubject

class IgnoreStore(private val settings: SettingsAPI) {
    private val listeners = mutableListOf<() -> Unit>()
    private var ignoredIds = load().toMutableSet()
    private val ignoredSubject = BehaviorSubject.k0<Set<Long>>()

    init {
        ignoredSubject.onNext(ignoredIds.toSet())
    }

    fun listen(listener: () -> Unit) {
        listeners += listener
    }

    fun all(): Set<Long> = ignoredIds.toSet()

    fun observe(): Observable<Set<Long>> = ignoredSubject

    fun contains(userId: Long): Boolean = ignoredIds.contains(userId)

    fun replace(userIds: Collection<Long>) {
        val next = userIds.toMutableSet()
        if (next == ignoredIds) return
        ignoredIds = next
        save()
    }

    fun set(userId: Long, ignored: Boolean) {
        val changed = if (ignored) ignoredIds.add(userId) else ignoredIds.remove(userId)
        if (changed) save()
    }

    private fun load(): Set<Long> {
        val keys = settings.getAllKeys()
            .filter { it == GLOBAL_STORAGE_KEY || it.startsWith("$ACCOUNT_STORAGE_PREFIX:") }
            .ifEmpty { listOf(storageKey(), GLOBAL_STORAGE_KEY) }

        val storedIds = mutableSetOf<Long>()
        keys.forEach { key ->
            storedIds += parseIds(settings.getString(key, "[]"))
        }
        return storedIds
    }

    private fun parseIds(json: String): Set<Long> {
        return try {
            val savedIds = JSONArray(json)
            val parsedIds = mutableSetOf<Long>()
            for (index in 0 until savedIds.length()) {
                savedIds.optString(index).toLongOrNull()?.let(parsedIds::add)
            }
            parsedIds
        } catch (_: Throwable) {
            emptySet()
        }
    }

    private fun save() {
        val savedIds = JSONArray()
        ignoredIds.sorted().forEach { savedIds.put(it.toString()) }
        val json = savedIds.toString()
        settings.setString(GLOBAL_STORAGE_KEY, json)
        val accountKey = storageKey()
        if (accountKey != GLOBAL_STORAGE_KEY) {
            settings.setString(accountKey, json)
        }
        ignoredSubject.onNext(ignoredIds.toSet())
        listeners.forEach { it() }
    }

    private fun storageKey(): String {
        val userId = try {
            StoreStream.getUsers().me.id
        } catch (_: Throwable) {
            0L
        }
        return if (userId == 0L) GLOBAL_STORAGE_KEY else "$ACCOUNT_STORAGE_PREFIX:$userId"
    }

    private companion object {
        const val GLOBAL_STORAGE_KEY = "ignoredUsers"
        const val ACCOUNT_STORAGE_PREFIX = "ignoredUsers"
    }
}
