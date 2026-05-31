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
import com.discord.models.message.Message
import com.discord.stores.StoreStream
import com.discord.utilities.fcm.NotificationClient
import com.discord.utilities.color.ColorCompat
import com.discord.widgets.chat.list.adapter.WidgetChatListAdapter
import com.discord.widgets.chat.list.actions.WidgetChatListActions
import com.discord.widgets.tabs.NavigationTab
import com.discord.widgets.user.WidgetUserMentions
import com.lytefast.flexinput.R
import org.json.JSONObject
import rx.functions.Action1
import rx.functions.Action2
import java.util.Calendar
import java.util.Date
import java.util.WeakHashMap

@AliucordPlugin(requiresRestart = false)
@Suppress("unused")
class MessageBookmarks : Plugin() {
    private companion object {
        const val EXTRA_REMINDER_TAP = "messagebookmarks.reminder_tap"
        const val EXTRA_REMINDER_CHANNEL_ID = "messagebookmarks.channel_id"
        const val EXTRA_REMINDER_MESSAGE_ID = "messagebookmarks.message_id"
    }

    private lateinit var store: BookmarkStore
    private lateinit var sync: BookmarkSync
    private lateinit var appContext: Context
    private var active = false
    private var appForeground = true
    private var lifecycleCallbacks: Application.ActivityLifecycleCallbacks? = null
    private var application: Application? = null
    private val actionBookmarkId = View.generateViewId()
    private val actionReminderId = View.generateViewId()
    private val actionCompleteId = View.generateViewId()
    private val menuBookmarksId = View.generateViewId()
    private val reminderRunnables = mutableMapOf<String, Runnable>()
    private val bookmarkMode = WeakHashMap<WidgetUserMentions, Boolean>()
    private val renderVersions = WeakHashMap<WidgetUserMentions, Int>()
    private val lastMentionsModel = WeakHashMap<WidgetUserMentions, WidgetUserMentions.Model>()
    private val messageCache = mutableMapOf<String, Message>()

    private val getActionsBinding = WidgetChatListActions::class.java
        .getDeclaredMethod("getBinding")
        .apply { isAccessible = true }

    init {
        settingsTab = SettingsTab(PluginSettings::class.java, SettingsTab.Type.BOTTOM_SHEET).withArgs(settings)
    }

    override fun start(context: Context) {
        active = true
        appContext = context.applicationContext
        store = BookmarkStore(settings)
        sync = BookmarkSync(settings, store) { Utils.showToast(it) }
        store.listen { scheduleReminders() }

        registerAppLifecycle(context)
        patchMessageActions(context)
        patchRecentMentions(context)
        registerGatewayEvents()
        sync.fetch()
        scheduleReminders()
    }

    override fun stop(context: Context) {
        active = false
        patcher.unpatchAll()
        reminderRunnables.values.forEach { Utils.mainThread.removeCallbacks(it) }
        reminderRunnables.clear()
        lifecycleCallbacks?.let { callbacks ->
            application?.unregisterActivityLifecycleCallbacks(callbacks)
        }
        lifecycleCallbacks = null
        application = null
    }

    private fun registerAppLifecycle(context: Context) {
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

    private fun patchMessageActions(context: Context) {
        val actionsContainerId = Utils.getResId("dialog_chat_actions_container", "id")
        val starIcon = ContextCompat.getDrawable(context, R.e.ic_star_24dp)?.mutate()
        val clockIcon = ContextCompat.getDrawable(context, R.e.ic_clock_24dp)?.mutate()
        val doneIcon = ContextCompat.getDrawable(context, R.e.ic_done_green_24dp)?.mutate()

        patcher.after<WidgetChatListActions>(
            "onViewCreated",
            View::class.java,
            Bundle::class.java,
        ) {
            val root = (it.args[0] as NestedScrollView).getChildAt(0) as LinearLayout
            val ctx = root.context
            val tint = ColorCompat.getThemedColor(ctx, R.b.colorInteractiveNormal)
            listOf(starIcon, clockIcon, doneIcon).forEach { icon -> icon?.setTint(tint) }

            root.addView(actionItem(ctx, actionBookmarkId, "Bookmark Message", starIcon), 1)
            root.addView(actionItem(ctx, actionReminderId, "Create Reminder", clockIcon), 2)
            root.addView(actionItem(ctx, actionCompleteId, "Complete Reminder", doneIcon), 3)
        }

        patcher.after<WidgetChatListActions>(
            "configureUI",
            WidgetChatListActions.Model::class.java,
        ) {
            val sheet = this
            val model = it.args[0] as WidgetChatListActions.Model
            val message = model.message
            val binding = getActionsBinding.invoke(sheet) as WidgetChatListActionsBinding
            val root = binding.root.findViewById<LinearLayout>(actionsContainerId)
            val record = store.get(message.channelId, message.id)

            root.findViewById<TextView>(actionBookmarkId)?.apply {
                text = if (record == null) "Bookmark Message" else "Remove Bookmark"
                setOnClickListener {
                    if (record == null) {
                        val saved = store.upsert(message)
                        messageCache[saved.key] = message
                        sync.create(saved)
                        Utils.showToast("Bookmarked message")
                    } else {
                        remove(record)
                    }
                    sheet.dismiss()
                }
            }
            root.findViewById<TextView>(actionReminderId)?.apply {
                text = if (record?.dueAt == null) "Create Reminder" else "Edit Reminder"
                setOnClickListener {
                    showReminderPicker(sheet.requireContext(), message, record)
                    sheet.dismiss()
                }
            }
            root.findViewById<TextView>(actionCompleteId)?.apply {
                visibility = if (record?.dueAt == null) View.GONE else View.VISIBLE
                setOnClickListener {
                    remove(record!!)
                    sheet.dismiss()
                }
            }
        }
    }

    private fun patchRecentMentions(context: Context) {
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

            val item = toolbarView.menu.add(0, menuBookmarksId, 0, "Bookmarks")
            item.setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
            ContextCompat.getDrawable(context, R.e.ic_star_24dp)?.mutate()?.let { icon ->
                icon.setTint(ColorCompat.getThemedColor(toolbarView.context, R.b.colorInteractiveNormal))
                item.icon = icon
            }
            item.setOnMenuItemClickListener {
                val isBookmarks = bookmarkMode[this] == true
                bookmarkMode[this] = !isBookmarks
                nextRenderVersion(this)
                if (isBookmarks) {
                    restoreRecentMentions(this)
                } else {
                    showBookmarks(this)
                }
                true
            }
        }

        patcher.after<WidgetUserMentions>("configureUI", WidgetUserMentions.Model::class.java) {
            val model = it.args[0] as WidgetUserMentions.Model
            lastMentionsModel[this] = model
            if (bookmarkMode[this] == true) {
                showBookmarks(this)
            }
        }
    }

    private fun nextRenderVersion(fragment: WidgetUserMentions): Int {
        val version = (renderVersions[fragment] ?: 0) + 1
        renderVersions[fragment] = version
        return version
    }

    private fun restoreRecentMentions(fragment: WidgetUserMentions) {
        val model = lastMentionsModel[fragment]
        if (model != null) {
            runCatching {
                WidgetUserMentions::class.java
                    .getDeclaredMethod("configureUI", WidgetUserMentions.Model::class.java)
                    .apply { isAccessible = true }
                    .invoke(fragment, model)
            }.onFailure {
                mentionsAdapter(fragment)?.setData(model)
                fragment.setActionBarSubtitle(model.guildName ?: "All Servers")
            }
            fragment.setActionBarTitle("Recent Mentions")
        } else {
            fragment.setActionBarTitle("Recent Mentions")
            fragment.setActionBarSubtitle("All Servers")
        }
    }

    private fun showBookmarks(fragment: WidgetUserMentions) {
        val renderVersion = nextRenderVersion(fragment)
        val adapter = mentionsAdapter(fragment) ?: return
        val messages = store.all().mapNotNull { record ->
            messageCache[record.key]
                ?: StoreStream.getMessages().getMessage(record.channelId, record.messageId)?.also { message ->
                    messageCache[record.key] = message
                }
        }

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
                if (bookmarkMode[fragment] != true || renderVersions[fragment] != renderVersion) return@subscribe
                val model = this
                adapter.setData(
                    model.copy(
                        model.userId,
                        model.channelId,
                        model.guild,
                        model.guildId,
                        model.channelNames,
                        model.oldestMessageId,
                        model.list,
                        model.myRoleIds,
                        model.newMessagesMarkerMessageId,
                        model.isSpoilerClickAllowed,
                        model.animateEmojis,
                        "Bookmarks",
                        model.selectedTab,
                    ),
                )
                fragment.setActionBarTitle("Bookmarks")
                fragment.setActionBarSubtitle(if (messages.isEmpty()) "No loaded bookmarks" else "${messages.size} saved")
            }
    }

    private fun mentionsAdapter(fragment: WidgetUserMentions): WidgetChatListAdapter? =
        fragment.readObject("mentionsAdapter") as? WidgetChatListAdapter

    private fun actionItem(ctx: Context, id: Int, label: String, icon: android.graphics.drawable.Drawable?) =
        TextView(ctx, null, 0, R.i.UiKit_Settings_Item_Icon).apply {
            this.id = id
            text = label
            setCompoundDrawablesRelativeWithIntrinsicBounds(icon, null, null, null)
        }

    private fun showReminderPicker(ctx: Context, message: Message?, existing: BookmarkRecord?) {
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
        android.app.AlertDialog.Builder(ctx)
            .setTitle("Remind Me")
            .setItems(options) { _, which ->
                if (which == options.lastIndex) {
                    pickCustomReminder(ctx) { saveReminder(message, existing, it) }
                } else {
                    saveReminder(message, existing, times[which])
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun pickCustomReminder(ctx: Context, onPicked: (Long) -> Unit) {
        val cal = Calendar.getInstance()
        DatePickerDialog(ctx, { _, year, month, day ->
            cal.set(Calendar.YEAR, year)
            cal.set(Calendar.MONTH, month)
            cal.set(Calendar.DAY_OF_MONTH, day)
            TimePickerDialog(ctx, { _, hour, minute ->
                cal.set(Calendar.HOUR_OF_DAY, hour)
                cal.set(Calendar.MINUTE, minute)
                cal.set(Calendar.SECOND, 0)
                cal.set(Calendar.MILLISECOND, 0)
                onPicked(cal.timeInMillis)
            }, cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE), false).show()
        }, cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH)).show()
    }

    private fun saveReminder(message: Message?, existing: BookmarkRecord?, dueAt: Long) {
        val record = if (message != null) {
            store.upsert(message, dueAt).also { messageCache[it.key] = message }
        } else {
            existing?.copy(dueAt = dueAt)?.also(store::upsert) ?: return
        }
        sync.create(record)
        Utils.showToast("Reminder set for ${Date(dueAt)}")
    }

    private fun remove(record: BookmarkRecord) {
        store.remove(record.channelId, record.messageId)
        sync.delete(record.channelId, record.messageId)
        Utils.showToast(if (record.dueAt == null) "Removed bookmark" else "Completed reminder")
    }

    private fun scheduleReminders() {
        reminderRunnables.values.forEach { Utils.mainThread.removeCallbacks(it) }
        reminderRunnables.clear()
        if (!settings.getBool("showReminderNotifications", true)) return

        val now = System.currentTimeMillis()
        store.all()
            .filter { it.dueAt != null && it.dueAt > now }
            .forEach { record ->
                val runnable = Runnable {
                    if (!active || store.get(record.channelId, record.messageId)?.dueAt != record.dueAt) return@Runnable
                    showReminderNotification(record)
                }
                reminderRunnables[record.key] = runnable
                Utils.mainThread.postDelayed(runnable, record.dueAt!! - now)
            }
    }

    private fun showReminderNotification(record: BookmarkRecord) {
        if (appForeground) {
            NotificationsAPI.display(
                NotificationData()
                    .setTitle("Reminder")
                    .setSubtitle(record.authorName ?: "Saved message")
                    .setBody(record.content ?: "Tap to jump to the saved message.")
                    .setAutoDismissPeriodSecs(10)
                    .setOnClick {
                        StoreStream.getMessagesLoader().jumpToMessage(record.channelId, record.messageId)
                    },
                record.channelId,
            )
        } else {
            showAndroidReminderNotification(record)
        }
    }

    @Suppress("LaunchActivityFromNotification")
    private fun showAndroidReminderNotification(record: BookmarkRecord) {
        val title = record.authorName ?: "Saved message"
        val body = record.content ?: "Tap to jump to the saved message."
        val launchIntent = appContext.packageManager.getLaunchIntentForPackage(appContext.packageName)
            ?: Intent()
        launchIntent
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra(EXTRA_REMINDER_TAP, true)
            .putExtra(EXTRA_REMINDER_CHANNEL_ID, record.channelId)
            .putExtra(EXTRA_REMINDER_MESSAGE_ID, record.messageId)

        val pendingIntent = PendingIntent.getActivity(
            appContext,
            record.key.hashCode(),
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(appContext, NotificationClient.NOTIF_GENERAL)
            .setSmallIcon(R.e.ic_clock_24dp)
            .setContentTitle("Reminder")
            .setContentText(body)
            .setSubText(record.channelName ?: title)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setLocalOnly(true)
            .setOnlyAlertOnce(true)
            .build()

        NotificationManagerCompat.from(appContext).notify(record.key.hashCode(), notification)
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
            if (active) StoreStream.getMessagesLoader().jumpToMessage(channelId, messageId)
        }, 750)
    }

    private fun registerGatewayEvents() {
        GatewayAPI.onRawEvent(listOf("SAVED_MESSAGE_CREATE", "SAVED_MESSAGE_DELETE")) { raw ->
            if (!active || !sync.enabled) return@onRawEvent
            runCatching {
                val root = JSONObject(raw)
                val data = root.optJSONObject("d") ?: return@runCatching
                when (root.optString("t")) {
                    "SAVED_MESSAGE_CREATE" -> sync.applyGatewayCreate(data)
                    "SAVED_MESSAGE_DELETE" -> sync.applyGatewayDelete(data)
                }
            }.onFailure {
                logger.warn("Failed to handle saved-message gateway event", it)
            }
        }
    }
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
