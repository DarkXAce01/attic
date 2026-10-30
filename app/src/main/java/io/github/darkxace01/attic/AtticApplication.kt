package io.github.darkxace01.attic

import android.app.Application
import android.util.Log
import io.github.darkxace01.attic.data.Library
import org.opencv.android.OpenCVLoader

class AtticApplication : Application() {
    val library by lazy { Library(this) }

    override fun onCreate() {
        super.onCreate()
        if (!OpenCVLoader.initLocal()) Log.e("Attic", "OpenCV failed to load")
    }
}

val android.content.Context.library: Library get() = (applicationContext as AtticApplication).library
