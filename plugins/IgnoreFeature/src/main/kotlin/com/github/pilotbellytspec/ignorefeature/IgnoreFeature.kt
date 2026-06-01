package com.github.pilotbellytspec.ignorefeature

import android.content.Context
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import b.a.a.d.a as UserActionsDialog
import b.a.i.u1 as UserActionsDialogBinding
import com.aliucord.Utils
import com.aliucord.annotations.AliucordPlugin
import com.aliucord.api.GatewayAPI
import com.aliucord.entities.Plugin
import com.aliucord.patcher.after
import com.aliucord.patcher.before
import com.discord.api.channel.Channel
import com.discord.api.role.GuildRole
import com.discord.models.member.GuildMember
import com.discord.models.message.Message
import com.discord.stores.StoreMessageReplies
import com.discord.stores.StoreMessageState
import com.discord.stores.StoreStream
import com.discord.stores.StoreThreadMessages
import com.discord.stores.StoreUserRelationships
import com.discord.utilities.drawable.DrawableCompat
import com.discord.utilities.embed.InviteEmbedModel
import com.discord.widgets.botuikit.ComponentChatListState
import com.discord.widgets.chat.list.model.WidgetChatListModelMessages
import com.lytefast.flexinput.R
import org.json.JSONObject
import rx.Observable
import rx.functions.FuncN

@AliucordPlugin(requiresRestart = false)
@Suppress("unused")
class IgnoreFeature : Plugin() {
    private lateinit var quietList: IgnoreStore
    private lateinit var relationshipSync: IgnoreSync
    private var running = false

    init {
        settingsTab = SettingsTab(PluginSettings::class.java, SettingsTab.Type.BOTTOM_SHEET).withArgs(settings)
    }

    override fun start(context: Context) {
        running = true
        quietList = IgnoreStore(settings)
        relationshipSync = IgnoreSync(quietList) { Utils.showToast(it) }
        quietList.listen { markRelationshipsChanged() }

        userActions()
        blockedRelationshipStream()
        chatModel()
        gateway()

        if (settings.getBool("syncIgnoreState", true)) {
            relationshipSync.fetch()
        }
    }

    override fun stop(context: Context) {
        running = false
        patcher.unpatchAll()
    }

    private fun userActions() {
        patcher.after<UserActionsDialog>("onViewBound", View::class.java) {
            val dialog = it.thisObject as UserActionsDialog
            val binding = dialog.binding()
            val actionList = binding.a
            val ignoreRow = actionList.findViewWithTag<TextView>(IGNORE_ITEM_TAG) ?: createIgnoreItem(actionList)
            if (ignoreRow.parent == null) {
                val insertIndex = actionList.indexOfChild(binding.c) + 1
                actionList.addView(ignoreRow, insertIndex)
            }

            val userId = dialog.userId()
            ignoreRow.setOnClickListener {
                val alreadyIgnored = quietList.contains(userId)
                ignoreRow.isEnabled = false
                if (settings.getBool("syncIgnoreState", true)) {
                    relationshipSync.setIgnored(userId, !alreadyIgnored) {
                        ignoreRow.isEnabled = true
                        dialog.dismiss()
                        Utils.showToast(if (alreadyIgnored) "User unignored." else "User ignored.")
                    }
                } else {
                    quietList.set(userId, !alreadyIgnored)
                    ignoreRow.isEnabled = true
                    dialog.dismiss()
                    Utils.showToast(if (alreadyIgnored) "User unignored." else "User ignored.")
                }
            }
        }

        patcher.after<UserActionsDialog>("onViewBoundOrOnResume") {
            val dialog = it.thisObject as UserActionsDialog
            updateIgnoreItem(dialog)
        }
    }

    private fun chatModel() {
        if (!settings.getBool("collapseIgnoredMessages", true)) return

        WidgetChatListModelMessages.Companion::class.java.getDeclaredMethod(
            "getMessageItems",
            Channel::class.java,
            Map::class.java,
            Map::class.java,
            Map::class.java,
            Channel::class.java,
            StoreThreadMessages.ThreadState::class.java,
            Message::class.java,
            StoreMessageState.State::class.java,
            Map::class.java,
            Boolean::class.javaPrimitiveType,
            Boolean::class.javaPrimitiveType,
            Long::class.javaObjectType,
            Boolean::class.javaPrimitiveType,
            Boolean::class.javaPrimitiveType,
            Boolean::class.javaPrimitiveType,
            Long::class.javaPrimitiveType,
            Boolean::class.javaPrimitiveType,
            Map::class.java,
            InviteEmbedModel::class.java,
            Boolean::class.javaPrimitiveType,
            Boolean::class.javaPrimitiveType,
        ).apply { isAccessible = true }

        patcher.before<WidgetChatListModelMessages.Companion>(
            "getMessageItems",
            Channel::class.java,
            Map::class.java,
            Map::class.java,
            Map::class.java,
            Channel::class.java,
            StoreThreadMessages.ThreadState::class.java,
            Message::class.java,
            StoreMessageState.State::class.java,
            Map::class.java,
            Boolean::class.javaPrimitiveType!!,
            Boolean::class.javaPrimitiveType!!,
            Long::class.javaObjectType,
            Boolean::class.javaPrimitiveType!!,
            Boolean::class.javaPrimitiveType!!,
            Boolean::class.javaPrimitiveType!!,
            Long::class.javaPrimitiveType!!,
            Boolean::class.javaPrimitiveType!!,
            Map::class.java,
            InviteEmbedModel::class.java,
            Boolean::class.javaPrimitiveType!!,
            Boolean::class.javaPrimitiveType!!,
        ) {
            val ignored = quietList.all()
            if (ignored.isEmpty()) return@before
            @Suppress("UNCHECKED_CAST")
            val nativeRelationships = it.args[3] as? Map<Long, Int> ?: return@before
            val merged = nativeRelationships.toMutableMap()
            ignored.forEach { userId ->
                if (merged[userId] != RELATIONSHIP_BLOCKED) {
                    merged[userId] = RELATIONSHIP_BLOCKED
                }
            }
            it.args[3] = merged
        }
    }

    private fun blockedRelationshipStream() {
        if (!settings.getBool("collapseIgnoredMessages", true)) return

        patcher.after<StoreUserRelationships>("observeForType", Int::class.javaPrimitiveType!!) {
            val relationshipType = it.args[0] as? Int ?: return@after
            if (relationshipType != RELATIONSHIP_BLOCKED) return@after
            if (!isChatListModelBuildingRelationships()) return@after

            @Suppress("UNCHECKED_CAST")
            val original = it.result as? Observable<Map<Long, Int>> ?: return@after
            val ignored = quietList.observe().G { ids ->
                ids.associateWith { RELATIONSHIP_BLOCKED }
            }

            it.result = Observable.b(
                listOf(original, ignored),
                FuncN<Map<Long, Int>> { values ->
                    @Suppress("UNCHECKED_CAST")
                    val blockedRelationships = values[0] as? Map<Long, Int> ?: emptyMap()
                    @Suppress("UNCHECKED_CAST")
                    val ignoredRelationships = values[1] as? Map<Long, Int> ?: emptyMap()
                    if (ignoredRelationships.isEmpty()) {
                        blockedRelationships
                    } else {
                        blockedRelationships.toMutableMap().apply {
                            putAll(ignoredRelationships)
                        }
                    }
                },
            ).r()
        }
    }

    private fun gateway() {
        GatewayAPI.onRawEvent(listOf("READY", "CONNECTION_OPEN", "RELATIONSHIP_ADD", "RELATIONSHIP_UPDATE", "RELATIONSHIP_REMOVE")) { raw ->
            if (!running || !settings.getBool("syncIgnoreState", true)) return@onRawEvent
            try {
                val event = JSONObject(raw)
                val payload = event.optJSONObject("d") ?: return@onRawEvent
                when (event.optString("t")) {
                    "READY", "CONNECTION_OPEN" -> relationshipSync.applyConnectionOpen(payload)
                    "RELATIONSHIP_ADD", "RELATIONSHIP_UPDATE", "RELATIONSHIP_REMOVE" -> {
                        relationshipSync.applyRelationshipEvent(payload, event.optString("t") == "RELATIONSHIP_REMOVE")
                    }
                }
            } catch (error: Throwable) {
                logger.warn("Ignore gateway payload did not parse", error)
            }
        }
    }

    private fun createIgnoreItem(root: LinearLayout): TextView {
        val context = root.context
        return TextView(context, null, 0, R.i.UiKit_ListItem_Icon).apply {
            tag = IGNORE_ITEM_TAG
            setCompoundDrawablesWithIntrinsicBounds(DrawableCompat.getThemedDrawableRes(this, R.b.ic_user_actions_block), 0, 0, 0)
        }
    }

    private fun updateIgnoreItem(dialog: UserActionsDialog) {
        val ignoreRow = dialog.binding().a.findViewWithTag<TextView>(IGNORE_ITEM_TAG) ?: return
        val userId = dialog.userId()
        val alreadyIgnored = quietList.contains(userId)
        ignoreRow.text = if (alreadyIgnored) "Unignore" else "Ignore"
        ignoreRow.visibility = if (userId == StoreStream.getUsers().me.id) View.GONE else View.VISIBLE
    }

    private fun markRelationshipsChanged() {
        try {
            StoreStream.getDispatcherYesThisIsIntentional().schedule {
                StoreStream.getUserRelationships().markChanged()
            }
        } catch (_: Throwable) {
        }
    }

    private fun UserActionsDialog.binding(): UserActionsDialogBinding {
        return javaClass.getDeclaredMethod("g").apply { isAccessible = true }.invoke(this) as UserActionsDialogBinding
    }

    private fun UserActionsDialog.userId(): Long {
        return javaClass.getDeclaredMethod("h").apply { isAccessible = true }.invoke(this).readLong("l") ?: 0L
    }

    private fun Any?.readLong(vararg names: String): Long? =
        when (val reflectedValue = peek(*names)) {
            is Long -> reflectedValue
            is Number -> reflectedValue.toLong()
            else -> null
        }

    private fun Any?.peek(vararg names: String): Any? {
        val target = this ?: return null
        names.forEach { name ->
        var cls: Class<*>? = target.javaClass
        while (cls != null) {
            val klass = cls
            try {
                val field = klass.getDeclaredField(name).apply { isAccessible = true }
                return field[target]
            } catch (_: Throwable) {
            }
            try {
                val method = klass.getDeclaredMethod(name).apply { isAccessible = true }
                return method.invoke(target)
            } catch (_: Throwable) {
            }
            cls = klass.superclass
        }
        }
        return null
    }

    private fun isChatListModelBuildingRelationships(): Boolean {
        return Thread.currentThread().stackTrace.any {
            it.className == "com.discord.widgets.chat.list.model.WidgetChatListModelMessages\$Companion"
        }
    }

    private companion object {
        const val IGNORE_ITEM_TAG = "IgnoreFeature:ignoreItem"
        const val RELATIONSHIP_BLOCKED = 2
    }
}
