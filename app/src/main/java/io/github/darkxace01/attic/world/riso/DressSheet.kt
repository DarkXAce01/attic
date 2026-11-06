package io.github.darkxace01.attic.world.riso

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import io.github.darkxace01.attic.library
import io.github.darkxace01.attic.ui.Chip
import io.github.darkxace01.attic.ui.QuietButton
import io.github.darkxace01.attic.ui.theme.Ink
import io.github.darkxace01.attic.ui.theme.Palette

/**
 * Make it yours: what an album's island is, its two inks, how its title is set, what's in the air round
 * it, and which photo leads. Every choice shows at once on the island at the top, and is kept as made.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DressSheet(
    albumId: Long,
    title: String,
    meta: String,
    start: IslandLook,
    photos: List<ImageBitmap>,
    time: () -> Float,
    onChange: (IslandLook) -> Unit,
    onDismiss: () -> Unit,
) {
    val library = LocalContext.current.library
    val prints by remember(albumId) { library.printsIn(albumId) }.collectAsStateWithLifecycle(emptyList())
    var look by remember { mutableStateOf(start) }
    fun set(l: IslandLook) { look = l; onChange(l) }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = Palette.Ground, dragHandle = { io.github.darkxace01.attic.ui.theme.PencilHandle() }, scrimColor = Palette.Ink.copy(alpha = 0.4f)) {
        Column(Modifier.verticalScroll(rememberScrollState()).navigationBarsPadding().padding(bottom = 20.dp)) {
            Row(Modifier.padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Make it yours", style = MaterialTheme.typography.titleLarge, color = Palette.Ink, modifier = Modifier.weight(1f))
                QuietButton("Surprise me", onClick = {
                    val k = IslandKind.entries.random(); val (a, b) = IslandLook.PAIRS.random()
                    set(look.copy(kind = k, a = a, b = b, extra = Extra.entries.random(), title = TitleStyle.entries.random()))
                })
            }
            Box(Modifier.fillMaxWidth().height(250.dp)) {
                Canvas(Modifier.fillMaxWidth().height(250.dp)) {
                    drawIsland(look, photos, Offset(size.width * 0.36f, size.height * 0.5f), size.width * 0.21f, time(), 1)
                }
                AlbumTitle(title, meta, look.title, Modifier.align(Alignment.BottomEnd).padding(20.dp), alignEnd = true)
            }

            Heading("Island")
            LazyRow(contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                items(IslandKind.entries) { k ->
                    val on = k == look.kind
                    Column(
                        Modifier
                            .clip(RoundedCornerShape(18.dp))
                            .background(if (on) Palette.Surface else Palette.Ground)
                            .border(2.dp, if (on) Palette.Ink else Palette.Ground, RoundedCornerShape(18.dp))
                            .clickable { set(look.copy(kind = k)) }
                            .padding(6.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Canvas(Modifier.size(96.dp)) {
                            drawIsland(look.copy(kind = k, extra = Extra.None), photos, Offset(size.width / 2, size.height * 0.52f), size.width * 0.34f, time(), k.ordinal)
                        }
                        Text(k.label, style = MaterialTheme.typography.labelMedium, color = Palette.Ink)
                    }
                }
            }

            Heading("First ink")
            InkRow(look.a) { set(look.copy(a = it)) }
            Heading("Second ink")
            InkRow(look.b) { set(look.copy(b = it)) }

            Heading("Title")
            ChipRow(TitleStyle.entries.map { it.label }, look.title.ordinal) { set(look.copy(title = TitleStyle.entries[it])) }

            Heading("In the air")
            ChipRow(Extra.entries.map { it.label }, look.extra.ordinal) { set(look.copy(extra = Extra.entries[it])) }

            if (prints.isNotEmpty()) {
                Heading("Leading photo")
                LazyRow(contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(prints.take(60), key = { it.id }) { p ->
                        val on = (look.cover ?: prints.first().id) == p.id
                        AsyncImage(
                            library.displayFile(p), null, contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .size(64.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .border(3.dp, if (on) Palette.Accent else Palette.Ground, RoundedCornerShape(8.dp))
                                .clickable { set(look.copy(cover = p.id)) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun Heading(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, color = Palette.Ink, modifier = Modifier.padding(start = 20.dp, top = 18.dp, bottom = 8.dp))
}

@Composable
private fun InkRow(chosen: Ink, onPick: (Ink) -> Unit) {
    LazyRow(contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        items(Ink.entries) { ink ->
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.clickable { onPick(ink) }) {
                Box(
                    Modifier
                        .size(44.dp)
                        .border(3.dp, if (ink == chosen) Palette.Ink else Palette.Ground, CircleShape)
                        .padding(5.dp)
                        .clip(CircleShape)
                        .background(ink.color),
                )
                Spacer(Modifier.height(4.dp))
                Text(ink.label, style = MaterialTheme.typography.labelMedium, color = if (ink == chosen) Palette.Ink else Palette.Muted)
            }
        }
    }
}

@Composable
private fun ChipRow(labels: List<String>, chosen: Int, onPick: (Int) -> Unit) {
    LazyRow(contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(labels.size) { i -> Chip(labels[i], selected = i == chosen) { onPick(i) } }
    }
}
