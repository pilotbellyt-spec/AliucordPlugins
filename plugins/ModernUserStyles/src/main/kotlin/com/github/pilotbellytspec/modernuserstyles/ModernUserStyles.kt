package com.github.pilotbellytspec.modernuserstyles

import android.content.Context
import android.text.Spanned
import android.text.SpannableStringBuilder
import android.view.View
import android.widget.TextView
import com.aliucord.Http
import com.aliucord.Utils
import com.aliucord.annotations.AliucordPlugin
import com.aliucord.entities.Plugin
import com.aliucord.patcher.after
import com.discord.models.member.GuildMember
import com.discord.stores.StoreMessageReplies
import com.discord.stores.StoreStream
import com.discord.utilities.mg_recycler.MGRecyclerDataPayload
import com.discord.utilities.textprocessing.node.UserMentionNode
import com.discord.widgets.channels.list.WidgetChannelsListAdapter
import com.discord.widgets.channels.list.items.ChannelListItem
import com.discord.widgets.channels.list.items.ChannelListItemPrivate
import com.discord.widgets.channels.list.items.ChannelListItemVoiceUser
import com.discord.widgets.channels.memberlist.adapter.ChannelMembersListAdapter
import com.discord.widgets.channels.memberlist.adapter.ChannelMembersListViewHolderMember
import com.discord.widgets.chat.input.autocomplete.AutocompleteViewModel
import com.discord.widgets.chat.input.autocomplete.InputEditTextAction
import com.discord.widgets.chat.input.autocomplete.UserAutocompletable
import com.discord.widgets.chat.input.autocomplete.adapter.AutocompleteItemViewHolder
import com.discord.widgets.chat.input.models.MentionInputModel
import com.discord.widgets.chat.list.adapter.WidgetChatListAdapterItemMessage
import com.discord.widgets.chat.list.entries.ChatListEntry
import com.discord.widgets.chat.list.entries.MessageEntry
import com.discord.widgets.chat.managereactions.ManageReactionsResultsAdapter
import com.discord.widgets.settings.account.WidgetSettingsAccount
import com.discord.widgets.user.profile.UserProfileHeaderView
import com.discord.widgets.user.profile.UserProfileHeaderViewModel
import com.discord.widgets.voice.fullscreen.stage.AudienceViewHolder
import com.discord.widgets.voice.fullscreen.stage.SpeakerViewHolder
import com.discord.widgets.voice.fullscreen.stage.StageCallItem
import com.discord.widgets.voice.sheet.CallParticipantsAdapter
import de.robv.android.xposed.XC_MethodHook
import kotlin.Function0
import org.json.JSONArray
import org.json.JSONObject
import java.util.WeakHashMap

@AliucordPlugin(requiresRestart = false)
@Suppress("unused", "UNCHECKED_CAST")
class ModernUserStyles : Plugin() {
    private lateinit var renderer: NameRenderer
    private lateinit var roles: RoleGradientResolver
    private val profileDisplayNames = mutableMapOf<Long, String>()
    private val profileStyles = mutableMapOf<Long, DisplayStyleData>()
    private val requestedProfiles = mutableSetOf<String>()
    private val loadedProfiles = mutableSetOf<String>()
    private val profileFetchCallbacks = mutableMapOf<String, MutableList<() -> Unit>>()
    private val requestedGuildRoles = mutableSetOf<Long>()
    private val loadedGuildRoles = mutableSetOf<Long>()
    private val guildRoleFetchCallbacks = mutableMapOf<Long, MutableList<() -> Unit>>()
    private val renderedUserIds = WeakHashMap<TextView, Long>()

    init {
        settingsTab = SettingsTab(PluginSettings::class.java, SettingsTab.Type.BOTTOM_SHEET).withArgs(settings)
    }

    override fun start(context: Context) {
        renderer = NameRenderer(context)
        roles = RoleGradientResolver()

        patchChatNames()
        patchReplyNames()
        patchMemberList()
        patchProfileNames()
        patchMentions()
        patchAutocomplete()
        patchDmList()
        patchVoiceNames()
        patchReactionUsers()
        patchSettingsAccount()
    }

    override fun stop(context: Context) {
        patcher.unpatchAll()
        commands.unregisterAll()
    }

    private fun patchChatNames() {
        val nameId = Utils.getResId("chat_list_adapter_item_text_name", "id")

        patcher.after<WidgetChatListAdapterItemMessage>(
            "onConfigure",
            Int::class.java,
            ChatListEntry::class.java,
        ) {
            if (!settings.getBool("chatNames", true)) return@after

            val entry = it.args[1] as? MessageEntry ?: return@after
            if (entry.message.isLoading) return@after

            val user = entry.message.author
            val member = entry.author
            val nameView = itemView.findViewById<TextView>(nameId)
            val guildId = member?.guildId
            val preserveName = member?.nick != null
            ensureGuildRolesFetched(guildId) {
                if (renderedUserIds[nameView] == user.id) {
                    renderUserName(
                        nameView,
                        user.id,
                        if (preserveName) null else displayNameFor(user.id, user),
                        styleFor(user.id, user),
                        roles.forMember(member),
                        guildId,
                        preserveName,
                    )
                }
            }
            renderUserName(
                nameView,
                user.id,
                if (preserveName) null else displayNameFor(user.id, user),
                styleFor(user.id, user),
                roles.forMember(member),
                guildId,
                preserveName,
            )
        }
    }

    private fun patchReplyNames() {
        patcher.after<WidgetChatListAdapterItemMessage>(
            "configureReplyPreview",
            MessageEntry::class.java,
        ) {
            if (!settings.getBool("chatNames", true)) return@after

            val entry = it.args[0] as MessageEntry
            val replyData = entry.replyData ?: return@after
            if (replyData.messageState !is StoreMessageReplies.MessageState.Loaded) return@after

            val refEntry = replyData.messageEntry
            val user = refEntry.message.author
            val member = refEntry.author
            val guildId = member?.guildId ?: entry.author?.guildId
            val nameView = readObject("replyName") as? TextView ?: return@after
            val preserveName = member?.nick != null

            ensureGuildRolesFetched(guildId) {
                if (renderedUserIds[nameView] == user.id) {
                    renderUserName(
                        nameView,
                        user.id,
                        if (preserveName) null else displayNameFor(user.id, user),
                        styleFor(user.id, user),
                        roles.forMember(member),
                        guildId,
                        preserveName,
                    )
                }
            }
            renderUserName(
                nameView,
                user.id,
                if (preserveName) null else displayNameFor(user.id, user),
                styleFor(user.id, user),
                roles.forMember(member),
                guildId,
                preserveName,
            )
        }
    }

    private fun patchMemberList() {
        val nameId = Utils.getResId("channel_members_list_item_name", "id")

        patcher.after<ChannelMembersListViewHolderMember>(
            "bind",
            ChannelMembersListAdapter.Item.Member::class.java,
            Function0::class.java,
        ) {
            if (!settings.getBool("memberList", true)) return@after

            val item = it.args[0] as ChannelMembersListAdapter.Item.Member
            val usernameView = memberListUsernameView(this) ?: itemView.findViewById<TextView>(nameId)
            val guildId = item.guildId ?: StoreStream.getGuildSelected().selectedGuildId
            val member = StoreStream.getGuilds().getMember(guildId, item.userId)
            ensureGuildRolesFetched(guildId) {
                val refreshedMember = StoreStream.getGuilds().getMember(guildId, item.userId)
                val preserveName = refreshedMember?.nick != null
                renderUserNameViews(
                    usernameView,
                    item.userId,
                    if (preserveName) null else displayNameFor(item.userId, null),
                    styleFor(item.userId, null),
                    roles.forMember(refreshedMember),
                    guildId,
                    preserveName,
                )
            }
            val preserveName = member?.nick != null
            renderUserNameViews(
                usernameView,
                item.userId,
                if (preserveName) null else displayNameFor(item.userId, null),
                styleFor(item.userId, null),
                roles.forMember(member),
                guildId,
                preserveName,
            )
        }
    }

    private fun renderUserNameViews(
        root: View?,
        userId: Long,
        label: String?,
        style: DisplayStyleData?,
        roleGradient: RoleGradient?,
        guildId: Long?,
        preserveExistingNameOnRefresh: Boolean = false,
    ) {
        when (root) {
            is TextView -> {
                if (root.text?.toString()?.trim()?.isNotEmpty() == true) {
                    renderUserName(root, userId, label, style, roleGradient, guildId, preserveExistingNameOnRefresh)
                }
            }
            is android.view.ViewGroup -> {
                val usernameText = usernameViewNameText(root)
                if (usernameText != null) {
                    renderUserName(usernameText, userId, label, style, roleGradient, guildId, preserveExistingNameOnRefresh)
                    return
                }

                var applied = false
                var index = 0
                while (index < root.childCount) {
                    val child = root.getChildAt(index)
                    if (child is TextView && child.text?.toString()?.trim()?.isNotEmpty() == true) {
                        renderUserName(
                            child,
                            userId,
                            if (applied) null else label,
                            style,
                            roleGradient,
                            guildId,
                            preserveExistingNameOnRefresh,
                        )
                        applied = true
                    } else if (child is android.view.ViewGroup) {
                        renderUserNameViews(child, userId, if (applied) null else label, style, roleGradient, guildId, preserveExistingNameOnRefresh)
                    }
                    index++
                }
            }
        }
    }

    private fun memberListUsernameView(holder: ChannelMembersListViewHolderMember): View? =
        holder.readObject("binding")?.readObject("f") as? View

    private fun usernameViewNameText(root: View): TextView? {
        if (root.javaClass.name != "com.discord.views.UsernameView") return null
        val binding = root.readObject("j") ?: return null
        return binding.readObject("c") as? TextView
    }

    private fun patchProfileNames() {
        val usernameTextId = Utils.getResId("username_text", "id")

        patcher.after<UserProfileHeaderView>(
            "configurePrimaryName",
            UserProfileHeaderViewModel.ViewState.Loaded::class.java,
        ) {
            if (!settings.getBool("profileNames", true)) return@after

            val loaded = it.args[0] as UserProfileHeaderViewModel.ViewState.Loaded
            cacheProfileObject(loaded.user.id, loaded.userProfile)
            cacheUserObject(loaded.user.id, loaded.user)
            val nameView = UserProfileHeaderView.`access$getBinding$p`(this).root.findViewById<TextView>(usernameTextId)
            val guildId = loaded.guildMember?.guildId
            val preserveName = loaded.guildMember?.nick != null
            ensureGuildRolesFetched(guildId) {
                if (renderedUserIds[nameView] == loaded.user.id) {
                    renderUserName(
                        nameView,
                        loaded.user.id,
                        if (preserveName) null else displayNameFor(loaded.user.id, loaded.user),
                        styleFor(loaded.user.id, loaded.user),
                        roles.forMember(loaded.guildMember),
                        guildId,
                        preserveName,
                    )
                }
            }
            renderUserName(
                nameView,
                loaded.user.id,
                if (preserveName) null else displayNameFor(loaded.user.id, loaded.user),
                styleFor(loaded.user.id, loaded.user),
                roles.forMember(loaded.guildMember),
                guildId,
                preserveName,
            )
        }
    }

    private fun patchMentions() {
        if (!settings.getBool("mentions", true)) return

        patcher.patch(
            UserMentionNode::class.java.getDeclaredMethod(
                "renderUserMention",
                SpannableStringBuilder::class.java,
                UserMentionNode.RenderContext::class.java,
            ),
            object : XC_MethodHook() {
                private var start = 0

                override fun beforeHookedMethod(param: MethodHookParam) {
                    start = (param.args[0] as SpannableStringBuilder).length
                }

                override fun afterHookedMethod(param: MethodHookParam) {
                    val node = param.thisObject as UserMentionNode<UserMentionNode.RenderContext>
                    val builder = param.args[0] as SpannableStringBuilder
                    val end = builder.length
                    if (start >= end) return

                    val user = StoreStream.getUsers().users[node.userId]
                    val guildId = StoreStream.getGuildSelected().selectedGuildId
                    val member = StoreStream.getGuilds().getMember(guildId, node.userId)
                    ensureProfileFetched(node.userId, guildId)
                    ensureGuildRolesFetched(guildId)
                    val colors = renderer.colorsFor(
                        roles.forMember(member),
                        settings.getBool("roleGradients", true),
                    )
                    if (colors.isEmpty()) return

                    builder.setSpan(
                        NameStyleSpan(colors.toIntArray(), renderer.effectForRoleColors(colors), end - start),
                        start,
                        end,
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                    )
                }
            },
        )
    }

    private fun patchAutocomplete() {
        if (!settings.getBool("autocomplete", true)) return

        patcher.after<AutocompleteViewModel>("generateSpanUpdates", MentionInputModel::class.java) {
            val res = it.result as InputEditTextAction.ReplaceCharacterStyleSpans
            val mentionInputModel = it.args[0] as MentionInputModel

            mentionInputModel.inputMentionsMap.forEach { (key, value) ->
                val user = value as? UserAutocompletable ?: return@forEach
                val guildId = user.guildMember?.guildId
                ensureProfileFetched(user.user.id, guildId)
                ensureGuildRolesFetched(guildId)
                val colors = renderer.colorsFor(
                    roles.forMember(user.guildMember),
                    settings.getBool("roleGradients", true),
                )
                if (colors.isNotEmpty()) {
                    res.spans[key] = listOf(NameStyleSpan(colors.toIntArray(), renderer.effectForRoleColors(colors), user.user.username.length))
                }
            }
        }

        val nameId = Utils.getResId("chat_input_item_name", "id")
        patcher.after<AutocompleteItemViewHolder>("bindUser", UserAutocompletable::class.java) {
            val item = it.args[0] as UserAutocompletable
            val nameView = rootFromBinding(this)?.findViewById<TextView>(nameId)
            val guildId = item.guildMember?.guildId
            val preserveName = item.guildMember?.nick != null
            ensureGuildRolesFetched(guildId) {
                if (nameView != null && renderedUserIds[nameView] == item.user.id) {
                    renderUserName(
                        nameView,
                        item.user.id,
                        if (preserveName) null else displayNameFor(item.user.id, item.user),
                        styleFor(item.user.id, item.user),
                        roles.forMember(item.guildMember),
                        guildId,
                        preserveName,
                    )
                }
            }
            renderUserName(
                nameView,
                item.user.id,
                if (preserveName) null else displayNameFor(item.user.id, item.user),
                styleFor(item.user.id, item.user),
                roles.forMember(item.guildMember),
                guildId,
                preserveName,
            )
        }
    }

    private fun patchDmList() {
        if (!settings.getBool("dmList", true)) return

        val nameId = Utils.getResId("channels_list_item_private_name", "id")
        patcher.after<WidgetChannelsListAdapter.ItemChannelPrivate>(
            "onConfigure",
            Int::class.java,
            ChannelListItem::class.java,
        ) {
            val item = it.args[1] as? ChannelListItemPrivate ?: return@after
            val recipient = item.channel.z()
                .firstOrNull { user -> user.id != StoreStream.getUsers().me.id }
                ?: item.channel.z().firstOrNull()
                ?: return@after
            val storeUser = StoreStream.getUsers().users[recipient.id]

            renderUserName(
                itemView.findViewById(nameId),
                recipient.id,
                displayNameFor(recipient.id, recipient),
                styleFor(recipient.id, storeUser ?: recipient),
                null,
            )
        }
    }

    private fun patchVoiceNames() {
        if (!settings.getBool("voiceNames", true)) return

        val voiceUserNameId = Utils.getResId("channels_item_voice_user_name", "id")
        val voiceUserListId = Utils.getResId("voice_user_list_item_user_name", "id")
        val stageSpeakerNameId = Utils.getResId("stage_channel_audience_member_name", "id")

        patcher.after<WidgetChannelsListAdapter.ItemVoiceUser>(
            "onConfigure",
            Int::class.java,
            ChannelListItem::class.java,
        ) {
            val item = it.args[1] as ChannelListItemVoiceUser
            val nameView = rootFromBinding(this)?.findViewById<TextView>(voiceUserNameId)
            val guildId = item.computed.readLong("guildId", "getGuildId")
            val member = guildId?.let { StoreStream.getGuilds().getMember(it, item.user.id) }
            ensureGuildRolesFetched(guildId) {
                val refreshedMember = guildId?.let { StoreStream.getGuilds().getMember(it, item.user.id) }
                val preserveName = refreshedMember?.nick != null
                if (nameView != null && renderedUserIds[nameView] == item.user.id) {
                    renderUserName(
                        nameView,
                        item.user.id,
                        if (preserveName) null else displayNameFor(item.user.id, item.user),
                        styleFor(item.user.id, item.user),
                        roles.forMember(refreshedMember),
                        guildId,
                        preserveName,
                    )
                }
            }
            val preserveName = member?.nick != null
            renderUserName(
                nameView,
                item.user.id,
                if (preserveName) null else displayNameFor(item.user.id, item.user),
                styleFor(item.user.id, item.user),
                roles.forMember(member),
                guildId,
                preserveName,
            )
        }

        patcher.after<CallParticipantsAdapter.ViewHolderUser>(
            "onConfigure",
            Int::class.java,
            MGRecyclerDataPayload::class.java,
        ) {
            val item = it.args[1] as? CallParticipantsAdapter.ListItem.VoiceUser ?: return@after
            val member = item.participant.guildMember
            val nameView = rootFromBinding(this)?.findViewById<TextView>(voiceUserListId)
            val guildId = member.guildId
            val preserveName = member.nick != null
            ensureGuildRolesFetched(guildId) {
                if (nameView != null && renderedUserIds[nameView] == member.userId) {
                    renderUserName(
                        nameView,
                        member.userId,
                        if (preserveName) null else displayNameFor(member.userId, null),
                        styleFor(member.userId, null),
                        roles.forMember(member),
                        guildId,
                        preserveName,
                    )
                }
            }
            renderUserName(
                nameView,
                member.userId,
                if (preserveName) null else displayNameFor(member.userId, null),
                styleFor(member.userId, null),
                roles.forMember(member),
                guildId,
                preserveName,
            )
        }

        patcher.after<AudienceViewHolder>("onConfigure", Int::class.java, StageCallItem::class.java) {
            val item = it.args[1] as? StageCallItem.AudienceItem ?: return@after
            val member = item.voiceUser.guildMember
            val nameView = rootFromBinding(this)?.findViewById<TextView>(voiceUserListId)
            val guildId = member.guildId
            val preserveName = member.nick != null
            ensureGuildRolesFetched(guildId) {
                if (nameView != null && renderedUserIds[nameView] == member.userId) {
                    renderUserName(
                        nameView,
                        member.userId,
                        if (preserveName) null else displayNameFor(member.userId, null),
                        styleFor(member.userId, null),
                        roles.forMember(member),
                        guildId,
                        preserveName,
                    )
                }
            }
            renderUserName(
                nameView,
                member.userId,
                if (preserveName) null else displayNameFor(member.userId, null),
                styleFor(member.userId, null),
                roles.forMember(member),
                guildId,
                preserveName,
            )
        }

        patcher.after<SpeakerViewHolder>("onConfigure", Int::class.java, StageCallItem::class.java) {
            val item = it.args[1] as? StageCallItem.SpeakerItem ?: return@after
            val member = item.voiceUser.guildMember
            val nameView = rootFromBinding(this)?.findViewById<TextView>(stageSpeakerNameId)
            val guildId = member.guildId
            val preserveName = member.nick != null
            ensureGuildRolesFetched(guildId) {
                if (nameView != null && renderedUserIds[nameView] == member.userId) {
                    renderUserName(
                        nameView,
                        member.userId,
                        if (preserveName) null else displayNameFor(member.userId, null),
                        styleFor(member.userId, null),
                        roles.forMember(member),
                        guildId,
                        preserveName,
                    )
                }
            }
            renderUserName(
                nameView,
                member.userId,
                if (preserveName) null else displayNameFor(member.userId, null),
                styleFor(member.userId, null),
                roles.forMember(member),
                guildId,
                preserveName,
            )
        }
    }

    private fun patchReactionUsers() {
        if (!settings.getBool("reactionUsers", true)) return

        val nameId = Utils.getResId("manage_reactions_result_user_name", "id")
        patcher.after<ManageReactionsResultsAdapter.ReactionUserViewHolder>(
            "onConfigure",
            Int::class.java,
            MGRecyclerDataPayload::class.java,
        ) {
            val item = it.args[1] as? ManageReactionsResultsAdapter.ReactionUserItem ?: return@after
            val member = item.guildMember ?: return@after
            val nameView = rootFromBinding(this)?.findViewById<TextView>(nameId)
            val guildId = member.guildId
            val preserveName = member.nick != null
            ensureGuildRolesFetched(guildId) {
                if (nameView != null && renderedUserIds[nameView] == member.userId) {
                    renderUserName(
                        nameView,
                        member.userId,
                        if (preserveName) null else displayNameFor(member.userId, null),
                        styleFor(member.userId, null),
                        roles.forMember(member),
                        guildId,
                        preserveName,
                    )
                }
            }
            renderUserName(
                nameView,
                member.userId,
                if (preserveName) null else displayNameFor(member.userId, null),
                styleFor(member.userId, null),
                roles.forMember(member),
                guildId,
                preserveName,
            )
        }
    }

    private fun patchSettingsAccount() {
        val modelClass = WidgetSettingsAccount.Model::class.java
        patcher.after<WidgetSettingsAccount>("configureUI", modelClass) {
            val model = it.args[0]
            val me = model.readObject("meUser", "getMeUser") ?: StoreStream.getUsers().me
            val meId = me.readLong("id", "getId") ?: StoreStream.getUsers().me.id
            cacheUserObject(meId, me)

            val root = WidgetSettingsAccount.`access$getBinding$p`(this).root
            styleMatchingTextViews(root, meId, me)
        }
    }

    private fun renderUserName(
        textView: TextView?,
        userId: Long,
        label: String?,
        style: DisplayStyleData?,
        roleGradient: RoleGradient?,
        guildId: Long? = null,
        preserveExistingNameOnRefresh: Boolean = false,
    ) {
        if (textView != null) {
            renderedUserIds[textView] = userId
            ensureProfileFetched(userId, guildId) {
                if (renderedUserIds[textView] == userId) {
                    val refreshedLabel = if (preserveExistingNameOnRefresh) label else displayNameFor(userId, null)
                    renderUserName(textView, userId, refreshedLabel, styleFor(userId, null), roleGradient, guildId, preserveExistingNameOnRefresh)
                }
            }
        }

        renderer.renderTextView(
            textView,
            label,
            style,
            roleGradient,
            settings.getBool("displayNames", true),
            settings.getBool("displayNameStyles", true),
            settings.getBool("roleGradients", true),
        )
    }

    private fun ensureProfileFetched(userId: Long, guildId: Long? = null, onLoaded: (() -> Unit)? = null) {
        val realGuildId = guildId?.takeIf { it != 0L }
        val key = "$userId:${realGuildId ?: 0L}"
        var shouldFetch = false
        synchronized(requestedProfiles) {
            if (loadedProfiles.contains(key)) return
            if (onLoaded != null) profileFetchCallbacks.getOrPut(key) { mutableListOf() }.add(onLoaded)
            shouldFetch = requestedProfiles.add(key)
        }
        if (!shouldFetch) return

        Utils.threadPool.execute {
            runCatching {
                val route = "/users/$userId/profile?type=popout&with_mutual_guilds=true&with_mutual_friends=true&with_mutual_friends_count=false" +
                    if (realGuildId == null) "" else "&guild_id=$realGuildId"
                Http.Request.newDiscordRNRequest(route).execute().use { response ->
                    parseProfilePayload(userId, JSONObject(response.text()))
                }
            }.onFailure {
                logger.warn("Failed to fetch modern profile data for $userId", it)
            }

            val callbacks = synchronized(requestedProfiles) {
                loadedProfiles.add(key)
                profileFetchCallbacks.remove(key).orEmpty()
            }
            if (callbacks.isNotEmpty()) {
                Utils.mainThread.post {
                    callbacks.forEach { callback -> callback() }
                }
            }
        }
    }

    private fun ensureGuildRolesFetched(guildId: Long?, onLoaded: (() -> Unit)? = null) {
        val realGuildId = guildId?.takeIf { it != 0L } ?: return
        var shouldFetch = false
        synchronized(requestedGuildRoles) {
            if (loadedGuildRoles.contains(realGuildId)) return
            if (onLoaded != null) guildRoleFetchCallbacks.getOrPut(realGuildId) { mutableListOf() }.add(onLoaded)
            shouldFetch = requestedGuildRoles.add(realGuildId)
        }
        if (!shouldFetch) return

        Utils.threadPool.execute {
            runCatching {
                Http.Request.newDiscordRNRequest("/guilds/$realGuildId/roles").execute().use { response ->
                    parseGuildRolesPayload(JSONArray(response.text()))
                }
            }.onFailure {
                logger.warn("Failed to fetch modern role colors for $realGuildId", it)
            }

            val callbacks = synchronized(requestedGuildRoles) {
                loadedGuildRoles.add(realGuildId)
                guildRoleFetchCallbacks.remove(realGuildId).orEmpty()
            }
            if (callbacks.isNotEmpty()) {
                Utils.mainThread.post {
                    callbacks.forEach { callback -> callback() }
                }
            }
        }
    }

    private fun parseProfilePayload(userId: Long, root: JSONObject) {
        root.optJSONObject("user")?.let { user ->
            user.optString("global_name").takeIf { it.trim().isNotEmpty() }?.let { profileDisplayNames[userId] = it }
            parseDisplayStyle(user.optJSONObject("display_name_styles"))?.let { profileStyles[userId] = it }
        }
        root.optJSONObject("profile_user")?.let { user ->
            user.optString("global_name").takeIf { it.trim().isNotEmpty() }?.let { profileDisplayNames[userId] = it }
            parseDisplayStyle(user.optJSONObject("display_name_styles"))?.let { profileStyles[userId] = it }
        }

        parseDisplayStyle(root.optJSONObject("display_name_styles"))?.let { profileStyles[userId] = it }
        parseDisplayStyle(root.optJSONObject("guild_member")?.optJSONObject("display_name_styles"))?.let { profileStyles[userId] = it }
        parseDisplayStyle(root.optJSONObject("guild_member_profile")?.optJSONObject("display_name_styles"))?.let { profileStyles[userId] = it }
        findDisplayStyleObject(root)?.let { style ->
            parseDisplayStyle(style)?.let { profileStyles[userId] = it }
        }
    }

    private fun findDisplayStyleObject(value: Any?): JSONObject? {
        when (value) {
            is JSONObject -> {
                val direct = value.optJSONObject("display_name_styles")
                if (direct != null) return direct

                val keys = value.keys()
                while (keys.hasNext()) {
                    val found = findDisplayStyleObject(value.opt(keys.next()))
                    if (found != null) return found
                }
            }
            is JSONArray -> {
                var index = 0
                while (index < value.length()) {
                    val found = findDisplayStyleObject(value.opt(index))
                    if (found != null) return found
                    index++
                }
            }
        }
        return null
    }

    private fun parseGuildRolesPayload(array: JSONArray) {
        var index = 0
        while (index < array.length()) {
            val role = array.optJSONObject(index)
            val roleId = role?.optString("id")?.toLongOrNull()
            val colors = role?.optJSONObject("colors")
            if (roleId != null && colors != null) {
                val primary = colors.optNullableColor("primary_color") ?: role.optNullableColor("color")
                val secondary = colors.optNullableColor("secondary_color")
                val tertiary = colors.optNullableColor("tertiary_color")
                if (primary != null && primary != 0) {
                    roles.setRuntimeRoleGradient(
                        roleId,
                        RoleGradient(
                            primaryColor = primary,
                            secondaryColor = secondary,
                            tertiaryColor = tertiary,
                            position = role.optNullableInt("position"),
                        ),
                    )
                }
            }
            index++
        }
    }

    private fun parseDisplayStyle(raw: JSONObject?): DisplayStyleData? {
        raw ?: return null

        val colors = mutableListOf<Int>()
        raw.optJSONArray("colors")?.let { array ->
            var index = 0
            while (index < array.length()) {
                array.optNullableColor(index)?.let(colors::add)
                index++
            }
        }

        val fontId = raw.optNullableInt("font_id") ?: raw.optNullableInt("fontId")
        val effectId = raw.optNullableInt("effect_id") ?: raw.optNullableInt("effectId")
        if (colors.isEmpty() && fontId == null && effectId == null) return null

        return DisplayStyleData(
            fontId = fontId,
            effectId = effectId,
            colors = colors.map { it and 0x00ffffff },
        )
    }

    private fun displayNameFor(userId: Long, fallbackUser: Any?): String? =
        profileDisplayNames[userId]
            ?: fallbackUser.readString("globalName", "getGlobalName")?.takeIf { it.trim().isNotEmpty() }
            ?: StoreStream.getUsers().users[userId].readString("globalName", "getGlobalName")?.takeIf { it.trim().isNotEmpty() }

    private fun styleFor(userId: Long, fallbackUser: Any?): DisplayStyleData? =
        profileStyles[userId] ?: fallbackUser.readDisplayStyle() ?: StoreStream.getUsers().users[userId].readDisplayStyle()

    private fun cacheProfileObject(userId: Long, profile: Any?) {
        val apiUser = profile.readObject("user", "getUser", "g")
        cacheUserObject(userId, apiUser)
    }

    private fun cacheUserObject(userId: Long, user: Any?) {
        user.readString("globalName", "getGlobalName")?.takeIf { it.trim().isNotEmpty() }?.let {
            profileDisplayNames[userId] = it
        }
        user.readDisplayStyle()?.let {
            profileStyles[userId] = it
        }
    }

    private fun Any?.readDisplayStyle(): DisplayStyleData? {
        val raw = this.readObject("displayNameStyles", "getDisplayNameStyles") ?: return null
        val colors = raw.readIntList("colors", "getColors")
        if (colors.isEmpty() && raw.readInt("fontId", "getFontId") == null && raw.readInt("effectId", "getEffectId") == null) {
            return null
        }

        return DisplayStyleData(
            fontId = raw.readInt("fontId", "getFontId"),
            effectId = raw.readInt("effectId", "getEffectId"),
            colors = colors.map { it and 0x00ffffff },
        )
    }

    private fun Any?.readString(vararg names: String): String? =
        readObject(*names) as? String

    private fun Any?.readInt(vararg names: String): Int? =
        when (val value = readObject(*names)) {
            is Int -> value
            is Number -> value.toInt()
            else -> null
        }

    private fun Any?.readLong(vararg names: String): Long? =
        when (val value = readObject(*names)) {
            is Long -> value
            is Number -> value.toLong()
            else -> null
        }

    private fun Any?.readIntList(vararg names: String): List<Int> {
        val value = readObject(*names) ?: return emptyList()
        if (value is Iterable<*>) return value.mapNotNull { (it as? Number)?.toInt() }
        if (value.javaClass.isArray) {
            val result = mutableListOf<Int>()
            var index = 0
            val length = java.lang.reflect.Array.getLength(value)
            while (index < length) {
                (java.lang.reflect.Array.get(value, index) as? Number)?.toInt()?.let(result::add)
                index++
            }
            return result
        }
        return emptyList()
    }

    private fun Any?.readObject(vararg names: String): Any? {
        val target = this ?: return null
        var cls: Class<*>? = target.javaClass
        names.forEach { name ->
            cls = target.javaClass
            while (cls != null) {
                runCatching {
                    val field = cls!!.getDeclaredField(name).apply { isAccessible = true }
                    return field[target]
                }
                runCatching {
                    val method = cls!!.getDeclaredMethod(name).apply { isAccessible = true }
                    return method.invoke(target)
                }
                cls = cls.superclass
            }
        }
        return null
    }

    private fun styleMatchingTextViews(root: View, userId: Long, user: Any?) {
        if (root is TextView) {
            val text = root.text?.toString()
            val username = user.readString("username", "getUsername") ?: StoreStream.getUsers().users[userId]?.username
            val global = displayNameFor(userId, user)
            if (text == username || text == global) {
                renderUserName(root, userId, global, styleFor(userId, user), null)
            }
        }

        val group = root as? android.view.ViewGroup ?: return
        var index = 0
        while (index < group.childCount) {
            styleMatchingTextViews(group.getChildAt(index), userId, user)
            index++
        }
    }

    private fun rootFromBinding(holder: Any): View? {
        holder.javaClass.declaredFields.forEach { field ->
            val binding = runCatching {
                field.isAccessible = true
                field[holder]
            }.getOrNull() ?: return@forEach

            val root = runCatching { binding.javaClass.getMethod("getRoot").invoke(binding) as? View }.getOrNull()
                ?: runCatching {
                    binding.javaClass.getDeclaredField("root").apply { isAccessible = true }[binding] as? View
                }.getOrNull()
            if (root != null) return root
        }
        return null
    }

}

private fun JSONObject.optNullableInt(key: String): Int? =
    if (has(key) && !isNull(key)) optInt(key) else null

private fun JSONObject.optNullableColor(key: String): Int? =
    optNullableInt(key)?.and(0x00ffffff)

private fun JSONArray.optNullableColor(index: Int): Int? =
    if (isNull(index)) null else optInt(index) and 0x00ffffff
