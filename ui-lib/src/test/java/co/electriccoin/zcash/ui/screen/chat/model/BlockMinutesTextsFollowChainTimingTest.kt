package co.electriccoin.zcash.ui.screen.chat.model

import co.electriccoin.zcash.ui.common.sendattempt.ChainTimingApi
import co.electriccoin.zcash.ui.screen.chat.view.introduceMinutesLeft
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * ZC-114 (fix-all 2.13.0, train T5b; high-items SPECS §1.5 R5): the minutes the app shows from a count of blocks follow
 * the block spacing at the height they are about, from ChainTiming: the group introduce progress and the slow-consent
 * note ("about M min", 200 s per paid announce at 75 s blocks), the confirmation countdown ("N/10 confirmations,
 * ~M min", 90 s per block: the 75 s target with a 6/5 drift allowance), the Block Height Lock ETA and the lock
 * description. From NU7 on (25 s blocks, ZIP 218) they are a third; before NU7, and while the table holds no NU7
 * height, they are today's.
 *
 * At RED they count 200 s, 90 s and 75 s at every height. [N] is a made-up activation height.
 */
class BlockMinutesTextsFollowChainTimingTest {
    private companion object {
        const val N = 4_000_000L
        const val PKG = "ui-lib/src/main/java/co/electriccoin/zcash/ui/"
    }

    private lateinit var saved: ChainTimingApi.Saved

    @Before
    fun setUp() {
        saved = ChainTimingApi.Saved.take()
    }

    @After
    fun tearDown() {
        saved.restore()
    }

    @Test
    fun `the group introduce minutes follow the block spacing at the last tip`() {
        ChainTimingApi.current = ChainTimingApi.heights(N)
        ChainTimingApi.lastTip = N + 100
        // 9 paid announces, about 200 s each at 75 s blocks (30 min): a third at 25 s blocks.
        assertEquals(10, introduceMinutesLeft(9), "9 paid announces after NU7 still say about 30 minutes")
        ChainTimingApi.lastTip = N - 1_000
        assertEquals(30, introduceMinutesLeft(9))
    }

    @Test
    fun `the confirmation countdown counts 30 seconds a block after NU7`() {
        ChainTimingApi.current = ChainTimingApi.heights(N)
        // Mined at N+95, tip N+100: 6 of 10, 4 blocks owed at 25 s x 6/5 = 30 s: 120 s, 2 min.
        val after = assertNotNull(confirmationProgress(networkHeight = N + 100, minedHeight = N + 95, trusted = false))
        assertEquals(120L, after.etaSeconds, "the countdown after NU7 still counts 90 s a block")
        assertEquals("6/10 confirmations, ~2 min", after.label)
        val receipt = PendingReceipt(minedHeight = N + 95, isOwnChange = false, isTrusted = false)
        assertEquals(120L, assertNotNull(receipt.progressAt(N + 100)).etaSeconds)
        // Before NU7: today's 90 s a block.
        val before = assertNotNull(confirmationProgress(networkHeight = N - 900, minedHeight = N - 905, trusted = false))
        assertEquals(360L, before.etaSeconds)
    }

    @Test
    fun `the Block Height Lock ETA and the lock description count blocks through ChainTiming`() {
        for (path in listOf("screen/chat/view/ChatDialogs.kt", "screen/chat/model/ZMSGProtocol.kt")) {
            val source = read(PKG + path)
            assertFalse(
                Regex("""\*\s*75\b""").containsMatchIn(source),
                "$path still turns blocks into time at a fixed 75 s a block",
            )
            assertTrue(source.contains("ChainTiming."), "$path does not count its blocks through ChainTiming")
        }
    }

    // ── controls ─────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `control - with the shipped table the minutes are today's`() {
        // ChainTiming's current table as shipped (no NU7 height): holds before and after the fix.
        assertEquals(30, introduceMinutesLeft(9))
        assertEquals(1, introduceMinutesLeft(0))
        val progress = assertNotNull(confirmationProgress(networkHeight = 3_200_100L, minedHeight = 3_200_095L, trusted = false))
        assertEquals(360L, progress.etaSeconds)
        assertEquals("6/10 confirmations, ~6 min", progress.label)
        assertEquals(ConfirmationProgress(confirmations = 6, required = 10), progress)
    }

    private fun read(path: String): String {
        var dir: File? = File("").absoluteFile
        while (dir != null && !File(dir, "gradle.properties").isFile) dir = dir.parentFile
        val root = assertNotNull(dir, "repo root (gradle.properties) not found above the test working dir")
        return File(root, path).readText()
    }
}
