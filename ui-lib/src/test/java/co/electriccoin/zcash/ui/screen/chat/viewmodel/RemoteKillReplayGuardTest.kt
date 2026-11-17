package co.electriccoin.zcash.ui.screen.chat.viewmodel

import co.electriccoin.zcash.ui.screen.chat.datasource.ZchatPreferences
import co.electriccoin.zcash.ui.screen.chat.model.ZMSGConstants
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.util.Collections
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext

/**
 * RED regression pin — remote kill must not fire on a REPLAY of an already-delivered transaction.
 *
 * ZChat re-derives the whole conversation list from the wallet's FULL on-chain history on every
 * emission of `transactionRepository.transactions` (ChatViewModel.convertToConversations, the
 * `for (tx in sortedTransactions)` loop), and calls [ChatViewModel.checkForRemoteKill] for every
 * received memo in it. The only thing that stopped a memo being re-evaluated was
 * `processedKillCheckTxIds` — a `Collections.synchronizedSet` held IN MEMORY ONLY (ChatViewModel.kt
 * ~295). It is never persisted, so it is EMPTY on every cold start and the entire backlog is
 * re-checked from scratch on every launch.
 *
 * Consequence: a user who armed remote kill and, at any point in the past, received a matching
 * transaction has their wallet wiped again by the mere act of relaunching — and 2.13.0 forces a
 * rescan, which re-delivers the whole backlog in one pass. The wipe is unrecoverable data
 * destruction that the user did not ask for.
 *
 * These pins are stated in user terms, not implementation terms:
 *  - a kill signal that predates arming, or that was already acted on, must NEVER wipe again;
 *  - a genuinely NEW kill signal must STILL wipe — a "fix" that quietly disables remote kill is a
 *    different, equally serious bug.
 *
 * Fixture idiom mirrors UnlockPaymentMessageReentryGuardTest: the ChatViewModel instance is
 * allocated WITHOUT running its very heavy constructor (sun.misc.Unsafe — no init {} runs, all
 * fields default to null) and only the fields this path reads are injected. `destroyManager` is
 * left NULL on purpose: it is the observable. Reaching `destroyManager.destroyAll(...)` throws
 * NullPointerException, so "an exception escaped" means exactly "the wallet would have been
 * wiped", and "no exception" means the guard held. (android.util.Log is inert here —
 * ui-lib sets `unitTests.isReturnDefaultValues = true`.)
 *
 * The private method is located by NAME and invoked adaptively, so this test exercises the real
 * production code path both before the fix (where checkForRemoteKill takes no mined height) and
 * after it. The assertions are behavioural in both shapes.
 */
class RemoteKillReplayGuardTest {
    private companion object {
        const val KILL_AMOUNT = 1337L
        const val ARMED_HEIGHT = 2_600_000L
        const val CHAIN_TIP = 2_700_000L
        const val SECRET_PHRASE = "correct horse battery staple"
        val KILL_MEMO = ZMSGConstants.REMOTE_KILL_PREFIX + SECRET_PHRASE
    }

    /**
     * Recording stand-in for the preference store the kill path consults.
     *
     * STATEFUL, like the real store: a floor written by one call is what the next call reads (with the
     * production setter's monotonic-forward rule), and a signal marked processed stays processed. So a
     * sequence of calls — or a "cold start" that builds a fresh ChatViewModel over the SAME FakePrefs —
     * sees what an app on a real phone would see.
     */
    private class FakePrefs(
        val enabled: Boolean = true,
        val hasPhrase: Boolean = true,
        val killAmount: Long = KILL_AMOUNT,
        val phraseMatches: Boolean = true,
        armedHeight: Long = ARMED_HEIGHT,
        alreadyProcessed: Set<String> = emptySet(),
    ) {
        var armedHeight: Long = armedHeight
            private set
        val processed: MutableSet<String> = alreadyProcessed.toMutableSet()
        val armedHeightWrites = mutableListOf<Long>()
        val markedProcessed = mutableListOf<String>()

        /** Mirrors ZchatPreferencesImpl.setRemoteKillArmedHeight: non-positive drops, otherwise only rises. */
        private fun writeArmedHeight(height: Long) {
            armedHeightWrites.add(height)
            armedHeight = if (height <= 0L) 0L else maxOf(armedHeight, height)
        }

        private fun markProcessed(txId: String) {
            processed.add(txId)
            markedProcessed.add(txId)
        }

        fun asProxy(): ZchatPreferences =
            Proxy.newProxyInstance(
                ZchatPreferences::class.java.classLoader,
                arrayOf(ZchatPreferences::class.java),
            ) { proxy, method, args ->
                when (method.name) {
                    "isRemoteKillEnabled" -> enabled
                    "hasRemoteKillPhrase" -> hasPhrase
                    "getRemoteKillAmount" -> killAmount
                    "verifyRemoteKillPhrase" -> phraseMatches && args!![0] == SECRET_PHRASE
                    // Guard state introduced by the fix. Answered here so this fixture drives the
                    // fixed code too; before the fix these names are simply never called.
                    "getRemoteKillArmedHeight" -> armedHeight
                    "setRemoteKillArmedHeight" -> writeArmedHeight(args!![0] as Long)
                    "hasProcessedKillSignalTx" -> processed.contains(args!![0])
                    "markKillSignalTxProcessed" -> markProcessed(args!![0] as String)
                    "toString" -> "FakeZchatPreferences"
                    "hashCode" -> System.identityHashCode(proxy)
                    "equals" -> proxy === args!![0]
                    else ->
                        when (method.returnType) {
                            java.lang.Boolean.TYPE -> false
                            java.lang.Integer.TYPE -> 0
                            java.lang.Long.TYPE -> 0L
                            else -> null
                        }
                }
            } as ZchatPreferences
    }

    private fun setField(vm: ChatViewModel, name: String, value: Any?) {
        ChatViewModel::class.java
            .getDeclaredField(name)
            .apply { isAccessible = true }
            .set(vm, value)
    }

    /**
     * Allocate a ChatViewModel without running its constructor and inject only what the remote-kill
     * path reads. [seenThisSession] models the in-memory replay set: empty == a COLD START.
     */
    private fun chatViewModel(
        prefs: FakePrefs,
        seenThisSession: Set<String> = emptySet(),
        chainTip: Long? = CHAIN_TIP,
    ): ChatViewModel {
        val unsafe =
            Class
                .forName("sun.misc.Unsafe")
                .getDeclaredField("theUnsafe")
                .apply { isAccessible = true }
                .get(null)
        val vm =
            unsafe.javaClass
                .getMethod("allocateInstance", Class::class.java)
                .invoke(unsafe, ChatViewModel::class.java) as ChatViewModel
        setField(vm, "zchatPreferences", prefs.asProxy())
        setField(
            vm,
            "processedKillCheckTxIds",
            Collections.synchronizedSet(seenThisSession.toMutableSet()),
        )
        setField(vm, "_blockHeight", MutableStateFlow(chainTip))
        // destroyManager deliberately left null — see class doc.
        return vm
    }

    private fun killCheckMethod(): Method =
        ChatViewModel::class.java.declaredMethods
            .firstOrNull { it.name == "checkForRemoteKill" }
            ?.apply { isAccessible = true }
            ?: error("ChatViewModel.checkForRemoteKill not found")

    private val noopContinuation =
        object : Continuation<Any?> {
            override val context = EmptyCoroutineContext

            override fun resumeWith(result: Result<Any?>) = Unit
        }

    /**
     * Run the real checkForRemoteKill. Returns true when the wipe was reached (null destroyManager
     * throws), false when the guard stopped it first.
     */
    private fun wipeAttempted(
        vm: ChatViewModel,
        memo: String? = KILL_MEMO,
        amount: Long = KILL_AMOUNT,
        txId: String = "tx_kill_0001",
        minedHeight: Long?,
    ): Boolean {
        val method = killCheckMethod()
        val args: Array<Any?> =
            when (method.parameterCount) {
                // Pre-fix shape: (amountZatoshi, memo, txId, continuation) — no height at all.
                4 -> arrayOf(amount, memo, txId, noopContinuation)
                // Post-fix shape: (amountZatoshi, memo, txId, minedHeight, continuation).
                5 -> arrayOf(amount, memo, txId, minedHeight, noopContinuation)
                else -> error("unexpected checkForRemoteKill arity: ${method.parameterCount}")
            }
        return try {
            method.invoke(vm, *args)
            false
        } catch (e: InvocationTargetException) {
            val cause = e.targetException
            if (cause is NullPointerException) {
                true
            } else {
                throw AssertionError("checkForRemoteKill failed for an unrelated reason", cause)
            }
        }
    }

    /**
     * Model the chain tip, and the wallet's transaction history, becoming known to [vm] — what
     * observeBlockHeight sees when lightwalletd answers. Publishes the tip into `_blockHeight` and,
     * where the tip-driven floor anchor exists (`anchorRemoteKillFloorIfUnset`), runs it with the mined
     * heights of the history the wallet holds at that moment.
     *
     * On code without that anchor only the tip is published, and the kill check then anchors the
     * floor from whichever TRANSACTION it is offered next — the behaviour the pins below reject. So
     * a missing or renamed anchor makes these pins fail; it can never make them pass silently.
     */
    private fun chainTipBecomesKnown(
        vm: ChatViewModel,
        tip: Long,
        historyMinedHeights: List<Long>?,
    ) {
        @Suppress("UNCHECKED_CAST")
        val blockHeight =
            ChatViewModel::class.java
                .getDeclaredField("_blockHeight")
                .apply { isAccessible = true }
                .get(vm) as MutableStateFlow<Long?>
        blockHeight.value = tip
        ChatViewModel::class.java.declaredMethods
            .firstOrNull { it.name == "anchorRemoteKillFloorIfUnset" }
            ?.apply { isAccessible = true }
            ?.invoke(vm, tip, historyMinedHeights)
    }

    @Test
    fun `a kill signal mined before remote kill was armed must never wipe`() {
        // The 2.13.0 forced rescan re-delivers the whole 48-day backlog on a cold start: nothing is
        // in the in-memory set, and the matching transaction is ancient history.
        val prefs = FakePrefs()
        val vm = chatViewModel(prefs, seenThisSession = emptySet())

        assertFalse(
            "a transaction mined BEFORE remote kill was armed was never a kill signal — " +
                "re-delivering it on a cold start or rescan must not wipe the wallet",
            wipeAttempted(vm, minedHeight = ARMED_HEIGHT - 100_000L),
        )
    }

    @Test
    fun `a kill signal already acted on must not wipe again after a cold start`() {
        // Mined above the armed height, so it was a legitimate signal once — but this install has
        // already processed it. The in-memory set is empty because the process restarted.
        val prefs = FakePrefs(alreadyProcessed = setOf("tx_kill_0001"))
        val vm = chatViewModel(prefs, seenThisSession = emptySet())

        assertFalse(
            "a kill signal this install already acted on must not be re-processed after a " +
                "cold start — the replay guard has to survive process death",
            wipeAttempted(vm, minedHeight = ARMED_HEIGHT + 500L),
        )
    }

    @Test
    fun `with no armed height on record the guard fails closed and anchors itself`() {
        // The upgrade case: the user armed remote kill on an older build, so no armed height was
        // ever recorded. Nothing already in the wallet's history may fire, and the guard must
        // anchor itself at the current chain tip so later scans have a floor to compare against.
        //
        // Spec change (audit A.4c): the floor is anchored from the CHAIN TIP, never from a transaction.
        // The previous version of this pin asserted that offering a transaction to the kill check
        // wrote the anchor — but the transaction that did so was discarded unevaluated, which is how
        // a genuine kill signal got swallowed. The fail-closed half of this pin is unchanged.
        val prefs = FakePrefs(armedHeight = 0L)
        val vm = chatViewModel(prefs, seenThisSession = emptySet(), chainTip = CHAIN_TIP)

        assertFalse(
            "with no armed height recorded the kill check must fail CLOSED — the pre-existing " +
                "history of a user upgrading into this build must never be able to wipe them",
            wipeAttempted(vm, minedHeight = CHAIN_TIP - 50_000L),
        )
        assertEquals(
            "the kill check must never anchor the floor from a transaction it is offered — that " +
                "transaction is then never evaluated, so a genuine kill signal can be swallowed",
            emptyList<Long>(),
            prefs.armedHeightWrites,
        )

        chainTipBecomesKnown(vm, tip = CHAIN_TIP, historyMinedHeights = listOf(CHAIN_TIP - 50_000L))

        assertEquals(
            "once the tip and the history are known the guard must anchor the floor at the current " +
                "chain tip, so the whole existing backlog is permanently below it",
            CHAIN_TIP,
            prefs.armedHeight,
        )
        assertFalse(
            "history re-offered after the anchor is written must still never wipe",
            wipeAttempted(vm, minedHeight = CHAIN_TIP - 50_000L),
        )
    }

    @Test
    fun `a live kill signal first seen while the chain tip is unknown still fires once it is mined`() {
        // Audit A.4c pin (i): tip null then known, same txid, same ChatViewModel. Cold start: the
        // wallet DB emits before the lightwalletd tip round-trip returns, and SDK 3.0.0 scans the
        // mempool, so the owner's kill signal is first offered UNMINED while no floor exists.
        val prefs = FakePrefs(armedHeight = 0L)
        val vm = chatViewModel(prefs, seenThisSession = emptySet(), chainTip = null)

        assertFalse(
            "with no tip and no floor nothing may wipe",
            wipeAttempted(vm, minedHeight = null),
        )

        // The tip arrives. The history the wallet already holds is all older than the tip.
        chainTipBecomesKnown(vm, tip = CHAIN_TIP, historyMinedHeights = listOf(CHAIN_TIP - 10L))

        assertTrue(
            "the kill signal offered before the tip was known must be evaluated again once it is " +
                "mined above the floor — consuming it on the first offer silently disables remote " +
                "kill for the user relying on it under duress",
            wipeAttempted(vm, minedHeight = CHAIN_TIP + 1L),
        )
        assertFalse(
            "the same signal must fire exactly once — a re-offer in the same session must not wipe again",
            wipeAttempted(vm, minedHeight = CHAIN_TIP + 1L),
        )
        assertEquals(listOf("tx_kill_0001"), prefs.markedProcessed)
    }

    @Test
    fun `the first kill signal after a fail-closed start is evaluated, not consumed as the anchor`() {
        // Audit A.4c, the mainline shape: floor 0 (no arm-time anchor exists in any build), the tip is
        // known and the history holds no matching transaction. The next received memo is the owner's
        // kill signal, mined in the block that also advanced the tip.
        val prefs = FakePrefs(armedHeight = 0L)
        val vm = chatViewModel(prefs, seenThisSession = emptySet(), chainTip = null)

        chainTipBecomesKnown(vm, tip = CHAIN_TIP, historyMinedHeights = listOf(CHAIN_TIP - 10L))
        chainTipBecomesKnown(vm, tip = CHAIN_TIP + 1L, historyMinedHeights = listOf(CHAIN_TIP - 10L))

        assertTrue(
            "a genuinely new kill signal must fire — it must not be spent as the event that anchors " +
                "the floor and then fall permanently below that floor",
            wipeAttempted(vm, minedHeight = CHAIN_TIP + 1L),
        )
    }

    @Test
    fun `a kill signal first seen unmined fires exactly once when it is mined`() {
        // Audit A.4c pin (ii): unmined then mined, same ChatViewModel, floor already set. SDK 3.0.0
        // scans the mempool, so this is how a live signal normally arrives.
        val prefs = FakePrefs(armedHeight = ARMED_HEIGHT)
        val vm = chatViewModel(prefs, seenThisSession = emptySet())

        assertFalse(
            "an unmined transaction must not wipe",
            wipeAttempted(vm, minedHeight = null),
        )
        assertTrue(
            "once mined above the floor the SAME transaction must fire in the SAME session — it " +
                "must not be rejected as already seen because it was first offered unmined",
            wipeAttempted(vm, minedHeight = ARMED_HEIGHT + 1L),
        )
        assertFalse(
            "a re-offer in the same session must not wipe a second time",
            wipeAttempted(vm, minedHeight = ARMED_HEIGHT + 1L),
        )

        val afterColdStart = chatViewModel(prefs, seenThisSession = emptySet())
        assertFalse(
            "and it must not wipe again after a cold start",
            wipeAttempted(afterColdStart, minedHeight = ARMED_HEIGHT + 1L),
        )
        assertEquals(listOf("tx_kill_0001"), prefs.markedProcessed)
    }

    @Test
    fun `a lagging server tip below the wallet's own history never lifts an old kill signal above the floor`() {
        // Audit A.4b: SDK 3.0.0 publishes lightwalletd's GetLatestBlock reply to networkHeight
        // unclamped. A lagging server (load-balanced pool, cold cache, switch to a stale node) can
        // report a tip BELOW transactions the wallet already holds. Anchoring at that raw tip left an
        // old matching transaction above the floor — and it wiped the wallet on the next launch.
        val laggingTip = 2_690_000L
        val oldKillHeight = 2_700_000L
        val prefs = FakePrefs(armedHeight = 0L)
        val vm = chatViewModel(prefs, seenThisSession = emptySet(), chainTip = laggingTip)

        assertFalse(
            "no floor yet — nothing may wipe",
            wipeAttempted(vm, minedHeight = oldKillHeight),
        )

        chainTipBecomesKnown(vm, tip = laggingTip, historyMinedHeights = listOf(2_650_000L, oldKillHeight))

        assertFalse(
            "a kill transaction already in the wallet's history is history, whatever the server's tip says",
            wipeAttempted(vm, minedHeight = oldKillHeight),
        )

        val afterColdStart = chatViewModel(prefs, seenThisSession = emptySet(), chainTip = laggingTip)
        assertFalse(
            "a lagging tip must never leave an old matching transaction above the floor — it would " +
                "destroy the wallet on the next launch",
            wipeAttempted(afterColdStart, minedHeight = oldKillHeight),
        )
        assertEquals(
            "the floor must sit at the highest mined height the wallet already holds when that is above the tip",
            oldKillHeight,
            prefs.armedHeight,
        )
    }

    @Test
    fun `no floor is anchored before the wallet history has loaded`() {
        // Without the history the lagging-tip protection above cannot be applied, so the anchor must
        // wait (the kill check keeps failing closed meanwhile). Green before the fix too: there the
        // anchor was never tip-driven at all.
        val prefs = FakePrefs(armedHeight = 0L)
        val vm = chatViewModel(prefs, seenThisSession = emptySet(), chainTip = null)

        chainTipBecomesKnown(vm, tip = CHAIN_TIP, historyMinedHeights = null)

        assertEquals(0L, prefs.armedHeight)
    }

    @Test
    fun `an unmined kill signal must not wipe`() {
        val prefs = FakePrefs()
        val vm = chatViewModel(prefs, seenThisSession = emptySet())

        assertFalse(
            "an unmined transaction has no height to compare against the armed height — it must " +
                "not be allowed to wipe the wallet",
            wipeAttempted(vm, minedHeight = null),
        )
    }

    @Test
    fun `a genuinely new kill signal above the armed height still wipes`() {
        // FEATURE PIN — green BEFORE and AFTER the fix on purpose. Remote kill must keep working;
        // a fix that quietly disables it is a different, equally serious bug. If this ever goes red
        // the replay guard has been over-tightened into a feature outage.
        val prefs = FakePrefs()
        val vm = chatViewModel(prefs, seenThisSession = emptySet())

        assertTrue(
            "a NEW kill signal mined above the armed height must still destroy the wallet — " +
                "remote kill must not be silently disabled by the replay guard",
            wipeAttempted(vm, minedHeight = ARMED_HEIGHT + 1L),
        )
    }

    @Test
    fun `acting on a kill signal records it durably before the wipe starts`() {
        val prefs = FakePrefs()
        val vm = chatViewModel(prefs, seenThisSession = emptySet())

        wipeAttempted(vm, minedHeight = ARMED_HEIGHT + 1L)

        assertEquals(
            "a kill signal that is acted on must be recorded as processed BEFORE the wipe so a " +
                "later launch cannot fire it a second time",
            listOf("tx_kill_0001"),
            prefs.markedProcessed,
        )
    }

    @Test
    fun `a non-matching phrase above the armed height never wipes`() {
        val prefs = FakePrefs(phraseMatches = false)
        val vm = chatViewModel(prefs, seenThisSession = emptySet())

        assertFalse(
            "a memo whose phrase does not verify is not a kill signal at any height",
            wipeAttempted(vm, minedHeight = ARMED_HEIGHT + 1L),
        )
    }

    @Test
    fun `a matching memo with the wrong amount above the armed height never wipes`() {
        val prefs = FakePrefs()
        val vm = chatViewModel(prefs, seenThisSession = emptySet())

        assertFalse(
            "the configured kill amount is part of the signal — a different amount is not a kill",
            wipeAttempted(vm, amount = KILL_AMOUNT + 1L, minedHeight = ARMED_HEIGHT + 1L),
        )
    }
}
