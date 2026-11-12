package io.github.darkxace01.attic.scan

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.darkxace01.attic.ui.OutlineEditor
import io.github.darkxace01.attic.ui.PrimaryButton
import io.github.darkxace01.attic.ui.QuietButton
import io.github.darkxace01.attic.ui.theme.Palette
import io.github.darkxace01.attic.vision.Pt
import io.github.darkxace01.attic.vision.Quad
import kotlinx.coroutines.launch
import kotlin.math.min

/**
 * The shot just taken, with the outline of every print found on it, to check before saving: drag a
 * corner or a side to fix an outline, add one the finder missed, remove one that isn't a photo.
 */
@Composable
fun ReviewScreen(review: ReviewUi, vm: ScanViewModel) {
    var quads by remember(review) { mutableStateOf(review.quads) }
    var selected by remember(review) { mutableIntStateOf(if (review.quads.size == 1) 0 else -1) }
    var snapping by remember(review) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    BackHandler { vm.retake() }

    val w = review.image.width.toFloat()
    val h = review.image.height.toFloat()
    val n = quads.size

    Column(
        Modifier
            .fillMaxSize()
            .background(Palette.Ground)
            .statusBarsPadding()
            .navigationBarsPadding(),
    ) {
        Column(Modifier.padding(horizontal = 20.dp, vertical = 12.dp)) {
            Text(
                when {
                    review.back -> if (n == 0) "Couldn't find the back" else "The back of the photo"
                    n == 0 -> "No photos found"
                    n == 1 -> "1 photo found"
                    else -> "$n photos found"
                },
                style = MaterialTheme.typography.titleMedium,
                color = Palette.Ink,
            )
            Text(
                when {
                    n == 0 -> "Add an outline for each photo and drag it into place, or take it again."
                    selected >= 0 -> "Drag a corner or a side onto the photo's edge. Missed one? Add a photo."
                    else -> "Tap an outline to fix it, or add one for a photo that was missed."
                },
                style = MaterialTheme.typography.bodyMedium,
                color = Palette.Muted,
            )
        }

        Box(
            Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(horizontal = 12.dp),
        ) {
            OutlineEditor(
                review.image, quads, selected,
                onSelect = { selected = it },
                onChange = { i, q -> quads = quads.toMutableList().also { it[i] = q } },
                modifier = Modifier.fillMaxSize(),
            )
        }

        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (!review.back || n == 0) {
                QuietButton("Add a photo", onClick = {
                    // A print-shaped outline in the middle, to drag onto the one that was missed.
                    val side = min(w, h) * 0.4f
                    val c = Pt(w / 2, h / 2)
                    val qw = side; val qh = side * 4f / 3f
                    quads = quads + Quad(Pt(c.x - qw / 2, c.y - qh / 2), Pt(c.x + qw / 2, c.y - qh / 2), Pt(c.x + qw / 2, c.y + qh / 2), Pt(c.x - qw / 2, c.y + qh / 2))
                    selected = quads.lastIndex
                })
            }
            val s = selected
            if (s in quads.indices) {
                QuietButton(if (snapping) "Snapping…" else "Snap to edges", onClick = {
                    if (snapping) return@QuietButton
                    snapping = true
                    scope.launch {
                        val snapped = vm.snapInReview(quads[s])
                        if (s in quads.indices) quads = quads.toMutableList().also { it[s] = snapped }
                        snapping = false
                    }
                })
                QuietButton("Remove", onClick = {
                    quads = quads.filterIndexed { i, _ -> i != s }
                    selected = -1
                })
            }
        }

        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = 8.dp, end = 16.dp, top = 4.dp, bottom = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            QuietButton("Retake", onClick = vm::retake)
            Spacer(Modifier.width(8.dp))
            PrimaryButton(
                when {
                    review.back -> "Save the back"
                    n == 1 -> "Save 1 photo"
                    else -> "Save $n photos"
                },
                enabled = n > 0,
                modifier = Modifier.weight(1f),
                onClick = { vm.saveReview(quads) },
            )
        }
        Spacer(Modifier.height(4.dp))
    }
}
