package io.github.darkxace01.attic

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.darkxace01.attic.albums.AlbumScreen
import io.github.darkxace01.attic.albums.AlbumsScreen
import io.github.darkxace01.attic.print.CropScreen
import io.github.darkxace01.attic.print.PrintScreen
import io.github.darkxace01.attic.scan.ScanScreen
import io.github.darkxace01.attic.settings.SettingsScreen
import io.github.darkxace01.attic.stories.SessionScreen
import io.github.darkxace01.attic.people.PeopleScreen
import io.github.darkxace01.attic.people.PersonScreen
import io.github.darkxace01.attic.timeline.TimelineScreen
import io.github.darkxace01.attic.scan.ScanViewModel
import io.github.darkxace01.attic.ui.AlbumPickerSheet
import io.github.darkxace01.attic.ui.PrimaryButton
import io.github.darkxace01.attic.ui.theme.Palette
import io.github.darkxace01.attic.world.riso.drawIsland

private sealed interface Route {
    data object Scan : Route
    data object Albums : Route
    data object Timeline : Route
    data class Album(val id: Long) : Route
    data class Print(val id: Long) : Route {
        /** The photo swiped to, so coming back from fixing a crop returns to it. */
        var showing: Long = id
    }
    data class Crop(val id: Long) : Route
    data class Session(val albumId: Long) : Route
    data object People : Route
    data object Lightbox : Route
    data class Slideshow(val albumId: Long?, val personId: Long?) : Route
    data class Person(val id: Long) : Route
    data class FamilyTree(val rootId: Long?) : Route
    data class ThenNow(val printId: Long) : Route
    data class Phone(val photo: io.github.darkxace01.attic.data.PhonePhoto) : Route
    data object Settings : Route
}

@Composable
fun AtticApp() {
    val context = LocalContext.current
    var hasCamera by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    val prefs = remember { context.getSharedPreferences("app", android.content.Context.MODE_PRIVATE) }
    var welcomed by remember { mutableStateOf(prefs.getBoolean("welcomed", false) || hasCamera) }
    // Asked once and said no for good: only the phone's settings can allow it now.
    var cameraRefused by remember { mutableStateOf(false) }
    val askCamera = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        hasCamera = ok
        val activity = context as? android.app.Activity
        cameraRefused = !ok && activity != null && !androidx.core.app.ActivityCompat.shouldShowRequestPermissionRationale(activity, Manifest.permission.CAMERA)
    }
    val stack = remember { mutableStateListOf<Route>(if (hasCamera) Route.Scan else Route.Albums) }

    if (!welcomed) {
        Welcome(
            onScan = { prefs.edit().putBoolean("welcomed", true).apply(); welcomed = true; stack.clear(); stack += Route.Scan; askCamera.launch(Manifest.permission.CAMERA) },
            onLook = { prefs.edit().putBoolean("welcomed", true).apply(); welcomed = true; stack.clear(); stack += Route.Albums },
        )
        return
    }

    val scanVm: ScanViewModel = viewModel()
    var pickingAlbum by remember { mutableStateOf(false) }
    val top = stack.last()

    fun go(r: Route) { stack += r }
    fun back() { if (stack.size > 1) stack.removeAt(stack.lastIndex) }
    fun tab(r: Route) { stack.clear(); stack += r }

    BackHandler(enabled = stack.size > 1) { back() }

    // Coming back from the phone's settings with the camera allowed.
    val owner = androidx.compose.ui.platform.LocalLifecycleOwner.current
    androidx.compose.runtime.DisposableEffect(owner) {
        val o = androidx.lifecycle.LifecycleEventObserver { _, e ->
            if (e == androidx.lifecycle.Lifecycle.Event.ON_RESUME) hasCamera = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        }
        owner.lifecycle.addObserver(o)
        onDispose { owner.lifecycle.removeObserver(o) }
    }

    // The camera and the slideshow are dark: the phone's own bar icons go light there.
    val dark = top == Route.Scan || top is Route.Slideshow || top == Route.Lightbox
    val view = androidx.compose.ui.platform.LocalView.current
    androidx.compose.runtime.LaunchedEffect(dark) {
        val window = (context as? android.app.Activity)?.window ?: return@LaunchedEffect
        androidx.core.view.WindowCompat.getInsetsController(window, view).apply {
            isAppearanceLightStatusBars = !dark
            isAppearanceLightNavigationBars = !dark
        }
    }

    Column(Modifier.fillMaxSize().background(Palette.Ground)) {
        Box(Modifier.weight(1f)) {
            when (top) {
                Route.Scan -> if (hasCamera) ScanScreen(
                    scanVm,
                    onOpenAlbum = { go(Route.Album(it)) },
                    onPickAlbum = { pickingAlbum = true },
                ) else NoCamera(refused = cameraRefused) {
                    if (cameraRefused) runCatching {
                        context.startActivity(android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.fromParts("package", context.packageName, null)))
                    } else askCamera.launch(Manifest.permission.CAMERA)
                }
                Route.Albums -> Box(Modifier.statusBarsPadding()) {
                    AlbumsScreen(onOpen = { go(Route.Album(it)) }, onSettings = { go(Route.Settings) })
                }
                Route.Timeline -> Box(Modifier.statusBarsPadding()) { TimelineScreen(onOpenPrint = { go(Route.Print(it)) }, onOpenPhone = { go(Route.Phone(it)) }) }
                is Route.Phone -> Box(Modifier.statusBarsPadding()) { io.github.darkxace01.attic.timeline.PhonePhotoScreen(top.photo, onBack = ::back) }
                Route.Settings -> Box(Modifier.statusBarsPadding()) { SettingsScreen(onBack = ::back, onLightbox = { go(Route.Lightbox) }) }
                is Route.Slideshow -> io.github.darkxace01.attic.slideshow.SlideshowScreen(top.albumId, top.personId, onDone = ::back)
                Route.Lightbox -> io.github.darkxace01.attic.settings.LightboxScreen(onDone = ::back)
                is Route.Album -> Box(Modifier.statusBarsPadding()) {
                    AlbumScreen(
                        top.id,
                        onBack = ::back,
                        onOpenPrint = { go(Route.Print(it)) },
                        onScanHere = { scanVm.scanInto(top.id); tab(Route.Scan) },
                        onTellStories = { go(Route.Session(top.id)) },
                        onSlideshow = { go(Route.Slideshow(top.id, null)) },
                        onOpenPhone = { go(Route.Phone(it)) },
                    )
                }
                is Route.Print -> Box(Modifier.statusBarsPadding()) {
                    PrintScreen(top.showing, onShowing = { top.showing = it }, onBack = ::back, onFixCrop = { go(Route.Crop(it)) }, onOpenPrint = { go(Route.Print(it)) },
                        onScanBack = { scanVm.scanBackOf(it); tab(Route.Scan) },
                        onThenNow = { go(Route.ThenNow(it)) },
                    )
                }
                is Route.Crop -> Box(Modifier.statusBarsPadding()) { CropScreen(top.id, onDone = ::back) }
                is Route.Session -> Box(Modifier.statusBarsPadding()) { SessionScreen(top.albumId, onDone = ::back) }
                Route.People -> Box(Modifier.statusBarsPadding()) { PeopleScreen(onOpenPerson = { go(Route.Person(it)) }, onTree = { go(Route.FamilyTree(null)) }) }
                is Route.Person -> Box(Modifier.statusBarsPadding()) { PersonScreen(top.id, onBack = ::back, onOpenPrint = { go(Route.Print(it)) }, onSlideshow = { go(Route.Slideshow(null, top.id)) },
                    onOpenPerson = { go(Route.Person(it)) }, onTree = { go(Route.FamilyTree(top.id)) }) }
                is Route.ThenNow -> Box(Modifier.statusBarsPadding()) { io.github.darkxace01.attic.thennow.ThenNowScreen(top.printId, onBack = ::back) }
                is Route.FamilyTree -> Box(Modifier.statusBarsPadding()) {
                    io.github.darkxace01.attic.people.FamilyTreeScreen(top.rootId, onBack = ::back, onOpenPerson = { go(Route.Person(it)) })
                }
            }
        }
        if (top == Route.Scan || top == Route.Albums || top == Route.People || top == Route.Timeline) {
            TabBar(top, dark = top == Route.Scan, onTab = ::tab)
        } else {
            Spacer(Modifier.navigationBarsPadding())
        }
    }

    if (pickingAlbum) {
        val albums by remember { context.library.albums() }.collectAsStateWithLifecycle(emptyList())
        val state by scanVm.state.collectAsStateWithLifecycle()
        AlbumPickerSheet(
            albums = albums,
            currentId = state.albumId,
            onDismiss = { pickingAlbum = false },
            onPick = { scanVm.scanInto(it); pickingAlbum = false },
            onNew = { scanVm.newAlbum(it); pickingAlbum = false },
        )
    }
}

@Composable
private fun TabBar(current: Route, dark: Boolean, onTab: (Route) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(if (dark) Palette.Ink else Palette.Ground)
            .navigationBarsPadding()
            .height(60.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        for ((label, route) in listOf("Scan" to Route.Scan, "Albums" to Route.Albums, "People" to Route.People, "Timeline" to Route.Timeline)) {
            TabItem(label, current == route, dark, { onTab(route) }, Modifier.weight(1f))
        }
    }
}

/** A tab: the chosen one in full ink, with a short grease-pencil stroke under it. */
@Composable
private fun TabItem(label: String, selected: Boolean, dark: Boolean, onClick: () -> Unit, modifier: Modifier) {
    val on = if (dark) Palette.Ground else Palette.Ink
    Column(modifier.fillMaxSize().clickable(onClick = onClick), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center) {
        Text(
            label,
            style = MaterialTheme.typography.labelLarge.copy(fontWeight = if (selected) androidx.compose.ui.text.font.FontWeight.Bold else androidx.compose.ui.text.font.FontWeight.Medium),
            color = if (selected) on else on.copy(alpha = 0.55f),
        )
        androidx.compose.foundation.Canvas(Modifier.padding(top = 3.dp).size(26.dp, 6.dp)) {
            if (selected) drawLine(
                Palette.Accent, androidx.compose.ui.geometry.Offset(2f, size.height * 0.75f), androidx.compose.ui.geometry.Offset(size.width - 2f, size.height * 0.3f),
                2.5.dp.toPx(), androidx.compose.ui.graphics.StrokeCap.Round,
            )
        }
    }
}

/**
 * The first time Attic opens: what it does, in a sentence, and a choice of starting with the camera or
 * looking round first. The camera is only asked for when it's wanted.
 */
@Composable
private fun Welcome(onScan: () -> Unit, onLook: () -> Unit) {
    val clock = androidx.compose.runtime.remember { androidx.compose.runtime.mutableFloatStateOf(0f) }
    androidx.compose.runtime.LaunchedEffect(Unit) {
        val start = androidx.compose.runtime.withFrameNanos { it }
        while (true) androidx.compose.runtime.withFrameNanos { clock.floatValue = (it - start) / 1e9f }
    }
    Column(
        Modifier
            .fillMaxSize()
            .background(Palette.Ground)
            .systemBarsPadding()
            .padding(24.dp),
    ) {
        androidx.compose.foundation.Canvas(Modifier.fillMaxWidth().weight(1f)) {
            val look = io.github.darkxace01.attic.world.riso.IslandLook(
                io.github.darkxace01.attic.world.riso.IslandKind.Book, io.github.darkxace01.attic.ui.theme.Ink.Teal, io.github.darkxace01.attic.ui.theme.Ink.Pink,
                extra = io.github.darkxace01.attic.world.riso.Extra.Birds,
            )
            drawIsland(look, emptyList(), androidx.compose.ui.geometry.Offset(size.width / 2, size.height * 0.55f), size.minDimension * 0.3f, clock.floatValue)
        }
        Text(
            "Bring the old photos home",
            style = MaterialTheme.typography.displaySmall,
            color = Palette.Ink,
        )
        Spacer(Modifier.height(12.dp))
        Text(
            "Point your phone at a print or a whole album page. Attic finds each photo, straightens it and keeps a clean copy, with who, when and where. Nothing leaves your phone: no internet, no account, no ads.",
            style = MaterialTheme.typography.bodyLarge,
            color = Palette.Muted,
        )
        Spacer(Modifier.height(28.dp))
        PrimaryButton("Start scanning", onClick = onScan, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(4.dp))
        io.github.darkxace01.attic.ui.QuietButton("Look around first", onClick = onLook, modifier = Modifier.fillMaxWidth())
    }
}

/** The Scan tab without the camera: why it's needed, and the way to allow it. */
@Composable
private fun NoCamera(refused: Boolean, onAllow: () -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .background(Palette.Ink)
            .statusBarsPadding()
            .padding(24.dp),
        verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
    ) {
        Text("Scanning needs the camera", style = MaterialTheme.typography.titleLarge, color = Palette.Ground)
        Spacer(Modifier.height(10.dp))
        Text(
            if (refused) "It's turned off for Attic. Allow it in the phone's settings, under Permissions, then come back."
            else "Attic uses it only while this screen is open, and the pictures stay on your phone.",
            style = MaterialTheme.typography.bodyLarge, color = Palette.Ground.copy(alpha = 0.7f),
        )
        Spacer(Modifier.height(24.dp))
        PrimaryButton(if (refused) "Open settings" else "Allow camera", onClick = onAllow, modifier = Modifier.fillMaxWidth())
    }
}
