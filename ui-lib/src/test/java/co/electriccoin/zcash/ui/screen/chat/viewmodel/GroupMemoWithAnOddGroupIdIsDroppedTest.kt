package co.electriccoin.zcash.ui.screen.chat.viewmodel

import co.electriccoin.zcash.ui.screen.chat.datasource.ZchatPreferences
import co.electriccoin.zcash.ui.screen.chat.model.GroupInfo
import co.electriccoin.zcash.ui.screen.chat.model.GroupMember
import co.electriccoin.zcash.ui.screen.chat.model.MemberStatus
import co.electriccoin.zcash.ui.screen.chat.model.ZMSGConstants
import co.electriccoin.zcash.ui.screen.chat.model.ZMSGGroupProtocol
import co.electriccoin.zcash.ui.screen.chat.viewmodel.GroupInviteDriver.GROUP_KEY
import co.electriccoin.zcash.ui.screen.chat.viewmodel.GroupInviteDriver.heldInMemory
import co.electriccoin.zcash.ui.screen.chat.viewmodel.GroupInviteDriver.plaintextInvite
import co.electriccoin.zcash.ui.screen.chat.viewmodel.HandshakeHarness.awaitIdle
import co.electriccoin.zcash.ui.screen.chat.viewmodel.HandshakeHarness.chatViewModelWith
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
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.security.SecureRandom
import java.time.Instant
import java.util.Base64
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

/**
 * ZC-708 — a group memo names its group by any text.
 *
 * The group id is the second field of a group memo. The app makes ids of one shape only ("zgrp_" and 24 lowercase hex
 * digits, [GroupInfo.generateGroupId], the same on v2.12.4), but the handlers take whatever the memo says: a group is
 * stored, marked and later deleted under an id that is the name, or the start of the name, of something else in the
 * same stores.
 *
 * Wanted: a group memo whose id does not have the shape the app makes is dropped before any handler runs, for every
 * group message type. Nothing is stored, marked, queued or sent for it.
 *
 * The memos are handed to the real ChatViewModel.processGroupMessage, as the history scan and the relays hand them
 * over. The phone is [OddIdPhone]: the stranger-invite fake with a roster store and a log of every store call. All
 * addresses, keys, ids and txids are synthetic.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class GroupMemoWithAnOddGroupIdIsDroppedTest {
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
        phone: OddIdPhone,
        seam: HandshakeSeam,
    ): ChatViewModel =
        chatViewModelWith(phone.proxy, seam).also { vm ->
            AutoSpendVm.wireBootstrapFields(vm)
            AutoSpendVm.setField(vm, "contactBook", SavedContacts(emptySet()))
            awaitIdle(vm)
        }

    private fun memo(
        type: String,
        groupId: String,
        payload: String,
    ): String = "${ZMSGConstants.Prefixes.GROUP}$type:$groupId:$payload"

    /** Nothing about [groupId] was stored, marked, queued or sent since [mark], and the roster store is as [rosters]. */
    private fun assertDropped(
        phone: OddIdPhone,
        seam: HandshakeSeam,
        mark: Int,
        rosters: Map<String, String>,
        groupId: String,
        what: String,
    ) {
        val shown = if (groupId.length > 40) groupId.take(40) + "…" else groupId
        assertEquals("$what ('$shown'): the store was written", emptyList<String>(), phone.writesSince(mark))
        assertEquals("$what ('$shown'): the roster store changed", rosters, HashMap(phone.rosters))
        assertFalse("$what ('$shown'): a Join mark was stored", phone.inner.needsJoin.containsKey(groupId))
        assertFalse("$what ('$shown'): a reply is held in memory", heldInMemory(groupId))
        assertEquals("$what ('$shown'): a payment was proposed", 0, seam.proposalRequests.get())
        assertEquals("$what ('$shown'): a payment was submitted", 0, seam.submits.get())
        assertEquals("$what ('$shown'): a free relay reply was tried", 0, phone.inner.nostrLookups.get())
    }

    // ---- invitations ---------------------------------------------------------------------------------------------

    @Test
    fun `a stranger's plaintext invitation under the id zgrp stores nothing`() =
        runBlocking {
            val stranger = SyntheticUnifiedAddress.mainnet(seed = 70801)
            val phone = OddIdPhone()
            val seam = HandshakeSeam(stranger)
            val vm = screen(phone, seam)
            val mark = phone.mark()
            val rosters = HashMap(phone.rosters)

            AnnounceDriver.receive(vm, memo("GI", "zgrp", plaintextInvite(stranger)), Instant.EPOCH, AnnounceDriver.txId(1))
            awaitIdle(vm)

            assertFalse("a group record was stored under 'zgrp'", phone.groups.containsKey("zgrp"))
            assertFalse("'zgrp' is in the group list", "zgrp" in phone.proxy.getAllGroupIds())
            assertFalse("an announce state was written", "saveGroupAnnounceState" in phone.callsSince(mark))
            assertDropped(phone, seam, mark, rosters, "zgrp", "a stranger's invitation")
        }

    @Test
    fun `an invitation under any id the app could not have made stores nothing`() =
        runBlocking {
            val stranger = SyntheticUnifiedAddress.mainnet(seed = 70802)
            val failures = mutableListOf<String>()
            for ((i, id) in ODD_IDS.withIndex()) {
                AutoSpendVm.resetProcessState()
                val phone = OddIdPhone()
                phone.rosters[GP1_ENTRY] = GP1_VALUE
                val seam = HandshakeSeam(stranger)
                val vm = screen(phone, seam)
                val mark = phone.mark()
                val rosters = HashMap(phone.rosters)

                AnnounceDriver.receive(vm, memo("GI", id, plaintextInvite(stranger)), Instant.EPOCH, AnnounceDriver.txId(10 + i))
                awaitIdle(vm)

                try {
                    assertFalse("a group record was stored", phone.groups.containsKey(id))
                    assertEquals("the members store's $GP1_ENTRY entry changed", GP1_VALUE, phone.rosters[GP1_ENTRY])
                    assertDropped(phone, seam, mark, rosters, id, "an invitation")
                } catch (e: AssertionError) {
                    failures += "id ${i + 1} of ${ODD_IDS.size} (${id.length} characters): ${e.message}"
                }
            }
            assertEquals("invitations with an odd id were acted on", emptyList<String>(), failures)
        }

    @Test
    fun `an invitation named like another group's message-spend consent leaves that consent as it was`() =
        runBlocking {
            val stranger = SyntheticUnifiedAddress.mainnet(seed = 70803)
            val consentEntry = "group_msg_spend_consent_$REAL_GROUP"
            val phone = OddIdPhone()
            phone.rosters[consentEntry] = CONSENT_VALUE
            val seam = HandshakeSeam(stranger)
            val vm = screen(phone, seam)
            val mark = phone.mark()
            val rosters = HashMap(phone.rosters)

            AnnounceDriver.receive(vm, memo("GI", consentEntry, plaintextInvite(stranger)), Instant.EPOCH, AnnounceDriver.txId(2))
            awaitIdle(vm)

            assertFalse("a group record was stored under a consent entry's name", phone.groups.containsKey(consentEntry))
            assertEquals("the real group's message-spend consent entry was overwritten", CONSENT_VALUE, phone.rosters[consentEntry])
            assertDropped(phone, seam, mark, rosters, consentEntry, "an invitation named like a consent entry")
        }

    @Test
    fun `an invitation under the id zgrp from an accepted Open contact over the relays joins nothing`() =
        runBlocking {
            val contact = SyntheticUnifiedAddress.mainnet(seed = 70804)
            val phone = OddIdPhone()
            val seam = HandshakeSeam(contact)
            val vm = screen(phone, seam)
            val mark = phone.mark()
            val rosters = HashMap(phone.rosters)

            // Seal-authenticated sender, no relay key of theirs on this phone: the "I joined" reply cannot go free.
            AnnounceDriver.receive(
                vm,
                memo("GI", "zgrp", plaintextInvite(contact)),
                Instant.EPOCH,
                sender = contact,
                eventId = "synthetic-zc708-relay-invite",
            )
            awaitIdle(vm)

            assertFalse("the group was joined", phone.groups.containsKey("zgrp"))
            assertDropped(phone, seam, mark, rosters, "zgrp", "a contact's relay invitation")
        }

    @Test
    fun `an invitation under the id zgrp whose key cannot be unwrapped stores nothing`() =
        runBlocking {
            val stranger = SyntheticUnifiedAddress.mainnet(seed = 70805)
            val phone = OddIdPhone()
            val seam = HandshakeSeam(stranger)
            val vm = screen(phone, seam)
            val mark = phone.mark()
            val rosters = HashMap(phone.rosters)
            val nobodysSession = ByteArray(32).also { SecureRandom().nextBytes(it) }

            AnnounceDriver.receive(
                vm,
                memo("GI", "zgrp", GroupInviteDriver.sessionInvite(stranger, nobodysSession)),
                Instant.EPOCH,
                AnnounceDriver.txId(3),
            )
            awaitIdle(vm)

            assertFalse("a group with no key was stored under 'zgrp'", phone.groups.containsKey("zgrp"))
            assertDropped(phone, seam, mark, rosters, "zgrp", "an invitation with a key nobody can unwrap")
        }

    // ---- every other type, at a record an earlier build may hold under an odd id ---------------------------------

    private fun otherType(
        what: String,
        overRelays: Boolean,
        build: (member: String, admin: String) -> String,
    ) = runBlocking {
        val admin = SyntheticUnifiedAddress.mainnet(seed = 70810)
        val member = SyntheticUnifiedAddress.mainnet(seed = 70811)
        val phone = OddIdPhone()
        phone.seedGroup("zgrp", admin, member)
        val seam = HandshakeSeam(admin)
        val vm = screen(phone, seam)
        val mark = phone.mark()
        val rosters = HashMap(phone.rosters)
        val infoBefore = phone.groups["zgrp"]

        if (overRelays) {
            AnnounceDriver.receive(vm, build(member, admin), Instant.EPOCH, sender = member, eventId = "synthetic-zc708-$what")
        } else {
            AnnounceDriver.receive(vm, build(member, admin), Instant.EPOCH, AnnounceDriver.txId(20))
        }
        awaitIdle(vm)

        assertEquals("$what: the stored record changed", infoBefore, phone.groups["zgrp"])
        assertEquals("$what ('zgrp'): the store was written", emptyList<String>(), phone.writesSince(mark))
        assertEquals("$what ('zgrp'): the roster store changed", rosters, HashMap(phone.rosters))
        assertEquals("$what ('zgrp'): a payment was proposed", 0, seam.proposalRequests.get())
        assertEquals("$what ('zgrp'): a payment was submitted", 0, seam.submits.get())
    }

    @Test
    fun `a group message under an odd id writes nothing`() =
        otherType("message", overRelays = true) { member, _ ->
            ZMSGGroupProtocol.createGroupMsgMessage("zgrp", 1L, 0, member, "synthetic text", Base64.getDecoder().decode(GROUP_KEY))
        }

    @Test
    fun `an accept under an odd id writes nothing`() =
        otherType("accept", overRelays = true) { member, _ ->
            ZMSGGroupProtocol.createGroupAcceptMessage("zgrp", member, "")
        }

    @Test
    fun `a leave under an odd id writes nothing`() =
        otherType("leave", overRelays = true) { member, _ ->
            ZMSGGroupProtocol.createGroupLeaveMessage("zgrp", member)
        }

    @Test
    fun `a removal under an odd id writes nothing`() =
        otherType("removal", overRelays = false) { member, admin ->
            ZMSGGroupProtocol.createGroupKickMessage("zgrp", member, admin, 1, null, "c3ludGhldGlj")
        }

    @Test
    fun `a key change under an odd id writes nothing`() =
        otherType("key", overRelays = false) { _, admin ->
            ZMSGGroupProtocol.createGroupKeyMessage("zgrp", admin, 1, "c3ludGhldGlj", "c3ludGhldGlj")
        }

    @Test
    fun `a key request under an odd id writes nothing`() =
        otherType("key request", overRelays = true) { member, _ ->
            ZMSGGroupProtocol.createGroupKeyRequestMessage("zgrp", member, 0, "")
        }

    // ---- guards (green before and after) --------------------------------------------------------------------------

    @Test
    fun `guard - a member announce and a roster query under an odd id are dropped as today`() =
        runBlocking {
            val admin = SyntheticUnifiedAddress.mainnet(seed = 70820)
            val member = SyntheticUnifiedAddress.mainnet(seed = 70821)
            for ((what, text) in listOf(
                "member announce" to memo("GP", "zgrp", """{"v":1,"o":"A","m":"${"a".repeat(40)}","n":1,"e":0,"s":"c3ludGhldGlj"}"""),
                "roster query" to ZMSGGroupProtocol.createGroupRosterQueryMessage("zgrp"),
            )) {
                AutoSpendVm.resetProcessState()
                val phone = OddIdPhone()
                phone.seedGroup("zgrp", admin, member)
                val seam = HandshakeSeam(admin)
                val vm = screen(phone, seam)
                val mark = phone.mark()
                val rosters = HashMap(phone.rosters)

                AnnounceDriver.receive(vm, text, Instant.EPOCH, sender = admin, eventId = "synthetic-zc708-$what")
                awaitIdle(vm)

                assertEquals("$what: the store was written", emptyList<String>(), phone.writesSince(mark))
                assertEquals("$what: the roster store changed", rosters, HashMap(phone.rosters))
                assertEquals("$what: a payment was proposed", 0, seam.proposalRequests.get())
            }
        }

    @Test
    fun `guard - an invitation under an id the app makes is stored and waits for Join exactly as before`() =
        runBlocking {
            val stranger = SyntheticUnifiedAddress.mainnet(seed = 70830)
            val phone = OddIdPhone()
            val seam = HandshakeSeam(stranger)
            val vm = screen(phone, seam)
            val mark = phone.mark()

            AnnounceDriver.receive(vm, memo("GI", REAL_GROUP, plaintextInvite(stranger)), Instant.EPOCH, AnnounceDriver.txId(30))
            awaitIdle(vm)

            assertTrue("the group must be stored as today", phone.groups.containsKey(REAL_GROUP))
            assertTrue("its roster must be stored as today", phone.rosters.containsKey(REAL_GROUP))
            assertTrue("a stranger's invitation must wait for Join", phone.inner.needsJoin.containsKey(REAL_GROUP))
            assertTrue("the log must see the store calls", "saveGroupInfo" in phone.writesSince(mark))
            assertEquals("nothing is paid before Join", 0, seam.proposalRequests.get())
        }

    private companion object {
        const val REAL_GROUP = "zgrp_0a1b2c3d4e5f6071829370aa"
        const val GP1_ENTRY = "group_gp1_capable"
        const val GP1_VALUE = "synthetic-gp1-capable-set"
        const val CONSENT_VALUE = "synthetic-consent-set"

        /** Empty, one hex digit short, one too many, upper-case hex, a fixed entry of the members store, very long. */
        val ODD_IDS =
            listOf(
                "",
                "zgrp_" + "0a1b2c3d4e5f60718293a4b",
                "zgrp_" + "0a1b2c3d4e5f60718293a4b5c",
                "zgrp_" + "0A1B2C3D4E5F60718293A4B5",
                GP1_ENTRY,
                "zgrp_" + "a".repeat(395),
            )
    }
}

/**
 * [StrangerInvitePrefs] with a roster store ([rosters], keyed like the real members store: by the bare group id) and a
 * log of every store call by name. [writesSince] is the calls after a [mark] that are not plain reads.
 */
internal class OddIdPhone(
    consent: Boolean = true,
) {
    val inner = StrangerInvitePrefs(consent)

    /** The stored group records, by group id. */
    val groups: ConcurrentHashMap<String, String> get() = inner.base.groupInfo

    /** The stored contact keys, by address. */
    val peerKeys: ConcurrentHashMap<String, String> get() = inner.base.peerKeys
    val rosters = ConcurrentHashMap<String, String>()
    private val keys = ConcurrentHashMap<String, String>()
    private val log: MutableList<String> = Collections.synchronizedList(mutableListOf())

    val proxy: ZchatPreferences =
        Proxy.newProxyInstance(
            ZchatPreferences::class.java.classLoader,
            arrayOf(ZchatPreferences::class.java),
        ) { p, method, args ->
            when (method.name) {
                "toString" -> "OddIdPhone"
                "hashCode" -> System.identityHashCode(p)
                "equals" -> p === args?.get(0)
                else -> {
                    log += method.name
                    when (method.name) {
                        "saveGroupMembers" -> {
                            rosters[args!![0] as String] = args[1] as String
                            null
                        }
                        "getGroupMembers" -> rosters[args!![0] as String]
                        "getAllGroupIds" -> groups.keys.toSet()
                        "getGroupKey" -> keys["${args!![0]}_${args[1]}"]
                        else ->
                            try {
                                method.invoke(inner.proxy, *(args ?: emptyArray()))
                            } catch (e: InvocationTargetException) {
                                throw e.targetException
                            }
                    }
                }
            }
        } as ZchatPreferences

    fun mark(): Int = log.size

    fun callsSince(mark: Int): List<String> = synchronized(log) { log.drop(mark) }

    fun writesSince(mark: Int): List<String> = callsSince(mark).filterNot(::isRead)

    /** An active group under [groupId] as an earlier build may have stored it: [admin] created it, we and [member] are in. */
    fun seedGroup(
        groupId: String,
        admin: String,
        member: String,
    ) {
        groups[groupId] =
            ZMSGGroupProtocol.serializeGroupInfo(
                GroupInfo(
                    groupId = groupId,
                    name = "Synthetic odd-id group",
                    creatorAddress = admin,
                    createdAt = Instant.EPOCH,
                    groupKey = GroupInviteDriver.GROUP_KEY,
                ),
            )
        rosters[groupId] =
            ZMSGGroupProtocol.serializeGroupMembers(
                listOf(
                    GroupMember(address = admin, joinedAt = Instant.EPOCH, status = MemberStatus.ACTIVE, isAdmin = true),
                    GroupMember(address = HandshakeHarness.SELF, joinedAt = Instant.EPOCH, status = MemberStatus.ACTIVE),
                    GroupMember(address = member, joinedAt = Instant.EPOCH, status = MemberStatus.INVITED),
                ),
            )
        keys["${groupId}_0"] = GroupInviteDriver.GROUP_KEY
    }

    private fun isRead(name: String): Boolean =
        !name.startsWith("getOrCreate") && READ_PREFIXES.any { name.startsWith(it) }

    private companion object {
        val READ_PREFIXES = listOf("get", "is", "has", "resolve", "find", "peek", "count", "can", "may", "should", "walletScope")
    }
}
