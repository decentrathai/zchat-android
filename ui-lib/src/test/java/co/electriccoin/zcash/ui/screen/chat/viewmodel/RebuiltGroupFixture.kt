package co.electriccoin.zcash.ui.screen.chat.viewmodel

import co.electriccoin.zcash.ui.screen.chat.crypto.E2EEncryption
import co.electriccoin.zcash.ui.screen.chat.model.GroupMessageType
import co.electriccoin.zcash.ui.screen.chat.model.ZMSGGroupProtocol
import co.electriccoin.zcash.ui.screen.chat.viewmodel.MA3.C
import co.electriccoin.zcash.ui.screen.chat.viewmodel.MA3.GID
import co.electriccoin.zcash.ui.screen.chat.viewmodel.MA3.NPUB
import org.json.JSONObject
import java.time.Instant

/**
 * ZC-757 — the restored creator's phone of the MA3 group and the ways to drive it, for the cases built after
 * RebuiltGroupMakesANewKeyBeforeItSendsTest (which keeps its own copies). All addresses, keys, ids and times are synthetic.
 */
internal object RebuiltGroupFixture {
    /** 2027-01-15, as milliseconds. */
    const val NOW_MS = 1_800_000_000_000L

    /** Sets the phone clock and the newest block time ZC-757 reads ([nowMs] null: the real clock). */
    fun setClock(
        nowMs: Long?,
        newestBlockMs: Long,
    ) {
        val cls = Class.forName("co.electriccoin.zcash.ui.screen.chat.model.RebuiltGroupKey")
        val instance = cls.getField("INSTANCE").get(null)
        val clock: () -> Long = if (nowMs == null) ({ System.currentTimeMillis() }) else ({ nowMs })
        cls.getMethod("setClock", Function0::class.java).invoke(instance, clock)
        cls.getMethod("setNewestMinedTxMs", Long::class.javaPrimitiveType).invoke(instance, newestBlockMs)
    }

    /** [member]'s phone in the group at [epoch], linked with [admin], its relay key known to [admin]. */
    fun member(
        member: String,
        admin: AnnouncerPhone,
        epoch: Int = 1,
    ): AnnouncerPhone = MA3.memberPhone(member, admin, epoch).also { admin.base.nostrKeys[member] = NPUB.getValue(member) }

    /** Our own invitation to [to] at [epoch]: session-wrapped when [admin] has a secure chat with [to], else unreadable. */
    fun ownInvite(
        admin: AnnouncerPhone,
        to: String,
        epoch: Int = 0,
    ): String =
        if (admin.base.ourKeys.containsKey(to) && admin.base.peerKeys.containsKey(to)) {
            AnnounceDriver.gi(C, admin.base.sessionWith(to), MA3.groupKey, epoch, GID)
        } else {
            "ZMSG:3.0:GROUP:GI:$GID:" +
                JSONObject()
                    .put("name", "Announce group")
                    .put("inviter", C)
                    .put("key_epoch", epoch)
                    .put("k2", "AAAA")
                    .toString()
        }

    /** Our own signed key change to [epoch], wrapped for [to]. */
    fun ownKeyChange(
        admin: AnnouncerPhone,
        to: String,
        epoch: Int,
    ): String {
        val wrapped = E2EEncryption.encrypt(AnnounceDriver.newGroupKey(), admin.base.sessionWith(to))
        val signed = ZMSGGroupProtocol.groupKeySignedData(GID, C, epoch, wrapped, "rotation")
        val sig = E2EEncryption.sign(MA3.keyFor(admin, to).privateKey, signed)
        return ZMSGGroupProtocol.createGroupKeyMessage(GID, C, epoch, wrapped, sig)
    }

    /** One of our own mined transactions, as the history pass hands it over. */
    fun own(
        vm: ChatViewModel,
        memo: String,
        tx: Int,
        to: String,
        height: Long,
    ) = AnnounceDriver.receive(
        vm,
        memo,
        Instant.ofEpochMilli(AnnounceDriver.T + tx * 60_000L),
        AnnounceDriver.txId(tx),
        ownSend = true,
        ownRecipient = to,
        minedHeight = height,
    )

    /** The key change: the question about members that cannot be keyed, then the quote, both answered yes. */
    suspend fun rotate(
        vm: GroupViewModel,
        f: GroupPaidOnceFixture,
    ) {
        f.awaitNewJobs(vm) { vm.rotateGroupKey(GID) }
        if (vm.rekeyUnkeyableConfirmation.value != null) f.awaitNewJobs(vm) { vm.confirmRekeyUnkeyable() }
        if (vm.paidActionConfirmation.value != null) MA3.confirm(vm, f)
    }

    /** A message send; the result it ended with. */
    suspend fun sendResult(
        vm: GroupViewModel,
        f: GroupPaidOnceFixture,
    ): GroupSendResult? {
        vm.consumeGroupSendEvent()
        f.awaitNewJobs(vm) { vm.sendGroupMessage(GID, "synthetic") }
        if (vm.paidActionConfirmation.value != null) MA3.confirm(vm, f)
        return vm.groupSendEvent.value
    }

    fun keyChangesTo(
        net: RelayNet,
        npub: String,
    ): List<Int> =
        net
            .ofType(GroupMessageType.GROUP_KEY, npub)
            .mapNotNull { ZMSGGroupProtocol.parsePayload(it) }
            .mapNotNull { ZMSGGroupProtocol.parseGroupKeyPayload(GID, it)?.epoch }
}
