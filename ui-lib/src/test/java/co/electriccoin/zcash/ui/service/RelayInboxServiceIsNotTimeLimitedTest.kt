package co.electriccoin.zcash.ui.service

import co.electriccoin.zcash.ui.screen.chat.view.ChatResourceText
import org.junit.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * ZC-1555 (high; owner, 6 Nov 2026: a call from the HONOR to the locked Seeker did nothing until ZChat was opened).
 * The service that holds the relay inbox (calls, Open and Tunnel messages) ran as a DATA_SYNC foreground service. From
 * Android 15 a DATA_SYNC service may run 6 hours a day: then onTimeout stops it, ZChat drops to the background, Android 16
 * blocks its network there (netpolicy blocked=APP_BACKGROUND on the Seeker), and nothing arrives until the app is opened
 * or a 15-minute alarm wins a start. Android 14 added REMOTE_MESSAGING for exactly this ("continue text messages from
 * one device to another"), with no daily limit. From API 34 the service's base type is REMOTE_MESSAGING; below it
 * DATA_SYNC as before (no limit there). A call adds the microphone (and the camera for video) to that base.
 *
 * At RED there is no SyncServiceTypes, and the manifest declares neither the type nor its permission.
 */
class RelayInboxServiceIsNotTimeLimitedTest {
    private val dataSync = 1 // ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
    private val microphone = 128 // FOREGROUND_SERVICE_TYPE_MICROPHONE
    private val camera = 64 // FOREGROUND_SERVICE_TYPE_CAMERA
    private val remoteMessaging = 512 // FOREGROUND_SERVICE_TYPE_REMOTE_MESSAGING (API 34)

    private fun types(): Class<*> = Class.forName("co.electriccoin.zcash.ui.service.SyncServiceTypes")

    private fun base(sdk: Int): Int {
        val k = types()
        return k
            .getMethod("base", Int::class.javaPrimitiveType)
            .invoke(k.getField("INSTANCE").get(null), sdk) as Int
    }

    private fun call(sdk: Int, mic: Boolean, cam: Boolean): Int {
        val k = types()
        return k
            .getMethod(
                "forCall",
                Int::class.javaPrimitiveType,
                Boolean::class.javaPrimitiveType,
                Boolean::class.javaPrimitiveType,
            ).invoke(k.getField("INSTANCE").get(null), sdk, mic, cam) as Int
    }

    @Test
    fun `from Android 14 the inbox service is a remote-messaging service, below it data sync`() {
        assertEquals(remoteMessaging, base(34))
        assertEquals(remoteMessaging, base(36))
        assertEquals(dataSync, base(33))
        assertEquals(dataSync, base(29))
    }

    @Test
    fun `a call adds the microphone and for video the camera to the base type`() {
        assertEquals(remoteMessaging or microphone, call(35, mic = true, cam = false))
        assertEquals(remoteMessaging or microphone or camera, call(35, mic = true, cam = true))
        assertEquals(remoteMessaging, call(35, mic = false, cam = false))
        assertEquals(dataSync or microphone, call(30, mic = true, cam = false))
    }

    @Test
    fun `the manifest declares the type and its permission`() {
        val manifest = File(ChatResourceText.repoRoot, "ui-lib/src/main/AndroidManifest.xml").readText()
        assertTrue(
            "android.permission.FOREGROUND_SERVICE_REMOTE_MESSAGING" in manifest,
            "FOREGROUND_SERVICE_REMOTE_MESSAGING is not requested",
        )
        val service =
            Regex("""<service[^>]*SyncForegroundService[^>]*>""", RegexOption.DOT_MATCHES_ALL)
                .find(manifest)
                ?.value
                .orEmpty()
        val declared =
            Regex("""foregroundServiceType="([^"]*)"""")
                .find(service)
                ?.groupValues
                ?.get(1)
                .orEmpty()
        assertTrue("remoteMessaging" in declared.split('|'), "SyncForegroundService types are '$declared'")
        assertTrue("dataSync" in declared.split('|'), "below API 34 the service still needs dataSync")
    }

    @Test
    fun `the service promotes with the helper's types, not a fixed DATA_SYNC`() {
        val src =
            File(
                ChatResourceText.repoRoot,
                "ui-lib/src/main/java/co/electriccoin/zcash/ui/service/SyncForegroundService.kt",
            ).readText()
        val fixed = Regex("""startForeground\([^)]*FOREGROUND_SERVICE_TYPE_DATA_SYNC""", RegexOption.DOT_MATCHES_ALL)
        assertTrue(!fixed.containsMatchIn(src), "a startForeground call still passes DATA_SYNC directly")
        assertTrue("SyncServiceTypes.base(" in src && "SyncServiceTypes.forCall(" in src)
    }
}
