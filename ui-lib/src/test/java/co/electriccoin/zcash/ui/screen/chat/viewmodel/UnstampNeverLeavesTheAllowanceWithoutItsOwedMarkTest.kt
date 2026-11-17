package co.electriccoin.zcash.ui.screen.chat.viewmodel

import android.content.SharedPreferences
import co.electriccoin.zcash.ui.nostr.NostrChatBridge
import co.electriccoin.zcash.ui.screen.chat.crypto.E2EEncryption
import co.electriccoin.zcash.ui.screen.chat.datasource.ZchatPreferences
import co.electriccoin.zcash.ui.screen.chat.datasource.ZchatPreferencesImpl
import co.electriccoin.zcash.ui.screen.chat.datasource.realPreferences
import co.electriccoin.zcash.ui.screen.chat.viewmodel.HandshakeHarness.awaitIdle
import co.electriccoin.zcash.ui.screen.chat.viewmodel.HandshakeHarness.awaitTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean

/**
 * ZC-703, ZC-661 — restoring a proven-unsent quoted ZBOOT must not persist an allowance without its owed mark.
 * A confirmed Reset really quotes two transactions and pays its KEX; its ZBOOT proposal then reports funds still
 * confirming before anything is stored. Quote/owed calls go through the real ZchatPreferencesImpl. A commit observer
 * models durable state separately from process memory (Android commit(false) can update memory), records every write
 * after the allowance is consumed, and can end the attempt directly after the restoring commit. Restart constructs a
 * fresh real preferences object from only durable data. No assertion assumes a failed commit rolls process memory back.
 * The controlled CancellationException is a test-only process-cut seam, not a product failure or a real Android kill.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class UnstampNeverLeavesTheAllowanceWithoutItsOwedMarkTest {
    private val sendScopes = SendHarnessScopes()

    @Before
    fun setUp() {
        sendScopes.open()
        Dispatchers.setMain(UnconfinedTestDispatcher())
        AutoSpendVm.resetProcessState()
        AutoSpendVm.setHistoryRead(true)
        FirstMessagePlanChat.resetOwedKexState()
        resetZbootWorkIfPresent()
        NostrChatBridge.unregisterPublisher()
    }

    @After
    fun tearDown() {
        NostrChatBridge.unregisterPublisher()
        sendScopes.close()
        Dispatchers.resetMain()
        AutoSpendVm.resetProcessState()
        FirstMessagePlanChat.resetOwedKexState()
        resetZbootWorkIfPresent()
    }

    @Test
    fun `every durable state of an unstamp has both the restored allowance and owed mark or neither`() =
        runBlocking {
            val chat = ResetOwedChat(PEER, consent = false, failFirstZbootWithStillConfirming = true)
            val persistence = ObservedQuoteFile(chat.store)
            val first = persistence.viewModel(chat)
            confirmResetAndReceiveKeyReply(chat, first)
            awaitTrue { chat.heights.isWaiting() }
            killProcesses(first)

            assertTrue("fixture sanity: the consumed allowance and restoring write must both be observed", persistence.sawRestore.get())
            val writes = persistence.snapshots.toList()
            assertTrue("fixture sanity: no unstamp writes were observed", writes.isNotEmpty())
            assertTrue(
                "an unstamp committed an allowance that no owed retry will spend: $writes",
                writes.none { it.allowance && !it.owed },
            )
            assertTrue("the successful restore must leave both on disk", persistence.diskState().let { it.allowance && it.owed })
            assertEquals("the unsuccessful attempt cannot have submitted a ZBOOT", 0, chat.seam.submittedOf("ZBOOT"))
        }

    @Test
    fun `a process cut at the successful restoring commit leaves a quoted ZBOOT that the next block pays once`() =
        runBlocking {
            val chat = ResetOwedChat(PEER, consent = false, failFirstZbootWithStillConfirming = true)
            val persistence = ObservedQuoteFile(chat.store, cutAtRestore = true)
            val first = persistence.viewModel(chat)
            confirmResetAndReceiveKeyReply(chat, first)
            awaitTrue { persistence.cutReached.get() }
            killProcesses(first)
            assertEquals("fixture sanity: one ZBOOT proposal was refused before store", 1, chat.seam.zbootProposals.get())
            assertEquals("fixture sanity: the cut happened before any ZBOOT submit", 0, chat.seam.submittedOf("ZBOOT"))
            assertNotNull(
                "fixture sanity: the cut must leave the delivered-or-in-flight marker set",
                chat.store.getSentNostrBootPubkey(PEER),
            )

            val next = persistence.viewModel(chat)
            val recorder = SendStateRecorder(next)
            chat.tick(next)
            awaitIdle(next)
            assertEquals("the restore commit stranded the confirmed ZBOOT after the process ended", 1, chat.seam.submittedOf("ZBOOT"))
            assertEquals("only the first refused proposal and one retry are allowed", 2, chat.seam.zbootProposals.get())
            assertEquals("the retry must consume both durable flags", QuoteState(false, false), persistence.diskState())

            chat.tick(next)
            awaitIdle(next)
            chat.tick(next)
            awaitIdle(next)
            assertEquals("later blocks paid the one allowance twice", 1, chat.seam.submittedOf("ZBOOT"))
            assertEquals("the confirmed Reset quoted exactly KEX plus ZBOOT", 2, chat.seam.submitted.size)
            recorder.assertNoHandshakeConsentQuestion("restart asked for a consent the confirmed quote already supplied")
            assertEquals("the confirmed Reset must not enable standing consent", 0, chat.consentWrites.get())
            recorder.stop()
            killProcesses(next)
        }

    @Test
    fun `a restoring commit that returns false leaves neither durable flag and no automatic payment after restart`() =
        runBlocking {
            val chat = ResetOwedChat(PEER, consent = false, failFirstZbootWithStillConfirming = true)
            val persistence = ObservedQuoteFile(chat.store, cutAtRestore = true, refuseRestore = true)
            val first = persistence.viewModel(chat)
            confirmResetAndReceiveKeyReply(chat, first)
            awaitTrue { persistence.cutReached.get() }
            killProcesses(first)
            assertTrue("fixture sanity: the restore commit must have been refused", persistence.restoreRefused.get())
            assertEquals("a failed commit cannot persist half of the restore", QuoteState(false, false), persistence.diskState())

            val next = persistence.viewModel(chat)
            val recorder = SendStateRecorder(next)
            chat.tick(next)
            awaitIdle(next)
            chat.tick(next)
            awaitIdle(next)
            assertEquals("an unsaved quote must never authorize a new proposal", 1, chat.seam.zbootProposals.get())
            assertEquals("an unsaved restore must never pay", 0, chat.seam.submittedOf("ZBOOT"))
            assertEquals("only the Reset's KEX was paid", 1, chat.seam.submitted.size)
            assertEquals(QuoteState(false, false), persistence.diskState())
            recorder.assertNoHandshakeConsentQuestion("an absent durable quote should not manufacture another question")
            assertEquals(0, chat.consentWrites.get())
            recorder.stop()
            killProcesses(next)
        }

    private suspend fun confirmResetAndReceiveKeyReply(
        chat: ResetOwedChat,
        vm: ChatViewModel,
    ) {
        vm.resetSecureSession(PEER)
        awaitIdle(vm)
        chat.reset.assertAskedFirst(vm)
        vm.confirmPaidAction()
        awaitIdle(vm)
        assertEquals("fixture sanity: the confirmed Reset's KEX went", 1, chat.seam.submittedOf("KEX"))
        chat.kexAckFromContact(vm, E2EEncryption.generateKeyPair(), txId = "synthetic-zc661-key-reply")
    }

    private data class QuoteState(
        val allowance: Boolean,
        val owed: Boolean,
    )

    /** Only the quote/owed file is simulated; every other call uses the existing persistent chat fixture. */
    private class ObservedQuoteFile(
        private val delegate: ZchatPreferences,
        private val cutAtRestore: Boolean = false,
        private val refuseRestore: Boolean = false,
    ) {
        private val disk = MemorySharedPreferences()
        private var consumed = false
        val snapshots: MutableList<QuoteState> = Collections.synchronizedList(mutableListOf())
        val sawRestore = AtomicBoolean(false)
        val cutReached = AtomicBoolean(false)
        val restoreRefused = AtomicBoolean(false)

        fun diskState(): QuoteState = QuoteState(disk.getBoolean(ALLOWANCE, false), disk.getBoolean(OWED, false))

        suspend fun viewModel(chat: ResetOwedChat): ChatViewModel {
            val real = realPreferences().first
            val memory = MemorySharedPreferences()
            val editor = memory.edit()
            disk.snapshot().forEach { (key, value) -> editor.putBoolean(key, value as Boolean) }
            editor.commit()
            ZchatPreferencesImpl::class.java
                .getDeclaredField("modePrefs")
                .apply { isAccessible = true }
                .set(real, observedFile(memory))
            val routes =
                setOf(
                    "isZbootQuoted",
                    "setZbootQuoted",
                    "isZbootOwed",
                    "setZbootOwed",
                    "getZbootOwedPeers",
                    "restoreQuotedZbootAsOwed",
                )
            val store =
                Proxy.newProxyInstance(
                    ZchatPreferences::class.java.classLoader,
                    arrayOf(ZchatPreferences::class.java),
                ) { proxy, method, args ->
                    when (method.name) {
                        "hashCode" -> System.identityHashCode(proxy)
                        "equals" -> proxy === args?.get(0)
                        "toString" -> "Zc661ObservedQuoteStore"
                        else ->
                            try {
                                method.invoke(if (method.name in routes) real else delegate, *(args ?: emptyArray()))
                            } catch (e: InvocationTargetException) {
                                throw e.targetException
                            }
                    }
                } as ZchatPreferences
            return chat.viewModel().also { AutoSpendVm.setField(it, "zchatPreferences", store) }
        }

        private fun observedFile(memory: SharedPreferences): SharedPreferences =
            Proxy.newProxyInstance(
                SharedPreferences::class.java.classLoader,
                arrayOf(SharedPreferences::class.java),
            ) { _, method, args ->
                if (method.name == "edit") observedEditor(memory.edit(), disk.edit()) else method.invoke(memory, *(args ?: emptyArray()))
            } as SharedPreferences

        private fun observedEditor(
            memoryEditor: SharedPreferences.Editor,
            diskEditor: SharedPreferences.Editor,
        ): SharedPreferences.Editor {
            val puts = mutableMapOf<String, Boolean>()
            val removals = mutableSetOf<String>()
            return Proxy.newProxyInstance(
                SharedPreferences.Editor::class.java.classLoader,
                arrayOf(SharedPreferences.Editor::class.java),
            ) { proxy, method, args ->
                when (method.name) {
                    "putBoolean" -> {
                        puts[args!![0] as String] = args[1] as Boolean
                        method.invoke(memoryEditor, *args)
                        method.invoke(diskEditor, *args)
                        proxy
                    }
                    "remove" -> {
                        removals += args!![0] as String
                        method.invoke(memoryEditor, *args)
                        method.invoke(diskEditor, *args)
                        proxy
                    }
                    "commit" -> {
                        // Android can update process memory even when persistence reports false.
                        memoryEditor.commit()
                        val restore = consumed && puts[ALLOWANCE] == true
                        val refused = restore && refuseRestore && !sawRestore.get()
                        val saved = !refused && diskEditor.commit()
                        if (ALLOWANCE in removals) consumed = true
                        if (consumed) snapshots += diskState()
                        if (restore) {
                            sawRestore.set(true)
                            if (refused) restoreRefused.set(true)
                            if (cutAtRestore && cutReached.compareAndSet(false, true)) {
                                throw CancellationException("synthetic process cut immediately after the restore commit")
                            }
                        }
                        saved
                    }
                    "apply" -> error("quoted ZBOOT state must use synchronous commit")
                    else -> error("unexpected editor operation in the quote/owed file: ${method.name}")
                }
            } as SharedPreferences.Editor
        }
    }

    /** Optional product process bookkeeping; absent on the baseline. Call only after owned sends are joined. */
    private fun resetZbootWorkIfPresent() {
        val field = ChatViewModel::class.java.declaredFields.firstOrNull { it.name == "zbootWorkByScope" } ?: return
        field.isAccessible = true
        (field.get(null) as? MutableMap<*, *>)?.clear()
    }

    private suspend fun killProcesses(vararg vms: ChatViewModel) {
        ResetOwedChat.kill(*vms)
        resetZbootWorkIfPresent()
    }

    private companion object {
        const val PEER = "usyntheticzc661atomicunstamp1"
        const val ALLOWANCE = "zbootallowance:$PEER"
        const val OWED = "zbootowed:$PEER"
    }
}
