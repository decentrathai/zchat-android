package co.electriccoin.zcash.ui.screen.chat.viewmodel

import co.electriccoin.zcash.ui.common.model.WalletAccount
import co.electriccoin.zcash.ui.nostr.NostrChatBridge
import co.electriccoin.zcash.ui.screen.chat.datasource.ZchatPreferences
import co.electriccoin.zcash.ui.screen.chat.model.ConversationMode
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
 * T1b-1 §2 (fix-all 2.13.0, train T1b): the chat identity does not follow an account switch.
 *
 * ChatViewModel sets its chat identity (_currentUserAddress, the address an Open message's v4 INIT and every on-chain
 * INIT carry) only when the conversation list loads. A ChatViewModel that outlives an account switch keeps the other
 * account's identity: after switching from the Keystone account back to the ZChat account, the next Open message tells
 * the contact the Keystone account's address, linking it to the user's relay identity.
 *
 * Wanted: the next message after a switch names the selected account's saved identity (OwnChatIdentityStore, per account
 * key: the account UUID in hex, as GetDefaultUnifiedAddressUseCase saves it); while the selected account's identity is
 * not known yet, no message carries the other account's identity.
 *
 * The ViewModel's record of which account its identity belongs to is set by name (identityAccountKey) when the build
 * has one, as the conversation load sets it. Every address and key is synthetic.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatIdentityFollowsSelectedAccountTest {
    private val sendScopes = SendHarnessScopes()
    private val published: MutableList<String> = Collections.synchronizedList(mutableListOf())

    private companion object {
        const val PEER = "usynthetict1bidentitypeer001"
        const val PEER_NOSTR = "7e5f0c4d2a1b9e8f7a6b5c4d3e2f1a0b9c8d7e6f5a4b3c2d1e0f9a8b7c6d5e4f"
        const val KEYSTONE_IDENTITY = "usynthetict1bidentitykeystone"
        const val ZCHAT_IDENTITY = "usynthetict1bidentityzchat001"
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
    fun `after a switch back to the ZChat account the next Open message names the ZChat identity`() =
        runBlocking {
            val screen = loadedUnderKeystone(zchatIdentityKnown = true)
            screen.accounts.select(screen.accounts.zashiAccount())

            screen.vm.sendMessage(PEER, "synthetic hello after the switch")
            awaitIdle(screen.vm)

            val wire = published.single()
            assertFalse(
                "the Open message after the switch told the contact the Keystone account's address",
                wire.contains(KEYSTONE_IDENTITY),
            )
            assertTrue("the Open message must name the selected account's chat identity: $wire", wire.contains(ZCHAT_IDENTITY))
        }

    @Test
    fun `while the new account's identity is not known yet no message carries the other account's identity`() =
        runBlocking {
            val screen = loadedUnderKeystone(zchatIdentityKnown = false)
            screen.accounts.select(screen.accounts.zashiAccount())

            screen.vm.sendMessage(PEER, "synthetic hello before the identity is read")
            awaitIdle(screen.vm)

            assertFalse(
                "a message after the switch carried the Keystone account's address",
                published.any { it.contains(KEYSTONE_IDENTITY) },
            )
        }

    // --------------------------------------------------------------- harness

    /** An Open chat whose screen loaded its identity while the Keystone account was selected. */
    private suspend fun loadedUnderKeystone(zchatIdentityKnown: Boolean): KeystoneChatScreen {
        val screen = KeystoneChatScreen.open(PEER)
        screen.prefs.mode = ConversationMode.OPEN
        screen.prefs.peerNostrPubkey = PEER_NOSTR
        val pinned =
            buildMap {
                put(accountKey(screen.accounts.keystoneAccount()), KEYSTONE_IDENTITY)
                if (zchatIdentityKnown) put(accountKey(screen.accounts.zashiAccount()), ZCHAT_IDENTITY)
            }
        AutoSpendVm.setField(screen.vm, "zchatPreferences", withPinnedIdentities(screen.prefs.proxy, pinned))
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

    /** [base] with the saved chat identities of [pinned] (account key to address) answered. */
    private fun withPinnedIdentities(
        base: ZchatPreferences,
        pinned: Map<String, String>,
    ): ZchatPreferences =
        Proxy.newProxyInstance(
            ZchatPreferences::class.java.classLoader,
            arrayOf(ZchatPreferences::class.java),
        ) { p, method, args ->
            when (method.name) {
                "pinnedIdentity" -> pinned[args!![0] as String]
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
