package com.github.pilotbellytspec.modernuserstyles

import android.content.Context
import android.text.Spannable
import android.text.Spanned
import android.text.SpannableStringBuilder
import android.text.style.LeadingMarginSpan
import android.util.TypedValue
import android.view.View
import android.widget.TextView
import com.aliucord.Http
import com.aliucord.Utils
import com.aliucord.annotations.AliucordPlugin
import com.aliucord.entities.Plugin
import com.aliucord.patcher.after
import com.aliucord.patcher.before
import com.discord.api.channel.ChannelUtils
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

@AliucordPlugin(requiresRestart = true)
@Suppress("unused", "UNCHECKED_CAST")
class ModernUserStyles : Plugin() {
    private lateinit var nameInk: NameRenderer
    private lateinit var roleInk: RoleGradientResolver
    private val savedUsernames = mutableMapOf<Long, String>()
    private val savedNames = mutableMapOf<Long, String>()
    private val savedStyles = mutableMapOf<Long, DisplayStyleData>()
    private val profileJobs = mutableSetOf<String>()
    private val profileSeen = mutableSetOf<String>()
    private val profileWaiters = mutableMapOf<String, MutableList<() -> Unit>>()
    private val roleJobs = mutableSetOf<Long>()
    private val roleSeen = mutableSetOf<Long>()
    private val roleWaiters = mutableMapOf<Long, MutableList<() -> Unit>>()
    private val viewOwners = WeakHashMap<TextView, Long>()
    private val rowTags = WeakHashMap<TextView, Tag>()

    init {
        settingsTab = SettingsTab(PluginSettings::class.java, SettingsTab.Type.BOTTOM_SHEET).withArgs(settings)
    }

    override fun start(context: Context) {
        nameInk = NameRenderer(context)
        roleInk = RoleGradientResolver()

        chatRows()
        memberList()
        profileHeader()
        mentionSpans()
        autocompleteRows()
        dmRows()
        dmHeader()
        toolbarTitle()
        voiceRows()
        reactionSheet()
        accountSettings()
    }

    override fun stop(context: Context) {
        patcher.unpatchAll()
        commands.unregisterAll()
    }

    private fun chatRows() {
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
            val gid = member?.guildId ?: guildOf(entry.message.channelId)
            val mem = member ?: gid?.let { guild -> StoreStream.getGuilds().getMember(guild, user.id) }
            val name = entry.nickOrUsernames[user.id].cleanName()
                ?: mem?.nick.cleanName()
                ?: displayNameFor(user.id, user)
                ?: usernameFor(user.id, user)
            ensureGuildRolesFetched(gid) {
                if (sameTag(nameView, user.id, gid, name)) {
                    val fresh = gid?.let { guild -> StoreStream.getGuilds().getMember(guild, user.id) } ?: mem
                    val role = roleInk.forMember(member ?: fresh, entry.roles)
                    renderUserName(
                        nameView,
                        user.id,
                        name,
                        styleFor(user.id, user),
                        role,
                        gid,
                        true,
                        lockRoleOnRefresh = true,
                    )
                }
            }
            val role = roleInk.forMember(mem, entry.roles)
            renderUserName(
                nameView,
                user.id,
                name,
                styleFor(user.id, user),
                role,
                gid,
                true,
                lockRoleOnRefresh = true,
            )
            renderReplyFromEntry(this, entry)
        }
    }

    private fun renderReplyFromEntry(
        row: WidgetChatListAdapterItemMessage,
        entry: MessageEntry,
    ) {
        val nameId = Utils.getResId("chat_list_adapter_item_text_decorator_reply_name", "id")
        val nameView = row.itemView.findViewById<TextView>(nameId) ?: return
        if (nameView.visibility != View.VISIBLE) return
        val replied = entry.replyData?.messageEntry ?: return
        val user = replied.message.author
        val member = replied.author
        val guildId = member?.guildId ?: guildOf(replied.message.channelId) ?: guildOf(entry.message.channelId)
        val name = nameView.text?.toString().cleanName()
            ?: replied.nickOrUsernames[user.id].cleanName()
            ?: member?.nick.cleanName()
            ?: displayNameFor(user.id, user)
            ?: usernameFor(user.id, user)
            ?: return
        cacheUserObject(user.id, user)
        renderUserName(
            nameView,
            user.id,
            name,
            styleFor(user.id, user),
            roleInk.forMember(member ?: guildId?.let { StoreStream.getGuilds().getMember(it, user.id) }, replied.roles),
            guildId,
            true,
            fetchAsync = false,
            allowReplacementEffects = false,
            keepPlainColor = true,
        )
        syncReplyPreviewInset(row)
        nameView.post { syncReplyPreviewInset(row) }
    }

    private fun syncReplyPreviewInset(row: WidgetChatListAdapterItemMessage) {
        val holderId = Utils.getResId("chat_list_adapter_item_reply_leading_views", "id")
        val textId = Utils.getResId("chat_list_adapter_item_text_reply_content", "id")
        val holder = row.itemView.findViewById<View>(holderId) ?: return
        val text = row.itemView.findViewById<TextView>(textId) ?: return
        val content = text.text ?: return
        val spans = when (content) {
            is Spannable -> content
            else -> SpannableStringBuilder(content)
        }
        holder.measure(0, 0)
        spans.getSpans(0, spans.length, LeadingMarginSpan::class.java).forEach { span ->
            spans.removeSpan(span)
        }
        if (spans.isNotEmpty()) {
            spans.setSpan(LeadingMarginSpan.Standard(holder.measuredWidth, 0), 0, spans.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        if (spans !== content) {
            text.text = spans
        } else {
            text.invalidate()
        }
    }

    private fun memberList() {
        val nameId = Utils.getResId("channel_members_list_item_name", "id")

        patcher.after<ChannelMembersListViewHolderMember>(
            "bind",
            ChannelMembersListAdapter.Item.Member::class.java,
            Function0::class.java,
        ) {
            if (!settings.getBool("memberList", true)) return@after

            val memberRow = it.args[0] as ChannelMembersListAdapter.Item.Member
            val usernameView = memberListUsernameView(this) ?: itemView.findViewById<TextView>(nameId)
            val guildId = memberRow.guildId ?: StoreStream.getGuildSelected().selectedGuildId
            val member = StoreStream.getGuilds().getMember(guildId, memberRow.userId)
            val useDisplayStyleColors = isSelectedPrivateChannel()
            val name = memberRow.name.cleanName()
                ?: member?.nick.cleanName()
                ?: displayNameFor(memberRow.userId, null)
                ?: usernameFor(memberRow.userId, null)
            ensureGuildRolesFetched(guildId) {
                val refreshedMember = StoreStream.getGuilds().getMember(guildId, memberRow.userId)
                renderUserNameViews(
                    usernameView,
                    memberRow.userId,
                    name,
                    styleFor(memberRow.userId, null),
                    roleInk.forMember(refreshedMember),
                    guildId,
                    true,
                    useDisplayStyleColors = useDisplayStyleColors,
                )
            }
            renderUserNameViews(
                usernameView,
                memberRow.userId,
                name,
                styleFor(memberRow.userId, null),
                roleInk.forMember(member),
                guildId,
                true,
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
                var childIndex = 0
                while (childIndex < root.childCount) {
                    val child = root.getChildAt(childIndex)
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
                    childIndex++
                }
            }
        }
    }

    private fun memberListUsernameView(holder: ChannelMembersListViewHolderMember): View? =
        holder.grab("binding")?.grab("f") as? View

    private fun usernameViewNameText(root: View): TextView? {
        if (root.javaClass.name != "com.discord.views.UsernameView") return null
        val binding = root.grab("j") ?: return null
        return binding.grab("c") as? TextView
    }

    private fun profileHeader() {
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
            val name = loaded.guildMember?.nick.cleanName()
                ?: displayNameFor(loaded.user.id, loaded.user)
                ?: usernameFor(loaded.user.id, loaded.user)
            ensureGuildRolesFetched(guildId) {
                if (viewOwners[nameView] == loaded.user.id) {
                    renderUserName(
                        nameView,
                        loaded.user.id,
                        name,
                        styleFor(loaded.user.id, loaded.user),
                        roleInk.forMember(loaded.guildMember),
                        guildId,
                        true,
                        useDisplayStyleColors = true,
                        allowMultiline = true,
                    )
                }
            }
            renderUserName(
                nameView,
                loaded.user.id,
                name,
                styleFor(loaded.user.id, loaded.user),
                roleInk.forMember(loaded.guildMember),
                guildId,
                true,
                useDisplayStyleColors = true,
                allowMultiline = true,
            )
        }
    }

    private fun mentionSpans() {
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
                    val colors = nameInk.colorsFor(
                        roleInk.forMember(member),
                        settings.getBool("roleGradients", true),
                    )
                    if (colors.isEmpty()) return

                    builder.setSpan(
                        NameStyleSpan(colors.toIntArray(), nameInk.effectForRoleColors(colors), end - start),
                        start,
                        end,
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                    )
                }
            },
        )
    }

    private fun autocompleteRows() {
        if (!settings.getBool("autocomplete", true)) return

        patcher.after<AutocompleteViewModel>("generateSpanUpdates", MentionInputModel::class.java) {
            val res = it.result as InputEditTextAction.ReplaceCharacterStyleSpans
            val mentionInputModel = it.args[0] as MentionInputModel

            mentionInputModel.inputMentionsMap.forEach { (key, value) ->
                val user = value as? UserAutocompletable ?: return@forEach
                val guildId = user.guildMember?.guildId
                ensureProfileFetched(user.user.id, guildId)
                ensureGuildRolesFetched(guildId)
                val colors = nameInk.colorsFor(
                    roleInk.forMember(user.guildMember),
                    settings.getBool("roleGradients", true),
                )
                if (colors.isNotEmpty()) {
                    res.spans[key] = listOf(NameStyleSpan(colors.toIntArray(), nameInk.effectForRoleColors(colors), user.user.username.length))
                }
            }
        }

        val nameId = Utils.getResId("chat_input_item_name", "id")
        patcher.after<AutocompleteItemViewHolder>("bindUser", UserAutocompletable::class.java) {
            val autocompleteUser = it.args[0] as UserAutocompletable
            val nameView = rootFromBinding(this)?.findViewById<TextView>(nameId)
            val guildId = autocompleteUser.guildMember?.guildId
            val name = autocompleteUser.guildMember?.nick.cleanName()
                ?: displayNameFor(autocompleteUser.user.id, autocompleteUser.user)
                ?: usernameFor(autocompleteUser.user.id, autocompleteUser.user)
            ensureGuildRolesFetched(guildId) {
                if (nameView != null && viewOwners[nameView] == autocompleteUser.user.id) {
                    renderUserName(
                        nameView,
                        autocompleteUser.user.id,
                        name,
                        styleFor(autocompleteUser.user.id, autocompleteUser.user),
                        roleInk.forMember(autocompleteUser.guildMember),
                        guildId,
                        true,
                    )
                }
            }
            renderUserName(
                nameView,
                autocompleteUser.user.id,
                name,
                styleFor(autocompleteUser.user.id, autocompleteUser.user),
                roleInk.forMember(autocompleteUser.guildMember),
                guildId,
                true,
            )
        }
    }

    private fun dmRows() {
        if (!settings.getBool("dmList", true)) return

        val nameId = Utils.getResId("channels_list_item_private_name", "id")
        patcher.after<WidgetChannelsListAdapter.ItemChannelPrivate>(
            "onConfigure",
            Int::class.java,
            ChannelListItem::class.java,
        ) {
            val dmRow = it.args[1] as? ChannelListItemPrivate ?: return@after
            val nameView = itemView.findViewById<TextView>(nameId)
            val meId = StoreStream.getUsers().me.id
            val recipients = dmRow.channel.z().filter { user -> user.id != meId }
            if (recipients.size != 1) {
                resetSidebarName(nameView, dmRow.channel.readString("name", "getName"))
                return@after
            }
            val recipient = recipients.first()
            val storeUser = StoreStream.getUsers().users[recipient.id]
            val label = displayNameFor(recipient.id, recipient) ?: usernameFor(recipient.id, recipient)

            if (!dmRow.selected) {
                resetSidebarName(nameView, label)
                renderUserName(
                    nameView,
                    recipient.id,
                    label,
                    styleFor(recipient.id, storeUser ?: recipient),
                    null,
                    preserveExistingNameOnRefresh = true,
                    useDisplayStyleColors = false,
                    allowReplacementEffects = false,
                    keepPlainColor = true,
                )
                return@after
            }

            renderUserNameDrawable(
                nameView,
                recipient.id,
                label,
                styleFor(recipient.id, storeUser ?: recipient),
                null,
                preserveExistingNameOnRefresh = true,
            )
        }
    }

    private fun resetSidebarName(textView: TextView?, label: String?) {
        if (textView == null) return
        val visible = textView.text?.toString().cleanName()
        val color = textView.textColors
        val typeface = textView.typeface
        val textSize = textView.textSize
        val scale = textView.textScaleX
        val spacing = textView.letterSpacing
        viewOwners.remove(textView)
        nameInk.resetTextView(textView)
        textView.setTextColor(color)
        textView.typeface = typeface
        textView.setTextSize(TypedValue.COMPLEX_UNIT_PX, textSize)
        textView.textScaleX = scale
        textView.letterSpacing = spacing
        textView.text = visible ?: label.cleanName() ?: textView.contentDescription
    }

    private fun dmHeader() {
        if (!settings.getBool("dmList", true)) return

        val headerId = Utils.getResId("chat_list_adapter_item_private_channel_start_header", "id")
        patcher.after<WidgetChatListAdapterItemPrivateChannelStart>(
            "onConfigure",
            Int::class.java,
            ChatListEntry::class.java,
        ) {
            val header = itemView.findViewById<TextView>(headerId) ?: return@after
            styleCurrentPrivateRecipient(header, allowMultiline = true, resetWhenNotMatched = true)
        }
    }

    private fun toolbarTitle() {
        if (!settings.getBool("dmList", true)) return

        patcher.after<ToolbarTitleLayout>(
            "a",
            CharSequence::class.java,
            Int::class.javaObjectType,
            Int::class.javaObjectType,
        ) {
            val toolbarLabel = (it.args[0] as? CharSequence)?.toString()
            styleCurrentPrivateRecipient(title, toolbarLabel, resetWhenNotMatched = true)
        }
    }

    private fun styleCurrentPrivateRecipient(
        textView: TextView?,
        expectedLabel: String? = null,
        allowMultiline: Boolean = false,
        resetWhenNotMatched: Boolean = false,
    ) {
        if (textView == null) return
        val channel = runCatching { StoreStream.getChannelsSelected().selectedChannel }.getOrNull()
        val expectedName = expectedLabel.cleanName()
        if (channel == null) {
            if (resetWhenNotMatched && ownsNameText(textView)) resetNameText(textView, expectedName)
            return
        }
        if (!ChannelUtils.B(channel)) {
            if (resetWhenNotMatched && ownsNameText(textView)) {
                resetNameText(textView, expectedName ?: ChannelUtils.c(channel).cleanName())
            }
            return
        }
        val recipients = runCatching { channel.z() }.getOrNull()
        if (recipients.isNullOrEmpty()) {
            if (resetWhenNotMatched) resetNameText(textView, expectedName ?: channel.readString("name", "getName"))
            return
        }
        val meId = StoreStream.getUsers().me.id
        val recipient = recipients.firstOrNull { user -> user.id != meId } ?: recipients.firstOrNull() ?: return
        val channelName = channel.readString("name", "getName").cleanName()
        if (recipients.size != 1) {
            if (resetWhenNotMatched) resetNameText(textView, expectedName ?: channelName)
            return
        }

        val storeUser = StoreStream.getUsers().users[recipient.id]
        val displayName = displayNameFor(recipient.id, recipient)
        val username = usernameFor(recipient.id, recipient)
        val wantedName = displayName ?: username
        if (expectedName != null &&
            expectedName != displayName.cleanName() &&
            expectedName != username.cleanName() &&
            expectedName != channelName
        ) {
            if (resetWhenNotMatched) resetNameText(textView, expectedName)
            return
        }
        if (viewOwners[textView] != null && viewOwners[textView] != recipient.id) {
            resetNameText(textView, wantedName)
        }
        val currentText = textView.text?.toString().cleanName()
            ?: textView.contentDescription?.toString().cleanName()
            ?: wantedName
            ?: return
        val matchesRecipient = currentText == displayName.cleanName() ||
            currentText == username.cleanName() ||
            currentText == channelName
        if (!matchesRecipient) {
            if (resetWhenNotMatched) resetNameText(textView, wantedName)
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

    private fun resetNameText(textView: TextView, label: String?) {
        val fallback = label.cleanName() ?: textView.text?.toString().cleanName()
        viewOwners.remove(textView)
        rowTags.remove(textView)
        nameInk.resetTextView(textView)
        fallback?.let { textView.text = it }
    }

    private fun ownsNameText(textView: TextView): Boolean =
        viewOwners.containsKey(textView) || rowTags.containsKey(textView)

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
        val mark = tag(userId, guildId, label)
        if (textView != null) {
            viewOwners[textView] = userId
            rowTags[textView] = mark
        }
        nameInk.renderTextViewAsDrawable(
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
                if (rowTags[textView] == mark) {
                    nameInk.renderTextViewAsDrawable(
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

    private fun voiceRows() {
        if (!settings.getBool("voiceNames", true)) return

        val voiceUserNameId = Utils.getResId("channels_item_voice_user_name", "id")
        val voiceUserListId = Utils.getResId("voice_user_list_item_user_name", "id")
        val stageSpeakerNameId = Utils.getResId("stage_channel_audience_member_name", "id")

        patcher.after<WidgetChannelsListAdapter.ItemVoiceUser>(
            "onConfigure",
            Int::class.java,
            ChannelListItem::class.java,
        ) {
            val voiceRow = it.args[1] as ChannelListItemVoiceUser
            val nameView = rootFromBinding(this)?.findViewById<TextView>(voiceUserNameId)
            val guildId = voiceRow.computed.readLong("guildId", "getGuildId")
            val member = guildId?.let { StoreStream.getGuilds().getMember(it, voiceRow.user.id) }
            val name = member?.nick.cleanName()
                ?: displayNameFor(voiceRow.user.id, voiceRow.user)
                ?: usernameFor(voiceRow.user.id, voiceRow.user)
            ensureGuildRolesFetched(guildId) {
                val refreshedMember = guildId?.let { StoreStream.getGuilds().getMember(it, voiceRow.user.id) }
                if (nameView != null && viewOwners[nameView] == voiceRow.user.id) {
                    renderUserName(
                        nameView,
                        voiceRow.user.id,
                        refreshedMember?.nick.cleanName() ?: name,
                        styleFor(voiceRow.user.id, voiceRow.user),
                        roleInk.forMember(refreshedMember),
                        guildId,
                        true,
                    )
                }
            }
            renderUserName(
                nameView,
                voiceRow.user.id,
                name,
                styleFor(voiceRow.user.id, voiceRow.user),
                roleInk.forMember(member),
                guildId,
                true,
            )
        }

        patcher.after<CallParticipantsAdapter.ViewHolderUser>(
            "onConfigure",
            Int::class.java,
            MGRecyclerDataPayload::class.java,
        ) {
            val participantRow = it.args[1] as? CallParticipantsAdapter.ListItem.VoiceUser ?: return@after
            val member = participantRow.participant.guildMember
            val nameView = rootFromBinding(this)?.findViewById<TextView>(voiceUserListId)
            val guildId = member.guildId
            val name = member.nick.cleanName()
                ?: displayNameFor(member.userId, null)
                ?: usernameFor(member.userId, null)
            ensureGuildRolesFetched(guildId) {
                if (nameView != null && viewOwners[nameView] == member.userId) {
                    renderUserName(
                        nameView,
                        member.userId,
                        name,
                        styleFor(member.userId, null),
                        roleInk.forMember(member),
                        guildId,
                        true,
                    )
                }
            }
            renderUserName(
                nameView,
                member.userId,
                name,
                styleFor(member.userId, null),
                roleInk.forMember(member),
                guildId,
                true,
            )
        }

        patcher.after<AudienceViewHolder>("onConfigure", Int::class.java, StageCallItem::class.java) {
            val audienceRow = it.args[1] as? StageCallItem.AudienceItem ?: return@after
            val member = audienceRow.voiceUser.guildMember
            val nameView = rootFromBinding(this)?.findViewById<TextView>(voiceUserListId)
            val guildId = member.guildId
            val name = member.nick.cleanName()
                ?: displayNameFor(member.userId, null)
                ?: usernameFor(member.userId, null)
            ensureGuildRolesFetched(guildId) {
                if (nameView != null && viewOwners[nameView] == member.userId) {
                    renderUserName(
                        nameView,
                        member.userId,
                        name,
                        styleFor(member.userId, null),
                        roleInk.forMember(member),
                        guildId,
                        true,
                    )
                }
            }
            renderUserName(
                nameView,
                member.userId,
                name,
                styleFor(member.userId, null),
                roleInk.forMember(member),
                guildId,
                true,
            )
        }

        patcher.after<SpeakerViewHolder>("onConfigure", Int::class.java, StageCallItem::class.java) {
            val speakerRow = it.args[1] as? StageCallItem.SpeakerItem ?: return@after
            val member = speakerRow.voiceUser.guildMember
            val nameView = rootFromBinding(this)?.findViewById<TextView>(stageSpeakerNameId)
            val guildId = member.guildId
            val name = member.nick.cleanName()
                ?: displayNameFor(member.userId, null)
                ?: usernameFor(member.userId, null)
            ensureGuildRolesFetched(guildId) {
                if (nameView != null && viewOwners[nameView] == member.userId) {
                    renderUserName(
                        nameView,
                        member.userId,
                        name,
                        styleFor(member.userId, null),
                        roleInk.forMember(member),
                        guildId,
                        true,
                    )
                }
            }
            renderUserName(
                nameView,
                member.userId,
                name,
                styleFor(member.userId, null),
                roleInk.forMember(member),
                guildId,
                true,
            )
        }
    }

    private fun reactionSheet() {
        if (!settings.getBool("reactionUsers", true)) return

        val nameId = Utils.getResId("manage_reactions_result_user_name", "id")
        patcher.after<ManageReactionsResultsAdapter.ReactionUserViewHolder>(
            "onConfigure",
            Int::class.java,
            MGRecyclerDataPayload::class.java,
        ) {
            val reactionUser = it.args[1] as? ManageReactionsResultsAdapter.ReactionUserItem ?: return@after
            val member = reactionUser.guildMember ?: return@after
            val nameView = rootFromBinding(this)?.findViewById<TextView>(nameId)
            val guildId = member.guildId
            val name = member.nick.cleanName()
                ?: displayNameFor(member.userId, null)
                ?: usernameFor(member.userId, null)
            ensureGuildRolesFetched(guildId) {
                if (nameView != null && viewOwners[nameView] == member.userId) {
                    renderUserName(
                        nameView,
                        member.userId,
                        name,
                        styleFor(member.userId, null),
                        roleInk.forMember(member),
                        guildId,
                        true,
                    )
                }
            }
            renderUserName(
                nameView,
                member.userId,
                name,
                styleFor(member.userId, null),
                roleInk.forMember(member),
                guildId,
                true,
            )
        }
    }

    private fun accountSettings() {
        val modelClass = WidgetSettingsAccount.Model::class.java
        patcher.after<WidgetSettingsAccount>("configureUI", modelClass) {
            val accountModel = it.args[0]
            val me = accountModel.grab("meUser", "getMeUser") ?: StoreStream.getUsers().me
            val meId = me.readLong("id", "getId") ?: StoreStream.getUsers().me.id
            cacheUserObject(meId, me)

            val accountRoot = WidgetSettingsAccount.`access$getBinding$p`(this).root
            styleMatchingTextViews(accountRoot, meId, me)
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
        keepPlainColor: Boolean = false,
        lockRoleOnRefresh: Boolean = false,
    ) {
        if (textView != null && fetchAsync) {
            viewOwners[textView] = userId
            val mark = tag(userId, guildId, label)
            rowTags[textView] = mark
            ensureProfileFetched(userId, guildId) {
                if (rowTags[textView] == mark) {
                    val refreshedLabel = if (preserveExistingNameOnRefresh) label else displayNameFor(userId, null)
                    val refreshedRole = if (lockRoleOnRefresh) roleGradient else roleFor(userId, guildId) ?: roleGradient
                    renderUserName(
                        textView,
                        userId,
                        refreshedLabel,
                        styleFor(userId, null),
                        refreshedRole,
                        guildId,
                        preserveExistingNameOnRefresh,
                        useDisplayStyleColors = useDisplayStyleColors,
                        allowMultiline = allowMultiline,
                        allowReplacementEffects = allowReplacementEffects,
                        preserveReplacementEffectText = preserveReplacementEffectText,
                        keepPlainColor = keepPlainColor,
                        lockRoleOnRefresh = lockRoleOnRefresh,
                    )
                }
            }
        }

        val resolvedLabel = label.cleanName()
            ?: if (preserveExistingNameOnRefresh) null else usernameFor(userId)

        nameInk.renderTextView(
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
            keepPlainColor,
        )
    }

    private fun guildOf(channelId: Long): Long? =
        StoreStream.getChannels().getChannel(channelId).readLong("guildId", "getGuildId", "i")?.takeIf { it != 0L }

    private fun roleFor(userId: Long, guildId: Long?): RoleGradient? =
        guildId?.let { guild -> roleInk.forMember(StoreStream.getGuilds().getMember(guild, userId)) }

    private fun sameTag(textView: TextView?, userId: Long, guildId: Long?, label: String?): Boolean =
        textView != null && rowTags[textView] == tag(userId, guildId, label)

    private fun tag(userId: Long, guildId: Long?, label: String?): Tag =
        Tag(userId, guildId?.takeIf { it != 0L }, label.cleanName())

    private data class Tag(
        val userId: Long,
        val guildId: Long?,
        val label: String?,
    )

    private fun ensureProfileFetched(userId: Long, guildId: Long? = null, onLoaded: (() -> Unit)? = null) {
        val realGuildId = guildId?.takeIf { it != 0L }
        val profileKey = "$userId:${realGuildId ?: 0L}"
        var shouldFetch = false
        synchronized(profileJobs) {
            if (profileSeen.contains(profileKey)) return
            if (onLoaded != null) profileWaiters.getOrPut(profileKey) { mutableListOf() }.add(onLoaded)
            shouldFetch = profileJobs.add(profileKey)
        }
        if (!shouldFetch) return

        Utils.threadPool.execute {
            runCatching {
                val profileRoute = "/users/$userId/profile?type=popout&with_mutual_guilds=true&with_mutual_friends=true&with_mutual_friends_count=false" +
                    if (realGuildId == null) "" else "&guild_id=$realGuildId"
                Http.Request.newDiscordRNRequest(profileRoute).execute().use { response ->
                    parseProfilePayload(userId, JSONObject(response.text()))
                }
            }.onFailure {
                logger.warn("Failed to fetch modern profile data for $userId", it)
            }

            val callbacks = synchronized(profileJobs) {
                profileSeen.add(profileKey)
                profileWaiters.remove(profileKey).orEmpty()
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
        synchronized(roleJobs) {
            if (roleSeen.contains(realGuildId)) return
            if (onLoaded != null) roleWaiters.getOrPut(realGuildId) { mutableListOf() }.add(onLoaded)
            shouldFetch = roleJobs.add(realGuildId)
        }
        if (!shouldFetch) return

        Utils.threadPool.execute {
            runCatching {
                Http.Request.newDiscordRNRequest("/guilds/$realGuildId").execute().use { response ->
                    parseGuildFeaturesPayload(realGuildId, JSONObject(response.text()))
                }
            }.onFailure {
                logger.warn("Failed to fetch modern guild features for $realGuildId", it)
                roleInk.setGuildEnhancedRoleColors(realGuildId, false)
            }

            runCatching {
                Http.Request.newDiscordRNRequest("/guilds/$realGuildId/roles").execute().use { response ->
                    parseGuildRolesPayload(JSONArray(response.text()))
                }
            }.onFailure {
                logger.warn("Failed to fetch modern role colors for $realGuildId", it)
            }

            val callbacks = synchronized(roleJobs) {
                roleSeen.add(realGuildId)
                roleWaiters.remove(realGuildId).orEmpty()
            }
            if (callbacks.isNotEmpty()) {
                Utils.mainThread.post {
                    callbacks.forEach { callback -> callback() }
                }
            }
        }
    }

    private fun parseGuildFeaturesPayload(guildId: Long, guildJson: JSONObject) {
        val features = guildJson.optJSONArray("features")
            ?: guildJson.optJSONObject("guild")?.optJSONArray("features")
        roleInk.setGuildEnhancedRoleColors(guildId, features.hasString("ENHANCED_ROLE_COLORS"))
    }

    private fun parseProfilePayload(userId: Long, profileJson: JSONObject) {
        var parsedStyle: DisplayStyleData? = null
        profileJson.optJSONObject("user")?.let { userJson ->
            userJson.optCleanString("username")?.let { savedUsernames[userId] = it }
            userJson.optCleanString("global_name")?.let { savedNames[userId] = it }
            parsedStyle = parseDisplayStyle(userJson.optJSONObject("display_name_styles")) ?: parsedStyle
        }
        profileJson.optJSONObject("profile_user")?.let { userJson ->
            userJson.optCleanString("username")?.let { savedUsernames[userId] = it }
            userJson.optCleanString("global_name")?.let { savedNames[userId] = it }
            parsedStyle = parseDisplayStyle(userJson.optJSONObject("display_name_styles")) ?: parsedStyle
        }

        parsedStyle = parseDisplayStyle(profileJson.optJSONObject("display_name_styles")) ?: parsedStyle
        parsedStyle = parseDisplayStyle(profileJson.optJSONObject("guild_member")?.optJSONObject("display_name_styles")) ?: parsedStyle
        parsedStyle = parseDisplayStyle(profileJson.optJSONObject("guild_member_profile")?.optJSONObject("display_name_styles")) ?: parsedStyle
        if (parsedStyle != null) {
            savedStyles[userId] = parsedStyle
        } else {
            savedStyles.remove(userId)
        }
    }

    private fun parseGuildRolesPayload(rolesJson: JSONArray) {
        var roleIndex = 0
        while (roleIndex < rolesJson.length()) {
            val role = rolesJson.optJSONObject(roleIndex)
            val roleId = role?.optString("id")?.toLongOrNull()
            val colors = role?.optJSONObject("colors")
            if (roleId != null && colors != null) {
                val primary = colors.optNullableColor("primary_color") ?: role.optNullableColor("color")
                val secondary = colors.optNullableColor("secondary_color")
                    ?.takeIf { it != primary }
                val tertiary = colors.optNullableColor("tertiary_color")
                    ?.takeIf { it != primary && it != secondary }
                if (primary != null && primary != 0) {
                    roleInk.setRuntimeRoleGradient(
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
            roleIndex++
        }
    }

    private fun parseDisplayStyle(styleJson: JSONObject?): DisplayStyleData? {
        styleJson ?: return null

        val colors = mutableListOf<Int>()
        styleJson.optJSONArray("colors")?.let { colorJson ->
            var colorIndex = 0
            while (colorIndex < colorJson.length()) {
                colorJson.optNullableColor(colorIndex)?.let(colors::add)
                colorIndex++
            }
        }

        val fontId = styleJson.optNullableInt("font_id") ?: styleJson.optNullableInt("fontId")
        val effectId = styleJson.optNullableInt("effect_id") ?: styleJson.optNullableInt("effectId")
        if (colors.isEmpty() && fontId == null && effectId == null) return null

        return DisplayStyleData(
            fontId = fontId,
            effectId = effectId,
            colors = colors.map { it and 0x00ffffff },
        )
    }

    private fun displayNameFor(userId: Long, fallbackUser: Any?): String? =
        savedNames[userId].cleanName()
            ?: fallbackUser.readString("globalName", "getGlobalName").cleanName()
            ?: StoreStream.getUsers().users[userId].readString("globalName", "getGlobalName").cleanName()

    private fun usernameFor(userId: Long, fallbackUser: Any? = null): String? =
        savedUsernames[userId].cleanName()
            ?: fallbackUser.readString("username", "getUsername").cleanName()
            ?: StoreStream.getUsers().users[userId]?.username.cleanName()

    private fun styleFor(userId: Long, fallbackUser: Any?): DisplayStyleData? {
        fallbackUser.readDisplayStyle()?.let {
            savedStyles[userId] = it
            return it
        }
        if (fallbackUser.hasSlot("displayNameStyles", "getDisplayNameStyles")) {
            savedStyles.remove(userId)
            return null
        }

        val storeUser = StoreStream.getUsers().users[userId]
        storeUser.readDisplayStyle()?.let {
            savedStyles[userId] = it
            return it
        }
        if (storeUser.hasSlot("displayNameStyles", "getDisplayNameStyles")) {
            savedStyles.remove(userId)
            return null
        }

        return savedStyles[userId]
    }

    private fun cacheProfileObject(userId: Long, profile: Any?) {
        val apiUser = profile.grab("user", "getUser", "g")
        cacheUserObject(userId, apiUser)
    }

    private fun cacheUserObject(userId: Long, user: Any?) {
        user.readString("username", "getUsername").cleanName()?.let {
            savedUsernames[userId] = it
        }
        user.readString("globalName", "getGlobalName").cleanName()?.let {
            savedNames[userId] = it
        }
        val style = user.readDisplayStyle()
        if (style != null) {
            savedStyles[userId] = style
        } else if (user.hasSlot("displayNameStyles", "getDisplayNameStyles")) {
            savedStyles.remove(userId)
        }
    }

    private fun Any?.readDisplayStyle(): DisplayStyleData? {
        val reflectedStyle = this.grab("displayNameStyles", "getDisplayNameStyles") ?: return null
        val colors = reflectedStyle.readIntList("colors", "getColors")
        if (colors.isEmpty() && reflectedStyle.readInt("fontId", "getFontId") == null && reflectedStyle.readInt("effectId", "getEffectId") == null) {
            return null
        }

        return DisplayStyleData(
            fontId = reflectedStyle.readInt("fontId", "getFontId"),
            effectId = reflectedStyle.readInt("effectId", "getEffectId"),
            colors = colors.map { it and 0x00ffffff },
        )
    }

    private fun Any?.readString(vararg names: String): String? =
        grab(*names) as? String

    private fun Any?.readInt(vararg names: String): Int? =
        when (val reflectedValue = grab(*names)) {
            is Int -> reflectedValue
            is Number -> reflectedValue.toInt()
            else -> null
        }

    private fun Any?.readLong(vararg names: String): Long? =
        when (val reflectedValue = grab(*names)) {
            is Long -> reflectedValue
            is Number -> reflectedValue.toLong()
            else -> null
        }

    private fun Any?.readIntList(vararg names: String): List<Int> {
        val reflectedValue = grab(*names) ?: return emptyList()
        if (reflectedValue is Iterable<*>) return reflectedValue.mapNotNull { (it as? Number)?.toInt() }
        if (reflectedValue.javaClass.isArray) {
            val numbers = mutableListOf<Int>()
            var arrayIndex = 0
            val length = java.lang.reflect.Array.getLength(reflectedValue)
            while (arrayIndex < length) {
                (java.lang.reflect.Array.get(reflectedValue, arrayIndex) as? Number)?.toInt()?.let(numbers::add)
                arrayIndex++
            }
            return numbers
        }
        return emptyList()
    }

    private fun Any?.grab(vararg names: String): Any? {
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

    private fun Any?.hasSlot(vararg names: String): Boolean {
        val target = this ?: return false
        var cls: Class<*>?
        names.forEach { name ->
            cls = target.javaClass
            while (true) {
                val check = cls ?: break
                runCatching {
                    check.getDeclaredField(name)
                    return true
                }
                runCatching {
                    check.getDeclaredMethod(name)
                    return true
                }
                cls = check.superclass
            }
        }
        return false
    }

    private fun styleMatchingTextViews(root: View, userId: Long, user: Any?) {
        if (root is TextView) {
            val visibleName = root.text?.toString()
            val username = usernameFor(userId, user)
            val global = displayNameFor(userId, user)
            if (visibleName == username || visibleName == global) {
                renderUserName(root, userId, global, styleFor(userId, user), null)
            }
        }

        val kids = root as? android.view.ViewGroup ?: return
        var childIndex = 0
        while (childIndex < kids.childCount) {
            styleMatchingTextViews(kids.getChildAt(childIndex), userId, user)
            childIndex++
        }
    }

    private fun rootFromBinding(holder: Any): View? {
        holder.javaClass.declaredFields.forEach { field ->
            val binding = runCatching {
                field.isAccessible = true
                field[holder]
            }.getOrNull() ?: return@forEach

            val bindingRoot = runCatching { binding.javaClass.getMethod("getRoot").invoke(binding) as? View }.getOrNull()
                ?: runCatching {
                    binding.javaClass.getDeclaredField("root").apply { isAccessible = true }[binding] as? View
                }.getOrNull()
            if (bindingRoot != null) return bindingRoot
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
    val strings = this ?: return false
    var stringIndex = 0
    while (stringIndex < strings.length()) {
        if (strings.optString(stringIndex) == value) return true
        stringIndex++
    }
    return false
}

private fun String?.cleanName(): String? =
    this?.trim()?.takeIf { it.isNotEmpty() && !it.equals("null", ignoreCase = true) }
