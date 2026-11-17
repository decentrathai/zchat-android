package co.electriccoin.zcash.ui.screen.chat.viewmodel

import androidx.lifecycle.viewModelScope
import cash.z.ecc.android.sdk.fixture.AccountFixture
import cash.z.ecc.android.sdk.model.WalletAddress
import cash.z.ecc.android.sdk.model.WalletBalance
import cash.z.ecc.android.sdk.model.Zatoshi
import co.electriccoin.zcash.ui.common.datasource.AccountDataSource
import co.electriccoin.zcash.ui.common.model.KeystoneAccount
import co.electriccoin.zcash.ui.common.model.TransparentInfo
import co.electriccoin.zcash.ui.common.model.UnifiedInfo
import co.electriccoin.zcash.ui.nostr.RelayCopyMarksApi
import co.electriccoin.zcash.ui.screen.chat.model.ConversationMode
import co.electriccoin.zcash.ui.screen.chat.viewmodel.HandshakeHarness.awaitIdle
import co.electriccoin.zcash.ui.screen.chat.viewmodel.RelayIdentity.PEER
import co.electriccoin.zcash.ui.screen.chat.viewmodel.RelayIdentity.PEER_RELAY_KEY
import co.electriccoin.zcash.ui.screen.chat.viewmodel.RelayIdentity.ourRelayKey
import co.electriccoin.zcash.ui.screen.chat.viewmodel.RelayIdentity.repairAtChatOpen
import co.electriccoin.zcash.ui.screen.chat.viewmodel.RelayIdentity.tick
import co.electriccoin.zcash.ui.screen.chat.viewmodel.RelayIdentity.zboot
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.lang.reflect.Proxy

/**
 * ZC-420 (high) and ZC-736 (fix-all 2.13.0, train T6b; ZC-420-SPEC §2.2 S1, §3.2, §3.4, §4.2): a relay identity (ZBOOT)
 * that only a public relay took is remembered and delivered again over the relays, for free.
 *
 * The relay-first block of sendNostrBootHandshake counts a ZBOOT any readable relay took as delivered (ZC-393 R1: that
 * decision is not changed) and sets the delivered-identity marker. When only primal took it, a friend on 2.12.4, who
 * reads only ZChat's relay and nos.lol, never gets it once the app restarts before the relay outbox hands the kept wrap
 * on; nothing repairs it (the marker returns early at every trigger). Wanted: a durable mark of that copy ("zb|<peer>",
 * written before the publish, ZC-736), and a repair at chat open and at each block that confirms it from the kept wrap
 * or publishes the same identity once more over the relays, never on-chain, with the gates of spec §3.4 item 4.
 *
 * The chat is PaidRelayFallbacksKeepTheirCountTest's (copied into [RelayIdentityChat]; that test is not edited). A
 * "restart" is a new ChatViewModel over the same store and marks, with nothing kept (the resender answers null).
 * Every key is made per run; every address is synthetic. Controls pass before and after the fix.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ZbootOnlyAPublicRelayTookIsRedeliveredTest {
    private val sendScopes = SendHarnessScopes()
    private val net = IdentityRelayNet()
    private val ours = ourRelayKey(0)

    @Before
    fun setUp() {
        sendScopes.open()
        Dispatchers.setMain(UnconfinedTestDispatcher())
        AutoSpendVm.resetProcessState()
        AutoSpendVm.setHistoryRead(true)
        net.install()
    }

    @After
    fun tearDown() {
        net.uninstall()
        RelayCopyMarksApi.unregister()
        sendScopes.close()
        Dispatchers.resetMain()
        AutoSpendVm.resetProcessState()
    }

    /** The marks must exist for every case but the controls. */
    private fun RelayIdentityChat.marksOrFail() {
        assertTrue("this build keeps no relay-copy mark (RelayCopyMarks, ZC-420): nothing can repair the identity", registerMarks())
    }

    /** The send of (a): the ZBOOT relay-first block with [answer]; the chat's first view model. */
    private suspend fun RelayIdentityChat.firstSend(answer: Triple<Int, String, Int> = Triple(0, "rumor-a", 1)): ChatViewModel {
        net.answer = answer
        val vm = viewModel()
        vm.ensureNostrBootstrapSent(PEER)
        awaitIdle(vm)
        return vm
    }

    /** A restart: nothing kept, a new view model over the same store and marks. */
    private suspend fun RelayIdentityChat.restart(answer: Triple<Int, String, Int> = Triple(1, "rumor-b", 1)): ChatViewModel {
        net.resender = null
        net.answer = answer
        return viewModel()
    }

    private fun RelayIdentityChat.assertMark(
        rumorId: String?,
        extra: String,
    ) {
        val slot = slot()
        assertNotNull("no relay-copy mark for the contact", marks.map[slot])
        if (rumorId != null) assertEquals("the mark's rumor id", rumorId, marks.rumorId(slot))
        assertEquals("the mark's identity", extra, marks.extra(slot))
    }

    private fun assertOneZbootTo(
        chat: RelayIdentityChat,
        published: IdentityRelayNet.Published,
        key: String,
        epoch: Long = 0L,
    ) {
        assertEquals("the ZBOOT went to another relay key", PEER_RELAY_KEY, published.to)
        val boot = zboot(published.memo)
        assertEquals("the ZBOOT carries another relay key", key, boot.senderNostrPubkeyHex)
        assertEquals("the ZBOOT's epoch", epoch, boot.epoch)
        assertEquals(3, boot.version)
        assertTrue("the ZBOOT does not verify under our E2E key", RelayIdentity.verifiesUnderOurKey(chat, boot))
    }

    // ── (a)-(e): marked, and delivered again ───────────────────────────────────────────────────────────

    @Test
    fun `a - a ZBOOT only a public relay took leaves the marker and a mark with our relay key and its rumor id`() =
        runBlocking {
            val chat = RelayIdentityChat().apply { marksOrFail() }

            chat.firstSend()

            assertEquals("the free relay ZBOOT started an on-chain one", 0, chat.seam.proposalRequests.get())
            assertEquals("today's result: the identity is marked delivered", ours, chat.marker())
            chat.assertMark("rumor-a", ours)
        }

    @Test
    fun `b - after a restart the chat-open repair publishes the same identity once over the relays and the mark goes`() =
        runBlocking {
            val chat = RelayIdentityChat().apply { marksOrFail() }
            chat.firstSend()
            val vm = chat.restart()

            repairAtChatOpen(vm)
            awaitIdle(vm)

            assertEquals("exactly one more publish", 2, net.published.size)
            assertOneZbootTo(chat, net.published[1], ours)
            assertNull("a shared relay took it: the mark goes", chat.marks.map[chat.slot()])
            assertEquals(0, chat.seam.proposalRequests.get())
            assertEquals("the marker is unchanged", ours, chat.marker())

            repairAtChatOpen(vm)
            awaitIdle(vm)
            assertEquals("a second chat open published again", 2, net.published.size)
        }

    @Test
    fun `c - after a restart the block tick repairs it once, and later ticks publish nothing`() =
        runBlocking {
            val chat = RelayIdentityChat().apply { marksOrFail() }
            chat.firstSend()
            val vm = chat.restart()

            tick(vm, 3_000_001L)
            awaitIdle(vm)

            assertEquals(2, net.published.size)
            assertOneZbootTo(chat, net.published[1], ours)
            assertNull(chat.marks.map[chat.slot()])
            repeat(3) { tick(vm, 3_000_002L + it) }
            awaitIdle(vm)
            assertEquals("a later tick published again", 2, net.published.size)
            assertEquals(0, chat.seam.proposalRequests.get())
        }

    @Test
    fun `d - in the same process the kept wrap is asked first - not shared keeps the mark, shared removes it, nothing new is made`() =
        runBlocking {
            val chat = RelayIdentityChat().apply { marksOrFail() }
            val vm = chat.firstSend()

            net.resender = { Triple(0, it, 1) }
            repairAtChatOpen(vm)
            awaitIdle(vm)
            assertEquals("the kept wrap of the mark's rumor id was not asked for", listOf("rumor-a"), net.resendAsks)
            assertEquals("a new ZBOOT was made while the wrap is kept", 1, net.published.size)
            chat.assertMark("rumor-a", ours)

            net.resender = { Triple(1, it, 1) }
            repairAtChatOpen(vm)
            awaitIdle(vm)
            assertEquals(1, net.published.size)
            assertNull("ZChat's relay holds the kept wrap now: the mark goes", chat.marks.map[chat.slot()])
        }

    @Test
    fun `e - a re-publish only a public relay took again keeps the mark with the new rumor id, and the next tick asks for that wrap`() =
        runBlocking {
            val chat = RelayIdentityChat().apply { marksOrFail() }
            chat.firstSend()
            val vm = chat.restart(Triple(0, "rumor-e", 1))

            tick(vm, 3_000_001L)
            awaitIdle(vm)
            assertEquals(2, net.published.size)
            chat.assertMark("rumor-e", ours)

            net.resendAsks.clear()
            net.resender = { Triple(0, it, 1) }
            tick(vm, 3_000_002L)
            awaitIdle(vm)
            assertEquals("the next tick did not ask for the new wrap", listOf("rumor-e"), net.resendAsks)
            assertEquals("the next tick made another ZBOOT while the new wrap is kept", 2, net.published.size)
        }

    // ── (f)-(j) ──────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `f - no relay at the repair, the standing consent and an allowance - nothing is paid over 20 ticks and 3 chat opens`() =
        runBlocking {
            val chat = RelayIdentityChat().apply { marksOrFail() }
            chat.zbootQuoted += PEER
            chat.firstSend()
            val vm = chat.restart(Triple(0, "rumor-f", 0))

            repeat(20) { tick(vm, 3_000_001L + it) }
            repeat(3) { repairAtChatOpen(vm) }
            awaitIdle(vm)

            assertEquals("a repair paid on-chain", 0, chat.seam.proposalRequests.get())
            assertTrue("fixture: the repair tried the relays", net.published.size > 1)
            chat.assertMark("rumor-a", ours)
            assertEquals(ours, chat.marker())
        }

    @Test
    fun `g - a Shielded chat with a mark is repaired at chat open and by the tick`() =
        runBlocking {
            val chat = RelayIdentityChat().apply { marksOrFail() }
            chat.firstSend()
            chat.prefs.modes[PEER] = ConversationMode.VAULT
            val vm = chat.restart()

            repairAtChatOpen(vm)
            awaitIdle(vm)
            assertEquals("the chat-open repair skipped a Shielded chat", 2, net.published.size)
            assertNull(chat.marks.map[chat.slot()])

            // Marked again, and repaired by the tick.
            chat.marks.map[chat.slot()] = "rumor-g|${System.currentTimeMillis()}|$ours"
            tick(vm, 3_000_001L)
            awaitIdle(vm)
            assertEquals("the tick skipped a Shielded chat", 3, net.published.size)
            assertNull(chat.marks.map[chat.slot()])
            assertEquals(0, chat.seam.proposalRequests.get())
        }

    @Test
    fun `h - with a Keystone account selected the repair publishes nothing and the mark stays`() =
        runBlocking {
            val chat = RelayIdentityChat().apply { marksOrFail() }
            chat.firstSend()
            val vm = chat.restart()
            AutoSpendVm.setField(vm, "accountDataSource", keystoneSelected(keystoneAccount()))

            repairAtChatOpen(vm)
            tick(vm, 3_000_001L)
            awaitIdle(vm)

            assertEquals("a repair published on a Keystone account", 1, net.published.size)
            chat.assertMark("rumor-a", ours)
        }

    @Test
    fun `i - a cleared marker (Reconnect) drops the mark and the repair publishes nothing`() =
        runBlocking {
            val chat = RelayIdentityChat().apply { marksOrFail() }
            chat.firstSend()
            chat.prefs.sentNostrBoot.remove(PEER)
            val vm = chat.restart()

            repairAtChatOpen(vm)
            awaitIdle(vm)

            assertEquals(1, net.published.size)
            assertNull("the sender owns a re-armed marker: the mark goes", chat.marks.map[chat.slot()])
        }

    @Test
    fun `j - two signatures of one ZBOOT differ, and both verify`() =
        runBlocking {
            val chat = RelayIdentityChat().apply { marksOrFail() }
            chat.firstSend()
            val vm = chat.restart()
            repairAtChatOpen(vm)
            awaitIdle(vm)

            val first = zboot(net.published[0].memo)
            val second = zboot(net.published[1].memo)
            assertEquals(first.signedData(), second.signedData())
            val why = "the re-delivered ZBOOT repeats the signature (a 2.12.4 contact would drop it as seen)"
            assertNotEquals(why, first.signature, second.signature)
            assertTrue(RelayIdentity.verifiesUnderOurKey(chat, first) && RelayIdentity.verifiesUnderOurKey(chat, second))
        }

    // ── (k)-(r): the design check of 6 Oct ─────────────────────────────────────────────────────────────

    @Test
    fun `k - the mark is on disk before the publish answers, and after a kill a new view model's tick delivers it once (ZC-736)`() =
        runBlocking {
            val chat = RelayIdentityChat().apply { marksOrFail() }
            net.hold = CompletableDeferred()
            val first = chat.viewModel()
            first.ensureNostrBootstrapSent(PEER)
            HandshakeHarness.awaitTrue { net.published.size == 1 }

            assertEquals("fixture: the marker is set before the relay step", ours, chat.marker())
            chat.assertMark("", ours)

            // A kill: the publish never answers and nothing of that process runs again (its attempt stays where it is,
            // and its process-wide claim on the contact is gone with the process). Cancelling the attempt is no kill: the
            // relay-first block reads a cancelled publish as "no relay took it" and goes on to its on-chain step.
            assertEquals("fixture: nothing was paid before the kill", 0, chat.seam.proposalRequests.get())
            net.hold = null
            clearProcessZbootClaims()
            chat.marks.age(chat.slot(), 2 * 60_000L)
            val vm = chat.restart()

            tick(vm, 3_000_001L)
            // Only the new view model's work: the cut-off attempt of the old process never ends.
            withTimeout(HandshakeHarness.TIMEOUT_MS) {
                vm.viewModelScope.coroutineContext.job.children
                    .forEach { it.join() }
            }

            assertEquals("the tick did not deliver the identity a kill cut off", 2, net.published.size)
            assertOneZbootTo(chat, net.published[1], ours)
            assertNull(chat.marks.map[chat.slot()])
            assertEquals(0, chat.seam.proposalRequests.get())
        }

    @Test
    fun `l - with the contact's relay key missing nothing is published and the mark stays, and with the key back the next tick delivers`() =
        runBlocking {
            val chat = RelayIdentityChat().apply { marksOrFail() }
            chat.firstSend()
            val vm = chat.restart()
            chat.peerRelayKey.set(null)

            tick(vm, 3_000_001L)
            awaitIdle(vm)
            assertEquals(1, net.published.size)
            chat.assertMark("rumor-a", ours)

            chat.peerRelayKey.set(PEER_RELAY_KEY)
            tick(vm, 3_000_002L)
            awaitIdle(vm)
            assertEquals(2, net.published.size)
            assertNull(chat.marks.map[chat.slot()])
        }

    @Test
    fun `m - a contact with no conversation id (a deleted chat) - the mark is dropped and no conversation id is made`() =
        runBlocking {
            val chat = RelayIdentityChat().apply { marksOrFail() }
            chat.firstSend()
            val vm = chat.restart()
            chat.conversationKnown.set(false)
            val createsBefore = chat.conversationCreates.get()

            repairAtChatOpen(vm)
            awaitIdle(vm)

            assertEquals(1, net.published.size)
            assertNull(chat.marks.map[chat.slot()])
            assertEquals("the repair made a conversation id for a deleted chat", createsBefore, chat.conversationCreates.get())
        }

    @Test
    fun `n - while the seed is not ready the mark stays and nothing is published`() =
        runBlocking {
            val chat = RelayIdentityChat().apply { marksOrFail() }
            chat.firstSend()
            val vm = chat.restart()
            AutoSpendVm.setField(vm, "persistableWalletProvider", SeedNotReadyWalletProvider)

            repairAtChatOpen(vm)
            awaitIdle(vm)

            assertEquals(1, net.published.size)
            chat.assertMark("rumor-a", ours)
        }

    @Test
    fun `o - no relay took the first publish and the ZBOOT was paid on-chain - a later tick hands it to the relays for free`() =
        runBlocking {
            val chat = RelayIdentityChat().apply { marksOrFail() }
            chat.firstSend(Triple(0, "rumor-o", 0))
            assertEquals("fixture: today's on-chain fallback", 1, chat.seam.proposalRequests.get())
            chat.assertMark("", ours)

            chat.marks.age(chat.slot(), 2 * 60_000L)
            val vm = chat.restart()
            tick(vm, 3_000_001L)
            awaitIdle(vm)

            assertEquals(2, net.published.size)
            assertOneZbootTo(chat, net.published[1], ours)
            assertNull(chat.marks.map[chat.slot()])
            assertEquals("the repair paid", 1, chat.seam.proposalRequests.get())
        }

    @Test
    fun `p - no relay and nothing paid (RELAY_ONLY) - the marker is re-armed and the next repair drops the mark`() =
        runBlocking {
            val chat = RelayIdentityChat().apply { marksOrFail() }
            net.answer = Triple(0, "rumor-p", 0)
            val first = chat.viewModel()
            sendRelayOnly(first)
            awaitIdle(first)
            assertEquals("fixture: RELAY_ONLY pays nothing", 0, chat.seam.proposalRequests.get())
            assertNull("fixture: the marker is re-armed", chat.marker())

            chat.marks.age(chat.slot(), 2 * 60_000L)
            val vm = chat.restart()
            // The chat-open repair (a tick would also run the Tunnel block retry, which owns a re-armed marker).
            repairAtChatOpen(vm)
            awaitIdle(vm)

            assertEquals(1, net.published.size)
            assertNull("the mark of a ZBOOT the sender re-armed stays", chat.marks.map[chat.slot()])
        }

    @Test
    fun `q - a mark of an older identity while the marker names the current one delivers the current identity, not the kept wrap`() =
        runBlocking {
            val chat = RelayIdentityChat().apply { marksOrFail() }
            val current = ourRelayKey(1)
            chat.rotation.set(1)
            chat.prefs.sentNostrBoot[PEER] = current
            chat.marks.map[chat.slot()] = "rumor-old|${System.currentTimeMillis() - 5 * 60_000L}|$ours"
            val vm = chat.restart()
            net.resender = { Triple(1, it, 1) }

            repairAtChatOpen(vm)
            awaitIdle(vm)

            assertEquals("the kept wrap of the older identity was asked for", emptyList<String>(), net.resendAsks)
            assertEquals(1, net.published.size)
            assertOneZbootTo(chat, net.published[0], current, epoch = 1L)
            assertNull(chat.marks.map[chat.slot()])
        }

    @Test
    fun `r - the tick takes its list off the caller's thread`() =
        runBlocking {
            val chat = RelayIdentityChat().apply { marksOrFail() }
            chat.firstSend()
            val vm = chat.restart()
            chat.marks.slotsThreads.clear()

            tick(vm, 3_000_001L)
            awaitIdle(vm)

            assertTrue("the tick did not list the marks", chat.marks.slotsThreads.isNotEmpty())
            assertTrue(
                "the tick listed the marks (a decrypt of the whole store) on the caller's thread",
                chat.marks.slotsThreads.none { it === Thread.currentThread() },
            )
        }

    @Test
    fun `a mark whose marker the history pass wrote (not a sender) is delivered over the relays for free`() =
        runBlocking {
            val chat = RelayIdentityChat().apply { marksOrFail() }
            // ZC-773: a complete history pass that read our mined ZBOOT of the current identity writes the marker itself.
            chat.prefs.sentNostrBoot[PEER] = ours
            chat.marks.map[chat.slot()] = "rumor-h|${System.currentTimeMillis() - 5 * 60_000L}|$ours"
            val vm = chat.restart()

            tick(vm, 3_000_001L)
            awaitIdle(vm)

            assertEquals(1, net.published.size)
            assertOneZbootTo(chat, net.published[0], ours)
            assertNull(chat.marks.map[chat.slot()])
            assertEquals(0, chat.seam.proposalRequests.get())
        }

    // ── controls (pass before and after) ─────────────────────────────────────────────────────────────

    @Test
    fun `control - a ZBOOT a shared relay took leaves no mark`() =
        runBlocking {
            val chat = RelayIdentityChat()
            val marked = chat.registerMarks()

            chat.firstSend(Triple(1, "rumor-c", 1))

            assertEquals(0, chat.seam.proposalRequests.get())
            assertEquals(ours, chat.marker())
            if (marked) assertTrue("a ZBOOT a shared relay took was marked: ${chat.marks.map.keys}", chat.marks.map.isEmpty())
        }

    @Test
    fun `control - a ZBOOT no relay took falls back on-chain as today`() =
        runBlocking {
            val chat = RelayIdentityChat()
            chat.registerMarks()

            chat.firstSend(Triple(0, "rumor-n", 0))

            assertEquals(1, chat.seam.proposalRequests.get())
        }

    // ── helpers ──────────────────────────────────────────────────────────────────────────────────────

    /** A new process: ChatViewModel's process-wide ZBOOT claims (the in-flight set) start empty. */
    private fun clearProcessZbootClaims() {
        val field = ChatViewModel::class.java.declaredFields.first { it.name == "nostrBootInFlight" }
        field.isAccessible = true
        (field.get(null) as MutableSet<*>).clear()
    }

    /** sendNostrBootHandshake(peer, ZbootOnChainRule.RELAY_ONLY): a confirmed switch whose quote did not count our ZBOOT. */
    private fun sendRelayOnly(vm: ChatViewModel) {
        val rule =
            Class
                .forName(ChatViewModel::class.java.name + "\$ZbootOnChainRule")
                .enumConstants!!
                .first { it.toString() == "RELAY_ONLY" }
        val method =
            ChatViewModel::class.java.declaredMethods.first {
                it.name == "sendNostrBootHandshake" && it.parameterCount == 5 && it.parameterTypes[1] == rule.javaClass
            }
        method.isAccessible = true
        method.invoke(vm, PEER, rule, false, null, null)
    }

    /** An account source whose selected account is [account] (a Keystone account: T1b-1 §2). */
    private fun keystoneSelected(account: KeystoneAccount): AccountDataSource =
        Proxy.newProxyInstance(AccountDataSource::class.java.classLoader, arrayOf(AccountDataSource::class.java)) { _, method, _ ->
            when (method.name) {
                "getSelectedAccount" -> account
                "toString" -> "KeystoneSelected"
                else -> null
            }
        } as AccountDataSource

    private suspend fun keystoneAccount(): KeystoneAccount {
        val zero = WalletBalance(Zatoshi(0), Zatoshi(0), Zatoshi(0))
        return KeystoneAccount(
            sdkAccount = AccountFixture.new(),
            unified = UnifiedInfo(WalletAddress.Unified.new("usynthetic-zc420-keystone"), zero),
            transparent = TransparentInfo(WalletAddress.Transparent.new("tsynthetic-zc420-keystone"), Zatoshi(0)),
            isSelected = true,
        )
    }
}
