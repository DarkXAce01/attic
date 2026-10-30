package io.github.darkxace01.album.store

import android.content.ContentValues
import android.content.Context
import android.provider.MediaStore
import io.github.darkxace01.album.vision.Quad
import org.json.JSONArray
import org.json.JSONObject
import org.opencv.core.Mat
import org.opencv.core.MatOfInt
import org.opencv.imgcodecs.Imgcodecs
import org.opencv.imgproc.Imgproc
import java.io.File

data class SavedPrint(
    val file: File,
    val scanId: String,
    val index: Int,
    val width: Int,
    val height: Int,
    val sharpness: Double,
    val savedAt: Long,
)

/**
 * Every capture gets its own folder: the untouched camera JPEG, one lossless PNG per print, and a
 * meta.json describing where each print sat in the original. Originals are never modified.
 */
class PrintStore(private val context: Context) {
    private val root = File(context.filesDir, "scans").apply { mkdirs() }

    class NewPrint(val image: Mat, val quad: Quad, val sharpness: Double)

    fun save(originalJpeg: ByteArray, rotationDegrees: Int, frameW: Int, frameH: Int, prints: List<NewPrint>): List<SavedPrint> {
        val now = System.currentTimeMillis()
        val id = now.toString()
        val dir = File(root, id).apply { mkdirs() }
        File(dir, "original.jpg").writeBytes(originalJpeg)

        val bgra = Mat()
        val saved = prints.mapIndexed { i, p ->
            val file = File(dir, "print_${i + 1}.png")
            Imgproc.cvtColor(p.image, bgra, Imgproc.COLOR_RGBA2BGR)
            Imgcodecs.imwrite(file.path, bgra, MatOfInt(Imgcodecs.IMWRITE_PNG_COMPRESSION, 3))
            SavedPrint(file, id, i + 1, p.image.cols(), p.image.rows(), p.sharpness, now)
        }
        bgra.release()

        val meta = JSONObject()
            .put("savedAt", now)
            .put("rotationDegrees", rotationDegrees)
            .put("frameWidth", frameW)
            .put("frameHeight", frameH)
            .put("prints", JSONArray(prints.mapIndexed { i, p ->
                JSONObject()
                    .put("file", saved[i].file.name)
                    .put("width", saved[i].width)
                    .put("height", saved[i].height)
                    .put("sharpness", p.sharpness)
                    .put("quad", JSONArray(p.quad.points.flatMap { listOf(it.x.toDouble(), it.y.toDouble()) }))
            }))
        File(dir, "meta.json").writeText(meta.toString(2))
        return saved
    }

    fun all(): List<SavedPrint> = root.listFiles().orEmpty()
        .filter { it.isDirectory }
        .sortedByDescending { it.name }
        .flatMap { dir -> readScan(dir) }

    fun delete(print: SavedPrint) {
        print.file.delete()
        val dir = print.file.parentFile ?: return
        if (dir.listFiles().orEmpty().none { it.name.startsWith("print_") }) dir.deleteRecursively()
    }

    /** Copies a print into Pictures/Album so it can be checked full-size in any gallery app. */
    fun exportToGallery(print: SavedPrint): Boolean {
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "album_${print.scanId}_${print.index}.png")
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Album")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values)
            ?: return false
        return runCatching {
            resolver.openOutputStream(uri)!!.use { out -> print.file.inputStream().use { it.copyTo(out) } }
            resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
            true
        }.getOrElse {
            resolver.delete(uri, null, null)
            false
        }
    }

    private fun readScan(dir: File): List<SavedPrint> {
        val meta = runCatching { JSONObject(File(dir, "meta.json").readText()) }.getOrNull() ?: return emptyList()
        val prints = meta.getJSONArray("prints")
        return (0 until prints.length()).mapNotNull { i ->
            val p = prints.getJSONObject(i)
            val file = File(dir, p.getString("file"))
            if (!file.exists()) return@mapNotNull null
            SavedPrint(file, dir.name, i + 1, p.getInt("width"), p.getInt("height"), p.getDouble("sharpness"), meta.getLong("savedAt"))
        }
    }
}
