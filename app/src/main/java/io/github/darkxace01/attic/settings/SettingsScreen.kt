package io.github.darkxace01.attic.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.darkxace01.attic.BuildConfig
import io.github.darkxace01.attic.R
import io.github.darkxace01.attic.backup
import io.github.darkxace01.attic.data.Backup
import io.github.darkxace01.attic.data.LibraryCounts
import io.github.darkxace01.attic.library
import io.github.darkxace01.attic.print.LookChoice
import io.github.darkxace01.attic.ui.QuietButton
import io.github.darkxace01.attic.ui.TopBar
import io.github.darkxace01.attic.ui.signature.PromiseLine
import io.github.darkxace01.attic.ui.signature.StudioFonts
import io.github.darkxace01.attic.ui.signature.StudioLockup
import io.github.darkxace01.attic.ui.theme.Palette
import androidx.compose.foundation.layout.size
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

@Composable
fun SettingsScreen(onBack: () -> Unit, onLightbox: () -> Unit = {}) {
    val context = LocalContext.current
    val library = context.library
    val backup = context.backup
    val prefs by library.settings.prefs.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var licences by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize()) {
        TopBar("Settings", onBack = onBack)
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .navigationBarsPadding(),
        ) {
            Heading("Look")
            Text("Paper by day, Midnight at night, or Auto to follow the phone.", style = MaterialTheme.typography.bodyMedium, color = Palette.Muted)
            Spacer(Modifier.height(10.dp))
            InkChoice(listOf("auto" to "Auto", "paper" to "Paper", "midnight" to "Midnight"), prefs.look) { l -> library.settings.update { it.copy(look = l) } }

            Toggle(
                "Tilt light",
                "Prints catch the light as you tilt the phone, like a real one in your hand. Only light on top; the photo isn't changed.",
                prefs.tiltLight,
            ) { on -> library.settings.update { it.copy(tiltLight = on) } }

            Heading("New scans")
            Text("How new scans look at first. You can change any photo later.", style = MaterialTheme.typography.bodyMedium, color = Palette.Muted)
            Spacer(Modifier.height(10.dp))
            LookChoice(prefs.newScanLook) { level -> library.settings.update { it.copy(newScanLook = level) } }
            Spacer(Modifier.height(8.dp))
            Toggle(
                "Also save them to your gallery",
                "In Pictures/Attic, with their dates, people and places, so Google Photos and other apps can back them up.",
                prefs.saveToGallery,
            ) { on -> library.settings.update { it.copy(saveToGallery = on) } }

            Toggle(
                "Check each shot before saving",
                "See the outlines Attic found and fix them by hand, like a document scanner. Also on the scan screen as Check.",
                prefs.checkEachShot,
            ) { on -> library.settings.update { it.copy(checkEachShot = on) } }

            Toggle(
                "Straighten curled prints",
                "When a print's sides bow because it's lifting off the page, the picture is straightened out with them. For new scans and fixed crops.",
                prefs.uncurl,
            ) { on -> library.settings.update { it.copy(uncurl = on) } }

            Toggle(
                "Trim plain borders",
                "New scans are cut down to the picture inside a white or black border. A border with writing on it is kept. You can put any border back from the photo.",
                prefs.trimBorders,
            ) { on -> library.settings.update { it.copy(trimBorders = on) } }

            Toggle(
                "Clean up dust and hairs",
                "Specks, hairs and small scratches are painted out of the Natural and Strong looks. Faces are left alone, and Original stays exactly as scanned. Changing this redoes every photo.",
                prefs.cleanDust,
            ) { on ->
                library.settings.update { it.copy(cleanDust = on) }
                kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default).launch { library.rerenderAll() }
            }

            Heading("Film")
            Row(
                Modifier.fillMaxWidth().clickable(onClick = onLightbox).padding(vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Use this phone as a lightbox", style = MaterialTheme.typography.bodyLarge, color = Palette.Ink)
                    Text(
                        "A plain white screen at full brightness to lay negatives or slides on, while another phone scans them with Negatives on.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Palette.Muted,
                    )
                }
            }

            Heading("Timeline")
            Toggle(
                "Phone photos on the timeline",
                "Today's photos alongside the old ones, read where they are in the phone's gallery. Attic never copies or changes them.",
                prefs.phonePhotos,
            ) { on -> library.settings.update { it.copy(phonePhotos = on) } }

            Heading("Copies in the gallery")
            GalleryCheckSection()

            Heading("Ask the family")
            io.github.darkxace01.attic.help.AskFamilySection()

            Heading("Backup")
            BackupSection(backup, library.counts())

            Heading("Keepsake")
            KeepsakeSection()

            Heading("About")
            Text(
                "Attic ${BuildConfig.VERSION_NAME}. Everything stays on this phone: Attic has no internet access, " +
                    "no account and no ads. Free software under the GPL-3.0.",
                style = MaterialTheme.typography.bodyMedium,
                color = Palette.Muted,
            )
            Spacer(Modifier.height(4.dp))
            QuietButton("Open-source licences", onClick = { licences = true })

            Spacer(Modifier.height(36.dp))
            val fonts = remember {
                StudioFonts(
                    serif = FontFamily(
                        Font(R.font.instrument_serif, FontWeight.Normal, FontStyle.Normal),
                        Font(R.font.instrument_serif_italic, FontWeight.Normal, FontStyle.Italic),
                    ),
                    mono = FontFamily(Font(R.font.geist_mono)),
                )
            }
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                StudioLockup(ink = Palette.Ink, fonts = fonts)
                Spacer(Modifier.height(14.dp))
                PromiseLine(
                    listOf("No internet", "No ads", "No account"), BuildConfig.VERSION_NAME, appName = "Attic",
                    color = Palette.Muted, showLoop = false,
                    style = androidx.compose.ui.text.TextStyle(fontFamily = fonts.mono, fontSize = 10.sp, letterSpacing = 0.8.sp),
                )
            }
        }
    }
    if (licences) LicencesSheet { licences = false }
}

@Composable
private fun BackupSection(backup: Backup, countsFlow: kotlinx.coroutines.flow.Flow<LibraryCounts>) {
    val scope = rememberCoroutineScope()
    val counts by remember { countsFlow }.collectAsStateWithLifecycle(LibraryCounts(0, 0))
    val progress by backup.progress.collectAsStateWithLifecycle()
    // Re-read after picking a folder or finishing a backup.
    var tick by remember { mutableIntStateOf(0) }
    val folderName = remember(tick, progress) { backup.folderName }
    val last = remember(tick, progress) { backup.lastBackupAt }

    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            backup.useFolder(uri)
            tick++
            scope.launch { backup.backUp() }
        }
    }
    val pickRestore = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) scope.launch { backup.restore(uri) }
    }

    Text(
        "A copy of every photo, its original and its details in a folder you choose: an SD card, a USB stick, " +
            "or a folder that another app copies elsewhere. Backing up again only adds what changed.",
        style = MaterialTheme.typography.bodyMedium,
        color = Palette.Muted,
    )
    Spacer(Modifier.height(10.dp))
    val notBacked = counts.total - counts.backedUp
    Text(
        when (val p = progress) {
            is Backup.Progress.Working -> "${p.what}… ${p.done} of ${p.total}"
            is Backup.Progress.Done -> p.message
            is Backup.Progress.Failed -> p.message
            Backup.Progress.Idle -> when {
                folderName == null -> "No backup folder yet."
                counts.total == 0 -> "Backing up to $folderName."
                notBacked == 0 -> "All ${counts.total} photos are backed up to $folderName" + (if (last > 0) ", last on ${DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(last))}." else ".")
                notBacked == 1 -> "1 photo isn't in the backup in $folderName yet."
                else -> "$notBacked photos aren't in the backup in $folderName yet."
            }
        },
        style = MaterialTheme.typography.bodyLarge,
        color = if (progress is Backup.Progress.Failed) Palette.Warning else Palette.Ink,
    )
    Spacer(Modifier.height(6.dp))
    val busy = progress is Backup.Progress.Working
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        if (folderName != null) {
            QuietButton("Back up now", onClick = { if (!busy) scope.launch { backup.backUp() } }, color = if (notBacked > 0) Palette.Accent else Palette.Ink)
            QuietButton("Change folder", onClick = { if (!busy) pickFolder.launch(null) })
        } else {
            QuietButton("Choose a backup folder", onClick = { if (!busy) pickFolder.launch(null) }, color = Palette.Accent)
        }
    }
    QuietButton("Restore from a backup", onClick = { if (!busy) pickRestore.launch(null) })
}

@Composable
private fun Heading(text: String) {
    Spacer(Modifier.height(28.dp))
    // Each section in its own spot ink, overprinted.
    val ink = io.github.darkxace01.attic.ui.theme.Ink.entries.filter { it != io.github.darkxace01.attic.ui.theme.Ink.Black && it != io.github.darkxace01.attic.ui.theme.Ink.Yellow }
        .let { it[Math.floorMod(text.hashCode(), it.size)] }
    io.github.darkxace01.attic.ui.theme.OverprintText(text, MaterialTheme.typography.headlineSmall, ink.color)
    Spacer(Modifier.height(8.dp))
}

@Composable
private fun Toggle(title: String, detail: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable { onChange(!checked) }
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = Palette.Ink)
            Text(detail, style = MaterialTheme.typography.bodyMedium, color = Palette.Muted)
        }
        Spacer(Modifier.padding(start = 12.dp))
        io.github.darkxace01.attic.ui.theme.InkSwitch(checked, onChange)
    }
}

/** What Attic is built with, and their licences. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LicencesSheet(onDismiss: () -> Unit) {
    val items = listOf(
        "OpenCV" to "Apache License 2.0. Finding, flattening and cleaning up prints.",
        "AndroidX, Jetpack Compose, CameraX, Room" to "Apache License 2.0.",
        "Coil" to "Apache License 2.0. Loading photos.",
        "Instrument Serif, Geist Mono" to "SIL Open Font License 1.1. The studio logo.",
        "Anybody, Instrument Sans, Reenie Beanie" to "SIL Open Font License 1.1. Attic's type: titles, everything you read, and grease-pencil handwriting.",
        "Print finder" to "Attic's own network, trained only on made-up album pages drawn with photos from the Unsplash Lite dataset (Unsplash License). No one's personal photos.",
        "Date reader" to "Attic's own network, trained only on made-up camera date stamps drawn on photos from the Unsplash Lite dataset.",
        "Face finder (YuNet)" to "MIT License, Copyright (c) 2020 Shiqi Yu. From the OpenCV model zoo. Runs on the phone.",
        "Face matcher (SFace)" to "Apache License 2.0, by Yaoyao Zhong and Weihong Deng. From the OpenCV model zoo. Runs on the phone; faces never leave it.",
        "Making prints bigger (Real-ESRGAN)" to "BSD 3-Clause License, Copyright (c) 2021 Xintao Wang. The general x4v3 model. Runs on the phone.",
        "Attic" to "GNU General Public License 3.0. The source is on GitHub: DarkXAce01/attic.",
    )
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = Palette.Ground, dragHandle = { io.github.darkxace01.attic.ui.theme.PencilHandle() }, scrimColor = Palette.Ink.copy(alpha = 0.4f)) {
        Column(
            Modifier
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp)
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState()),
        ) {
            Text("Open-source licences", style = MaterialTheme.typography.headlineSmall, color = Palette.Ink)
            for ((name, what) in items) {
                Spacer(Modifier.height(14.dp))
                Text(name, style = MaterialTheme.typography.bodyLarge, color = Palette.Ink)
                Text(what, style = MaterialTheme.typography.bodyMedium, color = Palette.Muted)
            }
        }
    }
}

/** A few choices in a row, in ink: the chosen one printed solid, like label tape. */
@Composable
private fun InkChoice(options: List<Pair<String, String>>, chosen: String, onChoose: (String) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(androidx.compose.foundation.shape.RoundedCornerShape(12.dp))
            .border(1.5.dp, Palette.Ink, androidx.compose.foundation.shape.RoundedCornerShape(12.dp))
            .padding(4.dp),
    ) {
        for ((key, label) in options) {
            val on = key == chosen
            Box(
                Modifier
                    .weight(1f)
                    .clip(androidx.compose.foundation.shape.RoundedCornerShape(9.dp))
                    .background(if (on) Palette.Ink else Palette.Ground)
                    .clickable(enabled = !on) { onChoose(key) }
                    .padding(vertical = 10.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(label, style = MaterialTheme.typography.labelLarge, color = if (on) Palette.Ground else Palette.Ink)
            }
        }
    }
}

/** Scans that already have a copy in the phone's gallery, side by side with it. Nothing is ever deleted. */
@Composable
private fun GalleryCheckSection() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val library = context.library
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var progress by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    var twins by remember { mutableStateOf<List<io.github.darkxace01.attic.data.GalleryTwins.Twin>?>(null) }
    fun check() {
        progress = 0 to 0
        scope.launch {
            twins = library.galleryTwins { done, total -> progress = done to total }
            progress = null
        }
    }
    val ask = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions(),
    ) { granted -> if (granted.values.any { it }) check() }
    Text(
        "Finds scans you already had on the phone, a print someone once sent you or a photo of one saved years ago. It only shows where they are; nothing is changed or deleted.",
        style = MaterialTheme.typography.bodyMedium, color = Palette.Muted,
    )
    val p = progress
    when {
        p != null -> Text(
            if (p.second == 0) "Looking through the gallery…" else "Looking through the gallery… ${p.first} of ${p.second}",
            style = MaterialTheme.typography.bodyMedium, color = Palette.Ink, modifier = Modifier.padding(vertical = 12.dp),
        )
        else -> QuietButton(if (twins == null) "Check the gallery" else "Check again", onClick = {
            if (io.github.darkxace01.attic.data.PhonePhotos.allowed(context)) check() else ask.launch(io.github.darkxace01.attic.data.PhonePhotos.permissions)
        })
    }
    val found = twins ?: return
    Text(
        when (found.size) { 0 -> "None of your scans are in the gallery."; 1 -> "1 scan is already in the gallery:"; else -> "${found.size} scans are already in the gallery:" },
        style = MaterialTheme.typography.bodyMedium, color = Palette.Ink, modifier = Modifier.padding(bottom = 8.dp),
    )
    androidx.compose.foundation.lazy.LazyRow(horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(16.dp)) {
        items(found.size) { i ->
            val t = found[i]
            Row(horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(4.dp)) {
                for (model in listOf<Any>(library.displayFile(t.print), android.net.Uri.parse(t.uri))) {
                    coil3.compose.AsyncImage(
                        model, null, contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                        modifier = Modifier.size(72.dp).background(Palette.Surface).clickable {
                            if (model is android.net.Uri) runCatching {
                                context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW).setDataAndType(model, "image/*").addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION))
                            }
                        },
                    )
                }
            }
        }
    }
}

/** The whole archive as a folder of web pages, for a USB stick or relatives without the app. */
@Composable
private fun KeepsakeSection() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var progress by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    var done by remember { mutableStateOf<String?>(null) }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        progress = 0 to 0
        scope.launch {
            val name = io.github.darkxace01.attic.data.Keepsake(context, context.library).write(uri) { d, t -> progress = d to t }
            done = name?.let { "Saved as “$it”. Open index.html in it with any browser." } ?: "Couldn't write to that folder."
            progress = null
        }
    }
    Text(
        "Every album as its own web page, with a front page for them all: photos, dates, names, backs and stories. For a USB stick, a drive, or a relative without Attic. Opens in any browser, no internet needed.",
        style = MaterialTheme.typography.bodyMedium, color = Palette.Muted,
    )
    val p = progress
    if (p != null) Text(
        if (p.second == 0) "Making the keepsake…" else "Making the keepsake… album ${minOf(p.first + 1, p.second)} of ${p.second}",
        style = MaterialTheme.typography.bodyMedium, color = Palette.Ink, modifier = Modifier.padding(vertical = 12.dp),
    ) else QuietButton("Make a keepsake folder", onClick = { done = null; pick.launch(null) })
    done?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = Palette.Ink) }
}
