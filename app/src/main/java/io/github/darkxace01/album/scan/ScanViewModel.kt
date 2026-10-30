package io.github.darkxace01.album.scan

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Rect as AndroidRect
import android.os.SystemClock
import android.util.Rational
import android.util.Size
import android.view.Surface
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.UseCaseGroup
import androidx.camera.core.ViewPort
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.viewModelScope
import io.github.darkxace01.album.store.PrintStore
import io.github.darkxace01.album.store.SavedPrint
import io.github.darkxace01.album.vision.PrintDetector
import io.github.darkxace01.album.vision.Quad
import io.github.darkxace01.album.vision.QuadTracker
import io.github.darkxace01.album.vision.Rectify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Rect
import java.util.concurrent.Executors

data class Outline(val quad: Quad, val locked: Boolean)

data class ScanUiState(
    /** Prints in view, in 0..1 coordinates of the upright camera frame. */
    val outlines: List<Outline> = emptyList(),
    /** Flattened previews of the prints in view, in reading order. */
    val previews: List<ImageBitmap> = emptyList(),
    val autoCapture: Boolean = true,
    val saving: Boolean = false,
    /** Already captured what's in view; waiting for the page to change. */
    val waitingForNext: Boolean = false,
    val justSaved: Int = 0,
    val savedTotal: Int = 0,
    /** Changes on every capture, so the UI can flash. */
    val shutterTick: Long = 0,
    val error: String? = null,
)

class ScanViewModel(app: Application) : AndroidViewModel(app), ImageAnalysis.Analyzer {
    private val store = PrintStore(app)
    private val detector = PrintDetector()
    private val tracker = QuadTracker()
    private val analysisExecutor = Executors.newSingleThreadExecutor()
    private val captureExecutor = Executors.newSingleThreadExecutor()

    private val _state = MutableStateFlow(ScanUiState(savedTotal = store.all().size))
    val state: StateFlow<ScanUiState> = _state

    private var imageCapture: ImageCapture? = null
    private var lastPreviewAt = 0L

    // Auto-capture: fire once everything in view is locked, then wait until the scene changes.
    private var armed = true
    private var lastCaptureAt = 0L
    private var allLockedSince = 0L
    private var emptySince = 0L

    fun bind(provider: ProcessCameraProvider, owner: LifecycleOwner, previewView: PreviewView) {
        val ratio43 = AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY
        val preview = Preview.Builder()
            .setResolutionSelector(ResolutionSelector.Builder().setAspectRatioStrategy(ratio43).build())
            .build()
            .also { it.setSurfaceProvider(previewView.surfaceProvider) }

        val analysis = ImageAnalysis.Builder()
            .setResolutionSelector(
                ResolutionSelector.Builder()
                    .setAspectRatioStrategy(ratio43)
                    .setResolutionStrategy(
                        ResolutionStrategy(Size(1280, 960), ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER),
                    )
                    .build(),
            )
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
            .build()
            .also { it.setAnalyzer(analysisExecutor, this) }

        val capture = ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
            .setJpegQuality(100)
            .setResolutionSelector(
                ResolutionSelector.Builder()
                    .setAspectRatioStrategy(ratio43)
                    .setAllowedResolutionMode(ResolutionSelector.PREFER_HIGHER_RESOLUTION_OVER_CAPTURE_RATE)
                    .build(),
            )
            .build()
        imageCapture = capture

        // FIT keeps the whole sensor frame in every use case, so the preview, the analysis frames and
        // the photo all show exactly the same view and the outlines line up.
        val viewPort = ViewPort.Builder(Rational(3, 4), Surface.ROTATION_0).setScaleType(ViewPort.FIT).build()
        val group = UseCaseGroup.Builder()
            .setViewPort(viewPort)
            .addUseCase(preview)
            .addUseCase(analysis)
            .addUseCase(capture)
            .build()

        provider.unbindAll()
        runCatching { provider.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, group) }
            .onFailure { e -> _state.update { it.copy(error = "Camera unavailable: ${e.message}") } }
    }

    fun setAutoCapture(on: Boolean) = _state.update { it.copy(autoCapture = on) }

    override fun analyze(image: ImageProxy) {
        val now = SystemClock.elapsedRealtime()
        val frame = try {
            image.toUprightRgba()
        } finally {
            image.close()
        }
        val w = frame.cols().toFloat()
        val h = frame.rows().toFloat()

        val found = detector.detect(frame).map { it.scale(1f / w, 1f / h) }
        val tracks = tracker.update(found, now)
            .sortedWith(compareBy({ (it.quad.center.y * 4).toInt() }, { it.quad.center.x }))

        val previews = if (now - lastPreviewAt > 250 || tracks.size != _state.value.previews.size) {
            lastPreviewAt = now
            tracks.map { t -> previewOf(frame, t.quad.scale(w, h)) }
        } else {
            null
        }
        frame.release()

        // Re-arm when the view has been empty for a moment, or everything in view appeared after the last capture.
        if (tracks.isEmpty()) {
            if (emptySince == 0L) emptySince = now
        } else {
            emptySince = 0L
        }
        if (!armed && ((emptySince != 0L && now - emptySince > 600) || (tracks.isNotEmpty() && tracks.all { it.bornAt > lastCaptureAt }))) {
            armed = true
        }

        val allLocked = tracks.isNotEmpty() && tracks.all { it.locked }
        if (allLocked) {
            if (allLockedSince == 0L) allLockedSince = now
        } else {
            allLockedSince = 0L
        }

        _state.update { s ->
            s.copy(
                outlines = tracks.map { Outline(it.quad, it.locked) },
                previews = previews ?: s.previews,
                waitingForNext = !armed && tracks.isNotEmpty(),
            )
        }

        val s = _state.value
        if (s.autoCapture && armed && !s.saving && allLocked && now - allLockedSince >= 250) capture()
    }

    /** Takes the full-resolution photo and flattens every print in view. */
    fun capture() {
        val capture = imageCapture ?: return
        if (_state.value.saving) return
        val quads = tracker.current.map { it.quad }
        armed = false
        lastCaptureAt = SystemClock.elapsedRealtime()
        _state.update { it.copy(saving = true, shutterTick = lastCaptureAt, error = null) }

        capture.takePicture(captureExecutor, object : ImageCapture.OnImageCapturedCallback() {
            override fun onCaptureSuccess(image: ImageProxy) {
                val rotation = image.imageInfo.rotationDegrees
                val crop = AndroidRect(image.cropRect)
                val size = Size(image.width, image.height)
                val buffer = image.planes[0].buffer
                val jpeg = ByteArray(buffer.remaining()).also(buffer::get)
                image.close()
                viewModelScope.launch(Dispatchers.Default) { process(jpeg, rotation, crop, size, quads) }
            }

            override fun onError(exception: ImageCaptureException) {
                armed = true
                _state.update { it.copy(saving = false, error = "Couldn't take the photo: ${exception.message}") }
            }
        })
    }

    private suspend fun process(jpeg: ByteArray, rotation: Int, crop: AndroidRect, size: Size, normQuads: List<Quad>) {
        val result = runCatching {
            val bitmap = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size)
            val decoded = Mat()
            Utils.bitmapToMat(bitmap, decoded)
            bitmap.recycle()
            val full = decoded.cropAndRotate(crop, size, rotation)
            if (full !== decoded) decoded.release()

            val w = full.cols().toFloat()
            val h = full.rows().toFloat()
            // Pressed the shutter with nothing tracked: look for prints in the photo itself.
            val rough = normQuads.map { it.scale(w, h) }.ifEmpty { detector.detect(full) }
            val prints = rough.map { q ->
                val refined = detector.refine(full, q)
                val flat = Rectify.warp(full, refined)
                PrintStore.NewPrint(flat, refined, Rectify.sharpness(flat))
            }
            val saved = store.save(jpeg, rotation, full.cols(), full.rows(), prints)
            prints.forEach { it.image.release() }
            full.release()
            saved.forEach(store::exportToGallery)
            saved
        }
        result.onSuccess { saved ->
            _state.update {
                it.copy(
                    saving = false,
                    justSaved = saved.size,
                    savedTotal = it.savedTotal + saved.size,
                    error = if (saved.isEmpty()) "No photos found in that shot" else null,
                )
            }
            delay(2200)
            _state.update { if (it.justSaved == saved.size) it.copy(justSaved = 0) else it }
        }.onFailure { e ->
            armed = true
            _state.update { it.copy(saving = false, error = "Couldn't save: ${e.message}") }
        }
    }

    fun refreshCount() = _state.update { it.copy(savedTotal = store.all().size) }

    override fun onCleared() {
        analysisExecutor.shutdown()
        captureExecutor.shutdown()
    }

    private fun previewOf(frame: Mat, quad: Quad): ImageBitmap {
        val flat = Rectify.warp(frame, quad, maxSide = 320)
        val bmp = Bitmap.createBitmap(flat.cols(), flat.rows(), Bitmap.Config.ARGB_8888)
        Utils.matToBitmap(flat, bmp)
        flat.release()
        return bmp.asImageBitmap()
    }
}

/** Copies an RGBA analysis frame into a Mat, cropped to the shared view and turned upright. */
private fun ImageProxy.toUprightRgba(): Mat {
    val plane = planes[0]
    val wrapped = Mat(height, width, CvType.CV_8UC4, plane.buffer, plane.rowStride.toLong())
    val crop = cropRect
    val cropped = if (crop.width() in 1 until width || crop.height() in 1 until height) {
        wrapped.submat(Rect(crop.left, crop.top, crop.width(), crop.height()))
    } else {
        wrapped
    }
    val out = Mat()
    when (imageInfo.rotationDegrees) {
        90 -> Core.rotate(cropped, out, Core.ROTATE_90_CLOCKWISE)
        180 -> Core.rotate(cropped, out, Core.ROTATE_180)
        270 -> Core.rotate(cropped, out, Core.ROTATE_90_COUNTERCLOCKWISE)
        else -> cropped.copyTo(out)
    }
    return out
}

/**
 * Applies the capture's crop rectangle (when the JPEG wasn't already cropped) and rotation.
 * Returns the same Mat when there was nothing to do.
 */
private fun Mat.cropAndRotate(crop: AndroidRect, size: Size, rotation: Int): Mat {
    var m = this
    val sameSize = cols() == size.width && rows() == size.height
    if (sameSize && (crop.width() < size.width || crop.height() < size.height) && crop.width() > 0) {
        m = submat(Rect(crop.left, crop.top, crop.width(), crop.height())).clone()
    }
    if (rotation == 0) return m
    val out = Mat()
    when (rotation) {
        90 -> Core.rotate(m, out, Core.ROTATE_90_CLOCKWISE)
        180 -> Core.rotate(m, out, Core.ROTATE_180)
        else -> Core.rotate(m, out, Core.ROTATE_90_COUNTERCLOCKWISE)
    }
    if (m !== this) m.release()
    return out
}
