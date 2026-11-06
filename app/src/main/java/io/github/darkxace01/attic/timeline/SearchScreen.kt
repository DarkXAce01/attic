package io.github.darkxace01.attic.timeline

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import io.github.darkxace01.attic.albums.PrintTile
import io.github.darkxace01.attic.data.PrintEntity
import io.github.darkxace01.attic.data.Search
import io.github.darkxace01.attic.library
import io.github.darkxace01.attic.ui.QuietButton
import io.github.darkxace01.attic.ui.TopBar
import io.github.darkxace01.attic.ui.fieldColors
import io.github.darkxace01.attic.ui.theme.Palette
import io.github.darkxace01.attic.ui.theme.printCard

/**
 * Finding photos: type a name, a place, a year, a caption. Before anything's typed, the smart albums
 * Attic keeps by itself: everyone photographed often, each decade, each place, the photos with stories.
 */
@Composable
fun SearchScreen(onBack: () -> Unit, onOpenPrint: (Long) -> Unit, onSlideshow: (List<Long>) -> Unit) {
    val library = LocalContext.current.library
    val known by remember { library.known() }.collectAsStateWithLifecycle(null)
    var query by rememberSaveable { mutableStateOf("") }
    // A smart album opened: its own photos, exactly, not a search that might catch more.
    var open by rememberSaveable { mutableStateOf<String?>(null) }
    val all = known ?: return
    val smart = remember(all) { Search.smart(all) }
    val opened = open?.let { t -> smart.firstOrNull { it.title == t } }
    BackHandler(enabled = opened != null) { open = null }

    Column(Modifier.fillMaxSize()) {
        TopBar(opened?.title ?: "Find", onBack = { if (opened != null) open = null else onBack() }) {
            val shown = opened?.prints ?: remember(query, all) { Search.find(query, all) }
            if (shown.size >= 2) QuietButton("Slideshow", onClick = { onSlideshow(shown.map { it.id }) })
        }
        if (opened != null) {
            Grid(opened.prints, count(opened.prints.size), onOpenPrint)
            return@Column
        }
        OutlinedTextField(
            query, { query = it },
            placeholder = { Text("A name, a place, a year", color = Palette.Muted) },
            singleLine = true,
            trailingIcon = { if (query.isNotEmpty()) IconButton(onClick = { query = "" }) { Icon(Icons.Default.Close, "Clear", tint = Palette.Muted) } },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            colors = fieldColors(),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        )
        if (query.isBlank()) {
            SmartAlbums(smart, library, onOpen = { open = it.title })
            return@Column
        }
        val found = remember(query, all) { Search.find(query, all) }
        if (found.isEmpty()) {
            Text(
                "Nothing yet. Search reads the names, places, dates, captions and albums you've given your photos; it doesn't look inside the pictures.",
                style = MaterialTheme.typography.bodyLarge, color = Palette.Muted,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
            )
        } else Grid(found, count(found.size), onOpenPrint)
    }
}

private fun count(n: Int) = if (n == 1) "1 photo" else "$n photos"

@Composable
private fun Grid(prints: List<PrintEntity>, line: String, onOpenPrint: (Long) -> Unit) {
    val library = LocalContext.current.library
    LazyVerticalGrid(
        columns = GridCells.Adaptive(104.dp),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            Text(line, style = MaterialTheme.typography.labelLarge, color = Palette.Muted, modifier = Modifier.padding(top = 8.dp))
        }
        items(prints, key = { it.id }) { p -> PrintTile(p, library.displayFile(p)) { onOpenPrint(p.id) } }
    }
}

/** The smart albums, two to a row: a print from each, its name, how many. */
@Composable
private fun SmartAlbums(smart: List<Search.Smart>, library: io.github.darkxace01.attic.data.Library, onOpen: (Search.Smart) -> Unit) {
    if (smart.isEmpty()) {
        Text(
            "Smart albums fill themselves once photos have names, dates and places: everyone in three or more photos, each decade, each place.",
            style = MaterialTheme.typography.bodyLarge, color = Palette.Muted,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
        )
        return
    }
    // Each smart album gets a cover none before it used: the middle photo, or the nearest free one, so a
    // person's album doesn't always open on them as a baby and two albums don't look the same.
    val covers = remember(smart) {
        val used = mutableSetOf<Long>()
        smart.associate { s ->
            val mid = s.prints.size / 2
            val order = s.prints.indices.sortedBy { kotlin.math.abs(it - mid) }.map { s.prints[it] }
            val c = order.firstOrNull { it.id !in used } ?: s.prints[mid]
            used += c.id
            s.title to c
        }
    }
    LazyVerticalGrid(
        columns = GridCells.Adaptive(150.dp),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            Text("Smart albums", style = MaterialTheme.typography.titleMedium, color = Palette.Ink, modifier = Modifier.padding(top = 14.dp))
        }
        items(smart, key = { it.title }) { s ->
            Column(Modifier.clickable { onOpen(s) }) {
                val cover = covers.getValue(s.title)
                AsyncImage(
                    library.displayFile(cover), null, contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxWidth().aspectRatio(1.2f).padding(4.dp)
                        .graphicsLayer { rotationZ = if (s.title.length % 2 == 0) -1.2f else 1.2f }
                        .printCard(),
                )
                Row(Modifier.padding(top = 8.dp, start = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(s.title, style = MaterialTheme.typography.titleSmall, color = Palette.Ink, maxLines = 1, modifier = Modifier.weight(1f, fill = false))
                }
                Text(count(s.prints.size), style = MaterialTheme.typography.labelMedium, color = Palette.Muted, modifier = Modifier.padding(start = 4.dp))
            }
        }
    }
}
