package io.github.darkxace01.attic

import io.github.darkxace01.attic.vision.FaceGroups
import io.github.darkxace01.attic.vision.Faces
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.BeforeClass
import org.junit.Test
import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.core.MatOfByte
import org.opencv.core.MatOfInt
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgcodecs.Imgcodecs
import org.opencv.imgproc.Imgproc
import java.io.File
import kotlin.random.Random

class FacesTest {
    companion object {
        @BeforeClass
        @JvmStatic
        fun loadOpenCv() = nu.pattern.OpenCV.loadLocally()

        val faces by lazy { Faces("src/main/assets/faces_detect.onnx", "src/main/assets/faces_recognise.onnx") }

        /** Unsplash photos (never anyone's own) with one clear face each. */
        private val PHOTOS = listOf("18ijl_lmgPc", "2aebgOyQ1hQ", "2g1gyEumIrE", "4M0dNfJ4TJE", "5mmNz49oQ2A", "-uAz9C0SZGQ")
        private val dir = File(System.getProperty("user.home"), ".cache/attic-ml/data/photos")

        fun photo(name: String): Mat? = File(dir, "$name.jpg").takeIf { it.exists() }?.let { Imgcodecs.imread(it.path) }

        /** The same picture as an old print scanned by a phone: smaller, softer, faded warm, JPEG. */
        fun aged(bgr: Mat): Mat {
            val m = Mat()
            Imgproc.resize(bgr, m, Size(), 0.55, 0.55, Imgproc.INTER_AREA)
            Imgproc.GaussianBlur(m, m, Size(3.0, 3.0), 0.9)
            Core.multiply(m, Scalar(0.82, 0.9, 1.0), m)
            Core.add(m, Scalar(28.0, 22.0, 12.0), m)
            val buf = MatOfByte()
            Imgcodecs.imencode(".jpg", m, buf, MatOfInt(Imgcodecs.IMWRITE_JPEG_QUALITY, 55))
            return Imgcodecs.imdecode(buf, Imgcodecs.IMREAD_COLOR)
        }
    }

    @Test
    fun findsTheFace() {
        val found = PHOTOS.mapNotNull { n -> photo(n)?.let { faces.find(it, Faces.BGR) } }
        assumeTrue("Unsplash photos not on this machine", found.isNotEmpty())
        assertTrue("found ${found.map { it.size }}", found.count { it.isNotEmpty() } >= found.size - 1)
        for (f in found.flatten()) {
            assertTrue(f.left < f.right && f.top < f.bottom)
            assertEquals(128, f.vec.size)
        }
    }

    /** Each face and its aged copy end up together, and apart from everyone else. */
    @Test
    fun theSameFaceOnAnOldPrintIsTheSamePerson() {
        val list = ArrayList<FaceGroups.Face>()
        var id = 0L
        for ((i, n) in PHOTOS.withIndex()) {
            val img = photo(n) ?: continue
            val a = faces.find(img, Faces.BGR).firstOrNull() ?: continue
            val b = faces.find(aged(img), Faces.BGR).firstOrNull() ?: continue
            list += FaceGroups.Face(id++, i * 2L, a.vec)
            list += FaceGroups.Face(id++, i * 2L + 1, b.vec)
        }
        assumeTrue(list.size >= 6)
        val groups = FaceGroups.groups(list)
        for (g in groups) {
            println(g.map { it.printId })
            assertEquals("one person per group", 1, g.map { it.printId / 2 }.distinct().size)
        }
        assertEquals("each face with its aged copy", list.size / 2, groups.size)
    }

    @Test
    fun groupsAndSuggestions() {
        val rnd = Random(4)
        fun around(c: FloatArray, noise: Float) = FloatArray(c.size) { c[it] + (rnd.nextFloat() - 0.5f) * noise }
        val people = List(4) { FloatArray(128) { rnd.nextFloat() - 0.5f } }
        val list = ArrayList<FaceGroups.Face>()
        var id = 0L
        for ((p, c) in people.withIndex()) repeat(5 - p) { k -> list += FaceGroups.Face(id++, (p * 100 + k).toLong(), around(c, 0.5f)) }
        list.shuffle(rnd)
        val groups = FaceGroups.groups(list)
        assertEquals(listOf(5, 4, 3, 2), groups.map { it.size })
        for (g in groups) assertEquals(1, g.map { it.printId / 100 }.distinct().size)

        // Two faces in one photo are two people, however alike.
        val twins = FaceGroups.groups(listOf(FaceGroups.Face(1, 7, people[0]), FaceGroups.Face(2, 7, around(people[0], 0.1f))))
        assertEquals(2, twins.size)

        val known = mapOf(10L to List(3) { around(people[0], 0.5f) }, 11L to List(3) { around(people[1], 0.5f) })
        assertEquals(10L, FaceGroups.suggest(around(people[0], 0.5f), known)?.first)
        assertNull(FaceGroups.suggest(people[3], known))
        val v = around(people[2], 1f)
        assertTrue(v.contentEquals(FaceGroups.fromBytes(FaceGroups.toBytes(v))))
    }
}
