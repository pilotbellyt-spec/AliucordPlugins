package com.github.pilotbellytspec.modernuserstyles

import android.os.Bundle
import android.view.View
import android.widget.TextView
import com.aliucord.Utils
import com.aliucord.api.SettingsAPI
import com.aliucord.widgets.BottomSheet
import com.discord.views.CheckedSetting
import com.lytefast.flexinput.R

class PluginSettings(private val settings: SettingsAPI) : BottomSheet() {
    override fun onViewCreated(view: View, bundle: Bundle?) {
        super.onViewCreated(view, bundle)

        val sheetContext = requireContext()

        fun row(title: String, subtext: String, key: String, default: Boolean = true) {
            addView(
                Utils.createCheckedSetting(sheetContext, CheckedSetting.ViewType.SWITCH, title, subtext).apply {
                    isChecked = settings.getBool(key, default)
                    setOnCheckedListener {
                        settings.setBool(key, it)
                        Utils.promptRestart("ModernUserStyles settings require a restart. Restart now?")
                    }
                },
            )
        }

        addView(
            TextView(sheetContext, null, 0, R.i.UiKit_Settings_Item_Header).apply {
                text = "Modern User Styles"
            },
        )
        addView(
            TextView(sheetContext, null, 0, R.i.UiKit_Settings_Item_SubText).apply {
                text = "Modern display names and role colors in the places old Android misses."
            },
        )

        row("Display names", "Use server and global display names in username-only views.", "displayNames")
        row("Name styles", "Use Discord's custom fonts, colors, and profile effects.", "displayNameStyles")
        row("Role gradients", "Use modern secondary and tertiary role colors when present.", "roleGradients")

        addView(
            TextView(sheetContext, null, 0, R.i.UiKit_Settings_Item_Header).apply {
                text = "Places"
            },
        )
        row("Chat names", "Message author names.", "chatNames")
        row("Member list", "Names in the channel member list.", "memberList")
        row("Profiles", "Primary names on profile sheets.", "profileNames")
        row("Mentions", "Rendered user mentions.", "mentions")
        row("Autocomplete", "Mention autocomplete names and input spans.", "autocomplete")
        row("DM list", "Private-channel recipient names.", "dmList")
        row("Voice names", "Voice and stage user names.", "voiceNames")
        row("Reaction users", "Names in reaction user lists.", "reactionUsers")
    }
}
