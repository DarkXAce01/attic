package io.github.darkxace01.attic.data

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.MediaStore
import androidx.exifinterface.media.ExifInterface
import io.github.darkxace01.attic.vision.Enhance
import io.github.darkxace01.attic.vision.FrameTransform
import io.github.darkxace01.attic.vision.Quad
import io.github.darkxace01.attic.vision.Rectify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
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
 */
class Library(private val context: Context) {
    val db = AtticDatabase.open(context)
    private val dao = db.dao()
    val root = File(context.filesDir, "scans").apply { mkdirs() }
    private val prefs = context.getSharedPreferences("library", Context.MODE_PRIVATE)

    // ---- reading ----

    fun albums(): Flow<List<AlbumSummary>> = dao.albumSummaries()
    fun album(id: Long): Flow<AlbumEntity?> = dao.albumFlow(id)
    fun printsIn(albumId: Long): Flow<List<PrintEntity>> = dao.printsIn(albumId)
    fun print(id: Long): Flow<PrintEntity?> = dao.printFlow(id)
    fun peopleIn(printId: Long): Flow<List<String>> = dao.peopleIn(printId)
    fun allPeople(): Flow<List<String>> = dao.allPeople()
    fun counts(): Flow<LibraryCounts> = dao.counts()

    /** The file to show and export: the enhanced version when there is one. */
    fun displayFile(p: PrintEntity): File = enhancedFile(p, p.enhance).takeIf { it.exists() } ?: p.file(root)

    /** The plain crop, for comparing against the enhanced one. */
    fun originalFile(p: PrintEntity): File = p.file(root)

    /** The album's cover: its first print, the way it's shown everywhere else. */
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

    class NewPrint(val image: Mat, val quad: Quad, val sharpness: Double)

    /** Saves one capture (one album page): the original photo plus a PNG per print. */
    suspend fun saveCapture(originalJpeg: ByteArray, frame: FrameTransform, prints: List<NewPrint>): List<PrintEntity> = io {
        val album = dao.album(prefs.getLong("scanningAlbum", -1)) ?: dao.albums().firstOrNull() ?: newAlbumNow("My first album")
        val now = System.currentTimeMillis()
        val dirName = now.toString()
        val dir = File(root, dirName).apply { mkdirs() }
        File(dir, "original.jpg").writeBytes(originalJpeg)
        File(dir, "frame.json").writeText(
            JSONObject()
                .put("bufferWidth", frame.bufferWidth).put("bufferHeight", frame.bufferHeight)
                .put("cropLeft", frame.cropLeft).put("cropTop", frame.cropTop)
                .put("cropWidth", frame.cropWidth).put("cropHeight", frame.cropHeight)
                .put("rotation", frame.rotation)
                .toString(),
        )
        val page = dao.lastPage(album.id) + 1
        prints.mapIndexed { i, p ->
            val name = "print_${i + 1}.png"
            writePng(p.image, File(dir, name))
            val e = PrintEntity(
                albumId = album.id, scanDir = dirName, fileName = name, page = page, position = i + 1,
                width = p.image.cols(), height = p.image.rows(), sharpness = p.sharpness,
                quad = p.quad.encode(), createdAt = now,
            )
            renderEnhanced(e, p.image)
            e.copy(id = dao.insertPrint(e))
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
        val out = Enhance.apply(src, p.enhance)
        writePng(out, enhancedFile(p, p.enhance))
        out.release()
        if (flat == null) src.release()
    }

    /** Fills in enhanced files that are missing (prints from before enhancement, or an interrupted save). */
    suspend fun renderMissing() = io {
        dao.allPrints().filter { it.enhance != Enhance.OFF && !enhancedFile(it, it.enhance).exists() && it.file(root).exists() }
            .forEach { renderEnhanced(it, null) }
    }

    /** Remakes the crop from the untouched original with new corners (upright-original pixels). */
    suspend fun recrop(id: Long, quad: Quad) = io {
        val p = dao.print(id) ?: return@io
        val original = loadUprightOriginal(p) ?: return@io
        val flat = Rectify.warp(original, quad)
        writePng(flat, p.file(root))
        derivedFiles(p).forEach { it.delete() }
        val updated = p.copy(
            quad = quad.encode(), width = flat.cols(), height = flat.rows(),
            sharpness = Rectify.sharpness(flat), version = p.version + 1,
        )
        renderEnhanced(updated, flat)
        flat.release()
        original.release()
        dao.updatePrint(updated)
    }

    /** Turns the print a quarter turn clockwise by rotating its corners, so it's remade from the original. */
    suspend fun rotate(id: Long) {
        val p = io { dao.print(id) } ?: return
        val q = p.cropQuad
        recrop(id, Quad(q.bl, q.tl, q.tr, q.br))
    }

    /** The original photo, turned upright, as an RGBA Mat. The caller releases it. */
    suspend fun uprightOriginal(p: PrintEntity): Mat? = io { loadUprightOriginal(p) }

    private fun loadUprightOriginal(p: PrintEntity): Mat? {
        val dir = File(root, p.scanDir)
        val file = File(dir, "original.jpg")
        val bmp = BitmapFactory.decodeFile(file.path) ?: return null
        val m = Mat()
        Utils.bitmapToMat(bmp, m)
        bmp.recycle()
        val frame = runCatching {
            val j = JSONObject(File(dir, "frame.json").readText())
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
        dao.untagAll(p.id)
        dao.deletePrint(p.id)
        val dir = File(root, p.scanDir)
        if (dir.listFiles().orEmpty().none { it.name.startsWith("print_") }) dir.deleteRecursively()
    }

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
        val people = dao.peopleInNow(p.id)

        val tmp = File(context.cacheDir, "export.jpg")
        val bmp = BitmapFactory.decodeFile(displayFile(p).path) ?: return
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
