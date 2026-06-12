package com.github.pilotbellytspec.managestickers

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.text.InputFilter
import android.text.InputType
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.widget.PopupMenu
import com.aliucord.Utils
import com.aliucord.fragments.ConfirmDialog
import com.aliucord.fragments.SettingsPage
import com.aliucord.utils.DimenUtils
import com.aliucord.utils.ViewUtils.addTo
import com.aliucord.views.Button
import com.aliucord.views.TextInput
import com.discord.stores.StoreStream
import com.discord.utilities.color.ColorCompat
import com.facebook.drawee.view.SimpleDraweeView
import com.lytefast.flexinput.R
import java.util.Locale

class StickerPage(private val guildId: Long) : SettingsPage() {
    private var picker: ActivityResultLauncher<Intent>? = null
    private var picked: Uri? = null
    private var used = 0
    private var max = 5
    private var list = false

    private class Form(val root: LinearLayout, val name: EditText, val desc: EditText, val tags: EditText)

    override fun onViewBound(view: View) {
        super.onViewBound(view)
        pages.add(this)
        setActionBarTitle("Stickers")
        setActionBarSubtitle("")
        picker = requireActivity().registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
            if (res.resultCode != Activity.RESULT_OK) return@registerForActivityResult
            val uri = res.data?.data ?: return@registerForActivityResult
            if (tooBig(uri)) {
                Utils.showToast("Sticker file must be 512KB or smaller")
                return@registerForActivityResult
            }
            picked = uri
            uploadDialog(uri)
        }
        load()
    }

    override fun onDestroyView() {
        pages.remove(this)
        super.onDestroyView()
    }

    fun reload(guild: Long) {
        if (guild == guildId && list) Utils.mainThread.post { load() }
    }

    private fun load() {
        list = true
        setActionBarTitle("Stickers")
        setActionBarSubtitle("")
        clear()
        val ctx = requireContext()
        header(null)
        addDivider(ctx)
        TextView(ctx, null, 0, R.i.UiKit_Settings_Item_SubText).apply {
            text = "Loading stickers..."
        }.addTo(linearLayout)

        Utils.threadPool.execute {
            runCatching { StickerApi.list(guildId) }
                .onSuccess { stickers -> Utils.mainThread.post { show(stickers) } }
                .onFailure { err -> Utils.mainThread.post { fail(err) } }
        }
    }

    private fun show(stickers: List<GuildSticker>) {
        clear()
        val ctx = requireContext()
        used = stickers.size
        max = limit()
        header(stickers.size)
        addDivider(ctx)
        if (stickers.isEmpty()) {
            TextView(ctx, null, 0, R.i.UiKit_Settings_Item_SubText).apply {
                text = "No stickers uploaded yet."
            }.addTo(linearLayout)
            return
        }

        stickers.sortedBy { text(it.name).toLowerCase(Locale.ROOT) }.forEach { sticker ->
            row(sticker).addTo(linearLayout)
        }
    }

    private fun fail(err: Throwable) {
        clear()
        val ctx = requireContext()
        TextView(ctx, null, 0, R.i.UiKit_Settings_Item_Header).apply {
            text = "Could not load stickers"
        }.addTo(linearLayout)
        TextView(ctx, null, 0, R.i.UiKit_Settings_Item_SubText).apply {
            text = err.message ?: "Discord rejected the request."
        }.addTo(linearLayout)
    }

    private fun header(count: Int?) {
        val ctx = requireContext()
        val view = LayoutInflater.from(ctx).inflate(Utils.getResId("widget_server_settings_emojis_header", "layout"), linearLayout, false)
        view.findViewById<TextView>(Utils.getResId("widget_server_settings_emojis_upload", "id")).apply {
            text = "Upload Sticker"
            setOnClickListener { pick() }
        }
        view.findViewById<TextView>(Utils.getResId("widget_server_settings_emojis_upload_description", "id")).text =
            if (count == null) "Loading stickers..." else "${max - count} / $max slots available"
        view.addTo(linearLayout)
    }

    private fun row(sticker: GuildSticker): View {
        val ctx = requireContext()
        val view = LayoutInflater.from(ctx).inflate(Utils.getResId("widget_server_settings_emojis_item", "layout"), linearLayout, false)
        val avatar = view.findViewById<SimpleDraweeView>(Utils.getResId("server_settings_emojis_avatar", "id"))
        val name = view.findViewById<TextView>(Utils.getResId("server_settings_emojis_name", "id"))
        val nick = view.findViewById<TextView>(Utils.getResId("server_settings_emojis_nickname", "id"))
        val tags = view.findViewById<TextView>(Utils.getResId("server_settings_emojis_username", "id"))
        val userAvatar = view.findViewById<SimpleDraweeView>(Utils.getResId("server_settings_emojis_username_avatar", "id"))
        val more = view.findViewById<ImageView>(Utils.getResId("server_settings_emojis_overflow", "id"))

        avatar.setImageURI("https://media.discordapp.net/stickers/${sticker.id}.png?size=96")
        name.text = sticker.name ?: "Unnamed Sticker"
        nick.text = ""
        nick.visibility = View.GONE
        val by = author(sticker)
        tags.text = text(by)
        tags.visibility = if (by == null) View.GONE else View.VISIBLE
        val icon = userIcon(sticker)
        userAvatar.visibility = if (icon == null) View.GONE else View.VISIBLE
        userAvatar.setImageURI(icon)
        more.setColorFilter(ColorCompat.getThemedColor(ctx, R.b.colorInteractiveNormal))
        more.setOnClickListener { actions(more, sticker) }
        view.setOnClickListener { editDialog(sticker) }
        return view
    }

    private fun actions(anchor: View, sticker: GuildSticker) {
        PopupMenu(requireContext(), anchor).apply {
            menu.add(0, 1, 0, "Edit Sticker")
            menu.add(0, 2, 1, "Delete Sticker")
            setOnMenuItemClickListener {
                when (it.itemId) {
                    1 -> editDialog(sticker)
                    2 -> Utils.mainThread.postDelayed({ deleteDialog(sticker) }, 150)
                }
                true
            }
            show()
        }
    }

    private fun editDialog(sticker: GuildSticker) {
        formPage("Edit Sticker", "Save", sticker, null)
    }

    private fun uploadDialog(uri: Uri) {
        formPage("Upload Sticker", "Upload", null, uri)
    }

    private fun deleteDialog(sticker: GuildSticker) {
        val dlg = StickerDeleteDialog()
        dlg.setTitle("Delete Sticker")
            .setDescription("Delete ${sticker.name ?: "this sticker"}?")
            .setIsDangerous(true)
            .setOnOkListener {
                dlg.dismiss()
                delete(sticker)
            }
            .show(parentFragmentManager, "DeleteSticker")
    }

    private fun delete(sticker: GuildSticker) {
        Utils.showToast("Deleting sticker...")
        work("Deleted sticker") { StickerApi.delete(guildId, sticker.id) }
    }

    private fun formPage(title: String, ok: String, sticker: GuildSticker?, uri: Uri?) {
        val ctx = requireContext()
        list = false
        clear()
        setActionBarTitle(title)
        setActionBarSubtitle("")

        TextView(ctx, null, 0, R.i.UiKit_Settings_Item_Header).apply {
            text = title
        }.addTo(linearLayout)

        val box = fields(sticker?.name, sticker?.description, sticker?.tags)
        box.root.addTo(linearLayout)

        val btns = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            setPadding(DimenUtils.dpToPx(16), DimenUtils.dpToPx(16), DimenUtils.dpToPx(16), 0)
        }.addTo(linearLayout)

        TextView(ctx, null, 0, R.i.UiKit_Settings_Item_SubText).apply {
            text = "Cancel"
            gravity = Gravity.CENTER
            setTextColor(ColorCompat.getThemedColor(ctx, R.b.colorInteractiveNormal))
            setPadding(DimenUtils.dpToPx(12), DimenUtils.dpToPx(8), DimenUtils.dpToPx(12), DimenUtils.dpToPx(8))
            setOnClickListener { load() }
        }.addTo(btns)

        Button(ctx).apply {
            text = ok
            setOnClickListener {
                val name = box.name.text.toString().trim()
                val desc = box.desc.text.toString().trim()
                val tags = box.tags.text.toString().trim()
                if (empty(name) || empty(tags)) {
                    Utils.showToast("Name and tags are required")
                    return@setOnClickListener
                }
                if (sticker != null) {
                    work("Saved sticker") { StickerApi.edit(guildId, sticker.id, name, desc, tags) }
                } else if (uri != null) {
                    work("Uploaded sticker") { StickerApi.upload(requireContext(), guildId, uri, name, desc, tags) }
                }
            }
        }.addTo(btns)
    }

    private fun fields(name: String?, desc: String?, tags: String?): Form {
        val ctx = requireContext()
        val pad = DimenUtils.dpToPx(16)

        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, 0)
        }

        val a = field(root, "Name", text(name), false)
        val b = field(root, "Description", text(desc).take(100), true)
        val c = field(root, "Tags", text(tags), false)
        return Form(root, a, b, c)
    }

    private fun field(root: LinearLayout, hint: String, value: String, multi: Boolean): EditText {
        val gap = DimenUtils.dpToPx(10)
        val box = TextInput(requireContext(), hint, value).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                if (root.childCount > 0) topMargin = gap
            }
        }
        val edit = box.editText.apply {
            if (multi) {
                minLines = 2
                maxLines = 4
                filters = arrayOf(InputFilter.LengthFilter(100))
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            } else {
                setSingleLine(true)
            }
        }
        root.addView(box)
        return edit
    }

    private fun pick() {
        if (used >= max) {
            Utils.showToast("This server has no sticker slots left")
            return
        }
        picker?.launch(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            type = "*/*"
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("image/png", "image/apng", "image/jpeg", "image/gif"))
        })
    }

    private fun work(done: String, op: () -> Unit) {
        Utils.threadPool.execute {
            runCatching { op() }
                .onSuccess {
                    Utils.mainThread.post {
                        Utils.showToast(done)
                        load()
                    }
                }
                .onFailure {
                    Utils.mainThread.post { Utils.showToast(StickerApi.clean(it)) }
                }
        }
    }

    private fun text(value: String?): String {
        return value ?: ""
    }

    private fun author(sticker: GuildSticker): String? {
        val user = sticker.user ?: return null
        return keep(user.username)
    }

    private fun userIcon(sticker: GuildSticker): String? {
        val user = sticker.user ?: return null
        val id = user.id ?: return null
        val hash = keep(user.avatar)
        if (hash != null) {
            val ext = if (hash.startsWith("a_")) "gif" else "png"
            return "https://cdn.discordapp.com/avatars/$id/$hash.$ext?size=32"
        }
        val n = keep(user.discriminator)?.toIntOrNull()
        val idx = if (n == null || n == 0) ((id shr 22) % 6).toInt() else n % 5
        return "https://cdn.discordapp.com/embed/avatars/$idx.png"
    }

    private fun keep(value: String?): String? {
        return if (value == null || value.trim().isEmpty()) null else value
    }

    private fun empty(value: String?): Boolean {
        return value == null || value.trim().isEmpty()
    }

    private fun tooBig(uri: Uri): Boolean {
        val size = requireContext().contentResolver.openFileDescriptor(uri, "r")?.use { it.statSize } ?: -1
        return size > 512 * 1024
    }

    private fun limit(): Int {
        return when (StoreStream.getGuilds().getGuild(guildId)?.premiumTier ?: 0) {
            1 -> 15
            2 -> 30
            3 -> 60
            else -> 5
        }
    }

    class StickerDeleteDialog : ConfirmDialog() {
        override fun onViewBound(view: View) {
            super.onViewBound(view)
            val w = view.resources.displayMetrics.widthPixels - DimenUtils.dpToPx(96)
            val width = w.coerceAtMost(DimenUtils.dpToPx(320)).coerceAtLeast(DimenUtils.dpToPx(260))
            getRoot().minimumWidth = width
            getRoot().layoutParams = (getRoot().layoutParams ?: ViewGroup.LayoutParams(width, ViewGroup.LayoutParams.WRAP_CONTENT)).apply {
                this.width = width
            }
            getOKButton().setText("Delete")
        }
    }

    companion object {
        private val pages = mutableSetOf<StickerPage>()

        fun refresh(guildId: Long) {
            pages.toList().forEach { it.reload(guildId) }
        }
    }
}
