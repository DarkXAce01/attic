package io.github.darkxace01.attic.ui

import android.content.ClipData
import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import io.github.darkxace01.attic.library

/**
 * Opens the share sheet with photos [ids]. Attic never sends anything anywhere itself: the app picked in
 * the sheet gets a copy and does the sending.
 */
suspend fun sharePhotos(context: Context, ids: List<Long>, title: String = "Share") {
    val files = context.library.shareFiles(ids)
    if (files.isEmpty()) return
    val uris = files.map { FileProvider.getUriForFile(context, "${context.packageName}.share", it) }
    val send = if (uris.size == 1) {
        Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uris[0])
    } else {
        Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
    }
    send.type = "image/jpeg"
    send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    // The preview in the sheet, and the grant, need the files in the clip too.
    send.clipData = ClipData.newRawUri(null, uris[0]).also { clip -> uris.drop(1).forEach { clip.addItem(ClipData.Item(it)) } }
    context.startActivity(Intent.createChooser(send, title).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}
