package io.github.darkxace01.attic.thennow

import android.util.Rational
import android.view.Surface
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.UseCaseGroup
import androidx.camera.core.ViewPort
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import io.github.darkxace01.attic.data.PrintEntity
import io.github.darkxace01.attic.library
import io.github.darkxace01.attic.ui.PrimaryButton
import io.github.darkxace01.attic.ui.QuietButton
import io.github.darkxace01.attic.ui.TopBar
import io.github.darkxace01.attic.ui.theme.Palette
import io.github.darkxace01.attic.vision.FrameTransform
import kotlinx.coroutines.launch
import java.util.concurrent.Executors

/**
 * Then and now. Stand where the old photo was taken, line it up over the camera, and take it again; then
 * slide between the two.
 */
@Composable
fun ThenNowScreen(printId: Long, onBack: () -> Unit) {
    val library = LocalContext.current.library
    val print by remember(printId) { library.print(printId) }.collectAsStateWithLifecycle(null)
    val p = print ?: return
    // Bumped when today's photo changes, so it's read again.
    var tick by remember { mutableIntStateOf(0) }
    val now = library.nowFile(p).takeIf { it.exists() }
    var camera by remember { mutableStateOf(now == null) }
    var note by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    Column(Modifier.fillMaxSize()) {
        TopBar("Then and now", onBack = if (camera && now != null) ({ camera = false }) else onBack)
        if (camera) {
            LineUp(p, onTaken = { matched ->
                tick++
                camera = false
                note = if (matched) null else "Couldn't match it to the old photo, so it's kept as you held it."
            })
        } else if (now != null) {
            Compare(p, now, tick, Modifier.fillMaxWidth().weight(1f))
            Column(Modifier.padding(horizontal = 20.dp, vertical = 12.dp).navigationBarsPadding()) {
                note?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = Palette.Muted) }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    QuietButton("Take it again", onClick = { note = null; camera = true })
                    Spacer(Modifier.weight(1f))
                    QuietButton("Delete", color = Palette.Warning, onClick = { scope.launch { library.deleteNow(printId); tick++; onBack() } })
                }
            }
        }
    }
}

/** Old on the right of the line, today on the left; drag the line, or tap to jump it there. */
@Composable
private fun Compare(p: PrintEntity, now: java.io.File, tick: Int, modifier: Modifier) {
    val library = LocalContext.current.library
    val context = LocalContext.current
    var split by remember { mutableFloatStateOf(0.5f) }
    Box(modifier.padding(horizontal = 16.dp), contentAlignment = Alignment.Center) {
        BoxWithConstraints(
            Modifier
                .aspectRatio(p.width.toFloat() / p.height.coerceAtLeast(1))
                .clip(RoundedCornerShape(6.dp))
                .pointerInput(Unit) {
                    detectTapGestures { split = (it.x / size.width).coerceIn(0f, 1f) }
                }
                .pointerInput(Unit) {
                    detectDragGestures { change, _ -> split = (change.position.x / size.width).coerceIn(0f, 1f) }
                },
        ) {
            AsyncImage(library.displayFile(p), "Then", contentScale = ContentScale.FillBounds, modifier = Modifier.fillMaxSize())
            Box(
                Modifier.fillMaxSize().graphicsLayer {
                    clip = true
                    shape = object : androidx.compose.ui.graphics.Shape {
                        override fun createOutline(size: androidx.compose.ui.geometry.Size, layoutDirection: androidx.compose.ui.unit.LayoutDirection, density: androidx.compose.ui.unit.Density) =
                            androidx.compose.ui.graphics.Outline.Rectangle(androidx.compose.ui.geometry.Rect(0f, 0f, size.width * split, size.height))
                    }
                },
            ) {
                AsyncImage(
                    ImageRequest.Builder(context).data(now).memoryCacheKey("${now.path}#$tick#${now.lastModified()}").crossfade(false).build(),
                    "Now", contentScale = ContentScale.FillBounds, modifier = Modifier.fillMaxSize(),
                )
            }
            Canvas(Modifier.fillMaxSize()) {
                val x = size.width * split
                drawLine(Color.White, Offset(x, 0f), Offset(x, size.height), 2.dp.toPx())
                drawCircle(Color.White, 12.dp.toPx(), Offset(x, size.height / 2))
            }
            Label("Now", Modifier.align(Alignment.TopStart).padding(10.dp))
            Label("Then", Modifier.align(Alignment.TopEnd).padding(10.dp))
        }
    }
}

@Composable
private fun Label(text: String, modifier: Modifier) {
    Text(
        text, style = MaterialTheme.typography.labelLarge, color = Color.White,
        modifier = modifier.clip(RoundedCornerShape(50)).background(Color.Black.copy(alpha = 0.55f)).padding(horizontal = 10.dp, vertical = 4.dp),
    )
}

/** The camera with the old photo laid over it, see-through, to stand in the same spot. */
@Composable
private fun LineUp(p: PrintEntity, onTaken: (matched: Boolean) -> Unit) {
    val context = LocalContext.current
    val library = context.library
    val owner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    var see by remember { mutableFloatStateOf(0.5f) }
    var working by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val capture = remember {
        ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
            .setResolutionSelector(ResolutionSelector.Builder().setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY).build())
            .build()
    }
    val executor = remember { Executors.newSingleThreadExecutor() }
    val previewView = remember {
        PreviewView(context).apply {
            scaleType = PreviewView.ScaleType.FIT_CENTER
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        }
    }
    DisposableEffect(owner) {
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            val provider = future.get()
            val preview = Preview.Builder()
                .setResolutionSelector(ResolutionSelector.Builder().setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY).build())
                .build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
            // Same view in the preview and the photo, so where the old print sits over the preview is
            // where it sits in the photo.
            val group = UseCaseGroup.Builder()
                .setViewPort(ViewPort.Builder(Rational(3, 4), Surface.ROTATION_0).setScaleType(ViewPort.FIT).build())
                .addUseCase(preview).addUseCase(capture).build()
            provider.unbindAll()
            runCatching { provider.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, group) }
                .onFailure { error = "Camera unavailable: ${it.message}" }
        }, ContextCompat.getMainExecutor(context))
        onDispose {
            if (future.isDone) future.get().unbindAll()
            executor.shutdown()
        }
    }
    // The old print fitted inside the 3:4 view: where it sits, as fractions of the view.
    val ar = p.width.toFloat() / p.height.coerceAtLeast(1)
    val guess = if (ar > 0.75f) {
        val h = 0.75f / ar; floatArrayOf(0f, (1 - h) / 2, 1f, (1 + h) / 2)
    } else {
        val w = ar / 0.75f; floatArrayOf((1 - w) / 2, 0f, (1 + w) / 2, 1f)
    }

    fun take() {
        working = true
        error = null
        capture.takePicture(executor, object : ImageCapture.OnImageCapturedCallback() {
            override fun onCaptureSuccess(image: ImageProxy) {
                val crop = image.cropRect
                val frame = FrameTransform(image.width, image.height, crop.left, crop.top, crop.width(), crop.height(), image.imageInfo.rotationDegrees)
                val buffer = image.planes[0].buffer
                val jpeg = ByteArray(buffer.remaining()).also(buffer::get)
                image.close()
                scope.launch {
                    val matched = library.saveNow(p.id, jpeg, frame, guess)
                    working = false
                    if (matched == null) error = "Couldn't keep the photo." else onTaken(matched)
                }
            }

            override fun onError(exception: ImageCaptureException) {
                scope.launch { working = false; error = "Couldn't take the photo: ${exception.message}" }
            }
        })
    }

    Column(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxWidth().aspectRatio(3f / 4f).clipToBounds().background(Color.Black)) {
            AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())
            AsyncImage(
                library.displayFile(p), null, contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize().graphicsLayer { alpha = see },
            )
        }
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp).navigationBarsPadding()) {
            Text(
                error ?: if (working) "Lining it up…" else "Stand where it was taken and line the old photo up over today.",
                style = MaterialTheme.typography.bodyMedium, color = if (error != null) Palette.Warning else Palette.Muted,
            )
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Today", style = MaterialTheme.typography.labelLarge, color = Palette.Muted)
                Slider(
                    value = see, onValueChange = { see = it },
                    colors = SliderDefaults.colors(thumbColor = Palette.Ink, activeTrackColor = Palette.Muted, inactiveTrackColor = Palette.SurfaceHigh),
                    modifier = Modifier.weight(1f),
                )
                Text("Then", style = MaterialTheme.typography.labelLarge, color = Palette.Muted)
            }
            Spacer(Modifier.height(8.dp))
            Row {
                Spacer(Modifier.weight(1f))
                PrimaryButton(if (working) "Lining it up…" else "Take it", onClick = { if (!working) take() }, enabled = !working)
                Spacer(Modifier.weight(1f))
            }
        }
    }
}
