package io.github.darkxace01.attic.data

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import android.util.Base64OutputStream
import io.github.darkxace01.attic.vision.FaceGroups
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.OutputStream
import kotlin.math.max

/**
 * Asking the family. Attic writes one web page with what it can't tell: faces nobody has named, photos
 * with no date or place. A relative opens it in any browser, no app and no internet needed, types what
 * they know and saves their answers as a small file, which they send back however they like. Attic then
 * reads the answers in: names on faces, dates and places where there were none, and anything they
 * remembered added to the caption. Nothing already filled in is overwritten.
 */
class HelpPack(private val library: Library) {
    private val dao get() = library.dao

    /** How much one page asks, so it stays a size people can send. */
    private val maxFaces = 40
    private val maxPhotos = 60

    class Counts(val faces: Int, val photos: Int)

    /** What's worth asking right now. */
    suspend fun counts(): Counts = withContext(Dispatchers.IO) { Counts(faceQuestions().size, photoQuestions().size) }

    private fun faceQuestions(): List<List<FaceEntity>> {
        val unnamed = dao.allFacesNow().filter { it.personId == null && !it.ignored }
        val byId = unnamed.associateBy { it.id }
        return FaceGroups.groups(unnamed.map { FaceGroups.Face(it.id, it.printId, FaceGroups.fromBytes(it.vec)) })
            .sortedByDescending { it.size }
            .take(maxFaces)
            .map { g -> g.map { byId.getValue(it.id) } }
    }

    private fun photoQuestions(): List<PrintEntity> {
        val albums = dao.albums().associateBy { it.id }
        return dao.allPrints()
            .filter { p -> (p.date ?: albums[p.albumId]?.date) == null || p.place == null }
            .sortedWith(compareBy({ it.albumId }, { it.page }, { it.position }))
            .take(maxPhotos)
    }

    /** The page of questions, written to [out]. */
    suspend fun write(out: OutputStream) = withContext(Dispatchers.IO) {
        val albums = dao.albums().associateBy { it.id }
        val faces = faceQuestions()
        val photos = photoQuestions()
        val people = dao.peopleNow().map { it.name }.sorted()
        val w = out.bufferedWriter()
        fun text(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
        fun embed(bmp: Bitmap, quality: Int) {
            w.write("data:image/jpeg;base64,")
            w.flush()
            val b64 = Base64OutputStream(object : OutputStream() {
                override fun write(b: Int) = out.write(b)
                override fun write(b: ByteArray, off: Int, len: Int) = out.write(b, off, len)
            }, Base64.NO_WRAP or Base64.NO_CLOSE)
            bmp.compress(Bitmap.CompressFormat.JPEG, quality, b64)
            b64.close()
            out.flush()
        }
        fun image(file: File?, side: Int, quality: Int = 82) {
            val bmp = file?.let { scaled(it, side) } ?: return
            embed(bmp, quality); bmp.recycle()
        }

        w.write("<!DOCTYPE html><html lang=\"en\"><head><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">")
        w.write("<title>Can you help with these photos?</title><style>$CSS</style></head><body><main>")
        w.write("<h1>Can you help with these photos?</h1>")
        w.write("<p class=\"lead\">Some of the family's old photos need a name, a date or a place. Fill in whatever you know and leave the rest. When you're done, tap <b>Send my answers</b> at the bottom and send the file back.</p>")
        w.write("<label class=\"field\">Your name<input id=\"from\" autocomplete=\"name\"></label>")
        w.write("<datalist id=\"people\">")
        for (n in people) w.write("<option value=\"${text(n)}\">")
        w.write("</datalist>")
        if (faces.isNotEmpty()) {
            w.write("<h2>Who is this?</h2>")
            for (g in faces) {
                val p = dao.print(g[0].printId) ?: continue
                val f = g[0]
                w.write("<section class=\"q face\" data-print=\"${text(key(p))}\" data-x=\"${cx(f)}\" data-y=\"${cy(f)}\" data-ids=\"${g.joinToString(",") { it.id.toString() }}\"><div class=\"faces\">")
                for (one in g.take(4)) { w.write("<img alt=\"\" src=\""); w.flush(); image(kotlinx.coroutines.runBlocking { library.faceThumb(one) }, 240, 86); w.write("\">") }
                w.write("</div>")
                val n = g.map { it.printId }.distinct().size
                w.write("<p class=\"note\">${if (n == 1) "In one photo" else "In $n photos"}</p>")
                w.write("<input class=\"name\" list=\"people\" placeholder=\"Their name\" autocomplete=\"off\"></section>")
            }
        }
        if (photos.isNotEmpty()) {
            w.write("<h2>About these photos</h2>")
            for (p in photos) {
                val album = albums[p.albumId]
                val date = p.date ?: album?.date
                w.write("<section class=\"q photo\" data-print=\"${text(key(p))}\" data-id=\"${p.id}\"><img class=\"print\" alt=\"\" src=\"")
                w.flush()
                image(library.displayFile(p), 1000)
                w.write("\"><p class=\"note\">${text(album?.title ?: "")}${p.caption?.let { " · " + text(it) } ?: ""}</p>")
                if (date == null) w.write("<input class=\"date\" placeholder=\"When? A year is fine, or about 1985, or the 80s\">")
                if (p.place == null) w.write("<input class=\"place\" placeholder=\"Where?\">")
                w.write("<textarea class=\"memo\" rows=\"2\" placeholder=\"Anything you remember about it\"></textarea></section>")
            }
        }
        if (faces.isEmpty() && photos.isEmpty()) w.write("<p class=\"lead\">Nothing to ask right now.</p>")
        w.write("<div class=\"send\"><button id=\"send\">Send my answers</button><p class=\"note\" id=\"status\"></p><textarea id=\"copy\" rows=\"5\" readonly hidden></textarea></div>")
        w.write("</main><script>const PACK=${JSONObject.quote(System.currentTimeMillis().toString())};$JS</script></body></html>")
        w.flush()
    }

    class Applied(val faces: Int, val dates: Int, val places: Int, val notes: Int, val from: String?) {
        val total get() = faces + dates + places + notes
    }

    /** Reads a relative's answers in. Null if it isn't an answers file. */
    suspend fun apply(json: String): Applied? = withContext(Dispatchers.IO) {
        val a = parse(json) ?: return@withContext null
        var faces = 0; var dates = 0; var places = 0; var notes = 0
        val allFaces = dao.allFacesNow().associateBy { it.id }
        for (f in a.faces) {
            val p = printFor(f.print) ?: continue
            val onPrint = dao.facesOfNow(p.id)
            // The face itself, found again on its photo by where it is (ids change when a library is restored).
            val face = onPrint.minByOrNull { kotlin.math.hypot((cx(it) - f.x).toDouble(), (cy(it) - f.y).toDouble()) }
                ?.takeIf { kotlin.math.hypot((cx(it) - f.x).toDouble(), (cy(it) - f.y).toDouble()) < 0.05 } ?: continue
            if (face.personId != null) continue
            // Along with the faces that looked like them when the page was made, if they're still unnamed.
            val same = f.ids.mapNotNull { allFaces[it] }.filter { it.personId == null && it.id != face.id }
            library.nameFaces(listOf(face.id) + same.map { it.id }, f.name)
            faces++
        }
        for (ph in a.photos) {
            val p = printFor(ph.print) ?: continue
            val album = dao.album(p.albumId)
            ph.date?.let { PhotoDate.parse(it) }?.let { d ->
                if ((p.date ?: album?.date) == null) { library.setDate(p.id, d); dates++ }
            }
            ph.place?.let { pl -> if (p.place == null) { library.setPlace(p.id, pl); places++ } }
            ph.memo?.let { m ->
                val fresh = dao.print(p.id) ?: return@let
                val line = if (a.from != null) "$m (${a.from})" else m
                if (fresh.caption?.contains(m) == true) return@let
                library.setCaption(p.id, listOfNotNull(fresh.caption, line).joinToString("\n"))
                notes++
            }
            library.export(p.id)
        }
        Applied(faces, dates, places, notes, a.from)
    }

    private fun printFor(key: String): PrintEntity? {
        val slash = key.lastIndexOf('/')
        if (slash <= 0) return null
        return dao.printAt(key.substring(0, slash), key.substring(slash + 1))
    }

    private fun scaled(file: File, side: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        if (bounds.outWidth <= 0) return null
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= side) sample *= 2
        val bmp = BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
        val k = side.toFloat() / max(bmp.width, bmp.height)
        if (k >= 1f) return bmp
        return Bitmap.createScaledBitmap(bmp, (bmp.width * k).toInt().coerceAtLeast(1), (bmp.height * k).toInt().coerceAtLeast(1), true).also { if (it !== bmp) bmp.recycle() }
    }

    companion object {
        /** A print, the same on any phone the library is restored to. */
        fun key(p: PrintEntity) = "${p.scanDir}/${p.fileName}"

        /** A face's middle, as a share of its print's size, rounded so it reads well in the page. */
        fun cx(f: FaceEntity) = ((f.left + f.right) / 2).let { Math.round(it * 10000) / 10000f }
        fun cy(f: FaceEntity) = ((f.top + f.bottom) / 2).let { Math.round(it * 10000) / 10000f }

        class FaceAnswer(val print: String, val x: Float, val y: Float, val ids: List<Long>, val name: String)
        class PhotoAnswer(val print: String, val date: String?, val place: String?, val memo: String?)
        class Answers(val from: String?, val faces: List<FaceAnswer>, val photos: List<PhotoAnswer>)

        /** The answers in a file the page saved, blanks left out. Null if it isn't one. */
        fun parse(json: String): Answers? {
            val o = runCatching { JSONObject(json.trim()) }.getOrNull() ?: return null
            if (o.optString("format") != FORMAT) return null
            fun JSONObject.str(k: String) = optString(k, "").trim().take(2000).ifEmpty { null }
            val faces = (o.optJSONArray("faces") ?: JSONArray()).let { arr ->
                (0 until arr.length()).mapNotNull { i ->
                    val f = arr.optJSONObject(i) ?: return@mapNotNull null
                    val name = f.str("name") ?: return@mapNotNull null
                    val print = f.str("print") ?: return@mapNotNull null
                    val ids = f.optString("ids").split(",").mapNotNull { it.trim().toLongOrNull() }
                    FaceAnswer(print, f.optDouble("x", -1.0).toFloat(), f.optDouble("y", -1.0).toFloat(), ids, name.take(80))
                }
            }
            val photos = (o.optJSONArray("photos") ?: JSONArray()).let { arr ->
                (0 until arr.length()).mapNotNull { i ->
                    val p = arr.optJSONObject(i) ?: return@mapNotNull null
                    val print = p.str("print") ?: return@mapNotNull null
                    PhotoAnswer(print, p.str("date"), p.str("place"), p.str("memo")).takeIf { it.date != null || it.place != null || it.memo != null }
                }
            }
            return Answers(o.str("from"), faces, photos)
        }

        const val FORMAT = "attic-answers"

        private const val CSS = """
*{box-sizing:border-box}body{margin:0;background:#f4f4f2;color:#1b1b1b;font:18px/1.5 system-ui,-apple-system,"Segoe UI",Roboto,sans-serif}
main{max-width:640px;margin:0 auto;padding:28px 18px 80px}h1{font-size:28px;line-height:1.2;margin:0 0 10px}h2{font-size:22px;margin:40px 0 12px}
.lead{color:#555}.note{color:#6b6b6b;font-size:15px;margin:8px 0}
.field{display:block;margin:20px 0;font-weight:600}
input,textarea{display:block;width:100%;margin-top:8px;padding:12px 14px;font:inherit;border:1px solid #cfcfcb;border-radius:12px;background:#fff;color:inherit}
input:focus,textarea:focus{outline:2px solid #1b1b1b;outline-offset:1px}
.q{background:#fff;border-radius:18px;padding:16px;margin:0 0 16px;box-shadow:0 1px 2px rgba(0,0,0,.06)}
.faces{display:flex;gap:10px;flex-wrap:wrap}.faces img{width:96px;height:96px;border-radius:50%;object-fit:cover;background:#e6e6e3}
.print{display:block;width:100%;height:auto;border-radius:8px;background:#e6e6e3}
.send{margin-top:36px;text-align:center}
button{font:inherit;font-weight:600;color:#fff;background:#E5553A;border:0;border-radius:999px;padding:14px 28px;cursor:pointer}
#copy{margin-top:12px;font:13px/1.4 ui-monospace,monospace}
"""

        private const val JS = """
(function(){
var store='attic-answers-'+PACK;
var fields=document.querySelectorAll('input,textarea:not(#copy)');
function keyOf(el,i){return el.id||('f'+i);}
try{var saved=JSON.parse(localStorage.getItem(store)||'{}');fields.forEach(function(el,i){if(saved[keyOf(el,i)])el.value=saved[keyOf(el,i)];});}catch(e){}
function remember(){try{var s={};fields.forEach(function(el,i){if(el.value)s[keyOf(el,i)]=el.value;});localStorage.setItem(store,JSON.stringify(s));}catch(e){}}
fields.forEach(function(el){el.addEventListener('input',remember);});
function val(sec,cls){var el=sec.querySelector('.'+cls);return el?el.value.trim():'';}
function answers(){
var out={format:'attic-answers',pack:PACK,from:document.getElementById('from').value.trim(),faces:[],photos:[]};
document.querySelectorAll('.q.face').forEach(function(s){var n=val(s,'name');if(n)out.faces.push({print:s.dataset.print,x:parseFloat(s.dataset.x),y:parseFloat(s.dataset.y),ids:s.dataset.ids,name:n});});
document.querySelectorAll('.q.photo').forEach(function(s){var a={print:s.dataset.print,date:val(s,'date'),place:val(s,'place'),memo:val(s,'memo')};if(a.date||a.place||a.memo)out.photos.push(a);});
return out;
}
document.getElementById('send').addEventListener('click',function(){
var a=answers();var status=document.getElementById('status');
var count=a.faces.length+a.photos.length;
if(!count){status.textContent='Nothing filled in yet.';return;}
var text=JSON.stringify(a);
var name='Attic answers'+(a.from?' from '+a.from.replace(/[^\w\- ]/g,''):'')+'.json';
var file=null;try{file=new File([text],name,{type:'application/json'});}catch(e){}
var copy=document.getElementById('copy');copy.value=text;copy.hidden=false;
if(file&&navigator.canShare&&navigator.canShare({files:[file]})){
navigator.share({files:[file],title:name}).then(function(){status.textContent='Thank you!';}).catch(function(){download();});
}else{download();}
function download(){
var url=URL.createObjectURL(new Blob([text],{type:'application/json'}));
var link=document.createElement('a');link.href=url;link.download=name;document.body.appendChild(link);link.click();link.remove();
status.textContent='Saved as “'+name+'”. Send that file back. If it didn’t save, copy the text below and send that instead.';
}
});
})();
"""
    }
}
