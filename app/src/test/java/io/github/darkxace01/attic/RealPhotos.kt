package io.github.darkxace01.attic

import io.github.darkxace01.attic.vision.PrintDetector
import io.github.darkxace01.attic.vision.Pt
import io.github.darkxace01.attic.vision.Quad
import org.junit.Assume.assumeTrue
import org.junit.BeforeClass
import org.junit.Test
import org.opencv.core.Mat
import org.opencv.core.Size
import org.opencv.imgcodecs.Imgcodecs
import org.opencv.imgproc.Imgproc
import java.io.File

/**
 * Runs the detector on real album photos in imgs/ (private, gitignored). Each photo is shrunk to what the
 * live analyser sees, and also re-run with small shifts and noise, the way consecutive camera frames
 * differ, to show how steady the outlines are. Overlays go to app/build/detector/real/.
 */
class RealPhotos {
    /** "found/total +extra": a print counts as found at IoU 0.85. */
    private fun score(truth: List<Quad>, found: List<Quad>): String {
        val left = found.toMutableList()
        var hits = 0
        for (t in truth) {
            val m = left.maxByOrNull { it.iou(t) }?.takeIf { it.iou(t) >= 0.85f } ?: continue
            left.remove(m); hits++
        }
        return "$hits/${truth.size}+${left.size}"
    }

    companion object {
        @BeforeClass
        @JvmStatic
        fun loadOpenCv() = nu.pattern.OpenCV.loadLocally()
    }

    /** The fully visible prints (picture area, not the sleeve pocket), read off zoomed grids, at the analyser's 720 x 1280. */
    private val truths = mapOf(
        "20261030_154120" to listOf(
            q(240, 136, 607, 141, 607, 391, 231, 380),
            q(210, 409, 628, 413, 624, 695, 212, 687),
            q(205, 705, 622, 710, 642, 1037, 194, 1037),
        ),
        "20261030_154123" to listOf(
            q(243, 141, 602, 137, 604, 375, 219, 367),
            q(213, 396, 602, 401, 605, 685, 185, 669),
            q(170, 698, 604, 711, 615, 1062, 145, 1037),
        ),
        "20261030_154127" to listOf(
            q(241, 104, 640, 105, 645, 385, 239, 385),
            q(226, 413, 637, 415, 640, 715, 224, 715),
            q(217, 740, 640, 746, 650, 1075, 207, 1070),
        ),
    )

    private fun q(vararg v: Int) = Quad(Pt(v[0].toFloat(), v[1].toFloat()), Pt(v[2].toFloat(), v[3].toFloat()), Pt(v[4].toFloat(), v[5].toFloat()), Pt(v[6].toFloat(), v[7].toFloat()))

    @Test
    fun run() {
        val dir = File("../imgs")
        val files = dir.listFiles { f -> f.name.endsWith(".jpg") }?.sorted().orEmpty()
        assumeTrue("put album photos in imgs/", files.isNotEmpty())
        val out = File("build/detector/real").apply { mkdirs() }
        val detector = PrintDetector()
        for (f in files) {
            val bgr = Imgcodecs.imread(f.path)
            val rgba = Mat()
            Imgproc.cvtColor(bgr, rgba, Imgproc.COLOR_BGR2RGBA)
            // What the analyser gets: about 960 x 1280.
            val live = Mat()
            val s = 1280.0 / maxOf(rgba.cols(), rgba.rows())
            Imgproc.resize(rgba, live, Size(), s, s, Imgproc.INTER_AREA)
            val seen = mutableListOf<Triple<String, Quad, Float?>>()
            detector.trace = { src, q, sc -> seen += Triple(src, q, sc) }
            detector.segmentTrace = { segs ->
                val img = Mat(); Imgproc.resize(live, img, Size(live.cols() / 2.0, live.rows() / 2.0))
                Imgproc.cvtColor(img, img, Imgproc.COLOR_RGBA2BGR)
                for (sg in segs) Imgproc.line(img, org.opencv.core.Point(sg.a.x.toDouble(), sg.a.y.toDouble()), org.opencv.core.Point(sg.b.x.toDouble(), sg.b.y.toDouble()), org.opencv.core.Scalar(0.0, 0.0, 255.0), 1)
                Imgcodecs.imwrite(File(out, f.nameWithoutExtension + "_segments.png").path, img)
                img.release()
            }
            val dropped = mutableListOf<Pair<Quad, String>>()
            io.github.darkxace01.attic.vision.Candidates.why = { q, w -> dropped += q to w }
            val t0 = System.nanoTime()
            val found = detector.detect(live)
            io.github.darkxace01.attic.vision.Candidates.why = null
            detector.trace = null
            detector.segmentTrace = null
            val ms = (System.nanoTime() - t0) / 1_000_000
            val truth = truths[f.nameWithoutExtension].orEmpty()
            println("${f.name}: ${found.size} outlines in $ms ms, ${score(truth, found)}")
            found.forEach { q -> println("   " + q.points.joinToString { "(%.0f,%.0f)".format(it.x, it.y) }) }
            Scenes.writeOverlay(File(out, f.nameWithoutExtension + ".png"), live, truth, found)
            val tws = truth.map { it.scale(0.5f, 0.5f) }
            println("   top scored:")
            seen.filter { it.third != null }.sortedByDescending { it.third }.distinctBy { it.second.points.map { p -> p.x.toInt() / 3 to p.y.toInt() / 3 } }.take(14).forEach { (src, q, sc) ->
                val best = tws.withIndex().maxBy { it.value.iou(q) }
                println("      ${"%.3f".format(sc)} ${src.take(70)} -> truth ${best.index} iou ${"%.2f".format(best.value.iou(q))} area ${"%.2f".format(q.area / best.value.area)}")
            }
            for ((i, t) in tws.withIndex()) {
                seen.filter { it.third != null && it.second.iou(t) > 0.8f }.sortedByDescending { it.third }.take(6).forEach { (src, q, sc) ->
                    println("   truth $i cand ${"%.3f".format(sc)} iou ${"%.2f".format(q.iou(t))} area ${"%.2f".format(q.area / t.area)} ${src.take(90)}")
                }
                dropped.filter { it.first.iou(t) > 0.85f }.take(6).forEach { println("   truth $i: dropped iou ${"%.2f".format(it.first.iou(t))}: ${it.second}") }
            }
            for ((i, t) in truth.withIndex()) {
                println("   truth $i best found iou ${"%.2f".format(found.maxOfOrNull { it.iou(t) } ?: 0f)}")
                val tw = t.scale(0.5f, 0.5f) // the detector works at 640
                seen.filter { it.second.iou(tw) > 0.5f }.sortedByDescending { it.second.iou(tw) }.take(6).forEach { (src, q, sc) ->
                    println("      $src iou ${"%.2f".format(q.iou(tw))} area ${"%.2f".format(q.area / tw.area)} score $sc")
                }
            }

            // Consecutive frames: tiny shifts, scale and noise.
            val counts = mutableListOf<Int>()
            val frameScores = mutableListOf<String>()
            val r = kotlin.random.Random(1)
            repeat(12) { i ->
                val dx = r.nextDouble(-6.0, 6.0); val dy = r.nextDouble(-6.0, 6.0); val sc = 1 + r.nextDouble(-0.015, 0.015)
                val m = Imgproc.getRotationMatrix2D(org.opencv.core.Point(live.cols() / 2.0, live.rows() / 2.0), r.nextDouble(-0.8, 0.8), sc)
                m.put(0, 2, m.get(0, 2)[0] + dx); m.put(1, 2, m.get(1, 2)[0] + dy)
                val moved = Mat()
                Imgproc.warpAffine(live, moved, m, live.size(), Imgproc.INTER_LINEAR, org.opencv.core.Core.BORDER_REFLECT)
                val noise = Mat(moved.size(), moved.type())
                org.opencv.core.Core.randn(noise, 0.0, 3.0)
                org.opencv.core.Core.add(moved, noise, moved)
                val q = detector.detect(moved)
                counts += q.size
                // Move the truths the same way to score this frame.
                val m0 = m.get(0, 0)[0]; val m1 = m.get(0, 1)[0]; val m2 = m.get(0, 2)[0]
                val m3 = m.get(1, 0)[0]; val m4 = m.get(1, 1)[0]; val m5 = m.get(1, 2)[0]
                val movedTruth = truth.map { t -> t.map { p -> Pt((m0 * p.x + m1 * p.y + m2).toFloat(), (m3 * p.x + m4 * p.y + m5).toFloat()) } }
                frameScores += score(movedTruth, q)
                if (i < 3) Scenes.writeOverlay(File(out, "${f.nameWithoutExtension}_frame$i.png"), moved, emptyList(), q)
                listOf(moved, noise, m).forEach { it.release() }
            }
            println("   per-frame counts: $counts")
            println("   per-frame: $frameScores")
            listOf(bgr, rgba, live).forEach { it.release() }
        }
    }
}
