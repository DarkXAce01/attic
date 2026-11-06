package io.github.darkxace01.attic.people

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.darkxace01.attic.albums.PrintTile
import io.github.darkxace01.attic.library
import io.github.darkxace01.attic.ui.DateSheet
import io.github.darkxace01.attic.ui.TextSheet
import io.github.darkxace01.attic.ui.TopBar
import io.github.darkxace01.attic.ui.theme.Palette
import kotlinx.coroutines.launch

/**
 * One person through the years: every photo they're in, oldest first, each with how old they were when
 * their birth year is known.
 */
@Composable
fun PersonScreen(personId: Long, onBack: () -> Unit, onOpenPrint: (Long) -> Unit, onSlideshow: () -> Unit = {}, onOpenPerson: (Long) -> Unit = {}, onTree: () -> Unit = {}) {
    val library = LocalContext.current.library
    val person by remember(personId) { library.person(personId) }.collectAsStateWithLifecycle(null)
    val prints by remember(personId) { library.printsOf(personId) }.collectAsStateWithLifecycle(emptyList())
    val albums by remember { library.albumsWithDates() }.collectAsStateWithLifecycle(emptyMap())
    val scope = rememberCoroutineScope()
    var renaming by remember { mutableStateOf(false) }
    var dating by remember { mutableStateOf(false) }
    val p = person ?: return
    val born = p.born
    val ages = prints.map { pr -> born?.let { b -> (pr.date ?: albums[pr.albumId])?.let { Age.at(b, it) } } }
    val known = ages.filterNotNull()

    Column(Modifier.fillMaxSize()) {
        TopBar(p.name, onBack = onBack, onTitleClick = { renaming = true }) {
            if (prints.isNotEmpty()) io.github.darkxace01.attic.ui.QuietButton("Play", onClick = onSlideshow)
        }
        LazyVerticalGrid(
            columns = GridCells.Adaptive(104.dp),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Column(Modifier.padding(bottom = 8.dp)) {
                    Row(
                        Modifier.clip(RoundedCornerShape(10.dp)).clickable { dating = true }.padding(vertical = 6.dp, horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            born?.let { "Born ${it.label}" } ?: "When were they born?",
                            style = MaterialTheme.typography.bodyLarge,
                            color = if (born != null) Palette.Ink else Palette.Muted,
                        )
                    }
                    val line = when {
                        prints.isEmpty() -> "No photos yet."
                        known.size >= 2 && known.minOf { it.years } != known.maxOf { it.years } ->
                            "${prints.size} photos, from age ${known.minOf { it.years }} to ${known.maxOf { it.years }}."
                        else -> if (prints.size == 1) "1 photo." else "${prints.size} photos."
                    }
                    Text(line, style = MaterialTheme.typography.bodyMedium, color = Palette.Muted, modifier = Modifier.padding(horizontal = 4.dp))
                    FamilySection(personId, p.name, onOpenPerson, onTree)
                    if (prints.isNotEmpty()) Spacer(Modifier.height(10.dp))
                }
            }
            items(prints.size, key = { prints[it].id }) { i ->
                val pr = prints[i]
                Box {
                    PrintTile(pr, library.displayFile(pr)) { onOpenPrint(pr.id) }
                    val tag = ages[i]?.label ?: (pr.date ?: albums[pr.albumId])?.from?.year?.toString()
                    if (tag != null) {
                        Text(
                            tag,
                            style = MaterialTheme.typography.labelMedium,
                            color = Palette.Ink,
                            modifier = Modifier
                                .align(Alignment.BottomEnd)
                                .padding(10.dp)
                                .clip(RoundedCornerShape(50))
                                .background(Palette.Ground.copy(alpha = 0.75f))
                                .padding(horizontal = 8.dp, vertical = 3.dp),
                        )
                    }
                }
            }
        }
    }

    if (renaming) {
        TextSheet(
            "Their name", p.name, "Name",
            onDismiss = { renaming = false },
            onSave = { v -> renaming = false; scope.launch { library.renamePerson(personId, v) } },
            capitalization = KeyboardCapitalization.Words,
        )
    }
    if (dating) {
        DateSheet(
            "When were they born?", born, null,
            onDismiss = { dating = false },
            onSave = { d -> dating = false; scope.launch { library.setBorn(personId, d) } },
        )
    }
}
