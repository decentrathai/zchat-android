package co.electriccoin.zcash.ui.screen.chat.viewmodel

import co.electriccoin.zcash.ui.common.model.SubmitResult
import co.electriccoin.zcash.ui.screen.chat.model.AutomaticSetupKind
import co.electriccoin.zcash.ui.screen.chat.model.HandshakePaidRecord
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlin.test.fail

/**
 * F8 (ZC-112, ZC-121): the set-up and the steps the send-attempt ledger tests share. Each step drives the real
 * ChatViewModel entry point a user or the app reaches (a chat open, Reconnect, a block, Retry, Confirm).
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal object LedgerTests {
    fun setUp(scopes: SendHarnessScopes) {
        scopes.open()
        Dispatchers.setMain(UnconfinedTestDispatcher())
        LedgerProcess.reset()
        // The history of this process was read (after a restore nothing automatic pays before it).
        AutoSpendVm.setHistoryRead(true)
    }

    fun tearDown(scopes: SendHarnessScopes) {
        scopes.close()
        Dispatchers.resetMain()
        LedgerProcess.reset()
    }
}

/** A stored transaction the network never confirms: the submit answers a transport failure with its ids. */
internal fun LedgerWorld.storedNeverMined() {
    seam.outcome = { _, ids -> SubmitResult.GrpcFailure(ids) }
}

/** The wallet lists every transaction the seam stored so far as unmined, with the seam's expiry. */
internal fun LedgerWorld.walletListsStoredUnmined() {
    wallet.txs = seam.stored.indices.map { chainTx(it + 1) }
}

/** A chat open of a Tunnel chat (the bootstrap: our KEX when it is due, then our ZBOOT when it is due), awaited. */
internal suspend fun LedgerWorld.chatOpen(
    vm: ChatViewModel,
    force: Boolean = false,
) {
    vm.ensureNostrBootstrapSent(peer, force)
    HandshakeHarness.awaitIdle(vm)
}

/** A new block reaching [vm] (the owed-KEX and ZBOOT ticks), awaited. */
internal suspend fun LedgerWorld.blockTick(
    vm: ChatViewModel,
    height: Long,
) {
    ChatViewModel::class.java
        .getDeclaredMethod("onChainTip", Long::class.javaObjectType)
        .apply { isAccessible = true }
        .invoke(vm, height)
    HandshakeHarness.awaitIdle(vm)
}

/** The Tunnel bootstrap's KEX, stored and never mined, with the wallet listing it. */
internal suspend fun LedgerWorld.kexThatNeverMines(vm: ChatViewModel) {
    storedNeverMined()
    chatOpen(vm)
    walletListsStoredUnmined()
}

/** Two complete passes at strictly rising tips, both past the stored transactions' expiry plus the reorg margin. */
internal suspend fun LedgerWorld.twoPassesPastExpiry(
    vm: ChatViewModel,
    from: Long = seam.expiry + LedgerWorld.MARGIN,
) {
    pass(vm, from)
    pass(vm, from + 1)
}

/** [kexThatNeverMines] proven: returns the marker the notice carries, or fails with what the phone shows instead. */
internal suspend fun LedgerWorld.kexProvenNotSent(vm: ChatViewModel): Long {
    kexThatNeverMines(vm)
    twoPassesPastExpiry(vm)
    return notice(vm)[peer]
        ?: fail(
            "the key exchange was stored, never mined and is past its expiry on two passes at rising tips, but the " +
                "chat shows no \"Key exchange not sent\" notice (ledger: ${ledger.all()}, proposals=${seam.proposals.get()}): " +
                "nothing tells the user, and the markers say \"sent\" for good",
        )
}

/** Makes every paid-setup record of this chat [minutes] old (the automatic "paid within the hour" rule lapses at 60). */
internal fun LedgerWorld.agePaidRecords(minutes: Long) {
    val at = System.currentTimeMillis() - minutes * 60_000L
    base.paidRecords[HandshakePaidRecord.slot(AutomaticSetupKind.KEX, peer)]?.let {
        base.paidRecords[HandshakePaidRecord.slot(AutomaticSetupKind.KEX, peer)] = HandshakePaidRecord.encode(ourKeys.publicKey, at)
    }
}

/** Every automatic trigger of the chat's key exchange under the standing consent, in turn. */
internal suspend fun LedgerWorld.everyAutomaticKexTrigger(
    vm: ChatViewModel,
    tip: Long,
) {
    repeat(3) { chatOpen(vm) }
    chatOpen(vm, force = true)
    repeat(5) { blockTick(vm, tip + it) }
    vm.retryOwedKeyExchange(peer)
    HandshakeHarness.awaitIdle(vm)
}

/** This phone is the responder: the contact's key offer arrived (their key is stored as received), no key reply was sent. */
internal fun LedgerWorld.asResponder(): String {
    val theirs =
        co.electriccoin.zcash.ui.screen.chat.crypto.E2EEncryption
            .generateKeyPair()
            .publicKey
    base.peerKeys[peer] = theirs
    prefs.receivedKex[peer] = theirs
    return theirs
}

/** The responder's key reply, stored and never mined, proven: returns the marker the notice carries. */
internal suspend fun LedgerWorld.kexAckProvenNotSent(vm: ChatViewModel): Long {
    asResponder()
    storedNeverMined()
    vm.retryKexAckIfResponder(peer)
    HandshakeHarness.awaitIdle(vm)
    walletListsStoredUnmined()
    twoPassesPastExpiry(vm)
    return notice(vm)[peer]
        ?: fail(
            "the key reply was stored, never mined and is past its expiry on two passes at rising tips, but the chat " +
                "shows no \"Key exchange not sent\" notice (ledger: ${ledger.all()}): its marker says \"sent\" for good",
        )
}

/** Our relay-identity message (ZBOOT) to a contact whose key exchange is complete, as a trigger sends it; awaited. */
internal suspend fun LedgerWorld.sendZboot(vm: ChatViewModel) {
    AutoSpendVm.sendNostrBoot(vm, peer)
    HandshakeHarness.awaitIdle(vm)
}

/** An on-chain ZBOOT, stored and never mined, proven: returns the marker the notice carries. */
internal suspend fun LedgerWorld.zbootProvenNotSent(vm: ChatViewModel): Long {
    storedNeverMined()
    sendZboot(vm)
    walletListsStoredUnmined()
    twoPassesPastExpiry(vm)
    return notice(vm)[peer]
        ?: fail(
            "our relay-identity message was stored, never mined and is past its expiry on two passes at rising tips, " +
                "but the chat shows no \"Key exchange not sent\" notice (ledger: ${ledger.all()}, stored=${seam.stored.size})",
        )
}

/** Retry on the notice, then Confirm on its question; awaited. */
internal suspend fun LedgerWorld.retryAndConfirm(
    vm: ChatViewModel,
    offerStartedAt: Long,
) {
    tapRetry(vm, offerStartedAt)
    HandshakeHarness.awaitIdle(vm)
    vm.confirmPaidAction()
    HandshakeHarness.awaitIdle(vm)
}
