package io.github.darkxace01.attic.timeline

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.darkxace01.attic.albums.PrintTile
import io.github.darkxace01.attic.data.Timeline
import io.github.darkxace01.attic.library
import io.github.darkxace01.attic.ui.TopBar
import io.github.darkxace01.attic.ui.theme.Palette

/** Every photo from every album, by decade and year, oldest first. */
@Composable
fun TimelineScreen(onOpenPrint: (Long) -> Unit) {
    val library = LocalContext.current.library
    val rows by remember { library.timeline() }.collectAsStateWithLifecycle(null)

    Column(Modifier.fillMaxSize()) {
        TopBar("Timeline", onBack = null)
        val list = rows ?: return@Column
        if (list.isEmpty()) {
            Text(
                "Your photos will line up here by year once they're scanned. Give a page a date, or a whole album, and it finds its place.",
                style = MaterialTheme.typography.bodyLarge,
                color = Palette.Muted,
                modifier = Modifier.padding(horizontal = 20.dp),
            )
            return@Column
        }
        LazyVerticalGrid(
            columns = GridCells.Adaptive(104.dp),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            for (row in list) {
                when (row) {
                    is Timeline.Row.Decade -> item(span = { GridItemSpan(maxLineSpan) }, key = "decade${row.decade}") {
                        Text(
                            "${row.decade}s",
                            style = MaterialTheme.typography.headlineSmall,
                            color = Palette.Ink,
                            modifier = Modifier.padding(top = 20.dp, bottom = 2.dp),
                        )
                    }
                    is Timeline.Row.Group -> {
                        item(span = { GridItemSpan(maxLineSpan) }, key = "group${row.key}") {
                            Text(
                                row.title + "  ·  " + (if (row.prints.size == 1) "1 photo" else "${row.prints.size} photos"),
                                style = MaterialTheme.typography.labelLarge,
                                color = Palette.Muted,
                                modifier = Modifier.padding(top = 10.dp, bottom = 2.dp),
                            )
                        }
                        items(row.prints, key = { "p${it.id}" }) { p -> PrintTile(p, library.displayFile(p)) { onOpenPrint(p.id) } }
                    }
                }
            }
        }
    }
}
