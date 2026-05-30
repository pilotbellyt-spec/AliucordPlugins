package com.github.pilotbellytspec.modernuserstyles

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

        fun addToggle(title: String, subtext: String, key: String, default: Boolean = true) {
            addView(
                Utils.createCheckedSetting(ctx, CheckedSetting.ViewType.SWITCH, title, subtext).apply {
                    isChecked = settings.getBool(key, default)
                    setOnCheckedListener {
                        settings.setBool(key, it)
                        PluginManager.stopPlugin("ModernUserStyles")
                        PluginManager.startPlugin("ModernUserStyles")
                    }
                },
            )
        }

        addView(
            TextView(ctx, null, 0, R.i.UiKit_Settings_Item_Header).apply {
                text = "Modern User Styles"
            },
        )
        addView(
            TextView(ctx, null, 0, R.i.UiKit_Settings_Item_SubText).apply {
                text = "Shows modern gradient role colors and custom display names throughout Discord."
            },
        )

        addToggle("Display names", "Use global/display names where legacy Discord still shows usernames.", "displayNames")
        addToggle("Display name styles", "Render display_name_styles font, colors, and effect IDs.", "displayNameStyles")
        addToggle("Role gradients", "Render role colors.secondary_color and tertiary_color when available.", "roleGradients")

        addView(
            TextView(ctx, null, 0, R.i.UiKit_Settings_Item_Header).apply {
                text = "Locations"
            },
        )
        addToggle("Chat names", "Style message author names.", "chatNames")
        addToggle("Member list", "Style names in the channel member list.", "memberList")
        addToggle("Profiles", "Style profile primary names.", "profileNames")
        addToggle("Mentions", "Style rendered user mentions.", "mentions")
        addToggle("Autocomplete", "Style mention autocomplete names and input spans.", "autocomplete")
        addToggle("DM list", "Style private-channel recipient names.", "dmList")
        addToggle("Voice names", "Style voice and stage user names.", "voiceNames")
        addToggle("Reaction users", "Style names in reaction user lists.", "reactionUsers")
    }
}
