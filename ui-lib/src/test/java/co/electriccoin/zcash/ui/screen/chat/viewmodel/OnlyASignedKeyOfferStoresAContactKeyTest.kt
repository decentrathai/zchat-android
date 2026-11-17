package co.electriccoin.zcash.ui.screen.chat.viewmodel

import cash.z.ecc.android.sdk.Synchronizer
import cash.z.ecc.android.sdk.model.BlockHeight
import cash.z.ecc.android.sdk.model.TransactionId
import cash.z.ecc.android.sdk.model.Zatoshi
import co.electriccoin.zcash.ui.common.repository.ReceiveTransaction
import co.electriccoin.zcash.ui.common.repository.Transaction
import co.electriccoin.zcash.ui.common.repository.TransactionRepository
import co.electriccoin.zcash.ui.screen.chat.crypto.E2EEncryption
import co.electriccoin.zcash.ui.screen.chat.model.AddressCache
import co.electriccoin.zcash.ui.screen.chat.model.Conversation
import co.electriccoin.zcash.ui.screen.chat.model.ZMSGProtocol
import co.electriccoin.zcash.ui.screen.chat.view.ChatResourceText
import co.electriccoin.zcash.ui.screen.chat.view.SourceScan
import co.electriccoin.zcash.ui.screen.chat.viewmodel.GroupInviteDriver.GROUP_KEY
import co.electriccoin.zcash.ui.screen.chat.viewmodel.GroupInviteDriver.plaintextInvite
import co.electriccoin.zcash.ui.screen.chat.viewmodel.GroupInviteDriver.processInvite
import co.electriccoin.zcash.ui.screen.chat.viewmodel.HandshakeHarness.awaitIdle
import co.electriccoin.zcash.ui.screen.chat.viewmodel.HandshakeHarness.chatViewModelWith
import co.electriccoin.zcash.ui.screen.chat.viewmodel.PaymentHarness.paymentViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.lang.reflect.Proxy
import java.time.Instant
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap

/**
 * ZC-638 — only a signed key offer stores a contact's key.
 *
 * Two more places write a contact's key from an unsigned memo: the legacy `E2E_INIT:` branch of the key-exchange
 * handler, and the `inviter_pub` field of a group invitation, which stores a key at an address that has none (also
 * while the invitation still waits for Join). No build since v2.9.3 writes either form, and nothing needs the stored
 * key. The history pass also sends every received memo that merely CONTAINS the text `E2E_INIT:` to the key-exchange
 * block, where it is never shown.
 *
 * Wanted: a contact's key is written by the signed key offer's store part only. An invitation's key field stores
 * nothing in any state. A memo that starts with `E2E_INIT:` is skipped; a message that only contains the text is
 * shown as the ordinary message it is.
 *
 * The invitation cases drive the real invite handler; the memo cases run the real history pass over one received
 * transaction. All addresses, keys, ids and txids are synthetic.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class OnlyASignedKeyOfferStoresAContactKeyTest {
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

    // ---- (a) the source ------------------------------------------------------------------------------------------

    @Test
    fun `a contact's key is written by the signed key offer's store part only`() {
        val source = SourceScan.stripComments(ChatResourceText.source(CVM))
        val adopt = SourceScan.braceBlock(source, "private fun adoptKeyOffer(")
        // ZC-634 (part B): the contact key's one write, with its claim mark and asked record in one commit.
        val write = "zchatPreferences.storePeerKey("

        assertTrue("fixture: the signed key offer's store part writes the key", write in adopt)
        assertEquals(
            "a contact's key is written outside adoptKeyOffer",
            occurrences(adopt, write),
            occurrences(source, write),
        )
        assertFalse("the invitation's key writer is still there", "storeInviterKeyOnFirstContact" in source)
        assertEquals(
            "the handler and the history pass may name E2E_INIT once: the skip of a memo that starts with it",
            1,
            occurrences(source, "E2E_INIT"),
        )
        assertTrue("a memo that starts with E2E_INIT: is not skipped by its start", SKIP in source)
    }

    // ---- (b) the invitation's key field ---------------------------------------------------------------------------

    @Test
    fun `an invitation's key field stores nothing at an address with no key`() =
        keyFieldCase("no key", storedKey = null, refusedKeyWaits = false)

    @Test
    fun `guard - an invitation's key field changes nothing at an address with the same key`() =
        keyFieldCase("the same key", storedKey = FIELD_KEY, refusedKeyWaits = false)

    @Test
    fun `guard - an invitation's key field changes nothing at an address with another key`() =
        keyFieldCase("another key", storedKey = E2EEncryption.generateKeyPair().publicKey, refusedKeyWaits = false)

    @Test
    fun `guard - an invitation's key field stores nothing while a refused key waits with its flag`() =
        keyFieldCase("no key, a refused key waiting", storedKey = null, refusedKeyWaits = true)

    private fun keyFieldCase(
        what: String,
        storedKey: String?,
        refusedKeyWaits: Boolean,
    ) = runBlocking {
        val inviter = SyntheticUnifiedAddress.mainnet(seed = 63801)
        val phone = OddIdPhone()
        storedKey?.let { phone.peerKeys[inviter] = it }
        val prefs =
            ScanPassHarness.prefsWith(
                phone.proxy,
                mapOf(
                    "isE2EKeyChanged" to { a -> refusedKeyWaits && a[0] == inviter },
                    "hasRefusedKeyOffer" to { a -> refusedKeyWaits && a[0] == inviter },
                ),
            )
        val vm =
            chatViewModelWith(prefs, HandshakeSeam(inviter)).also { vm ->
                AutoSpendVm.wireBootstrapFields(vm)
                AutoSpendVm.setField(vm, "contactBook", SavedContacts(emptySet()))
                awaitIdle(vm)
            }
        val mark = phone.mark()

        processInvite(vm, GROUP, JSONObject(plaintextInvite(inviter)).put("inviter_pub", FIELD_KEY).toString())
        awaitIdle(vm)

        assertTrue("fixture ($what): the invitation was handled and its group stored", phone.groups.containsKey(GROUP))
        assertEquals("$what: the contact's key changed", storedKey, phone.peerKeys[inviter])
        assertEquals(
            "$what: the key, the key-changed flag, the verified flag or the refused record was written",
            emptyList<String>(),
            phone.writesSince(mark).filter { it in KEY_STATE_WRITES },
        )
    }

    // ---- (c), (d) the history pass ---------------------------------------------------------------------------------

    @Test
    fun `guard - a received memo that is E2E_INIT and a key stores nothing, shows nothing and sets no hold`() =
        runBlocking {
            val (conversations, calls) = scanReceived("E2E_INIT:" + E2EEncryption.generateKeyPair().publicKey)

            assertEquals("a legacy key memo was shown", emptyList<String>(), conversations.flatMap { it.messages }.map { it.text })
            assertEquals(
                "a legacy key memo wrote key state",
                emptyList<String>(),
                calls.filter { it in KEY_STATE_WRITES || it == "setInboundKexAwaitingAccept" || it == "setE2EEnabled" },
            )
        }

    @Test
    fun `a received first message whose text contains E2E_INIT is shown as an ordinary message`() =
        runBlocking {
            val text = "synthetic question: what does E2E_INIT: mean?"
            val (conversations, _) = scanReceived(ZMSGProtocol.createLegacyMessage(PEER, text))

            assertEquals("the message was swallowed", listOf(text), conversations.flatMap { it.messages }.map { it.text })
            assertEquals("it belongs to its sender's chat", listOf(PEER), conversations.map { it.peerAddress })
        }

    @Test
    fun `guard - the same first message without the text is shown`() =
        runBlocking {
            val text = "synthetic question: what does it mean?"
            val (conversations, _) = scanReceived(ZMSGProtocol.createLegacyMessage(PEER, text))

            assertEquals(listOf(text), conversations.flatMap { it.messages }.map { it.text })
            assertEquals(listOf(PEER), conversations.map { it.peerAddress })
        }

    // ---- (e) the v2.9.3 invitation ---------------------------------------------------------------------------------

    @Test
    fun `guard - an enc_key invitation in the v2_9_3 form, with its key field, still joins with its group key`() =
        runBlocking {
            val inviter = SyntheticUnifiedAddress.mainnet(seed = 63802)
            val phone = OddIdPhone()
            val ours = phone.inner.base.session(inviter, complete = true)
            phone.inner.e2eEnabled += inviter
            val inviterKey = phone.peerKeys.getValue(inviter)
            val vm =
                chatViewModelWith(phone.proxy, HandshakeSeam(inviter)).also { vm ->
                    AutoSpendVm.wireBootstrapFields(vm)
                    AutoSpendVm.setField(vm, "contactBook", SavedContacts(emptySet()))
                    awaitIdle(vm)
                }
            // ZMSGGroupProtocol.createGroupInviteMessage at v2.9.3-pre-audit-fixes: name, inviter, inviter_pub, members,
            // key_epoch, enc_key (the group key under the recipient's public key).
            val invite =
                JSONObject()
                    .put("name", "Synthetic v2.9.3 group")
                    .put("inviter", inviter)
                    .put("inviter_pub", inviterKey)
                    .put("members", JSONArray().put(inviter).put(HandshakeHarness.SELF))
                    .put("key_epoch", 0)
                    .put("enc_key", E2EEncryption.encryptGroupKeyForMember(ours.publicKey, Base64.getDecoder().decode(GROUP_KEY)))
                    .toString()
            val mark = phone.mark()

            processInvite(vm, GROUP, invite)
            awaitIdle(vm)

            assertTrue("the group was not stored", phone.groups.containsKey(GROUP))
            assertTrue("its group key was not stored", "saveGroupKey" in phone.writesSince(mark))
            assertEquals("the contact's key changed", inviterKey, phone.peerKeys[inviter])
        }

    // ---- helpers ---------------------------------------------------------------------------------------------------

    private fun occurrences(
        text: String,
        part: String,
    ): Int = text.windowed(part.length).count { it == part }

    /** One history pass over one received, mined transaction carrying [memo]: the chats shown and the store calls made. */
    private suspend fun scanReceived(memo: String): Pair<List<Conversation>, List<String>> {
        val prefs =
            ScanPrefs(
                mapOf(
                    "getOrPutPendingTxFirstSeenMillis" to { a -> a[1] },
                    "getOrPutMessageExpiryAnchorMillis" to { a -> a[1] },
                ),
            )
        val vm = paymentViewModel(PaymentPrefs(PEER), PaymentSeam(PEER))
        PaymentHarness.setField(vm, "_currentUserAddress", MutableStateFlow<String?>(ME))
        PaymentHarness.setField(vm, "addressCache", MapCache())
        PaymentHarness.setField(vm, "decryptedTextCache", ConcurrentHashMap<String, String>())
        PaymentHarness.setField(vm, "decryptFailedPeers", ConcurrentHashMap.newKeySet<String>())
        PaymentHarness.setField(vm, "hiddenMessages", MutableStateFlow<Set<String>>(emptySet()))
        PaymentHarness.setField(vm, "peerStatuses", MutableStateFlow<Map<String, Any>>(emptyMap()))
        PaymentHarness.setField(vm, "unlockedMessages", MutableStateFlow<Map<String, String>>(emptyMap()))
        val id = TransactionId.new(TXID)
        val at = Instant.now().minusSeconds(600L)
        val received: Transaction =
            ReceiveTransaction.Success(
                id = id,
                amount = Zatoshi(1_000),
                timestamp = at,
                memoCount = 1,
                transactionOutputs = emptyList(),
                overview = ScanPassHarness.overview(id, MINED_AT, at, sent = false, amount = 1_000),
                recipient = null,
            )
        val conversations =
            ScanPassHarness.scan(
                vm = vm,
                prefs = prefs.proxy,
                contactBook = ScanPassHarness.noContacts(),
                repository = repository(memo),
                synchronizer = synchronizer(),
                transactions = listOf(received),
                userAddress = ME,
            )
        awaitIdle(vm)
        return conversations to prefs.calls.toList()
    }

    private fun repository(memo: String): TransactionRepository =
        Proxy.newProxyInstance(
            TransactionRepository::class.java.classLoader,
            arrayOf(TransactionRepository::class.java),
        ) { p, method, args ->
            when (method.name) {
                "getMemos" -> if ((args!![0] as Transaction).id.txIdString() == TXID) listOf(memo) else emptyList<String>()
                "getAllRecipientAddresses" -> emptyList<String>()
                "hashCode" -> System.identityHashCode(p)
                "equals" -> p === args?.get(0)
                "toString" -> "Zc638Repository"
                else -> error("unexpected TransactionRepository.${method.name}")
            }
        } as TransactionRepository

    private fun synchronizer(): Synchronizer =
        Proxy.newProxyInstance(
            Synchronizer::class.java.classLoader,
            arrayOf(Synchronizer::class.java),
        ) { p, method, args ->
            when (method.name) {
                "getFullyScannedHeight", "getNetworkHeight" -> MutableStateFlow(BlockHeight.new(ScanPassHarness.SCANNED))
                "hashCode" -> System.identityHashCode(p)
                "equals" -> p === args?.get(0)
                "toString" -> "Zc638Synchronizer"
                else -> error("unexpected Synchronizer.${method.name}")
            }
        } as Synchronizer

    /** An in-memory hash cache. */
    private class MapCache : AddressCache {
        private val byHash = ConcurrentHashMap<String, String>()
        private val partners = ConcurrentHashMap.newKeySet<String>()

        override fun cacheAddress(
            hash: String,
            address: String,
        ) {
            byHash.putIfAbsent(hash, address)
        }

        override fun cacheAddressValidated(
            hash: String,
            address: String,
        ) {
            byHash[hash] = address
        }

        override fun getAddress(hash: String): String? = byHash[hash]

        override fun hasAddress(hash: String): Boolean = byHash.containsKey(hash)

        override fun getAllCachedAddresses(): Map<String, String> = byHash.toMap()

        override fun addConversationPartner(address: String) {
            partners += address
        }

        override fun getConversationPartners(): Set<String> = partners.toSet()

        override fun isConversationPartner(address: String): Boolean = address in partners

        override fun findConversationPartnerByHash(hash: String): String? = byHash[hash]?.takeIf { it in partners }
    }

    private companion object {
        const val CVM = "ui-lib/src/main/java/co/electriccoin/zcash/ui/screen/chat/viewmodel/ChatViewModel.kt"
        const val SKIP = "memoText.startsWith(\"E2E_INIT:\")"
        const val GROUP = "zgrp_0a1b2c3d4e5f607182936380"
        const val MINED_AT = 3_000_100L
        val TXID: String = "%064x".format(0x6380)
        val PEER: String = SyntheticUnifiedAddress.mainnet(63810)
        val ME: String = SyntheticUnifiedAddress.mainnet(63811)
        val FIELD_KEY: String = E2EEncryption.generateKeyPair().publicKey
        val KEY_STATE_WRITES =
            setOf(
                "setE2EPeerPublicKey",
                "setE2EKeyChanged",
                "setE2EVerified",
                "rememberRefusedKeyOffer",
                "clearRefusedKeyOffer",
            )
    }
}
