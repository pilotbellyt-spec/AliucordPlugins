package com.github.pilotbellytspec.serversettingsfix

import android.os.Bundle
import android.view.View
import android.widget.TextView
import com.aliucord.PluginManager
import com.aliucord.Utils
import com.aliucord.api.SettingsAPI
import com.aliucord.widgets.BottomSheet
import com.discord.views.CheckedSetting
import com.lytefast.flexinput.R

class PluginSettings(private val settings: SettingsAPI) : BottomSheet() {
    override fun onViewCreated(view: View, bundle: Bundle?) {
        super.onViewCreated(view, bundle)
        val sheetContext = requireContext()

        addView(TextView(sheetContext, null, 0, R.i.UiKit_Settings_Item_Header).apply {
            text = "ServerSettingsFix"
        })
        addView(TextView(sheetContext, null, 0, R.i.UiKit_Settings_Item_SubText).apply {
            text = "Keeps bans, audit logs, and invites usable on old Discord builds."
        })

        switchRow("Paginated bans", "Use the current bans endpoint.", "fixBans", true)
        switchRow("Audit log route", "Use the current audit-log endpoint.", "fixAuditLog", true)
        switchRow("30-minute invites", "Set generated invite links to expire in 30 minutes.", "shortInvites", true)
        switchRow("Failure toasts", "Show a short message when a server settings request fails.", "showToasts", true)
    }

    private fun switchRow(title: String, subtitle: String, key: String, default: Boolean) {
        val sheetContext = requireContext()
        addView(Utils.createCheckedSetting(sheetContext, CheckedSetting.ViewType.SWITCH, title, subtitle).apply {
            isChecked = settings.getBool(key, default)
            setOnCheckedListener {
                settings.setBool(key, it)
                PluginManager.stopPlugin("ServerSettingsFix")
                PluginManager.startPlugin("ServerSettingsFix")
            }
        })
    }
}
