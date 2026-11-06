package io.github.darkxace01.attic.data

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.MediaStore
import androidx.exifinterface.media.ExifInterface
import io.github.darkxace01.attic.vision.Deglare
import io.github.darkxace01.attic.vision.Enhance
import io.github.darkxace01.attic.vision.Faces
import io.github.darkxace01.attic.vision.Dust
import io.github.darkxace01.attic.vision.Curl
import io.github.darkxace01.attic.vision.Negative
import io.github.darkxace01.attic.vision.Border
import io.github.darkxace01.attic.vision.FaceGroups
import io.github.darkxace01.attic.vision.DateStamp
import io.github.darkxace01.attic.vision.PrintHash
import io.github.darkxace01.attic.vision.FrameTransform
import io.github.darkxace01.attic.vision.Quad
import io.github.darkxace01.attic.vision.Rectify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.opencv.android.Utils
import org.opencv.core.Mat
import org.opencv.core.MatOfInt
import org.opencv.imgcodecs.Imgcodecs
import org.opencv.imgproc.Imgproc
import java.io.File
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Everything Attic keeps: albums and prints in the database, the files under files/scans.
 *
 * Each capture folder holds `original.jpg` (the untouched camera photo, never modified) and one PNG per
 * print. Edits are stored as data (crop corners, enhancement) and the PNGs are rebuilt from the original,
 * so nothing is ever lost by editing. The enhanced look sits next to the plain crop as `print_N_e1.png`
 * (natural) or `print_N_e2.png` (strong).
 *
 * A glare-free capture also keeps the other shots, `shot_2.jpg` and on (with `frame_2.json` and on), and
 * for each print `sweep_print_N.json`: where that print is in each of them. Fixing the crop redoes the
 * merge from all of them, so the glare stays gone.
 */
class Library(private val context: Context) {
    val db = AtticDatabase.open(context)
    internal val dao = db.dao()
    val settings = Settings(context)
    val root = File(context.filesDir, "scans").apply { mkdirs() }

    /** The date-stamp reader: small, loaded the first time a print is read. */
    private val dateStamp by lazy { DateStamp(context.assets.open("datestamp.onnx").use { it.readBytes() }) }
    private val prefs = context.getSharedPreferences("library", Context.MODE_PRIVATE)

    // ---- reading ----

    fun albums(): Flow<List<AlbumSummary>> = dao.albumSummaries()
    fun album(id: Long): Flow<AlbumEntity?> = dao.albumFlow(id)
    fun printsIn(albumId: Long): Flow<List<PrintEntity>> = dao.printsIn(albumId)
    fun print(id: Long): Flow<PrintEntity?> = dao.printFlow(id)
    suspend fun printNow(id: Long): PrintEntity? = io { dao.print(id) }
    fun peopleIn(printId: Long): Flow<List<String>> = dao.peopleIn(printId)
    fun allPeople(): Flow<List<String>> = dao.allPeople()
    fun counts(): Flow<LibraryCounts> = dao.counts()

    /** The whole library on one timeline: see [Timeline]. */
    fun timeline(): Flow<List<Timeline.Row>> =
        kotlinx.coroutines.flow.combine(dao.allPrintsFlow(), dao.albumsFlow(), phonePhotos()) { prints, albums, phone ->
            Timeline.rows(prints, albums.associate { it.id to it.date }, phone)
        }.flowOn(Dispatchers.Default)

    /** The phone's own photos, when they've been asked for (see [PhonePhotos]). */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    fun phonePhotos(): Flow<List<PhonePhoto>> =
        settings.prefs.map { it.phonePhotos }.distinctUntilChanged().flatMapLatest { on ->
            if (on) PhonePhotos.watch(context) else kotlinx.coroutines.flow.flowOf(emptyList())
        }

    /** What to bring back today: see [io.github.darkxace01.attic.timeline.OnThisDay]. */
    fun onThisDay(today: java.time.LocalDate): Flow<io.github.darkxace01.attic.timeline.OnThisDay.Memory?> =
        kotlinx.coroutines.flow.combine(dao.allPrintsFlow(), dao.albumsFlow(), phonePhotos()) { prints, albums, phone ->
            io.github.darkxace01.attic.timeline.OnThisDay.pick(prints, albums.associate { it.id to it.date }, today, phone)
        }.flowOn(Dispatchers.Default)

    /** The file to show and export: the enhanced version when there is one. */
    fun displayFile(p: PrintEntity): File = enhancedFile(p, p.enhance).takeIf { it.exists() } ?: p.file(root)

    /** The plain crop, for comparing against the enhanced one. */
    fun originalFile(p: PrintEntity): File = p.file(root)

    /** The flattened back of [p], if it has one. */
    fun backFile(p: PrintEntity): File? = p.backFile?.let { File(File(root, p.scanDir), it) }?.takeIf { it.exists() }

    /**
     * Keeps [flat] as the back of print [id], with the camera photo it came from next to it. Replaces an
     * earlier back.
     */
    suspend fun saveBack(id: Long, flat: Mat, photoJpeg: ByteArray) = io {
        val p = dao.print(id) ?: return@io
        val n = p.fileName.removePrefix("print_").removeSuffix(".png")
        val dir = File(root, p.scanDir)
        writePng(flat, File(dir, "back_$n.png"))
        File(dir, "back_${n}.jpg").writeBytes(photoJpeg)
        dao.updatePrint(p.copy(backFile = "back_$n.png", version = p.version + 1))
    }

    suspend fun removeBack(id: Long) = io {
        val p = dao.print(id) ?: return@io
        backFiles(p).forEach { it.delete() }
        dao.updatePrint(p.copy(backFile = null, version = p.version + 1))
    }

    private fun backFiles(p: PrintEntity): List<File> {
        val n = p.fileName.removePrefix("print_").removeSuffix(".png")
        val dir = File(root, p.scanDir)
        return listOf(File(dir, "back_$n.png"), File(dir, "back_$n.jpg"))
    }

    /** The album's cover: its first print, the way it's shown everywhere else. */
    /** A page's photo, small, for drawing the page behind its prints: the file and the full photo's size. */
    data class Backdrop(val file: File, val fullWidth: Int, val fullHeight: Int)

    /**
     * The upright photo a page was scanned from, at most [PAGE_SIDE] pixels long, made once and kept next to
     * it (backups leave it out: it's made again from the original).
     */
    suspend fun pageBackdrop(scanDir: String): Backdrop? = io {
        val dir = File(root, scanDir)
        val small = File(dir, "page_small.jpg")
        val meta = File(dir, "page_small.json")
        if (small.exists() && meta.exists()) {
            val j = runCatching { JSONObject(meta.readText()) }.getOrNull()
            if (j != null) return@io Backdrop(small, j.getInt("width"), j.getInt("height"))
        }
        val full = loadUpright(dir, "original.jpg", "frame.json") ?: return@io null
        val w = full.cols(); val h = full.rows()
        val scale = PAGE_SIDE.toDouble() / maxOf(w, h)
        val m = Mat()
        org.opencv.imgproc.Imgproc.resize(full, m, org.opencv.core.Size(w * scale, h * scale), 0.0, 0.0, org.opencv.imgproc.Imgproc.INTER_AREA)
        full.release()
        val bmp = android.graphics.Bitmap.createBitmap(m.cols(), m.rows(), android.graphics.Bitmap.Config.ARGB_8888)
        Utils.matToBitmap(m, bmp)
        m.release()
        small.outputStream().use { bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 88, it) }
        bmp.recycle()
        meta.writeText(JSONObject().put("width", w).put("height", h).toString())
        Backdrop(small, w, h)
    }

    /** The first few photos of an album as shown (enhanced or not), for its island: cover first. */
    suspend fun islandFiles(albumId: Long, n: Int = 4): List<File> = io {
        dao.printsInNow(albumId).take(n).map(::displayFile).filter { it.exists() }
    }

    fun coverFile(a: AlbumSummary): File? {
        val dir = a.coverDir ?: return null
        val name = a.coverFile ?: return null
        return enhancedFile(dir, name, a.coverEnhance ?: Enhance.OFF).takeIf { it.exists() } ?: File(File(root, dir), name)
    }

    private fun enhancedFile(p: PrintEntity, level: Int): File = enhancedFile(p.scanDir, p.fileName, level)

    private fun enhancedFile(dir: String, fileName: String, level: Int): File =
        if (level == Enhance.OFF) File(File(root, dir), fileName)
        else File(File(root, dir), fileName.removeSuffix(".png") + "_e$level.png")

    /** Every derived file a print can have, including the colour-fix files of earlier builds. */
    private fun derivedFiles(p: PrintEntity): List<File> {
        val dir = File(root, p.scanDir)
        val stem = p.fileName.removeSuffix(".png")
        return listOf("_fix", "_e1", "_e2").map { File(dir, "$stem$it.png") }
    }

    // ---- albums ----

    /** The album new scans go into. Created on first use. */
    suspend fun scanningAlbum(): AlbumEntity = io {
        dao.album(prefs.getLong("scanningAlbum", -1))
            ?: dao.albums().firstOrNull()?.also { setScanningAlbumNow(it.id) }
            ?: newAlbumNow("My first album")
    }

    suspend fun setScanningAlbum(id: Long) = io { setScanningAlbumNow(id) }

    suspend fun newAlbum(title: String): AlbumEntity = io { newAlbumNow(title) }

    private fun newAlbumNow(title: String): AlbumEntity {
        val a = AlbumEntity(title = title.trim().ifEmpty { "Untitled album" }, createdAt = System.currentTimeMillis())
        val id = dao.insertAlbum(a)
        setScanningAlbumNow(id)
        return a.copy(id = id)
    }

    private fun setScanningAlbumNow(id: Long) = prefs.edit().putLong("scanningAlbum", id).apply()

    suspend fun renameAlbum(id: Long, title: String) = io {
        dao.album(id)?.let { dao.updateAlbum(it.copy(title = title.trim().ifEmpty { it.title })) }
    }

    /** Sets the album's date. Prints without their own date use it, so their exports change too. */
    suspend fun setAlbumDate(id: Long, date: PhotoDate?) = io {
        val a = dao.album(id) ?: return@io
        dao.updateAlbum(a.copy(dateFrom = date?.from?.toEpochDay(), dateTo = date?.to?.toEpochDay(), dateLabel = date?.label))
        dao.printsInNow(id).filter { it.date == null }.forEach { dao.updatePrint(it.copy(version = it.version + 1)) }
    }

    suspend fun deleteAlbum(id: Long) = io {
        dao.printsInNow(id).forEach { deletePrintNow(it) }
        dao.deleteAlbum(id)
        if (prefs.getLong("scanningAlbum", -1) == id) prefs.edit().remove("scanningAlbum").apply()
    }

    // ---- prints ----

    /**
     * One print of a capture. [others] is where it is in each of the capture's other shots (glare-free
     * captures), null for a shot it isn't in.
     */
    class NewPrint(val image: Mat, val quad: Quad, val sharpness: Double, val others: List<Quad?> = emptyList())

    class Shot(val jpeg: ByteArray, val frame: FrameTransform)

    /**
     * Saves one capture (one album page): the original photo plus a PNG per print, and for a glare-free
     * capture the [otherShots] too.
     */
    suspend fun saveCapture(
        originalJpeg: ByteArray,
        frame: FrameTransform,
        prints: List<NewPrint>,
        otherShots: List<Shot> = emptyList(),
    ): List<PrintEntity> = io {
        val album = dao.album(prefs.getLong("scanningAlbum", -1)) ?: dao.albums().firstOrNull() ?: newAlbumNow("My first album")
        val now = System.currentTimeMillis()
        val dirName = now.toString()
        val dir = File(root, dirName).apply { mkdirs() }
        File(dir, "original.jpg").writeBytes(originalJpeg)
        File(dir, "frame.json").writeText(frame.toJson())
        otherShots.forEachIndexed { k, shot ->
            File(dir, "shot_${k + 2}.jpg").writeBytes(shot.jpeg)
            File(dir, "frame_${k + 2}.json").writeText(shot.frame.toJson())
        }
        val page = dao.lastPage(album.id) + 1
        prints.mapIndexed { i, p ->
            val name = "print_${i + 1}.png"
            writePng(p.image, File(dir, name))
            if (p.others.any { it != null }) writeSweep(File(dir, "sweep_print_${i + 1}.json"), p.others)
            val e = PrintEntity(
                albumId = album.id, scanDir = dirName, fileName = name, page = page, position = i + 1,
                width = p.image.cols(), height = p.image.rows(), sharpness = p.sharpness,
                quad = p.quad.encode(), createdAt = now, enhance = settings.now.newScanLook,
            )
            renderEnhanced(e, p.image)
            val withHash = withStamp(e.copy(hashes = PrintHash.allTurns(p.image).joinToString(",")), p.image)
            withHash.copy(id = dao.insertPrint(withHash))
        }
    }

    suspend fun setDate(id: Long, date: PhotoDate?) = edit(id) {
        it.copy(dateFrom = date?.from?.toEpochDay(), dateTo = date?.to?.toEpochDay(), dateLabel = date?.label)
    }

    suspend fun setPlace(id: Long, place: String?) = edit(id) { it.copy(place = place?.trim()?.ifEmpty { null }) }
    suspend fun setCaption(id: Long, caption: String?) = edit(id) { it.copy(caption = caption?.trim()?.ifEmpty { null }) }

    suspend fun setPeople(id: Long, names: List<String>) = io {
        dao.setPeople(id, names)
        dao.print(id)?.let { dao.updatePrint(it.copy(version = it.version + 1)) }
    }

    // ---- pages ----

    /** What every print on a page has in common: the details the page as a whole was given. */
    data class PageDetails(val count: Int, val date: PhotoDate?, val people: List<String>, val place: String?) {
        val isEmpty: Boolean get() = date == null && people.isEmpty() && place == null
    }

    suspend fun pageDetails(albumId: Long, page: Int): PageDetails? = io { pageDetailsNow(albumId, page) }

    private fun pageDetailsNow(albumId: Long, page: Int): PageDetails? {
        val ps = dao.printsInNow(albumId).filter { it.page == page }
        if (ps.isEmpty()) return null
        val people = ps.map { dao.peopleInNow(it.id) }
        return PageDetails(
            count = ps.size,
            date = ps.map { it.date }.distinct().singleOrNull(),
            people = people.first().filter { name -> people.all { name in it } },
            place = ps.map { it.place }.distinct().singleOrNull(),
        )
    }

    /**
     * Gives a whole page its details. Only what changed from [before] (what the page had in common) is
     * written, so a date or a name given to one photo on its own is kept: a name added here goes on every
     * photo of the page, a name removed here comes off every photo.
     */
    suspend fun setPageDetails(albumId: Long, page: Int, before: PageDetails, date: PhotoDate?, people: List<String>, place: String?) = io {
        val cleanPlace = place?.trim()?.ifEmpty { null }
        for (p in dao.printsInNow(albumId).filter { it.page == page }) {
            var u = p
            if (date != before.date) u = u.copy(dateFrom = date?.from?.toEpochDay(), dateTo = date?.to?.toEpochDay(), dateLabel = date?.label)
            if (cleanPlace != before.place) u = u.copy(place = cleanPlace)
            val had = dao.peopleInNow(p.id)
            val now = pagePeople(had, before.people, people)
            val peopleChanged = now.toSet() != had.toSet()
            if (peopleChanged) dao.setPeople(p.id, now)
            if (u != p || peopleChanged) {
                u = u.copy(version = u.version + 1)
                dao.updatePrint(u)
                exportNow(u)
            }
        }
    }

    fun recentPlaces(): Flow<List<String>> = dao.recentPlaces()

    companion object {
        /**
         * One photo's people after its page's names went from [pageBefore] to [pageNow]: names removed from
         * the page come off, names added go on, and names only this photo had stay.
         */
        fun pagePeople(had: List<String>, pageBefore: List<String>, pageNow: List<String>): List<String> {
            val removed = pageBefore.filter { b -> pageNow.none { it.equals(b, ignoreCase = true) } }
            val kept = had.filter { h -> removed.none { it.equals(h, ignoreCase = true) } }
            return kept + pageNow.filter { n -> kept.none { it.equals(n, ignoreCase = true) } }
        }
    }

    suspend fun setEnhance(id: Long, level: Int) = io {
        val p = dao.print(id) ?: return@io
        if (p.enhance == level) return@io
        val updated = p.copy(enhance = level, version = p.version + 1)
        if (!enhancedFile(updated, level).exists()) renderEnhanced(updated, null)
        dao.updatePrint(updated)
    }

    /** Writes the enhanced file for [p]'s level, from [flat] when the caller already has the crop loaded. */
    private fun renderEnhanced(p: PrintEntity, flat: Mat?) {
        if (p.enhance == Enhance.OFF) return
        val src = flat ?: readPng(p.file(root)) ?: return
        val out = if (settings.now.cleanDust) {
            // Faces stay exactly as they are: the light in someone's eye is a speck too.
            val faceBoxes = synchronized(faceLock) { runCatching { faces.find(src, Faces.RGBA) }.getOrElse { emptyList() } }
                .map { f ->
                    val gw = (f.right - f.left) * 0.25f; val gh = (f.bottom - f.top) * 0.25f
                    floatArrayOf(f.left - gw, f.top - gh, f.right + gw, f.bottom + gh)
                }
            val clean = Dust.clean(src, faceBoxes)
            Enhance.apply(clean.image, p.enhance).also { clean.image.release() }
        } else {
            Enhance.apply(src, p.enhance)
        }
        writePng(out, enhancedFile(p, p.enhance))
        out.release()
        if (flat == null) src.release()
    }

    /**
     * Redoes every print's look, after dust cleaning was switched on or off. Each one changes, so its
     * gallery copy and backup are redone too.
     */
    suspend fun rerenderAll() = io {
        for (p in dao.allPrints()) {
            if (p.enhance == Enhance.OFF) continue
            renderEnhanced(p, null)
            dao.updatePrint(p.copy(version = p.version + 1))
            if (settings.now.saveToGallery || p.galleryUri != null) exportNow(dao.print(p.id) ?: continue)
        }
    }

    /** Once, after an update that changes how looks are made (dust cleaning): redoes the prints from before. */
    suspend fun upgradeLooks() {
        if (prefs.getInt("looksVersion", 1) >= LOOKS_VERSION) return
        rerenderAll()
        prefs.edit().putInt("looksVersion", LOOKS_VERSION).apply()
    }

    // ---- prints scanned twice ----

    /**
     * Other prints that show the same picture as [p], in any orientation: most likely the same print
     * scanned twice. Prints from the same capture never count (two copies on one page are deliberate).
     */
    suspend fun twinsOf(p: PrintEntity): List<PrintEntity> = io { twinsOfNow(p, dao.printsWithHashes()) }

    private fun twinsOfNow(p: PrintEntity, all: List<PrintEntity>): List<PrintEntity> {
        val mine = p.hashList
        if (mine.isEmpty()) return emptyList()
        return all.filter { o ->
            o.id != p.id && o.scanDir != p.scanDir && o.hashList.firstOrNull()?.let { h -> mine.any { PrintHash.same(it, h) } } == true
        }
    }

    /** Where [p] is, for telling people: "page 3 of Nani's album". */
    suspend fun whereIs(p: PrintEntity): String = io { "page ${p.page} of ${dao.album(p.albumId)?.title ?: "an album"}" }

    /** Of [saved], how many have a twin elsewhere in the library. */
    suspend fun countWithTwins(saved: List<PrintEntity>): Int = io {
        val all = dao.printsWithHashes()
        saved.count { twinsOfNow(it, all).isNotEmpty() }
    }

    /** Fingerprints for prints saved before they existed. */
    /** Reads the camera's date stamp, for prints saved before Attic read them. */
    suspend fun readMissingStamps() = io {
        for (p in dao.printsWithoutStamp()) {
            val img = readPng(p.file(root)) ?: continue
            val read = withStamp(p, img)
            img.release()
            dao.updatePrint(if (read.date != p.date) read.copy(version = p.version + 1) else read)
        }
    }

    /**
     * [p] with its date stamp read from [image], and the date filled in from it when the print has none
     * and the stamp can only mean one day.
     */
    private fun withStamp(p: PrintEntity, image: Mat): PrintEntity {
        val reading = runCatching { dateStamp.read(image) }.getOrNull()
        val read = p.copy(stamp = reading?.text ?: "")
        val day = reading?.dates?.singleOrNull()
        return if (day != null && p.dateFrom == null) {
            val d = PhotoDate.day(day)
            read.copy(dateFrom = d.from.toEpochDay(), dateTo = d.to.toEpochDay(), dateLabel = d.label)
        } else {
            read
        }
    }

    suspend fun fillMissingHashes() = io {
        for (p in dao.printsWithoutHashes()) {
            val img = readPng(p.file(root)) ?: continue
            dao.setHashes(p.id, PrintHash.allTurns(img).joinToString(","))
            img.release()
        }
    }

    /** Fills in enhanced files that are missing (prints from before enhancement, or an interrupted save). */
    suspend fun renderMissing() = io {
        dao.allPrints().filter { it.enhance != Enhance.OFF && !enhancedFile(it, it.enhance).exists() && it.file(root).exists() }
            .forEach { renderEnhanced(it, null) }
    }

    /**
     * Remakes the crop from the untouched original with new corners (upright-original pixels). For a
     * glare-free print the other shots are cut the same way and merged again.
     */
    suspend fun recrop(id: Long, quad: Quad) = recrop(id, quad, border = null)

    /** [recrop], with [border] as the outline to put a trimmed border back to (null: not trimmed). */
    private suspend fun recrop(id: Long, quad: Quad, border: String?) = io {
        val p = dao.print(id) ?: return@io
        val original = loadUprightOriginal(p) ?: return@io
        val sweepFile = sweepFile(p)
        // A glare-free print is merged from shots cut the same straight way, so it isn't uncurled.
        var flat = (if (settings.now.uncurl && !sweepFile.exists()) runCatching { Curl.flatten(original, quad) }.getOrNull() else null)
            ?: Rectify.warp(original, quad)
        val others = if (sweepFile.exists()) readSweep(sweepFile) else emptyList()
        if (others.any { it != null }) {
            // The print is flat, so where its old corners went in each shot tells where the new ones go.
            val old = p.cropQuad
            val moved = others.map { q -> q?.let { Deglare.mapQuad(quad, old, it) } }
            val size = flat.cols() to flat.rows()
            val flats = moved.mapIndexedNotNull { k, q ->
                q ?: return@mapIndexedNotNull null
                val shot = loadUpright(File(root, p.scanDir), "shot_${k + 2}.jpg", "frame_${k + 2}.json") ?: return@mapIndexedNotNull null
                Rectify.warp(shot, q, size = size).also { shot.release() }
            }
            if (flats.isNotEmpty()) {
                val merged = Deglare.merge(flat, flats)
                flat.release()
                flat = merged
            }
            flats.forEach { it.release() }
            writeSweep(sweepFile, moved)
        }
        if (p.negative) {
            val developed = Negative.develop(flat)
            flat.release()
            flat = developed
        }
        writePng(flat, p.file(root))
        derivedFiles(p).forEach { it.delete() }
        val updated = p.copy(
            quad = quad.encode(), width = flat.cols(), height = flat.rows(),
            sharpness = Rectify.sharpness(flat), version = p.version + 1,
        )
        renderEnhanced(updated, flat)
        // A better crop can show a stamp the old one cut off.
        val rehashed = withStamp(updated.copy(hashes = PrintHash.allTurns(flat).joinToString(",")), flat)
        flat.release()
        original.release()
        dao.updatePrint(rehashed.copy(borderQuad = border))
    }

    /** Turns the print a quarter turn clockwise by rotating its corners, so it's remade from the original. */
    suspend fun rotate(id: Long) {
        val p = io { dao.print(id) } ?: return
        val q = p.cropQuad
        // A trimmed border turns with the print, so it can still be put back.
        val border = p.borderQuad?.let(::parseQuad)?.let { b -> Quad(b.bl, b.tl, b.tr, b.br).encode() }
        recrop(id, Quad(q.bl, q.tl, q.tr, q.br), border)
    }

    /** Marks freshly saved prints as taken from negatives (their crops are already developed). */
    suspend fun markNegative(ids: List<Long>) = io { if (ids.isNotEmpty()) dao.markNegative(ids) }

    // ---- family ----

    fun relations(): Flow<List<RelationEntity>> = dao.relations()

    /**
     * Says how [person] and the person called [name] (made if new) are related: [kind] is from [person]'s
     * side ("parent" makes the other their parent, "child" their child). Saying it twice does nothing.
     */
    suspend fun relate(person: Long, name: String, kind: String) = io {
        val clean = name.trim()
        if (clean.isEmpty()) return@io
        val other = dao.personNamed(clean)?.id ?: dao.insertPerson(PersonEntity(name = clean))
        if (other == person) return@io
        val r = when (kind) {
            "parent" -> RelationEntity(a = other, b = person, kind = RelationEntity.PARENT)
            "child" -> RelationEntity(a = person, b = other, kind = RelationEntity.PARENT)
            RelationEntity.SPOUSE -> RelationEntity(a = person, b = other, kind = RelationEntity.SPOUSE)
            else -> RelationEntity(a = person, b = other, kind = RelationEntity.SIBLING)
        }
        val known = dao.relationsNow().any { it.kind == r.kind && ((it.a == r.a && it.b == r.b) || (r.kind != RelationEntity.PARENT && it.a == r.b && it.b == r.a)) }
        if (!known) dao.insertRelation(r)
    }

    suspend fun unrelate(relationId: Long) = io { dao.deleteRelation(relationId) }

    // ---- then and now ----

    /** Today's photo of the place [p] was taken, lined up over it; it may not exist. */
    fun nowFile(p: PrintEntity): File = File(File(root, p.scanDir), p.fileName.substringBeforeLast('.') + "_now.jpg")

    /**
     * Keeps [jpeg] (from the camera, [frame] its turn) as today's photo of print [id], lined up over it.
     * [guess] is where the old print sat over the camera view, as fractions of it (left, top, right,
     * bottom). True if it lined up by what's in it, false if the framing was kept as held.
     */
    suspend fun saveNow(id: Long, jpeg: ByteArray, frame: io.github.darkxace01.attic.vision.FrameTransform, guess: FloatArray): Boolean? = withContext(Dispatchers.Default) {
        val p = dao.print(id) ?: return@withContext null
        val old = readPng(displayFile(p)) ?: return@withContext null
        val bmp = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size) ?: run { old.release(); return@withContext null }
        val decoded = Mat().also { Utils.bitmapToMat(bmp, it) }
        bmp.recycle()
        val now = frame.apply(decoded).also { if (it !== decoded) decoded.release() }
        val w = now.cols(); val h = now.rows()
        val rect = org.opencv.core.Rect((guess[0] * w).toInt(), (guess[1] * h).toInt(), ((guess[2] - guess[0]) * w).toInt().coerceAtLeast(1), ((guess[3] - guess[1]) * h).toInt().coerceAtLeast(1))
        val r = io.github.darkxace01.attic.vision.Align.toOld(old, now, rect)
        val bgr = Mat().also { Imgproc.cvtColor(r.image, it, Imgproc.COLOR_RGBA2BGR) }
        val out = nowFile(p)
        val tmp = File(out.parentFile, out.name + ".tmp.jpg")
        Imgcodecs.imwrite(tmp.path, bgr, MatOfInt(Imgcodecs.IMWRITE_JPEG_QUALITY, 92))
        tmp.renameTo(out)
        listOf(old, now, r.image, bgr).forEach { it.release() }
        r.matched
    }

    suspend fun deleteNow(id: Long) = io { dao.print(id)?.let { nowFile(it).delete() } }

    // ---- importing ----

    /** An image brought in from a file, ready to be treated like a camera shot. */
    class Imported(val jpeg: ByteArray, val frame: io.github.darkxace01.attic.vision.FrameTransform)

    /**
     * Reads an image file (a flatbed scan, an old phone photo) as a JPEG to keep as the capture's original,
     * with its EXIF turn. Very large scans are halved until they're under [IMPORT_PIXELS]. Null if it
     * isn't an image Android can open.
     */
    suspend fun readImport(uri: Uri): Imported? = io {
        val resolver = context.contentResolver
        val bytes = resolver.openInputStream(uri)?.use { it.readBytes() } ?: return@io null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0) return@io null
        val turn = runCatching { ExifInterface(bytes.inputStream()).rotationDegrees }.getOrDefault(0)
        var sample = 1
        while (bounds.outWidth.toLong() * bounds.outHeight / (sample * sample) > IMPORT_PIXELS) sample *= 2
        val isJpeg = bounds.outMimeType == "image/jpeg"
        val jpeg = if (isJpeg && sample == 1) bytes else {
            val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return@io null
            java.io.ByteArrayOutputStream().also { bmp.compress(Bitmap.CompressFormat.JPEG, 97, it); bmp.recycle() }.toByteArray()
        }
        val w = (bounds.outWidth + sample - 1) / sample; val h = (bounds.outHeight + sample - 1) / sample
        // A re-encoded JPEG has no EXIF, so the turn is applied from the frame instead.
        val size = BitmapFactory.Options().apply { inJustDecodeBounds = true }.also { BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, it) }
        Imported(jpeg, io.github.darkxace01.attic.vision.FrameTransform(size.outWidth, size.outHeight, 0, 0, size.outWidth, size.outHeight, turn))
            .takeIf { w > 0 && h > 0 }
    }

    // ---- borders ----

    /** The plain border round [p], if it has one (null once it's been trimmed). */
    suspend fun borderOf(p: PrintEntity): Border.Inset? = io {
        if (p.borderQuad != null) return@io null
        val img = readPng(p.file(root)) ?: return@io null
        runCatching { Border.find(img) }.getOrNull().also { img.release() }
    }

    /** Cuts the print down to the picture inside its border. Returns false if it has no border. */
    suspend fun trimBorder(id: Long, unlessWritten: Boolean = false): Boolean {
        val p = printNow(id) ?: return false
        val inset = borderOf(p) ?: return false
        if (unlessWritten && inset.writing) return false
        recrop(id, Border.inner(p.cropQuad, inset), border = p.quad)
        return true
    }

    /** Puts a trimmed border back. */
    suspend fun restoreBorder(id: Long) {
        val p = printNow(id) ?: return
        val outer = p.borderQuad?.let(::parseQuad) ?: return
        recrop(id, outer, border = null)
    }

    /** The original photo, turned upright, as an RGBA Mat. The caller releases it. */
    suspend fun uprightOriginal(p: PrintEntity): Mat? = io { loadUprightOriginal(p) }

    private fun loadUprightOriginal(p: PrintEntity): Mat? = loadUpright(File(root, p.scanDir), "original.jpg", "frame.json")

    /** A photo of a capture folder, turned upright with its frame file. */
    private fun loadUpright(dir: File, jpeg: String, frameJson: String): Mat? {
        val file = File(dir, jpeg)
        val bmp = BitmapFactory.decodeFile(file.path) ?: return null
        val m = Mat()
        Utils.bitmapToMat(bmp, m)
        bmp.recycle()
        val frame = runCatching {
            val j = JSONObject(File(dir, frameJson).readText())
            FrameTransform(
                j.getInt("bufferWidth"), j.getInt("bufferHeight"), j.getInt("cropLeft"), j.getInt("cropTop"),
                j.getInt("cropWidth"), j.getInt("cropHeight"), j.getInt("rotation"),
            )
        }.getOrElse {
            // Scans from the first build only recorded the rotation.
            val rotation = runCatching { JSONObject(File(dir, "meta.json").readText()).getInt("rotationDegrees") }.getOrDefault(0)
            FrameTransform(m.cols(), m.rows(), 0, 0, m.cols(), m.rows(), rotation)
        }
        val upright = frame.apply(m)
        if (upright !== m) m.release()
        return upright
    }

    suspend fun delete(id: Long) = io { dao.print(id)?.let(::deletePrintNow) }

    private fun deletePrintNow(p: PrintEntity) {
        p.galleryUri?.let { runCatching { context.contentResolver.delete(Uri.parse(it), null, null) } }
        p.file(root).delete()
        derivedFiles(p).forEach { it.delete() }
        sweepFile(p).delete()
        backFiles(p).forEach { it.delete() }
        nowFile(p).delete()
        dao.storiesOfNow(p.id).forEach { storyFile(p, it).delete(); dao.deleteStory(it.id) }
        dao.untagAll(p.id)
        dao.deleteFacesOf(p.id)
        dao.deletePrint(p.id)
        val dir = File(root, p.scanDir)
        if (dir.listFiles().orEmpty().none { it.name.startsWith("print_") }) dir.deleteRecursively()
    }

    // ---- stories ----

    fun stories(printId: Long): Flow<List<StoryEntity>> = dao.storiesOf(printId)

    /** The prints that have at least one story, for marking them in albums. */
    fun printsWithStories(): Flow<List<Long>> = dao.printsWithStories()

    fun storyFile(p: PrintEntity, s: StoryEntity) = File(File(root, p.scanDir), s.file)

    /** Keeps the recording in [audio] (moved, not copied) as a story about print [printId]. */
    suspend fun addStory(printId: Long, audio: File, durationMs: Long): StoryEntity? = io {
        val p = dao.print(printId) ?: return@io null
        val now = System.currentTimeMillis()
        val name = "story_${p.position}_$now.m4a"
        val dest = File(File(root, p.scanDir), name)
        if (!audio.renameTo(dest)) {
            audio.copyTo(dest, overwrite = true)
            audio.delete()
        }
        val s = StoryEntity(printId = printId, file = name, durationMs = durationMs, createdAt = now)
        dao.updatePrint(p.copy(version = p.version + 1))
        s.copy(id = dao.insertStory(s))
    }

    suspend fun deleteStory(s: StoryEntity) = io {
        val p = dao.print(s.printId) ?: return@io
        storyFile(p, s).delete()
        dao.deleteStory(s.id)
        dao.updatePrint(p.copy(version = p.version + 1))
    }

    internal fun storiesNow(printId: Long) = dao.storiesOfNow(printId)

    // ---- export ----

    /**
     * Puts every print that isn't in the gallery (or changed since) into Pictures/Attic as a
     * high-quality JPEG with its real date, people, place and caption written into the EXIF, so
     * Google Photos, Immich and any other app sort and search it properly. Returns how many were written.
     */
    suspend fun exportAll(onProgress: (done: Int, total: Int) -> Unit = { _, _ -> }): Int = io {
        val pending = dao.allPrints().filter { !it.inGallery }
        pending.forEachIndexed { i, p ->
            exportNow(p)
            onProgress(i + 1, pending.size)
        }
        pending.size
    }

    suspend fun export(id: Long) = io { dao.print(id)?.let(::exportNow) }

    private fun exportNow(p: PrintEntity) {
        val album = dao.album(p.albumId)
        val date = p.date ?: album?.date
        val tmp = File(context.cacheDir, "export.jpg")
        if (!writeJpeg(p, tmp)) return
        galleryWrite(p, tmp, album, date)
    }

    /**
     * [p] as it looks in Attic, as a high-quality JPEG with its date, people, place and caption in the EXIF,
     * so any photo app sorts and searches it properly.
     */
    internal fun writeJpeg(p: PrintEntity, tmp: File): Boolean {
        val album = dao.album(p.albumId)
        val date = p.date ?: album?.date
        val people = dao.peopleInNow(p.id)
        val bmp = BitmapFactory.decodeFile(displayFile(p).path) ?: return false
        tmp.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 97, it) }
        bmp.recycle()

        val exif = ExifInterface(tmp)
        val exifTime = DateTimeFormatter.ofPattern("yyyy:MM:dd HH:mm:ss", Locale.US)
        date?.let { exif.setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, it.representative.atTime(12, 0).format(exifTime)) }
        exif.setAttribute(
            ExifInterface.TAG_DATETIME_DIGITIZED,
            java.time.Instant.ofEpochMilli(p.createdAt).atZone(ZoneId.systemDefault()).toLocalDateTime().format(exifTime),
        )
        val description = listOfNotNull(p.caption, date?.label, p.place, album?.title).joinToString(" · ")
        if (description.isNotEmpty()) exif.setAttribute(ExifInterface.TAG_IMAGE_DESCRIPTION, description)
        if (people.isNotEmpty()) exif.setAttribute(ExifInterface.TAG_USER_COMMENT, "People: ${people.joinToString(", ")}")
        exif.setAttribute(ExifInterface.TAG_SOFTWARE, "Attic")
        exif.saveAttributes()
        return true
    }

    private fun galleryWrite(p: PrintEntity, tmp: File, album: AlbumEntity?, date: PhotoDate?) {
        val resolver = context.contentResolver
        val taken = date?.representative?.atTime(12, 0)?.atZone(ZoneId.systemDefault())?.toInstant()?.toEpochMilli()
        // Overwrite the existing gallery copy when there is one, so backup apps don't see duplicates.
        val existing = p.galleryUri?.let(Uri::parse)?.takeIf { uri ->
            runCatching {
                resolver.openOutputStream(uri, "wt")!!.use { out -> tmp.inputStream().use { it.copyTo(out) } }
                resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.DATE_TAKEN, taken) }, null, null)
            }.isSuccess
        }
        val uri = existing ?: run {
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, "attic_${p.scanDir}_${p.position}.jpg")
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Attic/${safeFolder(album?.title ?: "Attic")}")
                taken?.let { put(MediaStore.Images.Media.DATE_TAKEN, it) }
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
            val created = resolver.insert(MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values)
            created?.takeIf {
                runCatching {
                    resolver.openOutputStream(it)!!.use { out -> tmp.inputStream().use { input -> input.copyTo(out) } }
                    resolver.update(it, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
                }.onFailure { _ -> resolver.delete(created, null, null) }.isSuccess
            }
        }
        tmp.delete()
        if (uri != null) dao.updatePrint(p.copy(galleryUri = uri.toString(), exportedVersion = p.version))
    }

    /**
     * Copies of [ids] to hand to another app (messages, mail, Google Photos): each as it looks in Attic,
     * with its date, people, place and caption in the EXIF, named after its album and date. Earlier copies
     * are cleared first.
     */
    suspend fun shareFiles(ids: List<Long>): List<File> = io {
        val dir = File(context.cacheDir, "share")
        dir.deleteRecursively()
        dir.mkdirs()
        val used = HashSet<String>()
        ids.mapNotNull { dao.print(it) }.mapNotNull { p ->
            val album = dao.album(p.albumId)
            val date = p.date ?: album?.date
            val base = safeFolder(listOfNotNull(album?.title, date?.label).joinToString(", ").ifEmpty { "Photo" })
            var name = "$base.jpg"
            var n = 2
            while (!used.add(name)) name = "$base ${n++}.jpg"
            File(dir, name).takeIf { writeJpeg(p, it) }
        }
    }

    private fun safeFolder(s: String) = s.replace(Regex("[\\\\/:*?\"<>|]"), " ").trim().ifEmpty { "Attic" }

    // ---- housekeeping ----

    /** Brings scans saved by the first spike build (files only, no database) into an album. */
    suspend fun importLooseScans() = io {
        val known = dao.knownScanDirs().toSet()
        val loose = root.listFiles().orEmpty().filter { it.isDirectory && it.name !in known && File(it, "meta.json").exists() }
        if (loose.isEmpty()) return@io
        val album = dao.albums().firstOrNull { it.title == "Earlier scans" } ?: AlbumEntity(title = "Earlier scans", createdAt = 0L)
            .let { it.copy(id = dao.insertAlbum(it)) }
        var page = dao.lastPage(album.id)
        for (dir in loose.sortedBy { it.name }) {
            val meta = runCatching { JSONObject(File(dir, "meta.json").readText()) }.getOrNull() ?: continue
            val prints = meta.getJSONArray("prints")
            page++
            for (i in 0 until prints.length()) {
                val j = prints.getJSONObject(i)
                if (!File(dir, j.getString("file")).exists()) continue
                val q = j.getJSONArray("quad")
                val quad = (0 until 8).joinToString(",") { q.getDouble(it).toFloat().toString() }
                dao.insertPrint(
                    PrintEntity(
                        albumId = album.id, scanDir = dir.name, fileName = j.getString("file"), page = page,
                        position = i + 1, width = j.getInt("width"), height = j.getInt("height"),
                        sharpness = j.getDouble("sharpness"), quad = quad, createdAt = meta.getLong("savedAt"),
                    ),
                )
            }
        }
    }

    private fun sweepFile(p: PrintEntity) = File(File(root, p.scanDir), "sweep_" + p.fileName.removeSuffix(".png") + ".json")

    private fun writeSweep(file: File, others: List<Quad?>) {
        val arr = org.json.JSONArray()
        others.forEach { arr.put(it?.encode() ?: JSONObject.NULL) }
        file.writeText(JSONObject().put("others", arr).toString())
    }

    private fun readSweep(file: File): List<Quad?> = runCatching {
        val arr = JSONObject(file.readText()).getJSONArray("others")
        List(arr.length()) { i -> if (arr.isNull(i)) null else parseQuad(arr.getString(i)) }
    }.getOrDefault(emptyList())

    private fun FrameTransform.toJson(): String = JSONObject()
        .put("bufferWidth", bufferWidth).put("bufferHeight", bufferHeight)
        .put("cropLeft", cropLeft).put("cropTop", cropTop)
        .put("cropWidth", cropWidth).put("cropHeight", cropHeight)
        .put("rotation", rotation)
        .toString()

    // ---- faces and people ----

    /** The face finder and matcher, loaded the first time they're needed (the models are copied out of the APK once). */
    private val faces by lazy {
        fun model(name: String): String {
            val f = File(context.noBackupFilesDir, "models/$name")
            if (!f.exists() || f.length() == 0L) {
                f.parentFile?.mkdirs()
                context.assets.open(name).use { input -> f.outputStream().use { input.copyTo(it) } }
            }
            return f.path
        }
        Faces(model("faces_detect.onnx"), model("faces_recognise.onnx"))
    }
    private val faceLock = Any()

    fun facesOf(printId: Long): Flow<List<FaceEntity>> = dao.facesOf(printId)
    fun allFaces(): Flow<List<FaceEntity>> = dao.allFaces()
    /** Each album's own date, for photos that have none. */
    fun albumsWithDates(): Flow<Map<Long, PhotoDate>> = dao.albumsFlow().map { list -> list.mapNotNull { a -> a.date?.let { a.id to it } }.toMap() }
    fun people(): Flow<List<PersonSummary>> = dao.personSummaries()
    fun person(id: Long): Flow<PersonEntity?> = dao.personFlow(id)
    fun printsOf(personId: Long): Flow<List<PrintEntity>> = dao.printsOf(personId)

    /**
     * Finds the faces on every print that hasn't been looked at, or was recropped or turned since. A face
     * that was named keeps its name through a recrop: the new face most like it gets it.
     */
    suspend fun findMissingFaces() = io {
        for (p in dao.printsWithoutFaces()) findFacesNow(p)
    }

    private fun findFacesNow(p: PrintEntity) = synchronized(faceLock) {
        val img = readPng(p.file(root)) ?: return@synchronized
        val found = runCatching { faces.find(img, Faces.RGBA) }.getOrElse { emptyList() }
        img.release()
        val old = dao.facesOfNow(p.id)
        dao.deleteFacesOf(p.id)
        for (f in found) {
            val was = old.maxByOrNull { FaceGroups.cosine(f.vec, FaceGroups.fromBytes(it.vec)) }
                ?.takeIf { FaceGroups.cosine(f.vec, FaceGroups.fromBytes(it.vec)) > 0.6f }
            dao.insertFace(
                FaceEntity(
                    printId = p.id, left = f.left, top = f.top, right = f.right, bottom = f.bottom, score = f.score,
                    vec = FaceGroups.toBytes(f.vec), personId = was?.personId, ignored = was?.ignored ?: false,
                ),
            )
        }
        File(context.cacheDir, "faces").listFiles()?.filter { f -> old.any { f.name == "${it.id}.jpg" } }?.forEach { it.delete() }
        dao.setFacesQuad(p.id, p.quad)
    }

    /**
     * A small square picture of [face] (a bit of hair and chin around it), made the first time it's asked
     * for. Null if the print is gone.
     */
    suspend fun faceThumb(face: FaceEntity): File? = io {
        val out = File(context.cacheDir, "faces/${face.id}.jpg")
        if (out.exists()) return@io out
        val p = dao.print(face.printId) ?: return@io null
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        val src = displayFile(p)
        BitmapFactory.decodeFile(src.path, opts)
        val w = opts.outWidth; val h = opts.outHeight
        if (w <= 0 || h <= 0) return@io null
        val cx = (face.left + face.right) / 2 * w
        val cy = (face.top + face.bottom) / 2 * h
        val side = kotlin.math.max((face.right - face.left) * w, (face.bottom - face.top) * h) * 1.7f
        val l = (cx - side / 2).toInt().coerceIn(0, w - 1)
        val t = (cy - side / 2).toInt().coerceIn(0, h - 1)
        val r = (cx + side / 2).toInt().coerceIn(l + 1, w)
        val b = (cy + side / 2).toInt().coerceIn(t + 1, h)
        val region = android.graphics.BitmapRegionDecoder.newInstance(src.path)
        val sample = Integer.highestOneBit(((r - l) / 240).coerceAtLeast(1))
        val bmp = region?.decodeRegion(android.graphics.Rect(l, t, r, b), BitmapFactory.Options().apply { inSampleSize = sample })
        region?.recycle()
        bmp ?: return@io null
        out.parentFile?.mkdirs()
        out.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        bmp.recycle()
        out
    }

    /** Says who [faceIds] are: the person called [name] (made if new), and tags their photos with them. */
    suspend fun nameFaces(faceIds: List<Long>, name: String) = io {
        val clean = name.trim()
        if (clean.isEmpty()) return@io
        db.runInTransaction {
            val personId = dao.personNamed(clean)?.id ?: dao.insertPerson(PersonEntity(name = clean))
            for (id in faceIds) {
                val f = dao.face(id) ?: continue
                // Someone else's name on this face before: it comes off the photo too, unless another face there has it.
                val before = f.personId
                dao.updateFace(f.copy(personId = personId, ignored = false))
                if (before != null && before != personId && dao.facesOfNow(f.printId).none { it.personId == before }) {
                    dao.untag(f.printId, before)
                }
                dao.tag(PrintPersonEntity(f.printId, personId))
                dao.print(f.printId)?.let { dao.updatePrint(it.copy(version = it.version + 1)) }
            }
        }
    }

    /** Not a face, or nobody anyone will name: it stops being asked about. */
    suspend fun ignoreFace(faceId: Long) = io {
        val f = dao.face(faceId) ?: return@io
        dao.updateFace(f.copy(ignored = true, personId = null))
        if (f.personId != null && dao.facesOfNow(f.printId).none { it.personId == f.personId }) {
            dao.untag(f.printId, f.personId)
            dao.print(f.printId)?.let { dao.updatePrint(it.copy(version = it.version + 1)) }
        }
    }

    /** Unnamed faces that look like the same person, two or more, the biggest groups first. */
    class FaceGroup(val faces: List<FaceEntity>, val suggestion: PersonEntity?)

    suspend fun unnamedGroups(): List<FaceGroup> = io {
        val all = dao.allFacesNow()
        val named = all.filter { it.personId != null }.groupBy({ it.personId!! }, { FaceGroups.fromBytes(it.vec) })
        val people = dao.peopleNow().associateBy { it.id }
        val unnamed = all.filter { it.personId == null }
        val byId = unnamed.associateBy { it.id }
        FaceGroups.groups(unnamed.map { FaceGroups.Face(it.id, it.printId, FaceGroups.fromBytes(it.vec)) })
            .filter { it.size >= 2 }
            .map { g ->
                val mean = FloatArray(g[0].vec.size)
                g.forEach { f -> val n = kotlin.math.sqrt(f.vec.sumOf { (it * it).toDouble() }).toFloat(); for (k in mean.indices) mean[k] += f.vec[k] / n }
                FaceGroup(g.map { byId.getValue(it.id) }, FaceGroups.suggest(mean, named)?.let { people[it.first] })
            }
    }

    /** Who an unnamed face most likely is, among the people already named. */
    suspend fun suggestFor(face: FaceEntity): PersonEntity? = io {
        val named = dao.allFacesNow().filter { it.personId != null && it.id != face.id }.groupBy({ it.personId!! }, { FaceGroups.fromBytes(it.vec) })
        FaceGroups.suggest(FaceGroups.fromBytes(face.vec), named)?.let { dao.person(it.first) }
    }

    suspend fun setBorn(personId: Long, date: PhotoDate?) = io {
        val p = dao.person(personId) ?: return@io
        dao.updatePerson(p.copy(bornFrom = date?.from?.toEpochDay(), bornTo = date?.to?.toEpochDay(), bornLabel = date?.label))
    }

    suspend fun renamePerson(personId: Long, name: String) = io {
        val clean = name.trim()
        val p = dao.person(personId) ?: return@io
        if (clean.isEmpty() || clean == p.name) return@io
        val other = dao.personNamed(clean)
        if (other != null && other.id != personId) {
            // Two names for one person: fold this one into the other.
            db.runInTransaction {
                dao.allFacesNow().filter { it.personId == personId }.forEach { dao.updateFace(it.copy(personId = other.id)) }
                dao.retag(personId, other.id)
                dao.moveRelationsA(personId, other.id)
                dao.moveRelationsB(personId, other.id)
                dao.deletePerson(personId)
            }
        } else {
            dao.updatePerson(p.copy(name = clean))
        }
    }

    private fun readPng(file: File): Mat? {
        val bgr = Imgcodecs.imread(file.path)
        if (bgr.empty()) { bgr.release(); return null }
        val rgba = Mat()
        Imgproc.cvtColor(bgr, rgba, Imgproc.COLOR_BGR2RGBA)
        bgr.release()
        return rgba
    }

    private fun writePng(rgba: Mat, file: File) {
        val bgr = Mat()
        Imgproc.cvtColor(rgba, bgr, Imgproc.COLOR_RGBA2BGR)
        Imgcodecs.imwrite(file.path, bgr, MatOfInt(Imgcodecs.IMWRITE_PNG_COMPRESSION, 3))
        bgr.release()
    }

    private suspend fun <T> io(block: () -> T): T = withContext(Dispatchers.IO) { block() }
    private suspend fun edit(id: Long, change: (PrintEntity) -> PrintEntity) = io {
        dao.print(id)?.let { dao.updatePrint(change(it).copy(version = it.version + 1)) }
    }
}

/** The longest side of a page's backdrop: sharp on a phone, small on disk. */
private const val PAGE_SIDE = 1800

/** Bumped when the way looks are made changes, so prints from before are redone once. */
private const val LOOKS_VERSION = 2

/** Largest image imported as it is, in pixels (a 600 dpi A4 scan is halved). */
private const val IMPORT_PIXELS = 30_000_000L
