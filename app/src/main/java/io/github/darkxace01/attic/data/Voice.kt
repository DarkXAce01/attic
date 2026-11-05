package io.github.darkxace01.attic.data

import android.content.Context
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.os.Build
import android.os.SystemClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File

/**
 * Records one story at a time into the cache, as AAC in an m4a file: small, and any phone or computer
 * plays it. Stories are kept by moving the file into the library ([Library.addStory]).
 */
class Recorder(private val context: Context) {
    private var rec: MediaRecorder? = null
    private var file: File? = null
    private var startedAt = 0L

    val recording: Boolean get() = rec != null

    /** Milliseconds since the recording started. */
    val elapsed: Long get() = if (rec != null) SystemClock.elapsedRealtime() - startedAt else 0L

    /** How loud the voice is right now, 0..1, for a level meter. */
    fun level(): Float = runCatching { (rec?.maxAmplitude ?: 0) / 32767f }.getOrDefault(0f).coerceIn(0f, 1f)

    fun start() {
        cancel()
        val f = File(context.cacheDir, "story_${System.nanoTime()}.m4a")
        @Suppress("DEPRECATION")
        val r = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(context) else MediaRecorder()
        r.setAudioSource(MediaRecorder.AudioSource.MIC)
        r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
        r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
        r.setAudioChannels(1)
        r.setAudioSamplingRate(44100)
        r.setAudioEncodingBitRate(96_000)
        r.setOutputFile(f.path)
        r.prepare()
        r.start()
        rec = r
        file = f
        startedAt = SystemClock.elapsedRealtime()
    }

    /** Stops and gives the recording and its length, or null if it was too short to be a story. */
    fun stop(): Pair<File, Long>? {
        val r = rec ?: return null
        val f = file
        val ms = elapsed
        rec = null
        file = null
        // stop() throws when nothing was recorded yet; there's no story then.
        val ok = runCatching { r.stop() }.isSuccess
        r.release()
        if (!ok || f == null || ms < MIN_MS) {
            f?.delete()
            return null
        }
        return f to ms
    }

    fun cancel() {
        rec?.let { r -> runCatching { r.stop() }; r.release() }
        rec = null
        file?.delete()
        file = null
    }

    companion object {
        /** Shorter than this is a slip of the finger, not a story. */
        const val MIN_MS = 1000L
    }
}

/** Plays one story at a time. [playing] is the story playing, [progress] how far through it is (0..1). */
class StoryPlayer {
    private var player: MediaPlayer? = null
    private val _playing = MutableStateFlow<Long?>(null)
    val playing: StateFlow<Long?> = _playing

    val progress: Float
        get() = player?.let { p -> runCatching { p.currentPosition.toFloat() / p.duration.coerceAtLeast(1) }.getOrDefault(0f) } ?: 0f

    fun play(id: Long, file: File) {
        stop()
        val p = MediaPlayer()
        runCatching {
            p.setDataSource(file.path)
            p.setOnCompletionListener { stop() }
            p.prepare()
            p.start()
        }.onFailure {
            p.release()
            return
        }
        player = p
        _playing.value = id
    }

    fun stop() {
        player?.let { p -> runCatching { p.stop() }; p.release() }
        player = null
        _playing.value = null
    }
}

/** 0:07, 1:24, 12:03. */
fun storyLength(ms: Long): String {
    val s = (ms + 500) / 1000
    return "${s / 60}:${"%02d".format(s % 60)}"
}
