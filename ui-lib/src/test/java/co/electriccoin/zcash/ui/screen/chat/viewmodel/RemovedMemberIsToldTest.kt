package co.electriccoin.zcash.ui.screen.chat.viewmodel

import androidx.lifecycle.viewModelScope
import cash.z.ecc.android.sdk.model.TransactionId
import co.electriccoin.zcash.ui.nostr.NostrChatBridge
import co.electriccoin.zcash.ui.screen.chat.crypto.E2EEncryption
import co.electriccoin.zcash.ui.screen.chat.datasource.ZchatPreferences
import co.electriccoin.zcash.ui.screen.chat.model.GroupInfo
import co.electriccoin.zcash.ui.screen.chat.model.GroupMember
import co.electriccoin.zcash.ui.screen.chat.model.GroupMessageType
import co.electriccoin.zcash.ui.screen.chat.model.InviteStatus
import co.electriccoin.zcash.ui.screen.chat.model.MemberStatus
import co.electriccoin.zcash.ui.screen.chat.model.ZMSGGroupProtocol
import co.electriccoin.zcash.ui.screen.chat.view.ChatResourceText
import co.electriccoin.zcash.ui.screen.chat.view.groupSendBannerText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.lang.reflect.Proxy
import java.security.SecureRandom
import java.time.Instant
import java.util.Base64
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

/**
 * Round 8 P6 (PHONE_TRIAGE F5b) — the removed member is never told.
 *
 * Admin side: the admin (the fixture's SELF) removes K ([GroupPaidOnceFixture.MEMBER_P], a KEX session with us).
 * At 03232eac0 rotateAndNotify leaves K out on purpose, so nothing at all goes to K: K's phone keeps showing the group
 * with a free composer, and an on-chain K keeps paying 17,000 zatoshi per group message that nobody reads.
 *
 * Member side: when K does get a verified GROUP_KICK naming K, processGroupKick only logs "We were kicked" and returns.
 * The group stays active, the cost line keeps quoting the other members, and a send still goes out (and is paid).
 *
 * Wanted:
 * 1. A kick also sends K a signed GROUP_KICK with NO key (no `enc_key`), whose signature verifies against the admin's
 *    key over groupKickSignedData(G, K, admin, newEpoch, null). Free over the relays when K is reachable there; when
 *    K is on-chain only, it is quoted with the kick and paid only after Confirm. The new key never goes to K.
 * 2. After K processes that notice, K's group is inactive, groupMessageCost(G) has no recipients, and
 *    sendGroupMessage sends and pays nothing and reports a GroupSendResult. The screen text is
 *    "You were removed from this group" / "Te quitaron de este grupo".
 *
 * New API is not referenced, so this file compiles on the build before the fix. All addresses, keys and group ids are
 * synthetic.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RemovedMemberIsToldTest {
    private val sendScopes = SendHarnessScopes()
    private val vms = mutableListOf<GroupViewModel>()
    private val scheduler = TestCoroutineScheduler()

    /** Every memo published over the relays: (plaintext, recipient relay key). */
    private val published: MutableList<Pair<String, String>> = Collections.synchronizedList(mutableListOf())

    @Before
    fun setUp() {
        sendScopes.open()
        Dispatchers.setMain(UnconfinedTestDispatcher(scheduler))
        NostrChatBridge.unregisterPublisher()
    }

    @After
    fun tearDown() {
        NostrChatBridge.unregisterPublisher()
        sendScopes.close()
        vms.forEach { it.viewModelScope.cancel() }
        // A cancelled screen still resumes on Main while it ends; resetting Main first leaks into another test.
        kotlinx.coroutines.runBlocking {
            vms.toList().forEach { screen ->
                kotlinx.coroutines.withTimeoutOrNull(5_000L) {
                    screen.viewModelScope.coroutineContext[kotlinx.coroutines.Job]?.join()
                }
            }
        }
        vms.clear()
        Dispatchers.resetMain()
    }

    private fun relayAcksEverything() =
        NostrChatBridge.registerPublisher { plaintext, recipient ->
            published += plaintext to recipient
            NostrChatBridge.PublishResult(acks = 1, messageId = "synthetic-relay-event-${published.size}")
        }

    private suspend fun adminViewModel(f: GroupPaidOnceFixture): GroupViewModel {
        f.scheduler = scheduler
        return f.groupViewModel().also { vms += it }
    }

    /** The GROUP_KICK memos the relays carried to K. */
    private fun kicksPublishedToK(): List<String> =
        published
            .filter { (_, recipient) -> recipient == GroupPaidOnceFixture.SYNTHETIC_NOSTR_PUBKEY }
            .map { it.first }
            .filter { ZMSGGroupProtocol.parseMessageType(it) == GroupMessageType.GROUP_KICK }

    /** Kick K with K reachable over the relays; return the one GROUP_KICK K was sent, and the admin's public key. */
    private suspend fun kickKOverRelays(): Pair<String, String> {
        val f = GroupPaidOnceFixture(nostrKeyForP = { GroupPaidOnceFixture.SYNTHETIC_NOSTR_PUBKEY })
        relayAcksEverything()
        val vm = adminViewModel(f)
        f.awaitNewJobs(vm) { vm.kickMember(GroupPaidOnceFixture.GROUP_ID, GroupPaidOnceFixture.MEMBER_P) }
        if (vm.paidActionConfirmation.value != null) f.awaitNewJobs(vm) { vm.confirmPaidAction() }

        assertEquals("fixture: K is removed on the admin's phone", 2, f.epoch())
        assertEquals("fixture: K is removed on the admin's phone", MemberStatus.LEFT, f.memberRow().status)
        assertEquals("a notice reachable free over the relays was paid on-chain", 0, f.submits())
        val kicks = kicksPublishedToK()
        assertEquals("K, removed from the group, was sent nothing at all: $published", 1, kicks.size)
        return kicks.single() to f.proxy.getE2EOurPublicKey(GroupPaidOnceFixture.MEMBER_P)!!
    }

    // ── 1. admin side ────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `removing K sends K a signed removal notice with no key over the relays`() =
        runBlocking {
            val (memo, adminPub) = kickKOverRelays()

            assertEquals(GroupPaidOnceFixture.GROUP_ID, ZMSGGroupProtocol.parseGroupId(memo))
            val payload = JSONObject(ZMSGGroupProtocol.parsePayload(memo)!!)
            assertFalse("the removal notice carries a group key to the removed member: $payload", payload.has("enc_key"))
            assertEquals(GroupPaidOnceFixture.MEMBER_P, payload.getString("kicked"))
            assertEquals(GroupPaidOnceFixture.SELF, payload.getString("kicker"))
            assertEquals(2, payload.getInt("new_epoch"))
            val signedData =
                ZMSGGroupProtocol.groupKickSignedData(
                    GroupPaidOnceFixture.GROUP_ID,
                    GroupPaidOnceFixture.MEMBER_P,
                    GroupPaidOnceFixture.SELF,
                    2,
                    null,
                )
            assertTrue(
                "the removal notice's signature does not verify against the admin's key",
                E2EEncryption.verify(adminPub, signedData, payload.getString("sig")),
            )
            assertFalse(
                "a GROUP_KEY (the new key) went to the removed member",
                published.any { (text, recipient) ->
                    recipient == GroupPaidOnceFixture.SYNTHETIC_NOSTR_PUBKEY &&
                        ZMSGGroupProtocol.parseMessageType(text) == GroupMessageType.GROUP_KEY
                },
            )
        }

    @Test
    fun `the notice to a removed member with no relay route is quoted with the kick and paid only after Confirm`() =
        runBlocking {
            val f = GroupPaidOnceFixture()
            val vm = adminViewModel(f)

            f.awaitNewJobs(vm) { vm.kickMember(GroupPaidOnceFixture.GROUP_ID, GroupPaidOnceFixture.MEMBER_P) }

            val quote = vm.paidActionConfirmation.value
            assertNotNull(
                "the on-chain removal notice to K is not quoted (nothing is sent to K): epoch=${f.epoch()}, submits=${f.submits()}",
                quote,
            )
            assertEquals("the quote counts the one on-chain notice to K", 1, quote!!.onChainMessages)
            assertEquals("paid before Confirm", 0, f.seam.proposalRequests.get())

            f.awaitNewJobs(vm) { vm.confirmPaidAction() }
            assertEquals("Confirm pays exactly the quoted notice", 1, f.seam.proposalRequests.get())
            assertEquals(MemberStatus.LEFT, f.memberRow().status)
        }

    // ── 2. member side ───────────────────────────────────────────────────────────────────────────────

    @Test
    fun `after K processes the notice its group is inactive, costs nothing and sends nothing`() =
        runBlocking {
            val (memo, adminPub) = kickKOverRelays()
            val k = MemberKPrefs(adminPub = adminPub, active = true)

            processGroupKickOnK(k, memo)

            val info = ZMSGGroupProtocol.deserializeGroupInfo(k.proxy.getGroupInfo(GroupPaidOnceFixture.GROUP_ID)!!)!!
            assertFalse("K processed a verified removal of K and its group is still active", info.isActive)

            val vm = memberViewModel(k)
            val g = k.fixture
            assertEquals("K's cost line still quotes members of a group K was removed from", 0, vm.groupMessageCost(GROUP).recipients)
            sendAndConfirm(g, vm, "still here?")
            assertEquals("K, removed, still paid to send a group message", 0, g.seam.proposalRequests.get())
            assertTrue("K, removed, still published a group message: $published", published.none { it.second == OTHER_NOSTR })
            assertNotNull("the refused send reports no outcome", vm.groupSendEvent.value)
            // Round 8 P6 fix: the group is stored as one K was removed from, so the refusal says removed, not left.
            assertEquals(
                "You were removed from this group",
                groupSendBannerText(
                    vm.groupSendEvent.value!!,
                    { id -> ChatResourceText.english(id) },
                    { id, quantity, args -> ChatResourceText.englishPlural(id, quantity, args) },
                ),
            )
        }

    @Test
    fun `a verified removal notice naming K, with no key, makes K's group inactive`() =
        runBlocking {
            // The notice as the admin builds it, signed here with a synthetic admin key (independent of the admin side).
            val admin = E2EEncryption.generateKeyPair()
            val signedData = ZMSGGroupProtocol.groupKickSignedData(GROUP, K, GroupPaidOnceFixture.SELF, 2, null)
            val memo =
                ZMSGGroupProtocol.createGroupKickMessage(
                    GROUP,
                    K,
                    GroupPaidOnceFixture.SELF,
                    2,
                    null,
                    E2EEncryption.sign(admin.privateKey, signedData),
                )
            val k = MemberKPrefs(adminPub = admin.publicKey, active = true)

            processGroupKickOnK(k, memo)

            val info = ZMSGGroupProtocol.deserializeGroupInfo(k.proxy.getGroupInfo(GROUP)!!)!!
            assertFalse("K processed a verified removal of K and its group is still active", info.isActive)
            assertEquals(
                "K's own row is not marked as out of the group",
                MemberStatus.LEFT,
                ZMSGGroupProtocol.deserializeGroupMembers(k.proxy.getGroupMembers(GROUP)!!).single { it.address == K }.status,
            )
            assertEquals("K's cost line still quotes the other members", 0, memberViewModel(k).groupMessageCost(GROUP).recipients)
        }

    @Test
    fun `a group this phone was removed from refuses a send with a banner, and the screen text is in both languages`() =
        runBlocking {
            val k = MemberKPrefs(adminPub = E2EEncryption.generateKeyPair().publicKey, active = false)
            val vm = memberViewModel(k)
            val g = k.fixture

            assertEquals(0, vm.groupMessageCost(GROUP).recipients)
            sendAndConfirm(g, vm, "still here?")

            assertEquals("a group this phone is no longer in paid for a message", 0, g.seam.proposalRequests.get())
            assertTrue("a group this phone is no longer in published a message: $published", published.isEmpty())
            val event = vm.groupSendEvent.value
            assertNotNull("the refused send reports no outcome", event)
            assertFalse(
                "the refusal says the members have not joined: $event",
                event is GroupSendResult.NoActiveRecipients,
            )
            assertEquals("the typed text is kept", "still here?", k.draft())

            assertEquals("You were removed from this group", ChatResourceText.strings("values")["group_you_were_removed"])
            assertEquals("Te quitaron de este grupo", ChatResourceText.strings("values-es")["group_you_were_removed"])
            val banner =
                groupSendBannerText(
                    event!!,
                    { id -> ChatResourceText.english(id) },
                    { id, quantity, args -> ChatResourceText.englishPlural(id, quantity, args) },
                )
            assertEquals("You were removed from this group", banner)
        }

    @Test
    fun `control - a group this phone left refuses a send and says it was left, not removed`() =
        runBlocking {
            val k = MemberKPrefs(adminPub = E2EEncryption.generateKeyPair().publicKey, active = false, removed = false)
            val vm = memberViewModel(k)
            val g = k.fixture

            assertEquals(0, vm.groupMessageCost(GROUP).recipients)
            sendAndConfirm(g, vm, "still here?")

            assertEquals(0, g.seam.proposalRequests.get())
            assertTrue(published.isEmpty())
            val banner =
                groupSendBannerText(
                    vm.groupSendEvent.value!!,
                    { id -> ChatResourceText.english(id) },
                    { id, quantity, args -> ChatResourceText.englishPlural(id, quantity, args) },
                )
            assertEquals("You left this group", banner)
            assertEquals("Saliste de este grupo", ChatResourceText.strings("values-es")["group_you_left"])
        }

    // ── member-side plumbing ─────────────────────────────────────────────────────────────────────────

    /** Send [text] as K; accept the cost question if one is asked (a send the fix refuses asks none). */
    private suspend fun sendAndConfirm(
        g: GroupPaidOnceFixture,
        vm: GroupViewModel,
        text: String,
    ) {
        relayAcksEverything()
        g.awaitNewJobs(vm) { vm.sendGroupMessage(GROUP, text) }
        if (vm.paidActionConfirmation.value != null) g.awaitNewJobs(vm) { vm.confirmPaidAction() }
    }

    /** K's GroupViewModel: the fixture's wiring (and its SDK seam), over K's preferences, as K. */
    private suspend fun memberViewModel(k: MemberKPrefs): GroupViewModel {
        val vm = adminViewModel(k.fixture)
        GroupViewModel::class.java
            .getDeclaredField("zchatPreferences")
            .apply { isAccessible = true }
            .set(vm, k.proxy)
        @Suppress("UNCHECKED_CAST")
        (
            GroupViewModel::class.java
                .getDeclaredField("_currentUserAddress")
                .apply { isAccessible = true }
                .get(vm) as MutableStateFlow<String?>
        ).value = K
        vm.consumeGroupSendEvent()
        return vm
    }

    /** K's ChatViewModel receive path, on a bare instance holding only K's preferences. */
    private fun processGroupKickOnK(
        k: MemberKPrefs,
        memo: String,
    ) {
        val unsafeClass = Class.forName("sun.misc.Unsafe")
        val unsafe = unsafeClass.getDeclaredField("theUnsafe").apply { isAccessible = true }.get(null)
        val vm = unsafeClass.getMethod("allocateInstance", Class::class.java).invoke(unsafe, ChatViewModel::class.java) as ChatViewModel
        ChatViewModel::class.java.declaredFields
            .first { it.type == ZchatPreferences::class.java }
            .apply { isAccessible = true }
            .set(vm, k.proxy)
        ChatViewModel::class.java
            .getDeclaredMethod("processGroupKick", String::class.java, String::class.java, TransactionId::class.java)
            .apply { isAccessible = true }
            .invoke(vm, ZMSGGroupProtocol.parseGroupId(memo), ZMSGGroupProtocol.parsePayload(memo), null)
    }

    /**
     * K's preferences: the admin (SELF) created G; K and OTHER are ACTIVE members at epoch 1. K holds the admin's
     * public key from their key exchange. [active] false: the group is stored as one K is no longer in (K's own row
     * LEFT); [removed] true stores it as processGroupKick does after a removal (the "removed" mark), false as after
     * K left it.
     */
    private class MemberKPrefs(
        adminPub: String,
        active: Boolean,
        removed: Boolean = !active,
    ) {
        val fixture = GroupPaidOnceFixture()
        private val infos = ConcurrentHashMap<String, String>()
        private val rosters = ConcurrentHashMap<String, String>()
        private val keys = ConcurrentHashMap<String, String>()
        private val epochs = ConcurrentHashMap<String, Int>()
        private val drafts = ConcurrentHashMap<String, String>()
        private val floors = ConcurrentHashMap<String, Int>()
        private val consents = ConcurrentHashMap<String, Set<String>>()
        private val peerKeys = mapOf(GroupPaidOnceFixture.SELF to adminPub)

        init {
            val infoJson =
                ZMSGGroupProtocol.serializeGroupInfo(
                    GroupInfo(
                        groupId = GROUP,
                        name = "Removed member",
                        creatorAddress = GroupPaidOnceFixture.SELF,
                        createdAt = Instant.ofEpochMilli(1_000L),
                        isActive = active,
                        creatorPublicKey = adminPub,
                    ),
                )
            infos[GROUP] = if (removed) removedMark(infoJson) else infoJson
            rosters[GROUP] =
                ZMSGGroupProtocol.serializeGroupMembers(
                    listOf(
                        GroupMember(
                            address = GroupPaidOnceFixture.SELF,
                            joinedAt = Instant.ofEpochMilli(1_000L),
                            status = MemberStatus.ACTIVE,
                            isAdmin = true,
                        ),
                        GroupMember(
                            address = K,
                            joinedAt = Instant.ofEpochMilli(1_000L),
                            status = if (active) MemberStatus.ACTIVE else MemberStatus.LEFT,
                        ),
                        GroupMember(
                            address = OTHER,
                            joinedAt = Instant.ofEpochMilli(1_000L),
                            status = MemberStatus.ACTIVE,
                            inviteStatus = InviteStatus.SENT,
                        ),
                    ),
                )
            keys["$GROUP|1"] = Base64.getEncoder().encodeToString(ByteArray(32).also { SecureRandom().nextBytes(it) })
            epochs[GROUP] = 1
        }

        fun draft(): String? = drafts[GROUP]

        /** [infoJson] with the "removed" mark processGroupKick writes for a group this phone was removed from. */
        private fun removedMark(infoJson: String): String {
            val json = JSONObject(infoJson)
            json.put("removed", true)
            return json.toString()
        }

        @Suppress("CyclomaticComplexMethod")
        val proxy: ZchatPreferences =
            Proxy.newProxyInstance(
                ZchatPreferences::class.java.classLoader,
                arrayOf(ZchatPreferences::class.java),
            ) { p, method, args ->
                fun arg(i: Int) = args!![i]
                when (method.name) {
                    "getAllGroupIds" -> infos.keys.toSet()
                    "getGroupInfo" -> infos[arg(0) as String]
                    "saveGroupInfo" -> {
                        infos[arg(0) as String] = arg(1) as String
                        null
                    }
                    "getGroupMembers" -> rosters[arg(0) as String]
                    "saveGroupMembers" -> {
                        rosters[arg(0) as String] = arg(1) as String
                        null
                    }
                    "saveGroupMembersDurably" -> {
                        rosters[arg(0) as String] = arg(1) as String
                        true
                    }
                    "isGroupSelfCreated" -> false
                    "isSelfAddress" -> arg(0) == K
                    "resolvePeerAddress" -> arg(0)
                    "getGroupKeyEpoch" -> epochs[arg(0) as String] ?: 0
                    "setGroupKeyEpoch" -> {
                        epochs[arg(0) as String] = arg(1) as Int
                        null
                    }
                    "getGroupKey" -> keys["${arg(0)}|${arg(1)}"]
                    "saveGroupKey" -> {
                        keys["${arg(0)}|${arg(1)}"] = arg(2) as String
                        null
                    }
                    "getGroupRemovalFloor" -> floors[arg(0) as String] ?: 0
                    "setGroupRemovalFloor" -> {
                        floors[arg(0) as String] = maxOf(floors[arg(0) as String] ?: 0, arg(1) as Int)
                        null
                    }
                    "getE2EPeerPublicKey" -> peerKeys[arg(0) as String]
                    "getE2EPrivateKey" -> null
                    "getE2EKeyVersion" -> 2
                    // OTHER is reachable free (so a send at the buggy base goes out over the relays); the admin is not.
                    "getPeerNostrPubkey" -> if (arg(0) == OTHER) OTHER_NOSTR else null
                    "getAllConversationPeerAddresses" -> emptySet<String>()
                    "hasConsentedToHandshakeSpend" -> true
                    "getGroupMessageSequence" -> 0L
                    "incrementGroupMessageSequence" -> 1L
                    "getGroupDraft" -> drafts[arg(0) as String]
                    "setGroupDraft" -> {
                        drafts[arg(0) as String] = arg(1) as String
                        null
                    }
                    "clearGroupDraft" -> {
                        drafts.remove(arg(0) as String)
                        null
                    }
                    "getGroupMessageSpendConsent" -> consents[arg(0) as String].orEmpty()
                    "setGroupMessageSpendConsent" -> {
                        @Suppress("UNCHECKED_CAST")
                        consents[arg(0) as String] = (arg(1) as Collection<String>).toSet()
                        true
                    }
                    "toString" -> "MemberKPrefs"
                    "hashCode" -> System.identityHashCode(p)
                    "equals" -> p === args?.get(0)
                    else ->
                        when (method.returnType) {
                            java.lang.Boolean.TYPE -> false
                            java.lang.Integer.TYPE -> 0
                            java.lang.Long.TYPE -> 0L
                            List::class.java -> emptyList<Any>()
                            Set::class.java -> emptySet<Any>()
                            Map::class.java -> emptyMap<Any, Any>()
                            else -> null
                        }
                }
            } as ZchatPreferences
    }

    private companion object {
        const val GROUP = GroupPaidOnceFixture.GROUP_ID
        const val K = GroupPaidOnceFixture.MEMBER_P
        const val OTHER = "usyntheticp6othermember00001"
        const val OTHER_NOSTR = "5d6d6d6d5d6d6d6d5d6d6d6d5d6d6d6d5d6d6d6d5d6d6d6d5d6d6d6d5d6d6d6d"
    }
}
