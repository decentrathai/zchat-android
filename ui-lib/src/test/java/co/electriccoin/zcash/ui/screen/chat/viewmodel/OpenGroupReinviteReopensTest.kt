package co.electriccoin.zcash.ui.screen.chat.viewmodel

import co.electriccoin.zcash.ui.nostr.NostrChatBridge
import co.electriccoin.zcash.ui.screen.chat.model.ZMSGGroupProtocol
import co.electriccoin.zcash.ui.screen.chat.viewmodel.OpenGroup.B
import co.electriccoin.zcash.ui.screen.chat.viewmodel.OpenGroup.C
import co.electriccoin.zcash.ui.screen.chat.viewmodel.OpenGroup.GID
import co.electriccoin.zcash.ui.screen.chat.viewmodel.OpenGroup.X
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

/**
 * GR-OPEN G2, finding 4c (fold-specs/groups SPEC §2.1-§2.3): after a removal, a re-invite never reopens an all-Open
 * group.
 *
 * At HEAD, `offerGroupReopen` needs the creator's E2E key AND a `k2` (a key exchange) to reopen an inactive group, so a
 * plaintext relay re-invite from the creator of an all-Open group logs "not reopened": removing someone by mistake can't
 * be undone.
 *
 * Wanted: when the phone holds no key for the creator, a plaintext-key re-invite sealed by the creator (canonical), at a
 * key epoch not below the group's floor, and newer than the removal, waits for Join like any re-invite; Join reopens the
 * group with that key and keeps its messages. Every other rule (used ids, newer than the removal, the creator's seal)
 * stays. A replayed sealed kick after the reopen does not remove the member again (its epoch is not above).
 *
 * Real ChatViewModel over one MA2 phone (X). All addresses, keys and ids are synthetic; the relays are [RelayNet], so the
 * Join reply goes free; nothing pays.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class OpenGroupReinviteReopensTest {
    private val sendScopes = SendHarnessScopes()
    private val scheduler = TestCoroutineScheduler()
    private val net = RelayNet()

    @Before
    fun setUp() {
        sendScopes.open()
        Dispatchers.setMain(UnconfinedTestDispatcher(scheduler))
        AutoSpendVm.resetProcessState()
        NostrChatBridge.unregisterPublisher()
        MA3.resetRuntime()
        net.register()
    }

    @After
    fun tearDown() {
        NostrChatBridge.unregisterPublisher()
        MA3.resetRuntime()
        sendScopes.close()
        Dispatchers.resetMain()
        AutoSpendVm.resetProcessState()
    }

    private val newKey = AnnounceDriver.newGroupKey()

    /** X's phone (plaintext join), with one message of the creator stored, removed by the creator's sealed kick. */
    private suspend fun removedX(): Pair<AnnouncePhone, ChatViewModel> {
        val (phone, chat) = OpenGroup.plainMember(X)
        MA3.relay(chat, OpenGroup.gm(MA3.groupKey, C, "before the removal", epoch = 1, seq = 1), sender = C, timeMs = MA3.now() - 200_000L)
        MA3.relay(chat, OpenGroup.sealedKick(X, newEpoch = 2, gk = null), sender = C, timeMs = MA3.now() - 100_000L)
        assertFalse("the creator's sealed kick did not remove X's phone", phone.info(GID).isActive)
        return phone to chat
    }

    private fun texts(phone: AnnouncePhone): List<String> = OpenGroup.rows(phone.messages).map { it.optString("decrypted") }

    @Test
    fun `O1 after a sealed kick a newer plain re-invite from the creator waits for Join, Join reopens with its key, keeps messages`() =
        runBlocking {
            val (phone, chat) = removedX()

            MA3.relay(chat, OpenGroup.plainInvite(keyEpoch = 2, key = newKey), sender = C)

            assertTrue("the re-invite does not wait for Join", phone.needsJoin[GID] != null)
            assertFalse("the re-invite reopened the group before Join", phone.info(GID).isActive)
            assertTrue("Join refused", GroupInviteDriver.join(chat, GID))

            assertTrue("Join did not reopen the group", phone.info(GID).isActive)
            assertEquals(2, phone.epochs[GID])
            assertEquals("the reopened group does not use the re-invite's key", newKey, phone.keys["$GID|2"])
            assertTrue("the old messages were lost", texts(phone).contains("before the removal"))
        }

    @Test
    fun `O2 control - the same re-invite from a non-creator, on-chain, or at a lower epoch leaves the group inactive`() =
        runBlocking {
            // The removed state written as a kick leaves it (so this control does not depend on the sealed kick).
            val (phone, chat) = OpenGroup.plainMember(X)
            phone.infos[GID] = ZMSGGroupProtocol.serializeGroupInfo(phone.info(GID).copy(isActive = false, removedByAdmin = true))
            phone.states[GID] = phone.state(GID).copy(inactiveSince = MA3.now() - 100_000L)

            MA3.relay(chat, OpenGroup.plainInvite(keyEpoch = 2, key = newKey, inviter = B), sender = B)
            AnnounceDriver.receive(
                chat,
                OpenGroup.plainInvite(keyEpoch = 2, key = newKey),
                Instant.ofEpochMilli(MA3.now()),
                AnnounceDriver.txId(61),
                minedHeight = 3_000_000L,
            )
            MA3.relay(chat, OpenGroup.plainInvite(keyEpoch = 0, key = newKey), sender = C)

            assertNull("a re-invite from a non-creator, on-chain or at a lower epoch waits for Join", phone.needsJoin[GID])
            assertFalse(phone.info(GID).isActive)
            assertEquals(1, phone.epochs[GID])
        }

    @Test
    fun `O4 a plain-joined X that later got the creator's E2E key (half KEX) is still reopened by the creator's plain re-invite`() =
        runBlocking {
            val (phone, chat) = removedX()
            phone.peerKeys[C] =
                co.electriccoin.zcash.ui.screen.chat.crypto.E2EEncryption
                    .generateKeyPair()
                    .publicKey

            MA3.relay(chat, OpenGroup.plainInvite(keyEpoch = 2, key = newKey), sender = C)

            assertTrue("the re-invite does not wait for Join (review F)", phone.needsJoin[GID] != null)
            assertTrue("Join refused", GroupInviteDriver.join(chat, GID))
            assertTrue("Join did not reopen the group", phone.info(GID).isActive)
            assertEquals(newKey, phone.keys["$GID|2"])
        }

    @Test
    fun `O3 after the reopen, a relay replay of the old sealed kick naming X does not remove X again (epoch not above)`() =
        runBlocking {
            val (phone, chat) = removedX()
            MA3.relay(chat, OpenGroup.plainInvite(keyEpoch = 2, key = newKey), sender = C)
            assertTrue("fixture: Join refused", GroupInviteDriver.join(chat, GID))
            assertTrue("fixture: the group did not reopen", phone.info(GID).isActive)

            MA3.relay(chat, OpenGroup.sealedKick(X, newEpoch = 2, gk = null), sender = C)

            assertTrue("a replayed sealed kick removed X again", phone.info(GID).isActive)
        }
}
