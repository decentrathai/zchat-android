package co.electriccoin.zcash.ui.screen.chat.viewmodel

import co.electriccoin.zcash.ui.common.model.WalletAccount
import co.electriccoin.zcash.ui.nostr.NostrChatBridge
import co.electriccoin.zcash.ui.screen.chat.datasource.ZchatPreferences
import co.electriccoin.zcash.ui.screen.chat.model.ConversationMode
import co.electriccoin.zcash.ui.screen.chat.model.ZMSGProtocol
import co.electriccoin.zcash.ui.screen.chat.viewmodel.HandshakeHarness.awaitIdle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.util.Collections

/**
 * T2-extra (c), review should-fix S7 (train T1b REVIEW_CROSS X-1e): a reaction and a reply still carry the chat identity
 * the screen loaded, not the selected account's.
 *
 * T1b-1 made sendMessage take its identity from the selected account (chatIdentityForSelectedAccount), but sendReaction
 * and sendReply still read _currentUserAddress directly. A ChatViewModel that outlives a switch from the Keystone
 * account back to the ZChat account therefore puts the Keystone account's address in a reaction (a ZREACT names its
 * sender by its address hash) and in a reply that opens a conversation (its v4 INIT names the sender), linking the two accounts for the
 * contact until the account collector re-reads the identity.
 *
 * Must hold: after the switch, a reaction and a first reply name the selected account's saved identity and never the
 * Keystone account's. Same harness as ChatIdentityFollowsSelectedAccountTest; every address and key is synthetic.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ReactionAndReplyFollowSelectedAccountTest {
    private val sendScopes = SendHarnessScopes()
    private val published: MutableList<String> = Collections.synchronizedList(mutableListOf())

    private companion object {
        const val PEER = "usynthetict2extraidentity001"
        const val PEER_NOSTR = "6e5f0c4d2a1b9e8f7a6b5c4d3e2f1a0b9c8d7e6f5a4b3c2d1e0f9a8b7c6d5e4f"
        const val KEYSTONE_IDENTITY = "usynthetict2extraidkeystone1"
        const val ZCHAT_IDENTITY = "usynthetict2extraidzchat0001"
    }

    @Before
    fun setUp() {
        sendScopes.open()
        Dispatchers.setMain(UnconfinedTestDispatcher())
        NostrChatBridge.unregisterPublisher()
        NostrChatBridge.registerPublisher { plaintext, _ ->
            published += plaintext
            NostrChatBridge.PublishResult(acks = 1, messageId = "synthetic-rumor-${published.size}")
        }
    }

    @After
    fun tearDown() {
        NostrChatBridge.unregisterPublisher()
        sendScopes.close()
        Dispatchers.resetMain()
    }

    @Test
    fun `after a switch back to the ZChat account an Open reaction names the ZChat identity`() =
        runBlocking {
            val screen = loadedUnderKeystone(ConversationMode.OPEN, firstReply = false)
            screen.accounts.select(screen.accounts.zashiAccount())

            screen.vm.sendReaction(PEER, "synthetic-message-id-1", "👍")
            awaitIdle(screen.vm)

            // A ZREACT names its sender by the hash of the sender's chat address.
            val wire = published.single()
            assertFalse(
                "the reaction told the contact the Keystone account's address (its hash)",
                wire.contains(ZMSGProtocol.generateAddressHash(KEYSTONE_IDENTITY)),
            )
            assertTrue(
                "the reaction must name the selected account's chat identity (its hash): $wire",
                wire.endsWith("|" + ZMSGProtocol.generateAddressHash(ZCHAT_IDENTITY)),
            )
        }

    @Test
    fun `after a switch back to the ZChat account a first on-chain reply names the ZChat identity`() =
        runBlocking {
            val screen = loadedUnderKeystone(ConversationMode.VAULT, firstReply = true)
            screen.accounts.select(screen.accounts.zashiAccount())

            screen.vm.sendReply(PEER, "synthetic reply after the switch", "synthetic-message-id-2", "synthetic preview")
            awaitIdle(screen.vm)

            val memo = firstMemo(screen.seam.proposalUris.first())
            assertFalse("the reply's INIT told the contact the Keystone account's address", memo.contains(KEYSTONE_IDENTITY))
            assertTrue("the reply's INIT must name the selected account's chat identity: $memo", memo.contains(ZCHAT_IDENTITY))
        }

    // --------------------------------------------------------------- harness

    /** A chat of [mode] whose screen loaded its identity while the Keystone account was selected. */
    private suspend fun loadedUnderKeystone(
        mode: ConversationMode,
        firstReply: Boolean,
    ): KeystoneChatScreen {
        val screen = KeystoneChatScreen.open(PEER)
        screen.prefs.mode = mode
        screen.prefs.peerNostrPubkey = PEER_NOSTR
        val pinned =
            mapOf(
                accountKey(screen.accounts.keystoneAccount()) to KEYSTONE_IDENTITY,
                accountKey(screen.accounts.zashiAccount()) to ZCHAT_IDENTITY,
            )
        AutoSpendVm.setField(screen.vm, "zchatPreferences", withPinnedIdentities(screen.prefs.proxy, pinned, firstReply))
        AutoSpendVm.setField(screen.vm, "_currentUserAddress", MutableStateFlow<String?>(KEYSTONE_IDENTITY))
        ChatViewModel::class.java.declaredFields
            .firstOrNull { it.name == "identityAccountKey" }
            ?.apply { isAccessible = true }
            ?.set(screen.vm, accountKey(screen.accounts.keystoneAccount()))
        return screen
    }

    private fun accountKey(account: WalletAccount): String =
        account.sdkAccount.accountUuid.value
            .joinToString("") { "%02x".format(it) }

    /**
     * [base] with the saved chat identities of [pinned] (account key to address) answered; with [firstReply] the
     * conversation's INIT has not gone out yet (the next on-chain message is the INIT naming its sender).
     */
    private fun withPinnedIdentities(
        base: ZchatPreferences,
        pinned: Map<String, String>,
        firstReply: Boolean,
    ): ZchatPreferences =
        Proxy.newProxyInstance(
            ZchatPreferences::class.java.classLoader,
            arrayOf(ZchatPreferences::class.java),
        ) { p, method, args ->
            when (method.name) {
                "pinnedIdentity" -> pinned[args!![0] as String]
                "getOrCreateConversationIdForMessage" -> PaymentHarness.CONV_ID to firstReply
                "markConversationInitSubmitted" -> null
                "toString" -> "PinnedIdentities($base)"
                "hashCode" -> System.identityHashCode(p)
                "equals" -> p === args?.get(0)
                else ->
                    try {
                        method.invoke(base, *(args ?: emptyArray()))
                    } catch (e: InvocationTargetException) {
                        throw e.targetException
                    }
            }
        } as ZchatPreferences
}
