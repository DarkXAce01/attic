package io.github.darkxace01.attic.scan

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
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
import io.github.darkxace01.attic.data.Library
import io.github.darkxace01.attic.finalOutline
import io.github.darkxace01.attic.library
import io.github.darkxace01.attic.printNet
import io.github.darkxace01.attic.vision.Deglare
import io.github.darkxace01.attic.vision.Enhance
import io.github.darkxace01.attic.vision.FrameTransform
import io.github.darkxace01.attic.vision.PrintNet
import io.github.darkxace01.attic.vision.Quad
import io.github.darkxace01.attic.vision.QuadTracker
import io.github.darkxace01.attic.vision.Rectify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Rect
import java.util.concurrent.Executors

data class Outline(val id: Long, val quad: Quad, val locked: Boolean)

data class LivePreview(val id: Long, val image: ImageBitmap, val locked: Boolean)

data class ScanUiState(
    /** Prints in view, in 0..1 coordinates of the upright camera frame. */
    val outlines: List<Outline> = emptyList(),
    /** Flattened previews of the prints in view, one per print, in a stable order. */
    val previews: List<LivePreview> = emptyList(),
    /** The final, full-resolution crops of the last capture, shown for a moment after saving. */
    val lastSaved: List<ImageBitmap> = emptyList(),
    /** 0..1 while everything in view is locked and auto-capture is counting down. */
    val readyProgress: Float = 0f,
    val autoCapture: Boolean = true,
    val saving: Boolean = false,
    /** Already captured what's in view; waiting for the page to change. */
    val waitingForNext: Boolean = false,
    val justSaved: Int = 0,
    /** How many of the last capture's prints look soft enough to be worth retaking. */
    val justSavedSoft: Int = 0,
    /** The album scans go into, and how far into it we are. */
    val albumId: Long = 0,
    val albumTitle: String = "",
    val albumPages: Int = 0,
    val albumPrints: Int = 0,
    /** Changes on every capture, so the UI can flash. */
    val shutterTick: Long = 0,
    /** Glare-free mode: each page is taken from a few angles and the shots merged. */
    val glareFree: Boolean = false,
    /** While a glare-free page is being taken. */
    val sweep: SweepUi? = null,
    /** A print in view looks like it has glare on it. */
    val glareSeen: Boolean = false,
    /** The page saved last, for adding its details: album and page number, page 0 before any. */
    val lastAlbumId: Long = 0,
    val lastPage: Int = 0,
    val error: String? = null,
)

/** How far a glare-free page has got: [taken] of [of] shots, and what to do next. */
data class SweepUi(val taken: Int, val of: Int, val hint: SweepHint)

enum class SweepHint { MoveLeft, MoveRight, Further, KeepInView }

class ScanViewModel(app: Application) : AndroidViewModel(app), ImageAnalysis.Analyzer {
    private val library: Library = app.library
    private val detector = app.printNet
    private val tracker = QuadTracker()
    private val analysisExecutor = Executors.newSingleThreadExecutor()
    private val captureExecutor = Executors.newSingleThreadExecutor()

    private val _state = MutableStateFlow(ScanUiState(autoCapture = library.settings.now.autoCapture, glareFree = library.settings.now.glareFree))
    val state: StateFlow<ScanUiState> = _state

    private val scanningAlbumId = MutableStateFlow<Long?>(null)

    init {
        viewModelScope.launch {
            library.importLooseScans()
            scanningAlbumId.value = library.scanningAlbum().id
            library.renderMissing()
        }
        viewModelScope.launch {
            combine(scanningAlbumId.filterNotNull(), library.albums()) { id, list -> list.firstOrNull { it.id == id } }
                .collect { a ->
                    if (a != null) _state.update { it.copy(albumId = a.id, albumTitle = a.title, albumPages = a.pages, albumPrints = a.count) }
                }
        }
    }

    /** Re-reads which album new scans go into, after it's been switched. */
    fun refreshAlbum() {
        viewModelScope.launch { scanningAlbumId.value = library.scanningAlbum().id }
    }

    fun scanInto(albumId: Long) {
        viewModelScope.launch {
            library.setScanningAlbum(albumId)
            scanningAlbumId.value = albumId
        }
    }

    fun newAlbum(title: String) {
        viewModelScope.launch { scanningAlbumId.value = library.newAlbum(title).id }
    }

    private var imageCapture: ImageCapture? = null
    private val previewCache = HashMap<Long, Pair<Long, LivePreview>>()

    // Auto-capture: fire once the same prints have stayed locked for a moment, then wait until the scene
    // changes. Any print appearing or vanishing starts the countdown over, so it never fires mid-change.
    private var armed = true
    private var lastCaptureAt = 0L
    private var allLockedSince = 0L
    private var lockedIds = emptySet<Long>()
    private var emptySince = 0L
    private val autoDelayMs = 900L

    // Glare-free: the shots so far of the page being taken. Each holds the prints' outlines (0..1) in the
    // same order as the first shot, null for a print that wasn't matched.
    private class SweepShot(val jpeg: ByteArray, val frame: FrameTransform, val quads: List<Quad?>, val ids: List<Long>)
    private var sweep: MutableList<SweepShot>? = null
    private var sweepStartedAt = 0L
    private var sweepTaking = false
    private val glareCache = HashMap<Long, Boolean>()

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

    fun setAutoCapture(on: Boolean) {
        library.settings.update { it.copy(autoCapture = on) }
        _state.update { it.copy(autoCapture = on) }
    }

    /** While something covers the camera (the page details sheet), nothing is taken. */
    @Volatile private var paused = false

    fun setPaused(on: Boolean) { paused = on }

    /** Turning glare-free off in the middle of a page drops the shots taken so far. */
    fun setGlareFree(on: Boolean) {
        library.settings.update { it.copy(glareFree = on) }
        _state.update { it.copy(glareFree = on, sweep = null) }
        if (!on) analysisExecutor.execute { sweep = null; sweepTaking = false }
    }

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

        // Previews are kept per print: rebuilt when a print appears or locks, and at most every 700 ms
        // while it's moving, so the strip doesn't churn.
        previewCache.keys.retainAll(tracks.map { it.id }.toSet())
        glareCache.keys.retainAll(tracks.map { it.id }.toSet())
        val previews = tracks.map { t ->
            val cached = previewCache[t.id]
            val stale = cached == null || cached.second.locked != t.locked || (!t.locked && now - cached.first > 700)
            if (stale) {
                val p = LivePreview(t.id, previewOf(frame, t.shown.scale(w, h)) { flat -> glareCache[t.id] = Deglare.glareShare(flat) > GLARE_SHARE }, t.locked)
                previewCache[t.id] = now to p
                p
            } else {
                cached.second
            }
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
        val ids = tracks.map { it.id }.toSet()
        if (allLocked && ids == lockedIds) {
            if (allLockedSince == 0L) allLockedSince = now
        } else {
            allLockedSince = if (allLocked) now else 0L
            lockedIds = if (allLocked) ids else emptySet()
        }
        val s = _state.value
        val sw = sweep
        val counting = sw == null && !paused && s.autoCapture && armed && !s.saving && allLocked
        val progress = if (counting) ((now - allLockedSince).toFloat() / autoDelayMs).coerceIn(0f, 1f) else 0f
        val sweepUi = sw?.let { sweepStep(it, tracks, allLocked, now) }

        _state.update {
            it.copy(
                outlines = tracks.map { t -> Outline(t.id, t.shown, t.locked) },
                previews = previews,
                waitingForNext = sw == null && !armed && tracks.isNotEmpty(),
                readyProgress = if (sw != null) sw.size.toFloat() / SWEEP_SHOTS else progress,
                sweep = sweepUi,
                glareSeen = tracks.any { t -> glareCache[t.id] == true },
            )
        }

        if (counting && progress >= 1f) captureNow()
    }

    /**
     * One frame of a glare-free page: takes the next shot once the phone has moved far enough from every
     * shot so far (so the reflections land somewhere else on the prints) and is held still with every
     * print in view. Finishes after [SWEEP_SHOTS] shots, or with what it has after a while.
     */
    private fun sweepStep(sw: List<SweepShot>, tracks: List<QuadTracker.Track>, allLocked: Boolean, now: Long): SweepUi {
        val matched = matchToFirst(sw[0], tracks)
        val hintAfterMove = if (sw.size == 1) SweepHint.MoveLeft else SweepHint.MoveRight
        val ui = { h: SweepHint -> SweepUi(sw.size, SWEEP_SHOTS, h) }
        if (sweepTaking || paused || _state.value.saving) return ui(hintAfterMove)
        if (now - sweepStartedAt > SWEEP_TIMEOUT_MS) { finishSweep(); return ui(hintAfterMove) }
        if (matched == null) return ui(SweepHint.KeepInView)
        // How far the prints moved from the nearest shot so far, in shares of the frame.
        val moved = sw.minOf { shot ->
            matched.indices.mapNotNull { i -> shot.quads[i]?.let { matched[i].shown.maxCornerShift(it) } }.average().toFloat()
        }
        if (moved < SWEEP_MOVE) return ui(if (allLocked && moved > SWEEP_MOVE / 3) SweepHint.Further else hintAfterMove)
        if (!allLocked) return ui(hintAfterMove)
        sweepTaking = true
        val quads = matched.map { it.shown }
        val ids = matched.map { it.id }
        takeShot(
            onShot = { jpeg, frame ->
                sweepTaking = false
                val list = sweep ?: return@takeShot
                list += SweepShot(jpeg, frame, quads, ids)
                if (list.size >= SWEEP_SHOTS) finishSweep()
            },
            onError = { sweepTaking = false },
        )
        return ui(hintAfterMove)
    }

    /**
     * The tracks in view in the first shot's order, or null unless every print of the first shot is
     * there. By track when the tracker kept them, else by reading order when the count matches.
     */
    private fun matchToFirst(first: SweepShot, tracks: List<QuadTracker.Track>): List<QuadTracker.Track>? {
        val byId = tracks.associateBy { it.id }
        if (first.ids.all { it in byId }) return first.ids.map { byId.getValue(it) }
        if (tracks.size != first.ids.size) return null
        return tracks.sortedWith(compareBy({ (it.shown.center.y * 8).toInt() }, { it.shown.center.x }))
    }

    private fun finishSweep() {
        val shots = sweep ?: return
        sweep = null
        // Like any capture: wait for the next page. The tracker may have renewed tracks while the phone
        // moved, so the wait counts from now.
        armed = false
        lastCaptureAt = SystemClock.elapsedRealtime()
        _state.update { it.copy(saving = true, sweep = null, readyProgress = 0f) }
        viewModelScope.launch(Dispatchers.Default) { processSweep(shots) }
    }

    /**
     * Takes the full-resolution photo and flattens every print in view. In glare-free mode this is the
     * first of the page's shots, and pressing it again during the page finishes with the shots so far.
     */
    fun capture() {
        if (_state.value.saving) return
        // On the analysis thread, where the tracker and the sweep live.
        analysisExecutor.execute(::captureNow)
    }

    private fun captureNow() {
        if (_state.value.saving || sweepTaking) return
        if (sweep != null) { finishSweep(); return }
        val visible = tracker.visible.sortedWith(compareBy({ (it.shown.center.y * 8).toInt() }, { it.shown.center.x }))
        val quads = visible.map { it.shown }
        armed = false
        if (_state.value.glareFree && quads.isNotEmpty()) {
            sweepTaking = true
            takeShot(
                onShot = { jpeg, frame ->
                    sweepTaking = false
                    sweep = mutableListOf(SweepShot(jpeg, frame, quads, visible.map { it.id }))
                    sweepStartedAt = SystemClock.elapsedRealtime()
                    _state.update { it.copy(sweep = SweepUi(1, SWEEP_SHOTS, SweepHint.MoveLeft)) }
                },
                onError = { sweepTaking = false },
            )
            return
        }
        _state.update { it.copy(saving = true) }
        takeShot(
            onShot = { jpeg, frame -> viewModelScope.launch(Dispatchers.Default) { process(jpeg, frame, quads) } },
            onError = { _state.update { it.copy(saving = false) } },
        )
    }

    private fun takeShot(onShot: (ByteArray, FrameTransform) -> Unit, onError: () -> Unit) {
        val capture = imageCapture ?: return onError()
        lastCaptureAt = SystemClock.elapsedRealtime()
        _state.update { it.copy(shutterTick = lastCaptureAt, error = null, readyProgress = 0f) }
        capture.takePicture(captureExecutor, object : ImageCapture.OnImageCapturedCallback() {
            override fun onCaptureSuccess(image: ImageProxy) {
                val crop = image.cropRect
                val frame = FrameTransform(
                    image.width, image.height, crop.left, crop.top, crop.width(), crop.height(),
                    image.imageInfo.rotationDegrees,
                )
                val buffer = image.planes[0].buffer
                val jpeg = ByteArray(buffer.remaining()).also(buffer::get)
                image.close()
                // Sweep state lives on the analysis thread, like the tracker.
                analysisExecutor.execute { onShot(jpeg, frame) }
            }

            override fun onError(exception: ImageCaptureException) {
                analysisExecutor.execute {
                    armed = true
                    sweep = null
                    onError()
                    _state.update { it.copy(sweep = null, error = "Couldn't take the photo: ${exception.message}") }
                }
            }
        })
    }

    private fun decodeUpright(jpeg: ByteArray, frame: FrameTransform): Mat {
        val bitmap = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size)
        val decoded = Mat()
        Utils.bitmapToMat(bitmap, decoded)
        bitmap.recycle()
        val full = frame.apply(decoded)
        if (full !== decoded) decoded.release()
        return full
    }

    /**
     * A glare-free page: every print cut from every shot at the same size, then merged so each spot comes
     * from a shot where it has no glare. The first shot is the page's original.
     */
    private suspend fun processSweep(shots: List<SweepShot>) = report(runCatching {
        val first = shots[0]
        val base = decodeUpright(first.jpeg, first.frame)
        val bw = base.cols().toFloat(); val bh = base.rows().toFloat()
        val quads = first.quads.map { q -> detector.finalOutline(base, q!!.scale(bw, bh)) }
        val flats = quads.map { Rectify.warp(base, it) }
        base.release()
        val others = List(quads.size) { mutableListOf<Mat>() }
        val otherQuads = List(quads.size) { MutableList<Quad?>(shots.size - 1) { null } }
        for ((k, shot) in shots.drop(1).withIndex()) {
            val full = decodeUpright(shot.jpeg, shot.frame)
            val fw = full.cols().toFloat(); val fh = full.rows().toFloat()
            for (i in quads.indices) {
                val q = shot.quads.getOrNull(i) ?: continue
                val fq = detector.finalOutline(full, q.scale(fw, fh))
                others[i] += Rectify.warp(full, fq, size = flats[i].cols() to flats[i].rows())
                otherQuads[i][k] = fq
            }
            full.release()
        }
        val prints = flats.mapIndexed { i, flat ->
            val merged = Deglare.merge(flat, others[i])
            flat.release()
            others[i].forEach { it.release() }
            Library.NewPrint(merged, quads[i], Rectify.sharpness(merged), otherQuads[i])
        }
        store(prints) { library.saveCapture(first.jpeg, first.frame, it, shots.drop(1).map { s -> Library.Shot(s.jpeg, s.frame) }) }
    })

    private suspend fun process(jpeg: ByteArray, frame: FrameTransform, normQuads: List<Quad>) = report(runCatching {
        val full = decodeUpright(jpeg, frame)
        val w = full.cols().toFloat()
        val h = full.rows().toFloat()
        // Pressed the shutter with nothing tracked: look for prints in the photo itself.
        val rough = normQuads.map { it.scale(w, h) }.ifEmpty { detector.detect(full) }
        val prints = rough.map { q ->
            // The preview outline is good to about a pixel of the small analysis frame, which in the full
            // photo can be a sliver of album page. The network takes a closer look at just this print,
            // then each side settles on the real edge.
            val refined = detector.finalOutline(full, q)
            val flat = Rectify.warp(full, refined)
            Library.NewPrint(flat, refined, Rectify.sharpness(flat))
        }
        full.release()
        store(prints) { library.saveCapture(jpeg, frame, it) }
    })

    private class Stored(val saved: List<io.github.darkxace01.attic.data.PrintEntity>, val thumbs: List<ImageBitmap>, val soft: Int)

    /** Saves [prints] with [save], puts them in the gallery and releases them. */
    private suspend fun store(prints: List<Library.NewPrint>, save: suspend (List<Library.NewPrint>) -> List<io.github.darkxace01.attic.data.PrintEntity>): Stored {
        val saved = if (prints.isEmpty()) emptyList() else save(prints)
        val thumbs = prints.map { previewOf(it.image, Quad.ofSize(it.image.cols(), it.image.rows()), enhance = true) }
        val soft = prints.count { it.sharpness < SOFT_SHARPNESS }
        prints.forEach { it.image.release() }
        // New prints go straight into the gallery too, so they're safe the moment they're scanned.
        if (library.settings.now.saveToGallery) saved.forEach { library.export(it.id) }
        return Stored(saved, thumbs, soft)
    }

    private suspend fun report(result: Result<Stored>) {
        result.onSuccess { r ->
            _state.update {
                it.copy(
                    saving = false,
                    justSaved = r.saved.size,
                    justSavedSoft = r.soft,
                    lastSaved = r.thumbs,
                    error = if (r.saved.isEmpty()) "No photos found in that shot" else null,
                    lastAlbumId = r.saved.firstOrNull()?.albumId ?: it.lastAlbumId,
                    lastPage = r.saved.firstOrNull()?.page ?: it.lastPage,
                )
            }
            delay(if (r.soft > 0) 4000 else 2500)
            _state.update { if (it.lastSaved === r.thumbs) it.copy(justSaved = 0, justSavedSoft = 0, lastSaved = emptyList()) else it }
        }.onFailure { e ->
            armed = true
            _state.update { it.copy(saving = false, error = "Couldn't save: ${e.message}") }
        }
    }

    override fun onCleared() {
        analysisExecutor.shutdown()
        captureExecutor.shutdown()
    }

    private fun previewOf(frame: Mat, quad: Quad, enhance: Boolean = false, look: (Mat) -> Unit = {}): ImageBitmap {
        var flat = Rectify.warp(frame, quad, maxSide = 320)
        look(flat)
        // Saved prints are shown the way they were kept: enhanced.
        val level = library.settings.now.newScanLook
        if (enhance && level != Enhance.OFF) flat = Enhance.apply(flat, level).also { flat.release() }
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

/** Under this, a print looks soft (focus or shake) and is worth retaking. */
const val SOFT_SHARPNESS = 60.0

/** Shots of a glare-free page. */
private const val SWEEP_SHOTS = 3

/** How far the prints must move between shots (share of the frame) for the reflections to move off them. */
private const val SWEEP_MOVE = 0.06f

/** A glare-free page finishes with the shots it has after this long. */
private const val SWEEP_TIMEOUT_MS = 30_000L

/** A print with more than this share of blown-out, colourless spots has glare on it. */
private const val GLARE_SHARE = 0.01f
