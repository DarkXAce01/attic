package io.github.darkxace01.attic.albums

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.filled.MoreVert
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.withFrameNanos
import io.github.darkxace01.attic.world.riso.DressSheet
import io.github.darkxace01.attic.world.riso.IslandLook
import io.github.darkxace01.attic.world.riso.IslandKind
import io.github.darkxace01.attic.world.riso.drawIsland
import io.github.darkxace01.attic.world.riso.RisoWorld
import io.github.darkxace01.attic.world.riso.WorldIsland
import io.github.darkxace01.attic.world.riso.islandBitmap
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
    var menu by remember { mutableStateOf(false) }
    var shared by remember { mutableStateOf<android.net.Uri?>(null) }
    val pickShared = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.OpenDocument()) { shared = it }
    var exporting by remember { mutableStateOf<String?>(null) }
    val backup = LocalContext.current.backup
    val backupProgress by backup.progress.collectAsStateWithLifecycle()
    val backupFolder = remember(backupProgress) { backup.folder }
    // While a backup runs, the line says how far it is.
    val working = exporting ?: (backupProgress as? io.github.darkxace01.attic.data.Backup.Progress.Working)?.let { "${it.what}… ${it.done} of ${it.total}" }

    // One clock for the whole world: every island, every mote of dust.
    val clock = remember { mutableFloatStateOf(0f) }
    LaunchedEffect(Unit) {
        val start = withFrameNanos { it }
        while (true) withFrameNanos { clock.floatValue = (it - start) / 1e9f }
    }
    // Each island's photos are loaded once per album (again when it changes), off the main thread.
    val cache = remember { HashMap<Triple<Long, Int, Long?>, List<androidx.compose.ui.graphics.ImageBitmap>>() }
    val islands by produceState(emptyList<WorldIsland>(), albums) {
        value = albums.map { a ->
            val look = IslandLook.parse(a.look, a.id)
            val photos = cache.getOrPut(Triple(a.id, a.count * 10_000 + a.phone, look.cover)) {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    // Scanned prints lead; photos from the phone fill in, or are the whole island.
                    val scans = library.islandFiles(a.id, 4, look.cover).mapNotNull { islandBitmap(it) }
                    scans + if (scans.size < 4 && a.phone > 0) library.islandPhone(a.id, 4 - scans.size).map { it.asImageBitmap() } else emptyList()
                }
            }
            WorldIsland(a.id, a.title, albumLine(a), look, photos)
        }
    }
    var dressing by remember { mutableStateOf<Long?>(null) }

    val prefs by library.settings.prefs.collectAsStateWithLifecycle()
    val layout = prefs.albumLayout
    val header: @Composable () -> Unit = {
            Column(Modifier.padding(start = 20.dp, end = 12.dp, top = 8.dp, bottom = 8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    io.github.darkxace01.attic.ui.theme.OverprintText("Albums", MaterialTheme.typography.displayMedium, io.github.darkxace01.attic.ui.theme.Ink.Sunflower.color, Modifier.weight(1f), by = 3.dp)
                    QuietButton("New album", onClick = { creating = true }, color = Palette.Accent)
                    Box {
                        androidx.compose.material3.IconButton(onClick = { menu = true }) {
                            androidx.compose.material3.Icon(androidx.compose.material.icons.Icons.Default.MoreVert, "More", tint = Palette.Ink)
                        }
                        androidx.compose.material3.DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            androidx.compose.material3.DropdownMenuItem(text = { Text("Add a shared album") }, onClick = { menu = false; pickShared.launch(arrayOf("application/zip", "application/octet-stream", "application/x-zip-compressed")) })
                            androidx.compose.material3.DropdownMenuItem(text = { Text("Settings") }, onClick = { menu = false; onSettings() })
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                if (counts.total > 0 || working != null) Box(Modifier.padding(end = 8.dp)) {
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
                Spacer(Modifier.height(6.dp))
                if (albums.isEmpty()) {
                    // Where the first album will land: an island waiting, still unprinted.
                    androidx.compose.foundation.Canvas(Modifier.fillMaxWidth().height(280.dp)) {
                        run {
                            drawIsland(
                                IslandLook(IslandKind.Balloon, io.github.darkxace01.attic.ui.theme.Ink.Aqua, io.github.darkxace01.attic.ui.theme.Ink.Pink, extra = io.github.darkxace01.attic.world.riso.Extra.Birds),
                                emptyList(), androidx.compose.ui.geometry.Offset(size.width * 0.5f, size.height * 0.5f), size.width * 0.24f, clock.floatValue, 0,
                            )
                        }
                    }
                    Text("Your albums live here", style = MaterialTheme.typography.titleLarge, color = Palette.Ink)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Scan the first page of a paper album and it starts one. Or start an album and fill it with photos from your phone: an album can just be a way of keeping photos together.",
                        style = MaterialTheme.typography.bodyMedium, color = Palette.Muted,
                    )
                    Spacer(Modifier.height(12.dp))
                    io.github.darkxace01.attic.ui.PrimaryButton("Start an album", onClick = { creating = true })
                } else {
                    LayoutLine(layout) { l -> library.settings.update { it.copy(albumLayout = l) } }
                }
            }
        }

    when {
        albums.isEmpty() || layout == "path" -> RisoWorld(islands, time = { clock.floatValue }, onOpen = onOpen, onDress = { dressing = it }, header = header)
        else -> Column(Modifier.fillMaxSize().background(Palette.Ground)) {
            header()
            val world = Modifier.fillMaxWidth().weight(1f)
            if (layout == "ring") io.github.darkxace01.attic.world.riso.RisoRing(islands, { clock.floatValue }, onOpen, { dressing = it }, world)
            else io.github.darkxace01.attic.world.riso.RisoMap(islands, { clock.floatValue }, onOpen, { dressing = it }, world)
        }
    }

    dressing?.let { id ->
        val w = islands.firstOrNull { it.id == id }
        if (w != null) {
            DressSheet(
                id, w.title, w.meta, w.look, w.photos, time = { clock.floatValue },
                onChange = { l -> scope.launch { library.setLook(id, l.encode()) } },
                onDismiss = { dressing = null },
            )
        }
    }

    shared?.let { OpenSharedAlbum(it, onOpen = onOpen, onDone = { shared = null }) }

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

/**
 * How the albums are laid out, in a line like a camera's modes: down a path, round a ring, or scattered
 * on a map. The chosen one is underlined in pencil.
 */
@Composable
private fun LayoutLine(layout: String, onPick: (String) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(end = 8.dp)) {
        for ((key, label) in listOf("path" to "Path", "ring" to "Ring", "map" to "Map")) {
            val on = key == layout
            Column(
                Modifier.clip(RoundedCornerShape(8.dp)).clickable { onPick(key) }.padding(end = 18.dp, top = 4.dp, bottom = 2.dp),
            ) {
                Text(
                    label, color = if (on) Palette.Ink else Palette.Muted,
                    style = MaterialTheme.typography.labelLarge.copy(fontWeight = if (on) androidx.compose.ui.text.font.FontWeight.Bold else androidx.compose.ui.text.font.FontWeight.Medium),
                )
                androidx.compose.foundation.Canvas(Modifier.size(28.dp, 6.dp)) {
                    if (on) drawLine(Palette.Accent, androidx.compose.ui.geometry.Offset(2f, size.height * 0.7f), androidx.compose.ui.geometry.Offset(size.width - 2f, size.height * 0.35f), 2.5.dp.toPx(), androidx.compose.ui.graphics.StrokeCap.Round)
                }
            }
        }
        Spacer(Modifier.weight(1f))
        Text("Hold one to make it yours", style = MaterialTheme.typography.bodySmall, color = Palette.Muted)
    }
}
