package io.github.darkxace01.attic.vision

import org.opencv.core.Mat
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import org.opencv.objdetect.FaceDetectorYN
import org.opencv.objdetect.FaceRecognizerSF
import kotlin.math.max
import kotlin.math.min

/**
 * Finds faces in a print and takes each one's fingerprint, on the phone: YuNet finds them (with five
 * landmarks: eyes, nose, mouth corners), SFace lines each one up by its landmarks and turns it into 128
 * numbers that are alike for the same person. Both are from the OpenCV model zoo. [FaceGroups] does the
 * rest.
 */
class Faces(detectModel: String, recogniseModel: String) {
    private val detector = FaceDetectorYN.create(detectModel, "", Size(320.0, 320.0), MIN_SCORE, 0.3f, 50)
    private val recogniser = FaceRecognizerSF.create(recogniseModel, "")

    /** One face: its box as 0..1 of the image, how sure the finder is, and its fingerprint. */
    class Found(val left: Float, val top: Float, val right: Float, val bottom: Float, val score: Float, val vec: FloatArray)

    /** Every face in [img] (RGBA, RGB or BGR as [order] says), biggest first. Thread-safe. */
    fun find(img: Mat, order: Int = RGBA): List<Found> {
        val w0 = img.cols(); val h0 = img.rows()
        val s = min(1.0, SIDE / max(w0, h0).toDouble())
        val bgr = Mat()
        when (order) {
            RGBA -> Imgproc.cvtColor(img, bgr, Imgproc.COLOR_RGBA2BGR)
            RGB -> Imgproc.cvtColor(img, bgr, Imgproc.COLOR_RGB2BGR)
            else -> img.copyTo(bgr)
        }
        if (s < 1.0) Imgproc.resize(bgr, bgr, Size(w0 * s, h0 * s), 0.0, 0.0, Imgproc.INTER_AREA)
        val w = bgr.cols(); val h = bgr.rows()
        val out = ArrayList<Found>()
        synchronized(this) {
            detector.inputSize = Size(w.toDouble(), h.toDouble())
            val faces = Mat()
            detector.detect(bgr, faces)
            for (r in 0 until faces.rows()) {
                val row = FloatArray(15)
                faces.get(r, 0, row)
                val (x, y, fw, fh) = row
                // Too small to tell anyone apart: a face in a crowd, or a pattern that looks like one.
                if (min(fw, fh) < MIN_SIDE * min(w, h) || min(fw, fh) < 20f) continue
                val aligned = Mat()
                val feature = Mat()
                recogniser.alignCrop(bgr, faces.row(r), aligned)
                recogniser.feature(aligned, feature)
                val vec = FloatArray(128)
                feature.get(0, 0, vec)
                aligned.release(); feature.release()
                out += Found(
                    (x / w).coerceIn(0f, 1f), (y / h).coerceIn(0f, 1f),
                    ((x + fw) / w).coerceIn(0f, 1f), ((y + fh) / h).coerceIn(0f, 1f),
                    row[14], vec,
                )
            }
            faces.release()
        }
        bgr.release()
        return out.sortedByDescending { (it.right - it.left) * (it.bottom - it.top) }
    }

    companion object {
        const val RGBA = 0
        const val RGB = 1
        const val BGR = 2
        /** Long side the finder looks at: faces in prints are rarely smaller than 1/40 of it. */
        private const val SIDE = 1280.0
        private const val MIN_SCORE = 0.75f
        /** Smallest face kept, as a share of the print's short side. */
        private const val MIN_SIDE = 0.03f
    }
}
