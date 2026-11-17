package co.electriccoin.zcash.ui.screen.chat.viewmodel

import co.electriccoin.zcash.ui.screen.chat.crypto.E2EEncryption
import co.electriccoin.zcash.ui.screen.chat.viewmodel.HandshakeHarness.awaitIdle
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * R2-2 (a), plan step p2-e4bf2a03c56b — a stranger's KEX makes us pay.
 *
 * Anyone who knows our (public) address can put a signed KEX on chain. At b1f45ab32 handleKEXMessage answers
 * it at once with a paid KEXACK (a carrier output, the platform fee and the network fee, about 17,000
 * zatoshi) once the one-time handshake consent has been given, and uses one unit of the wallet-wide KEXACK
 * budget. Nothing asks the user whether they want a chat with this address. A stranger with many addresses
 * drains the wallet up to the budget every window; a KEX that names OUR own address, or an address that is
 * not a valid mainnet Unified Address, is answered the same way.
 *
 * Wanted: a first-contact KEX from an address we have no chat with pays nothing and uses no budget until the
 * user accepts the chat; Accept pays exactly one KEXACK. A KEX naming our own address or an invalid or
 * non-mainnet address sends nothing and uses no budget. An existing contact's KEX is answered as before.
 *
 * The new ViewModel entry point is called by name so this file compiles before it exists.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class KexFromStrangerWaitsForAcceptTest {
    private val sendScopes = SendHarnessScopes()

    @Before
    fun setUp() {
        sendScopes.open()
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        sendScopes.close()
        Dispatchers.resetMain()
    }

    private fun accept(
        vm: ChatViewModel,
        peer: String
    ) {
        ChatViewModel::class.java
            .getMethod("acceptInboundKexRequest", String::class.java)
            .invoke(vm, peer)
    }

    private fun awaitingAccept(
        vm: ChatViewModel,
        peer: String
    ): Boolean =
        ChatViewModel::class.java
            .getMethod("isInboundKexAwaitingAccept", String::class.java)
            .invoke(vm, peer) as Boolean

    @Test
    fun `a first-contact KEX from a stranger pays no KEXACK and uses no budget`() =
        runBlocking {
            val stranger = SyntheticUnifiedAddress.mainnet(seed = 1)
            val prefs = HandshakePrefs(stranger, firstContact = true)
            val seam = HandshakeSeam(stranger)
            val vm = handshakeViewModel(prefs, seam)

            handleKexMemo(vm, kexMemoFrom(stranger, E2EEncryption.generateKeyPair()), txId = "synthetic-stranger-kex-1")
            awaitIdle(vm)

            assertEquals("fixture sanity", emptyList<String>(), prefs.unexpectedCalls)
            assertTrue("fixture: the stranger's key must have been read and stored", prefs.e2ePeerKey.containsKey(stranger))
            assertEquals(
                "a KEX from an address we have no chat with was answered with a paid KEXACK before the user " +
                    "accepted the chat",
                0,
                seam.proposalRequests.get(),
            )
            assertEquals("no KEXACK budget may be used before the user accepts", 0, prefs.budgetConsumed.get())
            assertTrue("the chat must be offered for Accept", awaitingAccept(vm, stranger))
        }

    @Test
    fun `accepting the stranger's chat pays exactly one KEXACK, once`() =
        runBlocking {
            val stranger = SyntheticUnifiedAddress.mainnet(seed = 2)
            val strangerKeys = E2EEncryption.generateKeyPair()
            val prefs = HandshakePrefs(stranger, firstContact = true)
            val seam = HandshakeSeam(stranger)
            val vm = handshakeViewModel(prefs, seam)

            handleKexMemo(vm, kexMemoFrom(stranger, strangerKeys), txId = "synthetic-stranger-kex-2")
            awaitIdle(vm)
            // Opening the chat retries a responder's KEXACK; before Accept it must pay nothing either.
            vm.retryKexAckIfResponder(stranger)
            awaitIdle(vm)
            assertEquals("nothing may be paid before Accept", 0, seam.proposalRequests.get())

            accept(vm, stranger)
            awaitIdle(vm)
            accept(vm, stranger)
            awaitIdle(vm)
            vm.retryKexAckIfResponder(stranger)
            // The stranger re-sends the same key under a new txid.
            handleKexMemo(vm, kexMemoFrom(stranger, strangerKeys), txId = "synthetic-stranger-kex-2b")
            awaitIdle(vm)

            assertEquals("fixture sanity", emptyList<String>(), prefs.unexpectedCalls)
            assertEquals("Accept must pay exactly one KEXACK", 1, seam.proposalRequests.get())
            assertEquals(1, seam.broadcasts.size)
            assertEquals(strangerKeys.publicKey, prefs.sentKexAck[stranger])
            assertEquals(false, awaitingAccept(vm, stranger))
        }

    @Test
    fun `a chat-open KEX of ours to a stranger waiting for Accept pays nothing`() =
        runBlocking {
            val stranger = SyntheticUnifiedAddress.mainnet(seed = 3)
            val prefs = HandshakePrefs(stranger, firstContact = true)
            val seam = HandshakeSeam(stranger)
            val vm = handshakeViewModel(prefs, seam)

            handleKexMemo(vm, kexMemoFrom(stranger, E2EEncryption.generateKeyPair()), txId = "synthetic-stranger-kex-3")
            awaitIdle(vm)
            vm.sendKEXMessage(stranger, HandshakeHarness.SELF)
            awaitIdle(vm)

            assertEquals(
                "an automatic handshake to a stranger whose chat was not accepted was paid",
                0,
                seam.proposalRequests.get(),
            )
        }

    @Test
    fun `a KEX that names our own address sends nothing and uses no budget`() =
        runBlocking {
            val ours = SyntheticUnifiedAddress.mainnet(seed = 4)
            val prefs = HandshakePrefs(ours, firstContact = true).apply { selfAddresses += ours }
            val seam = HandshakeSeam(ours)
            val vm = handshakeViewModel(prefs, seam)

            handleKexMemo(vm, kexMemoFrom(ours, E2EEncryption.generateKeyPair()), txId = "synthetic-self-kex")
            awaitIdle(vm)

            assertEquals("a KEX claiming our own address was answered", 0, seam.proposalRequests.get())
            assertEquals(0, prefs.budgetConsumed.get())
            assertEquals("no key may be stored for our own address", null, prefs.e2ePeerKey[ours])
        }

    @Test
    fun `a KEX from an address with a broken checksum sends nothing and uses no budget`() =
        runBlocking {
            val invalid = SyntheticUnifiedAddress.mainnetBadChecksum(seed = 5)
            val prefs = HandshakePrefs(invalid, firstContact = true)
            val seam = HandshakeSeam(invalid)
            val vm = handshakeViewModel(prefs, seam)

            handleKexMemo(vm, kexMemoFrom(invalid, E2EEncryption.generateKeyPair()), txId = "synthetic-invalid-kex")
            awaitIdle(vm)

            assertEquals("a KEX from an invalid address was answered", 0, seam.proposalRequests.get())
            assertEquals(0, prefs.budgetConsumed.get())
            assertEquals("no key may be stored for an invalid address", null, prefs.e2ePeerKey[invalid])
        }

    /** Pin (passes before and after): a test-network address is never read as a sender at all. */
    @Test
    fun `a KEX from a test-network address sends nothing and uses no budget`() =
        runBlocking {
            val testnet = SyntheticUnifiedAddress.testnet(seed = 6)
            val prefs = HandshakePrefs(testnet, firstContact = true)
            val seam = HandshakeSeam(testnet)
            val vm = handshakeViewModel(prefs, seam)

            handleKexMemo(vm, kexMemoFrom(testnet, E2EEncryption.generateKeyPair()), txId = "synthetic-testnet-kex")
            awaitIdle(vm)

            assertEquals(0, seam.proposalRequests.get())
            assertEquals(0, prefs.budgetConsumed.get())
        }

    /** Control (passes before and after): an existing contact's KEX is still answered at once. */
    @Test
    fun `control - an existing contact's KEX is answered with one KEXACK`() =
        runBlocking {
            val contact = SyntheticUnifiedAddress.mainnet(seed = 7)
            val contactKeys = E2EEncryption.generateKeyPair()
            val prefs = HandshakePrefs(contact)
            // W-62 (ZC-599): a key exchange at a chat that still waits for the contact's first key (our key pair, no contact key)
            // now waits for Accept. This case is about the automatic answer at an existing chat, so the contact's key is already held (the key exchange repeats it).
            prefs.e2ePeerKey[contact] = contactKeys.publicKey
            val seam = HandshakeSeam(contact)
            val vm = handshakeViewModel(prefs, seam)

            handleKexMemo(vm, kexMemoFrom(contact, contactKeys), txId = "synthetic-contact-kex")
            awaitIdle(vm)

            assertEquals("fixture sanity", emptyList<String>(), prefs.unexpectedCalls)
            assertEquals(1, seam.proposalRequests.get())
            assertEquals(1, prefs.budgetConsumed.get())
            assertEquals(contactKeys.publicKey, prefs.sentKexAck[contact])
        }
}
