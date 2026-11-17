package co.electriccoin.zcash.ui.call

import android.app.NotificationManager
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.media.ToneGenerator
import android.os.Build
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import androidx.core.app.NotificationManagerCompat

/**
 * ZC-1469: the phone's call sounds. The callee's ringtone is the phone's default ringtone on the ring stream, looped
 * until [stop]; it follows the ringer mode (silent: nothing, vibrate: vibration only, normal: the ringtone at the ring
 * volume). The caller's ringback is the standard ringing tone on the voice-call stream. Every call here is best effort:
 * a sound that cannot start is logged and the call goes on. Logs carry no key, id or address.
 */
class AndroidCallRingPlayer(
    private val context: Context,
) : CallRingPlayer {
    private var ringtone: MediaPlayer? = null
    private var ringback: ToneGenerator? = null
    private var vibrating = false

    // ZC-1912: wakes the screen for a ringing call when the full-screen intent is denied (HONOR's default).
    private var screenWake: PowerManager.WakeLock? = null

    override fun startRingtone() {
        stop()
        val audio = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        val ringerMode = audio?.ringerMode ?: AudioManager.RINGER_MODE_NORMAL
        logIncomingCallAlerts(ringerMode)
        wakeScreenIfFullScreenDenied()
        when (ringerMode) {
            AudioManager.RINGER_MODE_SILENT -> Unit
            AudioManager.RINGER_MODE_VIBRATE -> startVibration()
            else -> startLoopingRingtone()
        }
    }

    override fun startRingback() {
        stop()
        ringback =
            runCatching {
                ToneGenerator(AudioManager.STREAM_VOICE_CALL, RINGBACK_VOLUME).also {
                    // The supervisory ringing tone repeats (2 s on, 4 s off) until stopTone().
                    it.startTone(ToneGenerator.TONE_SUP_RINGTONE)
                }
            }.onFailure { Log.w(TAG, "ringback could not start: ${it.javaClass.simpleName}") }
                .getOrNull()
    }

    override fun stop() {
        ringtone?.let { player ->
            runCatching { player.stop() }
            runCatching { player.release() }
        }
        ringtone = null
        ringback?.let { tone ->
            runCatching { tone.stopTone() }
            runCatching { tone.release() }
        }
        ringback = null
        if (vibrating) runCatching { vibrator()?.cancel() }
        vibrating = false
        screenWake?.let { lock -> runCatching { if (lock.isHeld) lock.release() } }
        screenWake = null
    }

    /**
     * ZC-1912 (HONOR, owner test 16 Nov: every call card posted FSI_REQUESTED_BUT_DENIED, the screen stayed off): with
     * the full-screen intent denied, a ringing call still turns the screen on, so the lock screen shows the call card
     * (ZC-1924: its Answer then opens the call screen, which the phone may first ask to unlock). Timed, and released
     * by [stop] when the ringing ends. Best effort: some Android versions ignore ACQUIRE_CAUSES_WAKEUP.
     */
    @Suppress("DEPRECATION")
    private fun wakeScreenIfFullScreenDenied() {
        if (!CallLockScreen.needsFullScreenIntentGuide(Build.VERSION.SDK_INT, fullScreenIntentAllowed())) return
        val power = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return
        screenWake =
            runCatching {
                power
                    .newWakeLock(
                        PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
                        "zchat:incoming-call-screen",
                    ).apply {
                        setReferenceCounted(false)
                        acquire(SCREEN_WAKE_MS)
                    }
            }.onFailure { Log.w(TAG, "screen wake could not start: ${it.javaClass.simpleName}") }
                .getOrNull()
        // ZC-1925: a held wake lock does not prove that the screen turned on.
        Log.i(TAG, "incoming call: full-screen intent denied, wake lock acquired=${screenWake != null}")
    }

    private fun fullScreenIntentAllowed(): Boolean? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            runCatching { context.getSystemService(NotificationManager::class.java)?.canUseFullScreenIntent() }.getOrNull()
        } else {
            true
        }

    private fun startLoopingRingtone() {
        val uri =
            RingtoneManager.getActualDefaultRingtoneUri(context, RingtoneManager.TYPE_RINGTONE)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
        if (uri == null) {
            Log.w(TAG, "no ringtone set on this phone")
            return
        }
        ringtone =
            runCatching {
                MediaPlayer().apply {
                    setAudioAttributes(
                        AudioAttributes
                            .Builder()
                            .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .build()
                    )
                    setDataSource(context, uri)
                    isLooping = true
                    prepare()
                    start()
                }
            }.onFailure { Log.w(TAG, "ringtone could not start: ${it.javaClass.simpleName}") }
                .getOrNull()
    }

    @Suppress("DEPRECATION")
    private fun startVibration() {
        val vibrator = vibrator() ?: return
        if (!vibrator.hasVibrator()) return
        runCatching {
            vibrator.vibrate(
                VibrationEffect.createWaveform(VIBRATION_PATTERN, 0),
                AudioAttributes
                    .Builder()
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                    .build()
            )
            vibrating = true
        }.onFailure { Log.w(TAG, "vibration could not start: ${it.javaClass.simpleName}") }
    }

    @Suppress("DEPRECATION")
    private fun vibrator(): Vibrator? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
        } else {
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }

    /**
     * ZC-1469: the incoming-call notification is silent when notifications are off or, on Android 14, when the
     * full-screen intent is not allowed; the log says which, so a "no ring" report can be read from logcat.
     */
    private fun logIncomingCallAlerts(ringerMode: Int) {
        val notificationsOn = runCatching { NotificationManagerCompat.from(context).areNotificationsEnabled() }.getOrNull()
        val fullScreenAllowed = fullScreenIntentAllowed()
        val mode =
            when (ringerMode) {
                AudioManager.RINGER_MODE_SILENT -> "silent"
                AudioManager.RINGER_MODE_VIBRATE -> "vibrate"
                else -> "normal"
            }
        Log.i(TAG, "incoming call: ringer=$mode notifications=$notificationsOn fullScreenIntent=$fullScreenAllowed")
    }

    private companion object {
        const val TAG = "CallRingPlayer"
        const val RINGBACK_VOLUME = 80

        // ZC-1912: as long as a call may ring (VoiceCallManager.SETUP_TIMEOUT_MS).
        const val SCREEN_WAKE_MS = 45_000L
        val VIBRATION_PATTERN = longArrayOf(0, 1000, 800)
    }
}
