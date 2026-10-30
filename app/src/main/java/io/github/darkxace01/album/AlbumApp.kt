package io.github.darkxace01.album

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.darkxace01.album.prints.PrintViewer
import io.github.darkxace01.album.prints.PrintsScreen
import io.github.darkxace01.album.scan.ScanScreen
import io.github.darkxace01.album.scan.ScanViewModel
import io.github.darkxace01.album.store.PrintStore
import io.github.darkxace01.album.store.SavedPrint
import io.github.darkxace01.album.ui.theme.Palette

private enum class Screen { Scan, Prints, Viewer }

@Composable
fun AlbumApp() {
    val context = LocalContext.current
    var hasCamera by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    val askCamera = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { hasCamera = it }

    if (!hasCamera) {
        CameraIntro(onAllow = { askCamera.launch(Manifest.permission.CAMERA) })
        return
    }

    val store = remember { PrintStore(context.applicationContext) }
    val scanVm: ScanViewModel = viewModel()
    var screen by rememberSaveable { mutableStateOf(Screen.Scan) }
    var prints by remember { mutableStateOf(emptyList<SavedPrint>()) }
    var open by remember { mutableStateOf<SavedPrint?>(null) }

    when (screen) {
        Screen.Scan -> ScanScreen(scanVm, onOpenPrints = {
            prints = store.all()
            screen = Screen.Prints
        })
        Screen.Prints -> PrintsScreen(
            prints = prints,
            onBack = { screen = Screen.Scan },
            onOpen = { open = it; screen = Screen.Viewer },
        )
        Screen.Viewer -> open?.let { p ->
            PrintViewer(
                print = p,
                onBack = { screen = Screen.Prints },
                onDelete = {
                    store.delete(it)
                    prints = store.all()
                    scanVm.refreshCount()
                    screen = Screen.Prints
                },
            )
        } ?: run { screen = Screen.Prints }
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
            "Point your camera at a print or a whole album page. Album finds each photo, straightens it " +
                "and saves a clean copy. Nothing leaves your phone.",
            style = MaterialTheme.typography.bodyLarge,
            color = Palette.Muted,
        )
        Spacer(Modifier.height(28.dp))
        Button(
            onClick = onAllow,
            shape = RoundedCornerShape(14.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Palette.Accent, contentColor = Palette.OnAccent),
            modifier = Modifier
                .fillMaxWidth()
                .height(54.dp),
        ) {
            Text("Allow camera", style = MaterialTheme.typography.labelLarge)
        }
    }
}
