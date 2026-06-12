package com.github.pilotbellytspec.managestickers

import android.content.Context
import android.graphics.drawable.LayerDrawable
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.aliucord.Utils
import com.aliucord.annotations.AliucordPlugin
import com.aliucord.entities.Plugin
import com.aliucord.patcher.after
import com.aliucord.patcher.before
import com.aliucord.utils.DimenUtils
import com.discord.databinding.WidgetServerSettingsBinding
import com.discord.api.sticker.GuildStickersUpdate
import com.discord.stores.StoreClientDataState
import com.discord.stores.StoreGuildStickers
import com.discord.widgets.servers.WidgetServerSettings
import com.lytefast.flexinput.R

@AliucordPlugin(requiresRestart = false)
class ManageStickers : Plugin() {
    private val tag = "manage-stickers-row"
    private val bind = WidgetServerSettings::class.java.getDeclaredMethod("getBinding").apply { isAccessible = true }
    private val hashes = GuildStickersUpdate::class.java.getDeclaredMethod("a").apply { isAccessible = true }
    private val guild = GuildStickersUpdate::class.java.getDeclaredMethod("b").apply { isAccessible = true }

    override fun start(context: Context) {
        patcher.before<StoreClientDataState>("handleStickersUpdate", GuildStickersUpdate::class.java) {
            val update = it.args[0] as? GuildStickersUpdate ?: return@before
            if (hashes.invoke(update) == null) it.result = null
        }

        patcher.after<StoreGuildStickers>("handleStickerUpdate", GuildStickersUpdate::class.java) {
            val update = it.args[0] as? GuildStickersUpdate ?: return@after
            StickerPage.refresh(guild.invoke(update) as Long)
        }

        patcher.after<WidgetServerSettings>("configureUI", WidgetServerSettings.Model::class.java) {
            val page = it.thisObject as WidgetServerSettings
            val model = it.args[0] as? WidgetServerSettings.Model ?: return@after
            val binding = bind.invoke(page) as? WidgetServerSettingsBinding ?: return@after
            val emoji = binding.d
            val parent = emoji.parent as? ViewGroup ?: return@after
            var i = parent.childCount - 1
            while (i >= 0) {
                val child = parent.getChildAt(i)
                if (child.tag == tag || child is TextView && child.text?.toString() == "Stickers") {
                    parent.removeViewAt(i)
                }
                i--
            }
            val row = row(emoji.context)
            val at = parent.indexOfChild(emoji) + 1
            parent.addView(row, at)

            row.visibility = emoji.visibility
            row.setOnClickListener {
                Utils.openPageWithProxy(row.context, StickerPage(model.guild.id))
            }
        }
    }

    override fun stop(context: Context) {
        patcher.unpatchAll()
    }

    private fun row(ctx: Context): TextView {
        return TextView(ctx, null, 0, R.i.UiKit_Settings_Item_Icon).apply {
            tag = this@ManageStickers.tag
            text = "Stickers"
            Utils.getResId("ic_sticker_icon_24dp", "drawable").takeIf { it != 0 }?.let {
                val icon = ContextCompat.getDrawable(ctx, it)?.mutate() ?: return@let
                Utils.tintToTheme(icon)
                val box = LayerDrawable(arrayOf(icon))
                val nudge = DimenUtils.dpToPx(2)
                box.setLayerInset(0, nudge, nudge, -nudge, -nudge)
                setCompoundDrawablesRelativeWithIntrinsicBounds(box, null, null, null)
            }
        }
    }
}
