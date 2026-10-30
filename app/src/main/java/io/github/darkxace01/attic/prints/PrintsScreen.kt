package io.github.darkxace01.attic.prints

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.size.Size
import io.github.darkxace01.attic.store.SavedPrint
import io.github.darkxace01.attic.ui.theme.Palette
import java.util.Locale

@Composable
fun PrintsScreen(prints: List<SavedPrint>, onBack: () -> Unit, onOpen: (SavedPrint) -> Unit) {
    BackHandler(onBack = onBack)
    Column(
        Modifier
            .fillMaxSize()
            .background(Palette.Ground)
            .systemBarsPadding(),
    ) {
        TopBar("Prints", onBack) {
            Text("${prints.size}", style = MaterialTheme.typography.bodyLarge, color = Palette.Muted)
        }
        if (prints.isEmpty()) {
            Text(
                "Nothing scanned yet. Point the camera at a photo and it'll appear here.",
                style = MaterialTheme.typography.bodyLarge,
                color = Palette.Muted,
                modifier = Modifier.padding(20.dp),
            )
            return@Column
        }
        LazyVerticalGrid(
            columns = GridCells.Adaptive(112.dp),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(prints, key = { it.file.path }) { p ->
                Box(
                    Modifier
                        .aspectRatio(1f)
                        .background(Palette.Surface)
                        .clickable { onOpen(p) }
                        .padding(6.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    AsyncImage(
                        model = p.file,
                        contentDescription = null,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }
    }
}

@Composable
fun PrintViewer(print: SavedPrint, onBack: () -> Unit, onDelete: (SavedPrint) -> Unit) {
    BackHandler(onBack = onBack)
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    val transform = rememberTransformableState { _, zoom, pan, _ ->
        scale = (scale * zoom).coerceIn(1f, 12f)
        offset = if (scale == 1f) Offset.Zero else offset + pan
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(Palette.Ground)
            .systemBarsPadding(),
    ) {
        TopBar("Print", onBack) {
            TextButton(onClick = { onDelete(print) }) {
                Text("Delete", color = Palette.Warning, style = MaterialTheme.typography.labelLarge)
            }
        }
        Box(
            Modifier
                .fillMaxWidth()
                .weight(1f)
                .transformable(transform)
                .pointerInput(Unit) {
                    detectTapGestures(onDoubleTap = {
                        if (scale > 1f) { scale = 1f; offset = Offset.Zero } else scale = 3f
                    })
                },
        ) {
            AsyncImage(
                // Full size, so zooming in shows the real detail of the scan.
                model = ImageRequest.Builder(LocalContext.current).data(print.file).size(Size.ORIGINAL).build(),
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        scaleX = scale; scaleY = scale
                        translationX = offset.x; translationY = offset.y
                    },
            )
        }
        Column(Modifier.padding(20.dp)) {
            val mp = print.width.toLong() * print.height / 1_000_000.0
            Text(
                "${print.width} × ${print.height} px · ${String.format(Locale.US, "%.1f", mp)} MP",
                style = MaterialTheme.typography.bodyLarge,
                color = Palette.Ink,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                sharpnessLabel(print.sharpness),
                style = MaterialTheme.typography.bodyMedium,
                color = if (print.sharpness < SOFT) Palette.Warning else Palette.Muted,
            )
        }
    }
}

private const val SOFT = 60.0

private fun sharpnessLabel(v: Double): String {
    val n = v.toInt()
    return when {
        v < SOFT -> "Sharpness $n: soft, worth retaking"
        v < 150 -> "Sharpness $n: fine"
        else -> "Sharpness $n: sharp"
    }
}

@Composable
private fun TopBar(title: String, onBack: () -> Unit, trailing: @Composable () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(56.dp)
            .padding(start = 4.dp, end = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Palette.Ink)
        }
        Text(title, style = MaterialTheme.typography.titleLarge, color = Palette.Ink)
        Spacer(Modifier.weight(1f))
        trailing()
    }
}
