package com.github.pilotbellytspec.messagerequests

import android.os.Bundle
import android.view.View
import com.aliucord.PluginManager
import com.aliucord.Utils
import com.aliucord.api.SettingsAPI
import com.aliucord.widgets.BottomSheet
import com.discord.views.CheckedSetting

class PluginSettings(private val settings: SettingsAPI) : BottomSheet() {
    override fun onViewCreated(view: View, bundle: Bundle?) {
        super.onViewCreated(view, bundle)
        row("DM list page", "Show message requests as a separate Direct Messages page.", "dmList", true)
        row("Sync request list", "Load Discord's current message request list on startup.", "syncList", true)
        row("DM request banner", "Show Accept and Deny at the start of request DMs.", "banner", true)
        row("Profile actions", "Show request actions in the profile menu when possible.", "profileMenu", true)
    }

    private fun row(title: String, sub: String, key: String, def: Boolean) {
        val ctx = requireContext()
        addView(Utils.createCheckedSetting(ctx, CheckedSetting.ViewType.SWITCH, title, sub).apply {
            isChecked = settings.getBool(key, def)
            setOnCheckedListener {
                settings.setBool(key, it)
                PluginManager.stopPlugin("MessageRequests")
                PluginManager.startPlugin("MessageRequests")
            }
        })
    }
}
