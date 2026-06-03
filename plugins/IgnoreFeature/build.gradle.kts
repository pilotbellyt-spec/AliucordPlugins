version = "0.0.5"
description = "Ignore users without blocking them"

aliucord {
    changelog.set(
        """
        # v0.0.4
        - Parse ignored users from the startup READY gateway payload.
        - Keep ignored-only startup users when the legacy relationship list is incomplete.
        - Stay on the standard relationship route and gateway events.
        - Handle both legacy and modern relationship event field names.
        - Log startup sync counts for easier verification.

        # v0.0.3
        - Improve cold-start sync for ignores made from Discord web/RN.
        - Run a delayed relationship sync after Aliucord startup.
        - Keep incomplete legacy relationship payloads from wiping the ignore cache.

        # v0.0.1
        - Add Ignore and Unignore to the profile actions menu.
        - Sync ignored users with Discord's relationship ignore API.
        - Collapse ignored users' chat messages using Discord's blocked-message UI.
        """.trimIndent(),
    )
    deploy.set(true)
}
