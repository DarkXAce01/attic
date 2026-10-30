package io.github.darkxace01.attic

import io.github.darkxace01.attic.vision.PrintDetector
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.File
import kotlin.math.hypot

/**
 * Runs the detector over many random scenes (album pages, tables, patterns, glare, sleeves) and scores it
 * like a person would: was every print found, with nothing extra, tight to its edges? Failures are
 * written to app/build/detector/bench/ and the summary to app/build/detector/benchmark.txt.
 */
class DetectorBenchmark {
    companion object {
        @BeforeClass
        @JvmStatic
        fun loadOpenCv() = nu.pattern.OpenCV.loadLocally()

        const val SCENES = 60
    }

    @Test
    fun randomScenes() {
        assumeTrue("run with -Pbenchmark", System.getProperty("attic.benchmark") == "true")
        val out = File("build/detector/bench").apply { deleteRecursively(); mkdirs() }
        val detector = PrintDetector()
        val seed = (System.getProperty("attic.seed") ?: "1000").toInt()
        var prints = 0
        var found = 0
        var extra = 0
        var perfectScenes = 0
        val errors = mutableListOf<Float>()
        var timeMs = 0L

        repeat(SCENES) { i ->
            val scene = Scenes(seed + i).random()
            val t0 = System.nanoTime()
            val detected = detector.detect(scene.image)
            timeMs += (System.nanoTime() - t0) / 1_000_000
            val unmatched = detected.toMutableList()
            var hits = 0
            for (t in scene.truths) {
                val m = unmatched.maxByOrNull { it.iou(t) }?.takeIf { it.iou(t) >= 0.85f }
                if (m != null) {
                    unmatched.remove(m)
                    hits++
                    errors += m.maxCornerShift(t) / hypot(t.outputWidth, t.outputHeight)
                }
            }
            prints += scene.truths.size
            found += hits
            extra += unmatched.size
            if (hits == scene.truths.size && unmatched.isEmpty()) perfectScenes++
            else Scenes.writeOverlay(File(out, "scene_${seed + i}.png"), scene.image, scene.truths, detected)
            scene.image.release()
            if (i % 10 == 9) System.gc()
        }

        val recall = found.toFloat() / prints
        val sorted = errors.sorted()
        val summary = buildString {
            appendLine("scenes: $SCENES, prints: $prints")
            appendLine("found: $found (${"%.1f".format(recall * 100)}%), extra outlines: $extra")
            appendLine("scenes exactly right: $perfectScenes (${"%.1f".format(perfectScenes * 100f / SCENES)}%)")
            if (sorted.isNotEmpty()) {
                appendLine("corner error (share of diagonal): median ${"%.4f".format(sorted[sorted.size / 2])}, 95th ${"%.4f".format(sorted[(sorted.size * 95) / 100])}")
            }
            appendLine("avg detect time on this machine: ${timeMs / SCENES} ms")
        }
        File("build/detector/benchmark.txt").writeText(summary)
        println(summary)

        assertTrue("recall ${"%.3f".format(recall)}\n$summary", recall >= MIN_RECALL)
        assertTrue("extra outlines $extra\n$summary", extra <= SCENES * MAX_EXTRA_PER_SCENE)
    }
}

// Raised as the detector improves; the benchmark must never get worse. Across seeds 1000, 3000 and 7000 the
// detector finds 96.3-97.2% of prints with at most 8 extra outlines per 60 scenes. (Slightly down from the
// synthetic-only tuning, in exchange for real album pages: see RealPhotos.)
private const val MIN_RECALL = 0.95f
private const val MAX_EXTRA_PER_SCENE = 0.15f
