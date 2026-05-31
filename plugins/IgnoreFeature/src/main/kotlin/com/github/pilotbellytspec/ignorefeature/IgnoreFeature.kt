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
    private lateinit var store: IgnoreStore
    private lateinit var sync: IgnoreSync
    private var active = false

    init {
        settingsTab = SettingsTab(PluginSettings::class.java, SettingsTab.Type.BOTTOM_SHEET).withArgs(settings)
    }

    override fun start(context: Context) {
        active = true
        store = IgnoreStore(settings)
        sync = IgnoreSync(store) { Utils.showToast(it) }
        store.listen { markRelationshipsChanged() }

        patchUserActionsDialog()
        patchBlockedRelationshipsObservable()
        patchChatBlockedRelationships()
        registerGatewayEvents()

        if (settings.getBool("syncIgnoreState", true)) {
            sync.fetch()
        }
    }

    override fun stop(context: Context) {
        active = false
        patcher.unpatchAll()
    }

    private fun patchUserActionsDialog() {
        patcher.after<UserActionsDialog>("onViewBound", View::class.java) {
            val dialog = it.thisObject as UserActionsDialog
            val binding = dialog.binding()
            val root = binding.a
            val item = root.findViewWithTag<TextView>(IGNORE_ITEM_TAG) ?: createIgnoreItem(root)
            if (item.parent == null) {
                val insertIndex = root.indexOfChild(binding.c) + 1
                root.addView(item, insertIndex)
            }

            val userId = dialog.userId()
            item.setOnClickListener {
                val ignored = store.contains(userId)
                item.isEnabled = false
                if (settings.getBool("syncIgnoreState", true)) {
                    sync.setIgnored(userId, !ignored) {
                        item.isEnabled = true
                        dialog.dismiss()
                        Utils.showToast(if (ignored) "User unignored." else "User ignored.")
                    }
                } else {
                    store.set(userId, !ignored)
                    item.isEnabled = true
                    dialog.dismiss()
                    Utils.showToast(if (ignored) "User unignored." else "User ignored.")
                }
            }
        }

        patcher.after<UserActionsDialog>("onViewBoundOrOnResume") {
            val dialog = it.thisObject as UserActionsDialog
            updateIgnoreItem(dialog)
        }
    }

    private fun patchChatBlockedRelationships() {
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
            java.lang.Long::class.java,
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
            java.lang.Long::class.java,
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
            val ignored = store.all()
            if (ignored.isEmpty()) return@before
            @Suppress("UNCHECKED_CAST")
            val existing = it.args[3] as? Map<Long, Int> ?: return@before
            val merged = existing.toMutableMap()
            ignored.forEach { userId ->
                if (merged[userId] != RELATIONSHIP_BLOCKED) {
                    merged[userId] = RELATIONSHIP_BLOCKED
                }
            }
            it.args[3] = merged
        }
    }

    private fun patchBlockedRelationshipsObservable() {
        if (!settings.getBool("collapseIgnoredMessages", true)) return

        patcher.after<StoreUserRelationships>("observeForType", Int::class.javaPrimitiveType!!) {
            val relationshipType = it.args[0] as? Int ?: return@after
            if (relationshipType != RELATIONSHIP_BLOCKED) return@after
            if (!isChatListModelBuildingRelationships()) return@after

            @Suppress("UNCHECKED_CAST")
            val original = it.result as? Observable<Map<Long, Int>> ?: return@after
            val ignored = store.observe().G { ids ->
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

    private fun registerGatewayEvents() {
        GatewayAPI.onRawEvent(listOf("READY", "CONNECTION_OPEN", "RELATIONSHIP_ADD", "RELATIONSHIP_UPDATE", "RELATIONSHIP_REMOVE")) { raw ->
            if (!active || !settings.getBool("syncIgnoreState", true)) return@onRawEvent
            runCatching {
                val root = JSONObject(raw)
                val data = root.optJSONObject("d") ?: return@runCatching
                when (root.optString("t")) {
                    "READY", "CONNECTION_OPEN" -> sync.applyConnectionOpen(data)
                    "RELATIONSHIP_ADD", "RELATIONSHIP_UPDATE", "RELATIONSHIP_REMOVE" -> {
                        sync.applyRelationshipEvent(data, root.optString("t") == "RELATIONSHIP_REMOVE")
                    }
                }
            }.onFailure {
                logger.warn("Failed to handle ignore relationship event", it)
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
        val item = dialog.binding().a.findViewWithTag<TextView>(IGNORE_ITEM_TAG) ?: return
        val userId = dialog.userId()
        val ignored = store.contains(userId)
        item.text = if (ignored) "Unignore" else "Ignore"
        item.visibility = if (userId == StoreStream.getUsers().me.id) View.GONE else View.VISIBLE
    }

    private fun markRelationshipsChanged() {
        runCatching {
            StoreStream.getDispatcherYesThisIsIntentional().schedule {
                StoreStream.getUserRelationships().markChanged()
            }
        }
    }

    private fun UserActionsDialog.binding(): UserActionsDialogBinding {
        return javaClass.getDeclaredMethod("g").apply { isAccessible = true }.invoke(this) as UserActionsDialogBinding
    }

    private fun UserActionsDialog.userId(): Long {
        return javaClass.getDeclaredMethod("h").apply { isAccessible = true }.invoke(this).readLong("l") ?: 0L
    }

    private fun Any?.readLong(vararg names: String): Long? =
        when (val value = readObject(*names)) {
            is Long -> value
            is Number -> value.toLong()
            else -> null
        }

    private fun Any?.readObject(vararg names: String): Any? {
        val target = this ?: return null
        names.forEach { name ->
            var cls: Class<*>? = target.javaClass
            while (cls != null) {
                runCatching {
                    val field = cls!!.getDeclaredField(name).apply { isAccessible = true }
                    return field[target]
                }
                runCatching {
                    val method = cls!!.getDeclaredMethod(name).apply { isAccessible = true }
                    return method.invoke(target)
                }
                cls = cls!!.superclass
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
