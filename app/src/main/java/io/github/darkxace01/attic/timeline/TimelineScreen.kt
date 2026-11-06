package io.github.darkxace01.attic.timeline

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import coil3.compose.AsyncImage
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import io.github.darkxace01.attic.ui.theme.printCard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.darkxace01.attic.albums.PrintTile
import io.github.darkxace01.attic.data.PhonePhoto
import io.github.darkxace01.attic.data.PhonePhotos
import io.github.darkxace01.attic.data.Timeline
import io.github.darkxace01.attic.library
import io.github.darkxace01.attic.ui.TopBar
import io.github.darkxace01.attic.ui.theme.Palette

/** Every photo from every album, by decade and year, oldest first. */
@Composable
fun TimelineScreen(onOpenPrint: (Long) -> Unit, onOpenPhone: (PhonePhoto) -> Unit = {}) {
    val context = LocalContext.current
    val library = context.library
    val prefs by library.settings.prefs.collectAsStateWithLifecycle()
    val askPhotos = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions(),
    ) { granted -> if (granted.values.any { it }) library.settings.update { it.copy(phonePhotos = true) } }
    val rows by remember { library.timeline() }.collectAsStateWithLifecycle(null)
    val today = remember { java.time.LocalDate.now() }
    val memory by remember(today) { library.onThisDay(today) }.collectAsStateWithLifecycle(null)

    Column(Modifier.fillMaxSize()) {
        TopBar("Timeline", onBack = null, ink = io.github.darkxace01.attic.ui.theme.Ink.Aqua.color) {
            if (!prefs.phonePhotos || !PhonePhotos.allowed(context)) {
                io.github.darkxace01.attic.ui.QuietButton("Add phone photos", onClick = {
                    if (PhonePhotos.allowed(context)) library.settings.update { it.copy(phonePhotos = true) } else askPhotos.launch(PhonePhotos.permissions)
                })
            }
        }
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
            memory?.let { m ->
                item(span = { GridItemSpan(maxLineSpan) }, key = "memory") { MemoryStrip(m, today, onOpenPrint, onOpenPhone) }
            }
            for (row in list) {
                when (row) {
                    is Timeline.Row.Decade -> item(span = { GridItemSpan(maxLineSpan) }, key = "decade${row.decade}") {
                        // Each decade overprinted in its own ink: the 70s in orange, the 80s in pink...
                        val inks = listOf(io.github.darkxace01.attic.ui.theme.Ink.Orange, io.github.darkxace01.attic.ui.theme.Ink.Pink, io.github.darkxace01.attic.ui.theme.Ink.Teal, io.github.darkxace01.attic.ui.theme.Ink.Blue, io.github.darkxace01.attic.ui.theme.Ink.Sunflower, io.github.darkxace01.attic.ui.theme.Ink.Purple, io.github.darkxace01.attic.ui.theme.Ink.Green)
                        io.github.darkxace01.attic.ui.theme.OverprintText(
                            "${row.decade}s",
                            MaterialTheme.typography.displayMedium,
                            inks[Math.floorMod(row.decade / 10, inks.size)].color,
                            Modifier.padding(top = 22.dp, bottom = 2.dp),
                            by = 3.dp,
                        )
                    }
                    is Timeline.Row.Group -> {
                        item(span = { GridItemSpan(maxLineSpan) }, key = "group${row.key}") {
                            Text(
                                row.title + "  ·  " + (if (row.count == 1) "1 photo" else "${row.count} photos"),
                                style = MaterialTheme.typography.labelLarge,
                                color = Palette.Muted,
                                modifier = Modifier.padding(top = 10.dp, bottom = 2.dp),
                            )
                        }
                        items(row.prints, key = { "p${it.id}" }) { p -> PrintTile(p, library.displayFile(p)) { onOpenPrint(p.id) } }
                        items(row.phone, key = { "m${it.id}" }) { ph -> PhoneTile(ph) { onOpenPhone(ph) } }
                    }
                }
            }
        }
    }
}

/** Today's memory over the timeline: big prints in a row, each with how long ago it was. */
@Composable
private fun MemoryStrip(m: OnThisDay.Memory, today: java.time.LocalDate, onOpenPrint: (Long) -> Unit, onOpenPhone: (PhonePhoto) -> Unit) {
    val library = LocalContext.current.library
    Column(Modifier.padding(bottom = 6.dp)) {
        Text(m.title, style = MaterialTheme.typography.titleMedium, color = Palette.Ink, modifier = Modifier.padding(bottom = 8.dp))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            items(m.prints, key = { it.first.id }) { (p, d) ->
                Column(Modifier.width(200.dp).clickable { onOpenPrint(p.id) }.padding(top = 6.dp, bottom = 4.dp)) {
                    AsyncImage(
                        library.displayFile(p), null, contentScale = ContentScale.Crop,
                        modifier = Modifier.width(186.dp).height(140.dp)
                            .graphicsLayer { rotationZ = if (p.id % 2 == 0L) -1.5f else 1.5f }
                            .printCard(),
                    )
                    val line = listOfNotNull(d?.label, OnThisDay.ago(d, today)).joinToString(", ")
                    if (line.isNotEmpty()) Text(line, style = MaterialTheme.typography.labelMedium, color = Palette.Muted, modifier = Modifier.padding(top = 6.dp, start = 2.dp))
                }
            }
            items(m.phone, key = { "m${it.id}" }) { ph ->
                Column(Modifier.width(200.dp).clickable { onOpenPhone(ph) }.padding(top = 6.dp, bottom = 4.dp)) {
                    AsyncImage(ph.uri, null, contentScale = ContentScale.Crop, modifier = Modifier.width(186.dp).height(140.dp).clip(RoundedCornerShape(10.dp)).background(Palette.Surface))
                    val d = io.github.darkxace01.attic.data.PhotoDate.day(ph.taken)
                    Text(listOfNotNull(d.label, OnThisDay.ago(d, today)).joinToString(", "), style = MaterialTheme.typography.labelMedium, color = Palette.Muted, modifier = Modifier.padding(top = 6.dp, start = 2.dp))
                }
            }
        }
    }
}

/** A photo from the phone's gallery: edge to edge, where a scanned print sits on its card. */
@Composable
private fun PhoneTile(ph: PhonePhoto, onClick: () -> Unit) {
    AsyncImage(
        ph.uri, null, contentScale = ContentScale.Crop,
        modifier = Modifier.aspectRatio(1f).background(Palette.Surface).clickable(onClick = onClick),
    )
}

/** One of the phone's photos, big, with its day; it opens in the phone's gallery for anything more. */
@Composable
fun PhonePhotoScreen(ph: PhonePhoto, onBack: () -> Unit) {
    val context = LocalContext.current
    Column(Modifier.fillMaxSize()) {
        TopBar(io.github.darkxace01.attic.data.PhotoDate.day(ph.taken).label, onBack = onBack) {
            io.github.darkxace01.attic.ui.QuietButton("Open in gallery", onClick = {
                runCatching {
                    context.startActivity(
                        android.content.Intent(android.content.Intent.ACTION_VIEW)
                            .setDataAndType(android.net.Uri.parse(ph.uri), "image/*")
                            .addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION),
                    )
                }
            })
        }
        AsyncImage(ph.uri, null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize().padding(bottom = 16.dp))
    }
}
