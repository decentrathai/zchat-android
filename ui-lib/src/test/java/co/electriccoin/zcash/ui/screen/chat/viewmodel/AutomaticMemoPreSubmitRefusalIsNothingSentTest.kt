package co.electriccoin.zcash.ui.screen.chat.viewmodel

import cash.z.ecc.android.sdk.model.TransactionId
import cash.z.ecc.android.sdk.model.Zatoshi
import co.electriccoin.zcash.ui.common.model.OrchardSealedException
import co.electriccoin.zcash.ui.common.model.PublicCrossingException
import co.electriccoin.zcash.ui.common.model.SubmitResult
import co.electriccoin.zcash.ui.screen.chat.datasource.ZchatPreferences
import co.electriccoin.zcash.ui.screen.chat.model.ChatSendCopy
import co.electriccoin.zcash.ui.screen.chat.model.SendMessageState
import co.electriccoin.zcash.ui.screen.chat.viewmodel.HandshakeHarness.awaitIdle
import co.electriccoin.zcash.ui.screen.chat.viewmodel.HandshakeHarness.chatViewModelWith
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.time.Instant
import java.util.Base64
import java.util.concurrent.atomic.AtomicInteger

/**
 * C1 (round 7 adversarial review of B1, item 1, the ChatViewModel sibling) — an automatic handshake memo
 * refused BEFORE anything was stored is treated as "may have been sent".
 *
 * `sendMemoWithBlockRetry` uses the same rule as the group helper: a failure is NOTHING_SENT only with
 * `SubmitRejectedException(nothingStored = true)` or an `InsufficientFundsException` in the chain. A refusal the
 * send chokepoint raises before any transaction exists (`PublicCrossingException` when the built proposal would
 * publish the amount, `OrchardSealedException`, the ZC-102 Keystone refusal) is MAY_HAVE_BEEN_SENT, so:
 *
 * - a KEX keeps the paid-setup record written for that attempt, and the next trigger reads it as "already paid"
 *   and sends nothing: the key exchange is stuck until a forced bootstrap;
 * - a held GROUP_ACCEPT is dropped instead of held again, so the creator keeps us Invited for good.
 *
 * And the reverse: once the submit has started, a stored-then-rejected transaction whose rejection text looks like
 * a shortfall is rewrapped by the use case as an `InsufficientFundsException`; that must not prove nothing was
 * sent, or the accept is held again and paid a second time.
 *
 * C5 (round 7 review of R6-4, finding 1, step 3): with consent given, a held accept the wallet cannot pay yet (every
 * zatoshi still in the sealed Orchard pool, the first Ironwood deposit still confirming, or a Keystone account
 * selected) was claimed on every block: its stored copy deleted, the attempt refused by the chokepoint, and the copy
 * written back. A kill between the two commits loses it, and nothing is gained by trying. Wanted: while the wallet
 * cannot pay it, a block leaves it held in memory and on disk with no write at all, and it is sent once when the
 * wallet can pay (Ironwood spendable, or a software account selected).
 *
 * Round 8 P1 (PHONE_TRIAGE F3): a group reply is paid only by the group screen's Join, never under the standing
 * consent, and no block sends it. A Join refused before anything was stored, or made while the wallet cannot pay yet,
 * puts the reply back as waiting for Join (it was held for the next block); blocks never touch it; the next Join sends
 * it once.
 *
 * All addresses, keys, group ids and txids are synthetic.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AutomaticMemoPreSubmitRefusalIsNothingSentTest {
    private val sendScopes = SendHarnessScopes()

    @Before
    fun setUp() {
        sendScopes.open()
        Dispatchers.setMain(UnconfinedTestDispatcher())
        AutoSpendVm.resetProcessState()
    }

    @After
    fun tearDown() {
        sendScopes.close()
        Dispatchers.resetMain()
        AutoSpendVm.resetProcessState()
    }

    private fun publicCrossing() = PublicCrossingException(Zatoshi(90_000), Zatoshi(0))

    private fun invitePayload(inviter: String): String {
        val members = JSONArray()
        members.put(inviter)
        members.put(HandshakeHarness.SELF)
        return JSONObject()
            .put("name", "Synthetic group")
            .put("inviter", inviter)
            .put("group_key", Base64.getEncoder().encodeToString(ByteArray(32) { it.toByte() }))
            .put("members", members)
            .toString()
    }

    private fun processInvite(
        vm: ChatViewModel,
        groupId: String,
        inviter: String
    ) {
        ChatViewModel::class.java
            .getDeclaredMethod(
                "processGroupInvite",
                String::class.java,
                String::class.java,
                TransactionId::class.java,
                Instant::class.java,
                String::class.java,
                String::class.java,
            ).apply { isAccessible = true }
            .invoke(vm, groupId, invitePayload(inviter), null, Instant.EPOCH, null, null)
    }

    @Test
    fun `a KEX refused by the chokepoint before anything was stored is re-armed and the next trigger pays it`() =
        runBlocking {
            val peer = "usyntheticc1kexrefused00001"
            val prefs = AutoSpendPrefs(consent = true).apply { session(peer, complete = false) }
            prefs.ownBootSent[peer] = true
            val seam = HandshakeSeam(peer, failFirstProposalWith = publicCrossing())
            val vm = chatViewModelWith(prefs.proxy, seam).also(AutoSpendVm::wireBootstrapFields)

            vm.sendKEXMessage(peer, HandshakeHarness.SELF)
            awaitIdle(vm)
            assertEquals("fixture sanity: the refused KEX re-armed", false, prefs.ownBootSent[peer])
            assertEquals("fixture sanity: nothing was submitted", 0, seam.submits.get())

            prefs.ownBootSent[peer] = true // the next bootstrap trigger
            vm.sendKEXMessage(peer, HandshakeHarness.SELF)
            awaitIdle(vm)

            assertEquals(
                "the KEX was refused before anything was stored, but its paid-setup record stayed, so the next " +
                    "trigger read it as already paid and the key exchange is stuck",
                1,
                seam.broadcasts.size,
            )
        }

    @Test
    fun `a GROUP_ACCEPT refused by the chokepoint before anything was stored waits for Join again and is sent once`() =
        runBlocking {
            val inviter = "usyntheticc1acceptrefused01"
            val prefs = AutoSpendPrefs(consent = true).apply { session(inviter, complete = true) }
            // Refused while the proposal is built; consent goes at the same moment (a move of the old balance).
            val seam =
                HandshakeSeam(
                    peer = inviter,
                    failFirstProposalWith = publicCrossing(),
                    onFirstAttempt = { prefs.consent = false },
                )
            val vm = chatViewModelWith(prefs.proxy, seam).also(AutoSpendVm::wireBootstrapFields)

            processInvite(vm, "GSYNTHC11", inviter)
            awaitIdle(vm)
            GroupInviteDriver.join(vm, "GSYNTHC11")

            assertTrue("fixture sanity: the group was joined", prefs.groupInfo.containsKey("GSYNTHC11"))
            assertEquals("fixture sanity: the Join was attempted", 1, seam.proposalRequests.get())
            assertEquals("fixture sanity: nothing was submitted", 0, seam.submits.get())
            assertTrue(
                "the accept was refused before anything was stored, but it was dropped instead of waiting for Join " +
                    "again; the creator keeps us Invited for good",
                prefs.needsJoin.containsKey("GSYNTHC11"),
            )

            vm.grantHandshakeSpendConsent()
            awaitIdle(vm)
            assertEquals("the standing consent paid a group join", 0, seam.broadcasts.size)
            GroupInviteDriver.join(vm, "GSYNTHC11")
            assertEquals("joining again must send the accept once", 1, seam.broadcasts.size)
            assertTrue("a sent accept must not stay stored", prefs.heldGroupAccepts.isEmpty())
        }

    @Test
    fun `a GROUP_ACCEPT stored then rejected with a shortfall text is not held again and not paid twice`() =
        runBlocking {
            val inviter = "usyntheticc1acceptstored01"
            val prefs = AutoSpendPrefs(consent = true).apply { session(inviter, complete = true) }
            val seam =
                HandshakeSeam(
                    peer = inviter,
                    firstSubmitOutcome =
                        SubmitResult.Failure(
                            txIds = listOf("synthetic-stored-accept-T"),
                            code = -26,
                            description = "Insufficient balance (synthetic rejection text)",
                        ),
                    onFirstAttempt = { prefs.consent = false },
                )
            val vm = chatViewModelWith(prefs.proxy, seam).also(AutoSpendVm::wireBootstrapFields)

            processInvite(vm, "GSYNTHC12", inviter)
            awaitIdle(vm)
            GroupInviteDriver.join(vm, "GSYNTHC12")

            assertEquals("fixture sanity: one submit", 1, seam.submits.get())
            assertTrue(
                "the transaction was stored and may still be broadcast, but a shortfall text after the submit made " +
                    "the accept held again",
                prefs.heldGroupAccepts.isEmpty(),
            )
            assertTrue(
                "the transaction was stored and may still be broadcast, but a shortfall text after the submit made " +
                    "the accept wait for Join again",
                prefs.needsJoin.isEmpty(),
            )

            vm.grantHandshakeSpendConsent()
            awaitIdle(vm)
            GroupInviteDriver.join(vm, "GSYNTHC12")
            assertEquals("the stored GROUP_ACCEPT was paid a second time", 1, seam.submits.get())
        }

    // ---- C5: an accept the wallet cannot pay yet is left alone by every block ------------------------------------

    /** [prefs] seen through a proxy that counts the writes of [groupId]'s stored held accept. */
    private class StoredAcceptWrites(
        private val prefs: AutoSpendPrefs,
        private val groupId: String,
    ) {
        val count = AtomicInteger(0)
        val proxy: ZchatPreferences =
            Proxy.newProxyInstance(
                ZchatPreferences::class.java.classLoader,
                arrayOf(ZchatPreferences::class.java),
            ) { _, method, args ->
                if (method.name == "setHeldGroupAccept" && args?.get(0) == groupId) count.incrementAndGet()
                try {
                    method.invoke(prefs.proxy, *(args ?: emptyArray()))
                } catch (e: InvocationTargetException) {
                    throw e.targetException
                }
            } as ZchatPreferences
    }

    private fun heldInMemory(groupId: String): Boolean =
        ChatViewModel::class.java
            .getDeclaredField("heldGroupAccepts")
            .apply { isAccessible = true }
            .let { (it.get(null) as Map<*, *>).containsKey(groupId) }

    private suspend fun chainTip(
        vm: ChatViewModel,
        height: Long,
    ) {
        ChatViewModel::class.java
            .getDeclaredMethod("onChainTip", Long::class.javaObjectType)
            .apply { isAccessible = true }
            .invoke(vm, height)
        awaitIdle(vm)
    }

    /**
     * Consent is given and the user taps Join on the invite of [groupId] while [cannotPay] describes the wallet. Round 8
     * P1: nothing is built or submitted and no held copy is left (ZC-284: Join writes one at the tap and removes it when
     * the reply goes back); the reply waits for Join again (it was held for the next block). A block must neither
     * attempt it nor touch its stored records; once [canPay] runs, blocks still send nothing and the next Join sends it
     * once.
     */
    private suspend fun heldWhileTheWalletCannotPay(
        groupId: String,
        inviter: String,
        what: String,
        cannotPay: (SelectedAccountFeed) -> Unit,
        canPay: (SelectedAccountFeed) -> Unit,
        notice: String,
    ) {
        val prefs = AutoSpendPrefs(consent = true).apply { session(inviter, complete = true) }
        val writes = StoredAcceptWrites(prefs, groupId)
        val accounts = SelectedAccountFeed.create().also(cannotPay)
        val seam = HandshakeSeam(inviter)
        val vm =
            chatViewModelWith(writes.proxy, seam, accountSource = accounts)
                .also(AutoSpendVm::wireBootstrapFields)

        processInvite(vm, groupId, inviter)
        awaitIdle(vm)
        assertTrue("fixture sanity: the group was joined", prefs.groupInfo.containsKey(groupId))
        writes.count.set(0)
        GroupInviteDriver.join(vm, groupId)
        assertEquals("fixture sanity: nothing was submitted", 0, seam.submits.get())
        assertEquals("Join built the accept while $what", 0, seam.proposalRequests.get())
        assertTrue("the accept must wait for Join again while $what", prefs.needsJoin.containsKey(groupId))
        // ZC-284: Join keeps a copy of its reply on disk from the tap; once the reply waits for Join again it is gone.
        assertFalse("Join left a held copy of an accept it could not attempt while $what", prefs.heldGroupAccepts.containsKey(groupId))
        writes.count.set(0)
        // Round 8 P1: the group screen is told why its Join sent nothing (the reply only waits for Join again).
        assertEquals(
            "Join sent nothing while $what and did not say why",
            SendMessageState.Error(notice),
            HandshakeHarness.sendMessageState(vm),
        )

        chainTip(vm, HandshakeHarness.START_HEIGHT + 1)
        assertEquals("a block submitted the accept while $what", 0, seam.submits.get())
        assertEquals("a block built the accept while $what", 0, seam.proposalRequests.get())
        assertFalse("a block held the accept in memory while $what", heldInMemory(groupId))
        assertTrue("a block dropped the waiting accept while $what", prefs.needsJoin.containsKey(groupId))
        assertEquals("a block wrote the accept's stored copy while $what", 0, writes.count.get())

        canPay(accounts)
        chainTip(vm, HandshakeHarness.START_HEIGHT + 2)
        assertEquals("a block paid a reply that waits for Join", 0, seam.submits.get())
        GroupInviteDriver.join(vm, groupId)
        assertEquals("once the wallet can pay, the next Join must send the accept once", 1, seam.submits.get())
        chainTip(vm, HandshakeHarness.START_HEIGHT + 3)
        assertEquals("the held accept was paid twice", 1, seam.submits.get())
        assertEquals(1, seam.broadcasts.size)
        assertTrue("a sent accept must not stay stored", prefs.heldGroupAccepts.isEmpty())
        assertFalse("a sent accept must not stay held", heldInMemory(groupId))
    }

    @Test
    fun `a GROUP_ACCEPT joined, then waiting for Join, is left alone by every block while all funds are in the sealed Orchard pool`() =
        runBlocking {
            heldWhileTheWalletCannotPay(
                groupId = "GSYNTHC51",
                inviter = "usyntheticc5sealedorchard01",
                what = "every zatoshi is in the sealed Orchard pool",
                cannotPay = { it.sealedOrchard() },
                canPay = { it.ironwoodSpendable() },
                notice = OrchardSealedException.ORCHARD_SEALED_MESSAGE,
            )
        }

    @Test
    fun `a GROUP_ACCEPT joined, then waiting for Join, is left alone by every block while the first Ironwood deposit confirms`() =
        runBlocking {
            heldWhileTheWalletCannotPay(
                groupId = "GSYNTHC52",
                inviter = "usyntheticc5ironwoodwait01",
                what = "the first Ironwood deposit is still confirming",
                cannotPay = { it.ironwoodConfirming() },
                canPay = { it.ironwoodSpendable() },
                notice = OrchardSealedException.IRONWOOD_CONFIRMING_MESSAGE,
            )
        }

    @Test
    fun `a GROUP_ACCEPT joined, then waiting for Join, is left alone by every block while a Keystone account is selected`() =
        runBlocking {
            heldWhileTheWalletCannotPay(
                groupId = "GSYNTHC53",
                inviter = "usyntheticc5keystone000001",
                what = "a Keystone account, which cannot sign an automatic send, is selected",
                cannotPay = { it.keystoneSelected() },
                canPay = { it.ironwoodSpendable() },
                notice = ChatSendCopy.KEYSTONE_AUTOMATIC_SETUP,
            )
        }
}
