package io.github.darkxace01.attic.timeline

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.view.View
import android.widget.RemoteViews
import io.github.darkxace01.attic.MainActivity
import io.github.darkxace01.attic.R
import io.github.darkxace01.attic.data.PhotoDate
import io.github.darkxace01.attic.library
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.time.LocalDate

/**
 * On this day, on the home screen: a print from today in another year (or this week, this month, or a
 * photo for the day, the way the timeline picks it), with when it was. Tap it to open the photo in Attic.
 * Paper by day, midnight when the phone is dark. Only scans: the phone's own photos are in its gallery.
 */
class MemoryWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        val pending = goAsync()
        scope.launch {
            try { render(context, manager, ids) } finally { pending.finish() }
        }
    }

    companion object {
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        /** Extra on the launch intent: the print to open. */
        const val OPEN_PRINT = "io.github.darkxace01.attic.OPEN_PRINT"

        /** Redraws every widget, after the library changed. Cheap when there are none. */
        fun refresh(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, MemoryWidget::class.java))
            if (ids.isEmpty()) return
            scope.launch { render(context.applicationContext, manager, ids) }
        }

        private fun render(context: Context, manager: AppWidgetManager, ids: IntArray) {
            val library = context.library
            val dao = library.dao
            val today = LocalDate.now()
            val albums = dao.albums().associate { it.id to it.date }
            val memory = OnThisDay.pick(dao.allPrints(), albums, today)
            val views = RemoteViews(context.packageName, R.layout.widget_memory)
            val open = Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            if (memory == null || memory.prints.isEmpty()) {
                views.setViewVisibility(R.id.widget_photo, View.GONE)
                views.setTextViewText(R.id.widget_title, "On this day")
                views.setTextViewText(R.id.widget_line, "Scan a few photos and they'll come back here on their days.")
            } else {
                // A different one of the day's photos each time it's redrawn through the day.
                val (p, d) = memory.prints[((System.currentTimeMillis() / UPDATE_MS) % memory.prints.size).toInt()]
                views.setViewVisibility(R.id.widget_photo, View.VISIBLE)
                small(library.displayFile(p).path)?.let { views.setImageViewBitmap(R.id.widget_photo, it) }
                views.setTextViewText(R.id.widget_title, memory.title)
                views.setTextViewText(R.id.widget_line, line(d, today))
                open.putExtra(OPEN_PRINT, p.id)
            }
            val pi = PendingIntent.getActivity(context, 0, open, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            views.setOnClickPendingIntent(R.id.widget_root, pi)
            manager.updateAppWidget(ids, views)
        }

        internal fun line(d: PhotoDate?, today: LocalDate): String =
            listOfNotNull(d?.label, OnThisDay.ago(d, today)?.replaceFirstChar { it.lowercase() }).joinToString(", ").ifEmpty { "No date yet" }

        private const val UPDATE_MS = 3 * 60 * 60 * 1000L

        /** The photo at most [SIDE] on its long side: a widget's bitmap has to stay small. */
        private fun small(path: String): Bitmap? = runCatching {
            val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(path, o)
            var sample = 1
            while (maxOf(o.outWidth, o.outHeight) / (sample * 2) >= SIDE) sample *= 2
            val b = BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
            val scale = SIDE.toFloat() / maxOf(b.width, b.height)
            if (scale < 1f) Bitmap.createScaledBitmap(b, (b.width * scale).toInt(), (b.height * scale).toInt(), true) else b
        }.getOrNull()

        private const val SIDE = 640
    }
}
