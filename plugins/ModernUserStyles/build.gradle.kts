import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

version = "0.0.47"
description = "Gradient roles and custom display names from DiscordRN"

fun appendModernUserStyleResources() {
    val output = layout.buildDirectory.file("outputs/ModernUserStyles.zip").get().asFile
    val packageDir = layout.projectDirectory.dir("src/main/modern_user_styles_package").asFile
    if (!output.exists() || !packageDir.exists()) return

    val temp = output.resolveSibling("${output.name}.tmp")
    ZipInputStream(output.inputStream().buffered()).use { input ->
        ZipOutputStream(temp.outputStream().buffered()).use { zip ->
            while (true) {
                val entry = input.nextEntry ?: break
                if (entry.name.startsWith("modern_user_styles/")) {
                    input.closeEntry()
                    continue
                }
                zip.putNextEntry(ZipEntry(entry.name))
                input.copyTo(zip)
                zip.closeEntry()
                input.closeEntry()
            }

            packageDir.walkTopDown()
                .filter { it.isFile }
                .forEach { file ->
                    val name = packageDir.toPath().relativize(file.toPath()).toString().replace('\\', '/')
                    zip.putNextEntry(ZipEntry("modern_user_styles/$name"))
                    file.inputStream().buffered().use { it.copyTo(zip) }
                    zip.closeEntry()
                }
        }
    }
    temp.copyTo(output, overwrite = true)
    temp.delete()
}

tasks.named("make") {
    doLast {
        appendModernUserStyleResources()
    }
}

aliucord {
    changelog.set(
        """
        # 0.0.47
        * Refresh tab toolbar ownership and clear stale subtitles during tab switches.

        # 0.0.46
        * Stop stale display-name font styles from sticking to recycled message rows.

        # 0.0.45
        * Keep regular message role colors tied to Discord's current message row data.

        # 0.0.44
        * Preserve theme fonts for users without a custom display-name font.

        # 0.0.43
        * Recalculate reply preview spacing after styled reply names render.

        # 0.0.42
        * Make install, uninstall, update, and settings changes require an Aliucord restart.
        * Keep solid role colors when role gradients are turned off.
        * Restore normal toolbar title styling when leaving styled DMs.

        # 0.0.41
        * Keep DM sidebar font styles on unselected rows while limiting full name effects to the selected DM.
        * Guard delayed drawable-name refreshes with the same row tag checks used by chat names.
        * Restore non-DM toolbar titles after clearing stale DM name effects.
        * Keep server channel toolbar titles out of the DM name-effect path.

        # 0.0.40
        * Stop DM name effects from leaking into non-DM toolbar titles.

        # 0.0.39
        * Clear stale DM header and toolbar name effects when switching conversations.

        # 0.0.38
        * Bind reply names from the current message row so recycled reply previews keep the right user, font, and role color.

        # 0.0.37
        * Keep the DM sidebar and header fixes on top of the stable reply styling build.

        # 0.0.36
        * Keep DM sidebar names normal until their one-to-one DM is selected, then show the custom effect.
        * Keep group DM names from being replaced by a single recipient's styled name.
        * Keep Discord's selected, unread, and normal DM sidebar colors when clearing name effects.
        * Clear stale one-to-one DM header effects when switching into group DMs.

        # 0.0.35
        * Harden reply username styling by following Discord's reply author path and avoiding recycled row state.
        * Keep Discord's live reply color when custom style or role data is not available yet.

        # 0.0.33
        * Rework chat, reply, and member-list styling to use Discord row models instead of recycled TextView state.
        * Stop using previous view colors as role fallbacks, fixing stale names, fonts, and role colors on reused rows.

        # 0.0.32
        * Apply custom display-name colors and effects to DM headers, the top chat bar, the DM list, and private member-list names.
        * Style the large "beginning of DM" header with the same custom name effect path used by profiles.

        # 0.0.31
        * Let long customized profile names wrap instead of collapsing to ellipses.
        * Split profile effect spans by word so styled names can line-break naturally.

        # 0.0.30
        * Fix reply names sometimes using stale user data from recycled chat rows.
        * Preserve server-specific reply names while still applying custom fonts and role colors.

        # 0.0.29
        * Prevent display-name fonts from leaking onto users whose profile payload does not include a font style.

        # 0.0.28
        * Use bundled TTF/OTF display-name fonts instead of native font decoding.
        * Re-render reply names after profile and role data loads so custom fonts and role colors stay consistent.

        # 0.0.27
        * Gate role gradients and holographic role colors on the server's Enhanced Role Styles perk instead of treating booster roles specially.

        # 0.0.26
        * Keep premium subscriber roles from creating false chat gradients while preserving their primary color.

        # 0.0.25
        * Prevent reflected legacy role color data from creating false chat gradients.

        # 0.0.24
        * Fix profile effect animations being wiped by follow-up renders.

        # 0.0.23
        * Restore Discord web's separate horizontal role-gradient shader for chat and limit profile animations to exact animated effects.

        # 0.0.22
        * Fix toon profile names to match Discord web's larger candy-fill gradient and add short profile effect animations.

        # 0.0.21
        * Fix customized profile display names collapsing to ellipses.

        # 0.0.20
        * Match Discord web gradient direction, color contrast, strokes, and layered profile name effects more closely.

        # 0.0.19
        * Show custom display-name colors and effects on profiles while keeping chat and member-list names role-colored.

        # 0.0.18
        * Apply reply name styles before Discord calculates reply preview spacing.

        # 0.0.17
        * Fix styled reply names overlapping the replied-to message text.

        # 0.0.16
        * Make role gradients closer to Discord web.
        * Fix member-list users sometimes showing as null.

        # 0.0.15
        * Link the author metadata to my Discord profile.

        # 0.0.14
        * Update public-facing plugin metadata and settings copy.

        # 0.0.13
        * Prepare the plugin for public repository review.
        * Remove old slash commands and noisy profile logging.
        * Update package, author metadata, and plugin description.

        # 0.0.12
        * Ignore legacy fallback member colors unless they resolve to an actual colored guild role, keeping roleless users white.
        * Preserve server nicknames during async profile refreshes instead of replacing them with global display names.
        * Render role colors without display-name glow/neon effects that Discord web does not apply to roles.

        # 0.0.11
        * Keep users with no colored role white instead of falling back to display-name style colors.

        # 0.0.10
        * Load Discord's real display-name fonts instead of Android approximations.
        * Match the current Discord web font-class mapping and letter spacing.
        * Let role colors/gradients stay authoritative when a user also has a custom font.

        # 0.0.9
        * Style member-list UsernameView's internal username text so side-menu gradients apply.
        * Style replied-to usernames in reply previews.
        * Stop changing text weight, text scale, or letter spacing for gradients/effects.

        # 0.0.8
        * Resolve member-list role gradients from the real guild member roles instead of only item.color.
        * Use selected guild fallback for member-list role/color fetches.

        # 0.0.7
        * Resolve role colors by highest colored role position, matching Discord role precedence.
        * Let higher solid roles override lower gradient roles and improve per-font Android styling.

        # 0.0.6
        * Rework name rendering to apply color, gradient, shadow, and typeface directly to TextViews.
        * Make font ID fallbacks more visually distinct on Android.

        # 0.0.5
        * Fetch display-name styles through the modern popout profile route with guild context.
        * Cache profile fetches by user and guild so a no-style lightweight response does not block styled data.

        # 0.0.4
        * Apply single-color display-name styles for effects and font styling.
        * Parse display_name_styles anywhere in profile payloads and avoid member-list guildId crashes.

        # 0.0.3
        * Make gradients higher contrast and easier to read on small text.
        * Reset unstyled names to white instead of inheriting Discord's blue tint.

        # 0.0.2
        * Fetch modern profile display-name data and role color payloads directly for chat/member/profile surfaces.
        * Only render colors when Discord sends an actual gradient; custom fonts still render without colors.

        # 0.0.1
        * Initial ModernUserStyles build.
        * Render display names, display-name style IDs, and real role gradients where legacy data exposes them.
        """.trimIndent(),
    )
    deploy.set(true)
}
