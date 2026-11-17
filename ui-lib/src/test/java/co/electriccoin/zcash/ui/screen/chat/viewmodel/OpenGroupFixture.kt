package co.electriccoin.zcash.ui.screen.chat.viewmodel

import co.electriccoin.zcash.ui.screen.chat.model.GroupMember
import co.electriccoin.zcash.ui.screen.chat.model.GroupMessageType
import co.electriccoin.zcash.ui.screen.chat.model.MemberStatus
import co.electriccoin.zcash.ui.screen.chat.model.ZMSGGroupProtocol
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant

/**
 * GR-OPEN (fold-specs/groups SPEC §1 and §2) — the all-Open group of the Fold E2 runs, for the removal tests.
 *
 * The creator's phone is the MA3 announcer phone ([MA3.adminPhone], [MA3.C]); its members hold NO key exchange with it
 * (no session, no E2E key either way) and each has a relay key ([MA3.NPUB]), as in an all-Open group. A member's phone
 * joined with the creator's plaintext relay invite ([plainMember]): the invite carries the group key as `group_key`,
 * sealed by the creator's relay identity, and the creator is a saved contact there, so it is joined at once.
 *
 * The sealed admin controls are written here by hand in the wire form the spec fixes (`"sig": ""`, the new key in
 * plaintext as `"gk"`), so the receiver tests do not depend on the sender side. All addresses, keys and ids are
 * synthetic; nothing here pays: every relay copy goes to [RelayNet], every on-chain memo to a recording seam.
 */
internal object OpenGroup {
    const val GID = MA3.GID
    const val C = MA3.C
    const val B = MA3.B
    const val X = MA3.X

    /** Another address of B's wallet (not a roster row, no alias): its own relay identity, as the HONOR's `…n62g`. */
    const val B2 = "usyntheticma3memberb000000002"

    /** Another address of X's wallet, likewise. */
    const val X2 = "usyntheticma3memberx000000002"

    /** A third member of the creator's roster (used where a row must share a relay key with X). */
    const val W = "usyntheticma3memberw000000001"

    val NPUB_B2 = "1".repeat(64)
    val NPUB_X2 = "2".repeat(64)
    val NPUB_W = "3".repeat(64)

    fun npub(address: String): String =
        when (address) {
            B2 -> NPUB_B2
            X2 -> NPUB_X2
            W -> NPUB_W
            // A synthetic member the MA3 phones do not know (K3's R) gets its own relay key, derived from its address.
            else -> MA3.NPUB[address] ?: sha256Hex(address)
        }

    private fun sha256Hex(text: String): String =
        java.security.MessageDigest
            .getInstance("SHA-256")
            .digest(text.toByteArray())
            .joinToString("") { "%02x".format(it) }

    /** The creator's plaintext relay invite (what 2.13.0 sends to a member it has no key exchange with). */
    fun plainInvite(
        keyEpoch: Int = 1,
        key: String = MA3.groupKey,
        inviter: String = C,
    ): String = ZMSGGroupProtocol.createGroupInviteCompact(GID, "Open group", inviter, keyEpoch, key, isSessionEncrypted = false)

    /** A sealed GROUP_KICK as the spec's wire fixes it: no signature, the new key in plaintext as `gk` (or none). */
    fun sealedKick(
        kicked: String,
        newEpoch: Int,
        gk: String?,
        kicker: String = C,
    ): String =
        "ZMSG:3.0:GROUP:GK:$GID:" +
            JSONObject()
                .put("kicked", kicked)
                .put("kicker", kicker)
                .put("new_epoch", newEpoch)
                .apply { gk?.let { put("gk", it) } }
                .put("sig", "")
                .toString()

    /** A sealed GROUP_KEY (a rotation, or an owed-key re-send): no signature, no enc_key, the key as `gk`. */
    fun sealedKey(
        epoch: Int,
        gk: String,
        signer: String = C,
        reason: String = "rotation",
    ): String =
        "ZMSG:3.0:GROUP:GY:$GID:" +
            JSONObject()
                .put("signer", signer)
                .put("epoch", epoch)
                .put("reason", reason)
                .put("gk", gk)
                .put("sig", "")
                .toString()

    /** A GROUP_MSG from [sender] under [key] at [epoch] (unsigned unless [sign] is given). */
    fun gm(
        key: String,
        sender: String,
        text: String,
        epoch: Int,
        seq: Long,
        sign: ((String) -> String)? = null,
    ): String = ZMSGGroupProtocol.createGroupMsgMessage(GID, seq, epoch, sender, text, ZMSGGroupProtocol.decodeGroupKey(key), sign)

    /**
     * The creator's phone with [members] ACTIVE, none with a key exchange, each with its relay key ([npub]). The
     * relay keys of [extraRelayKeys] (address to key) are stored too.
     */
    fun creator(
        members: List<String>,
        extraRelayKeys: Map<String, String> = emptyMap(),
    ): AnnouncerPhone {
        val admin = MA3.adminPhone(members.map { it to MemberStatus.ACTIVE })
        members.forEach { admin.base.nostrKeys[it] = npub(it) }
        extraRelayKeys.forEach { (address, key) -> admin.base.nostrKeys[address] = key }
        return admin
    }

    /**
     * A member's phone ([self]) that joined the group with the creator's plaintext relay invite at key [keyEpoch]. The
     * creator is a saved contact there, and its relay key is known. Returns the phone and its ChatViewModel.
     *
     * [test-change-ok: W-137 ZC-922 every invite waits for Join] Every invitation with a key waits for the user's Join,
     * the creator's too: the fixture taps Join when the invitation waits (the free relay reply goes then, as it went at
     * once before), so the returned phone has joined and no waiting record stays behind, before and after that rule.
     */
    suspend fun plainMember(
        self: String,
        keyEpoch: Int = 1,
        key: String = MA3.groupKey,
        joinedAtMs: Long = MA3.now() - 3_000_000L,
    ): Pair<AnnouncePhone, ChatViewModel> {
        val phone = AnnouncePhone(self)
        phone.nostrKeys[C] = MA3.NPUB.getValue(C)
        val chat = AnnounceDriver.chatVm(phone, HandshakeSeam(self))
        AutoSpendVm.setField(chat, "contactBook", SavedContacts(setOf(C)))
        MA3.relay(chat, plainInvite(keyEpoch, key), sender = C, timeMs = joinedAtMs)
        check(phone.infos[GID] != null) { "fixture: the plaintext relay invite did not create the group" }
        if (phone.needsJoin[GID] != null) {
            check(GroupInviteDriver.join(chat, GID)) { "fixture: Join did not send the member's reply" }
        }
        check(phone.needsJoin[GID] == null) { "fixture: the waiting record stayed after Join" }
        check(phone.keys["$GID|$keyEpoch"] == key) { "fixture: the invite's key was not stored" }
        return phone to chat
    }

    /** The stored group-message rows of [groupId] on a phone (raw JSON objects, in stored order). */
    fun rows(
        messages: Map<String, String>,
        groupId: String = GID,
    ): List<JSONObject> =
        messages[groupId]?.let { json -> JSONArray(json).let { a -> (0 until a.length()).map { a.getJSONObject(it) } } }.orEmpty()

    fun row(
        messages: Map<String, String>,
        text: String,
    ): JSONObject? = rows(messages).firstOrNull { it.optString("decrypted") == text }

    fun heldMark(
        messages: Map<String, String>,
        text: String,
    ): String? = row(messages, text)?.optString("held")?.takeIf { it.isNotEmpty() }

    /** Every relay copy of [type] published to [npub]. */
    fun published(
        net: RelayNet,
        type: GroupMessageType,
        npub: String,
    ): List<JSONObject> = net.ofType(type, npub).mapNotNull { memo -> ZMSGGroupProtocol.parsePayload(memo)?.let { JSONObject(it) } }

    fun member(
        address: String,
        status: MemberStatus = MemberStatus.ACTIVE,
        pendingKeyEpoch: Int? = null,
    ) = GroupMember(
        address = address,
        joinedAt = Instant.ofEpochMilli(MA3.now() - 3_600_000L),
        status = status,
        pendingKeyEpoch = pendingKeyEpoch,
    )

    /**
     * Kick [kicked] on the creator's screen: "Remove anyway" if the no-key question is asked (it is, before the fix),
     * and the cost accepted if one is quoted (none is, in these tests). @return whether the no-key question was asked.
     */
    suspend fun kick(
        vm: GroupViewModel,
        f: GroupPaidOnceFixture,
        kicked: String,
    ): Boolean {
        f.awaitNewJobs(vm) { vm.kickMember(GID, kicked) }
        val asked = vm.rekeyUnkeyableConfirmation.value != null
        if (asked) f.awaitNewJobs(vm) { vm.confirmRekeyUnkeyable() }
        if (vm.paidActionConfirmation.value != null) f.awaitNewJobs(vm) { vm.confirmPaidAction() }
        return asked
    }

    fun roster(phone: AnnouncePhone): List<GroupMember> = phone.roster(GID)
}

/** GR-OPEN — a chain of (transaction, memo) pairs for the group screen's history rescan. */
internal class OpenGroupChain(
    private val txs: List<Pair<co.electriccoin.zcash.ui.common.repository.Transaction, String>>,
) : co.electriccoin.zcash.ui.common.repository.TransactionRepository {
    override val transactions: kotlinx.coroutines.flow.Flow<List<co.electriccoin.zcash.ui.common.repository.Transaction>?> =
        kotlinx.coroutines.flow.flowOf(null)

    override suspend fun getMemos(transaction: co.electriccoin.zcash.ui.common.repository.Transaction): List<String> =
        listOf(txs.first { it.first.id == transaction.id }.second)

    override fun observeTransaction(txId: String): kotlinx.coroutines.flow.Flow<co.electriccoin.zcash.ui.common.repository.Transaction?> =
        kotlinx.coroutines.flow.flowOf(null)

    override fun observeTransactionsByMemo(memo: String): kotlinx.coroutines.flow.Flow<List<cash.z.ecc.android.sdk.model.TransactionId>?> =
        kotlinx.coroutines.flow.flowOf(null)

    override suspend fun getTransactions(): List<co.electriccoin.zcash.ui.common.repository.Transaction> = txs.map { it.first }

    override suspend fun getAllRecipientAddresses(transaction: co.electriccoin.zcash.ui.common.repository.Transaction): List<String> =
        emptyList()

    companion object {
        fun blockTime(n: Int): Instant = Instant.ofEpochMilli(MA3.now() - 1_000_000L + n * 1_000L)

        /** A received transaction [n] carrying [memo], mined at [height] (null: unmined). */
        fun received(
            n: Int,
            memo: String,
            height: Long?,
        ): Pair<co.electriccoin.zcash.ui.common.repository.Transaction, String> {
            val id = AnnounceDriver.txId(n)
            val time = blockTime(n)
            return co.electriccoin.zcash.ui.common.repository.ReceiveTransaction.Success(
                id = id,
                amount =
                    cash.z.ecc.android.sdk.model
                        .Zatoshi(1_000),
                timestamp = time,
                memoCount = 1,
                transactionOutputs = emptyList(),
                overview = ScanPassHarness.overview(id, height, time, sent = false, amount = 1_000),
                recipient = null,
            ) to memo
        }

        /** One of our own sent transactions [n] carrying [memo], mined at [height]. */
        fun sent(
            n: Int,
            memo: String,
            height: Long,
        ): Pair<co.electriccoin.zcash.ui.common.repository.Transaction, String> {
            val id = AnnounceDriver.txId(n)
            val time = blockTime(n)
            return co.electriccoin.zcash.ui.common.repository.SendTransaction.Success(
                id = id,
                amount =
                    cash.z.ecc.android.sdk.model
                        .Zatoshi(1_000),
                timestamp = time,
                memoCount = 1,
                fee =
                    cash.z.ecc.android.sdk.model
                        .Zatoshi(15_000),
                transactionOutputs = emptyList(),
                overview = ScanPassHarness.overview(id, height, time, sent = true, amount = 1_000),
                recipient = null,
            ) to memo
        }

        /**
         * ZC-493 — one of our own sent transactions [n] carrying [memo] that expired and was never mined, as
         * TransactionRepository lists it (SendTransaction.Failed, no mined height).
         */
        fun failed(
            n: Int,
            memo: String,
        ): Pair<co.electriccoin.zcash.ui.common.repository.Transaction, String> {
            val id = AnnounceDriver.txId(n)
            val time = blockTime(n)
            return co.electriccoin.zcash.ui.common.repository.SendTransaction.Failed(
                id = id,
                amount =
                    cash.z.ecc.android.sdk.model
                        .Zatoshi(1_000),
                timestamp = time,
                memoCount = 1,
                fee =
                    cash.z.ecc.android.sdk.model
                        .Zatoshi(15_000),
                transactionOutputs = emptyList(),
                overview = ScanPassHarness.overview(id, null, time, sent = true, amount = 1_000),
                recipient = null,
            ) to memo
        }

        /** ZC-493 — one of our own sent transactions [n] carrying [memo], not mined and not expired (it may still land). */
        fun pending(
            n: Int,
            memo: String,
        ): Pair<co.electriccoin.zcash.ui.common.repository.Transaction, String> {
            val id = AnnounceDriver.txId(n)
            val time = blockTime(n)
            return co.electriccoin.zcash.ui.common.repository.SendTransaction.Pending(
                id = id,
                amount =
                    cash.z.ecc.android.sdk.model
                        .Zatoshi(1_000),
                timestamp = time,
                memoCount = 1,
                fee =
                    cash.z.ecc.android.sdk.model
                        .Zatoshi(15_000),
                transactionOutputs = emptyList(),
                overview = ScanPassHarness.overview(id, null, time, sent = true, amount = 1_000),
                recipient = null,
            ) to memo
        }

        /** The history rescan of [vm] over [txs]. */
        suspend fun rescan(
            vm: GroupViewModel,
            txs: List<Pair<co.electriccoin.zcash.ui.common.repository.Transaction, String>>,
            groupId: String = OpenGroup.GID,
        ) {
            GroupViewModel::class.java
                .getDeclaredField("transactionRepository")
                .apply { isAccessible = true }
                .set(vm, OpenGroupChain(txs))
            vm.loadGroupMessagesFromHistory(groupId)
        }
    }
}
