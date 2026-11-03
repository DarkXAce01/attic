package io.github.darkxace01.attic.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Backs Attic up to a folder the user picks (an SD card, a USB stick, a folder another app syncs), and
 * restores from one. Uses Android's folder picker, so no internet or storage permission is needed.
 *
 * The backup folder, `Attic backup` inside the picked one, holds:
 * - `Photos/<album>/`: every photo as a JPEG with its date, people, place and caption in the EXIF, for
 *   anyone, with or without Attic;
 * - `scans/`: every capture folder as Attic keeps it (untouched originals, crops, glare-free shots);
 * - `attic.json`: albums, pages, dates, people, places and captions, for restoring into Attic.
 *
 * Backing up again only writes what's new or changed. Nothing in the backup is deleted when it's
 * deleted in Attic, so a mistake on the phone can still be undone from the backup; only the `Photos`
 * copies follow Attic exactly (they can always be made again).
 */
class Backup(private val context: Context, private val library: Library) {
    private val prefs = context.getSharedPreferences("backup", Context.MODE_PRIVATE)
    private val dao get() = library.dao

    /** The picked folder, if one was picked and Attic may still write to it. */
    val folder: Uri?
        get() = prefs.getString("folder", null)?.let(Uri::parse)?.takeIf { uri ->
            context.contentResolver.persistedUriPermissions.any { it.uri == uri && it.isWritePermission }
        }

    val folderName: String? get() = folder?.let { DocumentFile.fromTreeUri(context, it)?.name }

    /** When the last backup finished (epoch millis), 0 if never. */
    val lastBackupAt: Long get() = prefs.getLong("lastBackupAt", 0)

    /** Remembers [uri] (from the folder picker) as where backups go. */
    fun useFolder(uri: Uri) {
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        context.contentResolver.persistedUriPermissions.filter { it.uri != uri }.forEach {
            runCatching { context.contentResolver.releasePersistableUriPermission(it.uri, flags) }
        }
        context.contentResolver.takePersistableUriPermission(uri, flags)
        prefs.edit().putString("folder", uri.toString()).apply()
    }

    sealed interface Progress {
        data object Idle : Progress
        data class Working(val what: String, val done: Int, val total: Int) : Progress
        data class Done(val message: String) : Progress
        data class Failed(val message: String) : Progress
    }

    private val _progress = MutableStateFlow<Progress>(Progress.Idle)
    val progress: StateFlow<Progress> = _progress

    /** Writes everything new or changed to the backup folder. */
    suspend fun backUp() = withContext(Dispatchers.IO) {
        val root = folder?.let { DocumentFile.fromTreeUri(context, it) }
        if (root == null) {
            _progress.value = Progress.Failed("Choose a backup folder first.")
            return@withContext
        }
        runCatching {
            val home = root.dir(BACKUP_DIR)
            val prints = dao.allPrints()
            val albums = dao.albums().associateBy { it.id }

            // Capture folders: copy files that are new or changed. Enhanced looks are left out: Attic
            // makes them again from the crops.
            val scans = home.dir("scans")
            val scanDirs = prints.map { it.scanDir }.distinct()
            val existingScans = scans.children()
            scanDirs.forEachIndexed { i, name ->
                _progress.value = Progress.Working("Copying scans", i, scanDirs.size)
                val local = File(library.root, name)
                val files = local.listFiles().orEmpty().filter { it.isFile && !isDerived(it.name) }
                val dir = existingScans[name] ?: scans.createDirectory(name) ?: error("Couldn't make a folder in the backup")
                val there = dir.children()
                for (f in files) {
                    val have = there[f.name]
                    if (have != null && have.length() == f.length()) continue
                    have?.delete()
                    copyIn(f, dir, f.name, mime(f.name))
                }
            }

            // Photos, as anyone would want them: one folder per album.
            val photos = home.dir("Photos")
            val photoDirs = photos.children().toMutableMap()
            val wanted = HashMap<String, MutableSet<String>>()
            val inFolder = HashMap<String, Map<String, DocumentFile>>()
            val tmp = File(context.cacheDir, "backup.jpg")
            prints.forEachIndexed { i, p ->
                _progress.value = Progress.Working("Saving photos", i, prints.size)
                val album = safeName(albums[p.albumId]?.title ?: "Attic")
                val name = "${album} - page ${p.page} - ${p.position}.jpg"
                wanted.getOrPut(album) { mutableSetOf() } += name
                val dir = photoDirs[album] ?: (photos.createDirectory(album) ?: error("Couldn't make a folder in the backup")).also { photoDirs[album] = it }
                // One listing per album folder: asking the folder for each file is slow on big albums.
                val have = inFolder.getOrPut(album) { dir.children() }[name]
                if (have != null && p.backedUp) return@forEachIndexed
                if (!library.writeJpeg(p, tmp)) return@forEachIndexed
                have?.delete()
                copyIn(tmp, dir, name, "image/jpeg")
            }
            tmp.delete()
            // Photos of prints that were deleted, moved or renamed in Attic.
            for ((album, dir) in photoDirs) {
                val keep = wanted[album].orEmpty()
                for (f in dir.listFiles()) if (f.isFile && f.name?.endsWith(".jpg") == true && f.name !in keep) f.delete()
                if (keep.isEmpty() && dir.listFiles().isEmpty()) dir.delete()
            }

            // Last, so a backup cut short never claims more than it holds.
            val json = File(context.cacheDir, "attic.json").apply { writeText(describe(prints, albums.values.toList()).toString(1)) }
            home.findFile("attic.json")?.delete()
            copyIn(json, home, "attic.json", "application/json")
            json.delete()
            prints.forEach { dao.markBackedUp(it.id, it.version) }
            prefs.edit().putLong("lastBackupAt", System.currentTimeMillis()).apply()
            prints.size
        }.onSuccess { n ->
            _progress.value = Progress.Done(if (n == 1) "Backed up 1 photo." else "Backed up all $n photos.")
        }.onFailure { e ->
            _progress.value = Progress.Failed("The backup stopped: ${e.message}")
        }
    }

    /**
     * Brings back albums and photos from a backup in [picked] (the `Attic backup` folder or the one holding
     * it). Photos Attic already has are skipped, so restoring twice does no harm.
     */
    suspend fun restore(picked: Uri) = withContext(Dispatchers.IO) {
        runCatching {
            val top = DocumentFile.fromTreeUri(context, picked) ?: error("Couldn't open that folder")
            val home = if (top.findFile("attic.json") != null) top else top.findFile(BACKUP_DIR)?.takeIf { it.findFile("attic.json") != null }
                ?: error("There's no Attic backup in that folder.")
            val json = JSONObject(context.contentResolver.openInputStream(home.findFile("attic.json")!!.uri)!!.use { it.reader().readText() })
            if (json.optString("format") != FORMAT) error("That isn't an Attic backup.")
            val scans = home.findFile("scans")?.children().orEmpty()
            val albums = json.getJSONArray("albums")
            var restored = 0
            var total = 0
            for (i in 0 until albums.length()) total += albums.getJSONObject(i).getJSONArray("prints").length()
            for (i in 0 until albums.length()) {
                val a = albums.getJSONObject(i)
                val album = dao.albumMatching(a.getString("title"), a.getLong("createdAt"))
                    ?: AlbumEntity(
                        title = a.getString("title"), createdAt = a.getLong("createdAt"),
                        dateFrom = a.optLongOrNull("dateFrom"), dateTo = a.optLongOrNull("dateTo"), dateLabel = a.optStringOrNull("dateLabel"),
                    ).let { it.copy(id = dao.insertAlbum(it)) }
                val prints = a.getJSONArray("prints")
                for (j in 0 until prints.length()) {
                    val p = prints.getJSONObject(j)
                    _progress.value = Progress.Working("Restoring", restored, total)
                    val scanDir = p.getString("scanDir")
                    val fileName = p.getString("fileName")
                    if (dao.printAt(scanDir, fileName) != null) continue
                    val src = scans[scanDir] ?: continue
                    val local = File(library.root, scanDir).apply { mkdirs() }
                    for (f in src.listFiles()) {
                        val name = f.name ?: continue
                        val dest = File(local, name)
                        if (f.isFile && (!dest.exists() || dest.length() != f.length())) {
                            context.contentResolver.openInputStream(f.uri)!!.use { input -> dest.outputStream().use { input.copyTo(it) } }
                        }
                    }
                    if (!File(local, fileName).exists()) continue
                    val version = p.getInt("version")
                    val id = dao.insertPrint(
                        PrintEntity(
                            albumId = album.id, scanDir = scanDir, fileName = fileName,
                            page = p.getInt("page"), position = p.getInt("position"),
                            width = p.getInt("width"), height = p.getInt("height"), sharpness = p.getDouble("sharpness"),
                            quad = p.getString("quad"), enhance = p.getInt("enhance"),
                            dateFrom = p.optLongOrNull("dateFrom"), dateTo = p.optLongOrNull("dateTo"), dateLabel = p.optStringOrNull("dateLabel"),
                            place = p.optStringOrNull("place"), caption = p.optStringOrNull("caption"),
                            createdAt = p.getLong("createdAt"), version = version, backedUpVersion = version,
                        ),
                    )
                    val people = p.optJSONArray("people") ?: JSONArray()
                    dao.setPeople(id, List(people.length()) { people.getString(it) })
                    restored++
                }
            }
            library.renderMissing()
            library.fillMissingHashes()
            restored
        }.onSuccess { n ->
            _progress.value = Progress.Done(
                when (n) {
                    0 -> "Everything in that backup is already here."
                    1 -> "Restored 1 photo."
                    else -> "Restored $n photos."
                },
            )
        }.onFailure { e ->
            _progress.value = Progress.Failed(e.message ?: "Couldn't restore from that folder.")
        }
    }

    fun clearProgress() { _progress.value = Progress.Idle }

    /** Everything needed to rebuild Attic's library, apart from the files. */
    private fun describe(prints: List<PrintEntity>, albums: List<AlbumEntity>): JSONObject {
        val byAlbum = prints.groupBy { it.albumId }
        val arr = JSONArray()
        for (a in albums.sortedBy { it.createdAt }) {
            val ps = JSONArray()
            for (p in byAlbum[a.id].orEmpty().sortedWith(compareBy({ it.page }, { it.position }))) {
                ps.put(
                    JSONObject()
                        .put("scanDir", p.scanDir).put("fileName", p.fileName)
                        .put("page", p.page).put("position", p.position)
                        .put("width", p.width).put("height", p.height).put("sharpness", p.sharpness)
                        .put("quad", p.quad).put("enhance", p.enhance)
                        .putOpt("dateFrom", p.dateFrom).putOpt("dateTo", p.dateTo).putOpt("dateLabel", p.dateLabel)
                        .putOpt("place", p.place).putOpt("caption", p.caption)
                        .put("createdAt", p.createdAt).put("version", p.version)
                        .put("people", JSONArray(dao.peopleInNow(p.id))),
                )
            }
            arr.put(
                JSONObject()
                    .put("title", a.title).put("createdAt", a.createdAt)
                    .putOpt("dateFrom", a.dateFrom).putOpt("dateTo", a.dateTo).putOpt("dateLabel", a.dateLabel)
                    .put("prints", ps),
            )
        }
        return JSONObject().put("format", FORMAT).put("formatVersion", 1).put("savedAt", System.currentTimeMillis()).put("albums", arr)
    }

    private fun DocumentFile.dir(name: String): DocumentFile =
        findFile(name)?.takeIf { it.isDirectory } ?: createDirectory(name) ?: error("Couldn't make a folder in the backup")

    private fun DocumentFile.children(): Map<String, DocumentFile> = listFiles().mapNotNull { f -> f.name?.let { it to f } }.toMap()

    private fun copyIn(file: File, dir: DocumentFile, name: String, mime: String) {
        // Some providers add an extension to the name from the type; "application/octet-stream" keeps it as is.
        val doc = dir.createFile(mime, name) ?: error("Couldn't write $name")
        context.contentResolver.openOutputStream(doc.uri, "wt")!!.use { out -> file.inputStream().use { it.copyTo(out) } }
    }

    private fun mime(name: String) = when {
        name.endsWith(".jpg") -> "image/jpeg"
        name.endsWith(".png") -> "image/png"
        else -> "application/octet-stream"
    }

    private fun JSONObject.optLongOrNull(key: String): Long? = if (has(key) && !isNull(key)) getLong(key) else null
    private fun JSONObject.optStringOrNull(key: String): String? = if (has(key) && !isNull(key)) getString(key) else null

    companion object {
        const val BACKUP_DIR = "Attic backup"
        private const val FORMAT = "attic-backup"

        /** Enhanced looks and old colour-fix files: made again from the crop, so not backed up. */
        fun isDerived(name: String) = Regex("""^print_\d+_(e\d|fix)\.png$""").matches(name)

        /** A folder or file name that works on any file system (SD cards are often FAT). */
        fun safeName(s: String) = s.replace(Regex("""[\\/:*?"<>|\u0000-\u001f]"""), " ").replace(Regex("\\s+"), " ").trim().trimEnd('.').ifEmpty { "Attic" }
    }
}
