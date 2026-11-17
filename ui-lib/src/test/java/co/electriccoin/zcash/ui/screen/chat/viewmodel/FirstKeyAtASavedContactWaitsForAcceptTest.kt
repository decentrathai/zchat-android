package co.electriccoin.zcash.ui.screen.chat.viewmodel

import co.electriccoin.zcash.ui.screen.chat.crypto.E2EEncryption
import co.electriccoin.zcash.ui.screen.chat.model.SendMessageState
import co.electriccoin.zcash.ui.screen.chat.viewmodel.GroupInviteDriver.plaintextInvite
import co.electriccoin.zcash.ui.screen.chat.viewmodel.GroupInviteDriver.processInvite
import co.electriccoin.zcash.ui.screen.chat.viewmodel.HandshakeHarness.awaitIdle
import co.electriccoin.zcash.ui.screen.chat.viewmodel.HandshakeHarness.chatViewModelWith
import co.electriccoin.zcash.ui.screen.chat.viewmodel.HandshakeHarness.handleKexMemo
import co.electriccoin.zcash.ui.screen.chat.viewmodel.HandshakeHarness.handshakeViewModel
import co.electriccoin.zcash.ui.screen.chat.viewmodel.HandshakeHarness.kexMemoFrom
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Collections

/**
 * ZC-822 (owner decision W-52, answer 3) — a first key that claims a saved contact is answered and paid with no tap.
 *
 * A first key exchange is a chat request, waiting for Accept, only when the address has no key pair of ours, E2E was
 * never switched on for it AND it is not a saved contact. A key exchange's sender address is claimed, not proven, so
 * anyone can send one under a saved contact's address: its key is stored and one answer (about 17,000 zatoshi) is paid
 * from the automatic allowance under the standing consent, or the Allow question is raised without it.
 *
 * Wanted: a first key at a saved contact waits for Accept like any other first key. Nothing is paid, no allowance is
 * used and no question is raised before the tap; Accept pays exactly one answer. A request an earlier build already
 * stored without a hold keeps that build's rule, a key this wallet already answered is not asked about again, and a
 * plaintext group invitation from a saved contact still joins without Join.
 *
 * The harness is KexFromStrangerWaitsForAcceptTest's, with the contact saved in the address book. All addresses, keys
 * and txids are synthetic.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FirstKeyAtASavedContactWaitsForAcceptTest {
    private val sendScopes = SendHarnessScopes()

    @Before
    fun setUp() {
        sendScopes.open()
        Dispatchers.setMain(UnconfinedTestDispatcher())
        AutoSpendVm.resetProcessState()
        GroupInviteDriver.resetKeystoneExplanation()
    }

    @After
    fun tearDown() {
        sendScopes.close()
        Dispatchers.resetMain()
        AutoSpendVm.resetProcessState()
        GroupInviteDriver.resetKeystoneExplanation()
    }

    private suspend fun screen(
        prefs: HandshakePrefs,
        seam: HandshakeSeam,
        saved: Set<String>,
    ): ChatViewModel = handshakeViewModel(prefs, seam).also { AutoSpendVm.setField(it, "contactBook", SavedContacts(saved)) }

    private fun accept(
        vm: ChatViewModel,
        peer: String,
    ) = vm.acceptInboundKexRequest(peer)

    // ---- (a) nothing before the tap ---------------------------------------------------------------------------------

    @Test
    fun `a first key exchange that claims a saved contact waits for Accept and pays nothing`() =
        runBlocking {
            val contact = SyntheticUnifiedAddress.mainnet(seed = 82201)
            val prefs = HandshakePrefs(contact, firstContact = true)
            val seam = HandshakeSeam(contact)
            val vm = screen(prefs, seam, saved = setOf(contact))

            handleKexMemo(vm, kexMemoFrom(contact, E2EEncryption.generateKeyPair()), txId = "synthetic-zc822-kex-a")
            awaitIdle(vm)

            assertEquals("fixture sanity", emptyList<String>(), prefs.unexpectedCalls)
            assertTrue("the saved contact's first key must wait for Accept", vm.isInboundKexAwaitingAccept(contact))
            assertEquals("the automatic allowance was used before the tap", 0, prefs.budgetConsumed.get())
            assertEquals("an answer was proposed before the tap", 0, seam.proposalRequests.get())
        }

    // ---- (b) Accept ---------------------------------------------------------------------------------------------------

    @Test
    fun `Accept pays exactly one answer, not from the automatic allowance, and a second Accept sends nothing`() =
        runBlocking {
            val contact = SyntheticUnifiedAddress.mainnet(seed = 82202)
            val contactKeys = E2EEncryption.generateKeyPair()
            val prefs = HandshakePrefs(contact, firstContact = true)
            val seam = HandshakeSeam(contact)
            val vm = screen(prefs, seam, saved = setOf(contact))
            handleKexMemo(vm, kexMemoFrom(contact, contactKeys), txId = "synthetic-zc822-kex-b")
            awaitIdle(vm)

            accept(vm, contact)
            awaitIdle(vm)
            accept(vm, contact)
            awaitIdle(vm)
            vm.retryKexAckIfResponder(contact)
            awaitIdle(vm)

            assertEquals("fixture sanity", emptyList<String>(), prefs.unexpectedCalls)
            assertEquals("Accept must pay exactly one answer", 1, seam.proposalRequests.get())
            assertEquals(1, seam.broadcasts.size)
            assertEquals("the answer is for the key that waited", contactKeys.publicKey, prefs.sentKexAck[contact])
            assertEquals("the answer came from the automatic allowance", 0, prefs.budgetConsumed.get())
            assertFalse("the hold must be gone after Accept", vm.isInboundKexAwaitingAccept(contact))
        }

    // ---- (c) no question before the tap -----------------------------------------------------------------------------

    @Test
    fun `without the standing consent no Allow question is raised before Accept`() =
        runBlocking {
            val contact = SyntheticUnifiedAddress.mainnet(seed = 82203)
            val prefs = HandshakePrefs(contact, consent = false, firstContact = true)
            val seam = HandshakeSeam(contact)
            val vm = screen(prefs, seam, saved = setOf(contact))

            handleKexMemo(vm, kexMemoFrom(contact, E2EEncryption.generateKeyPair()), txId = "synthetic-zc822-kex-c")
            awaitIdle(vm)

            val state = HandshakeHarness.sendMessageState(vm)
            assertFalse(
                "the Allow question was raised for a saved contact's first key before Accept: $state",
                state is SendMessageState.NeedsHandshakeSpendConsent,
            )
            assertTrue("the saved contact's first key must wait for Accept", vm.isInboundKexAwaitingAccept(contact))
            assertEquals(0, seam.proposalRequests.get())
        }

    // ---- guards (green before and after) ----------------------------------------------------------------------------

    /** (e) In place, at the update: the hold is written only when a key exchange is first handled. */
    @Test
    fun `guard - a saved contact's request an earlier build stored with no hold is answered once by the retry at a chat open`() =
        runBlocking {
            val contact = SyntheticUnifiedAddress.mainnet(seed = 82205)
            val contactKey = E2EEncryption.generateKeyPair().publicKey
            // The earlier build handled the key exchange: our key and E2E for the contact, the contact's key, the
            // responder mark; its answer was never sent.
            val prefs = HandshakePrefs(contact)
            prefs.e2ePeerKey[contact] = contactKey
            prefs.receivedKex[contact] = contactKey
            val seam = HandshakeSeam(contact)
            val vm = screen(prefs, seam, saved = setOf(contact))

            vm.retryKexAckIfResponder(contact)
            awaitIdle(vm)
            vm.retryKexAckIfResponder(contact)
            awaitIdle(vm)

            assertFalse("no hold is written for a request that was stored before", vm.isInboundKexAwaitingAccept(contact))
            assertEquals("the retry at a chat open pays the one answer, as before", 1, seam.proposalRequests.get())
            assertEquals(contactKey, prefs.sentKexAck[contact])
        }

    /** (f) A restore: this wallet's own mined answer already answered that key. */
    @Test
    fun `guard - a saved contact's key exchange this wallet already answered sets no hold and pays nothing`() =
        runBlocking {
            val contact = SyntheticUnifiedAddress.mainnet(seed = 82206)
            val contactKeys = E2EEncryption.generateKeyPair()
            val prefs = HandshakePrefs(contact, firstContact = true)
            prefs.sentKexAck[contact] = contactKeys.publicKey
            val seam = HandshakeSeam(contact)
            val vm = screen(prefs, seam, saved = setOf(contact))

            handleKexMemo(vm, kexMemoFrom(contact, contactKeys), txId = "synthetic-zc822-kex-f")
            awaitIdle(vm)

            assertFalse("a key already answered is not asked about again", vm.isInboundKexAwaitingAccept(contact))
            assertEquals(0, seam.proposalRequests.get())
            assertEquals(0, prefs.budgetConsumed.get())
        }

    /**
     * (g) The group invitation no longer reads the address book to decide (isFirstContact is not edited): W-137 (ZC-922)
     * makes every invitation with a key wait for Join, a saved contact's too.
     * [test-change-ok: W-137 ZC-922 every invite waits for Join] (was: a saved contact's invitation joined without Join)
     */
    @Test
    fun `guard - a plaintext group invitation from a saved contact with no chat waits for Join and tries no reply`() =
        runBlocking {
            val contact = SyntheticUnifiedAddress.mainnet(seed = 82207)
            val prefs = StrangerInvitePrefs(consent = true)
            val seam = HandshakeSeam(contact)
            val vm =
                chatViewModelWith(prefs.proxy, seam).also { vm ->
                    AutoSpendVm.wireBootstrapFields(vm)
                    AutoSpendVm.setField(vm, "contactBook", SavedContacts(setOf(contact)))
                }

            processInvite(vm, GROUP, plaintextInvite(contact))
            awaitIdle(vm)

            assertTrue("the group must be saved", prefs.base.groupInfo.containsKey(GROUP))
            assertTrue("the invitation must wait for Join", prefs.needsJoin.containsKey(GROUP))
            assertEquals("no reply is tried before Join", 0, prefs.nostrLookups.get())
            assertEquals("nothing is paid without Join", 0, seam.submits.get())
        }

    /**
     * CONTROL, pins TODAY's behaviour on purpose (register ZC-923): a second key exchange with another key at an address
     * whose first request still waits is treated as a changed key. The refused offer is remembered and the key-changed
     * flag is raised with it (in the store they are one record: ZchatPreferences.rememberRefusedKeyOffer), which hides
     * Accept on the chat screen. The design wants the offer remembered WITHOUT the flag until Accept; that needs a
     * stored-format decision and is not built here. Whoever builds it must change this test knowingly.
     */
    @Test
    fun `control ZC-923 today - a second key at a waiting address is refused as a changed key and the hold keeps the first`() =
        runBlocking {
            val stranger = SyntheticUnifiedAddress.mainnet(seed = 82208)
            val first = E2EEncryption.generateKeyPair()
            val second = E2EEncryption.generateKeyPair()
            val prefs = HandshakePrefs(stranger, firstContact = true)
            val remembered: MutableList<String> = Collections.synchronizedList(mutableListOf())
            val flagWrites: MutableList<Boolean> = Collections.synchronizedList(mutableListOf())
            val verifiedWrites: MutableList<Boolean> = Collections.synchronizedList(mutableListOf())
            val store =
                ScanPassHarness.prefsWith(
                    prefs.proxy,
                    mapOf(
                        "getRefusedKeyOffer" to { _ -> null },
                        "rememberRefusedKeyOffer" to { a ->
                            remembered += a[1] as String
                            true
                        },
                        "setE2EKeyChanged" to { a ->
                            flagWrites += a[1] as Boolean
                            null
                        },
                        "setE2EVerified" to { a ->
                            verifiedWrites += a[1] as Boolean
                            null
                        },
                    ),
                )
            val seam = HandshakeSeam(stranger)
            val vm = chatViewModelWith(store, seam)

            handleKexMemo(vm, kexMemoFrom(stranger, first), txId = "synthetic-zc923-kex-1")
            awaitIdle(vm)
            assertTrue("fixture: the first key waits for Accept", vm.isInboundKexAwaitingAccept(stranger))
            handleKexMemo(vm, kexMemoFrom(stranger, second), txId = "synthetic-zc923-kex-2")
            awaitIdle(vm)

            assertEquals("fixture sanity", emptyList<String>(), prefs.unexpectedCalls)
            assertEquals("the hold must still name the first key", first.publicKey, prefs.awaitingAccept[stranger])
            assertEquals("the stored key must still be the first", first.publicKey, prefs.e2ePeerKey[stranger])
            assertEquals("today the second offer is remembered once", listOf(second.publicKey), remembered)
            assertEquals("today the key-changed flag is raised for it", listOf(true), flagWrites)
            assertEquals("today the verified flag is dropped for it", listOf(false), verifiedWrites)
            assertEquals("nothing is paid for either key before Accept", 0, seam.proposalRequests.get())
        }

    private companion object {
        const val GROUP = "zgrp_0a1b2c3d4e5f607182938220"
    }
}
