package com.github.pilotbellytspec.messagerequests

import android.content.Context
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.widget.AppCompatImageView
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.content.ContextCompat
import b.a.a.d.a as UserActionsDialog
import b.a.i.u1 as UserActionsDialogBinding
import com.aliucord.Utils
import com.aliucord.annotations.AliucordPlugin
import com.aliucord.api.GatewayAPI
import com.aliucord.entities.Plugin
import com.aliucord.patcher.after
import com.aliucord.patcher.before
import com.discord.api.channel.Channel
import com.discord.databinding.WidgetChatListAdapterItemPrivateChannelStartBinding
import com.discord.databinding.WidgetChannelsListBinding
import com.discord.stores.StoreStream
import com.discord.utilities.color.ColorCompat
import com.discord.utilities.color.ColorCompatKt
import com.discord.utilities.drawable.DrawableCompat
import com.discord.utilities.mg_recycler.MGRecyclerAdapterSimple
import com.discord.widgets.channels.list.WidgetChannelListModel
import com.discord.widgets.channels.list.WidgetChannelsList
import com.discord.widgets.channels.list.WidgetChannelsListAdapter
import com.discord.widgets.channels.list.items.ChannelListItem
import com.discord.widgets.channels.list.items.ChannelListItemPrivate
import com.discord.widgets.chat.list.adapter.WidgetChatListAdapterItemPrivateChannelStart
import com.discord.widgets.chat.list.entries.ChatListEntry
import com.discord.widgets.chat.list.entries.StartOfPrivateChatEntry
import com.lytefast.flexinput.R
import org.json.JSONObject
import java.lang.ref.WeakReference
import java.util.WeakHashMap

@AliucordPlugin(requiresRestart = false)
@Suppress("unused")
class MessageRequests : Plugin() {
    private lateinit var reqs: RequestStore
    private lateinit var api: RequestApi
    private var live = false
    private var inbox = false
    private var syncAt = 0L
    private var tab = WeakReference<WidgetChannelsList?>(null)
    private val seen = WeakHashMap<WidgetChannelsList, WidgetChannelListModel>()

    init {
        settingsTab = SettingsTab(PluginSettings::class.java, SettingsTab.Type.BOTTOM_SHEET).withArgs(settings)
    }

    override fun start(context: Context) {
        live = true
        reqs = RequestStore(settings)
        api = RequestApi(reqs) { Utils.showToast(it) }
        gateway()
        dmList()
        dmStart()
        userMenu()
        refresh(true)
    }

    override fun stop(context: Context) {
        live = false
        patcher.unpatchAll()
    }

    private fun gateway() {
        GatewayAPI.onRawEvent(listOf("READY", "CONNECTION_OPEN", "CHANNEL_CREATE", "CHANNEL_UPDATE", "CHANNEL_DELETE")) { raw ->
            if (!live) return@onRawEvent
            try {
                val old = reqs.all()
                val ev = JSONObject(raw)
                val type = ev.optString("t")
                if (type == "READY" || type == "CONNECTION_OPEN") {
                    refresh(true)
                }
                val data = ev.optJSONObject("d") ?: return@onRawEvent
                if (type == "CHANNEL_DELETE") {
                    data.optString("id").toLongOrNull()?.let(reqs::drop)
                } else {
                    reqs.ingest(data)
                }
                if (old != reqs.all()) {
                    touch()
                    redraw()
                }
                if (type == "CHANNEL_CREATE" || type == "CHANNEL_UPDATE" || type == "CHANNEL_DELETE") {
                    refresh(false)
                }
            } catch (err: Throwable) {
                logger.warn("message request gateway payload failed", err)
            }
        }
    }

    private fun dmList() {
        if (!settings.getBool("dmList", true)) return
        patcher.after<WidgetChannelsList>("onViewBound", View::class.java) {
            val page = it.thisObject as WidgetChannelsList
            page.binding()?.root?.let { root -> addReqButton(page, root) }
        }
        patcher.after<WidgetChannelsList>("configureUI", WidgetChannelListModel::class.java) {
            val page = it.thisObject as WidgetChannelsList
            val model = it.args[0] as WidgetChannelListModel
            if (model.isGuildSelected) {
                inbox = false
                return@after
            }
            tab = WeakReference(page)
            seen[page] = model
            render(page, model)
        }
        patcher.before<MGRecyclerAdapterSimple<*>>("setData", List::class.java) {
            val data = it.args[0] as? List<*> ?: return@before
            if (data.none { row -> row is ChannelListItemPrivate }) return@before
            it.args[0] = data.keepReqs()
        }
    }

    private fun addReqButton(page: WidgetChannelsList, root: View) {
        val chat = root.findViewById<AppCompatImageView>(DM_NEW) ?: return
        val wrap = chat.parent as? ConstraintLayout ?: return
        if (wrap.findViewWithTag<View>(REQ_TAB) != null) return
        val ctx = chat.context
        val px = (40 * ctx.resources.displayMetrics.density).toInt()
        val btn = AppCompatImageView(ctx).apply {
            id = View.generateViewId()
            tag = REQ_TAB
            scaleType = ImageView.ScaleType.CENTER
            setPadding(chat.paddingLeft, chat.paddingTop, chat.paddingRight, chat.paddingBottom)
            background = chat.background?.constantState?.newDrawable()?.mutate()
            setImageDrawable(ContextCompat.getDrawable(ctx, R.e.ic_mail_24dp)?.mutate()?.also {
                ColorCompatKt.setTint(it, ColorCompat.getThemedColor(ctx, R.b.colorInteractiveNormal), false)
            })
            contentDescription = "Message Requests"
            setOnClickListener {
                inbox = !inbox
                seen[page]?.let { model -> render(page, model) }
            }
        }
        wrap.addView(btn, ConstraintLayout.LayoutParams(px, px).apply {
            topToTop = chat.id
            bottomToBottom = chat.id
            endToStart = chat.id
            marginEnd = 2
        })
        (root.findViewById<TextView>(DM_TITLE)?.layoutParams as? ConstraintLayout.LayoutParams)?.let {
            it.endToStart = btn.id
        }
    }

    private fun render(page: WidgetChannelsList, model: WidgetChannelListModel) {
        val root = page.binding()?.root ?: return
        val items = model.items.keepReqs().filterIsInstance<ChannelListItem>()
        page.adapter()?.setData(items)
        root.findViewById<TextView>(DM_TITLE)?.text =
            if (inbox) "Message Requests" else "Direct Messages"
        root.findViewWithTag<AppCompatImageView>(REQ_TAB)?.alpha = if (inbox) 1f else 0.72f
    }

    private fun List<*>.keepReqs(): List<*> {
        if (inbox) return filter {
            it is ChannelListItemPrivate && reqs.has(it.channel.readLong("id", "getId", "k") ?: 0L)
        }
        return filter {
            it !is ChannelListItemPrivate || !reqs.has(it.channel.readLong("id", "getId", "k") ?: 0L)
        }
    }

    private fun redraw() {
        Utils.mainThread.post {
            val page = tab.get() ?: return@post
            seen[page]?.let { render(page, it) }
        }
    }

    private fun refresh(now: Boolean) {
        if (!settings.getBool("syncList", true)) return
        val time = System.currentTimeMillis()
        if (!now && time - syncAt < 15000L) return
        syncAt = time
        val old = reqs.all()
        api.sync {
            if (old != reqs.all()) touch()
            redraw()
        }
    }

    private fun touch() {
        StoreStream.`access$getDispatcher$p`(StoreStream.getPresences().stream).schedule {
            StoreStream.getMessagesMostRecent().markChanged()
        }
    }

    private fun dmStart() {
        if (!settings.getBool("banner", true)) return
        patcher.after<WidgetChatListAdapterItemPrivateChannelStart>(
            "onConfigure",
            Int::class.javaPrimitiveType!!,
            ChatListEntry::class.java,
        ) {
            val data = it.args[1] as? StartOfPrivateChatEntry ?: return@after
            val binding = this.binding() ?: return@after
            val card = binding.a.findViewWithTag<LinearLayout>(REQ_BANNER) ?: makeBanner(binding.a.context)
            if (card.parent == null) binding.a.addView(card, 2)
            fillBanner(card, data.channelId)
        }
    }

    private fun userMenu() {
        if (!settings.getBool("profileMenu", true)) return
        patcher.after<UserActionsDialog>("onViewBound", View::class.java) {
            val dialog = it.thisObject as UserActionsDialog
            val binding = dialog.binding()
            val channelId = currentDmId()
            val root = binding.a
            val accept = root.findViewWithTag<TextView>(ACCEPT_ROW) ?: menuRow(root, ACCEPT_ROW, "Accept Message Request")
            val deny = root.findViewWithTag<TextView>(DENY_ROW) ?: menuRow(root, DENY_ROW, "Deny Message Request")
            if (accept.parent == null) root.addView(accept, 0)
            if (deny.parent == null) root.addView(deny, 1)
            val show = channelId != 0L && reqs.has(channelId)
            accept.visibility = if (show) View.VISIBLE else View.GONE
            deny.visibility = if (show) View.VISIBLE else View.GONE
            accept.setOnClickListener {
                accept.isEnabled = false
                api.accept(channelId) {
                    redraw()
                    dialog.dismiss()
                }
            }
            deny.setOnClickListener {
                deny.isEnabled = false
                api.deny(channelId) {
                    redraw()
                    dialog.dismiss()
                }
            }
        }
    }

    private fun fillBanner(root: LinearLayout, channelId: Long) {
        val show = reqs.has(channelId)
        root.visibility = if (show) View.VISIBLE else View.GONE
        if (!show) return
        root.findViewWithTag<TextView>(REQ_TEXT)?.text = "Accept to start chatting. They will only be notified if you message them back."
        root.findViewWithTag<TextView>(REQ_ACCEPT)?.setOnClickListener { btn ->
            btn.isEnabled = false
            api.accept(channelId) {
                btn.isEnabled = true
                root.visibility = View.GONE
                redraw()
            }
        }
        root.findViewWithTag<TextView>(REQ_DENY)?.setOnClickListener { btn ->
            btn.isEnabled = false
            api.deny(channelId) {
                btn.isEnabled = true
                root.visibility = View.GONE
                redraw()
            }
        }
    }

    private fun makeBanner(ctx: Context): LinearLayout {
        val pad = (12 * ctx.resources.displayMetrics.density).toInt()
        val box = LinearLayout(ctx).apply {
            tag = REQ_BANNER
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        box.addView(TextView(ctx, null, 0, R.i.UiKit_TextView).apply {
            tag = REQ_TEXT
            setTextColor(ColorCompat.getThemedColor(this, R.b.colorTextNormal))
        })
        box.addView(LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(button(ctx, REQ_ACCEPT, "Accept"))
            addView(button(ctx, REQ_DENY, "Deny"))
        })
        return box
    }

    private fun button(ctx: Context, tagValue: String, label: String): TextView =
        TextView(ctx, null, 0, R.i.UiKit_Settings_Item).apply {
            tag = tagValue
            text = label
            setTextColor(ColorCompat.getThemedColor(this, R.b.colorHeaderPrimary))
        }

    private fun menuRow(root: LinearLayout, tagValue: String, label: String): TextView {
        return TextView(root.context, null, 0, R.i.UiKit_ListItem_Icon).apply {
            tag = tagValue
            text = label
            setCompoundDrawablesWithIntrinsicBounds(DrawableCompat.getThemedDrawableRes(this, R.b.ic_user_profile_action_message), 0, 0, 0)
        }
    }

    private fun currentDmId(): Long {
        val channel = runCatching { StoreStream.getChannelsSelected().selectedChannel }.getOrNull() ?: return 0L
        val type = channel.readInt("type", "getType", "D") ?: return 0L
        return if (type == Channel.DM || type == Channel.GROUP_DM) channel.readLong("id", "getId", "k") ?: 0L else 0L
    }

    private fun WidgetChatListAdapterItemPrivateChannelStart.binding(): WidgetChatListAdapterItemPrivateChannelStartBinding? =
        grab("binding") as? WidgetChatListAdapterItemPrivateChannelStartBinding

    private fun UserActionsDialog.binding(): UserActionsDialogBinding =
        javaClass.getDeclaredMethod("g").apply { isAccessible = true }.invoke(this) as UserActionsDialogBinding

    private fun WidgetChannelsList.binding(): WidgetChannelsListBinding? =
        runCatching { javaClass.getDeclaredMethod("getBinding").apply { isAccessible = true }.invoke(this) as WidgetChannelsListBinding }.getOrNull()

    private fun WidgetChannelsList.adapter(): WidgetChannelsListAdapter? =
        grab("adapter") as? WidgetChannelsListAdapter

    private fun Any?.readLong(vararg names: String): Long? =
        when (val v = grab(*names)) {
            is Long -> v
            is Number -> v.toLong()
            else -> null
        }

    private fun Any?.readInt(vararg names: String): Int? =
        when (val v = grab(*names)) {
            is Int -> v
            is Number -> v.toInt()
            else -> null
        }

    private fun Any?.grab(vararg names: String): Any? {
        val item = this ?: return null
        names.forEach { name ->
            var cls: Class<*>? = item.javaClass
            while (cls != null) {
                val c = cls
                try {
                    val field = c.getDeclaredField(name).apply { isAccessible = true }
                    return field[item]
                } catch (_: Throwable) {
                }
                try {
                    val method = c.getDeclaredMethod(name).apply { isAccessible = true }
                    return method.invoke(item)
                } catch (_: Throwable) {
                }
                cls = c.superclass
            }
        }
        return null
    }

    companion object {
        private const val REQ_BANNER = "mr:banner"
        private const val REQ_TEXT = "mr:text"
        private const val REQ_ACCEPT = "mr:accept"
        private const val REQ_DENY = "mr:deny"
        private const val ACCEPT_ROW = "mr:accept-row"
        private const val DENY_ROW = "mr:deny-row"
        private const val REQ_TAB = "mr:tab"
        private const val DM_TITLE = 0x7f0a02c9
        private const val DM_NEW = 0x7f0a02ef
    }
}
