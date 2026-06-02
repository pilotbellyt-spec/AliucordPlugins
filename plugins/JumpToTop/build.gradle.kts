version = "0.0.4"
description = "Adds a DiscordRN-style jump-to-top button for forum chats"

aliucord {
    changelog.set(
        """
        # v0.0.4
        - Only show the button inside forum posts, not the forum home view.

        # v0.0.3
        - Jump to the forum post starter message instead of only the oldest loaded row.

        # v0.0.2
        - Replace the text caret with a real toolbar icon.
        - Use a normal menu item click handler.

        # v0.0.1
        - Add a caret button beside search in forum chat headers.
        - Jump to the oldest loaded message or forum row.
        """.trimIndent(),
    )
    deploy.set(true)
}
