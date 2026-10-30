package io.github.darkxace01.attic.albums

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import io.github.darkxace01.attic.data.PrintEntity
import io.github.darkxace01.attic.library
import io.github.darkxace01.attic.scan.SOFT_SHARPNESS
import io.github.darkxace01.attic.ui.DateSheet
import io.github.darkxace01.attic.ui.QuietButton
import io.github.darkxace01.attic.ui.TextSheet
import io.github.darkxace01.attic.ui.TopBar
import io.github.darkxace01.attic.ui.theme.Palette
import kotlinx.coroutines.launch

/** One album, page by page, the way it was scanned. */
@Composable
fun AlbumScreen(albumId: Long, onBack: () -> Unit, onOpenPrint: (Long) -> Unit, onScanHere: () -> Unit) {
    val library = LocalContext.current.library
    val album by remember(albumId) { library.album(albumId) }.collectAsStateWithLifecycle(null)
    val prints by remember(albumId) { library.printsIn(albumId) }.collectAsStateWithLifecycle(emptyList())
    val scope = rememberCoroutineScope()
    var menu by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    var dating by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }

    val a = album ?: return
    val pages = prints.groupBy { it.page }.toSortedMap()

    Column(Modifier.fillMaxSize()) {
        TopBar(a.title, onBack = onBack, onTitleClick = { renaming = true }) {
            QuietButton("Scan here", onClick = onScanHere, color = Palette.Accent)
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "More", tint = Palette.Ink) }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text("Rename") }, onClick = { menu = false; renaming = true })
                    DropdownMenuItem(text = { Text("Set album date") }, onClick = { menu = false; dating = true })
                    DropdownMenuItem(text = { Text("Delete album", color = Palette.Warning) }, onClick = { menu = false; confirmDelete = true })
                }
            }
        }

        LazyVerticalGrid(
            columns = GridCells.Adaptive(104.dp),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Text(
                    a.dateLabel ?: "Add a date for the whole album",
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (a.dateLabel != null) Palette.Ink else Palette.Accent,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { dating = true }
                        .padding(vertical = 6.dp),
                )
            }
            if (prints.isEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Text(
                        "Nothing here yet. Tap Scan here and point at the first page.",
                        style = MaterialTheme.typography.bodyLarge,
                        color = Palette.Muted,
                    )
                }
            }
            for ((page, onPage) in pages) {
                item(span = { GridItemSpan(maxLineSpan) }, key = "page$page") {
                    Text(
                        "Page $page",
                        style = MaterialTheme.typography.labelMedium,
                        color = Palette.Muted,
                        modifier = Modifier.padding(top = 12.dp, bottom = 2.dp),
                    )
                }
                items(onPage, key = { it.id }) { p -> PrintTile(p, library.displayFile(p)) { onOpenPrint(p.id) } }
            }
        }
    }

    if (renaming) {
        TextSheet(
            "Album name", a.title, "Album name",
            capitalization = KeyboardCapitalization.Words,
            onDismiss = { renaming = false },
            onSave = { renaming = false; scope.launch { library.renameAlbum(albumId, it) } },
        )
    }
    if (dating) {
        DateSheet(
            "When is this album from?", a.date, inherited = null,
            onDismiss = { dating = false },
            onSave = { d -> dating = false; scope.launch { library.setAlbumDate(albumId, d) } },
        )
    }
    if (confirmDelete) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete ${a.title}?") },
            text = { Text("Its ${prints.size} scanned photos are deleted from Attic and from your gallery. The paper album is fine.") },
            confirmButton = {
                QuietButton("Delete", color = Palette.Warning, onClick = {
                    confirmDelete = false
                    scope.launch { library.deleteAlbum(albumId); onBack() }
                })
            },
            dismissButton = { QuietButton("Keep", onClick = { confirmDelete = false }) },
            containerColor = Palette.Surface,
        )
    }
}

@Composable
private fun PrintTile(p: PrintEntity, file: java.io.File, onClick: () -> Unit) {
    Box(
        Modifier
            .aspectRatio(1f)
            .background(Palette.Surface)
            .clickable(onClick = onClick)
            .padding(6.dp),
    ) {
        AsyncImage(
            model = coil3.request.ImageRequest.Builder(LocalContext.current)
                .data(file)
                .memoryCacheKey("${file.path}#${p.version}")
                .diskCacheKey("${file.path}#${p.version}")
                .build(),
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize(),
        )
        if (p.sharpness < SOFT_SHARPNESS) {
            Text(
                "Soft",
                style = MaterialTheme.typography.labelMedium,
                color = Palette.OnAccent,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .clip(RoundedCornerShape(4.dp))
                    .background(Palette.Warning)
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            )
        }
    }
}
