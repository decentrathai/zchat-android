package co.electriccoin.zcash.ui.call

/**
 * ZC-1469: the sounds of a call that has not connected yet. The callee hears a looping ringtone while it rings, the
 * caller a ringback tone while it dials; [stop] silences both.
 */
interface CallRingPlayer {
    fun startRingtone()

    fun startRingback()

    fun stop()
}

/**
 * ZC-1469: picks the sound for each call state. Ringing plays the ringtone and Dialling the ringback; every other state
 * (accepted, connecting, in call, ended for any reason, idle) plays nothing. [release] silences it on teardown and
 * shutdown. Confined to the caller's thread (VoiceCallManager's call thread).
 */
class CallRingController(
    private val player: CallRingPlayer,
) {
    private enum class Sound { NONE, RINGTONE, RINGBACK }

    private var playing = Sound.NONE

    fun onState(state: VoiceCallManager.CallState) {
        val wanted =
            when (state) {
                is VoiceCallManager.CallState.Ringing -> Sound.RINGTONE
                is VoiceCallManager.CallState.Dialling -> Sound.RINGBACK
                else -> Sound.NONE
            }
        if (wanted == playing) return
        if (playing != Sound.NONE) player.stop()
        playing = wanted
        when (wanted) {
            Sound.RINGTONE -> player.startRingtone()
            Sound.RINGBACK -> player.startRingback()
            Sound.NONE -> Unit
        }
    }

    fun release() {
        player.stop()
        playing = Sound.NONE
    }
}
