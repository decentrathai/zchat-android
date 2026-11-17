package co.electriccoin.zcash.ui.screen.chat.model

import co.electriccoin.zcash.ui.screen.chat.crypto.E2EEncryption
import co.electriccoin.zcash.ui.screen.chat.datasource.ZchatPreferences
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.lang.reflect.Proxy
import java.time.Instant

/**
 * ZC-022 (KI-25-SPEC 3.6b R2, R3, R5, R7 and R8) — the pure rules of [GroupAdminCheck], as pair 1 ships them: R5's
 * table row by row, the lookup order of the contact that holds a group's creator key (R2), which the admin's target
 * (R3) is built on, R7's first source (the invitation that created the group) and the order of R8's seven refusals.
 *
 * The store is a small fake that answers only the reads the rules make and fails on every other call, so a rule that
 * starts to write fails here. Every address, key and id is synthetic.
 */
class GroupAdminCheckRulesTest {
    private companion object {
        const val GID = "zgrp_rulestest0000000000000a"
        const val SELF = "usyntheticrulesself000000001"
        const val X = "usyntheticrulescreator000001"
        const val X_ALIAS = "usyntheticrulescreatoralias1"

        // Sorted: A < B < C.
        const val A = "usyntheticrulescontacta00001"
        const val B = "usyntheticrulescontactb00001"
        const val C = "usyntheticrulescontactc00001"
        const val CREATOR_KEY = "synthetic-creator-public-key"
        const val OTHER_KEY = "synthetic-other-public-key"
        const val GROUP_KEY = "synthetic-group-key-epoch-1"
    }

    /** One phone's store: only what the rules read. */
    private class Store {
        var info: GroupInfo? = rulesGroup()
        var needsJoin: String? = null
        var selfCreated = false
        var plainKeyJoin = false
        val peerKeys = HashMap<String, String>()
        val ourKeys = HashSet<String>()
        val aliases = HashMap<String, String>()
        val mapped = LinkedHashSet<String>()
        val conversations = LinkedHashSet<String>()
        val calls = ArrayList<String>()
        val verified = HashSet<String>()
        val keyChanged = HashSet<String>()
        val awaitingAccept = HashSet<String>()
        val groupKeys = HashMap<Int, String>()

        val prefs: ZchatPreferences =
            Proxy.newProxyInstance(ZchatPreferences::class.java.classLoader, arrayOf(ZchatPreferences::class.java)) { p, method, args ->
                fun s(i: Int) = args!![i] as String
                if (method.name !in setOf("toString", "hashCode", "equals")) calls += method.name
                when (method.name) {
                    "getGroupInfo" -> info?.let { ZMSGGroupProtocol.serializeGroupInfo(it) }
                    "getGroupInviteNeedsJoin" -> needsJoin
                    "isGroupSelfCreated" -> selfCreated
                    "isSelfAddress" -> s(0) == SELF
                    "getGroupAnnounceState" -> AnnounceState(plainKeyJoin = plainKeyJoin)
                    "getE2EPeerPublicKey" -> peerKeys[s(0)]
                    "getE2EPrivateKey" -> if (s(0) in ourKeys) "synthetic-private-key" else null
                    "resolvePeerAddress" -> aliases[s(0)] ?: s(0)
                    "isE2EVerified" -> s(0) in verified
                    "isE2EKeyChanged" -> s(0) in keyChanged
                    "getInboundKexAwaitingAccept" -> if (s(0) in awaitingAccept) "synthetic-waiting-key" else null
                    "getRefusedKeyOffer" -> null
                    "getGroupKey" -> groupKeys[args!![1] as Int]
                    "getAllPeerToConvIdMappings" -> mapped.associateWith { "synthetic-conversation" }
                    "getAllConversationPeerAddresses" -> conversations.toSet()
                    "toString" -> "GroupAdminCheckRulesStore"
                    "hashCode" -> System.identityHashCode(p)
                    "equals" -> p === args?.get(0)
                    else -> throw UnsupportedOperationException("the admin rules must not call ZchatPreferences.${method.name}")
                }
            } as ZchatPreferences

        /** A finished chat at [address]: the contact's [key] and our own key pair. */
        fun chat(
            address: String,
            key: String,
        ) {
            peerKeys[address] = key
            ourKeys += address
            mapped += address
        }

        fun state(saved: Collection<String> = emptyList()): AdminKeyState = GroupAdminCheck.keyState(prefs, saved, GID)
    }

    // ------------------------------------------------------------------ R5, row 1

    @Test
    fun `row 1 - no group record is not applicable`() {
        val store = Store().apply { info = null }
        assertEquals(AdminKeyState.NOT_APPLICABLE, store.state())
    }

    @Test
    fun `row 1 - a group that is not active is not applicable, whatever its keys`() {
        val store = Store().apply { info = rulesGroup(active = false) }
        assertEquals(AdminKeyState.NOT_APPLICABLE, store.state())
    }

    @Test
    fun `row 1 - a group whose invite still waits for Join is not applicable`() {
        val store = Store().apply { needsJoin = "synthetic-held-accept-record" }
        assertEquals(AdminKeyState.NOT_APPLICABLE, store.state())
    }

    @Test
    fun `row 1 - a group this phone created is not applicable, by its mark and by its creator address`() {
        assertEquals(AdminKeyState.NOT_APPLICABLE, Store().apply { selfCreated = true }.state())
        assertEquals(AdminKeyState.NOT_APPLICABLE, Store().apply { info = rulesGroup(creator = SELF) }.state())
    }

    @Test
    fun `row 1 comes before the key rows`() {
        val store =
            Store().apply {
                info = rulesGroup(creatorKey = CREATOR_KEY, active = false)
                chat(B, CREATOR_KEY)
            }
        assertEquals(AdminKeyState.NOT_APPLICABLE, store.state())
    }

    // ------------------------------------------------------------------ R5, row 3

    @Test
    fun `row 3 - a stored creator key that a chat holds is OK`() {
        val store =
            Store().apply {
                info = rulesGroup(creatorKey = CREATOR_KEY)
                chat(B, CREATOR_KEY)
            }
        assertEquals(AdminKeyState.OK, store.state())
    }

    @Test
    fun `row 3 - it is OK when the holder is a contact with no key pair of ours, or only a saved contact`() {
        val noKeyPair =
            Store().apply {
                info = rulesGroup(creatorKey = CREATOR_KEY)
                peerKeys[B] = CREATOR_KEY
                conversations += B
            }
        assertEquals(AdminKeyState.OK, noKeyPair.state())

        val savedOnly =
            Store().apply {
                info = rulesGroup(creatorKey = CREATOR_KEY)
                peerKeys[C] = CREATOR_KEY
            }
        // KI-25 pair 2 (R14): a stored creator key that no chat in the address set holds reads ORPHANED.
        assertEquals("a holder outside the address set was found", AdminKeyState.ORPHANED, savedOnly.state())
        assertEquals(AdminKeyState.OK, savedOnly.state(saved = listOf(C)))
    }

    @Test
    fun `a stored creator key that no contact holds is not OK - ORPHANED since pair 2 (R14)`() {
        val store =
            Store().apply {
                info = rulesGroup(creatorKey = CREATOR_KEY)
                chat(B, OTHER_KEY)
                // Not even a key at the creator address rescues it: the stored key is the one that counts.
                chat(X, OTHER_KEY)
            }
        assertEquals(AdminKeyState.ORPHANED, store.state())
    }

    // ------------------------------------------------- R5, the extra row of pair 1, withdrawn by pair 3 (D1, ZC-598)

    @Test
    fun `extra row withdrawn - no stored creator key and a key at the creator address is UNKNOWN`() {
        val store = Store().apply { peerKeys[X] = OTHER_KEY }
        assertEquals(AdminKeyState.UNKNOWN, store.state())
    }

    @Test
    fun `extra row withdrawn - a key at the creator address's alias target is UNKNOWN too`() {
        val store =
            Store().apply {
                aliases[X] = X_ALIAS
                peerKeys[X_ALIAS] = OTHER_KEY
            }
        assertEquals(AdminKeyState.UNKNOWN, store.state())
    }

    @Test
    fun `extra row withdrawn - a key with no key pair of ours at an Open group's creator address leaves it NOT_APPLICABLE`() {
        val store =
            Store().apply {
                peerKeys[X] = OTHER_KEY
                plainKeyJoin = true
            }
        assertEquals(AdminKeyState.NOT_APPLICABLE, store.state())
    }

    // ------------------------------------------------------------ R5, rows 5 and 6

    @Test
    fun `row 5 - no stored creator key in a group joined with the creator's plaintext relay invite is not applicable`() {
        val store = Store().apply { plainKeyJoin = true }
        assertEquals(AdminKeyState.NOT_APPLICABLE, store.state())
    }

    @Test
    fun `row 5 needs that no creator key is stored`() {
        val store =
            Store().apply {
                info = rulesGroup(creatorKey = CREATOR_KEY)
                plainKeyJoin = true
            }
        // KI-25 pair 2 (R14): with a stored creator key that no chat holds the group reads ORPHANED.
        assertEquals(AdminKeyState.ORPHANED, store.state())
    }

    @Test
    fun `row 6 - no stored creator key and no key at the creator address is UNKNOWN, with a chat elsewhere or not`() {
        assertEquals(AdminKeyState.UNKNOWN, Store().state())
        assertEquals(AdminKeyState.UNKNOWN, Store().apply { chat(B, CREATOR_KEY) }.state(saved = listOf(B, C)))
    }

    @Test
    fun `reading the state only reads`() {
        val store =
            Store().apply {
                info = rulesGroup(creatorKey = CREATOR_KEY)
                chat(B, CREATOR_KEY)
            }
        store.state(saved = listOf(C))
        Store().state()
        val writers = listOf("save", "set", "delete", "clear", "remove", "mark", "record", "put", "increment", "import")
        assertEquals(emptyList<String>(), store.calls.filter { call -> writers.any { call.startsWith(it) } })
    }

    // ------------------------------------------------------- R2, the lookup order

    @Test
    fun `the address set is the conversations and the saved contacts, sorted and without doubles`() {
        val store =
            Store().apply {
                mapped += C
                conversations += listOf(C, A)
            }
        assertEquals(listOf(A, B, C), GroupAdminCheck.contactAddresses(store.prefs, listOf(B, A)))
    }

    @Test
    fun `the holder is looked up at the creator address, then its alias target, then the sorted address set`() {
        val store =
            Store().apply {
                info = rulesGroup(creatorKey = CREATOR_KEY)
                aliases[X] = X_ALIAS
                chat(C, CREATOR_KEY)
                chat(A, CREATOR_KEY)
            }
        val info = store.info!!
        assertEquals("the first of the sorted set", A, GroupAdminCheck.creatorHolder(store.prefs, emptyList(), info))

        store.peerKeys[X_ALIAS] = CREATOR_KEY
        assertEquals("the alias target comes before the set", X_ALIAS, GroupAdminCheck.creatorHolder(store.prefs, emptyList(), info))

        store.peerKeys[X] = CREATOR_KEY
        assertEquals("the creator address comes first", X, GroupAdminCheck.creatorHolder(store.prefs, emptyList(), info))
    }

    @Test
    fun `a contact that holds another key is never the holder`() {
        val store =
            Store().apply {
                info = rulesGroup(creatorKey = CREATOR_KEY)
                chat(X, OTHER_KEY)
                chat(A, OTHER_KEY)
                chat(B, CREATOR_KEY)
            }
        assertEquals(B, GroupAdminCheck.creatorHolder(store.prefs, emptyList(), store.info!!))
        assertEquals(B, GroupAdminCheck.creatorSession(store.prefs, emptyList(), store.info!!))
    }

    @Test
    fun `the session is the first holder for which this phone has its own key pair`() {
        val store =
            Store().apply {
                info = rulesGroup(creatorKey = CREATOR_KEY)
                // A holds the key and our key pair for it is gone; C is a finished chat with the same key.
                peerKeys[A] = CREATOR_KEY
                chat(C, CREATOR_KEY)
            }
        val info = store.info!!
        assertEquals(A, GroupAdminCheck.creatorHolder(store.prefs, listOf(A), info))
        assertEquals(C, GroupAdminCheck.creatorSession(store.prefs, listOf(A), info))

        store.ourKeys.clear()
        assertNull("a session without our own key pair", GroupAdminCheck.creatorSession(store.prefs, listOf(A), info))
        assertEquals("losing our key pair hid the holder", A, GroupAdminCheck.creatorHolder(store.prefs, listOf(A), info))
    }

    @Test
    fun `no stored creator key means no holder and no session, and a given key is looked up instead`() {
        val store = Store().apply { chat(B, CREATOR_KEY) }
        val info = store.info!!
        assertNull(GroupAdminCheck.creatorHolder(store.prefs, emptyList(), info))
        assertNull(GroupAdminCheck.creatorSession(store.prefs, emptyList(), info))
        assertEquals(B, GroupAdminCheck.creatorHolder(store.prefs, emptyList(), info, CREATOR_KEY))
        assertEquals(B, GroupAdminCheck.creatorSession(store.prefs, emptyList(), info, CREATOR_KEY))
    }

    // ------------------------------------------------------- R3, the admin's target

    @Test
    fun `requests to the admin go to the session, else to the holder, else to the stored creator address`() {
        val store =
            Store().apply {
                info = rulesGroup(creatorKey = CREATOR_KEY)
                peerKeys[A] = CREATOR_KEY
                chat(C, CREATOR_KEY)
            }
        val info = store.info!!
        assertEquals(C, GroupAdminCheck.adminTarget(store.prefs, listOf(A), info))

        store.ourKeys.clear()
        assertEquals(A, GroupAdminCheck.adminTarget(store.prefs, listOf(A), info))

        store.peerKeys.clear()
        assertEquals(X, GroupAdminCheck.adminTarget(store.prefs, listOf(A), info))
        assertEquals("a group with no stored creator key", X, GroupAdminCheck.adminTarget(store.prefs, listOf(A), rulesGroup()))
    }

    // ------------------------------------------------- R7, source 1: the invitation

    /** The session this phone holds with [address] (synthetic: bytes made from the address text). */
    private fun session(address: String): ByteArray = ByteArray(32) { (address.hashCode() + it).toByte() }

    private val sessions: (String) -> ByteArray? = { address -> session(address) }

    /** A compact `GROUP_INVITE` for [groupId] whose `k2` is [groupKey] wrapped under the session with [from]. */
    private fun invite(
        from: String,
        groupKey: String = GROUP_KEY,
        keyEpoch: Int = 1,
        groupId: String = GID,
    ): String =
        "ZMSG:3.0:GROUP:GI:$groupId:" +
            JSONObject()
                .put("name", "Synthetic rules group")
                .put("inviter", X)
                .put("key_epoch", keyEpoch)
                .put("k2", E2EEncryption.encrypt(groupKey, session(from)))
                .toString()

    /** A drifted group (no creator key stored, none at the creator address) with the group key stored at epoch 1. */
    private fun driftedStore(): Store = Store().apply { groupKeys[1] = GROUP_KEY }

    private fun Store.invitationCandidate(vararg memos: String): String? =
        GroupAdminCheck.invitationCandidate(prefs, emptyList(), info!!, memos.toList(), sessions)

    @Test
    fun `R7 - the finished chat whose session unwraps the creating invite to the group's key is the candidate`() {
        val store =
            driftedStore().apply {
                chat(A, OTHER_KEY)
                chat(B, CREATOR_KEY)
            }
        assertEquals(B, store.invitationCandidate(invite(from = B)))
    }

    @Test
    fun `R7 - no invite, an invite for another group and an invite without a wrapped key give no candidate`() {
        val store = driftedStore().apply { chat(B, CREATOR_KEY) }
        assertNull(store.invitationCandidate())
        assertNull(store.invitationCandidate(ZMSGGroupProtocol.createGroupRosterQueryMessage(GID)))
        assertNull(store.invitationCandidate(invite(from = B, groupId = "zgrp_rulestest0000000000000b")))
        assertNull(store.invitationCandidate("ZMSG:3.0:GROUP:GI:$GID:" + JSONObject().put("name", "n").put("inviter", X)))
    }

    @Test
    fun `R7 - an invite that unwraps to another key than the group's gives no candidate`() {
        val store = driftedStore().apply { chat(B, CREATOR_KEY) }
        assertNull(store.invitationCandidate(invite(from = B, groupKey = "synthetic-other-group-key")))
    }

    @Test
    fun `R7 - the key is the one stored at the invite's epoch, else the record's`() {
        val atEpoch =
            driftedStore().apply {
                chat(B, CREATOR_KEY)
                groupKeys[2] = "synthetic-group-key-epoch-2"
            }
        assertNull("the invite's epoch decides which stored key counts", atEpoch.invitationCandidate(invite(from = B, keyEpoch = 2)))

        val fromRecord =
            Store().apply {
                info = rulesGroup().copy(groupKey = GROUP_KEY)
                chat(B, CREATOR_KEY)
            }
        assertEquals(B, fromRecord.invitationCandidate(invite(from = B)))
    }

    @Test
    fun `R7 - two chats with different keys give no candidate and one contact under two addresses gives the first`() {
        val two =
            driftedStore().apply {
                chat(A, OTHER_KEY)
                chat(B, CREATOR_KEY)
            }
        assertNull(two.invitationCandidate(invite(from = A), invite(from = B)))

        val one =
            driftedStore().apply {
                chat(B, CREATOR_KEY)
                chat(C, CREATOR_KEY)
            }
        assertEquals(B, one.invitationCandidate(invite(from = C), invite(from = B)))
    }

    @Test
    fun `R7 - only finished chats are tried - a chat request still waiting and a chat without our key pair are not`() {
        val waiting =
            driftedStore().apply {
                chat(B, CREATOR_KEY)
                awaitingAccept += B
            }
        assertNull(waiting.invitationCandidate(invite(from = B)))

        val noKeyPair =
            driftedStore().apply {
                chat(B, CREATOR_KEY)
                ourKeys -= B
            }
        assertNull(noKeyPair.invitationCandidate(invite(from = B)))
    }

    @Test
    fun `R7 - a pending key change and the verified mark do not change the candidate, and are told with it`() {
        val store =
            driftedStore().apply {
                chat(B, CREATOR_KEY)
                keyChanged += B
            }
        assertEquals(B, store.invitationCandidate(invite(from = B)))
        val told = GroupAdminCheck.candidate(store.prefs, B, "Synthetic name", AdminCandidateSource.INVITATION)
        assertEquals(AdminCandidate(B, "Synthetic name", AdminCandidateSource.INVITATION, true, true, false), told)

        store.keyChanged.clear()
        store.verified += B
        assertEquals(B, store.invitationCandidate(invite(from = B)))
        val ready = GroupAdminCheck.candidate(store.prefs, B, null, AdminCandidateSource.INVITATION)
        assertEquals(AdminCandidate(B, null, AdminCandidateSource.INVITATION, true, false, true), ready)
    }

    // ------------------------------------------------------ R8, the refusal order

    @Test
    fun `R8 - the seven checks are made in order and the first that fails is the refusal`() {
        // Every check fails at once: the group needs no confirmation, B is not the recorded chat, is an own address,
        // has no finished chat, has a key change pending, is not verified and holds another key than the one shown.
        val store =
            Store().apply {
                info = rulesGroup(creatorKey = CREATOR_KEY)
                chat(A, CREATOR_KEY)
                peerKeys[SELF] = OTHER_KEY
                keyChanged += SELF
            }

        fun refusal(recorded: String?) = GroupAdminCheck.confirmRefusal(store.prefs, emptyList(), GID, SELF, CREATOR_KEY, recorded)

        assertEquals(AdminConfirmRefusal.NOT_NEEDED, refusal(recorded = A))
        // 1 passes: no chat holds the stored creator key any more.
        store.peerKeys[A] = OTHER_KEY
        assertEquals(AdminConfirmRefusal.NOT_THE_RECORDED_CHAT, refusal(recorded = A))
        assertEquals(AdminConfirmRefusal.NOT_THE_RECORDED_CHAT, refusal(recorded = null))
        // 2 passes: the records name this address.
        assertEquals(AdminConfirmRefusal.OWN_ADDRESS, refusal(recorded = SELF))
    }

    @Test
    fun `R8 - after the own-address check - secure chat, key change, verified, the key shown, then no refusal`() {
        val store =
            driftedStore().apply {
                peerKeys[B] = OTHER_KEY
                keyChanged += B
            }

        fun refusal() = GroupAdminCheck.confirmRefusal(store.prefs, emptyList(), GID, B, CREATOR_KEY, B)

        assertEquals("no key pair of ours", AdminConfirmRefusal.NOT_A_SECURE_CHAT, refusal())
        store.ourKeys += B
        store.awaitingAccept += B
        assertEquals("a chat request still waiting", AdminConfirmRefusal.NOT_A_SECURE_CHAT, refusal())
        store.awaitingAccept.clear()
        assertEquals(AdminConfirmRefusal.KEY_CHANGED, refusal())
        store.keyChanged.clear()
        assertEquals(AdminConfirmRefusal.NOT_VERIFIED, refusal())
        store.verified += B
        assertEquals(AdminConfirmRefusal.KEY_DIFFERS, refusal())
        store.peerKeys[B] = CREATOR_KEY
        assertNull(refusal())
    }
}

private fun rulesGroup(
    creator: String = "usyntheticrulescreator000001",
    creatorKey: String? = null,
    active: Boolean = true,
): GroupInfo =
    GroupInfo(
        groupId = "zgrp_rulestest0000000000000a",
        name = "Synthetic rules group",
        creatorAddress = creator,
        createdAt = Instant.ofEpochMilli(1_790_000_000_000L),
        isActive = active,
        creatorPublicKey = creatorKey,
    )
