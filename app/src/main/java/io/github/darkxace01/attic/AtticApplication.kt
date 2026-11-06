package io.github.darkxace01.attic

import android.app.Application
import android.util.Log
import io.github.darkxace01.attic.data.Backup
import io.github.darkxace01.attic.data.Library
import io.github.darkxace01.attic.vision.EdgeFit
import io.github.darkxace01.attic.vision.EdgeSnap
import io.github.darkxace01.attic.vision.PrintNet
import io.github.darkxace01.attic.vision.Quad
import org.opencv.core.Mat
import org.opencv.android.OpenCVLoader

class AtticApplication : Application() {
    val library by lazy { Library(this) }
    val backup by lazy { Backup(this, library) }
    val albumFile by lazy { io.github.darkxace01.attic.data.AlbumFile(this, library, backup) }

    /** The print finder, shared by the camera and the crop screen. Loaded once, it's thread-safe. */
    val printNet by lazy { PrintNet(assets.open("printnet.onnx").use { it.readBytes() }) }

    override fun onCreate() {
        super.onCreate()
        if (!OpenCVLoader.initLocal()) Log.e("Attic", "OpenCV failed to load")
    }
}

val android.content.Context.library: Library get() = (applicationContext as AtticApplication).library

val android.content.Context.backup: Backup get() = (applicationContext as AtticApplication).backup

val android.content.Context.albumFile: io.github.darkxace01.attic.data.AlbumFile get() = (applicationContext as AtticApplication).albumFile

val android.content.Context.printNet: PrintNet get() = (applicationContext as AtticApplication).printNet

/**
 * The final outline of a print in a full-resolution photo, from a rough one. Two candidates: the rough
 * outline with each side fitted to the real edge within [edgeSearch] (a share of the diagonal), and the
 * network's close look at just that print, fitted the same way. The close look fixes outlines that were
 * off by a sliver of album page, but on a print it can't read well it can be worse, so the candidate that
 * sits on the stronger real edges wins.
 */
fun PrintNet.finalOutline(full: Mat, rough: Quad, edgeSearch: Float = 0.012f): Quad {
    fun fit(q: Quad) = EdgeSnap.snap(full, q, (EdgeFit.diagonal(q) * edgeSearch).coerceAtLeast(4f))
    val plain = fit(rough)
    val close = refineAt(full, rough)?.let(::fit) ?: return plain
    return if (EdgeSnap.support(full, close) >= EdgeSnap.support(full, plain)) close else plain
}
