package io.github.darkxace01.attic.scan

import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.darkxace01.attic.ui.PageSheet
import io.github.darkxace01.attic.ui.QuietButton
import io.github.darkxace01.attic.ui.theme.Palette
import androidx.compose.ui.graphics.StrokeCap
import io.github.darkxace01.attic.library
import coil3.compose.AsyncImage
import androidx.compose.foundation.layout.widthIn

@Composable
fun ScanScreen(vm: ScanViewModel, onOpenAlbum: (Long) -> Unit, onPickAlbum: () -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    val haptics = LocalHapticFeedback.current
    val flash = remember { Animatable(0f) }
    var pageSheet by remember { mutableStateOf<Pair<Long, Int>?>(null) }
    // Nothing is taken while the sheet covers the camera.
    LaunchedEffect(pageSheet) { vm.setPaused(pageSheet != null) }
    pageSheet?.let { (album, page) -> PageSheet(album, page, onDismiss = { pageSheet = null }) }
    val pickFiles = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        vm.importImages(uris)
    }

    LaunchedEffect(state.shutterTick) {
        if (state.shutterTick == 0L) return@LaunchedEffect
        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
        flash.snapTo(0.55f)
        flash.animateTo(0f, tween(260))
    }

    Box(Modifier.fillMaxSize().background(Palette.Ground)) {
    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        // The camera, edge to edge, with what you need floating over it.
        Box(
            Modifier
                .fillMaxWidth()
                .weight(1f),
            contentAlignment = Alignment.TopCenter,
        ) {
            Box(
                Modifier
                    .aspectRatio(3f / 4f, matchHeightConstraintsFirst = true)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(bottomStart = 20.dp, bottomEnd = 20.dp)),
            ) {
                CameraPreview(vm, Modifier.fillMaxSize())
                Outlines(state.outlines, Modifier.fillMaxSize())
                if (flash.value > 0f) {
                    Box(Modifier.fillMaxSize().background(Color.White.copy(alpha = flash.value)))
                }
            }

            Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 12.dp)) {
                // Which album the next page goes into. Tap to switch or start a new one.
                AlbumPill(state, onPickAlbum)
                Spacer(Modifier.height(8.dp))
                // Check: each shot's outlines are checked before saving. Glare-free: each page from a few
                // angles. Auto: takes the shot by itself once you hold still.
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    ModeChip("Auto", state.autoCapture) { vm.setAutoCapture(!state.autoCapture) }
                    ModeChip("Check", state.checkEachShot) { vm.setCheckEachShot(!state.checkEachShot) }
                    ModeChip("Glare-free", state.glareFree) { vm.setGlareFree(!state.glareFree) }
                    // Scans from a flatbed, or photos of prints already on the phone.
                    ModeChip("From files", false) { pickFiles.launch(arrayOf("image/*")) }
                }
            }

            // What's happening, over the bottom of the camera.
            Column(
                Modifier.align(Alignment.BottomCenter).padding(bottom = 14.dp, start = 16.dp, end = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                StatusPill(statusLine(state), warning = state.error != null, ready = state.outlines.isNotEmpty() && state.outlines.all { it.locked })
                // One loose print just saved: its back can come next. Taking a back: a way out.
                when {
                    state.backFor != null -> FloatingAction("Not now", vm::cancelBack)
                    state.lastSavedIds.size == 1 && state.sweep == null && !state.saving && !state.justSavedBack ->
                        FloatingAction("Add the back of this photo") { vm.scanBackOf(state.lastSavedIds.single()) }
                }
            }
        }

        PreviewStrip(state)

        Box(
            Modifier
                .fillMaxWidth()
                .height(104.dp)
                .padding(horizontal = 20.dp),
        ) {
            AlbumStack(state.albumId, state.albumPrints, { onOpenAlbum(state.albumId) }, Modifier.align(Alignment.CenterStart))
            // When, who and where for the page just saved. Never in the way: scanning goes on without it.
            if (state.lastPage > 0 && state.sweep == null) {
                Column(
                    Modifier
                        .align(Alignment.CenterEnd)
                        .clip(RoundedCornerShape(12.dp))
                        .background(Palette.Surface)
                        .clickable { pageSheet = state.lastAlbumId to state.lastPage }
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    horizontalAlignment = Alignment.End,
                ) {
                    Text("Details", style = MaterialTheme.typography.titleSmall, color = Palette.Ink)
                    Text("Page ${state.lastPage}", style = MaterialTheme.typography.labelMedium, color = Palette.Muted)
                }
            }
            Shutter(
                enabled = !state.saving,
                ready = state.outlines.isNotEmpty() && state.outlines.all { it.locked },
                progress = state.readyProgress,
                onClick = vm::capture,
                modifier = Modifier.align(Alignment.Center),
            )
        }
    }
    // Over the camera, which stays ready for a retake.
    state.review?.let { ReviewScreen(it, vm) }
    }
}

/** The album being scanned into, as a chip over the camera: its name and the page coming next. */
@Composable
private fun AlbumPill(state: ScanUiState, onClick: () -> Unit) {
    Row(
        Modifier
            .clip(RoundedCornerShape(14.dp))
            .background(OVER_CAMERA)
            .clickable(onClick = onClick)
            .padding(start = 12.dp, end = 14.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column {
            Text(
                state.albumTitle.ifEmpty { " " },
                style = MaterialTheme.typography.titleMedium,
                color = Color.White,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 240.dp),
            )
            Text(
                if (state.albumPages == 0) "Ready for the first page" else "Next is page ${state.albumPages + 1}",
                style = MaterialTheme.typography.labelMedium,
                color = Color.White.copy(alpha = 0.7f),
            )
        }
        Spacer(Modifier.width(10.dp))
        Text("Change", style = MaterialTheme.typography.labelLarge, color = Color.White.copy(alpha = 0.7f))
    }
}

/** The status line, floating over the camera; the dot goes red when every photo in view is locked. */
@Composable
private fun StatusPill(text: String, warning: Boolean, ready: Boolean) {
    Row(
        Modifier
            .clip(RoundedCornerShape(50))
            .background(OVER_CAMERA)
            .padding(horizontal = 14.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(if (warning) Palette.Warning else if (ready) Palette.Accent else Color.White.copy(alpha = 0.5f)),
        )
        Spacer(Modifier.width(10.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, color = Color.White)
    }
}

@Composable
private fun FloatingAction(text: String, onClick: () -> Unit) {
    Spacer(Modifier.height(8.dp))
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = Color.White,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(OVER_CAMERA)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
    )
}

/** Dark enough to read white on over any picture, without hiding it. */
private val OVER_CAMERA = Color.Black.copy(alpha = 0.55f)

/** A mode that's on or off: white when on, see-through when off. */
@Composable
private fun ModeChip(text: String, on: Boolean, onClick: () -> Unit) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = if (on) Palette.Ground else Color.White.copy(alpha = 0.8f),
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(if (on) Color.White else OVER_CAMERA)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
    )
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

@Composable
private fun Outlines(outlines: List<Outline>, modifier: Modifier) {
    Canvas(modifier) {
        for (o in outlines) {
            val pts = o.quad.points.map { Offset(it.x * size.width, it.y * size.height) }
            val path = Path().apply {
                moveTo(pts[0].x, pts[0].y)
                for (p in pts.drop(1)) lineTo(p.x, p.y)
                close()
            }
            val ink = if (o.locked) Palette.Accent else Color.White
            if (o.locked) drawPath(path, Palette.Accent.copy(alpha = 0.14f))
            drawPath(path, ink.copy(alpha = if (o.locked) 0.9f else 0.55f), style = Stroke(width = 1.5.dp.toPx()))
            // Heavy corners, like the marks on a cutting mat: where the print will be cut.
            val tick = Stroke(width = 4.dp.toPx(), cap = StrokeCap.Round)
            for (k in 0 until 4) {
                val c = pts[k]
                val next = pts[(k + 1) % 4]
                val prev = pts[(k + 3) % 4]
                val len = 18.dp.toPx()
                fun toward(p: Offset): Offset {
                    val d = p - c
                    val n = d.getDistance().coerceAtLeast(1f)
                    return c + d * (minOf(len, n / 3) / n)
                }
                drawLine(ink, c, toward(next), tick.width, StrokeCap.Round)
                drawLine(ink, c, toward(prev), tick.width, StrokeCap.Round)
            }
        }
    }
}

/**
 * What each print will look like once saved, flattened and straightened, live. Right after a capture
 * it shows the real saved crops instead.
 */
@Composable
private fun PreviewStrip(state: ScanUiState) {
    val saved = state.lastSaved
    LazyRow(
        Modifier
            .fillMaxWidth()
            .height(84.dp),
        contentPadding = PaddingValues(horizontal = 20.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (saved.isNotEmpty()) {
            items(saved.size, key = { "saved$it" }) { i -> PrintThumb(saved[i], highlighted = true) }
        } else {
            items(state.previews, key = { it.id }) { p -> PrintThumb(p.image, highlighted = p.locked) }
        }
    }
}

@Composable
private fun PrintThumb(bitmap: ImageBitmap, highlighted: Boolean) {
    Image(
        bitmap = bitmap,
        contentDescription = null,
        contentScale = ContentScale.Fit,
        modifier = Modifier
            .height(76.dp)
            .aspectRatio(bitmap.width.toFloat() / bitmap.height.coerceAtLeast(1))
            .clip(RoundedCornerShape(2.dp))
            .graphicsLayer { alpha = if (highlighted) 1f else 0.6f },
    )
}

@Composable
private fun Shutter(enabled: Boolean, ready: Boolean, progress: Float, onClick: () -> Unit, modifier: Modifier) {
    val ring = if (ready) Palette.Accent else Palette.Ink
    Box(
        modifier
            .size(76.dp)
            .clip(CircleShape)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        // The ring fills while auto-capture counts down, so you know to keep holding still.
        Canvas(Modifier.fillMaxSize()) {
            val w = 3.dp.toPx()
            val inset = w / 2
            val arcSize = androidx.compose.ui.geometry.Size(size.width - w, size.height - w)
            drawArc(
                color = ring.copy(alpha = if (enabled) (if (progress > 0f) 0.3f else 1f) else 0.4f),
                startAngle = 0f, sweepAngle = 360f, useCenter = false,
                topLeft = Offset(inset, inset), size = arcSize, style = Stroke(w),
            )
            if (progress > 0f) {
                drawArc(
                    color = Palette.Accent,
                    startAngle = -90f, sweepAngle = 360f * progress, useCenter = false,
                    topLeft = Offset(inset, inset), size = arcSize, style = Stroke(w),
                )
            }
        }
        Box(
            Modifier
                .size(62.dp)
                .clip(CircleShape)
                .background(if (enabled) Palette.Ink else Palette.SurfaceHigh),
        )
    }
}

/**
 * The album so far, as its last few prints in a little loose pile. Tap to open the album. The whole
 * pile and the count under it are one big target.
 */
@Composable
private fun AlbumStack(albumId: Long, count: Int, onClick: () -> Unit, modifier: Modifier) {
    val library = LocalContext.current.library
    val prints by remember(albumId) { library.printsIn(albumId) }.collectAsStateWithLifecycle(emptyList())
    val last = prints.takeLast(3)
    Column(
        modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable(enabled = albumId != 0L, onClick = onClick)
            .padding(6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.size(56.dp), contentAlignment = Alignment.Center) {
            if (last.isEmpty()) {
                Box(Modifier.size(44.dp, 52.dp).clip(RoundedCornerShape(4.dp)).background(Palette.SurfaceHigh))
            }
            last.forEachIndexed { i, p ->
                // Newest on top and straight; older ones turned a little underneath.
                val turn = (last.size - 1 - i) * if (i % 2 == 0) -9f else 8f
                AsyncImage(
                    model = coil3.request.ImageRequest.Builder(LocalContext.current)
                        .data(library.displayFile(p))
                        .memoryCacheKey("${p.id}#${p.version}#thumb")
                        .size(160)
                        .build(),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(42.dp, 52.dp)
                        .graphicsLayer { rotationZ = turn; shadowElevation = 4.dp.toPx() }
                        .background(Color(0xFFF4F1EA))
                        .padding(2.dp),
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(if (count == 1) "1 photo" else "$count photos", style = MaterialTheme.typography.labelMedium, color = Palette.Muted)
    }
}
