version = "0.0.4"
description = "Message bookmarks and reminders"

aliucord {
    changelog.set(
        """
        # 0.0.4
        * Keep the Bookmarks view from taking over Discord's shared titlebar.

        # 0.0.3
        * Show saved messages with Recent Mentions style channel headers and reply previews.

        # 0.0.1
        * Add local message bookmarks and reminders.
        * Add opt-in sync mode for Discord's saved-message API.
        * Add bookmark/reminder actions to the message action sheet.
        * Add a Bookmarks button to Recent Mentions.
        """.trimIndent(),
    )
    deploy.set(true)
}
