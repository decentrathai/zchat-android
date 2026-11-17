package co.electriccoin.zcash.ui.screen.chat.model

import co.electriccoin.zcash.ui.common.sendattempt.ChainTiming
import co.electriccoin.zcash.ui.common.sendattempt.NetworkUpgradeHeights

/**
 * ZC-1554: how far apart on chain this phone's own key exchange and an inbound answer to it can be. The answer can be
 * mined before our own exchange (the peer may read it over the relays first); our exchange is then mined at most one
 * expiry delta later. That delta was a fixed 40 blocks (ChainTiming.PRE_NU7_EXPIRY_DELTA): from NU7 on our transactions
 * expire 120 blocks after their target, so a fixed 40 would file a real answer as one nobody asked for. Pure.
 */
object KexAnswerWindow {
    /** Our own mined exchange at [ownKexHeight] can be the one an answer mined at [answerHeight] replies to. */
    fun ownKexCovers(
        ownKexHeight: Long,
        answerHeight: Long,
        upgrades: NetworkUpgradeHeights = ChainTiming.current,
    ): Boolean = ownKexHeight <= answerHeight + ChainTiming.maxExpiryDelta(answerHeight, upgrades)

    /** At scanned tip [scannedTip], no own exchange of ours can still be mined for an answer mined at [answerHeight]. */
    fun ownKexWaitIsOver(
        answerHeight: Long,
        scannedTip: Long,
        upgrades: NetworkUpgradeHeights = ChainTiming.current,
    ): Boolean = scannedTip > answerHeight + ChainTiming.maxExpiryDelta(answerHeight, upgrades)
}
