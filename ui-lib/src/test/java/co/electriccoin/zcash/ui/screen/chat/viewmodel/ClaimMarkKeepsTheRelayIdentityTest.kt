package co.electriccoin.zcash.ui.screen.chat.viewmodel

import co.electriccoin.zcash.ui.nostr.NostrChatBridge
import co.electriccoin.zcash.ui.screen.chat.crypto.E2EEncryption
import co.electriccoin.zcash.ui.screen.chat.crypto.E2EKeyPair
import co.electriccoin.zcash.ui.screen.chat.model.ConversationMode
import co.electriccoin.zcash.ui.screen.chat.model.SendMessageState
import co.electriccoin.zcash.ui.screen.chat.model.SysNotes
import co.electriccoin.zcash.ui.screen.chat.model.ZMSGProtocol
import co.electriccoin.zcash.ui.screen.chat.view.ChatResourceText
import co.electriccoin.zcash.ui.screen.chat.viewmodel.ClaimMarkChat.Companion.kexFrom
import co.electriccoin.zcash.ui.screen.chat.viewmodel.ClaimMarkChat.Companion.kexWithRelayFields
import co.electriccoin.zcash.ui.screen.chat.viewmodel.ClaimMarkChat.Companion.nostr
import co.electriccoin.zcash.ui.screen.chat.viewmodel.ClaimMarkChat.Companion.relayKey
import co.electriccoin.zcash.ui.screen.chat.viewmodel.ClaimMarkChat.Companion.v3Boot
import co.electriccoin.zcash.ui.screen.chat.viewmodel.ClaimMarkChat.Companion.v4Boot
import co.electriccoin.zcash.ui.screen.chat.viewmodel.HandshakeHarness.awaitIdle
import co.electriccoin.zcash.ui.screen.chat.viewmodel.HandshakeHarness.handleKexMemo
import co.electriccoin.zcash.ui.screen.chat.viewmodel.HandshakePassFixture.Companion.NEW
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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.lang.reflect.InvocationTargetException

/**
 * ZC-634 part B (FIRST-CONTACT-SPEC revision 4a, 5.5 items 2 to 4 and 6 to 9, 6.4; attack A5 of 5.4.2): a contact key
 * stored with no hold is only a claim, and a claim never binds or replaces a relay identity (FC-5); while it stands
 * nothing is paid to get past it and the screen names the one thing that works (FC-5b).
 *
 * Today no claim mark exists: a stranger's key exchange at an address where this phone switched E2E on but has no key
 * pair yet (setE2EEnabled writes the flag before the key pair; a Reset's wipe leaves the same state), or at an Open
 * contact, is stored with no hold, and a ZBOOT signed with it binds, or replaces, that address's relay identity; its
 * relay fields go to the binding; Reconnect pays an on-chain ZBOOT or key exchange; "Mark as verified" only sets a flag.
 *
 * Wanted (design 5.5): the key is stored with a mark in one commit of the key store (cases a to c of item 2; not for a
 * settled answer of this wallet, item 2a); while the mark stands a v2/v3 ZBOOT that would bind where nothing is bound,
 * or announce another relay key than the bound one, is refused unrecorded and kept as held, unless the bound relay key
 * sealed it (which releases the mark); v4 is followed by the bound relay key; the relay fields of a key exchange, and
 * of a leg 2 answer, are ignored; the proof (a leg 1 answer carrying the stored key) and "Mark as verified" release the
 * mark and hand the held ZBOOT on with a reply that goes over the relays only; Reconnect sends nothing paid and says
 * why. These are design 6.4's ClaimedKeyNeverMovesARelayIdentityTest cases d, f to z, ab to ah (and the guards of
 * HonestRelayIdentityStillFollowsTest b, d, i), kept apart from part A's file. With the owner's W-62 hold (a key
 * exchange at a chat this phone began waits for Accept), replay (c) is a hold, not a mark, and since the review of
 * 19 Oct (T7aD/T7a finding 1) also where E2E is on and our key pair is not made yet: (m) pins that hold and that
 * nothing is paid; a mark stands beside a hold where a relay key is bound (case a). Every address, key and id is
 * synthetic.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ClaimMarkKeepsTheRelayIdentityTest {
    private val sendScopes = SendHarnessScopes()
    private val scheduler = TestCoroutineScheduler()

    @Before
    fun setUp() {
        sendScopes.open()
        Dispatchers.setMain(UnconfinedTestDispatcher(scheduler))
        AutoSpendVm.resetProcessState()
        AutoSpendVm.setHistoryRead(true)
        NostrChatBridge.unregisterPublisher()
        ClaimMarkChat.clearTunnelQueue()
    }

    @After
    fun tearDown() {
        NostrChatBridge.unregisterPublisher()
        ClaimMarkChat.clearTunnelQueue()
        sendScopes.close()
        Dispatchers.resetMain()
        AutoSpendVm.resetProcessState()
    }

    // ---- the mark is written with the key (item 2) ------------------------------------------------------------------------

    @Test
    fun `(d) an Open contact this phone switched E2E on for keeps its relay identity against a claimed key's ZBOOT`() =
        runBlocking {
            val chat = gapChat(seed = 63410, mode = ConversationMode.OPEN, bound = relayKey(41))
            val vm = chat.viewModel()
            val stranger = E2EEncryption.generateKeyPair()

            handleKexMemo(vm, kexFrom(chat.peer, stranger, "ZC634D01"), "synthetic-zc634-d-kex")
            awaitIdle(vm)
            assertEquals("the claimed key is stored", stranger.publicKey, chat.prefs.e2ePeerKey[chat.peer])
            assertEquals("no claim mark was written with the claimed key (5.5 item 2 a)", stranger.publicKey, chat.maps.claims[chat.peer])
            assertEquals("no hold: a chat this phone is setting up (W-62)", stranger.publicKey, chat.prefs.awaitingAccept[chat.peer])
            val paidBefore = chat.proposals()
            assertEquals("an answer was paid before Accept", 0, paidBefore)

            val announced = relayKey(42)
            val raw = v3Boot(stranger, announced)
            chat.route(vm, raw)
            awaitIdle(vm)

            assertEquals("the claimed key's ZBOOT replaced the Open contact's relay identity", relayKey(41), chat.maps.nostr[chat.peer])
            assertNull("the announced key routes to this chat", chat.store.findPeerByNostrPubkey(announced))
            assertFalse("a refused replacement raised the key-changed flag", chat.peer in chat.maps.keyChanged)
            assertFalse("a refused replacement showed the rotated note", SysNotes.rotationNoteId(chat.peer) in chat.maps.rows)
            assertEquals("a refused ZBOOT sent a reply ZBOOT of ours", paidBefore, chat.proposals())
            assertTrue("the refused ZBOOT was recorded as processed", chat.maps.bootSigs.isEmpty())
            assertEquals("the refused ZBOOT is not kept in the mark's entry", raw, chat.maps.held[chat.peer])
        }

    @Test
    fun `(f) the relay fields of a claimed key exchange at an Open contact change no binding and raise no flag`() =
        runBlocking {
            val chat = gapChat(seed = 63411, mode = ConversationMode.OPEN, bound = relayKey(43))
            val vm = chat.viewModel()
            val stranger = E2EEncryption.generateKeyPair()

            handleKexMemo(vm, kexWithRelayFields(chat.peer, stranger, "ZC634F01", relayKey(44)), "synthetic-zc634-f-kex")
            awaitIdle(vm)

            assertEquals("fixture: the claimed key is stored", stranger.publicKey, chat.prefs.e2ePeerKey[chat.peer])
            assertEquals("the binding changed", relayKey(43), chat.maps.nostr[chat.peer])
            assertFalse("the claim's relay fields raised the key-changed flag", chat.peer in chat.maps.keyChanged)
        }

    @Test
    fun `(ab) a mark written at a contact the user had marked verified clears the flag in the same commit`() =
        runBlocking {
            val chat = gapChat(seed = 63412, mode = ConversationMode.TUNNEL, bound = relayKey(40))
            chat.maps.verified += chat.peer
            val vm = chat.viewModel()
            val stranger = E2EEncryption.generateKeyPair()

            handleKexMemo(vm, kexFrom(chat.peer, stranger, "ZC634AB1"), "synthetic-zc634-ab-kex")
            awaitIdle(vm)

            assertEquals("no claim mark was written", stranger.publicKey, chat.maps.claims[chat.peer])
            assertEquals(
                "the key and its mark are not one commit of the key store",
                listOf("${chat.peer} ${stranger.publicKey} true false"),
                chat.maps.keyCommits,
            )
            assertFalse("the verified flag survived the mark", chat.peer in chat.maps.verified)
        }

    @Test
    fun `(t) a key store that refuses the commit stores no key and no mark and the memo is ignored`() =
        runBlocking {
            val chat = gapChat(seed = 63413, mode = ConversationMode.TUNNEL)
            chat.maps.refuseKeyCommits = true
            val vm = chat.viewModel()
            val stranger = E2EEncryption.generateKeyPair()

            handleKexMemo(vm, kexFrom(chat.peer, stranger, "ZC634T01"), "synthetic-zc634-t-kex")
            awaitIdle(vm)

            assertNull("a key was stored although its commit was refused", chat.prefs.e2ePeerKey[chat.peer])
            assertNull("a mark was stored although its commit was refused", chat.maps.claims[chat.peer])
            assertEquals("a memo whose key could not be stored was answered", 0, chat.proposals())
        }

    // ---- replay (c): a chat this phone started (A5) -------------------------------------------------------------------------

    @Test
    fun `(m) a chat this phone started before its key pair exists - the claimed key waits for Accept, its ZBOOT binds nothing`() =
        runBlocking {
            val chat = gapChat(seed = 63414, mode = ConversationMode.TUNNEL)
            chat.queueTunnelMessage("synthetic first tunnel message")
            val vm = chat.viewModel()
            val stranger = E2EEncryption.generateKeyPair()

            handleKexMemo(vm, kexFrom(chat.peer, stranger, "ZC634M01"), "synthetic-zc634-m-kex")
            awaitIdle(vm)
            // W-62 (review T7aD/T7a 19 Oct finding 1): E2E on and our key pair not made yet is a chat this phone began.
            assertEquals("no hold at a chat this phone is setting up", stranger.publicKey, chat.prefs.awaitingAccept[chat.peer])
            assertEquals("an answer was paid with no Accept", 0, chat.proposals())
            assertEquals("a budget unit was spent with no Accept", 0, chat.prefs.budgetConsumed.get())

            chat.route(vm, v3Boot(stranger, relayKey(45)))
            chat.route(vm, v3Boot(stranger, relayKey(46), epoch = 2L))
            awaitIdle(vm)

            assertTrue("a relay identity was bound from the claimed key: ${chat.maps.binds}", chat.maps.binds.isEmpty())
            assertTrue("the waiting Tunnel message left the queue", chat.tunnelMessageWaits())
            assertTrue("the refused ZBOOTs were recorded as processed", chat.maps.bootSigs.isEmpty())
        }

    @Test
    fun `(m) guard - with our key pair made (W-62) the key exchange waits for Accept and its ZBOOT binds nothing`() =
        runBlocking {
            val chat = ClaimMarkChat(SyntheticUnifiedAddress.mainnet(seed = 63415), firstContact = true)
            chat.prefs.e2eEnabled += chat.peer
            chat.prefs.ourKeyStore[chat.peer] = chat.prefs.ourKeys.publicKey to chat.prefs.ourKeys.privateKey
            chat.maps.modes[chat.peer] = ConversationMode.TUNNEL
            val vm = chat.viewModel()
            val stranger = E2EEncryption.generateKeyPair()

            handleKexMemo(vm, kexFrom(chat.peer, stranger, "ZC634M02"), "synthetic-zc634-m2-kex")
            awaitIdle(vm)
            chat.route(vm, v3Boot(stranger, relayKey(47)))

            assertEquals("the W-62 hold", stranger.publicKey, chat.prefs.awaitingAccept[chat.peer])
            assertTrue("nothing may be bound: ${chat.maps.binds}", chat.maps.binds.isEmpty())
            assertEquals("nothing is paid before Accept", 0, chat.proposals())
        }

    @Test
    fun `(o) an Open contact this phone started with keeps its relay identity after Accept of a claimed key`() =
        runBlocking {
            val chat = ClaimMarkChat(SyntheticUnifiedAddress.mainnet(seed = 63416), firstContact = true)
            chat.prefs.e2eEnabled += chat.peer
            chat.prefs.ourKeyStore[chat.peer] = chat.prefs.ourKeys.publicKey to chat.prefs.ourKeys.privateKey
            chat.maps.modes[chat.peer] = ConversationMode.OPEN
            chat.maps.nostr[chat.peer] = relayKey(48)
            val vm = chat.viewModel()
            val stranger = E2EEncryption.generateKeyPair()

            handleKexMemo(vm, kexFrom(chat.peer, stranger, "ZC634O01"), "synthetic-zc634-o-kex")
            awaitIdle(vm)
            assertEquals("fixture: the W-62 hold", stranger.publicKey, chat.prefs.awaitingAccept[chat.peer])
            assertEquals(
                "a key exchange at an address with a relay key gets a mark (5.5 item 2 a)",
                stranger.publicKey,
                chat.maps.claims[chat.peer],
            )
            vm.acceptInboundKexRequest(chat.peer)
            awaitIdle(vm)
            chat.route(vm, v3Boot(stranger, relayKey(49)))
            awaitIdle(vm)

            assertEquals("Accept of a claimed key let its ZBOOT replace the relay identity", relayKey(48), chat.maps.nostr[chat.peer])
        }

    @Test
    fun `(p) after a Reset (our re-exchange owed, keys wiped, relay key kept) a claimed key's ZBOOT replaces nothing`() =
        runBlocking {
            // The state Reset's wipe leaves: E2E on, no key pair of ours, no contact key, the relay identity kept (B6 FIX 2).
            val chat = gapChat(seed = 63417, mode = ConversationMode.TUNNEL, bound = relayKey(50))
            val vm = chat.viewModel()
            val stranger = E2EEncryption.generateKeyPair()

            handleKexMemo(vm, kexFrom(chat.peer, stranger, "ZC634P01"), "synthetic-zc634-p-kex")
            awaitIdle(vm)
            chat.route(vm, v3Boot(stranger, relayKey(51)))
            awaitIdle(vm)

            assertEquals("a claimed key's ZBOOT replaced the relay identity after a Reset", relayKey(50), chat.maps.nostr[chat.peer])
            assertNotNull("no mark was written", chat.maps.claims[chat.peer])
        }

    // ---- the key-changed accept, v4, the bound key's seal ---------------------------------------------------------------------

    @Test
    fun `(j) a second key accepted over a marked first key keeps the mark and its ZBOOT binds nothing`() =
        runBlocking {
            val chat = gapChat(seed = 63418, mode = ConversationMode.TUNNEL)
            val vm = chat.viewModel()
            val first = E2EEncryption.generateKeyPair()
            handleKexMemo(vm, kexFrom(chat.peer, first, "ZC634J01"), "synthetic-zc634-j-kex1")
            awaitIdle(vm)
            assertEquals("fixture: the first key is stored", first.publicKey, chat.prefs.e2ePeerKey[chat.peer])

            // The user accepts a second key: the accept's wipe, then its own adoption of the remembered memo (ACCEPT mode).
            val second = E2EEncryption.generateKeyPair()
            chat.store.clearE2EPeerState(chat.peer)
            chat.prefs.awaitingAccept.remove(chat.peer)
            adoptKeyOffer(vm, kexFrom(chat.peer, second, "ZC634J02"), "synthetic-zc634-j-kex2", chat.peer, second.publicKey, "ACCEPT")
            chat.route(vm, v3Boot(second, relayKey(52)))
            awaitIdle(vm)

            assertEquals("fixture: the accepted key is stored", second.publicKey, chat.prefs.e2ePeerKey[chat.peer])
            assertNotNull("the accept of a key from a key exchange left no mark", chat.maps.claims[chat.peer])
            assertTrue("the accepted claim's ZBOOT bound a relay identity: ${chat.maps.binds}", chat.maps.binds.isEmpty())
        }

    @Test
    fun `(k) v4 at a marked address is followed when the bound relay key signed it and refused otherwise`() =
        runBlocking {
            val chat = markedChat(seed = 63419, mode = ConversationMode.OPEN, bound = relayKey(53))
            val vm = chat.viewModel()

            chat.route(vm, v4Boot(nostr(54), relayKey(55)), sealed = true)
            assertEquals("a v4 signed by another relay key was followed", relayKey(53), chat.maps.nostr[chat.peer])

            chat.route(vm, v4Boot(nostr(53), relayKey(56)), sealed = true)
            assertEquals(
                "a v4 signed by the bound relay key was refused at a marked address (5.5 item 4)",
                relayKey(56),
                chat.maps.nostr[chat.peer],
            )
            assertNotNull("v4 released the mark", chat.maps.claims[chat.peer])
        }

    @Test
    fun `honest (d) a ZBOOT signed by the stored key and sealed by the bound relay key releases the mark`() =
        runBlocking {
            val chat = markedChat(seed = 63420, mode = ConversationMode.OPEN, bound = relayKey(57))
            val vm = chat.viewModel()

            assertTrue("fixture: the sealed ZBOOT is adopted", chat.route(vm, v3Boot(chat.contact, relayKey(57)), sealed = true))

            assertNull("the older identity vouched for the key, the mark stays", chat.maps.claims[chat.peer])
            assertEquals(relayKey(57), chat.maps.nostr[chat.peer])
        }

    @Test
    fun `(z) the binding the key-changed banner's Reconnect drops leaves the mark - the claimed key's ZBOOT binds nothing`() =
        runBlocking {
            val chat = markedChat(seed = 63421, mode = ConversationMode.TUNNEL, bound = relayKey(58))
            val vm = chat.viewModel()
            // What reconnectAndPayOwedKex leaves (CVM "setPeerNostrPubkey(peerAddress, null)"), the flag cleared.
            chat.maps.nostr.remove(chat.peer)

            chat.route(vm, v3Boot(chat.contact, relayKey(59)))

            assertTrue("with no relay key bound the claimed key's ZBOOT bound one: ${chat.maps.binds}", chat.maps.binds.isEmpty())
            assertNotNull(chat.maps.claims[chat.peer])
        }

    // ---- "Mark as verified" hands the held ZBOOT on and pays nothing (items 6 and 9, RED w) ------------------------------------

    @Test
    fun `(w) Open - before the tap the ZBOOT is held, after it the relay key is bound, 0 proposals`() =
        markAsVerified(ConversationMode.OPEN)

    @Test
    fun `(w) Tunnel - before the tap the ZBOOT is held, after it the relay key is bound, 0 proposals`() =
        markAsVerified(ConversationMode.TUNNEL)

    @Test
    fun `(w) Shielded with a quoted allowance - before the tap the ZBOOT is held, after it the relay key is bound, 0 proposals`() =
        markAsVerified(ConversationMode.VAULT)

    private fun markAsVerified(mode: ConversationMode) =
        runBlocking {
            for (acks in listOf(0, 1)) {
                NostrChatBridge.unregisterPublisher()
                ClaimMarkChat.clearTunnelQueue()
                val chat = markedChat(seed = 63430 + mode.ordinal * 2 + acks, mode = mode, bound = null)
                if (mode == ConversationMode.VAULT) chat.maps.zbootQuoted += chat.peer
                if (mode != ConversationMode.VAULT) chat.queueTunnelMessage("synthetic waiting message")
                chat.relays(acks)
                val vm = chat.viewModel()
                val announced = relayKey(60 + acks)
                // acks 0: as attributeBoot hands on an on-chain one; acks 1: as one unsealed over the relays (the same call).
                chat.route(vm, v3Boot(chat.contact, announced))
                awaitIdle(vm)
                val label = "$mode, relays acknowledging $acks"
                assertNotNull("$label: the refused ZBOOT is not held in the mark's entry", chat.maps.held[chat.peer])
                assertTrue("$label: before the tap a relay key was bound: ${chat.maps.binds}", chat.maps.binds.isEmpty())

                vm.markE2EVerified(chat.peer)
                awaitIdle(vm)

                assertNull("$label: the tap left the mark", chat.maps.claims[chat.peer])
                assertTrue("$label: the tap did not set the verified flag", chat.peer in chat.maps.verified)
                assertEquals("$label: the held ZBOOT was not handed on", announced, chat.maps.nostr[chat.peer])
                assertEquals("$label: the tap paid (5.5 item 9)", 0, chat.proposals())
                // Design 5.5 item 9: with no acknowledgement the allowance is neither needed nor spent; an acknowledged relay
                // copy withdraws it, as today.
                if (mode == ConversationMode.VAULT && acks == 0) {
                    assertTrue("$label: the quoted allowance was spent", chat.peer in chat.maps.zbootQuoted)
                }
                if (acks == 0) assertNull("$label: our delivered-identity marker stayed set", chat.maps.sentNostrBoot[chat.peer])
                if (mode != ConversationMode.VAULT) {
                    assertFalse("$label: the waiting message did not go to the key", chat.tunnelMessageWaits())
                }
            }
        }

    // ---- Reconnect while a mark stands (item 8, RED x and y) -------------------------------------------------------------------

    @Test
    fun `(x) Reconnect in a marked chat with no relay key bound pays nothing and says why, twice, with or without our key pair`() =
        runBlocking {
            for (ourKeyPair in listOf(true, false)) {
                val chat = markedChat(seed = 63440 + if (ourKeyPair) 1 else 0, mode = ConversationMode.TUNNEL, bound = null)
                if (!ourKeyPair) chat.prefs.ourKeyStore.remove(chat.peer)
                val vm = chat.viewModel()

                repeat(2) {
                    vm.reSendOurIdentity(chat.peer)
                    awaitIdle(vm)
                }

                val label = if (ourKeyPair) "exchange complete" else "no key pair of ours"
                assertEquals("$label: Reconnect proposed a payment while the mark stands", 0, chat.proposals())
                assertTrue("$label: Reconnect consumed a cooldown", chat.prefs.paidRecords.isEmpty())
                assertEquals(
                    "$label: the notice is not chat_reconnect_not_sent_key_unconfirmed",
                    ChatResourceText.strings("values")["chat_reconnect_not_sent_key_unconfirmed"],
                    (HandshakeHarness.sendMessageState(vm) as? SendMessageState.Success)?.label,
                )
                assertNotNull("$label: the mark went", chat.maps.claims[chat.peer])
            }
        }

    @Test
    fun `(y) Reconnect in a marked chat with a relay key bound goes over the relays only, 0 proposals`() =
        runBlocking {
            for (acks in listOf(1, 0)) {
                NostrChatBridge.unregisterPublisher()
                val chat = markedChat(seed = 63442 + acks, mode = ConversationMode.TUNNEL, bound = relayKey(62))
                chat.relays(acks)
                val vm = chat.viewModel()

                vm.reSendOurIdentity(chat.peer)
                awaitIdle(vm)

                assertTrue("acks $acks: our identity did not go over the relays", chat.publishes.any { it.startsWith(relayKey(62)) })
                assertEquals("acks $acks: Reconnect paid in a marked chat", 0, chat.proposals())
            }
        }

    // ---- the history pass: answers, a restore (items 2, 2a, 6) -----------------------------------------------------------------

    @Test
    fun `(c) a first key taken from a leg 2 answer is stored with a mark`() =
        runBlocking {
            val f = passWithMaps(A)
            val (own, _) =
                f.fixture.ids.store
                    .getOrCreateConversationIdForMessage(A)
            f.fixture.open()
            f.fixture.ownKex("zc634c-own-kex", A, own, minedAt = NEW + 10)
            val theirs = E2EEncryption.generateKeyPair()
            f.fixture.received("zc634c-ack", ackFrom(A, theirs, "ZC634C02"), minedAt = NEW + 12)
            f.fixture.pass()

            assertEquals("fixture: the leg 2 answer is taken", theirs.publicKey, f.fixture.prefs.e2ePeerKey[A])
            assertEquals("a key taken by leg 2 has no mark (5.5 item 2 c)", theirs.publicKey, f.maps.claims[A])
        }

    @Test
    fun `(q) leg 2 answers at a marked address - the claimed key is a repeat, another key is ignored, the mark stands, nothing is bound`() =
        runBlocking {
            val f = passWithMaps(A)
            val (own, _) =
                f.fixture.ids.store
                    .getOrCreateConversationIdForMessage(A)
            f.fixture.open()
            f.fixture.ownKex("zc634q-own-kex", A, own, minedAt = NEW + 10)
            val stranger = E2EEncryption.generateKeyPair()
            f.fixture.prefs.e2ePeerKey[A] = stranger.publicKey
            f.maps.claims[A] = stranger.publicKey
            f.fixture.received("zc634q-ack1", ackWithRelayFields(A, stranger, "ZC634Q01", relayKey(63)), minedAt = NEW + 12)
            f.fixture.received("zc634q-ack2", ackFrom(A, E2EEncryption.generateKeyPair(), "ZC634Q02"), minedAt = NEW + 13)
            f.fixture.pass()

            assertEquals(stranger.publicKey, f.fixture.prefs.e2ePeerKey[A])
            assertNotNull("a leg 2 answer released the mark", f.maps.claims[A])
            assertTrue("a leg 2 answer's relay fields were bound (5.5 item 5): ${f.maps.binds}", f.maps.binds.isEmpty())
            assertFalse("a leg 2 answer raised the key-changed flag", A in f.maps.keyChanged)
        }

    @Test
    fun `(u) the forged proof - M's answer under the id made for M, signed for X with X's claimed key, leaves X's mark and binding`() =
        runBlocking {
            val f = passWithMaps(X)
            val (forM, _) =
                f.fixture.ids.store
                    .getOrCreateConversationIdForMessage(M)
            f.fixture.open()
            f.fixture.ownKex("zc634u-own-kex-m", M, forM, minedAt = NEW + 10)
            val stranger = E2EEncryption.generateKeyPair()
            f.fixture.prefs.e2eEnabled += X
            // The state the claimed key exchange left at X: the key, its mark, and our key pair the handler made for X.
            val oursForX = E2EEncryption.generateKeyPair()
            f.fixture.prefs.ourKeyStore[X] = oursForX.publicKey to oursForX.privateKey
            f.fixture.prefs.e2ePeerKey[X] = stranger.publicKey
            f.maps.claims[X] = stranger.publicKey
            f.maps.nostr[X] = relayKey(64)
            f.maps.modes[X] = ConversationMode.OPEN
            f.fixture.received("zc634u-forged", ackFrom(X, stranger, forM), minedAt = NEW + 12)
            f.fixture.pass()
            val raw = v3Boot(stranger, relayKey(65))
            routeOn(f.fixture.vm, X, raw, sealed = false)

            assertNotNull("X's mark went", f.maps.claims[X])
            assertEquals("X's relay identity moved", relayKey(64), f.maps.nostr[X])
            assertEquals("the ZBOOT is not held", raw, f.maps.held[X])
        }

    @Test
    fun `(ac) A5 after a restore - no second answer, no hold, the claimed key has a mark, its ZBOOT binds nothing and is held`() =
        runBlocking {
            val f = restoredA5(ownKexAfterAnswer = false)
            val raw = v3Boot(f.stranger, relayKey(66), convId = "ZC634AC2")

            assertEquals(
                "no second answer",
                0,
                f.fixture.seam.proposalRequests
                    .get(),
            )
            assertNull("no hold", f.fixture.prefs.awaitingAccept[X])
            assertEquals("fixture: the claimed key is stored", f.stranger.publicKey, f.fixture.prefs.e2ePeerKey[X])
            assertEquals("the answered but unsettled key has no mark (5.5 item 2a, A19)", f.stranger.publicKey, f.maps.claims[X])

            attributeOn(f.fixture.vm, raw)
            routeOn(f.fixture.vm, X, raw, sealed = false)
            assertTrue("a relay identity was bound from the history or live: ${f.maps.binds}", f.maps.binds.isEmpty())
            assertTrue("the ZBOOT was recorded", f.maps.bootSigs.isEmpty())
            assertEquals("the ZBOOT is not held", raw, f.maps.held[X])
        }

    @Test
    fun `(ad) A5 after a restore with our own key exchange mined after our own answer - the same mark`() =
        runBlocking {
            val f = restoredA5(ownKexAfterAnswer = true)

            assertEquals(
                0,
                f.fixture.seam.proposalRequests
                    .get(),
            )
            assertEquals("mined order made the key settled", f.stranger.publicKey, f.maps.claims[X])
        }

    @Test
    fun `(ae) it fails towards the mark - our key exchange's recipient is another text for X, or none can be read`() =
        runBlocking {
            for (recipient in listOf(SyntheticUnifiedAddress.mainnet(seed = 63450), "")) {
                AutoSpendVm.resetProcessState()
                AutoSpendVm.setHistoryRead(true)
                val f = restoredA5(ownKexAfterAnswer = false, ownKexTo = recipient)
                assertEquals("recipient '${recipient.take(8)}': no mark", f.stranger.publicKey, f.maps.claims[X])
            }
        }

    @Test
    fun `(af) guard - our own key exchanges went provably elsewhere, so the answered key at X is settled - no mark, its ZBOOT binds`() =
        runBlocking {
            val f = passWithMaps(X)
            val (forM, _) =
                f.fixture.ids.store
                    .getOrCreateConversationIdForMessage(M)
            f.fixture.open()
            f.fixture.ownKex("zc634af-own-kex-m", M, forM, minedAt = NEW + 5)
            f.fixture.received("zc634af-m-ack", ackFrom(M, E2EEncryption.generateKeyPair(), forM), minedAt = NEW + 6)
            val theirs = E2EEncryption.generateKeyPair()
            f.fixture.received("zc634af-x-kex", kexFrom(X, theirs, "ZC634AF2"), minedAt = NEW + 10)
            val ours = E2EEncryption.generateKeyPair()
            f.fixture.prefs.ourKeyStore[X] = ours.publicKey to ours.privateKey
            f.fixture.sent("zc634af-own-ack", ownAck("ZC634AF2", ours), X, minedAt = NEW + 12)
            f.fixture.pass()

            assertEquals("fixture: X's key is stored", theirs.publicKey, f.fixture.prefs.e2ePeerKey[X])
            assertNull("a settled answer got a mark", f.maps.claims[X])
            routeOn(f.fixture.vm, X, v3Boot(theirs, relayKey(67), convId = "ZC634AF2"), sealed = false)
            assertEquals("the honest restore's ZBOOT must bind", relayKey(67), f.maps.nostr[X])
        }

    @Test
    fun `(ag) flow 16 with A5's history - the hold stops the ZBOOT, and after Accept the stored key has a mark and the ZBOOT is held`() =
        runBlocking {
            val f = passWithMaps(X)
            val (forX, _) =
                f.fixture.ids.store
                    .getOrCreateConversationIdForMessage(X)
            f.fixture.open()
            // Our old key for X cannot be recovered: our own key exchange and answer carry a key the store does not hold.
            val lost = E2EEncryption.generateKeyPair()
            f.fixture.sent(
                "zc634ag-own-kex",
                ZMSGProtocol.createV4KEXMessage(forX, OWN_UA, E2EEncryption.createKEXPayload(OWN_UA, lost.publicKey, lost.privateKey)),
                X,
                minedAt = NEW + 5,
            )
            val stranger = E2EEncryption.generateKeyPair()
            f.fixture.received("zc634ag-kex", kexFrom(X, stranger, "ZC634AG2"), minedAt = NEW + 10)
            f.fixture.sent("zc634ag-own-ack", ownAck("ZC634AG2", lost), X, minedAt = NEW + 12)
            f.fixture.pass()
            assertEquals("fixture: the hold is up again", stranger.publicKey, f.fixture.prefs.awaitingAccept[X])
            val raw = v3Boot(stranger, relayKey(68), convId = "ZC634AG2")
            routeOn(f.fixture.vm, X, raw, sealed = false)
            assertTrue("the hold let the ZBOOT bind: ${f.maps.binds}", f.maps.binds.isEmpty())

            f.fixture.vm.acceptInboundKexRequest(X)
            f.fixture.idle()
            routeOn(f.fixture.vm, X, raw, sealed = false)

            assertEquals("after Accept the answered, unsettled key has no mark", stranger.publicKey, f.maps.claims[X])
            assertTrue("after Accept the claimed key's ZBOOT bound: ${f.maps.binds}", f.maps.binds.isEmpty())
            assertEquals(raw, f.maps.held[X])
        }

    @Test
    fun `(ah) the proof hands the held ZBOOT on - the mark goes and the relay key is bound in the same handling, over the relays only`() =
        runBlocking {
            val f = passWithMaps(X)
            val (forX, _) =
                f.fixture.ids.store
                    .getOrCreateConversationIdForMessage(X)
            f.fixture.open()
            val chatRelays = ClaimMarkChat(X)
            chatRelays.relays(acks = 0)
            f.fixture.ownKex("zc634ah-own-kex", X, forX, minedAt = NEW + 10)
            val theirs = E2EEncryption.generateKeyPair()
            f.fixture.prefs.e2eEnabled += X
            f.fixture.prefs.e2ePeerKey[X] = theirs.publicKey
            f.maps.claims[X] = theirs.publicKey
            f.maps.modes[X] = ConversationMode.TUNNEL
            val raw = v3Boot(theirs, relayKey(69), convId = forX)
            routeOn(f.fixture.vm, X, raw, sealed = false)
            assertTrue("before the answer a relay key was bound: ${f.maps.binds}", f.maps.binds.isEmpty())
            assertEquals("before the answer the ZBOOT is not held", raw, f.maps.held[X])

            f.fixture.received("zc634ah-ack", ackFrom(X, theirs, forX), minedAt = NEW + 12)
            f.fixture.pass()

            assertNull("the proof left the mark", f.maps.claims[X])
            assertEquals("the proof did not hand the held ZBOOT on", relayKey(69), f.maps.nostr[X])
            assertEquals(
                "the hand-over paid with no relay acknowledging (5.5 item 9)",
                0,
                f.fixture.seam.proposalRequests
                    .get(),
            )
        }

    // ---- helpers ------------------------------------------------------------------------------------------------------------

    /** A chat where this phone switched E2E on and has no key pair and no contact key yet (setE2EEnabled, a Reset's wipe). */
    private fun gapChat(
        seed: Int,
        mode: ConversationMode,
        bound: String? = null,
    ): ClaimMarkChat =
        ClaimMarkChat(SyntheticUnifiedAddress.mainnet(seed = seed), firstContact = true).also { chat ->
            chat.prefs.e2eEnabled += chat.peer
            chat.maps.modes[chat.peer] = mode
            if (bound != null) chat.maps.nostr[chat.peer] = bound
        }

    /** A chat whose contact key [MarkedChat.contact] is a claim (the mark stands), our key pair made, [bound] bound. */
    private fun markedChat(
        seed: Int,
        mode: ConversationMode,
        bound: String?,
    ): MarkedChat {
        val chat = MarkedChat(SyntheticUnifiedAddress.mainnet(seed = seed))
        chat.prefs.e2eEnabled += chat.peer
        chat.prefs.ourKeyStore[chat.peer] = chat.prefs.ourKeys.publicKey to chat.prefs.ourKeys.privateKey
        chat.prefs.e2ePeerKey[chat.peer] = chat.contact.publicKey
        chat.maps.claims[chat.peer] = chat.contact.publicKey
        chat.maps.modes[chat.peer] = mode
        if (bound != null) chat.maps.nostr[chat.peer] = bound
        return chat
    }

    private class MarkedChat(
        peer: String,
    ) {
        val contact: E2EKeyPair = E2EEncryption.generateKeyPair()
        private val chat = ClaimMarkChat(peer, firstContact = true)
        val peer get() = chat.peer
        val prefs get() = chat.prefs
        val maps get() = chat.maps
        val publishes get() = chat.publishes

        fun relays(acks: Int) = chat.relays(acks)

        fun queueTunnelMessage(text: String) = chat.queueTunnelMessage(text)

        fun tunnelMessageWaits() = chat.tunnelMessageWaits()

        suspend fun viewModel() = chat.viewModel()

        fun route(
            vm: ChatViewModel,
            raw: String,
            sealed: Boolean = false,
        ) = chat.route(vm, raw, sealed)

        fun proposals() = chat.proposals()
    }

    private class PassWithMaps(
        val fixture: HandshakePassFixture,
        val maps: ClaimMarkMaps,
        val stranger: E2EKeyPair = E2EEncryption.generateKeyPair(),
    )

    private fun passWithMaps(
        peer: String,
        stranger: E2EKeyPair = E2EEncryption.generateKeyPair(),
    ): PassWithMaps {
        val fixture = HandshakePassFixture(scheduler, peer)
        val maps = ClaimMarkMaps(fixture.prefs.e2ePeerKey, fixture.prefs.askedKey)
        fixture.extra = { method, a -> maps.handle(method, a) }
        return PassWithMaps(fixture, maps, stranger)
    }

    /**
     * A5's history read by a restored wallet (A19): this wallet's own key exchange to [ownKexTo] under an own-made id for
     * X, a stranger's key exchange claiming X, this wallet's own mined answer to it (with our key for X, which the
     * restore recovered: the fixture holds it), the real pass twice.
     */
    private suspend fun restoredA5(
        ownKexAfterAnswer: Boolean,
        ownKexTo: String = X,
    ): PassWithMaps {
        val f = passWithMaps(X)
        val (forX, _) =
            f.fixture.ids.store
                .getOrCreateConversationIdForMessage(X)
        f.fixture.open()
        val ours = E2EEncryption.generateKeyPair()
        f.fixture.prefs.ourKeyStore[X] = ours.publicKey to ours.privateKey
        val ownKexMemo =
            ZMSGProtocol.createV4KEXMessage(forX, OWN_UA, E2EEncryption.createKEXPayload(OWN_UA, ours.publicKey, ours.privateKey))
        if (!ownKexAfterAnswer) f.fixture.sent("zc634ac-own-kex-$ownKexTo", ownKexMemo, ownKexTo, minedAt = NEW + 5)
        f.fixture.received("zc634ac-kex", kexFrom(X, f.stranger, "ZC634AC2"), minedAt = NEW + 10)
        f.fixture.sent("zc634ac-own-ack", ownAck("ZC634AC2", ours), X, minedAt = NEW + 12)
        if (ownKexAfterAnswer) f.fixture.sent("zc634ad-own-kex", ownKexMemo, ownKexTo, minedAt = NEW + 20)
        f.fixture.pass()
        return f
    }

    private fun adoptKeyOffer(
        vm: ChatViewModel,
        memo: String,
        txId: String,
        expectedPeer: String,
        expectedKey: String,
        mode: String,
    ): Any? {
        val modeClass = ChatViewModel::class.java.declaredClasses.first { it.simpleName == "KeyOfferMode" }
        val modeValue = modeClass.enumConstants!!.first { (it as Enum<*>).name == mode }
        val method =
            ChatViewModel::class.java.declaredMethods
                .first { it.name == "adoptKeyOffer" && it.parameterCount == 5 }
                .apply { isAccessible = true }
        return try {
            method.invoke(vm, memo, txId, expectedPeer, expectedKey, modeValue)
        } catch (e: InvocationTargetException) {
            throw e.targetException
        }
    }

    private companion object {
        val A = SyntheticUnifiedAddress.mainnet(seed = 63460)
        val X = SyntheticUnifiedAddress.mainnet(seed = 63461)
        val M = SyntheticUnifiedAddress.mainnet(seed = 63462)

        /** This wallet's own address as its own answers sign it: a full-length unified address (see part A's file). */
        val OWN_UA = SyntheticUnifiedAddress.mainnet(seed = 63463)

        fun ackFrom(
            sender: String,
            keys: E2EKeyPair,
            convId: String,
        ): String =
            ZMSGProtocol.createV4KEXAckMessage(convId, sender, E2EEncryption.createKEXAckPayload(sender, keys.publicKey, keys.privateKey))

        fun ackWithRelayFields(
            sender: String,
            keys: E2EKeyPair,
            convId: String,
            relayKey: String,
        ): String {
            val signed = E2EEncryption.createKEXAckPayload(sender, keys.publicKey, keys.privateKey)
            val relayB64 =
                java.util.Base64
                    .getEncoder()
                    .encodeToString(ClaimMarkChat.RELAY.toByteArray(Charsets.UTF_8))
            val payload = signed.substringBeforeLast(":") + ":" + relayKey + ":" + relayB64 + ":" + sender
            return ZMSGProtocol.createV4KEXAckMessage(convId, sender, payload)
        }

        fun ownAck(
            convId: String,
            ours: E2EKeyPair,
        ): String =
            ZMSGProtocol.createV4KEXAckMessage(convId, OWN_UA, E2EEncryption.createKEXAckPayload(OWN_UA, ours.publicKey, ours.privateKey))

        fun routeOn(
            vm: ChatViewModel,
            peer: String,
            raw: String,
            sealed: Boolean,
        ): Boolean {
            val method =
                ChatViewModel::class.java.declaredMethods.first {
                    it.name == "routeIncomingBoot" && it.parameterCount == 3 && it.parameterTypes[2] == java.lang.Boolean.TYPE
                }
            method.isAccessible = true
            return try {
                method.invoke(vm, peer, raw, sealed) as Boolean
            } catch (e: InvocationTargetException) {
                throw e.targetException
            }
        }

        fun attributeOn(
            vm: ChatViewModel,
            raw: String,
        ): String? {
            val method = ChatViewModel::class.java.getDeclaredMethod("attributeBoot", String::class.java).apply { isAccessible = true }
            return try {
                method.invoke(vm, raw) as String?
            } catch (e: InvocationTargetException) {
                throw e.targetException
            }
        }
    }
}
