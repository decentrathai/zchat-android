package co.electriccoin.zcash.ui.screen.chat.viewmodel

import co.electriccoin.zcash.ui.nostr.NostrChatBridge
import co.electriccoin.zcash.ui.screen.chat.datasource.ZchatPreferences
import co.electriccoin.zcash.ui.screen.chat.model.AnnounceOp
import co.electriccoin.zcash.ui.screen.chat.model.AnnounceState
import co.electriccoin.zcash.ui.screen.chat.model.GroupMessageType
import co.electriccoin.zcash.ui.screen.chat.model.MemberAnnounceEntry
import co.electriccoin.zcash.ui.screen.chat.model.ZMSGGroupProtocol
import co.electriccoin.zcash.ui.screen.chat.viewmodel.AnnounceDriver.ADMIN
import co.electriccoin.zcash.ui.screen.chat.viewmodel.AnnounceDriver.ADMIN_NOSTR
import co.electriccoin.zcash.ui.screen.chat.viewmodel.AnnounceDriver.GID
import co.electriccoin.zcash.ui.screen.chat.viewmodel.AnnounceDriver.T
import co.electriccoin.zcash.ui.screen.chat.viewmodel.GroupInviteDriver.join
import co.electriccoin.zcash.ui.screen.chat.viewmodel.GroupInviteDriver.plaintextInvite
import co.electriccoin.zcash.ui.screen.chat.viewmodel.GroupInviteDriver.processInvite
import co.electriccoin.zcash.ui.screen.chat.viewmodel.HandshakeHarness.awaitIdle
import co.electriccoin.zcash.ui.screen.chat.viewmodel.HandshakeHarness.chatViewModelWith
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
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
import java.lang.reflect.Proxy
import java.time.Instant
import java.util.Collections
import java.util.concurrent.atomic.AtomicLong

/**
 * ZC-287 (R8 cross-family review, B2C-4) — a group invite that wallet A is processing when the recovery phrase changes
 * is written into wallet B's stores.
 *
 * ChatViewModel.processGroupInvite runs under the group's lock only. It reads who the inviter is, then writes: the
 * stranger's needs-join record (the signed "I joined" reply of wallet A, with A's address), the group, its roster,
 * its key, the used-invite id. None of those writes looks at the wallet generation. A change of recovery phrase in
 * the same process (ChatWalletOwnership.claimFor: wallet A's stores are set aside and wallet B's become the live
 * ones) between the reads and the writes puts A's record and A's group into B's stores. B's group screen then asks
 * "Join", and B's Join pays A's reply from B's funds and sends A's address to that inviter: money B never owed and
 * a link between the two wallets.
 *
 * Wanted: every wallet-scoped write an invite causes happens only while the wallet generation is the one its
 * processing began under; otherwise nothing is written. Three cases, each with the phrase change inside the
 * processing: a stranger's invite; a known contact's invite whose free relay reply cannot go; a re-invite to a group
 * this phone was removed from. In each, wallet B holds nothing of the invite and B's Join answers nothing.
 *
 * The phrase change is [TwoWallets.claim], as in HeldGroupAcceptBelongsToItsWalletTest: the live stores switch to
 * the other wallet's and the stores' generation goes up. Here it happens inside the processing, on a store read the
 * handler makes before its writes ([TwoWallets.claimOn]). All addresses, keys, txids and group ids are synthetic.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class StrangerInviteDuringPhraseChangeStaysInItsWalletTest {
    private val sendScopes = SendHarnessScopes()
    private val published: MutableList<Pair<String, String>> = Collections.synchronizedList(mutableListOf())

    @Before
    fun setUp() {
        sendScopes.open()
        Dispatchers.setMain(UnconfinedTestDispatcher())
        AutoSpendVm.resetProcessState()
        GroupInviteDriver.resetKeystoneExplanation()
        NostrChatBridge.unregisterPublisher()
    }

    @After
    fun tearDown() {
        NostrChatBridge.unregisterPublisher()
        sendScopes.close()
        Dispatchers.resetMain()
        AutoSpendVm.resetProcessState()
        GroupInviteDriver.resetKeystoneExplanation()
    }

    /** The live stores of one phone, which belong to wallet [a] or wallet [b]; the ViewModel sees [proxy]. */
    private class TwoWallets(
        val a: ZchatPreferences,
        val b: ZchatPreferences,
    ) {
        @Volatile var current: ZchatPreferences = a
        private val reloads = AtomicLong(0)

        /** When set: the first store call it answers true for changes the phrase to wallet [b] before it is answered. */
        @Volatile var claimOn: ((name: String, args: Array<Any?>) -> Boolean)? = null

        /** Every store call answered by wallet [b], by name. */
        val callsOnB: MutableList<String> = Collections.synchronizedList(mutableListOf())

        /** A phrase change: [wallet]'s stores become the live ones and every in-memory copy is re-read. */
        fun claim(wallet: ZchatPreferences) {
            current = wallet
            reloads.incrementAndGet()
        }

        val proxy: ZchatPreferences =
            Proxy.newProxyInstance(
                ZchatPreferences::class.java.classLoader,
                arrayOf(ZchatPreferences::class.java),
            ) { p, method, args ->
                when (method.name) {
                    "walletScopeGeneration" -> reloads.get()
                    "toString" -> "TwoWallets"
                    "hashCode" -> System.identityHashCode(p)
                    "equals" -> p === args?.get(0)
                    else -> {
                        val trigger = claimOn
                        if (trigger != null && trigger(method.name, args ?: emptyArray())) {
                            claimOn = null
                            claim(b)
                        }
                        if (current === b) callsOnB += method.name
                        try {
                            method.invoke(current, *(args ?: emptyArray()))
                        } catch (e: InvocationTargetException) {
                            throw e.targetException
                        }
                    }
                }
            } as ZchatPreferences

        /** The invite's wallet-scoped writes that reached wallet [b]. */
        fun inviteWritesOnB(): List<String> = callsOnB.filter { it in INVITE_WRITES }
    }

    private suspend fun strangerScreen(
        wallets: TwoWallets,
        seam: HandshakeSeam,
    ): ChatViewModel = chatViewModelWith(wallets.proxy, seam).also(AutoSpendVm::wireBootstrapFields)

    /** A screen over two [AnnouncePhone] wallets, as [AnnouncePhone.self]; [saved] are the address book's contacts. */
    private suspend fun phoneScreen(
        wallets: TwoWallets,
        seam: HandshakeSeam,
        saved: Set<String> = emptySet(),
    ): ChatViewModel =
        chatViewModelWith(wallets.proxy, seam).also { vm ->
            AutoSpendVm.wireBootstrapFields(vm)
            AutoSpendVm.setField(vm, "_currentUserAddress", MutableStateFlow<String?>(HandshakeHarness.SELF))
            if (saved.isNotEmpty()) AutoSpendVm.setField(vm, "contactBook", SavedContacts(saved))
        }

    private fun acceptsPublished(): Int = published.count { ZMSGGroupProtocol.parseMessageType(it.first) == GroupMessageType.GROUP_ACCEPT }

    // ---- (a) a stranger's invite ---------------------------------------------------------------------------------

    @Test
    fun `a stranger's invite caught by a phrase change leaves nothing in wallet B, and B's Join pays nothing`() =
        runBlocking {
            val stranger = SyntheticUnifiedAddress.mainnet(seed = 2871)
            val a = StrangerInvitePrefs(consent = true)
            val b = StrangerInvitePrefs(consent = true)
            val wallets = TwoWallets(a.proxy, b.proxy)
            val seam = HandshakeSeam(stranger)
            val underA = strangerScreen(wallets, seam)
            awaitIdle(underA)

            // The phrase changes while the handler decides whether the inviter is a stranger, before any write.
            wallets.claimOn = { name, args -> name == "isE2EEnabled" && args.firstOrNull() == stranger }
            processInvite(underA, "GRACE01", plaintextInvite(stranger))
            awaitIdle(underA)
            assertTrue("fixture sanity: the phrase changed inside the invite's processing", wallets.current === b.proxy)

            assertEquals("wallet A's invite was written into wallet B's stores", emptyList<String>(), wallets.inviteWritesOnB())
            assertFalse("wallet A's needs-join record is in wallet B's store", b.needsJoin.containsKey("GRACE01"))
            assertFalse("wallet A's group is in wallet B's store", b.base.groupInfo.containsKey("GRACE01"))
            assertFalse("the inviter's key was stored for wallet B", b.base.peerKeys.containsKey(stranger))

            val underB = strangerScreen(wallets, seam)
            awaitIdle(underB)
            assertFalse("wallet B's Join answered wallet A's invite", join(underB, "GRACE01"))
            assertEquals("wallet B paid wallet A's group reply", 0, seam.proposalRequests.get())
            assertEquals(0, seam.submits.get())
        }

    @Test
    fun `control - with no phrase change a stranger's invite waits for Join in its own wallet`() =
        runBlocking {
            val stranger = SyntheticUnifiedAddress.mainnet(seed = 2872)
            val a = StrangerInvitePrefs(consent = true)
            val b = StrangerInvitePrefs(consent = true)
            val wallets = TwoWallets(a.proxy, b.proxy)
            val seam = HandshakeSeam(stranger)
            val underA = strangerScreen(wallets, seam)
            awaitIdle(underA)

            processInvite(underA, "GRACE02", plaintextInvite(stranger))
            awaitIdle(underA)

            assertTrue("the stranger's invite must wait for Join in wallet A", a.needsJoin.containsKey("GRACE02"))
            assertTrue("the group must be saved in wallet A", a.base.groupInfo.containsKey("GRACE02"))
            assertEquals("nothing may be sent before Join", 0, seam.proposalRequests.get())
            assertEquals("wallet B's stores were touched", emptyList<String>(), wallets.callsOnB.toList())

            assertTrue("wallet A's Join was refused", join(underA, "GRACE02"))
            assertEquals("wallet A's Join must send its one reply", 1, seam.submits.get())
        }

    // ---- (b) a known contact's invite ----------------------------------------------------------------------------

    @Test
    fun `a known contact's invite caught by a phrase change leaves no group and no relay-failed record in wallet B`() =
        runBlocking {
            val contact = SyntheticUnifiedAddress.mainnet(seed = 2873)
            val a = AnnouncePhone()
            val b = AnnouncePhone()
            val wallets = TwoWallets(a.proxy, b.proxy)
            val seam = HandshakeSeam(contact)
            val underA = phoneScreen(wallets, seam, saved = setOf(contact))
            awaitIdle(underA)
            AnnounceDriver.relayAcks(published)

            // A saved contact with no key: the handler reads its E2E flag, then finds it in the address book. Neither
            // wallet holds its relay key, so the free reply cannot go.
            wallets.claimOn = { name, args -> name == "isE2EEnabled" && args.firstOrNull() == contact }
            processInvite(underA, "GRACE03", plaintextInvite(contact))
            awaitIdle(underA)
            assertTrue("fixture sanity: the phrase changed inside the invite's processing", wallets.current === b.proxy)

            assertEquals("wallet A's invite was written into wallet B's stores", emptyList<String>(), wallets.inviteWritesOnB())
            assertNull("wallet A's relay-failed reply waits for Join in wallet B", b.needsJoin["GRACE03"])
            assertFalse("wallet A's group is in wallet B's store", b.infos.containsKey("GRACE03"))
            assertFalse("wallet A's roster is in wallet B's store", b.rosters.containsKey("GRACE03"))
            assertTrue("wallet A's group key is in wallet B's store: ${b.keys.keys}", b.keys.isEmpty())
            assertEquals("a reply was sent for an invite caught by the phrase change", 0, acceptsPublished())

            val underB = phoneScreen(wallets, seam, saved = setOf(contact))
            awaitIdle(underB)
            assertFalse("wallet B's Join answered wallet A's invite", join(underB, "GRACE03"))
            assertEquals("wallet B paid wallet A's group reply", 0, seam.proposalRequests.get())
            assertEquals(0, seam.submits.get())
        }

    /** The reply belongs to the wallet the invite was processed under, also when the phrase changes after the group was saved. */
    @Test
    fun `a known contact's invite looks up no relay key, so a phrase change on that read finds nothing to write into wallet B`() =
        runBlocking {
            // [test-change-ok: W-137 ZC-922 every invite waits for Join] The invite no longer tries a relay reply (the
            // phrase change used to come on the lookup of the inviter's relay key); it waits for Join in wallet A. The
            // claim stays armed on that lookup: it must never come, and wallet B must hold nothing of the invite.
            val contact = SyntheticUnifiedAddress.mainnet(seed = 2874)
            val a = AnnouncePhone()
            val b = AnnouncePhone()
            val wallets = TwoWallets(a.proxy, b.proxy)
            val seam = HandshakeSeam(contact)
            val underA = phoneScreen(wallets, seam, saved = setOf(contact))
            awaitIdle(underA)
            AnnounceDriver.relayAcks(published)

            wallets.claimOn = { name, args -> name == "getPeerNostrPubkey" && args.firstOrNull() == contact }
            processInvite(underA, "GRACE04", plaintextInvite(contact))
            awaitIdle(underA)

            assertTrue("no relay reply is tried before Join, so no relay-key lookup changes the phrase", wallets.current === a.proxy)
            assertTrue("the group was saved in wallet A", a.infos.containsKey("GRACE04"))
            assertTrue("the invite waits for Join in wallet A", a.needsJoin.containsKey("GRACE04"))
            assertEquals("wallet A's reply was written into wallet B's stores", emptyList<String>(), wallets.inviteWritesOnB())
            assertNull("wallet A's reply waits for Join in wallet B", b.needsJoin["GRACE04"])
            assertEquals("a reply was sent before Join", 0, acceptsPublished())
            assertEquals(0, seam.proposalRequests.get())
        }

    @Test
    fun `control - with no phrase change a known contact's free reply goes over the relays on Join`() =
        runBlocking {
            val contact = SyntheticUnifiedAddress.mainnet(seed = 2875)
            val a = AnnouncePhone().apply { nostrKeys[contact] = ADMIN_NOSTR }
            val b = AnnouncePhone()
            val wallets = TwoWallets(a.proxy, b.proxy)
            val seam = HandshakeSeam(contact)
            val underA = phoneScreen(wallets, seam, saved = setOf(contact))
            awaitIdle(underA)
            AnnounceDriver.relayAcks(published)

            processInvite(underA, "GRACE05", plaintextInvite(contact))
            awaitIdle(underA)

            assertTrue("the group must be saved in wallet A", a.infos.containsKey("GRACE05"))
            // [test-change-ok: W-137 ZC-922 every invite waits for Join] the free relay reply goes when the user taps Join.
            assertEquals("a reply went before Join: $published", 0, acceptsPublished())
            assertTrue("the invite must wait for Join", a.needsJoin.containsKey("GRACE05"))
            assertTrue("Join must release the reply", join(underA, "GRACE05"))
            assertEquals(
                "the known contact's one free reply did not go over the relays: $published",
                1,
                published.count { (text, to) ->
                    to == ADMIN_NOSTR && ZMSGGroupProtocol.parseMessageType(text) == GroupMessageType.GROUP_ACCEPT
                },
            )
            assertNull("a reply that went free must not wait for Join", a.needsJoin["GRACE05"])
            assertEquals(0, seam.proposalRequests.get())
            assertEquals("wallet B's stores were touched", emptyList<String>(), wallets.callsOnB.toList())
        }

    // ---- (c) a re-invite to a group this phone was removed from --------------------------------------------------

    private val removedAt = T + 6_000

    /** A member's phone the creator removed from [GID]: the group is inactive at key epoch 2, the removal recorded. */
    private fun removedPhone(): AnnouncePhone {
        val phone = AnnouncePhone()
        AnnounceDriver.memberPhone(phone, epoch = 2)
        phone.infos[GID] = ZMSGGroupProtocol.serializeGroupInfo(phone.info(GID).copy(isActive = false, removedByAdmin = true))
        phone.states[GID] =
            AnnounceState(
                hw = removedAt,
                rosterAnnounced = true,
                inactiveSince = removedAt,
                usedInviteIds = setOf(AnnounceDriver.txId(100).txIdString()),
                members = mapOf(phone.self to MemberAnnounceEntry(n = removedAt, op = AnnounceOp.R, epoch = 2)),
            )
        phone.nostrKeys[ADMIN] = ADMIN_NOSTR
        return phone
    }

    @Test
    fun `a re-invite to an inactive group caught by a phrase change leaves no record in wallet B, and B's Join reopens nothing`() =
        runBlocking {
            // Both phrases were members of the group and both were removed; the creator invites wallet A again.
            val a = removedPhone()
            val b = removedPhone()
            val wallets = TwoWallets(a.proxy, b.proxy)
            val seam = HandshakeSeam(ADMIN)
            val underA = phoneScreen(wallets, seam)
            awaitIdle(underA)
            AnnounceDriver.relayAcks(published)
            val fresh = AnnounceDriver.gi(ADMIN, a.sessionWith(ADMIN), AnnounceDriver.newGroupKey(), keyEpoch = 3)
            val bStateBefore = b.state(GID)

            // The phrase changes after the invite was checked against wallet A's group and before its record is
            // written (the relay-key lookup the record's "relay failed" flag is made from).
            wallets.claimOn = { name, args -> name == "getPeerNostrPubkey" && args.firstOrNull() == ADMIN }
            AnnounceDriver.receive(underA, fresh, Instant.ofEpochMilli(T + 20_000), AnnounceDriver.txId(200))
            awaitIdle(underA)
            assertTrue("fixture sanity: the phrase changed inside the re-invite's processing", wallets.current === b.proxy)

            assertEquals("wallet A's re-invite was written into wallet B's stores", emptyList<String>(), wallets.inviteWritesOnB())
            assertNull("wallet A's re-invite waits for Join in wallet B", b.needsJoin[GID])
            assertEquals("wallet B's announce state was changed by wallet A's re-invite", bStateBefore, b.state(GID))

            val underB = phoneScreen(wallets, seam)
            awaitIdle(underB)
            assertFalse("wallet B's Join answered wallet A's re-invite", underB.joinGroupInvite(GID))
            awaitIdle(underB)
            assertFalse("wallet B's group was reopened with wallet A's invite", b.info(GID).isActive)
            assertEquals("wallet B's key epoch was changed", 2, b.epochs[GID])
            assertEquals("wallet A's signed reply was sent from wallet B: $published", 0, acceptsPublished())
            assertEquals(0, seam.proposalRequests.get())
        }

    @Test
    fun `control - with no phrase change a re-invite to an inactive group waits for Join in its own wallet`() =
        runBlocking {
            val a = removedPhone()
            val b = removedPhone()
            val wallets = TwoWallets(a.proxy, b.proxy)
            val seam = HandshakeSeam(ADMIN)
            val underA = phoneScreen(wallets, seam)
            awaitIdle(underA)
            AnnounceDriver.relayAcks(published)
            val fresh = AnnounceDriver.gi(ADMIN, a.sessionWith(ADMIN), AnnounceDriver.newGroupKey(), keyEpoch = 3)

            AnnounceDriver.receive(underA, fresh, Instant.ofEpochMilli(T + 20_000), AnnounceDriver.txId(200))
            awaitIdle(underA)

            assertNotNull("the re-invite must wait for Join in wallet A", a.needsJoin[GID])
            assertFalse("the group was reopened without Join", a.info(GID).isActive)
            assertEquals("a reply was sent before Join: $published", 0, acceptsPublished())
            assertEquals("wallet B's stores were touched", emptyList<String>(), wallets.callsOnB.toList())

            assertTrue("wallet A's Join was refused", underA.joinGroupInvite(GID))
            awaitIdle(underA)
            assertTrue("wallet A's Join did not reopen the group", a.info(GID).isActive)
            assertEquals("wallet A's one reply did not go free over the relays", 1, acceptsPublished())
            assertEquals(0, seam.proposalRequests.get())
        }

    private companion object {
        /** The store writes a group invite causes (ZchatPreferences, by name). */
        val INVITE_WRITES =
            setOf(
                "setGroupInviteNeedsJoin",
                "setE2EPeerPublicKey",
                // ZC-634: the one write of a contact key since part B.
                "storePeerKey",
                "saveGroupInfo",
                "saveGroupMembers",
                "saveGroupMembersDurably",
                "saveGroupKey",
                "setGroupKeyEpoch",
                "saveGroupAnnounceState",
                "markNostrEventSeen",
                "unmarkNostrEventSeen",
                "setHeldGroupAccept",
            )
    }
}
