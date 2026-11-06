package io.github.darkxace01.attic.help

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import io.github.darkxace01.attic.data.HelpPack
import io.github.darkxace01.attic.library
import io.github.darkxace01.attic.ui.QuietButton
import io.github.darkxace01.attic.ui.theme.Palette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Making the page of questions for the family, and reading their answers back in. */
class AskFamily(val ask: () -> Unit, val read: () -> Unit, private val state: MutableState<String?>) {
    val status: String? get() = state.value
}

@Composable
fun rememberAskFamily(): AskFamily {
    val context = LocalContext.current
    val library = context.library
    val pack = remember { HelpPack(library) }
    val scope = rememberCoroutineScope()
    val status = remember { mutableStateOf<String?>(null) }
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/html")) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            status.value = "Making the page…"
            val ok = runCatching { withContext(Dispatchers.IO) { context.contentResolver.openOutputStream(uri)?.use { pack.write(it) } } }.isSuccess
            status.value = if (ok) "Saved. Send it to someone who might know; they open it in any browser and send back their answers." else "Couldn't save the page."
        }
    }
    val open = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            val text = withContext(Dispatchers.IO) { runCatching { context.contentResolver.openInputStream(uri)?.use { it.readBytes().decodeToString() } }.getOrNull() }
            val done = text?.let { pack.apply(it) }
            status.value = when {
                done == null -> "That isn't an answers file from an Attic page."
                done.total == 0 -> "Nothing new in ${done.from?.let { "$it's" } ?: "those"} answers."
                else -> listOfNotNull(
                    done.faces.takeIf { it > 0 }?.let { if (it == 1) "1 face named" else "$it faces named" },
                    done.dates.takeIf { it > 0 }?.let { if (it == 1) "1 date" else "$it dates" },
                    done.places.takeIf { it > 0 }?.let { if (it == 1) "1 place" else "$it places" },
                    done.notes.takeIf { it > 0 }?.let { if (it == 1) "1 memory" else "$it memories" },
                ).joinToString(", ").replaceFirstChar { it.uppercase() } + (done.from?.let { ", from $it." } ?: ".")
            }
        }
    }
    return remember { AskFamily(ask = { save.launch("Can you help with these photos.html") }, read = { open.launch(arrayOf("application/json", "text/*", "application/octet-stream")) }, status) }
}

/** In Settings: both halves, with what they do. */
@Composable
fun AskFamilySection() {
    val ask = rememberAskFamily()
    Column {
        Text(
            "Make a page of the faces nobody has named and the photos with no date or place. Anyone can open it in a browser, fill in what they know, and send back a small file of answers.",
            style = MaterialTheme.typography.bodyMedium, color = Palette.Muted,
        )
        Row {
            QuietButton("Make the page", onClick = ask.ask)
            QuietButton("Add their answers", onClick = ask.read)
        }
        ask.status?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = Palette.Ink) }
    }
}
