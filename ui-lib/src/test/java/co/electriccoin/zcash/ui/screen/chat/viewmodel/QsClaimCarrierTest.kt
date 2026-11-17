package co.electriccoin.zcash.ui.screen.chat.viewmodel

import co.electriccoin.zcash.ui.keystone.TwoAccountDataSource
import co.electriccoin.zcash.ui.nostr.NostrChatBridge
import co.electriccoin.zcash.ui.nostr.NostrInboxManager
import co.electriccoin.zcash.ui.screen.chat.crypto.E2EEncryption
import co.electriccoin.zcash.ui.screen.chat.crypto.E2EKeyPair
import co.electriccoin.zcash.ui.screen.chat.crypto.QsClaim
import co.electriccoin.zcash.ui.screen.chat.crypto.QuantumShield
import co.electriccoin.zcash.ui.screen.chat.datasource.ZchatPreferences
import co.electriccoin.zcash.ui.screen.chat.model.ChatMessage
import co.electriccoin.zcash.ui.screen.chat.model.ConversationMode
import co.electriccoin.zcash.ui.screen.chat.viewmodel.HandshakeHarness.awaitIdle
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.File
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.util.Collections
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * D3-2 (fix-all 2.13.0, train T5; t5-specs SPECS §2.2, §2.4 C1-C3): the carrier of the Quantum Shield signed claim.
 *
 * The claim (D3-1's `ZRCPT|zqs1|…` line) is the content of one relay DM sent with NostrChatBridge.publish; it is never
 * on chain and never a proposal. Wanted:
 * - C1 triggers: (1) initiateQuantumShield, (2) a successful new setup, (3) resetQuantumShield (state n) each publish
 *   exactly one claim; (4) a verified newer claim from the peer while our current claim was never acked is answered
 *   once, and a repeat of it sends nothing; (5) the refresh tick sends a PENDING/ACTIVE shield's claim until a relay
 *   acks it (0 acks: sent_seq unwritten, the next tick tries again), and the list and detail screens ticking together
 *   publish it once. Our seq is committed before the publish; sent_seq only after an ack.
 * - C2 guards: nothing is signed or published for a Shielded (VAULT) chat, an unset mode (counts as Shielded), an
 *   unknown relay key, a key-changed peer, a selected Keystone account, our own address, or without a relay publisher.
 * - C3 receive (the real NostrChatBridge.dispatch and observeNostrInbound): a verified claim is stored with its receive
 *   time; a stale seq, a reflected claim, a bad signature, another signer, an unattributed DM and a key-changed peer are
 *   not; a stored record signed by a key that is no longer the peer's never blocks a newer claim; the DM never becomes a
 *   row. The on-chain scan never reads a claim.
 *
 * The ViewModel is the handshake harness allocation (no constructor, no init), so `qsClaims` is set here by reflection
 * to the production PrefsQsClaimStore over this test's in-memory preferences; the new names (the field, the store
 * class, the tick, the store's preference methods) are reached by name so this file compiles before they exist. Every
 * address and key is synthetic, generated per run.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class QsClaimCarrierTest {
    private val sendScopes = SendHarnessScopes()
    private val published: MutableList<Published> = Collections.synchronizedList(mutableListOf())
    private val tag = "synthetic-d3-2-${UUID.randomUUID()}"

    @Volatile private var acks = 1

    @Volatile private var gate: CompletableDeferred<Unit>? = null

    /** The store the publish sees (to read our seq at the moment of the publish). */
    @Volatile private var storeAtPublish: Any? = null

    private class Published(
        val line: String,
        val recipient: String,
        val ourSeqAtPublish: Long?,
    )

    private companion object {
        const val PEER = "usyntheticd32carrierpeer0001"
        const val STORE_CLASS = "co.electriccoin.zcash.ui.screen.chat.datasource.PrefsQsClaimStore"
        val PEER_RELAY_KEY: String = RelayKeyBinding.relayKey(3_201)
    }

    @Before
    fun setUp() {
        sendScopes.open()
        Dispatchers.setMain(UnconfinedTestDispatcher())
        NostrChatBridge.unregisterChatNotifier()
        registerPublisher()
    }

    @After
    fun tearDown() {
        NostrChatBridge.unregisterPublisher()
        sendScopes.close()
        Dispatchers.resetMain()
    }

    private fun registerPublisher() {
        NostrChatBridge.unregisterPublisher()
        NostrChatBridge.registerPublisher { plaintext, recipient ->
            val seqNow = storeAtPublish?.let { ownSeq(it, PEER) }
            gate?.await()
            published += Published(plaintext, recipient, seqNow)
            NostrChatBridge.PublishResult(acks = acks, messageId = "synthetic-rumor-${published.size}")
        }
    }

    // ------------------------------------------------------------------------------------------------ C1 triggers

    @Test
    fun `C1 triggers 1 2 3 - start, a new setup and a turn-off each publish exactly one signed claim`() =
        runBlocking {
            val prefs = CarrierPrefs(PEER)
            val (vm, seam) = chatScreen(prefs)
            val store = installStore(vm, prefs)
            storeAtPublish = store

            vm.initiateQuantumShield(PEER)
            awaitIdle(vm)
            assertEquals(1, published.size, "trigger 1 (start) must publish exactly one claim")
            val started = claimOf(published[0], prefs)
            assertEquals(QsClaim.STATE_UNPADDED, started.state, "a new secret is unpadded")
            assertEquals(ourCommit(prefs), started.commit)
            assertEquals("", started.kc, "no key check before the shield is on")

            val peerSecret = QuantumShield.generateRandom()
            assertTrue(vm.completeQuantumShield(PEER, QuantumShield.toQRPayload(peerSecret)), "the genuine setup completes")
            awaitIdle(vm)
            assertEquals(2, published.size, "trigger 2 (a new setup) must publish exactly one claim")
            val active = claimOf(published[1], prefs)
            val psk = base64(checkNotNull(prefs.qs["psk:$PEER"] as String?))
            assertEquals(QuantumShield.keyCheck(psk, prefs.ours.publicKey, prefs.peerKeys.publicKey), active.kc)
            assertTrue(active.seq > started.seq, "each claim has a higher seq")

            vm.resetQuantumShield(PEER)
            awaitIdle(vm)
            assertEquals(3, published.size, "trigger 3 (turn off) must publish exactly one claim")
            val off = claimOf(published[2], prefs)
            assertEquals(QsClaim.STATE_NONE, off.state, "a turned-off shield says n")
            assertEquals("", off.commit)
            assertEquals("", off.kc)

            published.forEach {
                assertEquals(PEER_RELAY_KEY, it.recipient, "a claim goes to the peer's relay key")
                assertEquals(claimOf(it, prefs).seq, it.ourSeqAtPublish, "our seq is committed before the publish")
            }
            assertEquals(off.seq, sentSeq(store, PEER), "sent_seq is the last acked claim")
            assertEquals(0, seam.proposalRequests.get(), "a claim never builds a proposal")
        }

    @Test
    fun `C1 trigger 1 again on a live setup publishes nothing more once its claim was acked`() =
        runBlocking {
            val prefs = CarrierPrefs(PEER)
            val (vm, _) = chatScreen(prefs)
            installStore(vm, prefs)

            val qr = vm.initiateQuantumShield(PEER)
            awaitIdle(vm)
            assertEquals(1, published.size, "trigger 1 (start) must publish exactly one claim")
            assertEquals(qr, vm.initiateQuantumShield(PEER), "reopening shows the same QR")
            awaitIdle(vm)
            assertEquals(1, published.size, "the acked claim of an unchanged shield is not sent again")
        }

    @Test
    fun `C1 trigger 4 - a newer verified peer claim is answered once, and a repeat sends nothing`() =
        runBlocking {
            val prefs = CarrierPrefs(PEER)
            val (vm, _) = chatScreen(prefs)
            val store = installStore(vm, prefs)
            RelayKeyChatScreen.start(vm, "observeNostrInbound")

            val now = System.currentTimeMillis()
            val first = peerClaim(prefs, QsClaim.STATE_UNPADDED, now)
            deliver(prefs, first, "t4-1")
            awaitTrue("the peer's claim is stored") { peerRecordLine(store, PEER) == first }
            awaitTrue("the peer's claim is answered") { published.size == 1 }
            val answer = claimOf(published[0], prefs)
            assertEquals(QsClaim.STATE_NONE, answer.state, "no shield here: the answer says n")

            deliver(prefs, first, "t4-2")
            awaitHandled(prefs, "t4-2")
            assertEquals(1, published.size, "a repeat of the same claim sends nothing")

            val newer = peerClaim(prefs, QsClaim.STATE_UNPADDED, now + 1_000)
            deliver(prefs, newer, "t4-3")
            awaitTrue("the newer claim is stored") { peerRecordLine(store, PEER) == newer }
            awaitHandled(prefs, "t4-3")
            assertEquals(1, published.size, "our claim was acked: a newer peer claim is not answered (no ping-pong)")
        }

    @Test
    fun `C1 trigger 5 - the tick sends until a relay acks, then nothing`() =
        runBlocking {
            val prefs = CarrierPrefs(PEER)
            // A shield set up before the update: our secret, no claim ever signed.
            prefs.qs["secret:$PEER"] = QuantumShield.encodeSecret(QuantumShield.generateRandom())
            val (vm, _) = chatScreen(prefs)
            val store = installStore(vm, prefs)

            acks = 0
            tick(vm)
            awaitIdle(vm)
            assertEquals(1, published.size, "the tick sends the claim of a PENDING shield that was never sent")
            assertEquals(0L, sentSeq(store, PEER), "0 acks: sent_seq stays unwritten")
            tick(vm)
            awaitIdle(vm)
            assertEquals(2, published.size, "0 acks: the next tick tries again")

            acks = 1
            tick(vm)
            awaitIdle(vm)
            assertEquals(3, published.size, "the next tick sends again")
            assertEquals(claimOf(published[2], prefs).seq, sentSeq(store, PEER), "the ack writes sent_seq")
            tick(vm)
            awaitIdle(vm)
            assertEquals(3, published.size, "an acked claim is not sent again")
        }

    @Test
    fun `C1 trigger 5 - the list and detail screens ticking together publish once`() =
        runBlocking {
            val prefs = CarrierPrefs(PEER)
            prefs.qs["secret:$PEER"] = QuantumShield.encodeSecret(QuantumShield.generateRandom())
            val (list, _) = chatScreen(prefs)
            val (detail, _) = chatScreen(prefs)
            val store = installStore(list, prefs)
            installStore(detail, prefs)

            val held = CompletableDeferred<Unit>()
            gate = held
            tick(list)
            tick(detail)
            held.complete(Unit)
            awaitIdle(list, detail)
            gate = null
            assertEquals(1, published.size, "two screens ticking at once must publish the claim once")
            assertEquals(claimOf(published[0], prefs).seq, sentSeq(store, PEER))

            tick(list)
            tick(detail)
            awaitIdle(list, detail)
            assertEquals(1, published.size, "after the ack neither screen sends it again")
        }

    @Test
    fun `C1 trigger 5 - a tick with no shield writes nothing (FD-5)`() =
        runBlocking {
            val prefs = CarrierPrefs(PEER)
            val (vm, _) = chatScreen(prefs)
            installStore(vm, prefs)

            tick(vm)
            awaitIdle(vm)
            assertEquals(0, published.size, "no shield: the tick sends nothing")
            assertEquals(emptyList(), prefs.claimWrites, "no shield: the tick writes nothing")
        }

    // ------------------------------------------------------------------------------------------------- C2 guards

    @Test
    fun `C2 a Shielded chat sends no claim`() =
        runBlocking {
            guard(setUp = { it.relay.modes[PEER] = ConversationMode.VAULT }, undo = { it.relay.modes[PEER] = ConversationMode.TUNNEL })
        }

    @Test
    fun `C2 a chat with no stored mode counts as Shielded and sends no claim`() =
        runBlocking {
            guard(setUp = { it.relay.modes.remove(PEER) }, undo = { it.relay.modes[PEER] = ConversationMode.OPEN })
        }

    @Test
    fun `C2 no claim without the peer's relay key`() =
        runBlocking {
            guard(setUp = { it.relay.pubkeys.remove(PEER) }, undo = { it.relay.pubkeys[PEER] = PEER_RELAY_KEY })
        }

    @Test
    fun `C2 no claim to a key-changed peer`() =
        runBlocking {
            guard(setUp = { it.keyChanged += PEER }, undo = { it.keyChanged -= PEER })
        }

    @Test
    fun `C2 no claim to our own address`() =
        runBlocking {
            guard(setUp = { it.relay.selfAddresses += PEER }, undo = { it.relay.selfAddresses -= PEER })
        }

    @Test
    fun `C2 no claim without a relay publisher`() =
        runBlocking {
            guard(setUp = { NostrChatBridge.unregisterPublisher() }, undo = { registerPublisher() })
        }

    @Test
    fun `C2 no claim while a Keystone account is selected`() =
        runBlocking {
            val prefs = CarrierPrefs(PEER)
            val accounts = TwoAccountDataSource.keystoneSelected()
            val seam = HandshakeSeam(PEER)
            val vm = HandshakeHarness.chatViewModelWith(prefs.proxy, seam, accountSource = accounts)
            wire(vm)
            val installed = installStoreIfPresent(vm, prefs)

            vm.initiateQuantumShield(PEER)
            tickIfPresent(vm)
            awaitIdle(vm)
            assertNothingSigned(prefs, "a Keystone account is selected")
            assertEquals(0, seam.proposalRequests.get(), "a claim never builds a proposal")

            if (installed) {
                accounts.select(accounts.zashiAccount())
                vm.initiateQuantumShield(PEER)
                awaitIdle(vm)
                assertEquals(1, published.size, "control: back on the ZChat account the claim goes out")
            }
        }

    // ------------------------------------------------------------------------------------------------ C3 receive

    @Test
    fun `C3 a verified claim is stored with its receive time and never becomes a row`() =
        runBlocking {
            val prefs = CarrierPrefs(PEER)
            val (vm, _) = chatScreen(prefs)
            val store = installStore(vm, prefs)
            RelayKeyChatScreen.start(vm, "observeNostrInbound")

            val before = System.currentTimeMillis()
            val line = peerClaim(prefs, QsClaim.STATE_UNPADDED, before)
            deliver(prefs, line, "c3-ok")
            awaitTrue("the verified claim is stored") { peerRecordLine(store, PEER) == line }
            val after = System.currentTimeMillis()
            val record = checkNotNull(peerRecord(store, PEER))
            assertEquals(prefs.peerKeys.publicKey, getter(record, "getSignerPub"))
            assertEquals(prefs.ours.publicKey, getter(record, "getOtherPub"))
            val rxAt = getter(record, "getRxAt") as Long
            assertTrue(rxAt in before..after, "rxAt is our local receive time")
            awaitHandled(prefs, "c3-ok")
            assertNoRow(vm, prefs)
        }

    @Test
    fun `C3 a claim with a seq not above the stored one is not stored`() =
        runBlocking {
            val prefs = CarrierPrefs(PEER)
            val (vm, _) = chatScreen(prefs)
            val store = installStore(vm, prefs)
            RelayKeyChatScreen.start(vm, "observeNostrInbound")

            val now = System.currentTimeMillis()
            val stored = peerClaim(prefs, QsClaim.STATE_UNPADDED, now)
            deliver(prefs, stored, "stale-1")
            awaitTrue("the first claim is stored") { peerRecordLine(store, PEER) == stored }

            deliver(prefs, peerClaim(prefs, QsClaim.STATE_NONE, now - 1), "stale-2")
            deliver(prefs, peerClaim(prefs, QsClaim.STATE_NONE, now), "stale-3")
            awaitHandled(prefs, "stale-2")
            awaitHandled(prefs, "stale-3")
            assertEquals(stored, peerRecordLine(store, PEER), "an older or equal seq never replaces the stored claim")
        }

    @Test
    fun `C3 a reflected claim, a bad signature and another signer are not stored`() =
        runBlocking {
            val prefs = CarrierPrefs(PEER)
            val (vm, _) = chatScreen(prefs)
            val installed = installStoreIfPresent(vm, prefs)
            RelayKeyChatScreen.start(vm, "observeNostrInbound")
            val now = System.currentTimeMillis()

            // Our own claim sent back to us: signed by our key for the peer.
            val reflected =
                unsigned(QsClaim.STATE_UNPADDED, now)
                    .sign(prefs.ours.privateKey, prefs.ours.publicKey, prefs.peerKeys.publicKey)
                    .encode()
            // The peer's claim with the signature of another claim.
            val other =
                unsigned(QsClaim.STATE_UNPADDED, now + 5)
                    .sign(prefs.peerKeys.privateKey, prefs.peerKeys.publicKey, prefs.ours.publicKey)
            val badSig = unsigned(QsClaim.STATE_UNPADDED, now + 6).copy(sig = other.sig).encode()
            // A third key claiming to be the peer.
            val third = E2EEncryption.generateKeyPair()
            val otherSigner =
                unsigned(QsClaim.STATE_UNPADDED, now + 7)
                    .sign(third.privateKey, third.publicKey, prefs.ours.publicKey)
                    .encode()

            deliver(prefs, reflected, "neg-reflected")
            deliver(prefs, badSig, "neg-badsig")
            deliver(prefs, otherSigner, "neg-signer")
            listOf("neg-reflected", "neg-badsig", "neg-signer").forEach { awaitHandled(prefs, it) }
            assertNull(prefs.claimRaw["peer:$PEER"], "an unverified claim must not be stored")
            assertNoRow(vm, prefs)

            if (installed) controlStores(prefs, now + 10)
        }

    @Test
    fun `C3 an unattributed claim is not stored`() =
        runBlocking {
            val prefs = CarrierPrefs(PEER)
            val (vm, _) = chatScreen(prefs)
            val installed = installStoreIfPresent(vm, prefs)
            RelayKeyChatScreen.start(vm, "observeNostrInbound")
            val now = System.currentTimeMillis()

            val line = peerClaim(prefs, QsClaim.STATE_UNPADDED, now)
            // The bridge hands a DM straight to a reader only while it holds none from earlier tests.
            awaitTrue("the bridge holds no earlier DM") { !bridgeHolds() }
            emitUnattributed(line, "$tag-unattributed")
            // The reader handles DMs in order: once a later DM is marked seen, the unattributed one was handled.
            deliver(prefs, "${QsClaim.PREFIX.substringBefore("zqs1")}synthetic-plain-receipt", "after-unattributed")
            awaitHandled(prefs, "after-unattributed")
            assertTrue(prefs.claimRaw.keys.none { it.startsWith("peer:") }, "an unattributed claim must not be stored")

            if (installed) controlStores(prefs, now + 10)
        }

    @Test
    fun `C3 a claim from a key-changed peer is not stored`() =
        runBlocking {
            val prefs = CarrierPrefs(PEER)
            val (vm, _) = chatScreen(prefs)
            val installed = installStoreIfPresent(vm, prefs)
            RelayKeyChatScreen.start(vm, "observeNostrInbound")
            val now = System.currentTimeMillis()

            prefs.keyChanged += PEER
            deliver(prefs, peerClaim(prefs, QsClaim.STATE_UNPADDED, now), "neg-keychanged")
            awaitHandled(prefs, "neg-keychanged")
            assertNull(prefs.claimRaw["peer:$PEER"], "a key-changed peer's claim must not be stored")

            prefs.keyChanged -= PEER
            if (installed) controlStores(prefs, now + 10)
        }

    @Test
    fun `C3 a stored claim signed by a key that is no longer the peer's never blocks a newer claim`() =
        runBlocking {
            val prefs = CarrierPrefs(PEER)
            val (vm, _) = chatScreen(prefs)
            val store = installStore(vm, prefs)
            RelayKeyChatScreen.start(vm, "observeNostrInbound")
            val now = System.currentTimeMillis()

            val old = peerClaim(prefs, QsClaim.STATE_UNPADDED, now + 3_600_000)
            deliver(prefs, old, "resign-1")
            awaitTrue("the claim of the old key is stored") { peerRecordLine(store, PEER) == old }

            // The peer reset its chat key; its clock is behind the old claim's seq.
            prefs.peerKeys = E2EEncryption.generateKeyPair()
            prefs.relay.e2ePeerKeys[PEER] = prefs.peerKeys.publicKey
            val fresh = peerClaim(prefs, QsClaim.STATE_NONE, now)
            deliver(prefs, fresh, "resign-2")
            awaitTrue("the new key's claim replaces the stale record") { peerRecordLine(store, PEER) == fresh }
            assertEquals(prefs.peerKeys.publicKey, getter(checkNotNull(peerRecord(store, PEER)), "getSignerPub"))
        }

    @Test
    fun `C3 the on-chain scan never reads a claim`() {
        val src = File(repoRoot(), "ui-lib/src/main/java/co/electriccoin/zcash/ui/screen/chat/viewmodel/ChatViewModel.kt").readText()
        val start = src.indexOf("if (ZMSGProtocol.isReadReceipt(memoText)) {")
        assertTrue(start >= 0, "the on-chain scan's receipt skip is where it was")
        val branch = src.substring(start, src.indexOf("continue", start) + "continue".length)
        assertFalse("QsClaim" in branch || "Claim(" in branch, "the on-chain receipt branch must only skip: $branch")
        val calls = Regex("(?<!fun )recordQsPeerClaim\\(").findAll(src).map { it.range.first }.toList()
        val inbound = src.indexOf("private fun observeNostrInbound()")
        val inboundEnd =
            Regex("\n    (private |internal |override |suspend )*fun ").find(src, inbound + 1)?.range?.first ?: src.length
        calls.forEach { at ->
            assertTrue(at in inbound..inboundEnd, "the claim reader is called only from the relay inbound handler")
        }
    }

    // -------------------------------------------------------------------------------------------------- helpers

    /** C2: [setUp] blocks every trigger; after the fix, [undo] shows the same trigger does send (not vacuous). */
    private suspend fun guard(
        setUp: (CarrierPrefs) -> Unit,
        undo: (CarrierPrefs) -> Unit,
    ) {
        val prefs = CarrierPrefs(PEER)
        val (vm, seam) = chatScreen(prefs)
        val installed = installStoreIfPresent(vm, prefs)
        setUp(prefs)

        vm.initiateQuantumShield(PEER)
        assertTrue(vm.completeQuantumShield(PEER, QuantumShield.toQRPayload(QuantumShield.generateRandom())))
        tickIfPresent(vm)
        vm.resetQuantumShield(PEER)
        awaitIdle(vm)
        assertNothingSigned(prefs, "guarded")
        assertEquals(0, seam.proposalRequests.get(), "a claim never builds a proposal")

        if (installed) {
            undo(prefs)
            vm.initiateQuantumShield(PEER)
            awaitIdle(vm)
            assertEquals(1, published.size, "control: without the guard the claim goes out")
        }
    }

    private fun assertNothingSigned(
        prefs: CarrierPrefs,
        why: String,
    ) {
        assertEquals(0, published.size, "$why: no claim may be published")
        assertTrue(prefs.claimRaw.keys.none { it.startsWith("our:") }, "$why: no claim may be signed (no seq written)")
    }

    /** C3 control after a negative case: a verified claim from the peer is stored (the handler was live). */
    private suspend fun controlStores(
        prefs: CarrierPrefs,
        seq: Long,
    ) {
        val line = peerClaim(prefs, QsClaim.STATE_UNPADDED, seq)
        deliver(prefs, line, "control-$seq")
        awaitTrue("control: a verified claim is stored") { prefs.claimRaw["peer:$PEER"]?.toString()?.contains(line) == true }
    }

    private suspend fun chatScreen(prefs: CarrierPrefs): Pair<ChatViewModel, HandshakeSeam> {
        val seam = HandshakeSeam(PEER)
        val vm = HandshakeHarness.chatViewModelWith(prefs.proxy, seam)
        wire(vm)
        return vm to seam
    }

    private fun wire(vm: ChatViewModel) {
        AutoSpendVm.setField(vm, "pendingMessages", MutableStateFlow<List<ChatMessage>>(emptyList()))
        AutoSpendVm.setField(vm, "replyRefSentinel", '\u0001')
        AutoSpendVm.setField(vm, "replyRefPrefix", "\u0001RPL:")
        AutoSpendVm.setField(vm, "replyPreviewSep", '\u001f')
        AutoSpendVm.setField(vm, "messageProcessors", ConcurrentHashMap<String, Any>())
        AutoSpendVm.setField(vm, "_quantumShieldSas", MutableStateFlow<Any?>(null))
    }

    /** The production store over [prefs] in the ViewModel's `qsClaims` field, or false when this build has none. */
    private fun installStoreIfPresent(
        vm: ChatViewModel,
        prefs: CarrierPrefs,
    ): Boolean {
        val field = ChatViewModel::class.java.declaredFields.firstOrNull { it.name == "qsClaims" } ?: return false
        val store = runCatching { Class.forName(STORE_CLASS) }.getOrNull() ?: return false
        field.isAccessible = true
        field.set(vm, store.getConstructor(ZchatPreferences::class.java).newInstance(prefs.proxy))
        return true
    }

    private fun installStore(
        vm: ChatViewModel,
        prefs: CarrierPrefs,
    ): Any {
        installStoreIfPresent(vm, prefs)
        val field = ChatViewModel::class.java.declaredFields.firstOrNull { it.name == "qsClaims" }
        return field?.apply { isAccessible = true }?.get(vm) ?: NoStore
    }

    /** Stands in for the store at a build without one: every read answers "nothing". */
    private object NoStore

    private fun tick(vm: ChatViewModel) {
        val method = ChatViewModel::class.java.declaredMethods.firstOrNull { it.name == "retryQsClaims" && it.parameterCount == 0 }
        assertNotNull(method, "the refresh tick's claim retry (retryQsClaims) is missing")
        call(method, vm)
    }

    private fun tickIfPresent(vm: ChatViewModel) {
        ChatViewModel::class.java.declaredMethods
            .firstOrNull { it.name == "retryQsClaims" && it.parameterCount == 0 }
            ?.let { call(it, vm) }
    }

    private fun call(
        method: Method,
        target: Any,
        vararg args: Any?,
    ): Any? =
        try {
            method.isAccessible = true
            method.invoke(target, *args)
        } catch (e: InvocationTargetException) {
            throw e.targetException
        }

    private fun storeCall(
        store: Any,
        name: String,
        peer: String,
    ): Any? {
        if (store === NoStore) return null
        val method = store.javaClass.methods.first { it.name == name && it.parameterCount == 1 }
        return call(method, store, peer)
    }

    private fun sentSeq(
        store: Any,
        peer: String,
    ): Long = storeCall(store, "sentSeq", peer) as Long? ?: 0L

    private fun ownSeq(
        store: Any,
        peer: String,
    ): Long? = storeCall(store, "ownClaim", peer)?.let { getter(it, "getSeq") as Long }

    private fun peerRecord(
        store: Any,
        peer: String,
    ): Any? = storeCall(store, "peerClaim", peer)

    private fun peerRecordLine(
        store: Any,
        peer: String,
    ): String? = peerRecord(store, peer)?.let { getter(it, "getLine") as String }

    private fun getter(
        target: Any,
        name: String,
    ): Any? = call(target.javaClass.getMethod(name), target)

    private fun unsigned(
        state: String,
        seq: Long,
    ): QsClaim {
        val commit = if (state == QsClaim.STATE_NONE) "" else QuantumShield.commitment(ByteArray(32) { (it + seq).toByte() })
        return QsClaim(state = state, commit = commit, kc = "", seq = seq, sig = "")
    }

    /** A claim from the peer: signed with the peer's E2E key, addressed to our E2E key. */
    private fun peerClaim(
        prefs: CarrierPrefs,
        state: String,
        seq: Long,
    ): String = unsigned(state, seq).sign(prefs.peerKeys.privateKey, prefs.peerKeys.publicKey, prefs.ours.publicKey).encode()

    /** A published line, verified as ours: signed with our E2E key for the peer's. */
    private fun claimOf(
        published: Published,
        prefs: CarrierPrefs,
    ): QsClaim {
        assertTrue(published.line.startsWith(QsClaim.PREFIX), "a claim is a ZRCPT|zqs1| line")
        val claim = assertNotNull(QsClaim.parse(published.line), "a published claim parses strictly")
        assertTrue(claim.verify(prefs.ours.publicKey, prefs.peerKeys.publicKey), "a published claim is signed by our chat key")
        return claim
    }

    private fun base64(text: String): ByteArray =
        java.util.Base64
            .getDecoder()
            .decode(text)

    private fun ourCommit(prefs: CarrierPrefs): String {
        val secret = base64(checkNotNull(prefs.qs["secret:$PEER"] as String?))
        return QuantumShield.commitment(secret)
    }

    private fun deliver(
        prefs: CarrierPrefs,
        line: String,
        id: String,
    ) {
        NostrChatBridge.dispatch(
            NostrInboxManager.InboundDm(
                senderPubkeyHex = PEER_RELAY_KEY,
                content = line,
                createdAtSec = System.currentTimeMillis() / 1000,
                eventId = "$tag-$id",
                rumorId = "rumor-$tag-$id",
            ),
            prefs.proxy,
        )
    }

    private fun bridgeHolds(): Boolean = call(NostrChatBridge::class.java.getDeclaredMethod("hasHeld"), NostrChatBridge) as Boolean

    /** An unattributed DM, as dispatch hands one to the reader (only ZBOOTs are, today): peerAddress "". */
    private fun emitUnattributed(
        line: String,
        eventId: String,
    ) {
        val chat =
            NostrChatBridge.InboundChat(
                peerAddress = NostrChatBridge.UNATTRIBUTED_BOOT,
                plaintext = line,
                createdAtSec = System.currentTimeMillis() / 1000,
                eventId = eventId,
            )
        val emit = NostrChatBridge::class.java.getDeclaredMethod("emitNow", NostrChatBridge.InboundChat::class.java)
        assertTrue(call(emit, NostrChatBridge, chat) as Boolean, "the unattributed DM reached a reader")
    }

    /** The reader has taken [id] (it marks an attributed event seen first), then give its launches a moment. */
    private suspend fun awaitHandled(
        prefs: CarrierPrefs,
        id: String,
    ) {
        awaitTrue("the DM $id reached the handler") { "$tag-$id" in prefs.relay.seen }
        delay(50)
    }

    private suspend fun awaitTrue(
        what: String,
        condition: () -> Boolean,
    ) {
        val met = withTimeoutOrNull(5_000) { while (!condition()) delay(10) } != null
        assertTrue(met || condition(), "timed out: $what")
    }

    private fun assertNoRow(
        vm: ChatViewModel,
        prefs: CarrierPrefs,
    ) {
        assertTrue(prefs.relay.rows.isEmpty(), "a claim never becomes a stored row")
        assertTrue(RelayKeyChatScreen.rows(vm).isEmpty(), "a claim never becomes a chat row")
    }

    private fun repoRoot(): File {
        var dir: File? = File("").absoluteFile
        while (dir != null && !File(dir, "settings.gradle.kts").exists()) dir = dir.parentFile
        return checkNotNull(dir) { "repo root not found" }
    }
}

/**
 * The in-memory store of the carrier tests: a [RelayKeyStore] (relay keys, modes, E2E public keys, seen events, rows)
 * plus our E2E private key, the key-changed flags, the Quantum Shield values and the claim store's preference methods
 * (answered by name, so this compiles before they exist). [claimWrites] lists every claim write.
 */
internal class CarrierPrefs(
    peer: String,
) {
    val relay = RelayKeyStore()
    val ours: E2EKeyPair = E2EEncryption.generateKeyPair()

    @Volatile var peerKeys: E2EKeyPair = E2EEncryption.generateKeyPair()
    val keyChanged: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /** "psk:<peer>", "secret:<peer>", "bound:<peer>". */
    val qs = ConcurrentHashMap<String, Any>()

    /** "peer:<peer>", "our:<peer>", "sent:<peer>", "setup:<peer>" → the raw stored value. */
    val claimRaw = ConcurrentHashMap<String, Any>()
    val claimWrites: MutableList<String> = Collections.synchronizedList(mutableListOf())

    init {
        relay.pubkeys[peer] = RelayKeyBinding.relayKey(3_201)
        relay.modes[peer] = ConversationMode.TUNNEL
        relay.e2eOurKeys[peer] = ours.publicKey
        relay.e2ePeerKeys[peer] = peerKeys.publicKey
    }

    private fun claimKey(name: String): String? =
        when (name.removePrefix("get").removePrefix("set")) {
            "QsClaimPeer" -> "peer"
            "QsClaimOurSeq" -> "our"
            "QsClaimSentSeq" -> "sent"
            "QsClaimSetup" -> "setup"
            else -> null
        }

    val proxy: ZchatPreferences =
        Proxy.newProxyInstance(
            ZchatPreferences::class.java.classLoader,
            arrayOf(ZchatPreferences::class.java),
        ) { p, method, args ->
            fun str(i: Int) = args!![i] as String
            val claim = claimKey(method.name)
            when {
                method.name == "toString" -> "CarrierPrefs"
                method.name == "hashCode" -> System.identityHashCode(p)
                method.name == "equals" -> p === args?.get(0)
                claim != null && method.name.startsWith("get") ->
                    claimRaw["$claim:${str(0)}"] ?: if (method.returnType == java.lang.Long.TYPE) 0L else null
                claim != null && method.name.startsWith("set") -> {
                    claimWrites += "$claim:${str(0)}"
                    claimRaw["$claim:${str(0)}"] = args!![1]
                    true
                }
                method.name == "getRelayModePeers" ->
                    synchronized(relay.modes) {
                        relay.modes
                            .filterValues { it == ConversationMode.TUNNEL || it == ConversationMode.OPEN }
                            .keys
                            .toList()
                    }
                method.name == "getE2EPrivateKey" -> if (relay.e2eOurKeys[str(0)] == ours.publicKey) ours.privateKey else null
                method.name == "isE2EKeyChanged" -> str(0) in keyChanged
                method.name == "getQuantumShieldPSK" -> qs["psk:${str(0)}"]
                method.name == "setQuantumShieldPSK" -> qs.put("psk:${str(0)}", str(1)).let { Unit }
                method.name == "getQuantumShieldOurSecret" -> qs["secret:${str(0)}"]
                method.name == "setQuantumShieldOurSecret" -> qs.put("secret:${str(0)}", str(1)).let { Unit }
                method.name == "getQuantumShieldBound" -> qs["bound:${str(0)}"]
                method.name == "setQuantumShieldBound" -> qs.put("bound:${str(0)}", args!![1] as Boolean).let { Unit }
                method.name == "clearQuantumShieldPSK" -> {
                    qs.remove("psk:${str(0)}")
                    qs.remove("bound:${str(0)}")
                    claimRaw.remove("setup:${str(0)}")
                    Unit
                }
                else ->
                    try {
                        method.invoke(relay.proxy, *(args ?: emptyArray()))
                    } catch (e: InvocationTargetException) {
                        throw e.targetException
                    }
            }
        } as ZchatPreferences
}
