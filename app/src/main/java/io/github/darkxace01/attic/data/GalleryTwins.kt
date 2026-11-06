package io.github.darkxace01.attic.data

import android.content.ContentUris
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.provider.MediaStore
import io.github.darkxace01.attic.vision.PrintHash
import org.opencv.android.Utils
import org.opencv.core.Mat
import java.io.File

/**
 * Prints that are already in the phone's gallery: a scan someone once sent on WhatsApp, a photo of a
 * print saved years ago. Every gallery photo gets the same fingerprint as a print ([PrintHash]) and is
 * compared with every print's, whichever way up. Attic's own copies (Pictures/Attic) don't count.
 * Fingerprints are kept, so checking again only reads photos that are new or changed. Nothing in the
 * gallery is ever changed or deleted: this only says where the copies are.
 */
class GalleryTwins(private val context: Context, private val dao: AtticDao) {
    data class Twin(val print: PrintEntity, val uri: String)

    private val cache = File(context.filesDir, "gallery_hashes.txt")

    /** Every print with a copy in the gallery, and where. [progress]: photos read so far, of how many. */
    fun find(progress: (Int, Int) -> Unit = { _, _ -> }): List<Twin> {
        if (!PhonePhotos.allowed(context)) return emptyList()
        val known = readCache()
        val now = HashMap<Long, Pair<Long, Long>>() // id to (modified, hash)
        val photos = list()
        for ((k, p) in photos.withIndex()) {
            if (k % 20 == 0) progress(k, photos.size)
            val (id, modified) = p
            val had = known[id]
            val hash = if (had != null && had.first == modified) had.second else hashOf(id) ?: continue
            now[id] = modified to hash
        }
        progress(photos.size, photos.size)
        writeCache(now)
        val prints = dao.printsWithHashes()
        return match(prints.map { it to it.hashList }, now.mapValues { it.value.second })
            .map { (p, id) -> Twin(p, ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id).toString()) }
    }

    /** The gallery's photos, outside Attic's own folder: id and when each last changed. */
    private fun list(): List<Pair<Long, Long>> {
        val out = ArrayList<Pair<Long, Long>>()
        runCatching {
            context.contentResolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI, arrayOf(MediaStore.Images.Media._ID, MediaStore.Images.Media.DATE_MODIFIED),
                "${MediaStore.Images.Media.RELATIVE_PATH} NOT LIKE ?", arrayOf("Pictures/Attic/%"), null,
            )?.use { c -> while (c.moveToNext()) out += c.getLong(0) to c.getLong(1) }
        }
        return out
    }

    private fun hashOf(id: Long): Long? = runCatching {
        val uri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)
        // A fingerprint needs only 32 x 32: a thumbnail is plenty and much faster than the photo.
        val bmp: Bitmap = context.contentResolver.loadThumbnail(uri, android.util.Size(256, 256), null)
        val rgba = Mat()
        Utils.bitmapToMat(bmp.copy(Bitmap.Config.ARGB_8888, false), rgba)
        PrintHash.of(rgba).also { rgba.release() }
    }.getOrNull()

    private fun readCache(): Map<Long, Pair<Long, Long>> = runCatching {
        cache.readLines().mapNotNull { line ->
            val v = line.split(',')
            if (v.size != 3) null else v[0].toLong() to (v[1].toLong() to v[2].toLong())
        }.toMap()
    }.getOrDefault(emptyMap())

    private fun writeCache(m: Map<Long, Pair<Long, Long>>) {
        runCatching { cache.writeText(m.entries.joinToString("\n") { "${it.key},${it.value.first},${it.value.second}" }) }
    }

    companion object {
        /**
         * Stricter than for two scans ([PrintHash.SAME]): a gallery has thousands of photos, and at 14 bits a
         * few hundred prints would meet a stranger by chance. A copy of a scan, even resent and recompressed,
         * stays within a few bits.
         */
        const val SAME = 8

        /** Each print with the first gallery photo that shows the same picture, any way up. */
        fun match(prints: List<Pair<PrintEntity, List<Long>>>, gallery: Map<Long, Long>): List<Pair<PrintEntity, Long>> =
            prints.mapNotNull { (p, turns) ->
                if (turns.isEmpty()) return@mapNotNull null
                gallery.entries.firstOrNull { g -> turns.any { PrintHash.distance(it, g.value) <= SAME } }?.let { p to it.key }
            }
    }
}
