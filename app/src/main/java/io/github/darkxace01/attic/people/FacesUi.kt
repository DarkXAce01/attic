package io.github.darkxace01.attic.people

import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import io.github.darkxace01.attic.data.FaceEntity
import io.github.darkxace01.attic.data.PersonEntity
import io.github.darkxace01.attic.data.PrintEntity
import io.github.darkxace01.attic.library
import io.github.darkxace01.attic.ui.Chip
import io.github.darkxace01.attic.ui.PrimaryButton
import io.github.darkxace01.attic.ui.QuietButton
import io.github.darkxace01.attic.ui.fieldColors
import io.github.darkxace01.attic.ui.theme.Palette
import kotlinx.coroutines.launch

/** A face, round, cut from its photo. */
@Composable
fun FaceThumb(face: FaceEntity, size: Dp, modifier: Modifier = Modifier) {
    val library = LocalContext.current.library
    val file by produceState<java.io.File?>(null, face.id) { value = library.faceThumb(face) }
    Box(modifier.size(size).clip(CircleShape).background(Palette.SurfaceHigh)) {
        file?.let { AsyncImage(it, null, contentScale = ContentScale.Crop, modifier = Modifier.size(size)) }
    }
}

/**
 * The faces Attic found on a photo, under "Who": named ones with their names, the rest asking. Tap one
 * to say who it is.
 */
@Composable
fun FacesRow(p: PrintEntity) {
    val library = LocalContext.current.library
    val faces by remember(p.id) { library.facesOf(p.id) }.collectAsStateWithLifecycle(emptyList())
    val people by remember { library.people() }.collectAsStateWithLifecycle(emptyList())
    val shown = faces.filter { !it.ignored }
    var naming by remember { mutableStateOf<FaceEntity?>(null) }
    if (shown.isEmpty()) return
    LazyRow(
        Modifier.fillMaxWidth().padding(bottom = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        items(shown, key = { it.id }) { f ->
            val name = people.firstOrNull { it.id == f.personId }?.name
            Column(
                Modifier.width(64.dp).clickable { naming = f },
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                FaceThumb(f, 52.dp)
                Spacer(Modifier.height(4.dp))
                Text(
                    name ?: "Who?",
                    style = MaterialTheme.typography.labelMedium,
                    color = if (name != null) Palette.Ink else Palette.Muted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
    naming?.let { f -> NameSheet(listOf(f), onDismiss = { naming = null }) }
}

/**
 * Who is this: for one face, or a group of faces that look like one person (tap a face that isn't them to
 * leave it out). The likeliest person comes first; anyone already named is a tap away.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun NameSheet(faces: List<FaceEntity>, onDismiss: () -> Unit, suggestion: PersonEntity? = null) {
    val library = LocalContext.current.library
    val scope = rememberCoroutineScope()
    val people by remember { library.people() }.collectAsStateWithLifecycle(emptyList())
    val guessed by produceState(suggestion, faces.firstOrNull()?.id) {
        if (value == null && faces.size == 1 && faces[0].personId == null) value = library.suggestFor(faces[0])
    }
    val current = people.firstOrNull { p -> faces.isNotEmpty() && faces.all { it.personId == p.id } }?.name
    var typed by remember { mutableStateOf(current ?: "") }
    val left = remember { mutableStateListOf<Long>() }
    val chosen = faces.filter { it.id !in left }

    fun save(name: String) {
        if (name.isBlank() || chosen.isEmpty()) return
        scope.launch { library.nameFaces(chosen.map { it.id }, name) }
        onDismiss()
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = Palette.Ground, dragHandle = { io.github.darkxace01.attic.ui.theme.PencilHandle() }, scrimColor = Palette.Ink.copy(alpha = 0.4f)) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 20.dp).imePadding().navigationBarsPadding()) {
            Text(if (faces.size == 1) "Who is this?" else "Who are these?", style = MaterialTheme.typography.headlineSmall, color = Palette.Ink)
            if (faces.size > 1) {
                Text("Tap anyone who isn't them to leave them out.", style = MaterialTheme.typography.bodyMedium, color = Palette.Muted)
            }
            Spacer(Modifier.height(14.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                for (f in faces.take(24)) {
                    val out = f.id in left
                    FaceThumb(
                        f, if (faces.size == 1) 96.dp else 56.dp,
                        Modifier
                            .graphicsLayer { alpha = if (out) 0.3f else 1f }
                            .then(if (faces.size > 1) Modifier.clickable { if (out) left -= f.id else left += f.id } else Modifier),
                    )
                }
            }
            if (faces.size > 24) Text("and ${faces.size - 24} more", style = MaterialTheme.typography.bodyMedium, color = Palette.Muted)
            Spacer(Modifier.height(16.dp))
            guessed?.takeIf { it.name != current }?.let { g ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Looks like ${g.name}", style = MaterialTheme.typography.bodyLarge, color = Palette.Ink, modifier = Modifier.weight(1f))
                    PrimaryButton("Yes", onClick = { save(g.name) })
                }
                Spacer(Modifier.height(12.dp))
            }
            OutlinedTextField(
                value = typed,
                onValueChange = { typed = it },
                placeholder = { Text("Their name", color = Palette.Muted) },
                singleLine = true,
                colors = fieldColors(),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { save(typed) }),
                modifier = Modifier.fillMaxWidth(),
            )
            val matches = people.filter { it.name != current && (typed.isBlank() || it.name.contains(typed.trim(), ignoreCase = true)) }.take(8)
            if (matches.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (m in matches) Chip(m.name) { save(m.name) }
                }
            }
            Spacer(Modifier.height(16.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (faces.size == 1) {
                    QuietButton("Not a face", color = Palette.Muted, onClick = {
                        scope.launch { library.ignoreFace(faces[0].id) }
                        onDismiss()
                    })
                }
                Spacer(Modifier.weight(1f))
                PrimaryButton("Save", onClick = { save(typed) }, enabled = typed.isNotBlank() && chosen.isNotEmpty())
            }
        }
    }
}

/** A ring around a face, for when it stands for a person. */
fun Modifier.faceRing(on: Boolean): Modifier = if (on) border(2.dp, Palette.Ink, CircleShape) else this
