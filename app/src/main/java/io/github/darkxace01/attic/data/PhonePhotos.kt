package io.github.darkxace01.attic.data

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.flowOn
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** A photo in the phone's own gallery: where it is, and the day it was taken. Never copied into Attic. */
data class PhonePhoto(val id: Long, val uri: String, val taken: LocalDate, val width: Int, val height: Int)

/**
 * The phone's photos, read from Android's media index where they are. Attic's own saved scans (under
 * Pictures/Attic) are left out: they're already on the timeline as themselves.
 */
object PhonePhotos {
    /** The permissions that let Attic read the phone's photos. Android 14 can give just some of them. */
    val permissions: Array<String> = when {
        Build.VERSION.SDK_INT >= 34 -> arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
        Build.VERSION.SDK_INT >= 33 -> arrayOf(Manifest.permission.READ_MEDIA_IMAGES)
        else -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
    }

    fun allowed(context: Context): Boolean = permissions.any { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }

    /** All of them, again whenever the gallery changes. Empty without permission. */
    fun watch(context: Context): Flow<List<PhonePhoto>> = callbackFlow {
        val resolver = context.contentResolver
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) { trySend(Unit) }
        }
        resolver.registerContentObserver(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, true, observer)
        trySend(Unit)
        awaitClose { resolver.unregisterContentObserver(observer) }
    }.conflate().let { ticks ->
        kotlinx.coroutines.flow.flow { ticks.collect { emit(read(context)) } }
    }.flowOn(Dispatchers.IO)

    fun read(context: Context): List<PhonePhoto> {
        if (!allowed(context)) return emptyList()
        val cols = arrayOf(
            MediaStore.Images.Media._ID, MediaStore.Images.Media.DATE_TAKEN, MediaStore.Images.Media.DATE_ADDED,
            MediaStore.Images.Media.WIDTH, MediaStore.Images.Media.HEIGHT,
        )
        val zone = ZoneId.systemDefault()
        val out = ArrayList<PhonePhoto>()
        runCatching {
            context.contentResolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cols,
                "${MediaStore.Images.Media.RELATIVE_PATH} NOT LIKE ?", arrayOf("Pictures/Attic/%"),
                "${MediaStore.Images.Media.DATE_TAKEN} ASC",
            )?.use { c ->
                val id = c.getColumnIndexOrThrow(cols[0]); val taken = c.getColumnIndexOrThrow(cols[1]); val added = c.getColumnIndexOrThrow(cols[2])
                val w = c.getColumnIndexOrThrow(cols[3]); val h = c.getColumnIndexOrThrow(cols[4])
                while (c.moveToNext()) {
                    val ms = c.getLong(taken).takeIf { it > 0 } ?: (c.getLong(added) * 1000)
                    if (ms <= 0) continue
                    val i = c.getLong(id)
                    out += PhonePhoto(
                        i, ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, i).toString(),
                        Instant.ofEpochMilli(ms).atZone(zone).toLocalDate(), c.getInt(w), c.getInt(h),
                    )
                }
            }
        }
        return out
    }
}
