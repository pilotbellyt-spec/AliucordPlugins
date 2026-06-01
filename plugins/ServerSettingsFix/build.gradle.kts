version = "0.0.3"
description = "Server settings fixes for legacy Android"

aliucord {
    changelog.set(
        """
        # 0.0.3
        - Rename plugin to ServerSettingsFix.
        - Use Discord's current audit-log route.
        
        # 0.0.1
        - Use Discord's paginated bans endpoint.
        - Keep stuck audit-log requests from leaving the page in a loading state.
        - Add a 30-minute invite option.
        """.trimIndent(),
    )
    deploy.set(true)
}
