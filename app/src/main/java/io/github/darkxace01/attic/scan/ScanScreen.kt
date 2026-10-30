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
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
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
import io.github.darkxace01.attic.ui.theme.Palette

@Composable
fun ScanScreen(vm: ScanViewModel, onOpenPrints: () -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    val haptics = LocalHapticFeedback.current
    val flash = remember { Animatable(0f) }

    LaunchedEffect(state.shutterTick) {
        if (state.shutterTick == 0L) return@LaunchedEffect
        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
        flash.snapTo(0.55f)
        flash.animateTo(0f, tween(260))
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(Palette.Ground)
            .systemBarsPadding(),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .height(56.dp)
                .padding(horizontal = 20.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Scan", style = MaterialTheme.typography.titleLarge, color = Palette.Ink)
            Spacer(Modifier.weight(1f))
            Text("Auto", style = MaterialTheme.typography.labelLarge, color = Palette.Muted)
            Spacer(Modifier.width(10.dp))
            Switch(
                checked = state.autoCapture,
                onCheckedChange = vm::setAutoCapture,
                colors = SwitchDefaults.colors(
                    checkedTrackColor = Palette.Accent,
                    checkedThumbColor = Palette.OnAccent,
                    uncheckedTrackColor = Palette.SurfaceHigh,
                    uncheckedThumbColor = Palette.Muted,
                    uncheckedBorderColor = Palette.SurfaceHigh,
                ),
            )
        }

        Box(
            Modifier
                .fillMaxWidth()
                .weight(1f),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                Modifier
                    .aspectRatio(3f / 4f, matchHeightConstraintsFirst = true)
                    .fillMaxHeight(),
            ) {
                CameraPreview(vm, Modifier.fillMaxSize())
                Outlines(state.outlines, Modifier.fillMaxSize())
                if (flash.value > 0f) {
                    Box(Modifier.fillMaxSize().background(Color.White.copy(alpha = flash.value)))
                }
            }
        }

        Text(
            text = statusLine(state),
            style = MaterialTheme.typography.bodyLarge,
            color = if (state.error != null) Palette.Warning else Palette.Ink,
            modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 14.dp, bottom = 10.dp),
        )

        PreviewStrip(state)

        Box(
            Modifier
                .fillMaxWidth()
                .height(112.dp)
                .padding(horizontal = 20.dp),
        ) {
            PrintsButton(state.savedTotal, onOpenPrints, Modifier.align(Alignment.CenterStart))
            Shutter(
                enabled = !state.saving,
                ready = state.outlines.isNotEmpty() && state.outlines.all { it.locked },
                onClick = vm::capture,
                modifier = Modifier.align(Alignment.Center),
            )
        }
    }
}

private fun statusLine(s: ScanUiState): String {
    val n = s.outlines.size
    val photos = if (n == 1) "1 photo" else "$n photos"
    return when {
        s.error != null -> s.error
        s.saving -> "Saving…"
        s.justSaved > 0 -> if (s.justSaved == 1) "Saved 1 photo" else "Saved ${s.justSaved} photos"
        s.waitingForNext -> "Done. Turn the page or move to the next photo."
        n == 0 -> "Point at a photo or an album page."
        s.outlines.all { it.locked } && !s.autoCapture -> "Found $photos. Tap to capture."
        s.outlines.all { it.locked } -> "Found $photos. Capturing…"
        else -> "Found $photos. Hold still."
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
        val stroke = Stroke(width = 2.5.dp.toPx())
        for (o in outlines) {
            val pts = o.quad.points.map { Offset(it.x * size.width, it.y * size.height) }
            val path = Path().apply {
                moveTo(pts[0].x, pts[0].y)
                for (p in pts.drop(1)) lineTo(p.x, p.y)
                close()
            }
            if (o.locked) {
                drawPath(path, Palette.Accent.copy(alpha = 0.14f))
                drawPath(path, Palette.Accent, style = stroke)
            } else {
                drawPath(path, Color.White.copy(alpha = 0.8f), style = stroke)
            }
        }
    }
}

/** What each print will look like once saved: flattened and straightened, live. */
@Composable
private fun PreviewStrip(state: ScanUiState) {
    LazyRow(
        Modifier
            .fillMaxWidth()
            .height(84.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 20.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        itemsIndexed(state.previews) { _, bitmap ->
            Image(
                bitmap = bitmap,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .height(76.dp)
                    .aspectRatio(bitmap.width.toFloat() / bitmap.height.coerceAtLeast(1))
                    .clip(RoundedCornerShape(2.dp)),
            )
        }
    }
}

@Composable
private fun Shutter(enabled: Boolean, ready: Boolean, onClick: () -> Unit, modifier: Modifier) {
    val ring = if (ready) Palette.Accent else Palette.Ink
    Box(
        modifier
            .size(76.dp)
            .border(3.dp, ring.copy(alpha = if (enabled) 1f else 0.4f), CircleShape)
            .padding(7.dp)
            .clip(CircleShape)
            .background(if (enabled) Palette.Ink else Palette.SurfaceHigh)
            .clickable(enabled = enabled, onClick = onClick),
    )
}

@Composable
private fun PrintsButton(count: Int, onClick: () -> Unit, modifier: Modifier) {
    Column(
        modifier
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 8.dp),
    ) {
        Text("$count", style = MaterialTheme.typography.titleMedium, color = Palette.Ink)
        Text(if (count == 1) "print" else "prints", style = MaterialTheme.typography.labelMedium, color = Palette.Muted)
    }
}
