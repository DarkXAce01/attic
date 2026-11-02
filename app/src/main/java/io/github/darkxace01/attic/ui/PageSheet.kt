package io.github.darkxace01.attic.ui

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
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.darkxace01.attic.data.Library
import io.github.darkxace01.attic.data.PhotoDate
import io.github.darkxace01.attic.library
import io.github.darkxace01.attic.ui.theme.Palette
import kotlinx.coroutines.launch

/**
 * When, who and where for a whole album page at once, in one sheet: one thumb, little typing. The last
 * page's answers are one tap away, since pages next to each other are usually from the same time and
 * place. Everything is optional; closing the sheet keeps what was there.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun PageSheet(albumId: Long, page: Int, onDismiss: () -> Unit) {
    val library = LocalContext.current.library
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var before by remember { mutableStateOf<Library.PageDetails?>(null) }
    var previous by remember { mutableStateOf<Library.PageDetails?>(null) }
    LaunchedEffect(albumId, page) {
        before = library.pageDetails(albumId, page)
        previous = if (page > 1) library.pageDetails(albumId, page - 1)?.takeIf { !it.isEmpty } else null
    }
    val b = before ?: return

    var dateText by remember(b) { mutableStateOf(b.date?.label ?: "") }
    val people = remember(b) { mutableStateListOf<String>().apply { addAll(b.people) } }
    val typedName = remember(b) { mutableStateOf("") }
    var place by remember(b) { mutableStateOf(b.place ?: "") }
    val date = PhotoDate.parse(dateText)
    val dateOk = dateText.isBlank() || date != null
    val places by remember { library.recentPlaces() }.collectAsStateWithLifecycle(emptyList())

    fun save() {
        if (!dateOk) return
        addTyped(people, typedName)
        val d = if (dateText.isBlank()) null else date
        scope.launch {
            library.setPageDetails(albumId, page, b, d, people.toList(), place)
            onDismiss()
        }
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = Palette.Surface) {
        Column(
            Modifier
                .padding(horizontal = 20.dp)
                .padding(bottom = 20.dp)
                .imePadding()
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState()),
        ) {
            Text("Page $page", style = MaterialTheme.typography.titleMedium, color = Palette.Ink)
            Text(
                if (b.count == 1) "For its photo. All optional." else "For all ${b.count} photos on it. All optional.",
                style = MaterialTheme.typography.bodyMedium,
                color = Palette.Muted,
            )
            val prev = previous
            if (prev != null) {
                Spacer(Modifier.height(12.dp))
                QuietButton(
                    "Same as page ${page - 1}: " + listOfNotNull(prev.date?.label, prev.people.joinToString(", ").ifEmpty { null }, prev.place).joinToString(" · "),
                    onClick = {
                        dateText = prev.date?.label ?: dateText
                        prev.people.forEach { n -> if (people.none { it.equals(n, true) }) people += n }
                        place = prev.place ?: place
                    },
                )
            }

            Spacer(Modifier.height(14.dp))
            Text("When", style = MaterialTheme.typography.labelLarge, color = Palette.Muted)
            Spacer(Modifier.height(6.dp))
            OutlinedTextField(
                value = dateText,
                onValueChange = { dateText = it },
                placeholder = { Text("summer 87, about 1985, 14/7/1998", color = Palette.Muted) },
                singleLine = true,
                colors = fieldColors(),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                modifier = Modifier.fillMaxWidth(),
            )
            if (dateText.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    if (date != null) "Reads as ${date.label}" else "Not sure what date that is",
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (date != null) Palette.Muted else Palette.Warning,
                )
            }

            Spacer(Modifier.height(16.dp))
            Text("Who", style = MaterialTheme.typography.labelLarge, color = Palette.Muted)
            Spacer(Modifier.height(6.dp))
            PeoplePicker(people, typedName)

            Spacer(Modifier.height(16.dp))
            Text("Where", style = MaterialTheme.typography.labelLarge, color = Palette.Muted)
            Spacer(Modifier.height(6.dp))
            OutlinedTextField(
                value = place,
                onValueChange = { place = it },
                placeholder = { Text("Grandma's house, Shimla", color = Palette.Muted) },
                singleLine = true,
                colors = fieldColors(),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { save() }),
                modifier = Modifier.fillMaxWidth(),
            )
            val placeSuggestions = places.filter { it != place && it.contains(place.trim(), ignoreCase = true) }.take(6)
            if (placeSuggestions.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (s in placeSuggestions) Chip(s) { place = s }
                }
            }

            Spacer(Modifier.height(20.dp))
            Row {
                QuietButton("Later", onClick = onDismiss)
                Spacer(Modifier.weight(1f))
                PrimaryButton("Save", onClick = ::save, enabled = dateOk)
            }
        }
    }
}

/**
 * Names as chips. People you've tagged before are one tap away. [typedState] holds a name typed but not
 * yet added, so a Save button can add it too: see [addTyped].
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PeoplePicker(
    chosen: SnapshotStateList<String>,
    typedState: MutableState<String> = remember { mutableStateOf("") },
    onDone: (() -> Unit)? = null,
) {
    val library = LocalContext.current.library
    val known by remember { library.allPeople() }.collectAsStateWithLifecycle(emptyList())
    var typed by typedState

    fun add(name: String) {
        val n = name.trim()
        if (n.isNotEmpty() && chosen.none { it.equals(n, ignoreCase = true) }) chosen += n
        typed = ""
    }

    Column {
        if (chosen.isNotEmpty()) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                for (name in chosen.toList()) Chip("$name  ×", selected = true) { chosen.remove(name) }
            }
            Spacer(Modifier.height(10.dp))
        }
        OutlinedTextField(
            value = typed,
            onValueChange = { v ->
                // A comma finishes a name, so several can be typed in a row.
                if (v.endsWith(",")) add(v.dropLast(1)) else typed = v
            },
            placeholder = { Text("Add a name", color = Palette.Muted) },
            singleLine = true,
            colors = fieldColors(),
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { if (typed.isBlank()) onDone?.invoke() else add(typed) }),
            modifier = Modifier.fillMaxWidth(),
        )
        val suggestions = known.filter { k -> chosen.none { it.equals(k, true) } && k.contains(typed.trim(), ignoreCase = true) }.take(12)
        if (suggestions.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                for (s in suggestions) Chip(s) { add(s) }
            }
        }
    }
}

/** Adds the name in [typed], if any, to [chosen]. */
fun addTyped(chosen: SnapshotStateList<String>, typed: MutableState<String>) {
    val n = typed.value.trim()
    if (n.isNotEmpty() && chosen.none { it.equals(n, ignoreCase = true) }) chosen += n
    typed.value = ""
}
