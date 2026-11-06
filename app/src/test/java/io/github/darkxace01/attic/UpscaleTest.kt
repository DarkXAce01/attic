package io.github.darkxace01.attic

import io.github.darkxace01.attic.vision.Upscale
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.BeforeClass
import org.junit.Test
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Rect
import org.opencv.core.Size
import org.opencv.imgcodecs.Imgcodecs
import org.opencv.imgproc.Imgproc
import java.io.File

class UpscaleTest {
    companion object {
        @BeforeClass
        @JvmStatic
        fun loadOpenCv() = nu.pattern.OpenCV.loadLocally()

        private val dir = File(System.getProperty("user.home"), ".cache/attic-ml/data/photos")
        private val model = File("src/main/assets/upscale.onnx")
    }

    private fun psnr(a: Mat, b: Mat): Double {
        val x = Mat(); val y = Mat()
        Imgproc.cvtColor(a, x, Imgproc.COLOR_RGBA2RGB); Imgproc.cvtColor(b, y, Imgproc.COLOR_RGBA2RGB)
        return Core.PSNR(x, y)
    }

    /** How much fine detail: mean gradient size. */
    private fun detail(m: Mat): Double {
        val g = Mat(); Imgproc.cvtColor(m, g, Imgproc.COLOR_RGBA2GRAY)
        val lap = Mat(); Imgproc.Laplacian(g, lap, CvType.CV_32F)
        val abs = Mat(); Core.absdiff(lap, org.opencv.core.Scalar(0.0), abs)
        return Core.mean(abs).`val`[0]
    }

    @Test
    fun aSmallPrintComesBackSharperThanAStretch() {
        val files = dir.listFiles().orEmpty().filter { it.extension == "jpg" }.sortedBy { it.name }.drop(800).take(6)
        assumeTrue(files.isNotEmpty() && model.exists())
        val up = Upscale(model.readBytes())
        var wins = 0; var psnrLoss = 0.0
        for (f in files) {
            val bgr = Imgcodecs.imread(f.path)
            // A 1024-wide original, centre cut so the test stays quick.
            Imgproc.resize(bgr, bgr, Size(1024.0, 1024.0 * bgr.rows() / bgr.cols()), 0.0, 0.0, Imgproc.INTER_AREA)
            val crop = bgr.submat(Rect(0, 0, 1024, minOf(768, bgr.rows()) / 4 * 4))
            val truth = Mat().also { Imgproc.cvtColor(crop, it, Imgproc.COLOR_BGR2RGBA) }
            val small = Mat().also { Imgproc.resize(truth, it, Size(truth.cols() / 4.0, truth.rows() / 4.0), 0.0, 0.0, Imgproc.INTER_AREA) }
            val t0 = System.nanoTime()
            val sr = up.run(small, 4.0)
            val ms = (System.nanoTime() - t0) / 1e6
            val bicubic = Mat().also { Imgproc.resize(small, it, truth.size(), 0.0, 0.0, Imgproc.INTER_CUBIC) }
            val ps = psnr(sr, truth); val pb = psnr(bicubic, truth)
            val ds = detail(sr); val db = detail(bicubic); val dt = detail(truth)
            println("upscale ${f.name}: ${small.cols()}x${small.rows()} in ${"%.0f".format(ms)} ms; psnr ${"%.2f".format(ps)} vs stretch ${"%.2f".format(pb)}; detail ${"%.1f".format(ds)} vs ${"%.1f".format(db)} (truth ${"%.1f".format(dt)})")
            if (ds > db * 1.3) wins++
            psnrLoss += pb - ps
            listOf(bgr, truth, small, sr, bicubic).forEach { it.release() }
        }
        // Edges come back crisp where a stretch blurs them. (It scores lower than a stretch on exact
        // pixels, as Real-ESRGAN always does: it draws in plausible texture rather than averaging.)
        assertTrue("sharper in $wins of ${files.size}", wins == files.size)
        println("upscale: psnr below a stretch by ${"%.2f".format(psnrLoss / files.size)} dB on average")
    }

    @Test
    fun tilesMeetWithoutASeam() {
        assumeTrue(model.exists())
        // A smooth sweep of colour: any seam where tiles meet would show as a step.
        val w = 300; val h = 300
        val px = ByteArray(w * h * 4)
        for (y in 0 until h) for (x in 0 until w) {
            val i = (y * w + x) * 4
            px[i] = (40 + x * 160 / w).toByte(); px[i + 1] = (60 + y * 120 / h).toByte(); px[i + 2] = (90 + (x + y) * 80 / (w + h)).toByte(); px[i + 3] = -1
        }
        val img = Mat(h, w, CvType.CV_8UC4).also { it.put(0, 0, px) }
        val out = Upscale(model.readBytes()).run(img, 4.0)
        val g = Mat().also { Imgproc.cvtColor(out, it, Imgproc.COLOR_RGBA2GRAY); it.convertTo(it, CvType.CV_32F) }
        fun colJump(x: Int): Double { val d = Mat(); Core.absdiff(g.col(x), g.col(x - 1), d); return Core.mean(d).`val`[0] }
        fun rowJump(y: Int): Double { val d = Mat(); Core.absdiff(g.row(y), g.row(y - 1), d); return Core.mean(d).`val`[0] }
        for (k in 1..2) {
            val e = Upscale.TILE * 4 * k
            val col = colJump(e) - (colJump(e - 3) + colJump(e + 3)) / 2
            val row = rowJump(e) - (rowJump(e - 3) + rowJump(e + 3)) / 2
            println("upscale seam at $e: column step ${"%.3f".format(col)}, row step ${"%.3f".format(row)}")
            assertTrue("column seam $col at $e", col < 0.5)
            assertTrue("row seam $row at $e", row < 0.5)
        }
    }

    @Test
    fun sizes() {
        assertTrue(Upscale.factorFor(1000, 700) == 4.0)
        assertTrue(Upscale.factorFor(2400, 1600) == 2.0)
    }
}
