version = "0.0.16"
description = "Backport of Discord message requests"

aliucord {
    changelog.set(
        """
        # 0.0.16
        * Keep live message request rows available for the request inbox before restart.
        * Refresh the request list when opening the Message Requests page.

        # 0.0.14
        * Only use Discord's is_message_request field to classify request DMs.

        # 0.0.13
        * Stop treating generic pending consent as a message request.

        # 0.0.12
        * Stop REST sync from overwriting live message request gateway state.

        # 0.0.11
        * Fix request inbox sync when Discord returns split channel arrays.
        * Keep accepted requests out of the inbox when stale request markers remain.

        # 0.0.10
        * Refresh the DM list after request sync/live state changes.

        # 0.0.5
        * Stop treating empty message request timestamp fields as pending requests.

        # 0.0.4
        * Keep message request filtering on the DM adapter data path so rows cannot fall back into normal DMs.

        # 0.0.3
        * Detect Discord's real message request channel fields.
        * Sync the current private channel list on startup so existing requests move out of normal DMs.

        # 0.0.2
        * Move pending message requests into a separate Direct Messages page.
        * Add a Message Requests button beside the new DM button.
        * Hide pending requests from the normal DM list.

        # 0.0.1
        * Add basic message request detection from Discord gateway payloads.
        * Add Accept and Deny actions for pending DM requests.
        * Add a small request banner to the start of DM chats.
        """.trimIndent(),
    )
    deploy.set(true)
}
