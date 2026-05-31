package com.github.pilotbellytspec.messagebookmarks

import android.os.Bundle
import android.view.View
import android.widget.RadioGroup
import android.widget.TextView
import com.aliucord.PluginManager
import com.aliucord.Utils
import com.aliucord.api.SettingsAPI
import com.aliucord.widgets.BottomSheet
import com.discord.views.CheckedSetting
import com.discord.views.RadioManager
import com.lytefast.flexinput.R

class PluginSettings(private val settings: SettingsAPI) : BottomSheet() {
    override fun onViewCreated(view: View, bundle: Bundle?) {
        super.onViewCreated(view, bundle)
        val ctx = requireContext()

        addView(TextView(ctx, null, 0, R.i.UiKit_Settings_Item_Header).apply {
            text = "Message Bookmarks"
        })
        addView(TextView(ctx, null, 0, R.i.UiKit_Settings_Item_SubText).apply {
            text = "Save messages for later, optionally sync them with Discord, and get reminder notifications."
        })

        val radios = listOf(
            Utils.createCheckedSetting(ctx, CheckedSetting.ViewType.RADIO, "Local Mode", "Stores bookmarks on this device only."),
            Utils.createCheckedSetting(ctx, CheckedSetting.ViewType.RADIO, "Sync Mode", "Uses Discord's saved-message API if your account has access."),
        )
        val manager = RadioManager(radios)
        val group = RadioGroup(ctx)
        radios.forEachIndexed { index, radio ->
            radio.e {
                settings.setInt("mode", index)
                manager.a(radio)
                restart()
            }
            group.addView(radio)
            if (index == settings.getInt("mode", BookmarkSync.MODE_LOCAL)) manager.a(radio)
        }
        addView(group)

        addToggle("Bookmarks button", "Adds a Bookmarks button to Recent Mentions.", "showBookmarksButton", true)
        addToggle("Reminder notifications", "Uses in-app notices while Aliucord is open and Android notifications while it is not.", "showReminderNotifications", true)
    }

    private fun addToggle(title: String, subtitle: String, key: String, default: Boolean) {
        val ctx = requireContext()
        addView(Utils.createCheckedSetting(ctx, CheckedSetting.ViewType.SWITCH, title, subtitle).apply {
            isChecked = settings.getBool(key, default)
            setOnCheckedListener {
                settings.setBool(key, it)
                restart()
            }
        })
    }

    private fun restart() {
        PluginManager.stopPlugin("MessageBookmarks")
        PluginManager.startPlugin("MessageBookmarks")
    }
}
