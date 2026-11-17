package co.electriccoin.zcash.ui.call

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * ZC-1912 (owner W-148 "Call screen over lock"): the call-only screen. The incoming-call card's full-screen intent and
 * its Answer (when it is not the accept broadcast) open it. It shows only the call screen (CallOverlay), never the
 * wallet, and only while a call rings or runs (CallLockScreen.showOverLock) does it show over the lock screen and turn
 * the screen on; when the call is over both are cleared and it closes.
 *
 * ZC-1922: Answer through this screen asks for the microphone (and the camera for a video call) first; refused, the
 * call ends with the "Microphone permission needed" notice instead of going silent.
 */
class IncomingCallActivity : ComponentActivity() {
    private var overLock = false
    private var watchJob: Job? = null

    // ZC-1935: the call whose Answer was tapped while its permission dialog is open; kept across a recreation.
    private var pendingAnswerCallId: String? = null

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            val callId = pendingAnswerCallId
            pendingAnswerCallId = null
            val mgr = CallController.current.value ?: return@registerForActivityResult
            // ZC-1936: the result lacks RECORD_AUDIO when only the camera was asked; the phone's state decides.
            val micGranted =
                CallLockScreen.micGrantedAfterRequest(
                    result,
                    ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
                        PackageManager.PERMISSION_GRANTED,
                )
            when (CallLockScreen.answerAfterPermissions(callId, mgr.state.value, micGranted)) {
                CallLockScreen.AnswerAfterPermissions.ACCEPT -> mgr.acceptIncoming(callId)
                CallLockScreen.AnswerAfterPermissions.END_MIC_DENIED ->
                    callId?.let { mgr.hangUpIfRinging(it, CallEndReason.PermissionDenied) }
                CallLockScreen.AnswerAfterPermissions.IGNORE -> Log.w(TAG, "permission result for a call no longer ringing")
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pendingAnswerCallId = savedInstanceState?.getString(KEY_PENDING_ANSWER)
        // Over the lock screen from the first frame while a call rings or runs. ZC-1940: with no call (no manager yet)
        // nothing shows over the lock screen; watchCall() turns it on once a call is there, or closes this screen.
        val mgrAtStart = CallController.current.value
        setOverLock(mgrAtStart != null && CallLockScreen.showOverLock(mgrAtStart.state.value))
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Box(Modifier.fillMaxSize()) { CallOverlay() }
            }
        }
        watchCall()
        // ZC-1935: a recreation with the permission dialog open waits for its result instead of asking again.
        if (pendingAnswerCallId == null) handleAccept(intent)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        pendingAnswerCallId?.let { outState.putString(KEY_PENDING_ANSWER, it) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleAccept(intent)
    }

    override fun onDestroy() {
        setOverLock(false)
        super.onDestroy()
    }

    /** Keeps the lock-screen flags while a call rings or runs; afterwards clears them and closes (after the notice). */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private fun watchCall() {
        watchJob?.cancel()
        watchJob =
            lifecycleScope.launch {
                val mgr =
                    withTimeoutOrNull(MANAGER_WAIT_MS) { CallController.current.filterNotNull().first() }
                if (mgr == null) {
                    Log.w(TAG, "no call manager — closing")
                    closeCallScreen(noticeMs = 0L)
                    return@launch
                }
                var sawCall = false
                CallController.current
                    .filterNotNull()
                    .flatMapLatest { it.state }
                    .collect { state ->
                        if (CallLockScreen.showOverLock(state)) {
                            sawCall = true
                            setOverLock(true)
                        } else {
                            setOverLock(false)
                            // The ended notice stays readable for a moment before the screen closes.
                            closeCallScreen(if (sawCall) END_NOTICE_MS else 0L)
                        }
                    }
            }
    }

    private fun closeCallScreen(noticeMs: Long) {
        lifecycleScope.launch {
            if (noticeMs > 0) delay(noticeMs)
            val mgr = CallController.current.value
            if (mgr == null || !CallLockScreen.showOverLock(mgr.state.value)) finish()
        }
    }

    /**
     * Answer from the card: answers the card's call (ZC-1951: [CallNotificationController.EXTRA_CALL_ID]) once the
     * microphone is allowed, only while that call rings. Waits only for a call manager that is not there yet; a missing
     * id or another call ringing by then only shows the call screen.
     */
    private fun handleAccept(intent: Intent?) {
        if (intent?.getBooleanExtra(CallNotificationController.EXTRA_ACCEPT_CALL, false) != true) return
        intent.removeExtra(CallNotificationController.EXTRA_ACCEPT_CALL)
        val cardCallId = intent.getStringExtra(CallNotificationController.EXTRA_CALL_ID)
        intent.removeExtra(CallNotificationController.EXTRA_CALL_ID)
        lifecycleScope.launch {
            val mgr =
                withTimeoutOrNull(ACCEPT_WAIT_MS) { CallController.current.filterNotNull().first() } ?: return@launch
            val state = mgr.state.value
            if (!CallLockScreen.answerFromCard(cardCallId, state)) {
                Log.w(TAG, "Answer for a call that is not ringing — not answered")
                return@launch
            }
            val ringing = state as VoiceCallManager.CallState.Ringing
            val needed = neededPermissions(this@IncomingCallActivity, ringing.isVideo)
            if (needed.isEmpty()) {
                mgr.acceptIncoming(ringing.callId)
            } else {
                pendingAnswerCallId = ringing.callId
                permissionLauncher.launch(needed)
            }
        }
    }

    /** minSdk is 27, so setShowWhenLocked and setTurnScreenOn are always there. */
    private fun setOverLock(on: Boolean) {
        if (overLock == on) return
        overLock = on
        if (on) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            setShowWhenLocked(false)
            setTurnScreenOn(false)
        }
    }

    companion object {
        private const val TAG = "IncomingCallActivity"
        private const val MANAGER_WAIT_MS = 10_000L
        private const val ACCEPT_WAIT_MS = 15_000L
        private const val END_NOTICE_MS = 3_000L
        private const val KEY_PENDING_ANSWER = "pending_answer_call_id"

        /** The permissions an answer still needs: the microphone always, the camera for a video call. */
        fun neededPermissions(
            context: Context,
            isVideo: Boolean,
        ): Array<String> =
            buildList {
                add(Manifest.permission.RECORD_AUDIO)
                if (isVideo) add(Manifest.permission.CAMERA)
            }.filter { ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED }
                .toTypedArray()

        /** Opens the call screen; [accept] answers the call [callId] from it while it rings (ZC-1951). */
        fun intent(
            context: Context,
            accept: Boolean,
            callId: String? = null,
        ): Intent =
            Intent(context, IncomingCallActivity::class.java)
                .setAction(if (accept) ACTION_ANSWER else ACTION_SHOW)
                .putExtra(CallNotificationController.EXTRA_ACCEPT_CALL, accept)
                .putExtra(CallNotificationController.EXTRA_CALL_ID, callId)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)

        // Distinct actions keep the two PendingIntents apart under Intent.filterEquals.
        private const val ACTION_SHOW = "co.electriccoin.zcash.call.SHOW_CALL_SCREEN"
        private const val ACTION_ANSWER = "co.electriccoin.zcash.call.ANSWER_ON_CALL_SCREEN"
    }
}
