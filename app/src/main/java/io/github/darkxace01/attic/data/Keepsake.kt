package io.github.darkxace01.attic.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

/**
 * The whole family archive as one folder for a USB stick or a drive: every album as its own web page
 * ([AlbumExport]: pages rebuilt, every photo's details, backs and stories to listen to) and a front page,
 * index.html, that lists them. Opens in any browser, with no app and no internet, now or in thirty years.
 */
class Keepsake(private val context: Context, private val library: Library) {
    /** Writes it into a new folder inside [tree]. [progress]: albums done, of how many. Returns the folder's name. */
    suspend fun write(tree: Uri, progress: (Int, Int) -> Unit = { _, _ -> }): String? = withContext(Dispatchers.IO) {
        val root = DocumentFile.fromTreeUri(context, tree) ?: return@withContext null
        val name = "Attic keepsake " + java.time.LocalDate.now()
        val dir = root.findFile(name)?.takeIf { it.isDirectory } ?: root.createDirectory(name) ?: return@withContext null
        val albums = library.albums().first().filter { it.count > 0 }
        val export = AlbumExport(library)
        val entries = mutableListOf<Entry>()
        for ((i, a) in albums.withIndex()) {
            progress(i, albums.size)
            val file = fileName(i + 1, a.title)
            dir.findFile(file)?.delete()
            val doc = dir.createFile("text/html", file.removeSuffix(".html")) ?: continue
            context.contentResolver.openOutputStream(doc.uri)?.use { out -> export.writeHtml(a.id, out.buffered()) }
            entries += Entry(a.title, a.dateLabel, a.count, doc.name ?: file, cover(a))
        }
        progress(albums.size, albums.size)
        dir.findFile("index.html")?.delete()
        dir.createFile("text/html", "index")?.let { doc ->
            context.contentResolver.openOutputStream(doc.uri)?.use { it.write(index(entries).toByteArray()) }
        }
        name
    }

    private class Entry(val title: String, val date: String?, val count: Int, val file: String, val cover: String?)

    /** The album's cover as a small embedded JPEG, so the front page needs no other files. */
    private fun cover(a: AlbumSummary): String? = runCatching {
        val f = library.coverFile(a) ?: return null
        val bmp = BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = 4 }) ?: return null
        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.JPEG, 80, out)
        "data:image/jpeg;base64," + Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    }.getOrNull()

    companion object {
        /** "03 Nani's red album.html": numbered so they sort as Attic shows them, safe on any USB stick. */
        fun fileName(n: Int, title: String): String =
            "%02d %s.html".format(n, title.replace(Regex("[\\\\/:*?\"<>|]"), " ").replace(Regex("\\s+"), " ").trim().take(60).ifEmpty { "Album" })

        private fun text(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

        private fun index(entries: List<Entry>): String = buildString {
            append("""<!DOCTYPE html><html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>Our family's photos</title><style>
*{box-sizing:border-box}body{margin:0;background:#0e0e0f;color:#f2f2f2;font:16px/1.5 system-ui,-apple-system,sans-serif}
header{padding:56px 24px 16px;text-align:center}h1{font:400 40px/1.15 Georgia,'Times New Roman',serif;margin:0 0 8px}
header p{color:#9e9e9e;margin:0 auto;max-width:560px}
main{max-width:980px;margin:0 auto;padding:24px;display:grid;grid-template-columns:repeat(auto-fill,minmax(220px,1fr));gap:28px}
a{color:inherit;text-decoration:none}.cover{aspect-ratio:4/3;background:#2a2724;box-shadow:0 12px 30px rgba(0,0,0,.45);overflow:hidden}
.cover img{width:100%;height:100%;object-fit:cover;display:block}h2{font:400 21px/1.3 Georgia,serif;margin:12px 0 2px}.meta{color:#9e9e9e;font-size:14px}
footer{color:#6e6e6e;text-align:center;font-size:13px;padding:24px 24px 48px}
</style></head><body>
<header><h1>Our family's photos</h1><p>Every album, page by page, with who's in each photo, when and where, and the stories told about them. Open any album to look through it.</p></header><main>
""")
            for (e in entries) {
                append("<a href=\"").append(text(e.file.replace(" ", "%20"))).append("\"><div class=\"cover\">")
                e.cover?.let { append("<img alt=\"\" src=\"").append(it).append("\">") }
                append("</div><h2>").append(text(e.title)).append("</h2><div class=\"meta\">")
                append(text(listOfNotNull(e.date, if (e.count == 1) "1 photo" else "${e.count} photos").joinToString(" · ")))
                append("</div></a>\n")
            }
            append("</main><footer>Made with Attic. These pages open in any browser, without the internet.</footer></body></html>")
        }
    }
}
