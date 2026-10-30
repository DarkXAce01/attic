package io.github.darkxace01.attic.vision

import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.core.Rect

/**
 * How a camera photo becomes the upright frame the prints were found in: the crop CameraX reports
 * (in sensor-buffer pixels) and the rotation. Saved with each capture so a crop can be remade from the
 * untouched original later.
 */
data class FrameTransform(
    val bufferWidth: Int,
    val bufferHeight: Int,
    val cropLeft: Int,
    val cropTop: Int,
    val cropWidth: Int,
    val cropHeight: Int,
    val rotation: Int,
) {
    /** Applies the crop (if the decoded image wasn't cropped already) and the rotation. May return [m] itself. */
    fun apply(m: Mat): Mat {
        var cur = m
        val uncropped = m.cols() == bufferWidth && m.rows() == bufferHeight
        if (uncropped && cropWidth > 0 && (cropWidth < bufferWidth || cropHeight < bufferHeight)) {
            cur = m.submat(Rect(cropLeft, cropTop, cropWidth, cropHeight)).clone()
        }
        if (rotation % 360 == 0) return cur
        val out = Mat()
        Core.rotate(
            cur, out,
            when (rotation) {
                90 -> Core.ROTATE_90_CLOCKWISE
                180 -> Core.ROTATE_180
                else -> Core.ROTATE_90_COUNTERCLOCKWISE
            },
        )
        if (cur !== m) cur.release()
        return out
    }
}
