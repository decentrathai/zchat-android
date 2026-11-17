package co.electriccoin.zcash.ui.screen.chat.viewmodel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * F8 (ZC-112, ZC-600) — the ledger is wired: the ViewModel takes it as a constructor parameter, Koin binds it from the
 * one preferences instance, and each sender of the batch hands its funnel a tag. A ViewModel with no ledger (a unit
 * test that installs none) records nothing: null means disabled.
 */
class SendAttemptLedgerIsWiredTest {
    private val source = LedgerSources.chatViewModel

    @Test
    fun `ChatViewModel takes the environment as a constructor parameter with no default`() {
        val constructor = source.substring(source.indexOf("class ChatViewModel("), source.indexOf(") : ViewModel()"))
        assertTrue(
            "sendAttempts: SendAttemptEnvironment? must be a constructor parameter without a default value",
            Regex("""private val sendAttempts: SendAttemptEnvironment\?,\n""").containsMatchIn(constructor),
        )
    }

    @Test
    fun `Koin binds the ledger from the preferences instance and the environment over it`() {
        val module = LedgerSources.main("di/DataSourceModule.kt")
        assertTrue(
            Regex("""single<[\w.]*SendAttemptLedger>\s*\{\s*get<ZchatPreferencesImpl>\(\)\.sendAttemptLedger\s*}""").containsMatchIn(
                module,
            ),
        )
        assertTrue(Regex("""single<[\w.]*SendAttemptEnvironment>""").containsMatchIn(module))
        assertTrue(module.contains("WalletSendAttemptEnvironment(get(), get())"))
    }

    @Test
    fun `the interface of the preferences store gained no ledger method`() {
        val prefs = LedgerSources.main("ui/screen/chat/datasource/ZchatPreferences.kt")
        val iface = prefs.substring(prefs.indexOf("interface ZchatPreferences"), prefs.indexOf("class ZchatPreferencesImpl("))
        assertTrue(
            "the ledger is a separate collaborator, not a method of ZchatPreferences",
            !iface.contains("sendAttempt", ignoreCase = true),
        )
    }

    @Test
    fun `every sender of the batch tags its memo`() {
        for ((function, kind) in listOf(
            "sendKexOnce" to "SendAttemptKind.KEX",
            "payOwedKex" to "SendAttemptKind.KEX",
            "sendKEXAckMessage" to "SendAttemptKind.KEXACK",
            "groupAcceptTag" to "SendAttemptKind.GROUP_ACCEPT",
            "lockedMessageTag" to "SendAttemptKind.TIME_LOCK",
            "setDisappearingTtl" to "SendAttemptKind.TIMER",
        )) {
            assertTrue("$function must tag its memo as $kind", wholeFunction(function).contains(kind))
        }
        assertTrue(
            "the on-chain ZBOOT is tagged",
            source.contains("SendAttemptTag(SendAttemptKind.ZBOOT, peerAddress, ourNostrPub, retryOf = retryOf)"),
        )
        assertTrue(
            "the key grant is tagged per member and key",
            source.contains("""SendAttemptTag(SendAttemptKind.KEY_GRANT, "${'$'}groupId|${'$'}requester", "epoch:${'$'}currentEpoch")"""),
        )
        assertEquals(
            "the four locked senders pass their tag",
            4,
            Regex("""val lockedTag = lockedMessageTag\(peerAddress, timeLockMemo\)""").findAll(source).count(),
        )
    }

    @Test
    fun `the funnel gates before the caller's own gate and records before the submit counts as started`() {
        val funnel = wholeFunction("sendMemoWithBlockRetry")
        val gate = funnel.indexOf("capture.gate()")
        val callerGate = funnel.indexOf("when (beforeAttempt())")
        val begin = funnel.indexOf("capture?.begin()")
        val started = funnel.indexOf("submitStarted = true")
        assertTrue("the ledger gate comes before beforeAttempt (before any stamp)", gate in 0 until callerGate)
        assertTrue("begin comes after the caller's beforeSubmit and before the submit counts as started", begin in 0 until started)
        assertTrue("the ids are committed in onTransactionsStored", funnel.contains("onTransactionsStored = capture?.let"))
        assertTrue(
            "a null ledger passes no callback: the path of every older test",
            funnel.contains("sendAttempts?.let { LedgerCapture(it, tag) }"),
        )
    }

    @Test
    fun `the debug kill seam in the capture goes through the gated reader`() {
        val capture =
            source.substring(
                source.indexOf("private inner class LedgerCapture("),
                source.indexOf("* R4-1 — what a memo send did"),
            )
        assertTrue(capture.contains("FaultSeams.killHereIfArmed(FaultSeam.KILL_AFTER_MARKER_BEFORE_CREATE)"))
        val seams = LedgerSources.main("ui/common/util/FaultSeams.kt")
        val reader = seams.substring(seams.indexOf("fun killHereIfArmed("), seams.indexOf("fun arm("))
        assertTrue("a release build reads the seam as not armed", reader.contains("if (!BuildConfig.DEBUG) return"))
    }

    private fun wholeFunction(name: String): String {
        val declaration = Regex("""\n    (private |internal )?(suspend )?fun $name\(""").find(source)
        val start = declaration?.range?.first ?: kotlin.test.fail("no fun $name")
        val next = Regex("""\n    (/\*\*|private |internal |fun |suspend |@)""").find(source, start + 10)?.range?.first ?: source.length
        // To the start of the next member whose body follows this one's closing brace.
        val end = source.indexOf("\n    }\n", start).let { if (it < 0) next else it }
        var cursor = end
        // A function with nested members at the same indent (rare): extend to the last closing brace before the next KDoc.
        val nextDoc = source.indexOf("\n    /**", start + 10).let { if (it < 0) source.length else it }
        while (true) {
            val more = source.indexOf("\n    }\n", cursor + 1)
            if (more < 0 || more > nextDoc) break
            cursor = more
        }
        return source.substring(start, cursor)
    }
}
