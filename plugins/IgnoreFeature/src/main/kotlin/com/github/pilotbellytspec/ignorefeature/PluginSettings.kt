package com.github.pilotbellytspec.ignorefeature

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
        val ctx = requireContext()

        addView(TextView(ctx, null, 0, R.i.UiKit_Settings_Item_Header).apply {
            text = "Ignore Feature"
        })
        addView(TextView(ctx, null, 0, R.i.UiKit_Settings_Item_SubText).apply {
            text = "Adds Discord's Ignore action to user profiles and hides ignored users in chat."
        })

        addToggle("Collapse ignored messages", "Shows ignored users' messages as a collapsed message group.", "collapseIgnoredMessages", true)
        addToggle("Sync with Discord", "Uses Discord's ignore API so ignored users sync between clients.", "syncIgnoreState", true)
    }

    private fun addToggle(title: String, subtitle: String, key: String, default: Boolean) {
        val ctx = requireContext()
        addView(Utils.createCheckedSetting(ctx, CheckedSetting.ViewType.SWITCH, title, subtitle).apply {
            isChecked = settings.getBool(key, default)
            setOnCheckedListener {
                settings.setBool(key, it)
                PluginManager.stopPlugin("IgnoreFeature")
                PluginManager.startPlugin("IgnoreFeature")
            }
        })
    }
}
