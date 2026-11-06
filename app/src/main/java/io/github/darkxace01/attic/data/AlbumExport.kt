package io.github.darkxace01.attic.data

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.util.Base64
import android.util.Base64OutputStream
import io.github.darkxace01.attic.vision.PageLayout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.OutputStream
import kotlin.math.max
import kotlin.math.min

/**
 * An album as something anyone can keep: one web page that opens in any browser, offline, with the album
 * page by page (each page rebuilt as it was, prints where they were), every photo's date, people, place
 * and caption, its back, and its stories to listen to; or a photobook PDF to print. Written to wherever
 * the person picks; Attic sends it nowhere.
 */
class AlbumExport(private val library: Library) {
    private val dao get() = library.dao

    /** A print with what's written about it. */
    private class Item(val p: PrintEntity, val date: String?, val people: List<String>)

    /** One album page: its prints, and where they sit when the page was one photo. */
    private class Page(val number: Int, val items: List<Item>, val backdrop: Bitmap?, val spots: List<PageLayout.Spot>?, val aspect: Float)

    private fun pages(albumId: Long): Pair<AlbumEntity, List<Page>>? {
        val album = dao.album(albumId) ?: return null
        val prints = dao.printsInNow(albumId)
        val pages = prints.groupBy { it.page }.toSortedMap().map { (number, ps) ->
            val items = ps.map { Item(it, (it.date ?: album.date)?.label, dao.peopleInNow(it.id)) }
            val dirs = ps.map { it.scanDir }.distinct()
            val bd = if (dirs.size == 1) kotlinx.coroutines.runBlocking { library.pageBackdrop(dirs[0]) } else null
            if (bd != null) {
                val frame = PageLayout.frame(ps.map { it.cropQuad }, bd.fullWidth.toFloat(), bd.fullHeight.toFloat())
                val full = BitmapFactory.decodeFile(bd.file.path)
                val k = full.width.toFloat() / bd.fullWidth
                val l = (frame.left * k).toInt().coerceIn(0, full.width - 1); val t = (frame.top * k).toInt().coerceIn(0, full.height - 1)
                val cropped = Bitmap.createBitmap(full, l, t, (frame.width * k).toInt().coerceIn(1, full.width - l), (frame.height * k).toInt().coerceIn(1, full.height - t))
                if (cropped !== full) full.recycle()
                Page(number, items, cropped, ps.map { PageLayout.spot(it.cropQuad, frame) }, frame.width / frame.height)
            } else {
                Page(number, items, null, null, 0.75f)
            }
        }
        return album to pages
    }

    /** The whole album as one HTML file, written to [out]. [progress] gets prints done and how many. */
    suspend fun writeHtml(albumId: Long, out: OutputStream, progress: (Int, Int) -> Unit = { _, _ -> }) = withContext(Dispatchers.IO) {
        val (album, pages) = pages(albumId) ?: return@withContext
        val total = pages.sumOf { it.items.size }
        var done = 0
        val w = out.bufferedWriter()
        fun text(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
        // Embedded files go straight from disk into the page, base64, without holding them in memory.
        fun embed(mime: String, write: (OutputStream) -> Unit) {
            w.write("data:$mime;base64,")
            w.flush()
            val b64 = Base64OutputStream(object : OutputStream() {
                override fun write(b: Int) = out.write(b)
                override fun write(b: ByteArray, off: Int, len: Int) = out.write(b, off, len)
            }, Base64.NO_WRAP or Base64.NO_CLOSE)
            write(b64)
            b64.close()
            out.flush()
        }
        fun jpeg(bmp: Bitmap, quality: Int = 86) = embed("image/jpeg") { bmp.compress(Bitmap.CompressFormat.JPEG, quality, it) }
        fun photo(file: File, side: Int) {
            val bmp = scaled(file, side) ?: return
            jpeg(bmp); bmp.recycle()
        }

        w.write(
            """<!DOCTYPE html><html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>${text(album.title)}</title><style>$CSS</style></head><body>
<header><h1>${text(album.title)}</h1><p>${text(listOfNotNull(album.date?.label, "$total photos").joinToString(" · "))}</p></header>
""",
        )
        for (page in pages) {
            w.write(if (page.backdrop != null) "<section class=\"page\"><div class=\"sheet rebuilt\" style=\"aspect-ratio:${page.aspect}\">" else "<section class=\"page\"><div class=\"sheet\">")
            page.backdrop?.let { bd -> w.write("<img class=\"bd\" alt=\"\" src=\""); jpeg(bd, 80); w.write("\">"); bd.recycle() }
            page.items.forEachIndexed { i, item ->
                val p = item.p
                val spot = page.spots?.getOrNull(i)
                val style = if (spot != null) {
                    "left:${(spot.cx - spot.w * 0.515f) * 100}%;top:${(spot.cy - spot.h * 0.515f) * 100}%;width:${spot.w * 103}%;height:${spot.h * 103}%;transform:rotate(${spot.angleDeg}deg)"
                } else ""
                val details = listOfNotNull(item.date, item.people.joinToString(", ").ifEmpty { null }, p.place, p.caption)
                w.write("<figure class=\"print\" style=\"$style\" data-info=\"${text(details.joinToString("\n"))}\"><img alt=\"${text(p.caption ?: "")}\" src=\"")
                photo(library.displayFile(p), 1800)
                w.write("\">")
                library.backFile(p)?.let { back -> w.write("<img class=\"back\" alt=\"The back\" src=\""); photo(back, 1400); w.write("\">") }
                for (s in library.storiesNow(p.id)) {
                    val f = library.storyFile(p, s)
                    if (!f.exists()) continue
                    w.write("<audio preload=\"none\" src=\"")
                    embed("audio/mp4") { o -> f.inputStream().use { it.copyTo(o) } }
                    w.write("\"></audio>")
                }
                w.write("</figure>")
                progress(++done, total)
            }
            w.write("</div><p class=\"num\">Page ${page.number}</p></section>\n")
        }
        w.write("<div id=\"view\" hidden><div class=\"pic\"><img id=\"big\" alt=\"\"></div><div class=\"side\"><p id=\"info\"></p><div id=\"acts\"></div></div></div>")
        w.write("<footer>Made with Attic, by Grease Pencil Studio. This page works without the internet.</footer><script>$JS</script></body></html>")
        w.flush()
    }

    /** The album as a photobook PDF, A4, one album page to a sheet, written to [out]. */
    suspend fun writePdf(albumId: Long, out: OutputStream, progress: (Int, Int) -> Unit = { _, _ -> }) = withContext(Dispatchers.IO) {
        val (album, pages) = pages(albumId) ?: return@withContext
        val total = pages.sumOf { it.items.size }
        var done = 0
        val doc = PdfDocument()
        val pw = 595; val ph = 842
        val ink = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(30, 30, 30) }
        val muted = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(120, 120, 120); textSize = 10f }
        val photoPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
        val shadow = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(40, 0, 0, 0) }

        // The cover: the title, and the first photo.
        var n = 1
        doc.startPage(PdfDocument.PageInfo.Builder(pw, ph, n++).create()).also { pg ->
            val c = pg.canvas
            ink.typeface = Typeface.create(Typeface.SERIF, Typeface.NORMAL); ink.textSize = 30f
            c.drawText(album.title, 60f, 120f, ink)
            listOfNotNull(album.date?.label, "$total photos").joinToString(" · ").let { c.drawText(it, 60f, 145f, muted) }
            pages.firstOrNull()?.items?.firstOrNull()?.let { first ->
                scaled(library.displayFile(first.p), 1600)?.let { bmp ->
                    val box = RectF(60f, 190f, pw - 60f, ph - 120f)
                    c.drawBitmap(bmp, null, fit(bmp.width, bmp.height, box), photoPaint)
                    bmp.recycle()
                }
            }
            doc.finishPage(pg)
        }
        ink.typeface = Typeface.DEFAULT; ink.textSize = 10f
        for (page in pages) {
            val pg = doc.startPage(PdfDocument.PageInfo.Builder(pw, ph, n++).create())
            val c = pg.canvas
            val area = RectF(48f, 48f, pw - 48f, ph - 110f)
            val spots = page.spots ?: grid(page.items.map { it.p }, area.width() / area.height())
            val aspect = if (page.spots != null) page.aspect else area.width() / area.height()
            val sheet = fit((aspect * 1000).toInt(), 1000, area)
            page.items.forEachIndexed { i, item ->
                val s = spots[i]
                val w = s.w * sheet.width(); val h = s.h * sheet.height()
                val cx = sheet.left + s.cx * sheet.width(); val cy = sheet.top + s.cy * sheet.height()
                // Printed at about 200 dpi: plenty for paper, and the file stays a sensible size.
                val bmp = scaled(library.displayFile(item.p), (max(w, h) / 72f * 200f).toInt()) ?: return@forEachIndexed
                c.save()
                c.rotate(s.angleDeg, cx, cy)
                c.drawRect(cx - w / 2 + 1.5f, cy - h / 2 + 2f, cx + w / 2 + 1.5f, cy + h / 2 + 2f, shadow)
                c.drawBitmap(bmp, null, RectF(cx - w / 2, cy - h / 2, cx + w / 2, cy + h / 2), photoPaint)
                c.restore()
                bmp.recycle()
                progress(++done, total)
            }
            // What's written about the page's photos, under it.
            var y = ph - 90f
            val lines = page.items.mapNotNull { item ->
                listOfNotNull(item.date, item.people.joinToString(", ").ifEmpty { null }, item.p.place, item.p.caption).joinToString(" · ").ifEmpty { null }
            }.distinct().take(5)
            for (line in lines) { c.drawText(ellipsize(line, ink, pw - 96f), 48f, y, ink); y += 14f }
            c.drawText("Page ${page.number}", pw - 48f - muted.measureText("Page ${page.number}"), ph - 30f, muted)
            page.backdrop?.recycle()
            doc.finishPage(pg)
        }
        doc.writeTo(out)
        doc.close()
        out.flush()
    }

    /** Neat rows for a page that wasn't one photo: one or two across, each print a hair turned. */
    private fun grid(prints: List<PrintEntity>, aspect: Float): List<PageLayout.Spot> {
        val cols = if (prints.size <= 2) 1 else 2
        val rows = (prints.size + cols - 1) / cols
        return prints.mapIndexed { i, p ->
            val cw = 1f / cols; val ch = 1f / rows
            val pa = p.width.toFloat() / p.height.coerceAtLeast(1)
            var w = cw * 0.84f
            var h = w * aspect / pa
            if (h > ch * 0.84f) { h = ch * 0.84f; w = h * pa / aspect }
            PageLayout.Spot(cw * (i % cols + 0.5f), ch * (i / cols + 0.5f), w, h, ((p.id * 37 % 7) - 3) * 0.4f)
        }
    }

    private fun fit(w: Int, h: Int, box: RectF): RectF {
        val s = min(box.width() / w, box.height() / h)
        val fw = w * s; val fh = h * s
        return RectF(box.centerX() - fw / 2, box.centerY() - fh / 2, box.centerX() + fw / 2, box.centerY() + fh / 2)
    }

    private fun ellipsize(s: String, paint: Paint, width: Float): String {
        if (paint.measureText(s) <= width) return s
        var t = s
        while (t.isNotEmpty() && paint.measureText("$t…") > width) t = t.dropLast(1)
        return "$t…"
    }

    /** [file] decoded with its long side about [side] pixels (never enlarged). */
    private fun scaled(file: File, side: Int): Bitmap? {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, o)
        if (o.outWidth <= 0) return null
        var sample = 1
        while (max(o.outWidth, o.outHeight) / (sample * 2) >= side) sample *= 2
        val bmp = BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
        val long = max(bmp.width, bmp.height)
        if (long <= side) return bmp
        val k = side.toFloat() / long
        return Bitmap.createScaledBitmap(bmp, (bmp.width * k).toInt().coerceAtLeast(1), (bmp.height * k).toInt().coerceAtLeast(1), true).also { if (it !== bmp) bmp.recycle() }
    }


    companion object {
        private const val CSS = """
*{box-sizing:border-box}body{margin:0;background:#0e0e0f;color:#f2f2f2;font:16px/1.5 system-ui,-apple-system,sans-serif}
header{padding:56px 24px 24px;text-align:center}h1{font:400 40px/1.15 Georgia,'Times New Roman',serif;margin:0 0 8px}
header p{color:#9e9e9e;margin:0}.page{max-width:900px;margin:0 auto;padding:28px 20px}
.sheet{position:relative;width:100%;background:#2a2724;box-shadow:0 18px 40px rgba(0,0,0,.45);display:flex;flex-wrap:wrap;gap:4%;justify-content:center;align-content:center;padding:6%}
.sheet.rebuilt{display:block;padding:0}.bd{position:absolute;inset:0;width:100%;height:100%;object-fit:fill;filter:brightness(.88)}
.print{margin:0;cursor:pointer;background:#f4f1ea;box-shadow:0 3px 10px rgba(0,0,0,.4);transition:transform .2s}
.sheet:not(.rebuilt) .print{width:44%}.sheet.rebuilt .print{position:absolute}
.print img{display:block;width:100%;height:100%;object-fit:cover}.sheet:not(.rebuilt) .print img{height:auto}
.print .back,.print audio{display:none}.print.story::after{content:"";position:absolute;right:6px;bottom:6px;width:10px;height:10px;border-radius:50%;background:#fff;box-shadow:0 0 0 5px rgba(0,0,0,.55)}
.num{color:#9e9e9e;text-align:center;margin:14px 0 0}
#view{position:fixed;inset:0;background:rgba(10,10,11,.97);display:flex;flex-direction:column;z-index:9}
#view[hidden]{display:none}.pic{flex:1;display:flex;align-items:center;justify-content:center;padding:16px;min-height:0}
.pic img{max-width:100%;max-height:100%;box-shadow:0 10px 30px rgba(0,0,0,.6)}.side{padding:8px 24px 28px;max-width:720px;margin:0 auto;width:100%}
#info{white-space:pre-line;margin:0 0 12px}#acts button{font:inherit;color:#f2f2f2;background:#262626;border:0;border-radius:99px;padding:9px 16px;margin:0 8px 8px 0;cursor:pointer}
#acts button.on{background:#e5553a;color:#1c0904}footer{color:#6b6b6b;text-align:center;padding:40px 20px 60px;font-size:13px}
"""
        private const val JS = """
const view=document.getElementById('view'),big=document.getElementById('big'),info=document.getElementById('info'),acts=document.getElementById('acts');
let playing=null;function stop(){if(playing){playing.pause();playing.currentTime=0;playing=null}}
document.querySelectorAll('.print').forEach(f=>{if(f.querySelector('audio'))f.classList.add('story');f.style.position=f.style.position||(f.closest('.rebuilt')?'absolute':'relative');
f.onclick=()=>{const front=f.querySelector('img:not(.back)'),back=f.querySelector('.back');big.src=front.src;info.textContent=f.dataset.info||'';acts.innerHTML='';
if(back){const b=document.createElement('button');b.textContent='Turn it over';let over=false;b.onclick=e=>{e.stopPropagation();over=!over;big.src=over?back.src:front.src;b.textContent=over?'The front':'Turn it over'};acts.appendChild(b)}
f.querySelectorAll('audio').forEach((a,i,all)=>{const b=document.createElement('button');b.textContent=all.length>1?'Story '+(i+1):'Listen to its story';
b.onclick=e=>{e.stopPropagation();if(playing===a){stop();b.classList.remove('on');return}stop();acts.querySelectorAll('button').forEach(x=>x.classList.remove('on'));a.play();playing=a;b.classList.add('on');a.onended=()=>{b.classList.remove('on');playing=null}};acts.appendChild(b)});
view.hidden=false}});
view.onclick=e=>{if(e.target.tagName!=='BUTTON'){stop();view.hidden=true}};document.onkeydown=e=>{if(e.key==='Escape'){stop();view.hidden=true}};
"""
    }
}
