package co.electriccoin.zcash.ui.screen.chat.viewmodel

import cash.z.ecc.android.sdk.model.TransactionId
import co.electriccoin.zcash.ui.nostr.NostrChatBridge
import co.electriccoin.zcash.ui.screen.chat.datasource.ZchatPreferences
import co.electriccoin.zcash.ui.screen.chat.viewmodel.HandshakeHarness.TIMEOUT_MS
import co.electriccoin.zcash.ui.screen.chat.viewmodel.HandshakeHarness.awaitIdle
import co.electriccoin.zcash.ui.screen.chat.viewmodel.HandshakeHarness.chatViewModelWith
import co.electriccoin.zcash.ui.screen.chat.viewmodel.JoinHeldCopy.joinNoWait
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.time.Instant
import java.util.Base64
import java.util.concurrent.atomic.AtomicLong

/**
 * ZC-284 (R8 findings, SPECS §6) — a phrase change while Join's relay reply is in flight loses the outgoing wallet's
 * reply.
 *
 * Join deletes the invite's waiting record first and writes the held copy of the reply only after the relay try and the
 * account read. When the recovery phrase changes during the relay try (wallet A's stores are set aside, wallet B's
 * become live), the reply is rightly not written into B's stores and not paid from B. But A's stores, as they were set
 * aside, hold neither the record nor a held copy: when A comes back, nothing offers Join again and the creator keeps A
 * Invited.
 *
 * Wanted: Join writes the held copy (in the wallet that tapped Join) before it clears the record. After the phrase
 * change nothing of it is written, offered or paid under B; when A is back, its first block turns the held copy into
 * the waiting record again and A's Join sends the one reply.
 *
 * The phrase change is [TwoWallets.claim] (the HeldGroupAcceptBelongsToItsWalletTest idiom): the live stores switch to
 * the other wallet's and the stores' generation goes up. All ids, addresses and memos are synthetic.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class JoinDuringAPhraseChangeKeepsTheOutgoingWalletsReplyTest {
    private val sendScopes = SendHarnessScopes()

    @Before
    fun setUp() {
        sendScopes.open()
        Dispatchers.setMain(UnconfinedTestDispatcher())
        AutoSpendVm.resetProcessState()
        NostrChatBridge.unregisterPublisher()
    }

    @After
    fun tearDown() {
        NostrChatBridge.unregisterPublisher()
        sendScopes.close()
        Dispatchers.resetMain()
        AutoSpendVm.resetProcessState()
    }

    /** The live stores of one phone, which belong to wallet [a] or wallet [b]; the ViewModel sees [proxy]. */
    private class TwoWallets(
        val a: AutoSpendPrefs,
        val b: AutoSpendPrefs,
    ) {
        @Volatile var current: AutoSpendPrefs = a
        private val reloads = AtomicLong(0)

        /** The peer whose relay key both wallets know (null: none, so the free reply cannot even be tried). */
        @Volatile var relayKeyFor: String? = null

        /** A phrase change: [wallet]'s stores become the live ones and every in-memory copy is re-read. */
        fun claim(wallet: AutoSpendPrefs) {
            current = wallet
            reloads.incrementAndGet()
        }

        val proxy: ZchatPreferences =
            Proxy.newProxyInstance(
                ZchatPreferences::class.java.classLoader,
                arrayOf(ZchatPreferences::class.java),
            ) { p, method, args ->
                when {
                    method.name == "walletScopeGeneration" -> reloads.get()
                    method.name == "getPeerNostrPubkey" && relayKeyFor != null && args?.get(0) == relayKeyFor -> JoinHeldCopy.RELAY_KEY
                    method.name == "toString" -> "TwoWallets"
                    method.name == "hashCode" -> System.identityHashCode(p)
                    method.name == "equals" -> p === args?.get(0)
                    else ->
                        try {
                            method.invoke(current.proxy, *(args ?: emptyArray()))
                        } catch (e: InvocationTargetException) {
                            throw e.targetException
                        }
                }
            } as ZchatPreferences
    }

    private fun invitePayload(inviter: String): String =
        JSONObject()
            .put("name", "Synthetic group")
            .put("inviter", inviter)
            .put("group_key", Base64.getEncoder().encodeToString(ByteArray(32) { it.toByte() }))
            .put("members", JSONArray().put(inviter).put(HandshakeHarness.SELF))
            .toString()

    private fun processInvite(
        vm: ChatViewModel,
        groupId: String,
        inviter: String,
    ) {
        ChatViewModel::class.java
            .getDeclaredMethod(
                "processGroupInvite",
                String::class.java,
                String::class.java,
                TransactionId::class.java,
                Instant::class.java,
                String::class.java,
                String::class.java,
            ).apply { isAccessible = true }
            .invoke(vm, groupId, invitePayload(inviter), null, Instant.EPOCH, null, null)
    }

    private suspend fun chainTip(vm: ChatViewModel) {
        ChatViewModel::class.java
            .getDeclaredMethod("onChainTip", Long::class.javaObjectType)
            .apply { isAccessible = true }
            .invoke(vm, HandshakeHarness.START_HEIGHT)
        awaitIdle(vm)
    }

    private suspend fun screen(
        prefs: ZchatPreferences,
        seam: HandshakeSeam,
    ): ChatViewModel = chatViewModelWith(prefs, seam).also(AutoSpendVm::wireBootstrapFields)

    /** Wallet A's invite waits for Join; wallet B's stores hold the same group (both phrases were invited). */
    private suspend fun aWaitsForJoin(
        wallets: TwoWallets,
        seam: HandshakeSeam,
        groupId: String,
        inviter: String,
    ): ChatViewModel {
        val underA = screen(wallets.proxy, seam)
        processInvite(underA, groupId, inviter)
        awaitIdle(underA)
        assertTrue("fixture sanity: A's reply waits for A's Join", wallets.a.needsJoin.containsKey(groupId))
        wallets.b.groupInfo[groupId] = wallets.a.groupInfo.getValue(groupId)
        return underA
    }

    @Test
    fun `a phrase change during Join's relay try keeps the reply with wallet A, and A's Join sends it once when A is back`() =
        runBlocking {
            val inviter = "usyntheticjoinphrase2841"
            val groupId = "GJOINPH1"
            val wallets =
                TwoWallets(
                    AutoSpendPrefs(consent = false).apply { session(inviter, complete = true) },
                    AutoSpendPrefs(consent = true).apply { session(inviter, complete = true) },
                )
            val seam = HandshakeSeam(inviter)
            val underA = aWaitsForJoin(wallets, seam, groupId, inviter)

            // Join under A; the phrase changes while the relay publish is in flight; then the relays take nothing.
            wallets.relayKeyFor = inviter
            val publishEntered = CompletableDeferred<Unit>()
            val relayAnswer = CompletableDeferred<NostrChatBridge.PublishResult>()
            NostrChatBridge.registerPublisher { _, _ ->
                publishEntered.complete(Unit)
                relayAnswer.await()
            }
            assertTrue("fixture sanity: Join released the reply", joinNoWait(underA, groupId))
            withTimeout(TIMEOUT_MS) { publishEntered.await() }
            wallets.claim(wallets.b)
            relayAnswer.complete(NostrChatBridge.PublishResult(acks = 0, messageId = null))
            awaitIdle(underA)
            NostrChatBridge.unregisterPublisher()

            assertEquals("wallet A's reply was paid from wallet B", 0, seam.proposalRequests.get())
            assertTrue("wallet A's reply was written into wallet B's store", wallets.b.heldGroupAccepts.isEmpty())
            assertTrue("wallet A's reply was written into wallet B's store as waiting for Join", wallets.b.needsJoin.isEmpty())

            // B's own screen: a block and a Join there never answer A's invite.
            val underB = screen(wallets.proxy, seam)
            chainTip(underB)
            assertFalse("wallet B's Join answered wallet A's invite", GroupInviteDriver.join(underB, groupId))
            assertEquals("wallet B paid wallet A's reply", 0, seam.proposalRequests.get())
            assertTrue(wallets.b.heldGroupAccepts.isEmpty())
            assertTrue(wallets.b.needsJoin.isEmpty())

            // A is restored in the same process: its first block offers Join again, and Join sends the one reply.
            wallets.claim(wallets.a)
            val backToA = screen(wallets.proxy, seam)
            chainTip(backToA)
            assertTrue(
                "wallet A's stores hold neither the waiting record nor a held copy: its reply was lost with the phrase " +
                    "change during Join, nothing offers Join again and the creator keeps A Invited",
                wallets.a.needsJoin.containsKey(groupId),
            )
            assertEquals("a block paid wallet A's reply that waits for Join", 0, seam.submits.get())
            assertTrue(GroupInviteDriver.join(backToA, groupId))
            assertEquals("wallet A's reply must be sent once when A is back and joins", 1, seam.submits.get())
            chainTip(backToA)
            assertFalse(GroupInviteDriver.join(backToA, groupId))
            assertEquals("wallet A's reply was paid twice", 1, seam.submits.get())
            assertTrue(wallets.a.heldGroupAccepts.isEmpty())
            assertTrue(wallets.b.heldGroupAccepts.isEmpty())
        }

    /** Control (passes before and after): with no phrase change, a failed relay try under A is paid once by that Join. */
    @Test
    fun `control - with no phrase change a Join whose relay try fails pays wallet A's one reply`() =
        runBlocking {
            val inviter = "usyntheticjoinphrase2842"
            val groupId = "GJOINPH2"
            val wallets =
                TwoWallets(
                    AutoSpendPrefs(consent = false).apply { session(inviter, complete = true) },
                    AutoSpendPrefs(consent = false).apply { session(inviter, complete = true) },
                )
            val seam = HandshakeSeam(inviter)
            val underA = aWaitsForJoin(wallets, seam, groupId, inviter)

            wallets.relayKeyFor = inviter
            NostrChatBridge.registerPublisher { _, _ -> NostrChatBridge.PublishResult(acks = 0, messageId = null) }
            assertTrue(GroupInviteDriver.join(underA, groupId))

            assertEquals("Join must pay exactly one reply", 1, seam.submits.get())
            assertTrue(wallets.a.heldGroupAccepts.isEmpty())
            assertFalse(wallets.a.needsJoin.containsKey(groupId))
            assertTrue(wallets.b.heldGroupAccepts.isEmpty())
            assertTrue(wallets.b.needsJoin.isEmpty())
        }
}
