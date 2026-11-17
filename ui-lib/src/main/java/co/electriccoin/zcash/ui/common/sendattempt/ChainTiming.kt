package co.electriccoin.zcash.ui.common.sendattempt

import cash.z.ecc.android.sdk.model.ZcashNetwork

/**
 * ZC-114 (fix-all 2.13.0, train T5b): the network upgrade heights this build's chain timing depends on. [nu7] is the
 * NU7 activation height (ZIP 218: 25-second blocks, a default expiry delta of 120), or null when the SDK this build
 * ships knows no NU7: it then builds only pre-NU7 transactions, at any height.
 */
data class NetworkUpgradeHeights(
    val nu7: Long?,
    /**
     * ZC-599 (FIRST-CONTACT-SPEC 5.4 item 2, leg 3): the upgrade height. An inbound key-exchange answer mined at or
     * below it is read as before (no build with the asked rule existed then); null: none (leg 3 is off).
     */
    val upgrade: Long? = null,
) {
    /** The table with no upgrade height (as every table before ZC-599 was built). */
    constructor(nu7: Long?) : this(nu7, null)
}

/**
 * ZC-114 (fix-all 2.13.0, train T5b; high-items SPECS §1.4): the one table every expiry bound and every minute count
 * derived from blocks reads, so NU7 (25-second blocks, a larger expiry delta) is a change of this table and nothing
 * else. Pure: no Android.
 *
 * No NU7 height is ever estimated here. The rows hold the heights of the SDK this build ships: since ZC-1554 (6 Nov
 * 2026, NU7 live on mainnet) our NU7 build of the official SDK, whose crates (zcash_protocol 0.11.0) set mainnet NU7
 * at 3,543,405 and testnet at 4,465,026 (ChainTimingFollowsTheShippedSdkTest pins the pair to the SDK version).
 *
 * Any unknown errs toward waiting longer: an unknown height counts as pre-NU7 for a block spacing (75 s, the longer
 * minute count) and the expiry delta is the largest the shipped SDK can use at the height.
 */
object ChainTiming {
    /** ZIP 208's target block spacing before NU7, in seconds (SDK 3.0.0's ZcashSdk.BLOCK_INTERVAL_MILLIS / 1000). */
    const val PRE_NU7_BLOCK_SECONDS = 75L

    /** ZIP 218's target block spacing from NU7 on, in seconds (zcash_protocol 0.11.0: a third of 75 s). */
    const val NU7_BLOCK_SECONDS = 25L

    /** The SDK's expiry delta before NU7 (librustzcash's DEFAULT_TX_EXPIRY_DELTA, ZIP 203 after Blossom). */
    const val PRE_NU7_EXPIRY_DELTA = 40L

    /**
     * The expiry delta from NU7 on: 120 blocks, about 50 minutes as before (ZIP 218; zcash_primitives 0.31.0
     * NU7_DEFAULT_TX_EXPIRY_DELTA = 3 * 40, the builder's default for an NU7 target). ZC-1554's phone proof reads
     * expiry - target of every send; raise this if the SDK ever uses more.
     */
    const val NU7_EXPIRY_DELTA = 120L

    /** The minute counts shown with a block countdown allow 6/5 of the target spacing (mainnet drifts above 75 s). */
    const val DISPLAY_DRIFT_NUMERATOR = 6L
    const val DISPLAY_DRIFT_DENOMINATOR = 5L

    private const val SECONDS_PER_MINUTE = 60L

    /** ZC-599: NU6.3's mainnet height (WalletAccount.kt), the upgrade height of leg 3. */
    const val MAINNET_UPGRADE_HEIGHT = 3_428_143L

    /** ZC-1554: NU7's mainnet activation height (ZIP 259), as the shipped SDK's crates know it. */
    const val MAINNET_NU7_HEIGHT = 3_543_405L

    /** ZC-1554: NU7's testnet activation height (ZIP 259). */
    const val TESTNET_NU7_HEIGHT = 4_465_026L

    /** Mainnet as the shipped SDK knows it. */
    val MAINNET = NetworkUpgradeHeights(nu7 = MAINNET_NU7_HEIGHT, upgrade = MAINNET_UPGRADE_HEIGHT)

    /** Testnet as the shipped SDK knows it. */
    val TESTNET = NetworkUpgradeHeights(nu7 = TESTNET_NU7_HEIGHT)

    /** The row of [network]. */
    fun forNetwork(network: ZcashNetwork): NetworkUpgradeHeights = if (network.isTestnet()) TESTNET else MAINNET

    /**
     * The table of the network this process's wallet runs on, set where the synchronizer is created (mainnet until
     * then). Process-wide, like SendAttemptProof; a test that changes it puts it back.
     */
    @Volatile
    var current: NetworkUpgradeHeights = MAINNET

    /** The last network height the chat screen read (for estimates shown without a height of their own), or null. */
    @Volatile
    var lastTip: Long? = null

    /** Whether the block at [height] is at or after NU7's activation in [upgrades]. */
    fun isNu7(
        height: Long,
        upgrades: NetworkUpgradeHeights = current,
    ): Boolean {
        val nu7 = upgrades.nu7 ?: return false
        return height >= nu7
    }

    /** How many seconds after the block before it the block at [height] arrives (null: unknown, counted pre-NU7). */
    fun blockSeconds(
        height: Long?,
        upgrades: NetworkUpgradeHeights = current,
    ): Long = if (height != null && isNu7(height, upgrades)) NU7_BLOCK_SECONDS else PRE_NU7_BLOCK_SECONDS

    /** The seconds from block [fromHeight] to block [toHeight]: each block in between at its own spacing; 0 if none. */
    fun secondsBetween(
        fromHeight: Long,
        toHeight: Long,
        upgrades: NetworkUpgradeHeights = current,
    ): Long {
        if (toHeight <= fromHeight) return 0L
        val nu7 = upgrades.nu7
        if (nu7 == null || toHeight < nu7) return (toHeight - fromHeight) * PRE_NU7_BLOCK_SECONDS
        // Blocks fromHeight+1 .. toHeight: those below nu7 at the old spacing, the rest at the new one.
        val preBlocks = (nu7 - 1 - fromHeight).coerceIn(0L, toHeight - fromHeight)
        val postBlocks = (toHeight - fromHeight) - preBlocks
        return preBlocks * PRE_NU7_BLOCK_SECONDS + postBlocks * NU7_BLOCK_SECONDS
    }

    /** [secondsBetween] in whole minutes, rounded up, at least 1. */
    fun minutesBetween(
        fromHeight: Long,
        toHeight: Long,
        upgrades: NetworkUpgradeHeights = current,
    ): Int {
        val seconds = secondsBetween(fromHeight, toHeight, upgrades)
        return ((seconds + SECONDS_PER_MINUTE - 1) / SECONDS_PER_MINUTE).coerceAtLeast(1L).toInt()
    }

    /**
     * The largest expiry delta the shipped SDK can give a transaction whose target height is [targetHeight]: 40 before
     * NU7 and whenever the table holds no NU7 height (the SDK then cannot build a post-NU7 transaction), the larger of
     * the two from NU7 on.
     */
    fun maxExpiryDelta(
        targetHeight: Long,
        upgrades: NetworkUpgradeHeights = current,
    ): Long = if (isNu7(targetHeight, upgrades)) maxOf(NU7_EXPIRY_DELTA, PRE_NU7_EXPIRY_DELTA) else PRE_NU7_EXPIRY_DELTA

    /**
     * ZC-688 H1/H3/H4 (decided 6 Oct, built for ZC-1554): a margin of [preNu7Blocks] blocks of 75 s kept as the same TIME
     * at [height]'s spacing: [preNu7Blocks] before NU7, three times as many from NU7 on. Read at the height the margin
     * follows (for an expiry margin: the expiry height), so a wait that straddles the activation keeps the rule of the
     * block it is counted from.
     */
    fun timeKeptBlocks(
        preNu7Blocks: Long,
        height: Long,
        upgrades: NetworkUpgradeHeights = current,
    ): Long = preNu7Blocks * PRE_NU7_BLOCK_SECONDS / blockSeconds(height, upgrades)

    /** The seconds a block is counted in a countdown shown to the user at [height]: its spacing with the 6/5 allowance. */
    fun displaySecondsPerBlock(
        height: Long?,
        upgrades: NetworkUpgradeHeights = current,
    ): Long = blockSeconds(height, upgrades) * DISPLAY_DRIFT_NUMERATOR / DISPLAY_DRIFT_DENOMINATOR
}
