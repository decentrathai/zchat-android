package co.electriccoin.zcash.ui.screen.chat.filesharing

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import android.util.Log
import java.io.File

/**
 * Thin wrapper around [MediaRecorder] for one-shot voice-message capture.
 *
 * Format: AAC-LC in MP4 container (.m4a), 44.1 kHz mono, 64 kbit/s — universally
 * playable on Android and iOS, small enough to upload over cellular (~480 KB/min).
 * Recordings cap at 60s to keep blob size predictable for the NIP-96/Blossom relay.
 *
 * Lifecycle:
 *   val r = AudioRecorder.start(ctx)              // throws on mic-permission or hardware failure
 *   ...user holds mic...
 *   val file = r.stop()                            // returns the .m4a or null if record was too short
 *
 * The caller is responsible for deleting the file after upload.
 */
class AudioRecorder private constructor(
    private val recorder: MediaRecorder,
    private val outputFile: File,
    private val startedAtMillis: Long,
) {
    companion object {
        private const val TAG = "AudioRecorder"
        const val MAX_DURATION_MS = 60_000L
        private const val MIN_DURATION_MS = 500L
        private const val SAMPLE_RATE = 44_100
        private const val BITRATE = 64_000

        fun start(context: Context): AudioRecorder {
            val dir = File(context.cacheDir, "zchat_audio").apply { mkdirs() }
            val outFile = File(dir, "rec_${System.currentTimeMillis()}.m4a")

            @Suppress("DEPRECATION")
            val r = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(context)
            } else {
                MediaRecorder()
            }
            r.setAudioSource(MediaRecorder.AudioSource.MIC)
            r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            r.setAudioSamplingRate(SAMPLE_RATE)
            r.setAudioEncodingBitRate(BITRATE)
            r.setAudioChannels(1)
            r.setMaxDuration(MAX_DURATION_MS.toInt())
            r.setOutputFile(outFile.absolutePath)
            try {
                r.prepare()
                r.start()
            } catch (e: Exception) {
                runCatching { r.release() }
                outFile.delete()
                throw e
            }
            return AudioRecorder(r, outFile, System.currentTimeMillis())
        }
    }

    val durationMs: Long get() = System.currentTimeMillis() - startedAtMillis

    /**
     * Stop the recording. Returns the file on success, or null if the clip was so
     * short the caller should treat it as a mis-tap.
     */
    fun stop(): File? {
        return try {
            recorder.stop()
            recorder.release()
            if (durationMs < MIN_DURATION_MS) {
                outputFile.delete()
                null
            } else {
                outputFile
            }
        } catch (e: Exception) {
            // MediaRecorder.stop() throws when called before any frame is captured.
            Log.w(TAG, "Recorder stop failed: ${e.message}")
            runCatching { recorder.release() }
            outputFile.delete()
            null
        }
    }

    /** Discard the in-progress recording. Safe to call at any time. */
    fun cancel() {
        runCatching { recorder.stop() }
        runCatching { recorder.release() }
        outputFile.delete()
    }
}
