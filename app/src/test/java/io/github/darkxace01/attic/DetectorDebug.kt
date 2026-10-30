package io.github.darkxace01.attic

import io.github.darkxace01.attic.vision.PrintDetector
import io.github.darkxace01.attic.vision.Quad
import org.junit.Assume.assumeTrue
import org.junit.BeforeClass
import org.junit.Test

/** Explains one benchmark scene: for each true print, the closest candidates and how they scored. */
class DetectorDebug {
    companion object {
        @BeforeClass
        @JvmStatic
        fun loadOpenCv() = nu.pattern.OpenCV.loadLocally()
    }

    @Test
    fun explain() {
        val seed = System.getProperty("attic.debugScene")?.toIntOrNull()
        assumeTrue("run with -PdebugScene=N", seed != null)
        val scene = Scenes(seed!!).random()
        val scale = 640f / maxOf(scene.image.cols(), scene.image.rows())
        val truths = scene.truths.map { it.scale(scale, scale) }
        val seen = mutableListOf<Triple<String, Quad, Float?>>()
        val detector = PrintDetector()
        detector.trace = { s, q, score -> seen += Triple(s, q, score) }
        val found = detector.detect(scene.image).map { it.scale(scale, scale) }
        for ((i, t) in truths.withIndex()) {
            println("truth $i: ${t.points.joinToString { "(%.0f,%.0f)".format(it.x, it.y) }}  found iou ${"%.2f".format(found.maxOfOrNull { it.iou(t) } ?: 0f)}")
            seen.filter { it.second.iou(t) > 0.6f }.sortedByDescending { it.second.iou(t) }.take(8).forEach { (s, q, score) ->
                println("   $s iou ${"%.3f".format(q.iou(t))} area ${"%.2f".format(q.area / t.area)} score $score")
            }
        }
        found.filter { f -> truths.none { it.iou(f) >= 0.85f } }.forEach { println("extra: ${it.points.joinToString { "(%.0f,%.0f)".format(it.x, it.y) }}") }
        scene.image.release()
    }
}
