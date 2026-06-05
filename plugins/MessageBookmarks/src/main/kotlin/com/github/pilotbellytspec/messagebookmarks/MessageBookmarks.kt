package com.github.pilotbellytspec.messagebookmarks

import android.app.Activity
import android.app.Application
import android.app.DatePickerDialog
import android.app.PendingIntent
import android.app.TimePickerDialog
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.MenuItem
import android.view.View
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.widget.NestedScrollView
import com.aliucord.Utils
import com.aliucord.annotations.AliucordPlugin
import com.aliucord.api.GatewayAPI
import com.aliucord.api.NotificationsAPI
import com.aliucord.entities.NotificationData
import com.aliucord.entities.Plugin
import com.aliucord.patcher.after
import com.aliucord.utils.RxUtils.subscribe
import com.discord.app.AppFragment
import com.discord.databinding.WidgetChatListActionsBinding
import com.discord.api.message.Message as ApiMessage
import com.discord.api.premium.PremiumTier
import com.discord.api.role.GuildRole
import com.discord.api.utcdatetime.UtcDateTime
import com.discord.api.user.User as ApiUser
import com.discord.models.message.Message
import com.discord.stores.StoreNavigation
import com.discord.stores.StoreStream
import com.discord.utilities.channel.ChannelSelector
import com.discord.utilities.fcm.NotificationClient
import com.discord.utilities.color.ColorCompat
import com.discord.widgets.chat.list.adapter.WidgetChatListAdapter
import com.discord.widgets.chat.list.adapter.WidgetChatListAdapterItemMessage
import com.discord.widgets.chat.list.actions.WidgetChatListActions
import com.discord.widgets.chat.list.entries.ChatListEntry
import com.discord.widgets.chat.list.entries.MessageHeaderEntry
import com.discord.widgets.chat.list.entries.MessageEntry
import com.discord.widgets.tabs.NavigationTab
import com.discord.widgets.user.WidgetUserMentions
import com.lytefast.flexinput.R
import org.json.JSONObject
import rx.functions.Action1
import rx.functions.Action2
import java.lang.ref.WeakReference
import java.util.Calendar
import java.util.Date
import java.util.WeakHashMap
import de.robv.android.xposed.XC_MethodHook

@AliucordPlugin(requiresRestart = false)
@Suppress("unused")
class MessageBookmarks : Plugin() {
    private companion object {
        const val EXTRA_REMINDER_TAP = "messagebookmarks.reminder_tap"
        const val EXTRA_REMINDER_CHANNEL_ID = "messagebookmarks.channel_id"
        const val EXTRA_REMINDER_MESSAGE_ID = "messagebookmarks.message_id"
    }

    private lateinit var stash: BookmarkStore
    private lateinit var cloudStash: BookmarkSync
    private lateinit var appContext: Context
    private var running = false
    private var appForeground = true
    private var lifecycleCallbacks: Application.ActivityLifecycleCallbacks? = null
    private var application: Application? = null
    private val actionBookmarkId = View.generateViewId()
    private val actionReminderId = View.generateViewId()
    private val actionCompleteId = View.generateViewId()
    private val menuBookmarksId = View.generateViewId()
    private val reminderTicks = mutableMapOf<String, Runnable>()
    private val bookmarkTabs = WeakHashMap<WidgetUserMentions, Boolean>()
    private val mentionCycles = WeakHashMap<WidgetUserMentions, Int>()
    private val mentionBackups = WeakHashMap<WidgetUserMentions, WidgetUserMentions.Model>()
    private val seenMessages = mutableMapOf<String, Message>()
    private val localRows = mutableSetOf<String>()
    private var bookmarkFragment: WeakReference<WidgetUserMentions>? = null

    private val getActionsBinding = WidgetChatListActions::class.java
        .getDeclaredMethod("getBinding")
        .apply { isAccessible = true }

    init {
        settingsTab = SettingsTab(PluginSettings::class.java, SettingsTab.Type.BOTTOM_SHEET).withArgs(settings)
    }

    override fun start(context: Context) {
        running = true
        appContext = context.applicationContext
        stash = BookmarkStore(settings)
        cloudStash = BookmarkSync(settings, stash) { Utils.showToast(it) }
        stash.listen { scheduleReminders() }

        watchAppState(context)
        msgMenu(context)
        recentMentions(context)
        localBookmarkClicks()
        gateway()
        cloudStash.fetch()
        scheduleReminders()
    }

    override fun stop(context: Context) {
        running = false
        patcher.unpatchAll()
        reminderTicks.values.forEach { Utils.mainThread.removeCallbacks(it) }
        reminderTicks.clear()
        lifecycleCallbacks?.let { callbacks ->
            application?.unregisterActivityLifecycleCallbacks(callbacks)
        }
        lifecycleCallbacks = null
        application = null
    }

    private fun watchAppState(context: Context) {
        val app = context.applicationContext as? Application ?: return
        application = app
        lifecycleCallbacks = object : Application.ActivityLifecycleCallbacks {
            private var resumedActivities = 0

            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
                handleReminderTap(activity.intent)
            }

            override fun onActivityStarted(activity: Activity) {}

            override fun onActivityResumed(activity: Activity) {
                resumedActivities++
                appForeground = true
                handleReminderTap(activity.intent)
            }

            override fun onActivityPaused(activity: Activity) {
                resumedActivities = (resumedActivities - 1).coerceAtLeast(0)
                Utils.mainThread.postDelayed({
                    appForeground = resumedActivities > 0
                }, 250)
            }

            override fun onActivityStopped(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        }
        app.registerActivityLifecycleCallbacks(lifecycleCallbacks)
    }

    private fun msgMenu(context: Context) {
        val actionsContainerId = Utils.getResId("dialog_chat_actions_container", "id")
        val starIcon = ContextCompat.getDrawable(context, R.e.ic_star_24dp)?.mutate()
        val clockIcon = ContextCompat.getDrawable(context, R.e.ic_clock_24dp)?.mutate()
        val doneIcon = ContextCompat.getDrawable(context, R.e.ic_done_green_24dp)?.mutate()

        patcher.after<WidgetChatListActions>(
            "onViewCreated",
            View::class.java,
            Bundle::class.java,
        ) {
            val actionList = (it.args[0] as NestedScrollView).getChildAt(0) as LinearLayout
            val actionContext = actionList.context
            val tint = ColorCompat.getThemedColor(actionContext, R.b.colorInteractiveNormal)
            listOf(starIcon, clockIcon, doneIcon).forEach { icon -> icon?.setTint(tint) }

            actionList.addView(actionItem(actionContext, actionBookmarkId, "Bookmark Message", starIcon), 1)
            actionList.addView(actionItem(actionContext, actionReminderId, "Create Reminder", clockIcon), 2)
            actionList.addView(actionItem(actionContext, actionCompleteId, "Complete Reminder", doneIcon), 3)
        }

        patcher.after<WidgetChatListActions>(
            "configureUI",
            WidgetChatListActions.Model::class.java,
        ) {
            val sheet = this
            val actionModel = it.args[0] as WidgetChatListActions.Model
            val selectedMessage = actionModel.message
            val binding = getActionsBinding.invoke(sheet) as WidgetChatListActionsBinding
            val actionList = binding.root.findViewById<LinearLayout>(actionsContainerId)
            val bookmark = stash.get(selectedMessage.channelId, selectedMessage.id)

            actionList.findViewById<TextView>(actionBookmarkId)?.apply {
                text = if (bookmark == null) "Bookmark Message" else "Remove Bookmark"
                setOnClickListener {
                    if (bookmark == null) {
                        val saved = stash.upsert(selectedMessage)
                        seenMessages[saved.key] = selectedMessage
                        cloudStash.create(saved)
                        Utils.showToast("Bookmarked message")
                    } else {
                        remove(bookmark)
                    }
                    sheet.dismiss()
                }
            }
            actionList.findViewById<TextView>(actionReminderId)?.apply {
                text = if (bookmark?.dueAt == null) "Create Reminder" else "Edit Reminder"
                setOnClickListener {
                    showReminderPicker(sheet.requireContext(), selectedMessage, bookmark)
                    sheet.dismiss()
                }
            }
            actionList.findViewById<TextView>(actionCompleteId)?.apply {
                visibility = if (bookmark?.dueAt == null) View.GONE else View.VISIBLE
                setOnClickListener {
                    remove(bookmark!!)
                    sheet.dismiss()
                }
            }
        }
    }

    private fun recentMentions(context: Context) {
        val mentionsMenuId = Utils.getResId("menu_user_mentions", "menu")

        patcher.after<AppFragment>(
            "setActionBarOptionsMenu",
            Int::class.javaPrimitiveType!!,
            Action2::class.java,
            Action1::class.java,
        ) {
            if (this !is WidgetUserMentions) return@after
            if (!settings.getBool("showBookmarksButton", true)) return@after
            if (it.args[0] != mentionsMenuId) return@after

            val toolbarView = it.result as? androidx.appcompat.widget.Toolbar ?: return@after
            if (toolbarView.menu.findItem(menuBookmarksId) != null) return@after

            val star = toolbarView.menu.add(0, menuBookmarksId, 0, "Bookmarks")
            star.setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
            ContextCompat.getDrawable(context, R.e.ic_star_24dp)?.mutate()?.let { icon ->
                icon.setTint(ColorCompat.getThemedColor(toolbarView.context, R.b.colorInteractiveNormal))
                star.icon = icon
            }
            star.setOnMenuItemClickListener {
                val isBookmarks = bookmarkTabs[this] == true
                bookmarkTabs[this] = !isBookmarks
                nextRenderVersion(this)
                if (isBookmarks) {
                    restoreRecentMentions(this)
                } else {
                    openShelf(this)
                }
                true
            }
        }

        patcher.after<WidgetUserMentions>("configureUI", WidgetUserMentions.Model::class.java) {
            val mentionsModel = it.args[0] as WidgetUserMentions.Model
            mentionBackups[this] = mentionsModel
            if (bookmarkTabs[this] == true) {
                openShelf(this)
            } else {
                updateRecentHeader(this, mentionsModel)
            }
        }
    }

    private fun nextRenderVersion(fragment: WidgetUserMentions): Int {
        val version = (mentionCycles[fragment] ?: 0) + 1
        mentionCycles[fragment] = version
        return version
    }

    private fun restoreRecentMentions(fragment: WidgetUserMentions) {
        val recentModel = mentionBackups[fragment]
        if (recentModel != null) {
            try {
                WidgetUserMentions::class.java
                    .getDeclaredMethod("configureUI", WidgetUserMentions.Model::class.java)
                    .apply { isAccessible = true }
                    .invoke(fragment, recentModel)
            } catch (_: Throwable) {
                mentionsAdapter(fragment)?.setData(recentModel)
                fragment.setActionBarSubtitle(recentModel.guildName ?: "All Servers")
            }
            fragment.setActionBarTitle("Recent Mentions")
        } else {
            fragment.setActionBarTitle("Recent Mentions")
            fragment.setActionBarSubtitle("All Servers")
        }
    }

    private fun updateRecentHeader(fragment: WidgetUserMentions, model: WidgetUserMentions.Model? = mentionBackups[fragment]) {
        fragment.setActionBarTitle("Recent Mentions")
        fragment.setActionBarSubtitle(model?.guildName ?: "All Servers")
    }

    private fun openShelf(fragment: WidgetUserMentions) {
        bookmarkFragment = WeakReference(fragment)
        val renderVersion = nextRenderVersion(fragment)
        val adapter = mentionsAdapter(fragment) ?: return
        val bookmarks = stash.all()
        val messages = bookmarks.mapNotNull(::cachedMessage)
        val channels = bookmarkChannelNames(bookmarks)

        val loader = WidgetUserMentions.Model.MessageLoader(1000L)
        loader.mentionsLoadingStateSubject.onNext(
            WidgetUserMentions.Model.MessageLoader.LoadingState(false, true, messages),
        )
        @Suppress("UNCHECKED_CAST")
        val modelObservable = WidgetUserMentions.Model::class.java
            .getDeclaredField("Companion")
            .get(null)
            .let { companion ->
                companion.javaClass
                    .getDeclaredMethod("get", WidgetUserMentions.Model.MessageLoader::class.java, NavigationTab::class.java)
                    .invoke(companion, loader, NavigationTab.MENTIONS) as rx.Observable<WidgetUserMentions.Model>
            }

        modelObservable
            .Z(1)
            .subscribe {
                if (bookmarkTabs[fragment] != true || mentionCycles[fragment] != renderVersion) return@subscribe
                val tabModel = this
                val rows = bookmarkRows(bookmarks, tabModel.list)
                val loaded = rows.count {
                    it is MessageEntry && BookmarkRecord.key(it.message.channelId, it.message.id) in bookmarks.map { saved -> saved.key }
                }
                adapter.setData(
                    tabModel.copy(
                        tabModel.userId,
                        tabModel.channelId,
                        tabModel.guild,
                        tabModel.guildId,
                        tabModel.channelNames + channels,
                        tabModel.oldestMessageId,
                        rows,
                        tabModel.myRoleIds,
                        tabModel.newMessagesMarkerMessageId,
                        tabModel.isSpoilerClickAllowed,
                        tabModel.animateEmojis,
                        "Bookmarks",
                        tabModel.selectedTab,
                    ),
                )
                fragment.setActionBarTitle("Bookmarks")
                fragment.setActionBarSubtitle(bookmarkSubtitle(bookmarks.size, loaded))
            }
    }

    private fun bookmarkRows(bookmarks: List<BookmarkRecord>, recentRows: List<ChatListEntry>): List<ChatListEntry> {
        val keys = bookmarks.map { it.key }.toSet()
        val rows = recentRows.filterNot { it.javaClass.simpleName == "MentionFooterEntry" || it.javaClass.simpleName == "LoadingEntry" }
        val shown = rows.mapNotNull {
            val entry = it as? MessageEntry ?: return@mapNotNull null
            BookmarkRecord.key(entry.message.channelId, entry.message.id)
        }.toSet()
        val missing = bookmarks.filter { it.key !in shown }.flatMap(::fallbackBookmarkRows)

        return (rows + missing).filter {
            it !is MessageEntry || BookmarkRecord.key(it.message.channelId, it.message.id) in keys
        }
    }

    private fun fallbackBookmarkRows(bookmark: BookmarkRecord): List<ChatListEntry> {
        val entry = bookmarkEntry(bookmark) ?: return emptyList()
        return listOf(
            MessageHeaderEntry(entry.message, bookmarkGuildName(bookmark), bookmarkChannelName(bookmark)),
            entry,
        )
    }

    private fun bookmarkEntry(bookmark: BookmarkRecord): MessageEntry? {
        val message = cachedMessage(bookmark) ?: return null
        val authorId = message.author?.id ?: bookmark.authorId
        val names = if (authorId == null) {
            emptyMap()
        } else {
            mapOf(authorId to (bookmark.authorName ?: message.author?.username ?: "Unknown User"))
        }
        return MessageEntry(
            message,
            null,
            null,
            null,
            emptyMap<Long, GuildRole>(),
            names,
            false,
            false,
            true,
            null,
            null,
            false,
            false,
            false,
            null,
            null,
        )
    }

    private fun bookmarkChannelNames(bookmarks: List<BookmarkRecord>): Map<Long, String> =
        bookmarks.mapNotNull { bookmark ->
            bookmarkChannelName(bookmark)?.let { name -> bookmark.channelId to name }
        }.toMap()

    private fun bookmarkChannelName(bookmark: BookmarkRecord): String? =
        runCatching { StoreStream.getChannels().getChannel(bookmark.channelId)?.p() }.getOrNull()
            ?: bookmark.channelName
            ?: bookmark.authorName?.let { "@$it" }

    private fun bookmarkGuildName(bookmark: BookmarkRecord): String? =
        bookmark.guildId?.let { guildId ->
            runCatching {
                val guild = StoreStream.getGuilds().getGuild(guildId) ?: return@runCatching null
                guild.javaClass.getDeclaredMethod("getName").invoke(guild) as? String
            }.getOrNull()
        }

    private fun cachedMessage(bookmark: BookmarkRecord): Message? =
        StoreStream.getMessages().getMessage(bookmark.channelId, bookmark.messageId)?.also { message ->
                localRows.remove(bookmark.key)
                seenMessages[bookmark.key] = message
            }
            ?: seenMessages[bookmark.key]
            ?: localMessage(bookmark)?.also { message ->
                localRows.add(bookmark.key)
                seenMessages[bookmark.key] = message
            }

    private fun localBookmarkClicks() {
        patcher.patch(
            Class.forName("com.discord.widgets.user.WidgetUserMentions\$UserMentionsAdapterEventHandler")
                .getDeclaredMethod("onMessageClicked", Message::class.java, Boolean::class.javaPrimitiveType),
            object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val message = param.args[0] as? Message ?: return
                    val key = BookmarkRecord.key(message.channelId, message.id)
                    if (key !in localRows) return

                    openBookmarkMessage(message.channelId, message.id)
                    param.result = null
                }
            },
        )

        patcher.after<WidgetChatListAdapterItemMessage>(
            "onConfigure",
            Int::class.java,
            ChatListEntry::class.java,
        ) {
            val entry = it.args[1] as? MessageEntry ?: return@after
            val message = entry.message
            val key = BookmarkRecord.key(message.channelId, message.id)
            if (key !in localRows) return@after

            itemView.setOnClickListener {
                openBookmarkMessage(message.channelId, message.id)
            }
        }
    }

    private fun openBookmarkMessage(channelId: Long, messageId: Long) {
        runCatching { selectBookmarkChannel(channelId) }
        closeBookmarksPage()
        closeHomePanel()
        jumpAfter(channelId, messageId, 0)
        Utils.mainThread.postDelayed({
            if (running) {
                closeBookmarksPage()
                closeHomePanel()
                StoreStream.getMessagesLoader().jumpToMessage(channelId, messageId)
            }
        }, 450)
        jumpAfter(channelId, messageId, 1400)
        jumpAfter(channelId, messageId, 2800)
    }

    private fun closeBookmarksPage() {
        val fragment = bookmarkFragment?.get() ?: return
        if (bookmarkTabs[fragment] != true) return
        bookmarkTabs[fragment] = false
        updateRecentHeader(fragment)
        Utils.mainThread.post {
            runCatching {
                val field = listOf("dismissViewModel", "dismissViewModel\$delegate")
                    .firstNotNullOfOrNull { name ->
                        runCatching {
                            WidgetUserMentions::class.java.getDeclaredField(name).apply { isAccessible = true }
                        }.getOrNull()
                    } ?: return@runCatching
                val lazy = field.get(fragment)
                val model = lazy.javaClass.getDeclaredMethod("getValue").invoke(lazy)
                model.javaClass.getDeclaredMethod("dismiss").invoke(model)
            }

            Utils.mainThread.postDelayed({
                if (fragment.isAdded && fragment.isResumed && fragment.view != null) {
                    runCatching { fragment.requireActivity().onBackPressed() }
                }
            }, 150)
        }
    }

    private fun selectBookmarkChannel(channelId: Long) {
        val channel = StoreStream.getChannels().getChannel(channelId)
        if (channel == null) {
            ChannelSelector.getInstance().findAndSetThread(appContext, channelId)
        } else {
            ChannelSelector.getInstance().selectChannel(channel, null, null)
        }
    }

    private fun jumpAfter(channelId: Long, messageId: Long, delay: Long) {
        val jump = {
            if (running) {
                closeHomePanel()
                StoreStream.getMessagesLoader().jumpToMessage(channelId, messageId)
            }
        }
        if (delay == 0L) {
            jump()
        } else {
            Utils.mainThread.postDelayed(jump, delay)
        }
    }

    private fun closeHomePanel() {
        runCatching {
            val stream = StoreStream::class.java.getDeclaredField("INSTANCE").get(null)
            val navigation = stream.javaClass.getDeclaredMethod("getNavigation").invoke(stream)
            navigation.javaClass
                .getDeclaredMethod("setNavigationPanelAction", StoreNavigation.PanelAction::class.java)
                .invoke(navigation, StoreNavigation.PanelAction.CLOSE)
        }
        runCatching {
            val stream = StoreStream::class.java.getDeclaredField("INSTANCE").get(null)
            val tabs = stream.javaClass.getDeclaredMethod("getTabsNavigation").invoke(stream)
            tabs.javaClass
                .getDeclaredMethod(
                    "selectHomeTab",
                    StoreNavigation.PanelAction::class.java,
                    Boolean::class.javaPrimitiveType,
                )
                .invoke(tabs, StoreNavigation.PanelAction.CLOSE, true)
        }
    }

    private fun localMessage(bookmark: BookmarkRecord): Message? =
        runCatching {
            val raw = ApiMessage(
                bookmark.messageId,
                bookmark.channelId,
                localAuthor(bookmark),
                bookmark.content ?: "",
                UtcDateTime(bookmark.savedAt),
                null,
                false,
                false,
                emptyList<Any?>(),
                emptyList<Any?>(),
                emptyList<Any?>(),
                emptyList<Any?>(),
                emptyList<Any?>(),
                null,
                false,
                null,
                0,
                null,
                null,
                null,
                null,
                null,
                emptyList<Any?>(),
                emptyList<Any?>(),
                null,
                null,
                null,
                emptyList<Any?>(),
                null,
                bookmark.guildId,
                null,
                false,
                null,
                0,
                0,
            )
            Message(raw)
        }.getOrNull()

    private fun localAuthor(bookmark: BookmarkRecord): ApiUser? =
        bookmark.authorId?.let { authorId ->
            ApiUser(
                authorId,
                bookmark.authorName ?: StoreStream.getUsers().users[authorId]?.username ?: "Unknown User",
                null,
                null,
                "0000",
                null,
                null,
                false,
                false,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                PremiumTier.NONE,
                null,
                null,
                null,
                null,
                0,
            )
        }

    private fun bookmarkSubtitle(total: Int, loaded: Int): String =
        when {
            total == 0 -> "No bookmarks"
            loaded == total -> "$loaded saved"
            loaded == 0 -> "No loaded bookmarks"
            else -> "$loaded/$total loaded"
        }

    private fun mentionsAdapter(fragment: WidgetUserMentions): WidgetChatListAdapter? =
        runCatching {
            WidgetUserMentions::class.java.getDeclaredField("mentionsAdapter").apply { isAccessible = true }.get(fragment) as WidgetChatListAdapter
        }.getOrNull()

    private fun actionItem(context: Context, id: Int, label: String, icon: android.graphics.drawable.Drawable?) =
        TextView(context, null, 0, R.i.UiKit_Settings_Item_Icon).apply {
            this.id = id
            text = label
            setCompoundDrawablesRelativeWithIntrinsicBounds(icon, null, null, null)
        }

    private fun showReminderPicker(context: Context, selectedMessage: Message?, savedBookmark: BookmarkRecord?) {
        val now = System.currentTimeMillis()
        val options = arrayOf("30 minutes", "1 hour", "4 hours", "Tomorrow morning", "Custom")
        val times = arrayOf(
            now + 30 * 60 * 1000L,
            now + 60 * 60 * 1000L,
            now + 4 * 60 * 60 * 1000L,
            Calendar.getInstance().apply {
                add(Calendar.DATE, 1)
                set(Calendar.HOUR_OF_DAY, 9)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }.timeInMillis,
        )
        android.app.AlertDialog.Builder(context)
            .setTitle("Remind Me")
            .setItems(options) { _, which ->
                if (which == options.lastIndex) {
                    pickCustomReminder(context) { saveReminder(selectedMessage, savedBookmark, it) }
                } else {
                    saveReminder(selectedMessage, savedBookmark, times[which])
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun pickCustomReminder(context: Context, onPicked: (Long) -> Unit) {
        val cal = Calendar.getInstance()
        DatePickerDialog(context, { _, year, month, day ->
            cal.set(Calendar.YEAR, year)
            cal.set(Calendar.MONTH, month)
            cal.set(Calendar.DAY_OF_MONTH, day)
            TimePickerDialog(context, { _, hour, minute ->
                cal.set(Calendar.HOUR_OF_DAY, hour)
                cal.set(Calendar.MINUTE, minute)
                cal.set(Calendar.SECOND, 0)
                cal.set(Calendar.MILLISECOND, 0)
                onPicked(cal.timeInMillis)
            }, cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE), false).show()
        }, cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH)).show()
    }

    private fun saveReminder(selectedMessage: Message?, savedBookmark: BookmarkRecord?, dueAt: Long) {
        val reminder = if (selectedMessage != null) {
            stash.upsert(selectedMessage, dueAt).also { seenMessages[it.key] = selectedMessage }
        } else {
            savedBookmark?.copy(dueAt = dueAt)?.also(stash::upsert) ?: return
        }
        cloudStash.create(reminder)
        Utils.showToast("Reminder set for ${Date(dueAt)}")
    }

    private fun remove(bookmark: BookmarkRecord) {
        stash.remove(bookmark.channelId, bookmark.messageId)
        cloudStash.delete(bookmark.channelId, bookmark.messageId)
        Utils.showToast(if (bookmark.dueAt == null) "Removed bookmark" else "Completed reminder")
    }

    private fun scheduleReminders() {
        reminderTicks.values.forEach { Utils.mainThread.removeCallbacks(it) }
        reminderTicks.clear()
        if (!settings.getBool("showReminderNotifications", true)) return

        val now = System.currentTimeMillis()
        stash.all()
            .filter { it.dueAt != null && it.dueAt > now }
            .forEach { bookmark ->
                val runnable = Runnable {
                    if (!running || stash.get(bookmark.channelId, bookmark.messageId)?.dueAt != bookmark.dueAt) return@Runnable
                    ring(bookmark)
                }
                reminderTicks[bookmark.key] = runnable
                Utils.mainThread.postDelayed(runnable, bookmark.dueAt!! - now)
            }
    }

    private fun ring(bookmark: BookmarkRecord) {
        if (appForeground) {
            NotificationsAPI.display(
                NotificationData()
                    .setTitle("Reminder")
                    .setSubtitle(bookmark.authorName ?: "Saved message")
                    .setBody(bookmark.content ?: "Tap to jump to the saved message.")
                    .setAutoDismissPeriodSecs(10)
                    .setOnClick {
                        openBookmarkMessage(bookmark.channelId, bookmark.messageId)
                    },
                bookmark.channelId,
            )
        } else {
            showAndroidReminderNotification(bookmark)
        }
    }

    @Suppress("LaunchActivityFromNotification")
    private fun showAndroidReminderNotification(bookmark: BookmarkRecord) {
        val title = bookmark.authorName ?: "Saved message"
        val notificationText = bookmark.content ?: "Tap to jump to the saved message."
        val launchIntent = appContext.packageManager.getLaunchIntentForPackage(appContext.packageName)
            ?: Intent()
        launchIntent
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra(EXTRA_REMINDER_TAP, true)
            .putExtra(EXTRA_REMINDER_CHANNEL_ID, bookmark.channelId)
            .putExtra(EXTRA_REMINDER_MESSAGE_ID, bookmark.messageId)

        val pendingIntent = PendingIntent.getActivity(
            appContext,
            bookmark.key.hashCode(),
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(appContext, NotificationClient.NOTIF_GENERAL)
            .setSmallIcon(R.e.ic_clock_24dp)
            .setContentTitle("Reminder")
            .setContentText(notificationText)
            .setSubText(bookmark.channelName ?: title)
            .setStyle(NotificationCompat.BigTextStyle().bigText(notificationText))
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setLocalOnly(true)
            .setOnlyAlertOnce(true)
            .build()

        NotificationManagerCompat.from(appContext).notify(bookmark.key.hashCode(), notification)
    }

    private fun handleReminderTap(intent: Intent?) {
        if (intent?.getBooleanExtra(EXTRA_REMINDER_TAP, false) != true) return
        val channelId = intent.getLongExtra(EXTRA_REMINDER_CHANNEL_ID, 0L)
        val messageId = intent.getLongExtra(EXTRA_REMINDER_MESSAGE_ID, 0L)
        intent.removeExtra(EXTRA_REMINDER_TAP)
        intent.removeExtra(EXTRA_REMINDER_CHANNEL_ID)
        intent.removeExtra(EXTRA_REMINDER_MESSAGE_ID)
        if (channelId == 0L || messageId == 0L) return

        Utils.mainThread.postDelayed({
            if (running) openBookmarkMessage(channelId, messageId)
        }, 750)
    }

    private fun gateway() {
        GatewayAPI.onRawEvent(listOf("SAVED_MESSAGE_CREATE", "SAVED_MESSAGE_DELETE")) { raw ->
            if (!running || !cloudStash.enabled) return@onRawEvent
            try {
                val gatewayEvent = JSONObject(raw)
                val eventData = gatewayEvent.optJSONObject("d") ?: return@onRawEvent
                when (gatewayEvent.optString("t")) {
                    "SAVED_MESSAGE_CREATE" -> cloudStash.applyGatewayCreate(eventData)
                    "SAVED_MESSAGE_DELETE" -> cloudStash.applyGatewayDelete(eventData)
                }
            } catch (error: Throwable) {
                logger.warn("Saved-message gateway payload did not parse", error)
            }
        }
    }
}
