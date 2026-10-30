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
import io.github.darkxace01.attic.scan.ScanViewModel
import io.github.darkxace01.attic.ui.AlbumPickerSheet
import io.github.darkxace01.attic.ui.PrimaryButton
import io.github.darkxace01.attic.ui.theme.Palette

private sealed interface Route {
    data object Scan : Route
    data object Albums : Route
    data class Album(val id: Long) : Route
    data class Print(val id: Long) : Route
    data class Crop(val id: Long) : Route
}

@Composable
fun AtticApp() {
    val context = LocalContext.current
    var hasCamera by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    val askCamera = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { hasCamera = it }

    if (!hasCamera) {
        CameraIntro(onAllow = { askCamera.launch(Manifest.permission.CAMERA) })
        return
    }

    val scanVm: ScanViewModel = viewModel()
    val stack = remember { mutableStateListOf<Route>(Route.Scan) }
    var pickingAlbum by remember { mutableStateOf(false) }
    val top = stack.last()

    fun go(r: Route) { stack += r }
    fun back() { if (stack.size > 1) stack.removeAt(stack.lastIndex) }
    fun tab(r: Route) { stack.clear(); stack += r }

    BackHandler(enabled = stack.size > 1) { back() }

    Column(Modifier.fillMaxSize().background(Palette.Ground)) {
        Box(Modifier.weight(1f)) {
            when (top) {
                Route.Scan -> ScanScreen(
                    scanVm,
                    onOpenAlbum = { go(Route.Album(it)) },
                    onPickAlbum = { pickingAlbum = true },
                )
                Route.Albums -> Box(Modifier.statusBarsPadding()) { AlbumsScreen(onOpen = { go(Route.Album(it)) }) }
                is Route.Album -> Box(Modifier.statusBarsPadding()) {
                    AlbumScreen(
                        top.id,
                        onBack = ::back,
                        onOpenPrint = { go(Route.Print(it)) },
                        onScanHere = { scanVm.scanInto(top.id); tab(Route.Scan) },
                    )
                }
                is Route.Print -> Box(Modifier.statusBarsPadding()) {
                    PrintScreen(top.id, onBack = ::back, onFixCrop = { go(Route.Crop(it)) })
                }
                is Route.Crop -> Box(Modifier.statusBarsPadding()) { CropScreen(top.id, onDone = ::back) }
            }
        }
        if (top == Route.Scan || top == Route.Albums) {
            TabBar(scanSelected = top == Route.Scan, onScan = { tab(Route.Scan) }, onAlbums = { tab(Route.Albums) })
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
private fun TabBar(scanSelected: Boolean, onScan: () -> Unit, onAlbums: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(Palette.Ground)
            .navigationBarsPadding()
            .height(56.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TabItem("Scan", scanSelected, onScan, Modifier.weight(1f))
        TabItem("Albums", !scanSelected, onAlbums, Modifier.weight(1f))
    }
}

@Composable
private fun TabItem(label: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier) {
    Box(modifier.fillMaxSize().clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            color = if (selected) Palette.Ink else Palette.Muted,
        )
    }
}

@Composable
private fun CameraIntro(onAllow: () -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .background(Palette.Ground)
            .systemBarsPadding()
            .padding(24.dp),
    ) {
        Spacer(Modifier.weight(1f))
        Text("Bring your old photos in", style = MaterialTheme.typography.titleLarge, color = Palette.Ink)
        Spacer(Modifier.height(12.dp))
        Text(
            "Point your camera at a print or a whole album page. Attic finds each photo, straightens it " +
                "and saves a clean copy. Nothing leaves your phone.",
            style = MaterialTheme.typography.bodyLarge,
            color = Palette.Muted,
        )
        Spacer(Modifier.height(28.dp))
        PrimaryButton("Allow camera", onClick = onAllow, modifier = Modifier.fillMaxWidth())
    }
}
