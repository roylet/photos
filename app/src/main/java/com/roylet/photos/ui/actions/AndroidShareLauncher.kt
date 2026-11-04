package com.roylet.photos.ui.actions

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import java.io.File

/** Android share sheet (files go out through our FileProvider) and the clipboard. */
class AndroidShareLauncher(private val context: Context) : ShareLauncher {
    private val authority = "${context.packageName}.files"

    override fun sendFiles(files: List<File>) {
        if (files.isEmpty()) return
        val uris = ArrayList<Uri>(files.map { FileProvider.getUriForFile(context, authority, it) })
        val mimes = files.map { mimeOf(it) }
        val type = when {
            mimes.distinct().size == 1 -> mimes.first()
            mimes.all { it.startsWith("image/") } -> "image/*"
            mimes.all { it.startsWith("video/") } -> "video/*"
            else -> "*/*"
        }
        val send = if (uris.size == 1) {
            Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uris[0])
        } else {
            Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
        }
        send.setType(type).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        // ClipData carries the read grant through the chooser to whichever app is picked.
        send.clipData = ClipData.newRawUri(null, uris[0]).apply { uris.drop(1).forEach { addItem(ClipData.Item(it)) } }
        start(Intent.createChooser(send, null))
    }

    override fun sendText(text: String) {
        start(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), null))
    }

    override fun copy(text: String) {
        val cm = context.getSystemService(ClipboardManager::class.java) ?: return
        cm.setPrimaryClip(ClipData.newPlainText("Photos link", text))
    }

    private fun start(intent: Intent) {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        runCatching { context.startActivity(intent) }
    }

    companion object {
        fun mimeOf(f: File): String =
            MimeTypeMap.getSingleton().getMimeTypeFromExtension(f.extension.lowercase()) ?: when (f.extension.lowercase()) {
                "heic", "heif" -> "image/heif"
                "mov" -> "video/quicktime"
                "dng" -> "image/x-adobe-dng"
                else -> "application/octet-stream"
            }
    }
}
