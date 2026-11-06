package io.github.darkxace01.attic.albums

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.runtime.produceState
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
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.ui.graphics.asImageBitmap
import io.github.darkxace01.attic.vision.PageLayout
import io.github.darkxace01.attic.world.PageModel
import io.github.darkxace01.attic.world.PagePrint
import io.github.darkxace01.attic.world.RebuiltPage
import io.github.darkxace01.attic.world.pageFlip
import io.github.darkxace01.attic.world.pagePin
import io.github.darkxace01.attic.world.pageShade
import io.github.darkxace01.attic.data.Library
import io.github.darkxace01.attic.data.PrintEntity
import io.github.darkxace01.attic.data.PhonePhoto
import io.github.darkxace01.attic.library
import io.github.darkxace01.attic.scan.SOFT_SHARPNESS
import io.github.darkxace01.attic.ui.DateSheet
import io.github.darkxace01.attic.ui.PageSheet
import io.github.darkxace01.attic.ui.QuietButton
import io.github.darkxace01.attic.ui.TextSheet
import io.github.darkxace01.attic.ui.TopBar
import io.github.darkxace01.attic.ui.theme.Palette
import androidx.compose.animation.core.animateFloat
import androidx.compose.foundation.layout.size
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.shape.CircleShape
import io.github.darkxace01.attic.ui.sharePhotos
import kotlinx.coroutines.launch

/** One album, page by page, the way it was scanned. */
@Composable
fun AlbumScreen(albumId: Long, onBack: () -> Unit, onOpenPrint: (Long) -> Unit, onScanHere: () -> Unit, onTellStories: () -> Unit, onSlideshow: () -> Unit = {}, onOpenPhone: (PhonePhoto) -> Unit = {}) {
    val context = LocalContext.current
    val library = context.library
    val album by remember(albumId) { library.album(albumId) }.collectAsStateWithLifecycle(null)
    val prints by remember(albumId) { library.printsIn(albumId) }.collectAsStateWithLifecycle(emptyList())
    val phone by remember(albumId) { library.phoneIn(albumId) }.collectAsStateWithLifecycle(emptyList())
    val scope = rememberCoroutineScope()
    var removing by remember { mutableStateOf<PhonePhoto?>(null) }
    var added by remember { mutableStateOf<String?>(null) }
    // The phone's own photo picker: no permission, and only what's picked is seen.
    val pickPhone = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.PickMultipleVisualMedia(200)) { uris ->
        if (uris.isNotEmpty()) scope.launch {
            val n = library.addPhone(albumId, uris)
            added = when (n) { 0 -> "Those are already in this album."; 1 -> "Added 1 photo. It stays in your gallery too."; else -> "Added $n photos. They stay in your gallery too." }
            kotlinx.coroutines.delay(3000)
            added = null
        }
    }
    fun addFromPhone() = pickPhone.launch(androidx.activity.result.PickVisualMediaRequest(androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia.ImageOnly))
    var menu by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    var dating by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var pageSheet by remember { mutableStateOf<Int?>(null) }
    var flipped by remember { mutableStateOf(setOf<Long>()) }
    // Saving the album as a file: how far along, and how it went.
    var saving by remember { mutableStateOf<String?>(null) }
    fun export(uri: android.net.Uri?, pdf: Boolean) {
        uri ?: return
        saving = "Saving the album…"
        // Not tied to this screen: leaving it doesn't stop the file half-written.
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            val ok = runCatching {
                context.contentResolver.openOutputStream(uri)!!.use { out ->
                    val ex = io.github.darkxace01.attic.data.AlbumExport(library)
                    val step: (Int, Int) -> Unit = { d, t -> saving = "Saving the album… $d of $t photos" }
                    if (pdf) ex.writePdf(albumId, out, step) else ex.writeHtml(albumId, out, step)
                }
            }.isSuccess
            saving = if (ok) (if (pdf) "Saved. The PDF is ready to print or send." else "Saved. The page opens in any browser, no internet needed.") else "Couldn't save the album there."
            kotlinx.coroutines.delay(3500)
            saving = null
        }
    }
    val saveHtml = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.CreateDocument("text/html")) { export(it, pdf = false) }
    val savePdf = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.CreateDocument("application/pdf")) { export(it, pdf = true) }
    val withStories by remember { library.printsWithStories() }.collectAsStateWithLifecycle(emptyList())
    val player = remember { io.github.darkxace01.attic.data.StoryPlayer() }
    val playing by player.playing.collectAsStateWithLifecycle()
    androidx.compose.runtime.DisposableEffect(Unit) { onDispose { player.stop() } }

    val a = album ?: return
    val pages = prints.groupBy { it.page }.toSortedMap()

    Column(Modifier.fillMaxSize().background(Palette.Ground)) {
        TopBar(a.title, onBack = onBack, onTitleClick = { renaming = true }) {
            QuietButton("Scan here", onClick = onScanHere, color = Palette.Accent)
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "More", tint = Palette.Ink) }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }, containerColor = Palette.Ground, shape = androidx.compose.foundation.shape.RoundedCornerShape(14.dp), border = androidx.compose.foundation.BorderStroke(1.5.dp, Palette.Ink), shadowElevation = 0.dp) {
                    DropdownMenuItem(text = { Text("Add from your phone") }, onClick = { menu = false; addFromPhone() })
                    if (prints.isNotEmpty() || phone.isNotEmpty()) DropdownMenuItem(text = { Text("Play as a slideshow") }, onClick = { menu = false; onSlideshow() })
                    DropdownMenuItem(text = { Text("Tell its stories") }, onClick = { menu = false; onTellStories() })
                    // Its copies are in Pictures/Attic/<album>: the gallery opens on the first, and swiping
                    // goes through the rest of the folder. Android has no way to open a folder itself.
                    prints.firstOrNull { it.galleryUri != null }?.let { first ->
                        DropdownMenuItem(text = { Text("Open in the gallery") }, onClick = {
                            menu = false
                            runCatching {
                                context.startActivity(
                                    android.content.Intent(android.content.Intent.ACTION_VIEW)
                                        .setDataAndType(android.net.Uri.parse(first.galleryUri), "image/*")
                                        .addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION),
                                )
                            }
                        })
                    }
                    DropdownMenuItem(text = { Text("Share the album") }, onClick = {
                        menu = false
                        saving = "Packing the album…"
                        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
                            val ok = runCatching {
                                io.github.darkxace01.attic.ui.shareAlbum(context, albumId, a.title) { d, t -> saving = "Packing the album… $d of $t photos" }
                            }.isSuccess
                            saving = if (ok) null else "Couldn't pack the album."
                            if (!ok) { kotlinx.coroutines.delay(3500); saving = null }
                        }
                    })
                    if (prints.isNotEmpty()) {
                        DropdownMenuItem(text = { Text("Save as a web page") }, onClick = { menu = false; saveHtml.launch("${a.title}.html") })
                        DropdownMenuItem(text = { Text("Save as a photobook (PDF)") }, onClick = { menu = false; savePdf.launch("${a.title}.pdf") })
                        DropdownMenuItem(text = { Text("Share all photos") }, onClick = {
                            menu = false
                            scope.launch { sharePhotos(context, prints.map { it.id }, "Share ${a.title}") }
                        })
                    }
                    DropdownMenuItem(text = { Text("Rename") }, onClick = { menu = false; renaming = true })
                    DropdownMenuItem(text = { Text("Set album date") }, onClick = { menu = false; dating = true })
                    DropdownMenuItem(text = { Text("Delete album", color = Palette.Warning) }, onClick = { menu = false; confirmDelete = true })
                }
            }
        }

        if (prints.isEmpty() && phone.isEmpty()) {
            Column(Modifier.padding(20.dp)) {
                Text(
                    "Nothing here yet. Scan a page of a paper album, or keep photos from your phone here: an album can just be a way of keeping photos together.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = Palette.Muted,
                )
                Row(Modifier.padding(top = 12.dp)) {
                    QuietButton("Scan here", onClick = onScanHere, color = Palette.Accent)
                    QuietButton("Add from your phone", onClick = ::addFromPhone)
                }
            }
        } else {
            val numbers = pages.keys.toList()
            // The phone's photos come after the scanned pages, on a page of their own.
            val phonePage = if (phone.isNotEmpty()) numbers.size else -1
            val pager = rememberPagerState { numbers.size + if (phone.isNotEmpty()) 1 else 0 }
            HorizontalPager(pager, beyondViewportPageCount = 1, modifier = Modifier.fillMaxWidth().weight(1f)) { i ->
                if (i == phonePage) {
                    PhonePage(phone, onOpenPhone, onRemove = { removing = it }, onAdd = ::addFromPhone)
                    return@HorizontalPager
                }
                val number = numbers.getOrNull(i) ?: return@HorizontalPager
                val onPage = pages[number].orEmpty()
                val model by produceState<PageModel?>(null, number, onPage.map { it.id to it.version }) { value = pageModel(library, number, onPage) }
                Box(Modifier.fillMaxSize().pagePin(pager, i)) {
                    model?.let { m ->
                        RebuiltPage(
                            m, onOpenPrint,
                            modifier = Modifier.fillMaxSize(),
                            pageModifier = Modifier.pageFlip(pager, i),
                            shade = { pageShade(pager, i) },
                            // Long-press turns a print over to its back, and back again.
                            onLongPressPrint = { id -> if (onPage.any { it.id == id && library.backFile(it) != null }) flipped = if (id in flipped) flipped - id else flipped + id },
                        ) { pp, mod ->
                            val p = onPage.first { it.id == pp.id }
                            PagePrintView(p, pp.image, flipped = p.id in flipped, hasStory = p.id in withStories, playing = playing == p.id, mod) {
                                if (playing == p.id) player.stop()
                                else scope.launch {
                                    val first = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { library.storiesNow(p.id).firstOrNull() }
                                    first?.let { player.play(p.id, library.storyFile(p, it)) }
                                }
                            }
                        }
                    }
                }
            }
            (saving ?: added)?.let { msg ->
                Text(msg, style = MaterialTheme.typography.bodyMedium, color = Palette.Ink, modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp))
            }
            Box(Modifier.padding(horizontal = 16.dp).padding(bottom = 12.dp)) {
                if (pager.currentPage == phonePage || numbers.isEmpty()) {
                    Row(Modifier.fillMaxWidth().padding(top = 8.dp).padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("From your phone", style = MaterialTheme.typography.labelLarge, color = Palette.Ink)
                        Text(
                            if (phone.size == 1) "  1 photo, still in your gallery" else "  ${phone.size} photos, still in your gallery",
                            style = MaterialTheme.typography.bodyMedium, color = Palette.Muted, modifier = Modifier.weight(1f),
                        )
                    }
                } else {
                    val current = numbers.getOrElse(pager.currentPage) { numbers.first() }
                    PageHeader(albumId, current, numbers.size, pager.currentPage + 1, pages[current].orEmpty().map { it.version }) { pageSheet = current }
                }
            }
        }
    }

    pageSheet?.let { page -> PageSheet(albumId, page, onDismiss = { pageSheet = null }) }
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
    removing?.let { ph ->
        io.github.darkxace01.attic.ui.theme.InkDialog(
            onDismissRequest = { removing = null },
            title = { Text("Take it out of ${a.title}?") },
            text = { Text("It stays in your phone's gallery, just not in this album.") },
            confirmButton = { QuietButton("Take out", color = Palette.Warning, onClick = { removing = null; scope.launch { library.removePhone(ph.id) } }) },
            dismissButton = { QuietButton("Keep", onClick = { removing = null }) },
            containerColor = Palette.Surface,
        )
    }
    if (confirmDelete) {
        io.github.darkxace01.attic.ui.theme.InkDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete ${a.title}?") },
            text = {
                Text(
                    buildString {
                        if (prints.isNotEmpty()) append("Its ${prints.size} scanned photos are deleted from Attic and from your gallery. The paper album is fine.")
                        if (phone.isNotEmpty()) append((if (isNotEmpty()) " " else "") + "Photos from your phone stay in your gallery.")
                        if (isEmpty()) append("There's nothing in it yet.")
                    },
                )
            },
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

/** "Page 4" and what the page is about, or a way to add it. Tap for the page's details. */
@Composable
private fun PageHeader(albumId: Long, page: Int, pageCount: Int, position: Int, versions: List<Int>, onClick: () -> Unit) {
    val library = LocalContext.current.library
    val details by produceState<Library.PageDetails?>(null, albumId, page, versions) { value = library.pageDetails(albumId, page) }
    val d = details
    val summary = d?.let { listOfNotNull(it.date?.label, it.people.joinToString(", ").ifEmpty { null }, it.place).joinToString(" · ") }
    Row(
        Modifier
            .fillMaxWidth()
            .padding(top = 8.dp)
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("Page $page", style = MaterialTheme.typography.labelLarge, color = Palette.Ink)
        if (pageCount > 1) Text(" of $pageCount", style = MaterialTheme.typography.labelLarge, color = Palette.Muted)
        Text(
            if (summary.isNullOrEmpty()) "Add when, who, where" else summary,
            style = MaterialTheme.typography.bodyMedium,
            color = if (summary.isNullOrEmpty()) Palette.Muted else Palette.Ink,
            maxLines = 1,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 12.dp).weight(1f),
        )
    }
}

@Composable
internal fun PrintTile(p: PrintEntity, file: java.io.File, onClick: () -> Unit) {
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

/**
 * A page as it was: when all its prints came from one photo, that photo is the page and each print sits
 * where it was; otherwise the prints are laid out neatly on album paper.
 */
private suspend fun pageModel(library: Library, number: Int, prints: List<PrintEntity>): PageModel {
    val dirs = prints.map { it.scanDir }.distinct()
    if (dirs.size == 1) {
        val bd = library.pageBackdrop(dirs[0])
        val bmp = bd?.let { kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { android.graphics.BitmapFactory.decodeFile(it.file.path) } }
        if (bd != null && bmp != null) {
            val quads = prints.map { it.cropQuad }
            val frame = PageLayout.frame(quads, bd.fullWidth.toFloat(), bd.fullHeight.toFloat())
            val k = bmp.width.toFloat() / bd.fullWidth
            return PageModel(
                number, bmp.asImageBitmap(),
                PageLayout.Frame(frame.left * k, frame.top * k, frame.right * k, frame.bottom * k),
                prints.map { PagePrint(it.id, library.displayFile(it), PageLayout.spot(it.cropQuad, frame)) },
                aspect = frame.width / frame.height,
            )
        }
    }
    // Neatly, one or two to a row, each tilted a hair the way hands mount prints.
    val cols = if (prints.size <= 2) 1 else 2
    val rows = (prints.size + cols - 1) / cols
    val aspect = 0.75f
    val spots = prints.mapIndexed { i, p ->
        val cw = 1f / cols; val ch = 1f / rows
        val pa = p.width.toFloat() / p.height.coerceAtLeast(1)
        // Fit the print in 80% of its cell, in page proportions.
        var w = cw * 0.8f
        var h = w * aspect / pa
        if (h > ch * 0.8f) { h = ch * 0.8f; w = h * pa / aspect }
        PageLayout.Spot(cw * (i % cols + 0.5f), ch * (i / cols + 0.5f), w, h, ((p.id * 37 % 7) - 3) * 0.4f)
    }
    return PageModel(number, null, null, prints.mapIndexed { i, p -> PagePrint(p.id, library.displayFile(p), spots[i]) }, aspect)
}

/**
 * A print on an album page. Turned over (long-press) it shows its back; a print with a story has a small
 * mark in its corner that plays it right there, the page staying open.
 */
@Composable
private fun PagePrintView(p: PrintEntity, image: Any, flipped: Boolean, hasStory: Boolean, playing: Boolean, modifier: Modifier, onStory: () -> Unit) {
    val library = LocalContext.current.library
    val turn by androidx.compose.animation.core.animateFloatAsState(if (flipped) 180f else 0f, androidx.compose.animation.core.tween(520), label = "turn")
    Box(modifier.graphicsLayer { rotationY = turn; cameraDistance = 16f * density }) {
        val back = if (turn > 90f) library.backFile(p) else null
        AsyncImage(
            model = coil3.request.ImageRequest.Builder(LocalContext.current)
                .data(back ?: image)
                .memoryCacheKey("${back ?: image}#${p.version}")
                .diskCacheKey("${back ?: image}#${p.version}")
                .build(),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            // The back is seen through the turned print: turn it again so it reads the right way.
            modifier = Modifier.fillMaxSize().graphicsLayer { if (back != null) rotationY = 180f },
        )
        if (hasStory && turn < 90f) {
            val pulse by androidx.compose.animation.core.rememberInfiniteTransition(label = "story").animateFloat(
                0.55f, 1f, androidx.compose.animation.core.infiniteRepeatable(androidx.compose.animation.core.tween<Float>(700), androidx.compose.animation.core.RepeatMode.Reverse), label = "pulse",
            )
            Box(
                Modifier
                    .align(Alignment.BottomEnd)
                    .padding(5.dp)
                    .size(22.dp)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.6f))
                    .clickable(onClick = onStory),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    Modifier
                        .size(8.dp)
                        .graphicsLayer { alpha = if (playing) pulse else 1f }
                        .clip(CircleShape)
                        .background(if (playing) Palette.Accent else Color.White),
                )
            }
        }
    }
}

/**
 * The album's photos from the phone, as a contact sheet. Tap one to see it big; hold to take it out of
 * the album (it stays in the gallery).
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun PhonePage(phone: List<PhonePhoto>, onOpen: (PhonePhoto) -> Unit, onRemove: (PhonePhoto) -> Unit, onAdd: () -> Unit) {
    LazyVerticalGrid(
        GridCells.Adaptive(110.dp),
        contentPadding = PaddingValues(16.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        items(phone, key = { it.id }) { ph ->
            AsyncImage(
                model = coil3.request.ImageRequest.Builder(LocalContext.current).data(ph.uri).size(360).build(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .aspectRatio(1f)
                    .clip(RoundedCornerShape(4.dp))
                    .background(Palette.Surface)
                    .combinedClickable(onClick = { onOpen(ph) }, onLongClick = { onRemove(ph) }),
            )
        }
        item(span = { GridItemSpan(maxLineSpan) }) {
            Box(Modifier.padding(top = 8.dp)) { QuietButton("Add more from your phone", onClick = onAdd) }
        }
    }
}
