package com.github.pilotbellytspec.serversettingsfix

import android.content.Context
import com.aliucord.Http
import com.aliucord.Logger
import com.aliucord.Utils
import com.aliucord.annotations.AliucordPlugin
import com.aliucord.entities.Plugin
import com.aliucord.patcher.after
import com.aliucord.patcher.before
import com.aliucord.patcher.instead
import com.aliucord.utils.GsonUtils
import com.aliucord.utils.GsonUtils.fromJson
import com.aliucord.utils.ReflectUtils
import com.discord.models.domain.ModelAuditLog
import com.discord.models.domain.ModelAuditLogEntry
import com.discord.models.domain.ModelBan
import com.discord.models.domain.ModelGuildIntegration
import com.discord.models.domain.ModelInvite
import com.discord.models.domain.ModelWebhook
import com.discord.stores.Dispatcher
import com.discord.stores.StoreAuditLog
import com.discord.stores.StoreBans
import com.discord.stores.StoreInviteSettings
import de.robv.android.xposed.XC_MethodReplacement
import org.json.JSONArray
import org.json.JSONObject
import java.lang.reflect.Method
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

@AliucordPlugin(requiresRestart = false)
@Suppress("unused")
class ServerSettingsFix : Plugin() {
    private val pluginLog = Logger("ServerSettingsFix")
    private val pendingBanGuilds = ConcurrentHashMap.newKeySet<Long>()
    private lateinit var auditSuccessMethod: Method

    init {
        settingsTab = SettingsTab(PluginSettings::class.java, SettingsTab.Type.BOTTOM_SHEET).withArgs(settings)
    }

    override fun start(context: Context) {
        auditSuccessMethod = StoreAuditLog::class.java
            .getDeclaredMethod("handleFetchSuccess", Long::class.javaPrimitiveType, ModelAuditLog::class.java)
            .apply { isAccessible = true }

        bans()
        auditLog()
        inviteDurationText()
        inviteDefaults()
    }

    override fun stop(context: Context) {
        patcher.unpatchAll()
        pendingBanGuilds.clear()
    }

    private fun bans() {
        if (!settings.getBool("fixBans", true)) return

        patcher.after<StoreBans>("observeBans", Long::class.javaPrimitiveType!!) {
            val server = it.args[0] as Long
            val bansStore = it.thisObject as StoreBans
            fetchBansOnce(bansStore, server)
        }
    }

    private fun inviteDurationText() {
        patcher.patch(
            Class.forName("com.discord.utilities.resources.DurationUtilsKt").getDeclaredMethod(
                "formatInviteExpireAfterString",
                Context::class.java,
                Int::class.javaPrimitiveType,
            ),
            object : XC_MethodReplacement() {
                override fun replaceHookedMethod(param: MethodHookParam): Any {
                    return formatInviteDuration(param.args[1] as Int)
                }
            },
        )
    }

    private fun formatInviteDuration(seconds: Int): CharSequence {
        if (seconds == 0) return "Never"
        val (amount, unit) = when {
            seconds % SECONDS_PER_MONTH == 0 -> seconds / SECONDS_PER_MONTH to "month"
            seconds % SECONDS_PER_WEEK == 0 -> seconds / SECONDS_PER_WEEK to "week"
            seconds % SECONDS_PER_DAY == 0 -> seconds / SECONDS_PER_DAY to "day"
            seconds % SECONDS_PER_HOUR == 0 -> seconds / SECONDS_PER_HOUR to "hour"
            seconds % SECONDS_PER_MINUTE == 0 -> seconds / SECONDS_PER_MINUTE to "minute"
            else -> seconds to "second"
        }
        val suffix = if (amount == 1) unit else "${unit}s"
        return "$amount $suffix"
    }

    private fun auditLog() {
        if (!settings.getBool("fixAuditLog", true)) return

        patcher.instead<StoreAuditLog>(
            "fetchAuditLogs",
            Long::class.javaPrimitiveType!!,
            StoreAuditLog.AuditLogFilter::class.java,
        ) {
            val server = it.args[0] as Long
            val filter = it.args[1] as StoreAuditLog.AuditLogFilter
            val beforeEntryId = auditBefore(this)
            if (!prepareAuditLogLoad(this, server, filter, beforeEntryId)) return@instead null

            Utils.threadPool.execute {
                try {
                    val auditLog = fetchAuditLog(server, filter, beforeEntryId)
                    ensureAuditEntries(auditLog)
                    auditSuccessMethod.invoke(this, server, auditLog)
                } catch (error: Throwable) {
                    pluginLog.warn("Could not load audit log for $server", error)
                    showToast("Could not load audit log.")
                    auditSuccessMethod.invoke(this, server, blankAuditLog())
                }
            }
            null
        }
    }

    private fun inviteDefaults() {
        if (!settings.getBool("shortInvites", true)) return

        patcher.after<StoreInviteSettings>("getInviteSettings", Long::class.javaPrimitiveType!!) {
            val inviteSettings = it.result as? ModelInvite.Settings
            it.result = inviteSettings?.mergeMaxAge(INVITE_MAX_AGE_SECONDS) ?: ModelInvite.Settings(INVITE_MAX_AGE_SECONDS)
        }

        patcher.before<StoreInviteSettings>(
            "generateInvite",
            Long::class.javaPrimitiveType!!,
            ModelInvite.Settings::class.java,
        ) {
            val inviteSettings = it.args[1] as? ModelInvite.Settings
            it.args[1] = inviteSettings?.mergeMaxAge(INVITE_MAX_AGE_SECONDS) ?: ModelInvite.Settings(INVITE_MAX_AGE_SECONDS)
        }
    }

    private fun fetchBansOnce(bansStore: StoreBans, server: Long) {
        if (!pendingBanGuilds.add(server)) return

        Utils.threadPool.execute {
            try {
                val bans = fetchAllBans(server)
                putBans(bansStore, server, bans)
            } catch (error: Throwable) {
                pendingBanGuilds.remove(server)
                pluginLog.warn("Could not load bans for $server", error)
                showToast("Could not load bans.")
                putBans(bansStore, server, emptyList())
            }
        }
    }

    private fun fetchAllBans(server: Long): List<ModelBan> {
        val bans = mutableListOf<ModelBan>()
        var after: Long? = null
        var pages = 0

        do {
            val bansRoute = buildString {
                append("/guilds/")
                append(server)
                append("/bans?limit=")
                append(BANS_PAGE_SIZE)
                if (after != null) {
                    append("&after=")
                    append(after)
                }
            }
            val banPage = Http.Request.newDiscordRNRequest(bansRoute).execute().use { response ->
                response.assertOk()
                response.json(GsonUtils.gsonRestApi, Array<ModelBan>::class.java).toList()
            }
            bans += banPage.filter { it.user != null }
            after = banPage.lastOrNull()?.user?.id
            pages++
        } while (banPage.size == BANS_PAGE_SIZE && after != null && pages < MAX_BAN_PAGES)

        return bans
    }

    @Suppress("UNCHECKED_CAST")
    private fun putBans(bansStore: StoreBans, server: Long, bans: List<ModelBan>) {
        val dispatcher = ReflectUtils.getField(bansStore, "dispatcher") as Dispatcher
        dispatcher.schedule {
            val bannedUsers = ReflectUtils.getField(bansStore, "bannedUsers") as HashMap<Long, HashMap<Long, ModelBan>>
            val guildBans = HashMap<Long, ModelBan>()
            bans.forEach { ban ->
                val banned = ban.user ?: return@forEach
                guildBans[banned.id] = ban
            }
            bannedUsers[server] = guildBans
            bansStore.markChanged()
        }
    }

    private fun prepareAuditLogLoad(
        store: StoreAuditLog,
        guildId: Long,
        filter: StoreAuditLog.AuditLogFilter,
        beforeEntryId: Long?,
    ): Boolean {
        val state = ReflectUtils.getField(store, "state") as StoreAuditLog.AuditLogState
        if (ReflectUtils.getField(store, "cutoffTimestamp") == null) {
            ReflectUtils.setField(store, "cutoffTimestamp", 0L)
        }
        if (beforeEntryId == ReflectUtils.getField(store, "cutoffTimestamp") as Long?) return false

        ReflectUtils.setField(store, "cutoffTimestamp", beforeEntryId)
        ReflectUtils.setField(
            store,
            "state",
            state.copy(
                guildId,
                state.users,
                state.entries,
                state.webhooks,
                state.integrations,
                state.guildScheduledEvents,
                state.threads,
                state.selectedItemId,
                filter,
                state.deletedTargets,
                true,
            ),
        )
        store.markChanged()
        return true
    }

    private fun auditBefore(store: StoreAuditLog): Long? {
        val state = ReflectUtils.getField(store, "state") as StoreAuditLog.AuditLogState
        return state.entries?.lastOrNull()?.id
    }

    private fun fetchAuditLog(
        guildId: Long,
        filter: StoreAuditLog.AuditLogFilter,
        beforeEntryId: Long?,
    ): ModelAuditLog {
        val responseBody = Http.Request.newDiscordRNRequest(buildAuditLogRoute(guildId, filter, beforeEntryId))
            .execute()
            .use { response ->
                response.assertOk()
                response.text()
            }
        return GsonUtils.gsonRestApi.fromJson(sanitizeAuditLogJson(responseBody), ModelAuditLog::class.java)
    }

    private fun buildAuditLogRoute(
        guildId: Long,
        filter: StoreAuditLog.AuditLogFilter,
        before: Long?,
    ) = buildString {
        append("/guilds/")
        append(guildId)
        append("/audit-logs?limit=")
        append(AUDIT_LOG_PAGE_SIZE)
        if (before != null) {
            append("&before=")
            append(before)
        }
        if (filter.userFilter != 0L) {
            append("&user_id=")
            append(filter.userFilter)
        }
        if (filter.actionFilter != 0) {
            append("&action_type=")
            append(filter.actionFilter)
        }
    }

    private fun sanitizeAuditLogJson(responseBody: String): String {
        val auditLogJson = JSONObject(responseBody)
        val entries = auditLogJson.optJSONArray("audit_log_entries") ?: return responseBody
        for (entryIndex in 0 until entries.length()) {
            val entry = entries.optJSONObject(entryIndex) ?: continue
            val changes = entry.optJSONArray("changes") ?: continue
            for (changeIndex in 0 until changes.length()) {
                val change = changes.optJSONObject(changeIndex) ?: continue
                sanitizeAuditChangeValue(change, "old_value")
                sanitizeAuditChangeValue(change, "new_value")
            }
        }
        return auditLogJson.toString()
    }

    private fun sanitizeAuditChangeValue(change: JSONObject, key: String) {
        if (!change.has(key)) return
        when (val auditValue = change.opt(key)) {
            is JSONObject -> change.put(key, labelForAuditObject(auditValue) ?: auditValue.toString())
            is JSONArray -> change.put(key, legacyReadableArray(auditValue))
        }
    }

    private fun legacyReadableArray(values: JSONArray): JSONArray {
        val sanitized = JSONArray()
        for (i in 0 until values.length()) {
            when (val auditValue = values.opt(i)) {
                is JSONObject -> sanitized.put(auditValue)
                is String -> sanitized.put(auditValue)
                is Number -> sanitized.put(auditValue)
            }
        }
        return sanitized
    }

    private fun labelForAuditObject(value: JSONObject): String? {
        return value.optString("name").takeIf { it.isNotBlank() }
            ?: value.optString("id").takeIf { it.isNotBlank() }
    }

    private fun ensureAuditEntries(auditLog: ModelAuditLog) {
        if (auditLog.auditLogEntries() == null) {
            ReflectUtils.setField(auditLog, "auditLogEntries", emptyList<ModelAuditLogEntry>())
        }
    }

    private fun blankAuditLog() = ModelAuditLog().apply {
        ReflectUtils.setField(this, "auditLogEntries", emptyList<ModelAuditLogEntry>())
        ReflectUtils.setField(this, "users", Collections.emptyList<com.discord.api.user.User>())
        ReflectUtils.setField(this, "webhooks", emptyList<ModelWebhook>())
        ReflectUtils.setField(this, "integrations", emptyList<ModelGuildIntegration>())
        ReflectUtils.setField(this, "guildScheduledEvents", emptyList<Any>())
        ReflectUtils.setField(this, "threads", emptyList<Any>())
    }

    private fun ModelAuditLog.auditLogEntries(): List<ModelAuditLogEntry>? = getAuditLogEntries()

    private fun showToast(message: String) {
        if (settings.getBool("showToasts", true)) {
            Utils.mainThread.post { Utils.showToast(message) }
        }
    }

    private companion object {
        const val INVITE_MAX_AGE_SECONDS = 30 * 60
        const val BANS_PAGE_SIZE = 1000
        const val MAX_BAN_PAGES = 5
        const val AUDIT_LOG_PAGE_SIZE = 50
        const val SECONDS_PER_MINUTE = 60
        const val SECONDS_PER_HOUR = 60 * SECONDS_PER_MINUTE
        const val SECONDS_PER_DAY = 24 * SECONDS_PER_HOUR
        const val SECONDS_PER_WEEK = 7 * SECONDS_PER_DAY
        const val SECONDS_PER_MONTH = 30 * SECONDS_PER_DAY
    }
}
