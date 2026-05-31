version = "0.0.4"
description = "Backport of Discord's ignore user feature"

aliucord {
    changelog.set(
        """
        # v0.0.4
        - Fixed launch-time ignore sync to parse Discord's raw relationship payload.
        - Reads ignored users from the startup READY gateway payload.
        - Prevents legacy REST snapshots from clearing ignored-only startup users.
        - Keeps sync to the standard relationship route and gateway events only.
        - Supports both legacy and modern relationship event field names.
        - Added count-only sync logging for startup verification.

        # v0.0.3
        - Improved cold-start sync for ignores made from Discord web/RN.
        - Added delayed RN relationship sync after Aliucord startup.
        - Prevented incomplete legacy relationship payloads from wiping the ignore cache.

        # v0.0.1
        - Added Ignore and Unignore to the profile actions menu.
        - Syncs ignored users with Discord's relationship ignore API.
        - Collapses ignored users' chat messages using Discord's blocked-message UI.
        """.trimIndent(),
    )
    deploy.set(true)
}
