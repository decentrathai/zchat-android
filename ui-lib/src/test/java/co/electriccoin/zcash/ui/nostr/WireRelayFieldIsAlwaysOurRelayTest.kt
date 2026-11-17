package co.electriccoin.zcash.ui.nostr

import org.junit.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * RELAY-HEALTH G1 (guard; fix-all 2.13.0, train T5; SPEC.md §2 "Relay URL on the wire", §3.1): the relay field we
 * send is always ZChat's relay.
 *
 * Every KEX/KEXACK and ZBOOT (whose signature covers the relay), invite, contact code, onboarding code and message
 * request names one relay, and a 2.12.4 phone stores it. Relay health may swap slots 1 and 2 but never slot 0, so each
 * of the six wire sites must keep reading NostrRelayPool.DEFAULT_RELAYS.first() (or OWN_RELAY), and that must be
 * wss://relay.zsend.xyz. No production file may read another slot of DEFAULT_RELAYS. Passes before and after the fix.
 */
class WireRelayFieldIsAlwaysOurRelayTest {
    private val uiDir: File by lazy {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        repeat(8) {
            val current = dir ?: return@repeat
            val direct = current.resolve("src/main/java/co/electriccoin/zcash/ui")
            if (direct.isDirectory) return@lazy direct
            val nested = current.resolve("ui-lib/src/main/java/co/electriccoin/zcash/ui")
            if (nested.isDirectory) return@lazy nested
            dir = current.parentFile
        }
        fail("could not locate ui-lib's sources")
    }

    private val ownRelayRead = Regex("""NostrRelayPool\.(DEFAULT_RELAYS\.first\(\)|OWN_RELAY)""")

    @Test
    fun `the six wire sites read DEFAULT_RELAYS first or OWN_RELAY`() {
        val sites =
            mapOf(
                "screen/chat/viewmodel/ChatViewModel.kt" to 2, // accept default + getOurNostrPubkey (KEX, KEXACK, ZBOOT)
                "screen/invite/viewmodel/InviteFriendVM.kt" to 1,
                "screen/chat/viewmodel/ZchatReceiveVM.kt" to 1,
                "screen/onboarding/viewmodel/OnboardingGuideVM.kt" to 1,
                "nostr/NostrChatBridge.kt" to 1, // message request
            )
        sites.forEach { (path, count) ->
            val file = uiDir.resolve(path)
            assertTrue(file.isFile, "missing $path")
            val found = ownRelayRead.findAll(file.readText()).count()
            assertTrue(found >= count, "$path reads ZChat's relay $found time(s), expected at least $count")
        }
    }

    @Test
    fun `no production file reads another slot of DEFAULT_RELAYS`() {
        val otherSlot = Regex("""DEFAULT_RELAYS(\[|\.get\(|\.last\(|\.drop\(|\.getOrNull\(|\.elementAt\()""")
        val offenders =
            uiDir
                .walkTopDown()
                .filter { it.isFile && it.extension == "kt" && it.name != "NostrRelayPool.kt" }
                .filter { otherSlot.containsMatchIn(it.readText()) }
                .map { it.name }
                .toList()
        assertEquals(emptyList(), offenders, "a production file names a public slot of DEFAULT_RELAYS")
    }

    @Test
    fun `DEFAULT_RELAYS first is ZChat's relay`() {
        assertEquals("wss://relay.zsend.xyz", NostrRelayPool.DEFAULT_RELAYS.first())
    }
}
