package io.github.darkxace01.attic.people

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.darkxace01.attic.data.FaceEntity
import io.github.darkxace01.attic.data.Library
import io.github.darkxace01.attic.data.PersonSummary
import io.github.darkxace01.attic.library
import io.github.darkxace01.attic.ui.TopBar
import io.github.darkxace01.attic.ui.theme.Palette

/**
 * Everyone in the photos. At the top, faces Attic thinks are one person nobody has named yet ("Who are
 * these?"); below, everyone named, most photographed first.
 */
@Composable
fun PeopleScreen(onOpenPerson: (Long) -> Unit, onTree: () -> Unit = {}) {
    val library = LocalContext.current.library
    val people by remember { library.people() }.collectAsStateWithLifecycle(null)
    val faces by remember { library.allFaces() }.collectAsStateWithLifecycle(emptyList())
    // Regrouped whenever a face is found or named.
    val groups by produceState(emptyList<Library.FaceGroup>(), faces.size, faces.count { it.personId != null }) { value = library.unnamedGroups() }
    var naming by remember { mutableStateOf<Library.FaceGroup?>(null) }
    val relations by remember { library.relations() }.collectAsStateWithLifecycle(emptyList())
    val ask = io.github.darkxace01.attic.help.rememberAskFamily()
    val list = people ?: return

    Column(Modifier.fillMaxSize()) {
        TopBar("People", onBack = null) {
            if (relations.isNotEmpty()) io.github.darkxace01.attic.ui.QuietButton("Family tree", onClick = onTree)
        }
        if (list.isEmpty() && groups.isEmpty()) {
            Text(
                if (faces.isEmpty()) "Faces are found in your photos as you scan. Name someone once and Attic finds them in the rest."
                else "Open a photo and tap a face under Who to say who it is. Attic then finds them in the rest.",
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
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            if (groups.isNotEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Column {
                        Text("Who are these?", style = MaterialTheme.typography.titleMedium, color = Palette.Ink, modifier = Modifier.padding(horizontal = 4.dp))
                        Text("The same face in more than one photo.", style = MaterialTheme.typography.bodyMedium, color = Palette.Muted, modifier = Modifier.padding(horizontal = 4.dp))
                        androidx.compose.foundation.layout.Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Don't know them?", style = MaterialTheme.typography.bodyMedium, color = Palette.Muted, modifier = Modifier.padding(horizontal = 4.dp))
                            io.github.darkxace01.attic.ui.QuietButton("Ask the family", onClick = ask.ask)
                        }
                        ask.status?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = Palette.Ink, modifier = Modifier.padding(horizontal = 4.dp)) }
                        Spacer(Modifier.height(12.dp))
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            items(groups, key = { g -> g.faces.first().id }) { g -> GroupCard(g) { naming = g } }
                        }
                    }
                }
                if (list.isNotEmpty()) item(span = { GridItemSpan(maxLineSpan) }) { Spacer(Modifier.height(4.dp)) }
            }
            items(list, key = { it.id }) { p -> PersonTile(p, faces) { onOpenPerson(p.id) } }
        }
    }
    naming?.let { g -> NameSheet(g.faces, onDismiss = { naming = null }, suggestion = g.suggestion) }
}

/** A group of unnamed faces: three of them overlapping, how many photos, and a guess if there is one. */
@Composable
private fun GroupCard(g: Library.FaceGroup, onClick: () -> Unit) {
    Column(
        Modifier
            .clip(RoundedCornerShape(16.dp))
            .background(Palette.Surface)
            .clickable(onClick = onClick)
            .padding(12.dp)
            .width(116.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.size(width = 104.dp, height = 64.dp)) {
            g.faces.take(3).forEachIndexed { i, f ->
                FaceThumb(f, 56.dp, Modifier.offset(x = (i * 24).dp, y = if (i == 1) 0.dp else 8.dp))
            }
        }
        Spacer(Modifier.height(8.dp))
        val photos = g.faces.map { it.printId }.distinct().size
        Text(g.suggestion?.let { "${it.name}?" } ?: "Who?", style = MaterialTheme.typography.titleSmall, color = Palette.Ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text("$photos photos", style = MaterialTheme.typography.labelMedium, color = Palette.Muted)
    }
}

@Composable
private fun PersonTile(p: PersonSummary, faces: List<FaceEntity>, onClick: () -> Unit) {
    val face = faces.firstOrNull { it.id == p.faceId }
    Column(
        Modifier.fillMaxWidth().clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (face != null) {
            FaceThumb(face, 88.dp)
        } else {
            // Named on a photo without a face found: their initial.
            io.github.darkxace01.attic.ui.theme.Monogram(p.name, 88.dp)
        }
        Spacer(Modifier.height(6.dp))
        Text(p.name, style = MaterialTheme.typography.titleSmall, color = Palette.Ink, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
        Text(if (p.count == 1) "1 photo" else "${p.count} photos", style = MaterialTheme.typography.labelMedium, color = Palette.Muted)
    }
}
