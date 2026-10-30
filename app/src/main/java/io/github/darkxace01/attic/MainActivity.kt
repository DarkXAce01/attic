package io.github.darkxace01.attic

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import io.github.darkxace01.attic.ui.theme.AtticTheme
import org.opencv.android.OpenCVLoader

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!OpenCVLoader.initLocal()) Log.e("Attic", "OpenCV failed to load")
        enableEdgeToEdge()
        setContent {
            AtticTheme { AtticApp() }
        }
    }
}
