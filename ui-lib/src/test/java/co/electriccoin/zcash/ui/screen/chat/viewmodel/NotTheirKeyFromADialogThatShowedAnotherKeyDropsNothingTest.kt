package co.electriccoin.zcash.ui.screen.chat.viewmodel

import android.content.SharedPreferences
import androidx.lifecycle.viewModelScope
import co.electriccoin.zcash.ui.screen.chat.crypto.E2EEncryption
import co.electriccoin.zcash.ui.screen.chat.crypto.E2EKeyPair
import co.electriccoin.zcash.ui.screen.chat.datasource.ZchatPreferencesImpl
import co.electriccoin.zcash.ui.screen.chat.viewmodel.HandshakeHarness.TIMEOUT_MS
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.job
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * ZC-702 — Not their key applies only to the whole refused key whose number the dialog showed.
 *
 * The chat uses real signed incoming offers and the real ZchatPreferencesImpl over in-memory files. Taking A models
 * the accept's final store write; the new B offer then reaches the real inbound handler. No accept or payment is
 * invented. A separate synchronous callback replaces A just before the real store dismissal, after the VM's peek.
 * Every generated key/address is synthetic; equality failures contain stable messages rather than key values.
 */
class NotTheirKeyFromADialogThatShowedAnotherKeyDropsNothingTest : KeyChangeAcceptTestBase() {
    private data class State(
        val files: Map<String, Map<String, Any?>>,
        val record: Any?,
        val storedKey: String?,
        val flag: Boolean,
    )

    private fun state(chat: KeyChangeAcceptChat): State =
        State(chat.files.mapValues { it.value.snapshot() }, chat.record(), chat.storedKey(), chat.flag())

    private fun assertUnchanged(chat: KeyChangeAcceptChat, before: State, writes: Writes) {
        assertTrue("a dismissal for another dialog changed the current refused-key record", before.record == chat.record())
        assertEquals("a dismissal for another dialog changed the warning flag", before.flag, chat.flag())
        assertTrue("a dismissal for another dialog changed the stored contact key", before.storedKey == chat.storedKey())
        assertTrue("a rejected dismissal changed stored preference data", before.files == chat.files.mapValues { it.value.snapshot() })
        assertEquals("a rejected dismissal committed preference data", 0, writes.commits.get())
        assertEquals("a rejected dismissal applied preference data", 0, writes.applies.get())
    }

    private fun assertFreeAndNoWipe(chat: KeyChangeAcceptChat, vm: ChatViewModel? = null) {
        assertEquals("Not their key must not propose a payment", 0, chat.seam.proposals())
        assertEquals("Not their key must not submit a payment", 0, chat.seam.submitted.size)
        assertTrue("Not their key must not wipe session preferences", chat.reset.wipes.isEmpty())
        assertTrue("Not their key must not delete ratchet state", chat.reset.ratchetDeletes.isEmpty())
        assertFalse("Not their key must not record an owed key exchange", PEER in chat.owed.kexOwed)
        assertFalse("Not their key must not grant a ZBOOT allowance", chat.owed.zbootQuoted[PEER] == true)
        if (vm != null) assertNull("Not their key must not ask a price", chat.question(vm))
    }

    /** Hold the existing peer lock so the real dismissal Job can be captured and checked for hidden child failures. */
    private suspend fun dismiss(vm: ChatViewModel, shownKey: String) {
        val lock = KeyAcceptApi.peerSessionLock(PEER)
        assertTrue("fixture: no earlier operation owns the peer session", lock.tryLock())
        var ownsLock = true
        val started = mutableListOf<Job>()
        try {
            vm.resetSendState()
            val previous =
                vm.viewModelScope.coroutineContext.job.children
                    .toSet()
            KeyAcceptApi.dismiss(vm, PEER, shownKey)
            started +=
                vm.viewModelScope.coroutineContext.job.children
                    .filter { it !in previous }
            assertEquals("fixture: one real dismissal waits for the peer lock", 1, started.size)
            val sender = started.single()
            assertTrue("fixture: the real dismissal is waiting for the peer lock", sender.isActive)
            lock.unlock()
            ownsLock = false
            withTimeout(TIMEOUT_MS) { sender.join() }
            assertFalse("fixture: the real dismissal failed or was cancelled", sender.isCancelled)
        } finally {
            withContext(NonCancellable) {
                started.forEach { it.cancel() }
                if (ownsLock) lock.unlock()
                withTimeout(TIMEOUT_MS) { started.joinAll() }
            }
        }
    }

    private fun remember(chat: KeyChangeAcceptChat, key: E2EKeyPair) {
        assertTrue(
            "fixture: the genuine signed offer is remembered",
            KeyAcceptApi.remember(chat.impl, PEER, key.publicKey, chat.kexMemo(key), "synthetic-zc702-store-offer", 1_000L),
        )
        assertTrue("fixture: the current record contains the whole offered key", chat.recordKey() == key.publicKey)
        assertTrue("fixture: the offer raises the warning flag", chat.flag())
        assertTrue("fixture: the old contact key is still stored", chat.storedKey() == chat.oldContact.publicKey)
    }

    private fun shownKey(chat: KeyChangeAcceptChat, vm: ChatViewModel, offered: E2EKeyPair): String {
        val view = KeyAcceptApi.view(vm, PEER)
        assertNotNull("fixture: the dialog has a refused-key snapshot", view)
        val shown = KeyAcceptApi.get(view!!, "refusedKey") as String
        assertTrue("fixture: the dialog shows the whole refused key", shown == offered.publicKey)
        assertTrue("fixture: Not their key is offered while the old key is stored", KeyAcceptApi.get(view, "oldKeyStored") == true)
        assertTrue("fixture: the warning is raised", chat.flag())
        assertTrue("fixture: the send gate is closed before dismissal", chat.sendsRefused(vm))
        return shown
    }

    @Test
    fun `Not their key for dialog A cannot drop the later offer B or open sends`() =
        runBlocking {
            val chat = KeyChangeAcceptChat(PEER)
            val vm = chat.viewModel()
            val a = E2EEncryption.generateKeyPair()
            val b = E2EEncryption.generateKeyPair()
            assertFalse("fixture: the offers are distinct whole keys", a.publicKey == b.publicKey)
            chat.raiseBanner(vm, a, "synthetic-zc702-first-offer")
            val shown = shownKey(chat, vm, a)
            assertNotNull("fixture: A's accept final write took its record", KeyAcceptApi.take(chat.impl, PEER, shown))
            assertNull("fixture: the earlier episode ended", chat.record())
            assertFalse("fixture: the earlier episode lowered its flag", chat.flag())
            chat.raiseBanner(vm, b, "synthetic-zc702-later-offer")
            assertTrue("fixture: the new episode remembers B", chat.recordKey() == b.publicKey)
            assertTrue("fixture: B has its own warning", chat.flag())
            val writes = Writes.install(chat)
            val before = state(chat)

            dismiss(vm, shown)

            assertUnchanged(chat, before, writes)
            assertEquals("a stale dismissal must ask the user to decide again", KeyAcceptApi.changedAgainText(), chat.error(vm))
            assertTrue("a stale dismissal must keep the send gate closed", chat.sendsRefused(vm))
            assertFreeAndNoWipe(chat, vm)
        }

    @Test
    fun `the store refuses a dismissal for a different whole key without a write`() {
        val chat = KeyChangeAcceptChat(PEER)
        val a = E2EEncryption.generateKeyPair()
        remember(chat, a)
        val other = E2EEncryption.generateKeyPair().publicKey
        assertFalse("fixture: the expected key is different", other == a.publicKey)
        val writes = Writes.install(chat)
        val before = state(chat)

        assertFalse("the store dismissed a record for a different whole key", KeyAcceptApi.dismissInStore(chat.impl, PEER, other))

        assertUnchanged(chat, before, writes)
        assertFreeAndNoWipe(chat)
    }

    @Test
    fun `the store refuses a prefix of the remembered whole key without a write`() {
        val chat = KeyChangeAcceptChat(PEER)
        val a = E2EEncryption.generateKeyPair()
        remember(chat, a)
        val prefix = a.publicKey.dropLast(1)
        assertTrue("fixture: the expected key is a proper nonempty prefix", prefix.isNotEmpty() && prefix != a.publicKey)
        val writes = Writes.install(chat)
        val before = state(chat)

        assertFalse("the store dismissed a record for only a prefix", KeyAcceptApi.dismissInStore(chat.impl, PEER, prefix))

        assertUnchanged(chat, before, writes)
        assertFreeAndNoWipe(chat)
    }

    @Test
    fun `control - the store with no record keeps a legacy flag and changes nothing`() {
        val chat = KeyChangeAcceptChat(PEER)
        val shown = E2EEncryption.generateKeyPair().publicKey
        chat.impl.setE2EKeyChanged(PEER, true)
        assertNull("fixture: a legacy warning has no remembered record", chat.record())
        val writes = Writes.install(chat)
        val before = state(chat)

        assertFalse("no record must return false", KeyAcceptApi.dismissInStore(chat.impl, PEER, shown))

        assertUnchanged(chat, before, writes)
        assertFreeAndNoWipe(chat)
    }

    @Test
    fun `control - the store dismisses its exact whole key and flag in one commit leaving the old key`() {
        val chat = KeyChangeAcceptChat(PEER)
        val a = E2EEncryption.generateKeyPair()
        remember(chat, a)
        val writes = Writes.install(chat)

        assertTrue("the current whole key must dismiss", KeyAcceptApi.dismissInStore(chat.impl, PEER, a.publicKey))

        assertNull("the current record must be removed", chat.record())
        assertFalse("the current flag must be removed", chat.flag())
        assertTrue("a matching store dismissal must keep the old key", chat.storedKey() == chat.oldContact.publicKey)
        assertEquals("the current record and flag must go in one commit", 1, writes.commits.get())
        assertEquals("the dismissal must not use an asynchronous apply", 0, writes.applies.get())
        assertFreeAndNoWipe(chat)
    }

    @Test
    fun `control - Not their key for the current dialog is free and opens sends to the unchanged old key`() =
        runBlocking {
            val chat = KeyChangeAcceptChat(PEER)
            val vm = chat.viewModel()
            val a = E2EEncryption.generateKeyPair()
            chat.raiseBanner(vm, a, "synthetic-zc702-current-offer")
            val shown = shownKey(chat, vm, a)
            val writes = Writes.install(chat)

            dismiss(vm, shown)

            assertNull("the current dialog must remove its record", chat.record())
            assertFalse("the current dialog must lower its warning", chat.flag())
            assertTrue("Not their key must keep the old contact key", chat.storedKey() == chat.oldContact.publicKey)
            assertTrue("Not their key must keep our own public key", chat.impl.getE2EOurPublicKey(PEER) == chat.our.publicKey)
            assertEquals("the dialog's record and flag must go in one commit", 1, writes.commits.get())
            assertEquals("the dialog must not asynchronously apply its dismissal", 0, writes.applies.get())
            assertNull("a matching dismissal must not report a failure", chat.error(vm))
            assertFalse("a matching dismissal opens sends to the old key", chat.sendsRefused(vm))
            assertFreeAndNoWipe(chat, vm)
        }

    @Test
    fun `a new offer between the view model peek and store compare stays and reports changed again`() =
        runBlocking {
            val chat = KeyChangeAcceptChat(PEER)
            val vm = chat.viewModel()
            val a = E2EEncryption.generateKeyPair()
            val b = E2EEncryption.generateKeyPair()
            assertFalse("fixture: the race offers are distinct whole keys", a.publicKey == b.publicKey)
            chat.raiseBanner(vm, a, "synthetic-zc702-race-offer")
            val shown = shownKey(chat, vm, a)
            val writes = Writes.install(chat)
            var replaced = false
            var replacement: State? = null
            val store =
                JoinHeldCopy.intercepting(chat.store) { name, _ ->
                    if (name == "dismissRefusedKeyOffer") {
                        assertFalse("fixture: the real dismissal enters its store only once", replaced)
                        replaced = true
                        assertTrue("fixture: the VM peek still named A before the store call", chat.recordKey() == shown)
                        assertNotNull("fixture: the earlier episode ends at its last write", KeyAcceptApi.take(chat.impl, PEER, shown))
                        remember(chat, b)
                        replacement = state(chat)
                        writes.commits.set(0)
                        writes.applies.set(0)
                    }
                    JoinHeldCopy.PASS
                }
            AutoSpendVm.setField(vm, "zchatPreferences", store)

            dismiss(vm, shown)

            assertTrue("fixture: the dismissal reached the real store boundary", replaced)
            assertNotNull("fixture: a genuine B record was installed before store comparison", replacement)
            assertUnchanged(chat, replacement!!, writes)
            assertEquals("a changed store record must ask the user to decide again", KeyAcceptApi.changedAgainText(), chat.error(vm))
            assertTrue("the changed record must keep the send gate closed", chat.sendsRefused(vm))
            assertFreeAndNoWipe(chat, vm)
        }

    /** Counts the real E2E file's commits and applies; each editor operation returns this wrapper. */
    private class Writes(
        private val inner: MemorySharedPreferences,
    ) : SharedPreferences by inner {
        val commits = AtomicInteger(0)
        val applies = AtomicInteger(0)

        override fun edit(): SharedPreferences.Editor {
            val editor = inner.edit()
            return object : SharedPreferences.Editor by editor {
                override fun putString(key: String, value: String?) = apply { editor.putString(key, value) }

                override fun putBoolean(key: String, value: Boolean) = apply { editor.putBoolean(key, value) }

                override fun putInt(key: String, value: Int) = apply { editor.putInt(key, value) }

                override fun putLong(key: String, value: Long) = apply { editor.putLong(key, value) }

                override fun remove(key: String) = apply { editor.remove(key) }

                override fun clear() = apply { editor.clear() }

                override fun commit(): Boolean {
                    commits.incrementAndGet()
                    return editor.commit()
                }

                override fun apply() {
                    applies.incrementAndGet()
                    editor.apply()
                }
            }
        }

        companion object {
            fun install(chat: KeyChangeAcceptChat): Writes {
                val writes = Writes(chat.files.getValue("e2ePrefs"))
                ZchatPreferencesImpl::class.java
                    .getDeclaredField("e2ePrefs")
                    .apply { isAccessible = true }
                    .set(chat.impl, writes)
                return writes
            }
        }
    }

    private companion object {
        const val PEER = "usyntheticzc702dismiss000001"
    }
}
