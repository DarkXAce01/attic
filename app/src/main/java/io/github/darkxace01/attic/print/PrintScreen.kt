package io.github.darkxace01.attic.print

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.size.Size
import io.github.darkxace01.attic.data.PrintEntity
import io.github.darkxace01.attic.library
import io.github.darkxace01.attic.scan.SOFT_SHARPNESS
import io.github.darkxace01.attic.ui.Chip
import io.github.darkxace01.attic.ui.DateSheet
import io.github.darkxace01.attic.ui.PrimaryButton
import io.github.darkxace01.attic.ui.QuietButton
import io.github.darkxace01.attic.ui.TextSheet
import io.github.darkxace01.attic.ui.TopBar
import io.github.darkxace01.attic.ui.fieldColors
import io.github.darkxace01.attic.ui.theme.Palette
import io.github.darkxace01.attic.vision.Enhance
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale

private enum class Editing { None, Date, People, Place, Caption }

@Composable
fun PrintScreen(printId: Long, onBack: () -> Unit, onFixCrop: (Long) -> Unit) {
    val library = LocalContext.current.library
    val print by remember(printId) { library.print(printId) }.collectAsStateWithLifecycle(null)
    val p = print ?: return
    val album by remember(p.albumId) { library.album(p.albumId) }.collectAsStateWithLifecycle(null)
    val people by remember(printId) { library.peopleIn(printId) }.collectAsStateWithLifecycle(emptyList())
    val scope = rememberCoroutineScope()
    var editing by remember { mutableStateOf(Editing.None) }
    var busy by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }

    fun work(block: suspend () -> Unit) {
        scope.launch {
            busy = true
            block()
            library.export(printId)
            busy = false
        }
    }

    Column(Modifier.fillMaxSize()) {
        TopBar(album?.title ?: "", onBack = onBack) {
            if (busy) Text("Saving…", style = MaterialTheme.typography.bodyMedium, color = Palette.Muted)
        }
        ZoomableImage(
            p,
            file = library.displayFile(p),
            before = library.originalFile(p).takeIf { p.enhance != Enhance.OFF },
            modifier = Modifier.fillMaxWidth().weight(1f),
        )

        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 12.dp),
        ) {
            val inherited = album?.date
            DetailRow(
                label = "When",
                value = p.date?.label ?: inherited?.let { "${it.label} (album)" },
                placeholder = "Add a date",
            ) { editing = Editing.Date }
            DetailRow("Who", people.joinToString(", ").ifEmpty { null }, "Add people") { editing = Editing.People }
            DetailRow("Where", p.place, "Add a place") { editing = Editing.Place }
            DetailRow("Caption", p.caption, "Add a caption") { editing = Editing.Caption }

            Spacer(Modifier.height(14.dp))
            LookChoice(p.enhance) { level -> work { library.setEnhance(printId, level) } }
            Spacer(Modifier.height(6.dp))
            Text(
                if (p.enhance == Enhance.OFF) "Exactly as scanned." else "Hold the photo to see it as scanned.",
                style = MaterialTheme.typography.bodyMedium,
                color = Palette.Muted,
            )
            Spacer(Modifier.height(10.dp))
            Text(
                qualityLine(p),
                style = MaterialTheme.typography.bodyMedium,
                color = if (p.sharpness < SOFT_SHARPNESS) Palette.Warning else Palette.Muted,
            )
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                QuietButton("Fix crop", onClick = { onFixCrop(printId) })
                QuietButton("Rotate", onClick = { work { library.rotate(printId) } })
                Spacer(Modifier.weight(1f))
                QuietButton("Delete", onClick = { confirmDelete = true }, color = Palette.Warning)
            }
        }
    }

    when (editing) {
        Editing.Date -> DateSheet(
            "When was this taken?", p.date, inherited = album?.date,
            onDismiss = { editing = Editing.None },
            onSave = { d -> editing = Editing.None; work { library.setDate(printId, d) } },
        )
        Editing.People -> PeopleSheet(
            initial = people,
            onDismiss = { editing = Editing.None },
            onSave = { names -> editing = Editing.None; work { library.setPeople(printId, names) } },
        )
        Editing.Place -> TextSheet(
            "Where was this?", p.place ?: "", "Grandma's house, Shimla",
            capitalization = KeyboardCapitalization.Words,
            onDismiss = { editing = Editing.None },
            onSave = { v -> editing = Editing.None; work { library.setPlace(printId, v) } },
        )
        Editing.Caption -> TextSheet(
            "Caption", p.caption ?: "", "What's happening here?",
            onDismiss = { editing = Editing.None },
            onSave = { v -> editing = Editing.None; work { library.setCaption(printId, v) } },
        )
        Editing.None -> {}
    }

    if (confirmDelete) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete this photo?") },
            text = { Text("The scan is deleted from Attic and your gallery. The paper print is fine, so you can scan it again.") },
            confirmButton = {
                QuietButton("Delete", color = Palette.Warning, onClick = {
                    confirmDelete = false
                    scope.launch { library.delete(printId); onBack() }
                })
            },
            dismissButton = { QuietButton("Keep", onClick = { confirmDelete = false }) },
            containerColor = Palette.Surface,
        )
    }
}

private fun qualityLine(p: PrintEntity): String {
    val mp = String.format(Locale.US, "%.1f", p.width.toLong() * p.height / 1_000_000.0)
    val sharp = when {
        p.sharpness < SOFT_SHARPNESS -> "looks soft, worth rescanning"
        p.sharpness < 150 -> "sharp enough"
        else -> "sharp"
    }
    return "${p.width} × ${p.height} px, $mp MP, $sharp"
}

@Composable
private fun DetailRow(label: String, value: String?, placeholder: String, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge, color = Palette.Muted, modifier = Modifier.width(76.dp))
        Text(
            value ?: placeholder,
            style = MaterialTheme.typography.bodyLarge,
            color = if (value != null) Palette.Ink else Palette.Accent,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun ZoomableImage(p: PrintEntity, file: java.io.File, before: java.io.File?, modifier: Modifier) {
    var scale by remember(p.id) { mutableFloatStateOf(1f) }
    var offset by remember(p.id) { mutableStateOf(Offset.Zero) }
    var comparing by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val transform = rememberTransformableState { _, zoom, pan, _ ->
        scale = (scale * zoom).coerceIn(1f, 12f)
        offset = if (scale == 1f) Offset.Zero else offset + pan
    }
    Box(
        modifier
            .background(Palette.Ground)
            .transformable(transform)
            .pointerInput(Unit) {
                detectTapGestures(
                    onDoubleTap = { if (scale > 1f) { scale = 1f; offset = Offset.Zero } else scale = 3f },
                    // Holding shows the scan as it came off the camera. A short wait first, so a pinch or
                    // a double tap doesn't flash it.
                    onPress = {
                        if (before != null) {
                            val show = scope.launch { delay(180); comparing = true }
                            tryAwaitRelease()
                            show.cancel()
                            comparing = false
                        }
                    },
                )
            },
    ) {
        val layer = Modifier
            .fillMaxSize()
            .padding(12.dp)
            .graphicsLayer {
                scaleX = scale; scaleY = scale
                translationX = offset.x; translationY = offset.y
            }
        // Both are loaded up front so switching is instant, not a reload.
        if (before != null) FullImage(before, p.version, layer)
        FullImage(file, p.version, layer.graphicsLayer { alpha = if (comparing) 0f else 1f })
    }
}

@Composable
private fun FullImage(file: java.io.File, version: Int, modifier: Modifier) {
    AsyncImage(
        // Full size, so zooming in shows the real detail of the scan.
        model = ImageRequest.Builder(LocalContext.current)
            .data(file)
            .size(Size.ORIGINAL)
            .memoryCacheKey("${file.path}#$version")
            .diskCacheKey("${file.path}#$version")
            .build(),
        contentDescription = null,
        contentScale = ContentScale.Fit,
        modifier = modifier,
    )
}

/** Original, natural or strong, as one quiet segmented row. */
@Composable
private fun LookChoice(level: Int, onChoose: (Int) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Palette.Surface)
            .padding(4.dp),
    ) {
        for (l in listOf(Enhance.OFF, Enhance.NATURAL, Enhance.STRONG)) {
            val selected = l == level
            Box(
                Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(9.dp))
                    .background(if (selected) Palette.SurfaceHigh else Palette.Surface)
                    .clickable(enabled = !selected) { onChoose(l) }
                    .padding(vertical = 10.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    Enhance.label(l),
                    style = MaterialTheme.typography.labelLarge,
                    color = if (selected) Palette.Ink else Palette.Muted,
                )
            }
        }
    }
}

/** Names as chips. People you've tagged before are one tap away. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun PeopleSheet(initial: List<String>, onDismiss: () -> Unit, onSave: (List<String>) -> Unit) {
    val library = LocalContext.current.library
    val known by remember { library.allPeople() }.collectAsStateWithLifecycle(emptyList())
    val chosen = remember { mutableStateListOf<String>().apply { addAll(initial) } }
    var typed by remember { mutableStateOf("") }

    fun add(name: String) {
        val n = name.trim()
        if (n.isNotEmpty() && chosen.none { it.equals(n, ignoreCase = true) }) chosen += n
        typed = ""
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = Palette.Surface) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 20.dp).imePadding().navigationBarsPadding()) {
            Text("Who's in this photo?", style = MaterialTheme.typography.titleMedium, color = Palette.Ink)
            Spacer(Modifier.height(14.dp))
            if (chosen.isNotEmpty()) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (name in chosen.toList()) Chip("$name  ×", selected = true) { chosen.remove(name) }
                }
                Spacer(Modifier.height(14.dp))
            }
            OutlinedTextField(
                value = typed,
                onValueChange = { typed = it },
                placeholder = { Text("Add a name", color = Palette.Muted) },
                singleLine = true,
                colors = fieldColors(),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { add(typed) }),
                modifier = Modifier.fillMaxWidth(),
            )
            val suggestions = known.filter { k -> chosen.none { it.equals(k, true) } && k.contains(typed.trim(), ignoreCase = true) }.take(12)
            if (suggestions.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (s in suggestions) Chip(s) { add(s) }
                }
            }
            Spacer(Modifier.height(18.dp))
            PrimaryButton("Save", onClick = { add(typed); onSave(chosen.toList()) }, modifier = Modifier.fillMaxWidth())
        }
    }
}
