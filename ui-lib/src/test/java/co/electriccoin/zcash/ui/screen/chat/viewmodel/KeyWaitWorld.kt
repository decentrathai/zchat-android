package co.electriccoin.zcash.ui.screen.chat.viewmodel

import co.electriccoin.zcash.ui.nostr.NostrChatBridge
import co.electriccoin.zcash.ui.screen.chat.crypto.E2EEncryption
import co.electriccoin.zcash.ui.screen.chat.crypto.E2EKeyPair
import co.electriccoin.zcash.ui.screen.chat.datasource.ZchatPreferences
import co.electriccoin.zcash.ui.screen.chat.model.ZBootMessage
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

/**
 * ZC-739, T7BH-N1 (T7B-HANDSHAKE-SPEC rev 2, sections 2.1 and 2.2) — a [LedgerWorld] phone whose store also answers the
 * "Security key changed" flag ([flagged]) and the few calls the free relay paths read that [LedgerPrefs] leaves empty:
 * the contacts' conversation ids (the rotation's peer list), the Accept hold of a chat request ([holds]), the contact's
 * relay key (kept in [LedgerPrefs.peerNostr], as an inbound ZBOOT writes it).
 *
 * [recordKeepsFlag] models the production rule of `ZchatPreferencesImpl.setE2EKeyChanged` while a refused key is
 * remembered (ZC-545 R2): a plain clear (`setE2EKeyChanged(peer, false)`, what a verified ZBOOT makes) changes nothing.
 *
 * [publishes] records every relay publish of the registered publisher ([registerPublisher]) as (recipient relay key,
 * memo); [acks] is what each publish answers. Every key and address is synthetic.
 */
internal class KeyWaitWorld(
    val w: LedgerWorld,
) {
    val flagged: MutableSet<String> = ConcurrentHashMap.newKeySet()

    @Volatile var recordKeepsFlag = false

    val publishes: MutableList<Pair<String, String>> = Collections.synchronizedList(mutableListOf())

    @Volatile var acks = 0

    /** The Accept hold of a chat request (a stranger's first key exchange), by contact. */
    val holds = ConcurrentHashMap<String, String>()

    val proxy: ZchatPreferences =
        Proxy.newProxyInstance(
            ZchatPreferences::class.java.classLoader,
            arrayOf(ZchatPreferences::class.java),
        ) { _, method, args ->
            fun peer() = args!![0] as String
            when (method.name) {
                "isE2EKeyChanged" -> peer() in flagged
                "setE2EKeyChanged" -> {
                    if (args!![1] as Boolean) {
                        flagged += peer()
                    } else if (!recordKeepsFlag) {
                        flagged -= peer()
                    }
                    null
                }
                "getInboundKexAwaitingAccept" -> holds[peer()]
                "setInboundKexAwaitingAccept" -> {
                    val value = args!![1] as String?
                    if (value == null) holds.remove(peer()) else holds[peer()] = value
                    true
                }
                "getAllPeerToConvIdMappings" -> contacts().associateWith { w.base.convIdFor(it) }
                "getAllConversationPeerAddresses" -> contacts().toSet()
                "setPeerNostrPubkey" -> {
                    val value = args!![1] as String?
                    if (value == null) w.prefs.peerNostr.remove(peer()) else w.prefs.peerNostr[peer()] = value
                    null
                }
                else ->
                    try {
                        method.invoke(w.prefs.proxy, *(args ?: emptyArray()))
                    } catch (e: InvocationTargetException) {
                        throw e.targetException
                    }
            }
        } as ZchatPreferences

    /** A view model of [w] over this store. */
    suspend fun openVm(): ChatViewModel {
        val vm = w.openVm()
        PaymentHarness.setField(vm, "zchatPreferences", proxy)
        return vm
    }

    /** Installs a relay publisher that records each publish and answers [acks] relay acks. */
    fun registerPublisher() {
        NostrChatBridge.registerPublisher { memo, to ->
            publishes += to to memo
            NostrChatBridge.PublishResult(acks = acks, messageId = "synthetic-${publishes.size}")
        }
    }

    fun publishesTo(relayKey: String): List<String> =
        synchronized(publishes) {
            publishes.filter { it.first == relayKey }.map { it.second }
        }

    /** The proposals the wallet was asked to build (each one would be paid). */
    fun proposals(): Int = w.seam.proposals.get()

    /** The automatic-answer budget units used. */
    fun budgetUsed(): Int = w.prefs.budgetUsed.get()

    private fun contacts(): Set<String> = w.base.ourKeys.keys

    /** The contact's signed version-3 ZBOOT (its relay identity), signed with [contact]'s E2E key. */
    fun contactZboot(
        peer: String,
        contact: E2EKeyPair,
        relayKey: String,
        relay: String = "wss://relay.synthetic.example",
        epoch: Long = 0L,
    ): String {
        val convId = w.base.convIdFor(peer)
        val signature = E2EEncryption.sign(contact.privateKey, ZBootMessage.signedDataFor(convId, relayKey, relay, epoch))
        return ZBootMessage(convId, relayKey, relay, signature, epoch = epoch).serialize()
    }

    /** What the relay and on-chain inbound paths do with a ZBOOT from [peer]; @return whether it was taken. */
    fun routeIncomingBoot(
        vm: ChatViewModel,
        peer: String,
        raw: String,
    ): Boolean =
        try {
            ChatViewModel::class.java
                .getDeclaredMethod("routeIncomingBoot", String::class.java, String::class.java)
                .apply { isAccessible = true }
                .invoke(vm, peer, raw) as Boolean
        } catch (e: InvocationTargetException) {
            throw e.targetException
        }

    companion object {
        /** A synthetic relay key (64 hex). */
        fun relayKey(seed: Int): String = "%064x".format(seed)
    }
}
