package co.electriccoin.zcash.ui.screen.chat.crypto.ratchet

import co.electriccoin.zcash.ui.common.sendattempt.NetworkUpgradeHeights
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn

/**
 * ZC-1792 (owner W-142, P-A): the SEAM the fix must create, reached by name so the tests compile before it exists.
 *
 * `co.electriccoin.zcash.ui.screen.chat.crypto.ratchet.OldRootFallback` is a public Kotlin `object` (no `internal`, no
 * `@JvmStatic` needed; a name mangled by `internal` is also found) in the new file `crypto/ratchet/OldRootFallback.kt`.
 * It holds the logic; ChatViewModel only calls it. Its members:
 *
 * - `const val MAX_ATTEMPTS_PER_ROW = 3`, `const val MAX_FAILED_ATTEMPTS_PER_PEER_PER_PASS = 64`
 *   (PA_DESIGN.md section 1, "Budget").
 * - `fun expiryWindow(rowHeight: Long, upgrades: NetworkUpgradeHeights): Long`: how many blocks below a row a key exchange
 *   can have been mined and still be unscanned by its sender. It is `ChainTiming.maxExpiryDelta(rowHeight, upgrades)`:
 *   40 before NU7 (librustzcash builder.rs DEFAULT_TX_EXPIRY_DELTA = 40, ZIP 203) and 120 from NU7 on (ChainTiming
 *   NU7_EXPIRY_DELTA, zcash_primitives 0.31 NU7_DEFAULT_TX_EXPIRY_DELTA = 3 * 40). The design said "40, to confirm": it is
 *   40 only below NU7's activation height, and mainnet is past it.
 * - `fun candidates(kexTxids: Set<String>, kexScalar: String?, kexAckTxids: Set<String>, kexAckScalar: String?,
 *   minedHeights: Map<String, Long>, rowHeight: Long, upgrades: NetworkUpgradeHeights): List<Pair<ByteArray, ByteArray>>`:
 *   the roots to try, each as (KEX material, KEXACK material) in the bytes `E2ERatchet.canonicalTxidMaterial(set, null)`
 *   gives. The inputs are what the current root reads (the stored sets and the legacy scalars); a txid counts only if
 *   `minedHeights` knows it and it is at or below `rowHeight`. Order: C1 (all of those), C2 (C1 without its newest
 *   exchange, only if that one is within `expiryWindow` below the row), C3 (without its two newest, only if both are).
 *   Newest = highest height, then highest txid. One exchange = one txid, KEX or KEXACK. A candidate equal to the current
 *   root's material (all inputs) or to an earlier candidate is left out.
 * - `suspend fun open(ecdhSharedSecret: ByteArray, psk: ByteArray?, kexMaterial: ByteArray, kexAckMaterial: ByteArray,
 *   headerConvId: String, isLower: Boolean, fromContact: Boolean, envelope: Ciphertext): ByteArray?`: derives the
 *   root with `E2ERatchet.deriveRatchetRoot`, builds a throwaway `E2ERatchet` over a fresh `InMemoryRatchetStateStore`
 *   (its receive window seeded with the envelope's counter), decrypts, and answers null on ANY failure. It answers null
 *   at once unless the envelope's direction is the contact's (`fromContact`) or our own (not `fromContact`).
 * - `fun attemptCount(): Long`: how many throwaway ratchets this process built since `resetProcessState()`.
 * - `fun resetProcessState()`: zeroes that count and empties the process-wide negative cache (txid + floorKeyFor(candidate
 *   root) -> failed). The negative cache is process-wide on purpose: a ChatViewModel built without its constructor (the
 *   test harnesses) must not need a new field.
 */
internal object OldRootFallbackApi {
    private const val CLASS_NAME = "co.electriccoin.zcash.ui.screen.chat.crypto.ratchet.OldRootFallback"

    val exists: Boolean get() = runCatching { Class.forName(CLASS_NAME) }.isSuccess

    private fun cls(): Class<*> =
        runCatching { Class.forName(CLASS_NAME) }.getOrNull()
            ?: throw AssertionError("OldRootFallback does not exist: a message sealed before the answer has no reader (ZC-1792, W-142)")

    private fun target(): Any = checkNotNull(cls().getField("INSTANCE").get(null))

    private fun method(
        name: String,
        parameterCount: Int,
    ): Method =
        cls().methods.firstOrNull { (it.name == name || it.name.startsWith("$name$")) && it.parameterCount == parameterCount }
            ?: throw AssertionError("OldRootFallback.$name with $parameterCount parameters does not exist (seam, see OldRootFallbackApi)")

    private fun call(
        method: Method,
        vararg args: Any?,
    ): Any? =
        try {
            method.invoke(target(), *args)
        } catch (e: InvocationTargetException) {
            throw e.targetException
        }

    fun maxAttemptsPerRow(): Int = cls().getField("MAX_ATTEMPTS_PER_ROW").getInt(null)

    fun maxFailedAttemptsPerPeerPerPass(): Int = cls().getField("MAX_FAILED_ATTEMPTS_PER_PEER_PER_PASS").getInt(null)

    fun expiryWindow(
        rowHeight: Long,
        upgrades: NetworkUpgradeHeights,
    ): Long = call(method("expiryWindow", 2), rowHeight, upgrades) as Long

    @Suppress("UNCHECKED_CAST")
    fun candidates(
        kexTxids: Set<String>,
        kexScalar: String?,
        kexAckTxids: Set<String>,
        kexAckScalar: String?,
        minedHeights: Map<String, Long>,
        rowHeight: Long,
        upgrades: NetworkUpgradeHeights,
    ): List<Pair<ByteArray, ByteArray>> =
        call(
            method("candidates", 7),
            kexTxids,
            kexScalar,
            kexAckTxids,
            kexAckScalar,
            minedHeights,
            rowHeight,
            upgrades,
        ) as List<Pair<ByteArray, ByteArray>>

    /**
     * ZC-1865 (QA2 pa4): `candidates(..., upgrades, pending: Set<String>)`: [pending] are txids the wallet lists with no
     * height (not mined yet): they are left out like an exchange mined above the row. Only a txid neither mined nor pending
     * fails closed (ZC-1831).
     */
    @Suppress("UNCHECKED_CAST", "LongParameterList")
    fun candidates(
        kexTxids: Set<String>,
        kexScalar: String?,
        kexAckTxids: Set<String>,
        kexAckScalar: String?,
        minedHeights: Map<String, Long>,
        rowHeight: Long,
        upgrades: NetworkUpgradeHeights,
        pending: Set<String>,
    ): List<Pair<ByteArray, ByteArray>> =
        call(
            method("candidates", 8),
            kexTxids,
            kexScalar,
            kexAckTxids,
            kexAckScalar,
            minedHeights,
            rowHeight,
            upgrades,
            pending,
        ) as List<Pair<ByteArray, ByteArray>>

    suspend fun open(
        ecdhSharedSecret: ByteArray,
        psk: ByteArray?,
        kexMaterial: ByteArray,
        kexAckMaterial: ByteArray,
        headerConvId: String,
        isLower: Boolean,
        fromContact: Boolean,
        envelope: Ciphertext,
    ): ByteArray? {
        val m = method("open", 9)
        return suspendCoroutineUninterceptedOrReturn<Any?> { continuation ->
            try {
                m.invoke(
                    target(),
                    ecdhSharedSecret,
                    psk,
                    kexMaterial,
                    kexAckMaterial,
                    headerConvId,
                    isLower,
                    fromContact,
                    envelope,
                    continuation,
                )
            } catch (e: InvocationTargetException) {
                throw e.targetException
            }
        } as ByteArray?
    }

    /** Zero when the seam does not exist yet: the compat guards read it without needing the fix. */
    fun attemptCount(): Long = if (exists) call(method("attemptCount", 0)) as Long else 0L

    fun resetProcessState() {
        if (exists) call(method("resetProcessState", 0))
    }
}
