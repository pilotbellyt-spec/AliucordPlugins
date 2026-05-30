import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

version = "0.0.15"
description = "Backport of gradient user roles & custom display names features from DiscordRN"

dependencies {
    implementation("io.github.khoben.woff2-android:typeface:0.0.2") {
        exclude(group = "org.jetbrains.kotlin")
    }
    implementation("androidx.startup:startup-runtime:1.2.0") {
        exclude(group = "org.jetbrains.kotlin")
    }
}

fun appendModernUserStyleResources() {
    val output = layout.buildDirectory.file("outputs/ModernUserStyles.zip").get().asFile
    val packageDir = layout.projectDirectory.dir("src/main/modern_user_styles_package").asFile
    if (!output.exists() || !packageDir.exists()) return

    val temp = output.resolveSibling("${output.name}.tmp")
    ZipInputStream(output.inputStream().buffered()).use { input ->
        ZipOutputStream(temp.outputStream().buffered()).use { zip ->
            while (true) {
                val entry = input.nextEntry ?: break
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
        # 0.0.15
        * Link the author metadata to my Discord profile.

        # 0.0.14
        * Update public-facing plugin metadata and settings copy.

        # 0.0.13
        * Prepare the plugin for public repository review.
        * Remove debug slash commands and noisy profile logging.
        * Update package, author metadata, and plugin description.

        # 0.0.12
        * Ignore legacy fallback member colors unless they resolve to an actual colored guild role, keeping roleless users white.
        * Preserve server nicknames during async profile refreshes instead of replacing them with global display names.
        * Render role colors without display-name glow/neon effects that Discord web does not apply to roles.

        # 0.0.11
        * Keep users with no colored role white instead of falling back to display-name style colors.

        # 0.0.10
        * Load Discord's real WOFF2 display-name fonts with woff2-android instead of Android approximations.
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
