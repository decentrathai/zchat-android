package co.electriccoin.zcash.ui.service

import android.content.pm.ServiceInfo

/**
 * ZC-1555: the foreground-service types of [SyncForegroundService], which holds the relay inbox (calls, Open and Tunnel
 * messages) besides the wallet sync. From Android 14 (API 34) the base is REMOTE_MESSAGING, Android's type for an app
 * that keeps messages flowing between devices, with no daily limit; below it DATA_SYNC, as before (no limit there).
 * DATA_SYNC from Android 15 may run 6 hours a day: then onTimeout stopped the service and an idle phone missed calls.
 * Pure: the SDK level is a parameter.
 */
object SyncServiceTypes {
    private const val UPSIDE_DOWN_CAKE = 34

    /** The service's type at SDK level [sdkInt] while no call is live. */
    fun base(sdkInt: Int): Int =
        if (sdkInt >= UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_REMOTE_MESSAGING
        } else {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        }

    /** The type while a call is live: the base plus the microphone ([mic]) and, for video, the camera ([cam]). */
    fun forCall(
        sdkInt: Int,
        mic: Boolean,
        cam: Boolean,
    ): Int {
        var type = base(sdkInt)
        if (mic) type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        if (cam) type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
        return type
    }
}
