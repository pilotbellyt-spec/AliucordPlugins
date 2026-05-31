package com.github.pilotbellytspec.messagebookmarks

data class BookmarkRecord(
    val channelId: Long,
    val messageId: Long,
    val guildId: Long?,
    val authorId: Long?,
    val authorName: String?,
    val channelName: String?,
    val content: String?,
    val savedAt: Long,
    val dueAt: Long?,
) {
    val key: String
        get() = key(channelId, messageId)

    companion object {
        fun key(channelId: Long, messageId: Long) = "$channelId-$messageId"
    }
}
