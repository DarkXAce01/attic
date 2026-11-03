package io.github.darkxace01.attic.albums

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import io.github.darkxace01.attic.data.AlbumSummary
import io.github.darkxace01.attic.data.LibraryCounts
import io.github.darkxace01.attic.backup
import io.github.darkxace01.attic.library
import io.github.darkxace01.attic.ui.QuietButton
import io.github.darkxace01.attic.ui.TextSheet
import io.github.darkxace01.attic.ui.TopBar
import io.github.darkxace01.attic.ui.albumLine
import io.github.darkxace01.attic.ui.theme.Palette
import kotlinx.coroutines.launch
import java.io.File

/** Albums as objects: big covers you want to open. Above them, whether everything is safe. */
@Composable
fun AlbumsScreen(onOpen: (Long) -> Unit, onSettings: () -> Unit) {
    val library = LocalContext.current.library
    val albums by remember { library.albums() }.collectAsStateWithLifecycle(emptyList())
    val counts by remember { library.counts() }.collectAsStateWithLifecycle(LibraryCounts(0, 0))
    val scope = rememberCoroutineScope()
    var creating by remember { mutableStateOf(false) }
    var exporting by remember { mutableStateOf<String?>(null) }
    val backup = LocalContext.current.backup
    val backupProgress by backup.progress.collectAsStateWithLifecycle()
    val backupFolder = remember(backupProgress) { backup.folder }
    // While a backup runs, the line says how far it is.
    val working = exporting ?: (backupProgress as? io.github.darkxace01.attic.data.Backup.Progress.Working)?.let { "${it.what}… ${it.done} of ${it.total}" }

    Column(Modifier.fillMaxSize()) {
        TopBar("Albums", onBack = null) {
            QuietButton("New album", onClick = { creating = true }, color = Palette.Accent)
            QuietButton("Settings", onClick = onSettings)
        }
        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            item(span = { GridItemSpan(2) }) {
                SafetyLine(
                    counts, working, hasBackup = backupFolder != null,
                    onExport = {
                        scope.launch {
                            exporting = "Saving to your gallery…"
                            library.exportAll { done, total -> exporting = "Saving to your gallery… $done of $total" }
                            exporting = null
                        }
                    },
                    onBackUp = { scope.launch { backup.backUp() } },
                    onSetUpBackup = onSettings,
                )
            }
            if (albums.isEmpty()) {
                item(span = { GridItemSpan(2) }) {
                    Text(
                        "No albums yet. Scan your first page and it'll start one.",
                        style = MaterialTheme.typography.bodyLarge,
                        color = Palette.Muted,
                        modifier = Modifier.padding(4.dp),
                    )
                }
            }
            items(albums, key = { it.id }) { a -> AlbumCover(a, library.coverFile(a)) { onOpen(a.id) } }
        }
    }

    if (creating) {
        TextSheet(
            title = "New album",
            initial = "",
            placeholder = "Nani's red album",
            capitalization = KeyboardCapitalization.Words,
            onDismiss = { creating = false },
            onSave = { title ->
                creating = false
                scope.launch { onOpen(library.newAlbum(title).id) }
            },
        )
    }
}

/**
 * One sentence on how safe the photos are, with the fix next to it. A photo is safe once it's in the
 * backup folder (off this phone, if the folder is); the gallery copy is on the same phone, but apps like
 * Google Photos can back it up.
 */
@Composable
private fun SafetyLine(
    counts: LibraryCounts,
    working: String?,
    hasBackup: Boolean,
    onExport: () -> Unit,
    onBackUp: () -> Unit,
    onSetUpBackup: () -> Unit,
) {
    val notInGallery = counts.total - counts.inGallery
    val notBacked = counts.total - counts.backedUp
    fun photos(n: Int) = if (n == 1) "1 photo" else "$n photos"
    val (line, action) = when {
        working != null -> working to null
        counts.total == 0 -> "Scanned photos are kept here and in your gallery." to null
        counts.onlyInAttic > 0 -> "${photos(counts.onlyInAttic)} only in Attic, with no other copy." to ("Save to gallery" to onExport)
        !hasBackup -> "All ${photos(counts.total)} are in your gallery too. A backup folder keeps another copy." to ("Set up backup" to onSetUpBackup)
        notBacked > 0 -> "${photos(notBacked)} not in your backup yet." to ("Back up" to onBackUp)
        notInGallery > 0 -> "All ${photos(counts.total)} are backed up; ${photos(notInGallery)} not in your gallery." to ("Save to gallery" to onExport)
        else -> "All ${photos(counts.total)} are in your gallery and backed up." to null
    }
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Palette.Surface)
            .padding(start = 16.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            line,
            style = MaterialTheme.typography.bodyMedium,
            color = if (action != null) Palette.Ink else Palette.Muted,
            modifier = Modifier
                .weight(1f)
                .padding(vertical = 10.dp),
        )
        if (action != null) QuietButton(action.first, onClick = action.second, color = Palette.Accent)
    }
}

@Composable
private fun AlbumCover(a: AlbumSummary, cover: File?, onClick: () -> Unit) {
    Column(Modifier.clickable(onClick = onClick)) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(0.8f)
                .clip(RoundedCornerShape(6.dp))
                .background(Palette.Surface),
            contentAlignment = Alignment.Center,
        ) {
            if (cover != null) {
                AsyncImage(
                    model = cover,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Text("Empty", style = MaterialTheme.typography.bodyMedium, color = Palette.Muted)
            }
        }
        Spacer(Modifier.height(10.dp))
        Text(a.title, style = MaterialTheme.typography.titleMedium, color = Palette.Ink, maxLines = 2)
        Text(albumLine(a), style = MaterialTheme.typography.bodyMedium, color = Palette.Muted)
    }
}
