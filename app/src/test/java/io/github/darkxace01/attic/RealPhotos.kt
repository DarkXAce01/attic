package io.github.darkxace01.attic

import io.github.darkxace01.attic.vision.EdgeFit
import io.github.darkxace01.attic.vision.EdgeSnap
import io.github.darkxace01.attic.vision.PrintNet
import io.github.darkxace01.attic.vision.Pt
import io.github.darkxace01.attic.vision.Quad
import io.github.darkxace01.attic.vision.QuadTracker
import org.junit.Assume.assumeTrue
import org.junit.BeforeClass
import org.junit.Test
import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.core.Size
import org.opencv.imgcodecs.Imgcodecs
import org.opencv.imgproc.Imgproc
import java.io.File

/**
 * Runs the print finder on real album photos in imgs/ (private, gitignored, never uploaded), scored
 * against imgs/labels.json. Prints numbers only. Besides the photo itself, it runs a held-phone
 * simulation (slow drift, shake, noise) through the tracker, the way the camera screen works, to show
 * how steady the outlines are and what auto-capture would take.
 *
 * labels.json: {"<name>": {"size": [w, h], "prints": [[[x, y], [x, y], [x, y], [x, y]], ...]}, ...}
 */
class RealPhotos {
    companion object {
        @BeforeClass
        @JvmStatic
        fun loadOpenCv() = nu.pattern.OpenCV.loadLocally()
    }

    private fun score(truth: List<Quad>, found: List<Quad>): String {
        val left = found.toMutableList()
        var hits = 0
        for (t in truth) {
            val m = left.maxByOrNull { it.iou(t) }?.takeIf { it.iou(t) >= 0.9f } ?: continue
            left.remove(m); hits++
        }
        return "$hits/${truth.size}+${left.size}"
    }

    /** Just enough JSON for labels.json. */
    private fun labels(file: File): Map<String, Pair<Pair<Float, Float>, List<Quad>>> {
        val text = file.readText()
        val entry = Regex(""""([^"]+)"\s*:\s*\{\s*"size"\s*:\s*\[\s*([\d.]+)\s*,\s*([\d.]+)\s*]\s*,\s*"prints"\s*:\s*(\[.*?]\s*]\s*]|\[\s*])\s*}""", RegexOption.DOT_MATCHES_ALL)
        return entry.findAll(text).associate { m ->
            val nums = Regex("""-?[\d.]+""").findAll(m.groupValues[4]).map { it.value.toFloat() }.toList()
            val quads = nums.chunked(8).filter { it.size == 8 }.map { v -> Quad(Pt(v[0], v[1]), Pt(v[2], v[3]), Pt(v[4], v[5]), Pt(v[6], v[7])) }
            m.groupValues[1] to ((m.groupValues[2].toFloat() to m.groupValues[3].toFloat()) to quads)
        }
    }

    @Test
    fun run() {
        val labelFile = File("../imgs/labels.json")
        assumeTrue("put album photos and labels.json in imgs/", labelFile.exists())
        val net = PrintNet(File("src/main/assets/printnet.onnx").readBytes())
        var total = 0
        var found = 0
        var extra = 0
        for ((name, label) in labels(labelFile).toSortedMap()) {
            val bgr = Imgcodecs.imread(File("../imgs/$name.jpg").path)
            if (bgr.empty()) continue
            val rgba = Mat()
            Imgproc.cvtColor(bgr, rgba, Imgproc.COLOR_BGR2RGBA)
            // What the analyser gets: about 960 x 1280.
            val live = Mat()
            val s = 1280.0 / maxOf(rgba.cols(), rgba.rows())
            Imgproc.resize(rgba, live, Size(), s, s, Imgproc.INTER_AREA)
            val (size, truth0) = label
            val truth = truth0.map { it.scale(live.cols() / size.first, live.rows() / size.second) }
            val t0 = System.nanoTime()
            val q = net.detect(live)
            val ms = (System.nanoTime() - t0) / 1_000_000
            val sc = score(truth, q)
            println("$name: $sc in $ms ms, best IoU per print ${truth.joinToString { t -> "%.2f".format(q.maxOfOrNull { it.iou(t) } ?: 0f) }}")
            total += truth.size
            found += sc.substringBefore("/").toInt()
            extra += sc.substringAfter("+").toInt()

            // A held phone: slow drift and a little shake, at about 8 frames a second through the tracker.
            val tracker = QuadTracker()
            val r = kotlin.random.Random(1)
            val w = live.cols().toFloat(); val h = live.rows().toFloat()
            var x = 0.0; var y = 0.0; var a = 0.0
            val timeline = StringBuilder()
            val frameScores = mutableListOf<String>()
            var captured: String? = null
            var lockedSince = -1L
            var lockedIds = emptySet<Long>()
            repeat(28) { i ->
                val now = i * 125L
                x += r.nextDouble(-1.5, 1.5); y += r.nextDouble(-1.5, 1.5); a += r.nextDouble(-0.08, 0.08)
                val m = Imgproc.getRotationMatrix2D(org.opencv.core.Point(w / 2.0, h / 2.0), a, 1.0)
                m.put(0, 2, m.get(0, 2)[0] + x); m.put(1, 2, m.get(1, 2)[0] + y)
                val moved = Mat()
                Imgproc.warpAffine(live, moved, m, live.size(), Imgproc.INTER_LINEAR, Core.BORDER_REFLECT)
                val noise = Mat(moved.size(), moved.type())
                Core.randn(noise, 0.0, 3.0)
                Core.add(moved, noise, moved)
                val m0 = m.get(0, 0)[0]; val m1 = m.get(0, 1)[0]; val m2 = m.get(0, 2)[0]
                val m3 = m.get(1, 0)[0]; val m4 = m.get(1, 1)[0]; val m5 = m.get(1, 2)[0]
                val movedTruth = truth.map { t -> t.map { p -> Pt((m0 * p.x + m1 * p.y + m2).toFloat(), (m3 * p.x + m4 * p.y + m5).toFloat()) } }
                val det = net.detect(moved)
                frameScores += score(movedTruth, det)
                val tracks = tracker.update(det.map { it.scale(1f / w, 1f / h) }, now)
                val allLocked = tracks.isNotEmpty() && tracks.all { it.locked }
                if (System.getProperty("attic.explain") != null && i in 6..12) println("      frame $i: " + tracks.joinToString { "agree %.2f spread %.4f".format(it.agreement, QuadTracker.spread(it.history)) })
                timeline.append(tracks.size).append(if (allLocked) "L " else "  ")
                val ids = tracks.map { it.id }.toSet()
                if (allLocked && ids == lockedIds) {
                    if (captured == null && now - lockedSince >= 900) {
                        // As the app does: the frozen outlines, fitted to the real edges of the photo.
                        val shot = tracks.map { t -> t.shown.scale(w, h).let { q -> EdgeSnap.snap(moved, q, EdgeFit.diagonal(q) * 0.012f) } }
                        captured = "frame $i: " + score(movedTruth, shot)
                    }
                } else {
                    lockedSince = now
                    lockedIds = if (allLocked) ids else emptySet()
                }
                listOf(moved, noise, m).forEach { it.release() }
            }
            println("   frames: $frameScores")
            println("   on screen (L = all locked): $timeline")
            println("   auto-capture: ${captured ?: "none"}")
            listOf(bgr, rgba, live).forEach { it.release() }
        }
        println("total: $found/$total found, $extra extra")
    }
}
