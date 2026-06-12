package com.github.pilotbellytspec.managestickers

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import com.aliucord.Http
import com.aliucord.utils.GsonUtils
import org.json.JSONObject
import java.io.File
import java.io.IOException

data class GuildSticker(
    val id: Long,
    val name: String?,
    val description: String?,
    val tags: String?,
    val type: Int?,
    val format_type: Int?,
    val available: Boolean?,
    val user: StickerUser?,
)

data class StickerUser(
    val id: Long?,
    val username: String?,
    val global_name: String?,
    val discriminator: String?,
    val avatar: String?,
)

object StickerApi {
    fun list(guildId: Long): List<GuildSticker> {
        return Http.Request.newDiscordRNRequest("/guilds/$guildId/stickers").execute().use {
            it.json(GsonUtils.gsonRestApi, Array<GuildSticker>::class.java).toList()
        }
    }

    fun edit(guildId: Long, stickerId: Long, name: String, desc: String, tags: String) {
        val body = JSONObject()
            .put("name", name)
            .put("description", if (empty(desc)) JSONObject.NULL else desc)
            .put("tags", tags)

        Http.Request.newDiscordRNRequest("/guilds/$guildId/stickers/$stickerId", "PATCH")
            .setHeader("Content-Type", "application/json")
            .executeWithBody(body.toString())
            .use { ok(it) }
    }

    fun delete(guildId: Long, stickerId: Long) {
        Http.Request.newDiscordRNRequest("/guilds/$guildId/stickers/$stickerId", "DELETE")
            .execute()
            .use { ok(it) }
    }

    fun upload(ctx: Context, guildId: Long, uri: Uri, name: String, desc: String, tags: String) {
        val kind = ext(ctx, uri) ?: throw IOException("Sticker file must be PNG, APNG, JPG, JPEG, or GIF")
        val file = File(ctx.cacheDir, "sticker-upload-${System.currentTimeMillis()}.${if (jpg(kind)) "png" else kind}")
        save(ctx, uri, file, kind)

        try {
            Http.Request.newDiscordRNRequest("/guilds/$guildId/stickers", "POST")
                .executeWithMultipartForm(
                    mapOf(
                        "name" to name,
                        "description" to desc,
                        "tags" to tags,
                        "file" to file,
                    ),
                    false,
                )
                .use { ok(it) }
        } finally {
            file.delete()
        }
    }

    private fun save(ctx: Context, uri: Uri, file: File, kind: String) {
        ctx.contentResolver.openInputStream(uri).use { input ->
            if (jpg(kind)) {
                val bmp = BitmapFactory.decodeStream(input) ?: throw IOException("Could not read sticker image")
                try {
                    file.outputStream().use { output ->
                        if (!bmp.compress(Bitmap.CompressFormat.PNG, 100, output)) throw IOException("Could not convert sticker image")
                    }
                } finally {
                    bmp.recycle()
                }
            } else {
                file.outputStream().use { output -> input?.copyTo(output) }
            }
        }
    }

    private fun empty(text: String?): Boolean {
        return text == null || text.trim().isEmpty()
    }

    private fun ext(ctx: Context, uri: Uri): String? {
        val nameExt = name(ctx, uri)?.substringAfterLast('.', "")?.toLowerCase()
        if (good(nameExt)) return nameExt!!

        val mimeExt = MimeTypeMap.getSingleton().getExtensionFromMimeType(ctx.contentResolver.getType(uri))?.toLowerCase()
        if (good(mimeExt)) return mimeExt!!

        return when (ctx.contentResolver.getType(uri)) {
            "image/apng" -> "apng"
            "image/png" -> "png"
            "image/gif" -> "gif"
            "image/jpeg" -> "jpg"
            else -> null
        }
    }

    private fun name(ctx: Context, uri: Uri): String? {
        return ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        }
    }

    private fun good(ext: String?): Boolean {
        return ext == "png" || ext == "apng" || ext == "jpg" || ext == "jpeg" || ext == "gif"
    }

    private fun jpg(ext: String): Boolean {
        return ext == "jpg" || ext == "jpeg"
    }

    private fun ok(res: Http.Response) {
        try {
            res.assertOk()
        } catch (err: Http.HttpException) {
            throw IOException(clean(err), err)
        }
    }

    fun clean(err: Throwable): String {
        var hit: Throwable? = err
        while (hit != null) {
            msg(hit.message)?.let { return it }
            msg(hit.toString())?.let { return it }
            hit = hit.cause
        }
        return "Discord rejected the request"
    }

    private fun msg(raw: String?): String? {
        if (raw == null) return null
        val start = raw.indexOf('{')
        val end = raw.lastIndexOf('}')
        if (start != -1 && end > start) runCatching {
            JSONObject(raw.substring(start, end + 1)).optString("message").takeIf { it.trim().isNotEmpty() }
        }.getOrNull()?.let { return it }
        val key = raw.indexOf("\"message\"")
        if (key == -1) return null
        val colon = raw.indexOf(':', key)
        val first = raw.indexOf('"', colon + 1)
        val last = raw.indexOf('"', first + 1)
        if (colon == -1 || first == -1 || last == -1) return null
        return raw.substring(first + 1, last)
    }
}
