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

        val values = mutableSetOf<Long>()
        keys.forEach { key ->
            values += parseIds(settings.getString(key, "[]"))
        }
        return values
    }

    private fun parseIds(raw: String): Set<Long> {
        return runCatching {
            val array = JSONArray(raw)
            val values = mutableSetOf<Long>()
            for (index in 0 until array.length()) {
                array.optString(index).toLongOrNull()?.let(values::add)
            }
            values
        }.getOrDefault(emptySet())
    }

    private fun save() {
        val array = JSONArray()
        ignoredIds.sorted().forEach { array.put(it.toString()) }
        val raw = array.toString()
        settings.setString(GLOBAL_STORAGE_KEY, raw)
        val accountKey = storageKey()
        if (accountKey != GLOBAL_STORAGE_KEY) {
            settings.setString(accountKey, raw)
        }
        ignoredSubject.onNext(ignoredIds.toSet())
        listeners.forEach { it() }
    }

    private fun storageKey(): String {
        val userId = runCatching { StoreStream.getUsers().me.id }.getOrDefault(0L)
        return if (userId == 0L) GLOBAL_STORAGE_KEY else "$ACCOUNT_STORAGE_PREFIX:$userId"
    }

    private companion object {
        const val GLOBAL_STORAGE_KEY = "ignoredUsers"
        const val ACCOUNT_STORAGE_PREFIX = "ignoredUsers"
    }
}
