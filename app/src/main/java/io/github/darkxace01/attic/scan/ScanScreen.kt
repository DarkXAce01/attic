package io.github.darkxace01.attic.scan

import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import io.github.darkxace01.attic.library
import io.github.darkxace01.attic.ui.PageSheet
import io.github.darkxace01.attic.ui.signature.GreasePencilLoop
import io.github.darkxace01.attic.ui.theme.Fonts
import io.github.darkxace01.attic.ui.theme.Palette
import kotlin.math.abs

/** The frame round the camera: Riso black, so the picture is the brightest thing on screen. */
private val Frame = Palette.Ink
/** Paper-coloured text on the frame. */
private val OnFrame = Palette.Ground
private val OnFrameQuiet = Palette.Ground.copy(alpha = 0.55f)

/** The three ways of scanning, like a camera's photo, video and portrait. */
private enum class Mode(val label: String) { Prints("Prints"), GlareFree("Glare-free"), Film("Film") }

/**
 * Scanning, as calm as a camera app: the viewfinder, the album it goes into, one line of modes above
 * the shutter. Everything else (taking it by itself, checking each shot, files) is one tap away.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScanScreen(vm: ScanViewModel, onOpenAlbum: (Long) -> Unit, onPickAlbum: () -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    val haptics = LocalHapticFeedback.current
    val flash = remember { Animatable(0f) }
    var pageSheet by remember { mutableStateOf<Pair<Long, Int>?>(null) }
    var more by remember { mutableStateOf(false) }
    // Nothing is taken while a sheet covers the camera.
    LaunchedEffect(pageSheet, more) { vm.setPaused(pageSheet != null || more) }
    pageSheet?.let { (album, page) -> PageSheet(album, page, onDismiss = { pageSheet = null }) }
    val pickFiles = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        vm.importImages(uris)
    }

    LaunchedEffect(state.shutterTick) {
        if (state.shutterTick == 0L) return@LaunchedEffect
        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
        flash.snapTo(0.5f)
        flash.animateTo(0f, tween(240))
    }
    val mode = when { state.negatives -> Mode.Film; state.glareFree -> Mode.GlareFree; else -> Mode.Prints }
    fun setMode(m: Mode) {
        if (m == mode) return
        vm.setNegatives(m == Mode.Film)
        vm.setGlareFree(m == Mode.GlareFree)
    }
    val ready = state.outlines.isNotEmpty() && state.outlines.all { it.locked }

    Box(Modifier.fillMaxSize().background(Frame)) {
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            // Which album: its name, small, at the top. Tap to switch or start one.
            Row(Modifier.fillMaxWidth().height(52.dp).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Row(
                    Modifier.weight(1f, fill = false).clip(RoundedCornerShape(12.dp)).clickable(onClick = onPickAlbum).padding(horizontal = 8.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        state.albumTitle.ifEmpty { "Choose an album" }, color = OnFrame, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.titleMedium, modifier = Modifier.widthIn(max = 230.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Chevron()
                }
                Spacer(Modifier.weight(1f))
                MoreButton { more = true }
            }

            // The viewfinder.
            Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                Box(Modifier.aspectRatio(3f / 4f, matchHeightConstraintsFirst = true).fillMaxHeight().clip(RoundedCornerShape(18.dp))) {
                    CameraPreview(vm, Modifier.fillMaxSize())
                    Outlines(state.outlines, Modifier.fillMaxSize())
                    if (flash.value > 0f) Box(Modifier.fillMaxSize().background(Color.White.copy(alpha = flash.value)))
                    // One back to add, or a way out of adding it: quiet, at the bottom of the picture.
                    val action = when {
                        state.backFor != null -> "Not now" to vm::cancelBack
                        state.lastSavedIds.size == 1 && state.sweep == null && !state.saving && !state.justSavedBack ->
                            "Add the back of this photo" to { vm.scanBackOf(state.lastSavedIds.single()) }
                        else -> null
                    }
                    action?.let { (text, go) ->
                        Text(
                            text, style = MaterialTheme.typography.labelLarge, color = Color.White,
                            modifier = Modifier.align(Alignment.BottomCenter).padding(12.dp).clip(RoundedCornerShape(50))
                                .background(Color.Black.copy(alpha = 0.5f)).clickable(onClick = go).padding(horizontal = 14.dp, vertical = 8.dp),
                        )
                    }
                }
            }

            // What's happening, in one quiet line under the picture.
            Row(Modifier.fillMaxWidth().height(40.dp).padding(horizontal = 24.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
                if (ready || state.error != null) {
                    Box(Modifier.size(7.dp).clip(CircleShape).background(if (state.error != null) Palette.Warning else Palette.Accent))
                    Spacer(Modifier.width(8.dp))
                }
                Text(statusLine(state), style = MaterialTheme.typography.bodyMedium, color = OnFrameQuiet, maxLines = 2, textAlign = TextAlign.Center)
            }

            ModeLine(mode, ::setMode)

            Box(Modifier.fillMaxWidth().height(112.dp).padding(horizontal = 24.dp).navigationBarsPadding()) {
                AlbumPile(state.albumId, state.justSaved, { onOpenAlbum(state.albumId) }, Modifier.align(Alignment.CenterStart))
                Shutter(enabled = !state.saving, ready = ready, progress = state.readyProgress, onClick = vm::capture, modifier = Modifier.align(Alignment.Center))
                if (state.lastPage > 0 && state.sweep == null) {
                    Text(
                        "Details", style = MaterialTheme.typography.labelLarge, color = OnFrame,
                        modifier = Modifier.align(Alignment.CenterEnd).clip(RoundedCornerShape(50)).clickable { pageSheet = state.lastAlbumId to state.lastPage }
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                    )
                }
            }
        }
        // Over the camera, which stays ready for a retake.
        state.review?.let { ReviewScreen(it, vm) }
    }

    if (more) {
        ModalBottomSheet(onDismissRequest = { more = false }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = Palette.Ground) {
            Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 24.dp).navigationBarsPadding()) {
                Text("Camera", style = MaterialTheme.typography.titleLarge, color = Palette.Ink)
                Spacer(Modifier.height(8.dp))
                SheetToggle("Take it by itself", "Once every photo in view is found, hold still and it takes the shot.", state.autoCapture) { vm.setAutoCapture(it) }
                SheetToggle("Check each shot", "See what was found before it's saved, and fix any outline.", state.checkEachShot) { vm.setCheckEachShot(it) }
                Spacer(Modifier.height(8.dp))
                Text(
                    "Bring in scans or photos from files", style = MaterialTheme.typography.titleMedium, color = Palette.Ink,
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { more = false; pickFiles.launch(arrayOf("image/*")) }.padding(vertical = 14.dp),
                )
            }
        }
    }
}

@Composable
private fun SheetToggle(title: String, detail: String, on: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onChange(!on) }.padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = Palette.Ink)
            Text(detail, style = MaterialTheme.typography.bodyMedium, color = Palette.Muted)
        }
        Spacer(Modifier.width(12.dp))
        Switch(on, onChange, colors = SwitchDefaults.colors(checkedTrackColor = Palette.Accent, checkedThumbColor = Palette.OnAccent, uncheckedTrackColor = Palette.SurfaceHigh, uncheckedThumbColor = Palette.Muted, uncheckedBorderColor = Palette.SurfaceHigh))
    }
}

/** A small hand-drawn chevron after the album's name. */
@Composable
private fun Chevron() {
    Canvas(Modifier.size(12.dp, 8.dp)) {
        val w = 2.dp.toPx()
        drawLine(OnFrameQuiet, Offset(w, w), Offset(size.width / 2, size.height - w), w, StrokeCap.Round)
        drawLine(OnFrameQuiet, Offset(size.width / 2, size.height - w), Offset(size.width - w, w), w, StrokeCap.Round)
    }
}

/** Three dots: the camera's other settings. */
@Composable
private fun MoreButton(onClick: () -> Unit) {
    Box(Modifier.size(44.dp).clip(CircleShape).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(20.dp, 4.dp)) {
            for (k in 0..2) drawCircle(OnFrame, 2.dp.toPx(), Offset(size.width * (0.1f + 0.4f * k), size.height / 2))
        }
    }
}

/** The modes in a line, the chosen one centred and underlined in pencil. Tap one, or swipe along. */
@Composable
private fun ModeLine(mode: Mode, onMode: (Mode) -> Unit) {
    var drag by remember { mutableStateOf(0f) }
    Row(
        Modifier
            .fillMaxWidth()
            .height(40.dp)
            .pointerInput(mode) {
                detectHorizontalDragGestures(onDragEnd = {
                    val i = mode.ordinal + if (drag < -40f) 1 else if (drag > 40f) -1 else 0
                    Mode.entries.getOrNull(i)?.let(onMode)
                    drag = 0f
                }) { _, d -> drag += d }
            },
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        for (m in Mode.entries) {
            val on = m == mode
            Column(Modifier.clip(RoundedCornerShape(8.dp)).clickable { onMode(m) }.padding(horizontal = 14.dp, vertical = 4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    m.label, color = if (on) OnFrame else OnFrameQuiet,
                    style = MaterialTheme.typography.labelLarge.copy(fontWeight = if (on) FontWeight.Bold else FontWeight.Medium),
                )
                Canvas(Modifier.size(28.dp, 6.dp)) {
                    if (on) drawLine(Palette.Accent, Offset(2f, size.height * 0.7f), Offset(size.width - 2f, size.height * 0.35f), 2.5.dp.toPx(), StrokeCap.Round)
                }
            }
        }
    }
}

private fun statusLine(s: ScanUiState): String {
    val n = s.outlines.size
    val photos = if (n == 1) "1 photo" else "$n photos"
    val sweep = s.sweep
    return when {
        s.importing != null -> "Importing ${s.importing.first + 1} of ${s.importing.second}…".takeIf { s.importing.first < s.importing.second } ?: "Importing…"
        s.error != null -> s.error
        s.saving -> if (s.backFor != null) "Saving the back…" else if (s.glareFree) "Removing glare…" else "Saving…"
        s.justSavedBack -> "Added the back."
        s.backFor != null -> if (n == 0) "Turn the photo over and put it in view." else "Hold still to take the back."
        sweep != null -> when (sweep.hint) {
            SweepHint.MoveLeft -> "Shot ${sweep.taken} of ${sweep.of}. Move the phone a little to the left, then hold."
            SweepHint.MoveRight -> "Shot ${sweep.taken} of ${sweep.of}. Now a little to the right, then hold."
            SweepHint.Further -> "A little further, then hold."
            SweepHint.KeepInView -> "Keep every photo in view."
        }
        s.justSavedSoft > 0 -> if (s.justSavedSoft == 1) "1 photo came out soft. Hold steadier and scan again." else "${s.justSavedSoft} photos came out soft. Hold steadier and scan again."
        s.justSaved > 0 -> (if (s.justSaved == 1) "Saved 1 photo to page ${s.albumPages}." else "Saved ${s.justSaved} photos to page ${s.albumPages}.") +
            when (s.justSavedTwins) {
                0 -> ""
                1 -> if (s.justSaved == 1) " It looks like one you already have." else " 1 looks like one you already have."
                else -> " ${s.justSavedTwins} look like ones you already have."
            }
        s.waitingForNext -> "Done. Turn the page or move to the next photo."
        n == 0 && s.negatives -> "Lay the film on a lightbox (another phone on Lightbox works) and point at it."
        n == 0 -> if (s.glareFree) "Point at a page. You'll take it from a few angles." else "Point at a photo or an album page."
        s.glareSeen && !s.glareFree && s.readyProgress == 0f -> "Found $photos. There's glare; try Glare-free."
        s.outlines.all { it.locked } && !s.autoCapture -> if (s.checkEachShot) "Found $photos. Tap to take it and check." else "Found $photos. Tap to capture."
        s.outlines.all { it.locked } -> "Found $photos. Hold still…"
        else -> "Found $photos. Hold steady."
    }
}

@Composable
private fun CameraPreview(vm: ScanViewModel, modifier: Modifier) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val previewView = remember {
        PreviewView(context).apply {
            scaleType = PreviewView.ScaleType.FIT_CENTER
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        }
    }
    DisposableEffect(owner) {
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({ vm.bind(future.get(), owner, previewView) }, ContextCompat.getMainExecutor(context))
        onDispose { if (future.isDone) future.get().unbindAll() }
    }
    AndroidView(factory = { previewView }, modifier = modifier)
}

/**
 * The prints in view, marked the way you'd mark a contact sheet: grease-pencil corners, white while
 * it's still looking, pencil red once each one is held steady.
 */
@Composable
private fun Outlines(outlines: List<Outline>, modifier: Modifier) {
    Canvas(modifier) {
        for (o in outlines) {
            val pts = o.quad.points.map { Offset(it.x * size.width, it.y * size.height) }
            val ink = if (o.locked) Palette.Accent else Color.White.copy(alpha = 0.85f)
            val w = (if (o.locked) 4.dp else 3.dp).toPx()
            for (k in 0 until 4) {
                val c = pts[k]
                val len = 22.dp.toPx()
                fun toward(p: Offset): Offset {
                    val d = p - c
                    val n = d.getDistance().coerceAtLeast(1f)
                    return c + d * (minOf(len, n / 3) / n)
                }
                // A pencil corner overshoots a little past the turn, as a hand does.
                val back = c - (toward(pts[(k + 1) % 4]) - c) * 0.12f
                drawLine(ink, back, toward(pts[(k + 1) % 4]), w, StrokeCap.Round)
                drawLine(ink, c, toward(pts[(k + 3) % 4]), w, StrokeCap.Round)
            }
        }
    }
}

/** Paper ring, pencil-red middle; the ring fills while it counts down to taking the shot by itself. */
@Composable
private fun Shutter(enabled: Boolean, ready: Boolean, progress: Float, onClick: () -> Unit, modifier: Modifier) {
    Box(modifier.size(78.dp).clip(CircleShape).clickable(enabled = enabled, onClick = onClick), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val w = 3.5.dp.toPx()
            val inset = w / 2
            val arc = androidx.compose.ui.geometry.Size(size.width - w, size.height - w)
            drawArc(OnFrame.copy(alpha = if (enabled) (if (progress > 0f) 0.35f else 1f) else 0.4f), 0f, 360f, false, Offset(inset, inset), arc, style = Stroke(w))
            if (progress > 0f) drawArc(Palette.Accent, -90f, 360f * progress, false, Offset(inset, inset), arc, style = Stroke(w))
        }
        val inner by androidx.compose.animation.core.animateDpAsState(if (ready) 64.dp else 60.dp, label = "shutter")
        Box(Modifier.size(inner).clip(CircleShape).background(if (enabled) Palette.Accent else Palette.Muted))
    }
}

/**
 * The album so far, as its last few prints in a loose pile. Each time a page is saved the Grease
 * Pencil loop draws itself round the pile: the one moment in Attic that gets it. Tap to open the album.
 */
@Composable
private fun AlbumPile(albumId: Long, savedTick: Int, onClick: () -> Unit, modifier: Modifier) {
    val library = LocalContext.current.library
    val prints by remember(albumId) { library.printsIn(albumId) }.collectAsStateWithLifecycle(emptyList())
    val last = prints.takeLast(3)
    // A new count of prints is a save: the loop plays again.
    var loopKey by remember(albumId) { mutableStateOf(-1) }
    var showLoop by remember { mutableStateOf(false) }
    LaunchedEffect(prints.size) {
        if (loopKey >= 0 && prints.size > loopKey && savedTick > 0) {
            showLoop = true
            kotlinx.coroutines.delay(2200)
            showLoop = false
        }
        loopKey = prints.size
    }
    Box(modifier.size(64.dp), contentAlignment = Alignment.Center) {
      Box(Modifier.fillMaxSize().clip(RoundedCornerShape(12.dp)).clickable(enabled = albumId != 0L, onClick = onClick), contentAlignment = Alignment.Center) {
        if (last.isEmpty()) Box(Modifier.size(40.dp, 48.dp).clip(RoundedCornerShape(3.dp)).background(OnFrame.copy(alpha = 0.15f)))
        last.forEachIndexed { i, p ->
            // Newest on top and straight; older ones turned a little underneath.
            val turn = (last.size - 1 - i) * if (i % 2 == 0) -10f else 9f
            AsyncImage(
                model = coil3.request.ImageRequest.Builder(LocalContext.current).data(library.displayFile(p)).memoryCacheKey("${p.id}#${p.version}#thumb").size(160).build(),
                contentDescription = null, contentScale = ContentScale.Crop,
                modifier = Modifier.size(40.dp, 48.dp).graphicsLayer { rotationZ = turn }.background(Palette.PrintWhite).padding(2.dp),
            )
        }
      }
        AnimatedVisibility(showLoop, enter = fadeIn(tween(80)), exit = fadeOut(tween(400))) {
            GreasePencilLoop(Modifier.requiredSize(88.dp), color = Palette.Accent, strokeWidth = 0.05f, key = loopKey)
        }
    }
}
