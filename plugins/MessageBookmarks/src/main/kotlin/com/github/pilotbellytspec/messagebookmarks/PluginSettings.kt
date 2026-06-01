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
        val sheetContext = requireContext()

        addView(TextView(sheetContext, null, 0, R.i.UiKit_Settings_Item_Header).apply {
            text = "Message Bookmarks"
        })
        addView(TextView(sheetContext, null, 0, R.i.UiKit_Settings_Item_SubText).apply {
            text = "Bookmarks and reminders for messages."
        })

        val modeRows = listOf(
            Utils.createCheckedSetting(sheetContext, CheckedSetting.ViewType.RADIO, "Local", "Stored on this device."),
            Utils.createCheckedSetting(sheetContext, CheckedSetting.ViewType.RADIO, "Sync", "Use Discord saved messages when available."),
        )
        val radioManager = RadioManager(modeRows)
        val modeBox = RadioGroup(sheetContext)
        modeRows.forEachIndexed { index, radio ->
            radio.e {
                settings.setInt("mode", index)
                radioManager.a(radio)
                bounce()
            }
            modeBox.addView(radio)
            if (index == settings.getInt("mode", BookmarkSync.MODE_LOCAL)) radioManager.a(radio)
        }
        addView(modeBox)

        switchRow("Recent Mentions button", "Put Bookmarks beside the filter menu.", "showBookmarksButton", true)
        switchRow("Notifications", "In-app while Aliucord is open, Android notification in background.", "showReminderNotifications", true)
    }

    private fun switchRow(title: String, subtitle: String, key: String, default: Boolean) {
        val sheetContext = requireContext()
        addView(Utils.createCheckedSetting(sheetContext, CheckedSetting.ViewType.SWITCH, title, subtitle).apply {
            isChecked = settings.getBool(key, default)
            setOnCheckedListener {
                settings.setBool(key, it)
                bounce()
            }
        })
    }

    private fun bounce() {
        PluginManager.stopPlugin("MessageBookmarks")
        PluginManager.startPlugin("MessageBookmarks")
    }
}
