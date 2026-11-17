package co.electriccoin.zcash.ui.screen.chat.viewmodel

import co.electriccoin.zcash.ui.common.repository.Transaction
import co.electriccoin.zcash.ui.common.repository.TransactionRepository
import co.electriccoin.zcash.ui.nostr.NostrChatBridge
import co.electriccoin.zcash.ui.screen.chat.crypto.E2EEncryption
import co.electriccoin.zcash.ui.screen.chat.model.GroupInfo
import co.electriccoin.zcash.ui.screen.chat.model.GroupMember
import co.electriccoin.zcash.ui.screen.chat.model.MemberStatus
import co.electriccoin.zcash.ui.screen.chat.viewmodel.AdminCheck.CONFIRMED
import co.electriccoin.zcash.ui.screen.chat.viewmodel.AdminCheck.GID
import co.electriccoin.zcash.ui.screen.chat.viewmodel.AdminCheck.M
import co.electriccoin.zcash.ui.screen.chat.viewmodel.AdminCheck.T
import co.electriccoin.zcash.ui.screen.chat.viewmodel.AdminCheck.X_OLD
import co.electriccoin.zcash.ui.screen.chat.viewmodel.AdminCheck.Y
import co.electriccoin.zcash.ui.screen.chat.viewmodel.AdminCheck.Y_NOSTR
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Instant
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger

/**
 * ZC-951 (review of train T7a2, 13 Oct 2026, findings 1 and 4; KI-25 R8) — the result of an admin confirmation
 * belongs to the group and the chat it was made for, and "Nothing was confirmed" is said only when no key is left.
 *
 * The result (`ChatViewModel.groupAdminConfirmation`) is held for the whole process and, at `e224d16ac`, says neither
 * which group nor which chat it is for. The result dialog of a group shows whatever is there.
 * - (a) Confirm is tapped in group A, whose history read is slow; the user goes back and taps Confirm in group B, which
 *   is refused at once. A's result arrives late and replaces B's, so B's dialog says "Admin confirmed" with B's name.
 *   Wanted: the result that is shown is the one of the last tap, and it names its group and its chat.
 * - (b) a result says which group and which chat it is for, and answers only for those (`isFor`), which is what the
 *   dialog asks before it shows one.
 * - (c) the contact's key changes while the confirmation's write is made, and the store then refuses to write the
 *   record from before back. At `e224d16ac` the dialog says "Nothing was confirmed" while the group's record keeps
 *   the key, and the gate accepts changes signed with it. Wanted: the record the phone goes on with is the one from
 *   before.
 *
 * Nothing is paid by any case. All addresses, keys, ids and times are synthetic.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AdminConfirmOutcomeIsForItsOwnGroupTest {
    private val sendScopes = SendHarnessScopes()
    private val scheduler = TestCoroutineScheduler()
    private val published: MutableList<Pair<String, String>> = Collections.synchronizedList(mutableListOf())

    @Before
    fun setUp() {
        sendScopes.open()
        Dispatchers.setMain(UnconfinedTestDispatcher(scheduler))
        AutoSpendVm.resetProcessState()
        AdminCheck.forgetConfirmedPasses()
        NostrChatBridge.unregisterPublisher()
    }

    @After
    fun tearDown() {
        NostrChatBridge.unregisterPublisher()
        sendScopes.close()
        Dispatchers.resetMain()
        AutoSpendVm.resetProcessState()
        AdminCheck.forgetConfirmedPasses()
    }

    private val created: Instant = Instant.ofEpochMilli(T)

    /** A drifted phone ready for the confirmation of [GID]: the creating invite from Y is in the history, Y is verified. */
    private fun readyPhone(): AdminCheckPhone =
        AdminCheck.driftedPhone().also { p ->
            p.creatingInvite(p.base.sessionWith(Y), created)
            p.verified += Y
            p.base.nostrKeys[Y] = Y_NOSTR
            AdminCheck.relayRecorder(published)
        }

    /** A second group on [p] that cannot confirm its admin either, and whose invitation is not in the history. */
    private fun AdminCheckPhone.withSecondGroup(): AdminCheckPhone =
        also {
            base.putGroup(
                GroupInfo(
                    groupId = GID_B,
                    name = "Second group",
                    creatorAddress = X_OLD,
                    createdAt = created,
                    isActive = true,
                    creatorPublicKey = null,
                ),
                listOf(
                    GroupMember(address = X_OLD, joinedAt = created, status = MemberStatus.ACTIVE, isAdmin = true),
                    GroupMember(address = self, joinedAt = created, status = MemberStatus.ACTIVE),
                    GroupMember(address = M, joinedAt = created, status = MemberStatus.ACTIVE),
                ),
                1,
                AnnounceDriver.newGroupKey(),
            )
        }

    /** The wallet history of a phone whose first whole read waits until [gate] is opened; every later read is as it is. */
    private class SlowFirstRead(
        private val history: TransactionRepository,
        private val gate: CompletableDeferred<Unit>,
    ) : TransactionRepository by history {
        val waiting = AtomicInteger(0)
        private val reads = AtomicInteger(0)

        override suspend fun getTransactions(): List<Transaction> {
            if (reads.getAndIncrement() == 0) {
                waiting.incrementAndGet()
                gate.await()
            }
            return history.getTransactions()
        }
    }

    /** The property [name] of the published result, or null when a result holds no such thing. */
    private fun resultProperty(
        vm: ChatViewModel,
        name: String,
    ): Any? {
        val result = vm.groupAdminConfirmation.value ?: throw AssertionError("fixture: no result was published")
        val getter = "get" + name.replaceFirstChar { it.uppercase() }
        return result.javaClass.methods
            .firstOrNull { it.name == getter && it.parameterCount == 0 }
            ?.invoke(result)
    }

    // ------------------------------------------------------------------ (a)

    @Test
    fun `a - a slow confirmation of another group never replaces the result of the group the user is in`() =
        runBlocking {
            val p = readyPhone().withSecondGroup()
            val vm = AdminCheck.chatVm(p, HandshakeSeam(Y))
            val gate = CompletableDeferred<Unit>()
            val slow = SlowFirstRead(p.history, gate)
            AutoSpendVm.setField(vm, "transactionRepository", slow)
            val key = p.admin.publicKey

            // Group A: Confirm is tapped and its history read does not come back yet. The user leaves the screen.
            AdminCheck.confirmNow(vm, Y, key, GID)
            assertEquals("fixture: group A's confirmation waits in its history read", 1, slow.waiting.get())
            assertNull("fixture: group A's confirmation has no result yet", AdminCheck.confirmationOutcome(vm))

            // Group B: Confirm is tapped. Its invitation is not in the history, so it is refused at once.
            AdminCheck.confirmNow(vm, Y, key, GID_B)
            HandshakeHarness.awaitTrue { AdminCheck.confirmationOutcome(vm) != null }
            assertEquals("fixture: group B's confirmation is refused", "NOT_THE_RECORDED_CHAT", AdminCheck.confirmationOutcome(vm))

            // Group A's confirmation ends while group B's result dialog is open.
            gate.complete(Unit)
            HandshakeHarness.awaitIdle(vm)

            assertEquals("fixture: group A's admin was confirmed", key, p.base.info(GID).creatorPublicKey)
            assertNull("group B's admin was confirmed", p.base.info(GID_B).creatorPublicKey)
            assertEquals(
                "group B's dialog is handed the result of group A's confirmation: it says Admin confirmed with B's name",
                "NOT_THE_RECORDED_CHAT",
                AdminCheck.confirmationOutcome(vm),
            )
            assertEquals("the result does not say that it is group B's", GID_B, resultProperty(vm, "groupId"))
            assertEquals("the result does not say which chat it is for", Y, resultProperty(vm, "candidate"))
        }

    // ------------------------------------------------------------------ (b)

    @Test
    fun `b - a result names its group and its chat, and answers for no other group and no other chat`() =
        runBlocking {
            val p = readyPhone().withSecondGroup()
            val vm = AdminCheck.chatVm(p, HandshakeSeam(Y))

            assertEquals(CONFIRMED, AdminCheck.confirm(vm, Y, p.admin.publicKey))
            HandshakeHarness.awaitIdle(vm)

            assertEquals("a confirmation's result does not say which group it is for", GID, resultProperty(vm, "groupId"))
            assertEquals("a confirmation's result does not say which chat it is for", Y, resultProperty(vm, "candidate"))
            assertTrue("the result does not answer for its own group and chat", isFor(vm, GID, Y))
            assertFalse("group A's result answers for group B: B's dialog would show it", isFor(vm, GID_B, Y))
            assertFalse("the result answers for a chat that was not the one confirmed", isFor(vm, GID, M))
            assertFalse("the result answers for a screen that confirmed nothing", isFor(vm, GID, null))

            assertEquals("NOT_NEEDED", AdminCheck.confirm(vm, Y, p.admin.publicKey))
            assertEquals("a refusal does not say which group it is for", GID, resultProperty(vm, "groupId"))
            assertEquals("a refusal does not say which chat it is for", Y, resultProperty(vm, "candidate"))
            assertFalse("group A's refusal answers for group B", isFor(vm, GID_B, Y))
        }

    /** What the result dialog asks before it shows a result: `AdminConfirmOutcome.isFor(groupId, candidate)`. */
    private fun isFor(
        vm: ChatViewModel,
        groupId: String,
        candidate: String?,
    ): Boolean {
        val result = vm.groupAdminConfirmation.value ?: throw AssertionError("fixture: no result was published")
        val rule =
            result.javaClass.methods.firstOrNull { it.name == "isFor" && it.parameterCount == 2 }
                ?: throw AssertionError(
                    "a confirmation's result cannot be asked whether it is for a group and a chat " +
                        "(AdminConfirmOutcome.isFor(groupId, candidate)): every group's dialog shows it",
                )
        return rule.invoke(result, groupId, candidate) as Boolean
    }

    // ------------------------------------------------------------------ (c)

    @Test
    fun `c - when the record from before cannot be written back the phone still goes on with it and accepts no change`() =
        runBlocking {
            val p = readyPhone()
            val vm = AdminCheck.chatVm(p, HandshakeSeam(Y))
            val key = p.admin.publicKey
            val recordBefore = p.base.info(GID)
            val replacement = E2EEncryption.generateKeyPair().publicKey
            // The first durable write is the creator key: inside it the contact's key becomes another one. The second
            // is the record from before: the store refuses it and keeps what it holds, as a failed commit() does.
            p.onDurableWrite = { if (p.durableWrites.size == 1) p.base.peerKeys[Y] = replacement else p.storeRefuses = true }
            val signedData = "GK|$GID|$M|$Y|2|"
            val signature = E2EEncryption.sign(p.admin.privateKey, signedData)

            val outcome = AdminCheck.confirm(vm, Y, key)
            HandshakeHarness.awaitIdle(vm)

            assertEquals("fixture: the write and the write-back were both tried", 2, p.durableWrites.size)
            assertEquals("KEY_DIFFERS", outcome)
            assertNull(
                "the dialog says Nothing was confirmed and the group's record holds the key",
                p.base.info(GID).creatorPublicKey,
            )
            assertEquals("the record is not the one from before the confirmation", recordBefore, p.base.info(GID))
            assertEquals("the group reads as confirmed", "UNKNOWN", AdminCheck.keyState(vm))
            assertFalse(
                "after Nothing was confirmed the group accepts a change signed with the key that was not confirmed",
                AdminCheck.verifyAdminControl(vm, Y, signedData, signature),
            )
            assertEquals("a refused confirmation published over the relays", emptyList<Pair<String, String>>(), published.toList())
        }

    // ------------------------------------------------------------------ (d)

    /**
     * ZC-951 residual (review of the night follow-ups, 14 Oct 2026, finding 2): as (c), and the put-back of the record
     * from before into memory fails too (the key store does not answer). Memory then holds the key that was never
     * confirmed, so the result must not be KEY_DIFFERS ("Nothing was confirmed: something changed"). Review of 14 Oct
     * finding 5: nor STORE_REFUSED, whose text says nothing was confirmed either; it is KEY_CHANGED_NOT_UNDONE, whose
     * text says the key was saved and could not be undone.
     */
    @Test
    fun `d - when the write-back and the put-back both fail the result is KEY_CHANGED_NOT_UNDONE`() =
        runBlocking {
            val p = readyPhone()
            val vm = AdminCheck.chatVm(p, HandshakeSeam(Y))
            val replacement = E2EEncryption.generateKeyPair().publicKey
            p.onDurableWrite = { if (p.durableWrites.size == 1) p.base.peerKeys[Y] = replacement else p.storeRefuses = true }
            p.onCall = { name ->
                if (name == "saveGroupInfo" && p.storeRefuses) throw IllegalStateException("synthetic: the key store does not answer")
            }

            val outcome = AdminCheck.confirm(vm, Y, p.admin.publicKey)
            HandshakeHarness.awaitIdle(vm)

            assertEquals("fixture: the write and the write-back were both tried", 2, p.durableWrites.size)
            assertTrue("fixture: the put-back was tried", "saveGroupInfo" in p.calls)
            assertEquals(
                "the result says Nothing was confirmed while memory holds the key that was not confirmed",
                "KEY_CHANGED_NOT_UNDONE",
                outcome,
            )
            assertEquals("a refused confirmation published over the relays", emptyList<Pair<String, String>>(), published.toList())
        }

    // ------------------------------------------------------------------ (e)

    /**
     * ZC-951 residual (same review, nit 3): folding or unfolding the Fold rebuilds the group screen (the manifest's
     * configChanges leave out smallestScreenSize). The chat this screen is confirming must survive that, or the result
     * is never shown: the group id and the chat's address and name are kept with rememberSaveable, not remember.
     */
    @Test
    fun `e - the chat the screen is confirming survives a rebuild of the screen`() {
        val source =
            co.electriccoin.zcash.ui.screen.chat.view.ChatResourceText
                .source("ui-lib/src/main/java/co/electriccoin/zcash/ui/screen/chat/AndroidChat.kt")
        val body = source.substringAfter("private fun GroupAdminConfirmDialogs(").substringBefore("\n}\n")
        assertFalse(
            "the confirming chat is held with remember, which a fold of the screen loses",
            Regex("""by remember\(groupId\)""").containsMatchIn(body),
        )
        assertTrue(
            "the confirming chat is not held with rememberSaveable",
            Regex("""rememberSaveable\(groupId\)""").containsMatchIn(body),
        )
    }

    private companion object {
        const val GID_B = "zgrp_2d3e4f5a6b7c8d9e0f1a2b3c"
    }
}
