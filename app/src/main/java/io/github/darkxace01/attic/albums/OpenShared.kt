package io.github.darkxace01.attic.albums

import android.net.Uri
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import io.github.darkxace01.attic.albumFile
import io.github.darkxace01.attic.data.AlbumFile
import io.github.darkxace01.attic.ui.QuietButton
import io.github.darkxace01.attic.ui.theme.Palette

/**
 * Adds the shared album in [uri] and says what came in: a new album, or what was new in one already
 * here. [onOpen] goes to the album; [onDone] closes this.
 */
@Composable
fun OpenSharedAlbum(uri: Uri, onOpen: (Long) -> Unit, onDone: () -> Unit) {
    val context = LocalContext.current
    var working by remember(uri) { mutableStateOf("Opening the album…") }
    var result by remember(uri) { mutableStateOf<AlbumFile.Added?>(null) }
    var failed by remember(uri) { mutableStateOf<String?>(null) }
    LaunchedEffect(uri) {
        runCatching { context.albumFile.open(uri) { d, t -> working = "Adding the album… $d of $t photos" } }
            .onSuccess { result = it }
            .onFailure { failed = it.message?.takeIf { m -> m.endsWith(".") } ?: "Couldn't open that file. Is it an Attic album?" }
    }
    val r = result
    io.github.darkxace01.attic.ui.theme.InkDialog(
        onDismissRequest = { if (r != null || failed != null) onDone() },
        title = {
            Text(
                when {
                    failed != null -> "Couldn't add it"
                    r == null -> "A shared album"
                    r.alreadyHere -> r.title
                    else -> "${r.title} is here"
                },
            )
        },
        text = {
            Text(
                failed ?: when {
                    r == null -> working
                    !r.alreadyHere -> photos(r.newPhotos) + " added, with their dates, names and stories."
                    r.newPhotos == 0 && r.filledIn == 0 -> "You already have everything in this copy."
                    else -> listOfNotNull(
                        if (r.newPhotos > 0) photos(r.newPhotos) + " new" else null,
                        if (r.filledIn > 0) "more known about " + photos(r.filledIn) else null,
                    ).joinToString(", ").replaceFirstChar { it.uppercase() } + ". Nothing you had was changed."
                },
            )
        },
        confirmButton = {
            if (r != null) QuietButton("Open it", color = Palette.Accent, onClick = { onDone(); onOpen(r.albumId) })
            else if (failed != null) QuietButton("Close", onClick = onDone)
        },
        dismissButton = { if (r != null) QuietButton("Later", onClick = onDone) },
        containerColor = Palette.Surface,
    )
}

private fun photos(n: Int) = if (n == 1) "1 photo" else "$n photos"
