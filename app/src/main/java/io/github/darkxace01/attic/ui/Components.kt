package io.github.darkxace01.attic.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import io.github.darkxace01.attic.data.AlbumSummary
import io.github.darkxace01.attic.data.PhotoDate
import io.github.darkxace01.attic.ui.theme.Palette

@Composable
fun TopBar(title: String, onBack: (() -> Unit)?, onTitleClick: (() -> Unit)? = null, trailing: @Composable () -> Unit = {}) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(56.dp)
            .padding(start = if (onBack != null) 4.dp else 20.dp, end = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onBack != null) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Palette.Ink)
            }
        }
        Text(
            title,
            style = MaterialTheme.typography.titleLarge,
            color = Palette.Ink,
            maxLines = 1,
            modifier = Modifier
                .weight(1f)
                .then(if (onTitleClick != null) Modifier.clickable(onClick = onTitleClick) else Modifier),
        )
        trailing()
    }
}

@Composable
fun PrimaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    Button(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(14.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = Palette.Accent, contentColor = Palette.OnAccent,
            disabledContainerColor = Palette.SurfaceHigh, disabledContentColor = Palette.Muted,
        ),
        modifier = modifier.height(52.dp),
    ) { Text(text, style = MaterialTheme.typography.labelLarge) }
}

@Composable
fun QuietButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, color: androidx.compose.ui.graphics.Color = Palette.Ink) {
    TextButton(onClick = onClick, modifier = modifier) {
        Text(text, style = MaterialTheme.typography.labelLarge, color = color)
    }
}

@Composable
fun Chip(text: String, selected: Boolean = false, onClick: () -> Unit) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = if (selected) Palette.OnAccent else Palette.Ink,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(if (selected) Palette.Accent else Palette.SurfaceHigh)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
    )
}

@Composable
fun fieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = Palette.Muted,
    unfocusedBorderColor = Palette.SurfaceHigh,
    cursorColor = Palette.Accent,
    focusedTextColor = Palette.Ink,
    unfocusedTextColor = Palette.Ink,
    focusedLabelColor = Palette.Muted,
    unfocusedLabelColor = Palette.Muted,
)

/** A bottom sheet with one text field: names, places, captions. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TextSheet(
    title: String,
    initial: String,
    placeholder: String,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
    capitalization: KeyboardCapitalization = KeyboardCapitalization.Sentences,
) {
    var value by remember { mutableStateOf(initial) }
    val focus = remember { FocusRequester() }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = Palette.Surface) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 20.dp).imePadding().navigationBarsPadding()) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = Palette.Ink)
            Spacer(Modifier.height(14.dp))
            OutlinedTextField(
                value = value,
                onValueChange = { value = it },
                placeholder = { Text(placeholder, color = Palette.Muted) },
                singleLine = true,
                colors = fieldColors(),
                keyboardOptions = KeyboardOptions(capitalization = capitalization, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { onSave(value) }),
                modifier = Modifier.fillMaxWidth().focusRequester(focus),
            )
            Spacer(Modifier.height(16.dp))
            PrimaryButton("Save", onClick = { onSave(value) }, modifier = Modifier.fillMaxWidth())
        }
    }
    LaunchedEffect(Unit) { focus.requestFocus() }
}

/**
 * Type a date the way you'd say it. The line under the field shows how it was understood, so
 * "summer 87" visibly becomes "Summer 1987" before it's saved.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun DateSheet(
    title: String,
    initial: PhotoDate?,
    inherited: PhotoDate?,
    onDismiss: () -> Unit,
    onSave: (PhotoDate?) -> Unit,
) {
    var text by remember { mutableStateOf(initial?.label ?: "") }
    val parsed = PhotoDate.parse(text)
    val focus = remember { FocusRequester() }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = Palette.Surface) {
        Column(
            Modifier
                .padding(horizontal = 20.dp)
                .padding(bottom = 20.dp)
                .imePadding()
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState()),
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = Palette.Ink)
            Spacer(Modifier.height(4.dp))
            Text(
                "As exact as you know it: 14 Jul 1998, Jul 1998, summer 1987, about 1985, early 90s, 1970s.",
                style = MaterialTheme.typography.bodyMedium,
                color = Palette.Muted,
            )
            Spacer(Modifier.height(14.dp))
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                placeholder = { Text("When was this?", color = Palette.Muted) },
                singleLine = true,
                colors = fieldColors(),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { if (parsed != null) onSave(parsed) }),
                modifier = Modifier.fillMaxWidth().focusRequester(focus),
            )
            Spacer(Modifier.height(8.dp))
            Text(
                when {
                    text.isBlank() -> inherited?.let { "Empty uses the album's date: ${it.label}" } ?: " "
                    parsed != null -> "Reads as ${parsed.label}"
                    else -> "Not sure what date that is"
                },
                style = MaterialTheme.typography.bodyMedium,
                color = if (text.isNotBlank() && parsed == null) Palette.Warning else Palette.Muted,
            )
            Spacer(Modifier.height(14.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                for (decade in listOf(1950, 1960, 1970, 1980, 1990, 2000, 2010)) {
                    Chip("${decade}s", selected = parsed?.label == "${decade}s") { text = "${decade}s" }
                }
            }
            Spacer(Modifier.height(18.dp))
            Row {
                if (initial != null) {
                    QuietButton("Clear", onClick = { onSave(null) }, color = Palette.Warning)
                    Spacer(Modifier.width(8.dp))
                }
                PrimaryButton(
                    "Save",
                    onClick = { onSave(parsed) },
                    enabled = parsed != null,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
    LaunchedEffect(Unit) { focus.requestFocus() }
}

/** Pick which album new scans go into, or start a new one. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AlbumPickerSheet(
    albums: List<AlbumSummary>,
    currentId: Long,
    onDismiss: () -> Unit,
    onPick: (Long) -> Unit,
    onNew: (String) -> Unit,
) {
    var newTitle by remember { mutableStateOf("") }
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = Palette.Surface) {
        Column(Modifier.padding(bottom = 20.dp).imePadding().navigationBarsPadding()) {
            Text(
                "Scan into",
                style = MaterialTheme.typography.titleMedium,
                color = Palette.Ink,
                modifier = Modifier.padding(horizontal = 20.dp),
            )
            Spacer(Modifier.height(8.dp))
            for (a in albums) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { onPick(a.id) }
                        .padding(horizontal = 20.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(a.title, style = MaterialTheme.typography.bodyLarge, color = Palette.Ink)
                        Text(albumLine(a), style = MaterialTheme.typography.bodyMedium, color = Palette.Muted)
                    }
                    if (a.id == currentId) Text("Current", style = MaterialTheme.typography.labelMedium, color = Palette.Accent)
                }
            }
            Spacer(Modifier.height(12.dp))
            Row(Modifier.padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = newTitle,
                    onValueChange = { newTitle = it },
                    placeholder = { Text("New album, e.g. Nani's red album", color = Palette.Muted) },
                    singleLine = true,
                    colors = fieldColors(),
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { if (newTitle.isNotBlank()) onNew(newTitle) }),
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(10.dp))
                QuietButton("Add", onClick = { if (newTitle.isNotBlank()) onNew(newTitle) }, color = Palette.Accent)
            }
        }
    }
}

fun albumLine(a: AlbumSummary): String {
    val parts = mutableListOf<String>()
    a.dateLabel?.let(parts::add)
    parts += if (a.pages == 1) "1 page" else "${a.pages} pages"
    parts += if (a.count == 1) "1 photo" else "${a.count} photos"
    return parts.joinToString(" · ")
}
