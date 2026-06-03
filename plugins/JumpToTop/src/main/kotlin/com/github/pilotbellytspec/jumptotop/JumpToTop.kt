package com.github.pilotbellytspec.jumptotop

import android.content.Context
import android.view.MenuItem
import android.view.View
import androidx.appcompat.widget.Toolbar
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.aliucord.Utils
import com.aliucord.annotations.AliucordPlugin
import com.aliucord.entities.Plugin
import com.aliucord.patcher.after
import com.discord.api.channel.Channel
import com.discord.app.AppFragment
import com.discord.stores.StoreStream
import com.discord.utilities.color.ColorCompat
import com.discord.widgets.chat.list.WidgetChatList
import com.discord.widgets.home.WidgetHome
import com.lytefast.flexinput.R
import rx.functions.Action1
import rx.functions.Action2
import java.lang.ref.WeakReference

@AliucordPlugin(requiresRestart = false)
class JumpToTop : Plugin() {
    private val menuId = View.generateViewId()
    private var chat = WeakReference<RecyclerView>(null)

    override fun start(context: Context) {
        val chatListId = Utils.getResId("chat_list_recycler", "id")
        val chatMenuId = Utils.getResId("menu_chat_toolbar", "menu")
        val searchId = Utils.getResId("menu_chat_search", "id")

        patcher.after<WidgetChatList>("onViewBound", View::class.java) {
            val root = it.args[0] as? View ?: return@after
            chat = WeakReference(root.findViewById(chatListId))
        }

        patcher.after<AppFragment>(
            "setActionBarOptionsMenu",
            Int::class.javaPrimitiveType!!,
            Action2::class.java,
            Action1::class.java,
        ) {
            if (this !is WidgetHome) return@after
            if (it.args[0] != chatMenuId) return@after

            val bar = it.result as? Toolbar ?: return@after
            val search = bar.menu.findItem(searchId) ?: return@after
            if (!search.isVisible || starter() == null) return@after
            if (bar.menu.findItem(menuId) != null) return@after

            val item = bar.menu.add(0, menuId, search.order, "Jump to top")
            item.setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
            ContextCompat.getDrawable(bar.context, R.e.ic_arrow_up_24dp)?.mutate()?.let { icon ->
                icon.setTint(ColorCompat.getThemedColor(bar.context, R.b.colorInteractiveNormal))
                item.icon = icon
            }
            item.setOnMenuItemClickListener {
                jump()
                true
            }
        }
    }

    override fun stop(context: Context) {
        patcher.unpatchAll()
        chat.clear()
    }

    private fun jump() {
        starter()?.let { (channelId, messageId) ->
            StoreStream.getMessagesLoader().jumpToMessage(channelId, messageId)
            return
        }

        val list = chat.get() ?: return
        val count = list.adapter?.itemCount ?: return
        if (count < 1) return
        list.stopScroll()
        list.post { list.scrollToPosition(count - 1) }
    }

    private fun starter(): Pair<Long, Long>? {
        val ch = runCatching { StoreStream.getChannelsSelected().selectedChannel }.getOrNull() ?: return null
        val type = ch.D()
        if (type != Channel.PUBLIC_THREAD && type != Channel.PRIVATE_THREAD && type != Channel.ANNOUNCEMENT_THREAD) return null
        val parentId = ch.u()
        val parent = runCatching { StoreStream.getChannels().getChannel(parentId) }.getOrNull()
        if (parent?.D() != Channel.GUILD_FORUM) return null
        val id = ch.k()
        return id to id
    }
}
