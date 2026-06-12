version = "0.0.22"
description = "Adds a Stickers page to server settings"

aliucord {
    changelog.set(
        """
        # v0.0.22
        - Use loading copy in the sticker list header before slots are loaded.

        # v0.0.21
        - Remove Lottie JSON sticker uploads because Discord rejects them.

        # v0.0.20
        - Keep sticker descriptions out of the author subtext area.

        # v0.0.19
        - Limit sticker descriptions to 100 characters.
        - Show sticker author username and avatar like the emoji settings list.

        # v0.0.18
        - Fix a crash when showing sticker authors on older Kotlin runtime.

        # v0.0.17
        - Show the sticker author's username in the sticker list.

        # v0.0.16
        - Align the Stickers server settings icon with Discord's native settings rows.

        # v0.0.15
        - Convert JPG and JPEG sticker uploads to PNG like Discord web.

        # v0.0.14
        - Allow JPG and JPEG sticker uploads without renaming the file type.
        - Clean Discord upload errors before showing them in toasts.

        # v0.0.13
        - Clean Discord API errors at the toast boundary too.

        # v0.0.12
        - Show Discord API error messages cleanly in toasts.

        # v0.0.11
        - Remove the sticker dimension safeguard.

        # v0.0.10
        - Block sticker uploads over Discord's maximum dimensions.

        # v0.0.9
        - Refresh the open Stickers page when Discord sends sticker updates.

        # v0.0.8
        - Preserve sticker upload file type for GIF and Lottie files.

        # v0.0.7
        - Block sticker uploads over 512KB before showing the form.

        # v0.0.6
        - Show remaining sticker slots and block uploads when full.

        # v0.0.5
        - Fix delete confirmation showing after the sticker menu closes.

        # v0.0.4
        - Fix delete confirmation width for short sticker names.

        # v0.0.3
        - Fix sticker delete menu opening.

        # v0.0.2
        - Fix duplicate Stickers rows in server settings.
        - Fix delete confirmation action.

        # v0.0.1
        - Add a Stickers row under Emoji in server settings.
        - Add a server sticker manager with upload, edit, and delete actions.
        """.trimIndent(),
    )
    deploy.set(true)
}
