package io.github.darkxace01.attic

import io.github.darkxace01.attic.vision.DateStamp
import io.github.darkxace01.attic.vision.StampDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Point
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import java.io.File

class DateStampTest {
    companion object {
        @BeforeClass
        @JvmStatic
        fun loadOpenCv() = nu.pattern.OpenCV.loadLocally()

        val reader by lazy { DateStamp(File("src/main/assets/datestamp.onnx").readBytes()) }

        private val SEGMENTS = mapOf(
            '0' to "abcdef", '1' to "bc", '2' to "abdeg", '3' to "abcdg", '4' to "bcfg",
            '5' to "acdfg", '6' to "acdefg", '7' to "abc", '8' to "abcdefg", '9' to "abcdfg",
        )

        /**
         * Burns [text] into the bottom-right of [print] (RGBA) the way a camera did: slanted orange
         * seven-segment digits with a soft glow. Drawn here independently of the training generator.
         */
        fun stamp(print: Mat, text: String, digitH: Double) {
            val w = digitH * 0.55
            val t = (digitH * 0.13).coerceAtLeast(1.0)
            val gap = w * 0.45
            val space = w * 1.1
            val total = text.sumOf { c -> if (c == ' ') space else if (c == '\'') w * 0.35 + gap * 0.5 else w + gap }
            val mask = Mat.zeros(print.rows(), print.cols(), CvType.CV_8UC1)
            var x = print.cols() - print.rows() * 0.06 - total
            val y0 = print.rows() - print.rows() * 0.05 - digitH
            val slant = 0.15
            fun line(ax: Double, ay: Double, bx: Double, by: Double) = Imgproc.line(
                mask, Point(ax + (y0 + digitH - ay) * slant, ay), Point(bx + (y0 + digitH - by) * slant, by), Scalar(255.0), t.toInt().coerceAtLeast(1), Imgproc.LINE_AA,
            )
            for (c in text) {
                when (c) {
                    ' ' -> { x += space; continue }
                    '\'' -> { line(x + w * 0.3, y0 + t, x + w * 0.1, y0 + digitH * 0.3); x += w * 0.35 + gap * 0.5; continue }
                }
                val i = t * 0.7
                val l = x + t / 2; val r = x + w - t / 2
                val top = y0 + t / 2; val mid = y0 + digitH / 2; val bot = y0 + digitH - t / 2
                for (s in SEGMENTS.getValue(c)) when (s) {
                    'a' -> line(l + i, top, r - i, top)
                    'g' -> line(l + i, mid, r - i, mid)
                    'd' -> line(l + i, bot, r - i, bot)
                    'f' -> line(l, top + i, l, mid - i)
                    'b' -> line(r, top + i, r, mid - i)
                    'e' -> line(l, mid + i, l, bot - i)
                    'c' -> line(r, mid + i, r, bot - i)
                }
                x += w + gap
            }
            // Light = the digits plus a glow round them, laid over the picture in orange.
            val glow = Mat()
            Imgproc.GaussianBlur(mask, glow, Size(0.0, 0.0), digitH * 0.12)
            val light = Mat()
            Core.addWeighted(mask, 1.0, glow, 0.6, 0.0, light)
            light.convertTo(light, CvType.CV_32F, 1 / 255.0)
            Core.min(light, Scalar(1.0), light)
            val f = Mat()
            print.convertTo(f, CvType.CV_32FC4)
            val ch = ArrayList<Mat>()
            Core.split(f, ch)
            val orange = doubleArrayOf(255.0, 150.0, 45.0)
            for (k in 0 until 3) {
                val keep = Mat()
                Core.subtract(Mat(light.size(), CvType.CV_32F, Scalar(1.0)), light, keep)
                Core.multiply(ch[k], keep, ch[k])
                val add = Mat()
                Core.multiply(light, Scalar(orange[k]), add)
                Core.add(ch[k], add, ch[k])
            }
            Core.merge(ch, f)
            f.convertTo(print, CvType.CV_8UC4)
            listOf(mask, glow, light, f).forEach { it.release() }
        }
    }

    @Test
    fun decodeMergesRepeatsAndDropsBlanks() {
        val steps = DateStamp.STEPS
        val probs = FloatArray(DateStamp.CLASSES * steps)
        // '8 8 blank 8 then blanks: "88" needs the blank between the two 8s.
        val seq = listOf("'", "8", "8", "", "8", " ", "7") + List(steps - 7) { "" }
        for ((t, ch) in seq.withIndex()) {
            val k = if (ch.isEmpty()) 0 else DateStamp.ALPHABET.indexOf(ch[0]) + 1
            probs[k * steps + t] = if (ch == "7") 0.7f else 0.95f
        }
        val (text, confidence) = DateStamp.decode(probs)
        assertEquals("'88 7", text)
        assertEquals(0.7f, confidence, 1e-6f)
        assertEquals("" to 0f, DateStamp.decode(FloatArray(DateStamp.CLASSES * steps).also { for (t in 0 until steps) it[t] = 1f }))
    }

    @Test
    fun readsAStampWhicheverWayThePrintIsTurned() {
        val sc = Scenes(5)
        var right = 0
        var total = 0
        for (text in listOf("'87 7 14", "7 14 '92", "'79 12 25", "21 3 '85")) {
            val print = sc.photo(900, 600, color = true)
            stamp(print, text, digitH = 600 * 0.045)
            for (turn in listOf(-1, Core.ROTATE_90_CLOCKWISE, Core.ROTATE_180, Core.ROTATE_90_COUNTERCLOCKWISE)) {
                val img = if (turn < 0) print.clone() else Mat().also { Core.rotate(print, it, turn) }
                val r = reader.read(img)
                println("$text, turn $turn: ${r?.text} (${r?.confidence?.let { "%.2f".format(it) }}) -> ${r?.dates}")
                total++
                if (r != null && r.dates.first() == StampDate.read(text).first()) right++
                img.release()
            }
            print.release()
        }
        println("read $right of $total")
        assertTrue("read $right of $total", right >= total - 2)
    }

    @Test
    fun aPrintWithoutAStampHasNoDate() {
        val sc = Scenes(9)
        var dated = 0
        repeat(12) { k ->
            val print = sc.photo(900, 600, color = k % 3 != 0, border = if (k % 2 == 0) 24 else 0)
            val r = reader.read(print)
            if (r != null) { dated++; println("photo $k read as ${r.text} (${r.confidence})") }
            print.release()
        }
        assertEquals(0, dated)
    }
}
