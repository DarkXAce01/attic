package io.github.darkxace01.attic.data

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * One album as one file, to hand to family: `<album>.attic`, a zip holding the album as Attic keeps it
 * (scans, crops, backs, stories), what's known about each photo (dates, places, captions, people, faces)
 * and the album's look. Photos kept from the phone go in as copies, so they travel too.
 *
 * Opening the file in another Attic adds the album. Opening a later copy of the same album (passed round
 * the family and back) only adds what's new: photos it doesn't have yet, and dates, places, captions,
 * names and stories where it had none. Nothing already there is overwritten or deleted.
 */
class AlbumFile(private val context: Context, private val library: Library, private val backup: Backup) {
    private val dao get() = library.dao

    /** What opening a file did. */
    data class Added(val title: String, val albumId: Long, val newPhotos: Int, val filledIn: Int, val alreadyHere: Boolean)

    /** Writes album [albumId] to [out]. [progress] gets (done, total) as photos go in. */
    suspend fun write(albumId: Long, out: OutputStream, progress: (Int, Int) -> Unit = { _, _ -> }) = withContext(Dispatchers.IO) {
        val album = dao.album(albumId) ?: error("That album is gone")
        val prints = dao.printsInNow(albumId)
        val phone = dao.phoneInNow(albumId)
        val json = backup.describe(prints, listOf(album), everyone = false)
        json.put("format", FORMAT).put("formatVersion", 1)
        val described = json.getJSONArray("albums").getJSONObject(0).getJSONArray("prints")
        val total = prints.size + phone.size
        ZipOutputStream(out.buffered()).use { zip ->
            // Capture folders, as Attic keeps them; what Attic makes again by itself stays out.
            val dirs = prints.map { it.scanDir }.distinct()
            var done = 0
            for (dir in dirs) {
                val local = File(library.root, dir)
                for (f in local.listFiles().orEmpty()) {
                    if (!f.isFile || !keep(f.name)) continue
                    zip.putNextEntry(ZipEntry("scans/$dir/${f.name}"))
                    f.inputStream().use { it.copyTo(zip) }
                    zip.closeEntry()
                }
                done += prints.count { it.scanDir == dir }
                progress(done, total)
            }
            // Photos from the phone: a copy of each, as a page photo of its own after the scans.
            val lastPage = prints.maxOfOrNull { it.page } ?: 0
            phone.forEachIndexed { i, e ->
                val dir = "phone_${album.createdAt}_${e.id}"
                val bytes = runCatching { context.contentResolver.openInputStream(Uri.parse(e.uri))?.use { it.readBytes() } }.getOrNull()
                if (bytes == null) { progress(++done, total); return@forEachIndexed }
                val (w, h) = uprightSize(bytes, e.width, e.height)
                zip.putNextEntry(ZipEntry("scans/$dir/$PHONE_FILE"))
                zip.write(bytes)
                zip.closeEntry()
                described.put(
                    JSONObject()
                        .put("scanDir", dir).put("fileName", PHONE_FILE)
                        .put("page", lastPage + 1 + i / PHONE_PER_PAGE).put("position", i % PHONE_PER_PAGE)
                        .put("width", w).put("height", h).put("sharpness", 1000.0)
                        .put("quad", "0.0,0.0,$w.0,0.0,$w.0,$h.0,0.0,$h.0").put("enhance", 0)
                        .putOpt("dateFrom", e.taken).putOpt("dateTo", e.taken).putOpt("dateLabel", e.taken?.let { PhotoDate.day(java.time.LocalDate.ofEpochDay(it)).label })
                        .put("createdAt", e.addedAt).put("version", 1)
                        .put("negative", false).put("upscaled", false)
                        .put("people", JSONArray()).put("faces", JSONArray()).put("stories", JSONArray()),
                )
                progress(++done, total)
            }
            zip.putNextEntry(ZipEntry(MANIFEST))
            zip.write(json.toString(1).toByteArray())
            zip.closeEntry()
        }
    }

    /** A file name for album [title]. */
    fun fileName(title: String) = Backup.safeName(title) + ".attic"

    /**
     * Adds the album in [uri] (an .attic file) to Attic, or what's new in it to the album already here.
     * Fails with a plain message if the file isn't an Attic album.
     */
    suspend fun open(uri: Uri, progress: (Int, Int) -> Unit = { _, _ -> }): Added = withContext(Dispatchers.IO) {
        val input = context.contentResolver.openInputStream(uri) ?: error("Couldn't open that file.")
        input.use { open(it, progress) }
    }

    internal suspend fun open(input: InputStream, progress: (Int, Int) -> Unit = { _, _ -> }): Added {
        // Unpacked into a folder of its own first, so a broken file leaves nothing half added.
        val tmp = File(context.cacheDir, "incoming_album").apply { deleteRecursively(); mkdirs() }
        try {
            var json: JSONObject? = null
            var bytes = 0L
            ZipInputStream(input.buffered()).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    val name = entry.name
                    if (entry.isDirectory) continue
                    if (name == MANIFEST) { json = JSONObject(zip.readBytes().decodeToString()); continue }
                    // Only plain names under scans/<folder>/: nothing can be written anywhere else.
                    val parts = name.split('/')
                    if (parts.size != 3 || parts[0] != "scans" || parts.any { it.isEmpty() || it == "." || it == ".." || it.contains('\\') }) continue
                    val dest = File(File(tmp, parts[1]).apply { mkdirs() }, parts[2])
                    dest.outputStream().use { out ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            val n = zip.read(buf)
                            if (n < 0) break
                            bytes += n
                            if (bytes > MAX_BYTES) error("That file is too big to be an Attic album.")
                            out.write(buf, 0, n)
                        }
                    }
                }
            }
            val manifest = json ?: error("That isn't an Attic album.")
            if (manifest.optString("format") != FORMAT) error("That isn't an Attic album.")
            val a = manifest.getJSONArray("albums").getJSONObject(0)
            val title = a.getString("title")
            val existing = dao.albumMatching(title, a.getLong("createdAt"))
            val album = existing ?: AlbumEntity(
                title = title, createdAt = a.getLong("createdAt"),
                dateFrom = a.optLongOrNull("dateFrom"), dateTo = a.optLongOrNull("dateTo"), dateLabel = a.optStringOrNull("dateLabel"), look = a.optStringOrNull("look"),
            ).let { it.copy(id = dao.insertAlbum(it)) }
            val prints = a.getJSONArray("prints")
            var added = 0
            var filled = 0
            for (j in 0 until prints.length()) {
                progress(j, prints.length())
                val p = prints.getJSONObject(j)
                val scanDir = p.getString("scanDir")
                val fileName = p.getString("fileName")
                val src = File(tmp, scanDir)
                val have = dao.printAt(scanDir, fileName)
                val local = File(library.root, scanDir).apply { mkdirs() }
                // New files only: a crop already here may have been fixed here since.
                for (f in src.listFiles().orEmpty()) {
                    val dest = File(local, f.name)
                    if (!dest.exists()) f.copyTo(dest)
                }
                if (have == null) {
                    if (backup.restorePrint(p, album.id, local, backedUp = false)) added++
                } else if (fillIn(have, p, local)) filled++
            }
            backup.restorePeople(manifest)
            // Looks, fingerprints and faces are made here, as for a scan.
            library.renderMissing()
            library.fillMissingHashes()
            library.findMissingFaces()
            progress(prints.length(), prints.length())
            return Added(title, album.id, added, filled, existing != null)
        } finally {
            tmp.deleteRecursively()
        }
    }

    /** What the incoming copy knows about print [have] that this one doesn't. True if anything was added. */
    private fun fillIn(have: PrintEntity, p: JSONObject, local: File): Boolean {
        var changed = false
        var updated = have
        if (have.date == null && p.optLongOrNull("dateFrom") != null && p.optStringOrNull("dateLabel") != null) {
            updated = updated.copy(dateFrom = p.getLong("dateFrom"), dateTo = p.optLongOrNull("dateTo") ?: p.getLong("dateFrom"), dateLabel = p.getString("dateLabel"))
            changed = true
        }
        if (have.place == null && p.optStringOrNull("place") != null) { updated = updated.copy(place = p.getString("place")); changed = true }
        if (have.caption == null && p.optStringOrNull("caption") != null) { updated = updated.copy(caption = p.getString("caption")); changed = true }
        if (have.backFile == null && p.optStringOrNull("backFile")?.let { File(local, it).exists() } == true) { updated = updated.copy(backFile = p.getString("backFile")); changed = true }
        if (changed) dao.updatePrint(updated.copy(version = updated.version + 1))
        // Names: everyone either copy knows of.
        val people = p.optJSONArray("people") ?: JSONArray()
        val names = dao.peopleInNow(have.id)
        val more = List(people.length()) { people.getString(it) }.filter { n -> names.none { it.equals(n, ignoreCase = true) } }
        if (more.isNotEmpty()) { dao.setPeople(have.id, names + more); changed = true }
        // Stories recorded on the other phone.
        val stories = p.optJSONArray("stories") ?: JSONArray()
        val known = dao.storiesOfNow(have.id).map { it.file }.toSet()
        for (k in 0 until stories.length()) {
            val st = stories.getJSONObject(k)
            val file = st.getString("file")
            if (file in known || !File(local, file).exists()) continue
            dao.insertStory(StoryEntity(printId = have.id, file = file, durationMs = st.getLong("durationMs"), createdAt = st.getLong("createdAt")))
            changed = true
        }
        return changed
    }

    /** The size of a phone photo the way it's seen (EXIF turns a sideways-stored photo upright). */
    private fun uprightSize(bytes: ByteArray, w: Int, h: Int): Pair<Int, Int> {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
        val bw = if (o.outWidth > 0) o.outWidth else w
        val bh = if (o.outHeight > 0) o.outHeight else h
        val turn = runCatching { ExifInterface(bytes.inputStream()).rotationDegrees }.getOrDefault(0)
        return if (turn == 90 || turn == 270) bh to bw else bw to bh
    }

    private fun JSONObject.optLongOrNull(key: String): Long? = if (has(key) && !isNull(key)) getLong(key) else null
    private fun JSONObject.optStringOrNull(key: String): String? = if (has(key) && !isNull(key)) getString(key) else null

    companion object {
        const val FORMAT = "attic-album"
        const val MANIFEST = "album.json"
        const val MIME = "application/zip"
        private const val PHONE_FILE = "photo.jpg"
        private const val PHONE_PER_PAGE = 4
        /** Far more than any real album; a guard against a file that unpacks forever. */
        private const val MAX_BYTES = 8L * 1024 * 1024 * 1024

        /** Files of a capture folder worth sending: not what Attic makes again by itself. */
        internal fun keep(name: String) = !Backup.isDerived(name) && !name.endsWith(".tmp.jpg") && !name.startsWith("upscaled")
    }
}
