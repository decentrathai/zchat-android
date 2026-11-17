package co.electriccoin.zcash.ui.screen.chat.viewmodel

import co.electriccoin.zcash.ui.screen.chat.datasource.ZchatPreferences
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.util.Collections

/**
 * KI-24 part A test support: the processed-KEX store as it behaves after its 500-entry cap evicted old entries.
 *
 * The real store (ZchatPreferences, the "zchat_kex_seen" file) drops the oldest txids once it holds 500. KI-24 part A
 * gives it a height floor: when entries are evicted, the floor rises to the highest evicted mined height (never above
 * the fully scanned height), and a KEX mined at or below the floor counts as processed. Here an evicted txid is simply
 * absent from [HandshakePrefs.processedKexTx], and [floorHeight] is the floor the store holds after the eviction. The
 * store's own eviction and floor arithmetic are tested on the store itself.
 *
 * Every call goes to [base] except the processed-KEX pair (answered with the floor, whichever arity the build calls)
 * and the trust flags a false key change writes ([keyChangedWrites], [verifiedWrites]), which [HandshakePrefs] does
 * not expect.
 */
internal class KexFloorPrefs(
    val base: HandshakePrefs,
    private val floorHeight: Long,
) {
    val keyChangedWrites: MutableList<String> = Collections.synchronizedList(mutableListOf())
    val verifiedWrites: MutableList<String> = Collections.synchronizedList(mutableListOf())

    val proxy: ZchatPreferences =
        Proxy.newProxyInstance(
            ZchatPreferences::class.java.classLoader,
            arrayOf(ZchatPreferences::class.java),
        ) { p, method, args ->
            val a = args ?: emptyArray()
            when (method.name) {
                "hasProcessedKexTx" -> {
                    val height = a.getOrNull(1) as Long?
                    a[0] in base.processedKexTx || (height != null && height <= floorHeight)
                }
                "markKexTxProcessed" -> {
                    base.processedKexTx += a[0] as String
                    null
                }
                "setE2EKeyChanged" -> {
                    keyChangedWrites += "${a[0]}=${a[1]}"
                    null
                }
                "setE2EVerified" -> {
                    verifiedWrites += "${a[0]}=${a[1]}"
                    null
                }
                "toString" -> "KexFloorPrefs"
                "hashCode" -> System.identityHashCode(p)
                "equals" -> p === a.getOrNull(0)
                else ->
                    try {
                        method.invoke(base.proxy, *a)
                    } catch (e: InvocationTargetException) {
                        throw e.targetException
                    }
            }
        } as ZchatPreferences
}

internal object KexFloorHarness {
    /** Well below [HandshakeHarness.START_HEIGHT]: the floor the store holds after its cap evicted old KEX txids. */
    const val FLOOR = 3_450_000L

    /**
     * Hands a KEX from the history pass to the real handler, as convertToConversations does, with the mined height
     * the pass read ([minedHeight], null for a mempool KEX). A build whose handler takes the mined height gets it; at
     * HEAD the handler has no height parameter, so the pass can never tell it how old the KEX is.
     */
    fun handleScannedKex(
        vm: ChatViewModel,
        memo: String,
        txId: String,
        minedHeight: Long?,
    ) {
        val withHeight =
            ChatViewModel::class.java.declaredMethods.firstOrNull { m ->
                m.name == "handleKEXMessage" &&
                    m.parameterCount == 5 &&
                    m.parameterTypes[3] == java.lang.Boolean.TYPE &&
                    m.parameterTypes[4] == Long::class.javaObjectType
            }
        if (withHeight == null) {
            HandshakeHarness.handleKexMemo(vm, memo, txId)
            return
        }
        withHeight.isAccessible = true
        try {
            withHeight.invoke(vm, memo, HandshakeHarness.SELF, txId, true, minedHeight)
        } catch (e: InvocationTargetException) {
            throw e.targetException
        }
    }
}
