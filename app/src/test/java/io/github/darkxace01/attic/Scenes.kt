package io.github.darkxace01.attic

import io.github.darkxace01.attic.vision.Pt
import io.github.darkxace01.attic.vision.Quad
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgcodecs.Imgcodecs
import org.opencv.imgproc.Imgproc
import java.io.File
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/**
 * Builds synthetic camera frames of prints for detector tests: photo-like prints (with or without
 * white borders), turned and seen in perspective, on tables and album pages, with patterns, glare,
 * plastic-sleeve haze, blur and sensor noise. Every scene knows where its prints really are.
 */
class Scenes(seed: Int) {
    val rnd = Random(seed)

    class Scene(val image: Mat, val truths: List<Quad>)

    fun background(w: Int, h: Int, color: Scalar, grain: Double): Mat {
        val m = Mat(h, w, CvType.CV_8UC4, color)
        addNoise(m, grain, blur = 9.0)
        return m
    }

    /** Something photo-like: a sky-to-ground gradient, a few shapes, noise. Optional white border. */
    fun photo(w: Int, h: Int, color: Boolean, border: Int = 0, pale: Boolean = false): Mat {
        val m = Mat(h, w, CvType.CV_8UC4, Scalar(244.0, 242.0, 236.0, 255.0))
        val iw = w - border * 2
        val ih = h - border * 2
        val img = m.submat(border, border + ih, border, border + iw)
        val top = when {
            pale -> doubleArrayOf(225.0, 228.0, 232.0)
            color -> doubleArrayOf(110.0 + rnd.nextInt(40), 150.0 + rnd.nextInt(40), 200.0 + rnd.nextInt(30))
            else -> doubleArrayOf(170.0, 170.0, 170.0)
        }
        val bottom = if (color) doubleArrayOf(60.0 + rnd.nextInt(60), 80.0 + rnd.nextInt(40), 40.0 + rnd.nextInt(40)) else doubleArrayOf(55.0, 55.0, 55.0)
        for (y in 0 until ih) {
            val t = y.toDouble() / ih
            Imgproc.line(
                img, Point(0.0, y.toDouble()), Point(iw.toDouble(), y.toDouble()),
                Scalar(top[0] + (bottom[0] - top[0]) * t, top[1] + (bottom[1] - top[1]) * t, top[2] + (bottom[2] - top[2]) * t, 255.0),
            )
        }
        repeat(5) {
            val c = if (color) Scalar(rnd.nextDouble(30.0, 230.0), rnd.nextDouble(30.0, 230.0), rnd.nextDouble(30.0, 230.0), 255.0)
            else rnd.nextDouble(30.0, 220.0).let { v -> Scalar(v, v, v, 255.0) }
            val x = rnd.nextInt(iw / 6, iw * 5 / 6).toDouble()
            val y = rnd.nextInt(ih / 6, ih * 5 / 6).toDouble()
            if (it % 2 == 0) Imgproc.circle(img, Point(x, y), rnd.nextInt(8, maxOf(9, ih / 5)), c, -1)
            else Imgproc.rectangle(img, Point(x, y), Point(x + iw / 6, y + ih / 5), c, -1)
        }
        addNoise(img, 5.0)
        img.release()
        return m
    }

    /** Pastes [print] turned by [angleDeg], leaning back by [tilt]. Returns where it landed. */
    fun place(scene: Mat, print: Mat, center: Pt, angleDeg: Float, tilt: Float = 0.03f): Quad {
        val hw = print.cols() / 2f
        val hh = print.rows() / 2f
        val a = Math.toRadians(angleDeg.toDouble())
        fun turn(x: Float, y: Float) = Pt(center.x + (x * cos(a) - y * sin(a)).toFloat(), center.y + (x * sin(a) + y * cos(a)).toFloat())
        val quad = Quad(turn(-hw * (1 - tilt), -hh), turn(hw * (1 - tilt), -hh), turn(hw, hh), turn(-hw, hh))
        val from = MatOfPoint2f(
            Point(0.0, 0.0), Point(print.cols().toDouble(), 0.0),
            Point(print.cols().toDouble(), print.rows().toDouble()), Point(0.0, print.rows().toDouble()),
        )
        val to = quad.toMat2f()
        val m = Imgproc.getPerspectiveTransform(from, to)
        val warped = Mat()
        Imgproc.warpPerspective(print, warped, m, scene.size(), Imgproc.INTER_LINEAR)
        val mask = Mat(scene.size(), CvType.CV_8UC1, Scalar(0.0))
        Imgproc.fillPoly(mask, listOf(quad.toMatPoint()), Scalar(255.0))
        warped.copyTo(scene, mask)
        listOf(from, to, m, warped, mask, print).forEach { it.release() }
        return quad
    }

    fun fillQuad(scene: Mat, q: Quad, color: Scalar, grain: Double) {
        val layer = Mat(scene.size(), scene.type(), color)
        addNoise(layer, grain, blur = 5.0)
        val mask = Mat(scene.size(), CvType.CV_8UC1, Scalar(0.0))
        Imgproc.fillPoly(mask, listOf(q.toMatPoint()), Scalar(255.0))
        layer.copyTo(scene, mask)
        layer.release(); mask.release()
    }

    /** A busy album page: coloured stripes and small flowers, like printed album paper. */
    fun patternInside(scene: Mat, q: Quad, base: Scalar) {
        val layer = Mat(scene.size(), scene.type(), base)
        var x = -scene.rows().toDouble()
        val stripe = Scalar(base.`val`[0] * 0.85, base.`val`[1] * 0.85, base.`val`[2] * 0.9, 255.0)
        while (x < scene.cols()) {
            Imgproc.line(layer, Point(x, 0.0), Point(x + scene.rows(), scene.rows().toDouble()), stripe, 6)
            x += 26
        }
        repeat(120) {
            val c = Point(rnd.nextDouble(0.0, scene.cols().toDouble()), rnd.nextDouble(0.0, scene.rows().toDouble()))
            Imgproc.circle(layer, c, rnd.nextInt(3, 8), Scalar(200.0, 120.0 + rnd.nextInt(60), 140.0, 255.0), -1)
        }
        addNoise(layer, 5.0)
        val mask = Mat(scene.size(), CvType.CV_8UC1, Scalar(0.0))
        Imgproc.fillPoly(mask, listOf(q.toMatPoint()), Scalar(255.0))
        layer.copyTo(scene, mask)
        layer.release(); mask.release()
    }

    /** A soft bright highlight, like a lamp reflected in a glossy print or a plastic sleeve. */
    fun glare(scene: Mat, center: Pt, rx: Double, ry: Double, angle: Double, strength: Double) {
        val g = Mat(scene.size(), CvType.CV_8UC4, Scalar(0.0, 0.0, 0.0, 0.0))
        Imgproc.ellipse(g, Point(center.x.toDouble(), center.y.toDouble()), Size(rx, ry), angle, 0.0, 360.0, Scalar(strength, strength, strength, 0.0), -1)
        Imgproc.GaussianBlur(g, g, Size(0.0, 0.0), minOf(rx, ry) / 2.5)
        Core.add(scene, g, scene)
        g.release()
    }

    /** Plastic sleeve: everything slightly hazed and lower contrast. */
    fun haze(scene: Mat, amount: Double) {
        val white = Mat(scene.size(), scene.type(), Scalar(235.0, 235.0, 235.0, 255.0))
        Core.addWeighted(scene, 1 - amount, white, amount, 0.0, scene)
        white.release()
    }

    /** What the camera adds: a little blur and sensor noise. */
    fun camera(scene: Mat, blur: Double = 0.8, noise: Double = 6.0): Mat {
        val out = Mat()
        Imgproc.GaussianBlur(scene, out, Size(0.0, 0.0), blur)
        addNoise(out, noise)
        scene.release()
        return out
    }

    fun addNoise(m: Mat, sigma: Double, blur: Double = 0.0) {
        val noise = Mat(m.size(), CvType.CV_16SC4)
        Core.randn(noise, 0.0, sigma)
        if (blur > 0) Imgproc.GaussianBlur(noise, noise, Size(0.0, 0.0), blur / 3)
        val wide = Mat()
        m.convertTo(wide, CvType.CV_16SC4)
        Core.add(wide, noise, wide)
        wide.convertTo(m, m.type())
        noise.release(); wide.release()
    }

    // ---- randomised scenes for the benchmark ----

    /** A random, realistic scene: 1 to 6 prints on a table or an album page, with random trouble. */
    fun random(w: Int = 960, h: Int = 1280): Scene {
        val onPage = rnd.nextFloat() < 0.7
        val dark = rnd.nextFloat() < 0.5
        val table = if (dark) Scalar(35.0 + rnd.nextInt(30), 30.0 + rnd.nextInt(25), 25.0 + rnd.nextInt(20), 255.0)
        else Scalar(150.0 + rnd.nextInt(50), 130.0 + rnd.nextInt(40), 100.0 + rnd.nextInt(40), 255.0)
        val scene = background(w, h, table, grain = 12.0)

        val area = if (onPage) {
            val m = 30f + rnd.nextInt(30)
            Quad(Pt(m, m + rnd.nextInt(20)), Pt(w - m, m), Pt(w - m + rnd.nextInt(10), h - m), Pt(m, h - m - rnd.nextInt(20)))
        } else {
            Quad(Pt(0f, 0f), Pt(w.toFloat(), 0f), Pt(w.toFloat(), h.toFloat()), Pt(0f, h.toFloat()))
        }
        if (onPage) {
            val paper = when (rnd.nextInt(4)) {
                0 -> Scalar(25.0, 25.0, 28.0, 255.0) // black album paper
                1 -> Scalar(200.0, 190.0, 165.0, 255.0) // cream
                2 -> Scalar(120.0, 60.0, 55.0, 255.0) // maroon
                else -> Scalar(170.0, 175.0, 160.0, 255.0)
            }
            if (rnd.nextFloat() < 0.35) patternInside(scene, area, paper) else fillQuad(scene, area, paper, 8.0)
        }

        // Lay out prints in a grid so they don't overlap.
        val count = if (onPage) 1 + rnd.nextInt(6) else 1 + rnd.nextInt(2)
        val cols = if (count <= 1) 1 else 2
        val rows = (count + cols - 1) / cols
        val minX = area.points.maxOf { if (it.x < w / 2) it.x else 0f } + 30
        val minY = area.points.maxOf { if (it.y < h / 2) it.y else 0f } + 30
        val cellW = (w - 2 * minX) / cols
        val cellH = (h - 2 * minY) / rows
        val truths = mutableListOf<Quad>()
        for (i in 0 until count) {
            val cx = minX + cellW * (i % cols + 0.5f)
            val cy = minY + cellH * (i / cols + 0.5f)
            val landscape = rnd.nextBoolean()
            val fill = 0.62f + rnd.nextFloat() * 0.18f
            var pw = cellW * fill
            var ph = pw * (if (landscape) 0.7f else 1.4f)
            if (ph > cellH * fill) { val s = cellH * fill / ph; pw *= s; ph *= s }
            val border = if (rnd.nextFloat() < 0.3) (pw * 0.05f).toInt() else 0
            val print = photo(pw.toInt(), ph.toInt(), color = rnd.nextFloat() < 0.75, border = border)
            truths += place(scene, print, Pt(cx, cy), angleDeg = rnd.nextFloat() * 10f - 5f, tilt = rnd.nextFloat() * 0.06f)
        }

        if (rnd.nextFloat() < 0.35) {
            val t = truths[rnd.nextInt(truths.size)]
            val c = t.points[rnd.nextInt(4)].let { p -> Pt((p.x + t.center.x) / 2, (p.y + t.center.y) / 2) }
            glare(scene, c, 40.0 + rnd.nextInt(60), 20.0 + rnd.nextInt(30), rnd.nextDouble(0.0, 180.0), 110.0 + rnd.nextInt(80))
        }
        if (onPage && rnd.nextFloat() < 0.25) haze(scene, 0.12 + rnd.nextDouble() * 0.12)
        return Scene(camera(scene, blur = 0.6 + rnd.nextDouble() * 0.8, noise = 4.0 + rnd.nextDouble() * 5), truths)
    }

    companion object {
        fun writeOverlay(file: File, img: Mat, truths: List<Quad>, found: List<Quad>) {
            val bgr = Mat()
            Imgproc.cvtColor(img, bgr, Imgproc.COLOR_RGBA2BGR)
            truths.forEach { Imgproc.polylines(bgr, listOf(it.toMatPoint()), true, Scalar(0.0, 255.0, 0.0), 1) }
            found.forEach { Imgproc.polylines(bgr, listOf(it.toMatPoint()), true, Scalar(0.0, 0.0, 255.0), 2) }
            file.parentFile?.mkdirs()
            Imgcodecs.imwrite(file.path, bgr)
            bgr.release()
        }
    }
}

fun Quad.toMatPoint() = MatOfPoint(*points.map { Point(it.x.toDouble(), it.y.toDouble()) }.toTypedArray())
fun Quad.toMat2f() = MatOfPoint2f(*points.map { Point(it.x.toDouble(), it.y.toDouble()) }.toTypedArray())
