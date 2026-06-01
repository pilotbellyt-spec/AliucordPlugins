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
import com.aliucord.patcher.before
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
import com.discord.widgets.chat.list.adapter.WidgetChatListAdapterItemPrivateChannelStart
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
import com.discord.views.ToolbarTitleLayout
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
    private val profileUsernames = mutableMapOf<Long, String>()
    private val profileDisplayNames = mutableMapOf<Long, String>()
    private val profileStyles = mutableMapOf<Long, DisplayStyleData>()
    private val requestedProfiles = mutableSetOf<String>()
    private val loadedProfiles = mutableSetOf<String>()
    private val profileFetchCallbacks = mutableMapOf<String, MutableList<() -> Unit>>()
    private val requestedGuildRoles = mutableSetOf<Long>()
    private val loadedGuildRoles = mutableSetOf<Long>()
    private val guildRoleFetchCallbacks = mutableMapOf<Long, MutableList<() -> Unit>>()
    private val renderedUserIds = WeakHashMap<TextView, Long>()
    private val replyNameContexts = WeakHashMap<WidgetChatListAdapterItemMessage, ReplyNameContext>()

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
        patchDmHeaders()
        patchToolbarTitle()
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
            val configuredName = nameView?.text?.toString().cleanName()
            val preserveName = shouldPreserveConfiguredName(configuredName, user.id, user, member)
            ensureGuildRolesFetched(guildId) {
                if (renderedUserIds[nameView] == user.id) {
                    renderUserName(
                        nameView,
                        user.id,
                        if (preserveName) configuredName else displayNameFor(user.id, user),
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
                if (preserveName) configuredName else displayNameFor(user.id, user),
                styleFor(user.id, user),
                roles.forMember(member),
                guildId,
                preserveName,
            )
        }
    }

    private fun patchReplyNames() {
        patcher.before<WidgetChatListAdapterItemMessage>(
            "configureReplyPreview",
            MessageEntry::class.java,
        ) {
            if (!settings.getBool("chatNames", true)) return@before

            replyNameContexts.remove(this)
            val entry = it.args[0] as MessageEntry
            val replyData = entry.replyData ?: return@before
            if (replyData.messageState !is StoreMessageReplies.MessageState.Loaded) return@before

            val refEntry = replyData.messageEntry
            val user = refEntry.message.author
            val guildId = refEntry.author?.guildId ?: entry.author?.guildId
            val member = refEntry.author ?: guildId?.let { guild -> StoreStream.getGuilds().getMember(guild, user.id) }
            val context = ReplyNameContext(user.id, user, member, guildId, refEntry.message.id)
            replyNameContexts[this] = context
            ensureProfileFetched(user.id, guildId) {
                renderReplyNameIfCurrent(this, context)
            }
            ensureGuildRolesFetched(guildId) {
                renderReplyNameIfCurrent(this, context)
            }
        }

        patcher.after<WidgetChatListAdapterItemMessage>(
            "configureReplyName",
            String::class.java,
            Int::class.javaPrimitiveType!!,
            Boolean::class.javaPrimitiveType!!,
        ) {
            if (!settings.getBool("chatNames", true)) return@after

            val context = replyNameContexts[this]?.copy(
                configuredName = (it.args[0] as? String).cleanName(),
            ) ?: return@after
            replyNameContexts[this] = context
            val nameView = readObject("replyName") as? TextView ?: return@after
            renderReplyName(nameView, context)
        }
    }

    private fun renderReplyNameIfCurrent(
        item: WidgetChatListAdapterItemMessage,
        context: ReplyNameContext,
    ) {
        val current = replyNameContexts[item] ?: return
        if (!current.isSameReply(context)) return
        val nameView = item.readObject("replyName") as? TextView ?: return
        renderReplyName(nameView, current)
    }

    private fun renderReplyName(
        nameView: TextView,
        context: ReplyNameContext,
    ) {
        val member = context.member ?: context.guildId?.let { guild -> StoreStream.getGuilds().getMember(guild, context.userId) }
        val username = usernameFor(context.userId, context.user)
        val displayName = displayNameFor(context.userId, context.user)
        val preserveName = shouldPreserveReplyConfiguredName(context.configuredName, username, displayName, member)
        renderUserName(
            nameView,
            context.userId,
            if (preserveName) context.configuredName else displayName,
            styleFor(context.userId, context.user),
            roles.forMember(member),
            context.guildId,
            preserveName,
            fetchAsync = false,
        )
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
            val useDisplayStyleColors = isSelectedPrivateChannel()
            val configuredName = item.name.cleanName() ?: usernameView?.let { view ->
                if (view is TextView) view.text?.toString().cleanName()
                else usernameViewNameText(view)?.text?.toString().cleanName()
            }
            ensureGuildRolesFetched(guildId) {
                val refreshedMember = StoreStream.getGuilds().getMember(guildId, item.userId)
                val preserveName = !useDisplayStyleColors && shouldPreserveConfiguredName(configuredName, item.userId, null, refreshedMember)
                renderUserNameViews(
                    usernameView,
                    item.userId,
                    if (preserveName) configuredName else displayNameFor(item.userId, null),
                    styleFor(item.userId, null),
                    roles.forMember(refreshedMember),
                    guildId,
                    preserveName,
                    useDisplayStyleColors = useDisplayStyleColors,
                )
            }
            val preserveName = !useDisplayStyleColors && shouldPreserveConfiguredName(configuredName, item.userId, null, member)
            renderUserNameViews(
                usernameView,
                item.userId,
                if (preserveName) configuredName else displayNameFor(item.userId, null),
                styleFor(item.userId, null),
                roles.forMember(member),
                guildId,
                preserveName,
                useDisplayStyleColors = useDisplayStyleColors,
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
        useDisplayStyleColors: Boolean = false,
    ) {
        when (root) {
            is TextView -> {
                if (root.text?.toString()?.trim()?.isNotEmpty() == true) {
                    renderUserName(root, userId, label, style, roleGradient, guildId, preserveExistingNameOnRefresh, useDisplayStyleColors = useDisplayStyleColors)
                }
            }
            is android.view.ViewGroup -> {
                val usernameText = usernameViewNameText(root)
                if (usernameText != null) {
                    renderUserName(usernameText, userId, label, style, roleGradient, guildId, preserveExistingNameOnRefresh, useDisplayStyleColors = useDisplayStyleColors)
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
                            useDisplayStyleColors = useDisplayStyleColors,
                        )
                        applied = true
                    } else if (child is android.view.ViewGroup) {
                        renderUserNameViews(child, userId, if (applied) null else label, style, roleGradient, guildId, preserveExistingNameOnRefresh, useDisplayStyleColors)
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
            val preserveName = loaded.guildMember.hasCleanNick()
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
                        useDisplayStyleColors = true,
                        allowMultiline = true,
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
                useDisplayStyleColors = true,
                allowMultiline = true,
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
            val preserveName = item.guildMember.hasCleanNick()
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
            val nameView = itemView.findViewById<TextView>(nameId)
            val currentName = nameView?.text?.toString().cleanName()

            renderUserNameDrawable(
                nameView,
                recipient.id,
                displayNameFor(recipient.id, recipient) ?: currentName,
                styleFor(recipient.id, storeUser ?: recipient),
                null,
                preserveExistingNameOnRefresh = currentName != null,
            )
        }
    }

    private fun patchDmHeaders() {
        if (!settings.getBool("dmList", true)) return

        val headerId = Utils.getResId("chat_list_adapter_item_private_channel_start_header", "id")
        patcher.after<WidgetChatListAdapterItemPrivateChannelStart>(
            "onConfigure",
            Int::class.java,
            ChatListEntry::class.java,
        ) {
            val header = itemView.findViewById<TextView>(headerId) ?: return@after
            styleCurrentPrivateRecipient(header, allowMultiline = true)
        }
    }

    private fun patchToolbarTitle() {
        if (!settings.getBool("dmList", true)) return

        patcher.after<ToolbarTitleLayout>(
            "a",
            CharSequence::class.java,
            Int::class.javaObjectType,
            Int::class.javaObjectType,
        ) {
            styleCurrentPrivateRecipient(title, resetWhenNotMatched = true)
        }
    }

    private fun styleCurrentPrivateRecipient(
        textView: TextView?,
        allowMultiline: Boolean = false,
        resetWhenNotMatched: Boolean = false,
    ) {
        if (textView == null) return
        val channel = runCatching { StoreStream.getChannelsSelected().selectedChannel }.getOrNull()
        if (channel == null) {
            if (resetWhenNotMatched) renderer.resetTextView(textView)
            return
        }
        val recipients = runCatching { channel.z() }.getOrNull()
        if (recipients.isNullOrEmpty()) {
            if (resetWhenNotMatched) renderer.resetTextView(textView)
            return
        }
        val meId = StoreStream.getUsers().me.id
        val recipient = recipients.firstOrNull { user -> user.id != meId } ?: recipients.firstOrNull() ?: return
        if (recipients.size != 1) {
            if (resetWhenNotMatched) renderer.resetTextView(textView)
            return
        }

        val storeUser = StoreStream.getUsers().users[recipient.id]
        val displayName = displayNameFor(recipient.id, recipient)
        val username = usernameFor(recipient.id, recipient)
        val currentText = textView.text?.toString().cleanName() ?: return
        val channelName = channel.readString("name", "getName").cleanName()
        val matchesRecipient = currentText == displayName.cleanName() ||
            currentText == username.cleanName() ||
            currentText == channelName
        if (!matchesRecipient) {
            if (resetWhenNotMatched) renderer.resetTextView(textView)
            return
        }

        renderUserNameDrawable(
            textView,
            recipient.id,
            displayName ?: currentText,
            styleFor(recipient.id, storeUser ?: recipient),
            null,
            preserveExistingNameOnRefresh = true,
            allowMultiline = allowMultiline,
        )
    }

    private fun isSelectedPrivateChannel(): Boolean {
        val channel = runCatching { StoreStream.getChannelsSelected().selectedChannel }.getOrNull() ?: return false
        val recipients = runCatching { channel.z() }.getOrNull() ?: return false
        return recipients.isNotEmpty()
    }

    private fun renderUserNameDrawable(
        textView: TextView?,
        userId: Long,
        label: String?,
        style: DisplayStyleData?,
        roleGradient: RoleGradient?,
        guildId: Long? = null,
        preserveExistingNameOnRefresh: Boolean = false,
        allowMultiline: Boolean = false,
    ) {
        if (textView != null) renderedUserIds[textView] = userId
        renderer.renderTextViewAsDrawable(
            textView,
            label,
            style,
            roleGradient,
            settings.getBool("displayNames", true),
            settings.getBool("nameStyles", true),
            settings.getBool("roleGradients", true),
            allowDisplayStyleColors = true,
            allowMultiline = allowMultiline,
        )
        if (textView != null) {
            ensureProfileFetched(userId, guildId) {
                if (renderedUserIds[textView] == userId) {
                    renderer.renderTextViewAsDrawable(
                        textView,
                        if (preserveExistingNameOnRefresh) label else displayNameFor(userId, null),
                        styleFor(userId, null),
                        roleGradient,
                        settings.getBool("displayNames", true),
                        settings.getBool("nameStyles", true),
                        settings.getBool("roleGradients", true),
                        allowDisplayStyleColors = true,
                        allowMultiline = allowMultiline,
                    )
                }
            }
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
                val preserveName = refreshedMember.hasCleanNick()
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
            val preserveName = member.hasCleanNick()
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
            val preserveName = member.hasCleanNick()
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
            val preserveName = member.hasCleanNick()
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
            val preserveName = member.hasCleanNick()
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
            val preserveName = member.hasCleanNick()
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
        fetchAsync: Boolean = true,
        useDisplayStyleColors: Boolean = false,
        allowMultiline: Boolean = false,
        allowReplacementEffects: Boolean = true,
        preserveReplacementEffectText: Boolean = false,
    ) {
        if (textView != null && fetchAsync) {
            renderedUserIds[textView] = userId
            ensureProfileFetched(userId, guildId) {
                if (renderedUserIds[textView] == userId) {
                    val refreshedLabel = if (preserveExistingNameOnRefresh) label else displayNameFor(userId, null)
                    renderUserName(
                        textView,
                        userId,
                        refreshedLabel,
                        styleFor(userId, null),
                        roleGradient,
                        guildId,
                        preserveExistingNameOnRefresh,
                        useDisplayStyleColors = useDisplayStyleColors,
                        allowMultiline = allowMultiline,
                        allowReplacementEffects = allowReplacementEffects,
                        preserveReplacementEffectText = preserveReplacementEffectText,
                    )
                }
            }
        }

        val resolvedLabel = label.cleanName()
            ?: if (preserveExistingNameOnRefresh) null else usernameFor(userId)

        renderer.renderTextView(
            textView,
            resolvedLabel,
            style,
            roleGradient,
            settings.getBool("displayNames", true),
            settings.getBool("displayNameStyles", true),
            settings.getBool("roleGradients", true),
            useDisplayStyleColors,
            allowMultiline,
            allowReplacementEffects,
            preserveReplacementEffectText,
        )
    }

    private data class ReplyNameContext(
        val userId: Long,
        val user: Any?,
        val member: GuildMember?,
        val guildId: Long?,
        val repliedMessageId: Long,
        val configuredName: String? = null,
    )

    private fun ReplyNameContext.isSameReply(other: ReplyNameContext): Boolean =
        userId == other.userId && repliedMessageId == other.repliedMessageId

    private fun shouldPreserveReplyConfiguredName(
        configuredName: String?,
        username: String?,
        displayName: String?,
        member: GuildMember?,
    ): Boolean {
        val configured = configuredName.cleanName() ?: return member.hasCleanNick()
        if (member.hasCleanNick()) return true
        return configured != username.cleanName() && configured != displayName.cleanName()
    }

    private fun shouldPreserveConfiguredName(
        configuredName: String?,
        userId: Long,
        fallbackUser: Any?,
        member: GuildMember?,
    ): Boolean {
        val configured = configuredName.cleanName() ?: return member.hasCleanNick()
        if (member.hasCleanNick()) return true
        val username = usernameFor(userId, fallbackUser).cleanName()
        val displayName = displayNameFor(userId, fallbackUser).cleanName()
        return configured != username && configured != displayName
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
                Http.Request.newDiscordRNRequest("/guilds/$realGuildId").execute().use { response ->
                    parseGuildFeaturesPayload(realGuildId, JSONObject(response.text()))
                }
            }.onFailure {
                logger.warn("Failed to fetch modern guild features for $realGuildId", it)
                roles.setGuildEnhancedRoleColors(realGuildId, false)
            }

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

    private fun parseGuildFeaturesPayload(guildId: Long, root: JSONObject) {
        val features = root.optJSONArray("features")
            ?: root.optJSONObject("guild")?.optJSONArray("features")
        roles.setGuildEnhancedRoleColors(guildId, features.hasString("ENHANCED_ROLE_COLORS"))
    }

    private fun parseProfilePayload(userId: Long, root: JSONObject) {
        var parsedStyle: DisplayStyleData? = null
        root.optJSONObject("user")?.let { user ->
            user.optCleanString("username")?.let { profileUsernames[userId] = it }
            user.optCleanString("global_name")?.let { profileDisplayNames[userId] = it }
            parsedStyle = parseDisplayStyle(user.optJSONObject("display_name_styles")) ?: parsedStyle
        }
        root.optJSONObject("profile_user")?.let { user ->
            user.optCleanString("username")?.let { profileUsernames[userId] = it }
            user.optCleanString("global_name")?.let { profileDisplayNames[userId] = it }
            parsedStyle = parseDisplayStyle(user.optJSONObject("display_name_styles")) ?: parsedStyle
        }

        parsedStyle = parseDisplayStyle(root.optJSONObject("display_name_styles")) ?: parsedStyle
        parsedStyle = parseDisplayStyle(root.optJSONObject("guild_member")?.optJSONObject("display_name_styles")) ?: parsedStyle
        parsedStyle = parseDisplayStyle(root.optJSONObject("guild_member_profile")?.optJSONObject("display_name_styles")) ?: parsedStyle
        if (parsedStyle != null) {
            profileStyles[userId] = parsedStyle
        } else {
            profileStyles.remove(userId)
        }
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
                    ?.takeIf { it != primary }
                val tertiary = colors.optNullableColor("tertiary_color")
                    ?.takeIf { it != primary && it != secondary }
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
        profileDisplayNames[userId].cleanName()
            ?: fallbackUser.readString("globalName", "getGlobalName").cleanName()
            ?: StoreStream.getUsers().users[userId].readString("globalName", "getGlobalName").cleanName()

    private fun usernameFor(userId: Long, fallbackUser: Any? = null): String? =
        profileUsernames[userId].cleanName()
            ?: fallbackUser.readString("username", "getUsername").cleanName()
            ?: StoreStream.getUsers().users[userId]?.username.cleanName()

    private fun styleFor(userId: Long, fallbackUser: Any?): DisplayStyleData? =
        profileStyles[userId] ?: fallbackUser.readDisplayStyle() ?: StoreStream.getUsers().users[userId].readDisplayStyle()

    private fun cacheProfileObject(userId: Long, profile: Any?) {
        val apiUser = profile.readObject("user", "getUser", "g")
        cacheUserObject(userId, apiUser)
    }

    private fun cacheUserObject(userId: Long, user: Any?) {
        user.readString("username", "getUsername").cleanName()?.let {
            profileUsernames[userId] = it
        }
        user.readString("globalName", "getGlobalName").cleanName()?.let {
            profileDisplayNames[userId] = it
        }
        user.readDisplayStyle().let {
            if (it != null) {
                profileStyles[userId] = it
            } else if (user.readObject("displayNameStyles", "getDisplayNameStyles") != null) {
                profileStyles.remove(userId)
            }
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

    private fun GuildMember?.hasCleanNick(): Boolean =
        this?.nick.cleanName() != null

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
            val username = usernameFor(userId, user)
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

private fun JSONObject.optCleanString(key: String): String? =
    if (has(key) && !isNull(key)) optString(key).cleanName() else null

private fun JSONArray.optNullableColor(index: Int): Int? =
    if (isNull(index)) null else optInt(index) and 0x00ffffff

private fun JSONArray?.hasString(value: String): Boolean {
    val array = this ?: return false
    var index = 0
    while (index < array.length()) {
        if (array.optString(index) == value) return true
        index++
    }
    return false
}

private fun String?.cleanName(): String? =
    this?.trim()?.takeIf { it.isNotEmpty() && !it.equals("null", ignoreCase = true) }
