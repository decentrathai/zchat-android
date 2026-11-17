package co.electriccoin.zcash.ui.screen.ai

import co.electriccoin.zcash.ui.screen.ai.AiKeyScreensFixture.Companion.OTHER_KEY_ID
import co.electriccoin.zcash.ui.screen.ai.AiKeyScreensFixture.Companion.OWN_KEY_ID
import co.electriccoin.zcash.ui.screen.ai.AiKeyScreensFixture.Companion.SEED
import co.electriccoin.zcash.ui.screen.chat.view.SourceScan
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/**
 * AI-KEY-R/T round 2 (W-97; ZC-1223; RT_ROUND2.md items 1 and 4).
 *
 * Item 1: an error that is not a cancellation, inside the three calls of the Reset and Destroy PIN screens, is "no answer":
 * nothing is thrown at the screen, and its busy mark always clears.
 *
 * Item 4: Check now hands the answer on under the ticket of its own request. GPT's sequence: balance is asked for A, the
 * check that A is still current passes, the pair becomes B, and A's paid=false lands on B: the Reset then drops B's key.
 *
 * WHY IT FAILS TODAY: [AiAccountAccess.linkNow] and the end of [AiAccountAccess.checkNow] run outside readingTheStore, so
 * what walletSaved or the seed throws reaches the caller; checkNow calls the onBalance that takes no ticket.
 */
class AiResetChecksGiveNoAnswerOnAFailureTest {
    private val phone = AiKeyScreensFixture()

    @After
    fun tearDown() = phone.close()

    private fun access(
        walletSaved: suspend () -> Boolean = { true },
        seed: suspend () -> ByteArray? = { SEED.copyOf() },
        scope: () -> Long = { 0L },
    ) = AiAccountAccess(
        prefs = phone.ai,
        seed = seed,
        candidates = { emptyList() },
        ownerTag = { AiKeyScreensFixture.TAG },
        scopeCounter = scope,
        walletSaved = walletSaved,
        clock = System::currentTimeMillis,
        send = { _, path, _, _ ->
            phone.requests += path
            phone.answer(path)
        },
    )

    private val broken: suspend () -> Boolean = { throw IllegalStateException("synthetic: the wallet store failed") }

    @Test
    fun `item 1 - walletSaved throws in the linking try - recheckForResetScreen does not throw`() {
        phone.store()
        runBlocking { access(walletSaved = broken).recheckForResetScreen() }
    }

    @Test
    fun `item 1 - walletSaved throws - linkNow answers false`() {
        phone.store()
        assertFalse(runBlocking { access(walletSaved = broken).linkNow() })
    }

    @Test
    fun `item 1 - the seed read throws - linkNow answers false and the re-check does not throw`() {
        phone.store()
        val access = access(seed = { throw IllegalStateException("synthetic: the seed read failed") })
        assertFalse(runBlocking { access.linkNow() })
        runBlocking { access.recheckForResetScreen() }
    }

    @Test
    fun `item 1 - walletSaved throws at any point of checkNow, also after the answer - checkNow does not throw`() {
        phone.store(verdict = "linked", paid = "yes")
        phone.answer = { 200 to """{"balanceUsd":1.0,"signInKeyId":"$OWN_KEY_ID","paid":true}""" }
        for (failing in 1..MOST_WALLET_READS_OF_A_CHECK) {
            var calls = 0
            val access =
                access(
                    walletSaved = {
                        calls++
                        if (calls >= failing) throw IllegalStateException("synthetic: the wallet store failed")
                        true
                    },
                )
            runBlocking { access.checkNow() }
        }
    }

    // GUARD, passes before and after the fix: only errors become "no answer"; a cancellation stays a cancellation.
    @Test
    fun `item 1 - a cancellation is not swallowed by any of the three`() {
        phone.store()
        val cancelled = access(walletSaved = { throw CancellationException("synthetic") })
        val calls =
            mapOf<String, suspend () -> Unit>(
                "linkNow" to { cancelled.linkNow() },
                "checkNow" to { cancelled.checkNow() },
                "recheckForResetScreen" to { cancelled.recheckForResetScreen() },
            )
        for ((name, call) in calls) {
            runBlocking {
                try {
                    call()
                    fail("$name swallowed the cancellation")
                } catch (_: CancellationException) {
                    // what is wanted
                }
            }
        }
    }

    @Test
    fun `item 1 - source - both taps of the Destroy PIN line clear busy in a finally`() {
        val source = SourceScan.stripComments(File(repoRoot(), LINE).readText())
        assertEquals(
            "a tap that ends in an error leaves the buttons disabled for good",
            2,
            Regex("""finally\s*\{[^}]*busy\s*=\s*false""").findAll(source).count(),
        )
    }

    @Test
    fun `item 4 - the pair changes right after the check that A is current - A's answer does not land on B`() {
        phone.store()
        val before = """{"accountId":"$USER_B","paid":"unknown"}"""
        var answered = false
        var readsAfterTheAnswer = 0
        var scope = 0L
        phone.answer = {
            answered = true
            200 to """{"balanceUsd":0.0,"signInKeyId":"$OTHER_KEY_ID","paid":false}"""
        }
        val access =
            access(
                walletSaved = {
                    if (answered && ++readsAfterTheAnswer == READ_AFTER_THE_CURRENT_CHECK) {
                        phone.ai.saveCredentials(TOKEN_B, USER_B)
                        phone.ai.writeIfStillThisWallet({ true }) { putLinkStateJson(before) }
                        scope++
                    }
                    true
                },
                scope = { scope },
            )

        runBlocking { access.checkNow() }

        assertEquals("A's balance answer changed what is known of B", before, phone.ai.getLinkStateJson())
        assertTrue(
            "the Reset would drop B's key on A's answer",
            AiPreferences.ACCESS_KEY_ENTRIES.first() in phone.ai.entriesKeptAtReset(),
        )
    }

    private fun repoRoot(): File {
        var dir: File? = File("").absoluteFile
        while (dir != null && !File(dir, "ui-lib/src/main").isDirectory) dir = dir.parentFile
        return dir ?: error("repo root not found")
    }

    private companion object {
        const val LINE = "ui-lib/src/main/java/co/electriccoin/zcash/ui/screen/onboarding/view/DestroyPinAiCreditLine.kt"
        const val MOST_WALLET_READS_OF_A_CHECK = 4
        const val READ_AFTER_THE_CURRENT_CHECK = 2
        const val TOKEN_B = "synthetic-token-b"
        const val USER_B = "synthetic-user-b"
    }
}
