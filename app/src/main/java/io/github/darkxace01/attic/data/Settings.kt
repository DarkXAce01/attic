package io.github.darkxace01.attic.data

import android.content.Context
import io.github.darkxace01.attic.vision.Enhance
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** The few choices Attic remembers. */
data class Prefs(
    /** The look new scans start with: [Enhance] off, natural or strong. */
    val newScanLook: Int = Enhance.NATURAL,
    /** New scans also go into the phone's gallery (Pictures/Attic). */
    val saveToGallery: Boolean = true,
    val autoCapture: Boolean = true,
    val glareFree: Boolean = false,
    /** Each shot waits on a check of its outlines before it's saved, like a document scanner. */
    val checkEachShot: Boolean = true,
)

class Settings(context: Context) {
    private val sp = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val _prefs = MutableStateFlow(read())
    val prefs: StateFlow<Prefs> = _prefs
    val now: Prefs get() = _prefs.value

    private fun read() = Prefs(
        newScanLook = sp.getInt("newScanLook", Enhance.NATURAL),
        saveToGallery = sp.getBoolean("saveToGallery", true),
        autoCapture = sp.getBoolean("autoCapture", true),
        glareFree = sp.getBoolean("glareFree", false),
        checkEachShot = sp.getBoolean("checkEachShot", true),
    )

    fun update(change: (Prefs) -> Prefs) {
        val p = change(_prefs.value)
        sp.edit()
            .putInt("newScanLook", p.newScanLook)
            .putBoolean("saveToGallery", p.saveToGallery)
            .putBoolean("autoCapture", p.autoCapture)
            .putBoolean("glareFree", p.glareFree)
            .putBoolean("checkEachShot", p.checkEachShot)
            .apply()
        _prefs.value = p
    }
}
