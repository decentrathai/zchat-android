package co.electriccoin.zcash.ui.screen.chat.viewmodel

import co.electriccoin.zcash.ui.nostr.NostrChatBridge
import co.electriccoin.zcash.ui.screen.chat.datasource.ZchatPreferences
import co.electriccoin.zcash.ui.screen.chat.model.GroupInfo
import co.electriccoin.zcash.ui.screen.chat.model.GroupInviteWaitRecord
import co.electriccoin.zcash.ui.screen.chat.model.SendMessageState
import co.electriccoin.zcash.ui.screen.chat.model.ZMSGGroupProtocol
import co.electriccoin.zcash.ui.screen.chat.viewmodel.GroupInviteDriver.chainTip
import co.electriccoin.zcash.ui.screen.chat.viewmodel.GroupInviteDriver.plaintextInvite
import co.electriccoin.zcash.ui.screen.chat.viewmodel.GroupInviteDriver.processInvite
import co.electriccoin.zcash.ui.screen.chat.viewmodel.HandshakeHarness.START_HEIGHT
import co.electriccoin.zcash.ui.screen.chat.viewmodel.HandshakeHarness.awaitIdle
import co.electriccoin.zcash.ui.screen.chat.viewmodel.HandshakeHarness.chatViewModelWith
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.time.Instant

/**
 * Round 8 P1 (PHONE_TRIAGE F3) — a standing "Allow" pays every later group join, with no question.
 *
 * The handshake-spend consent is one app-wide answer, kept for good. D4 made an invite from an address we never
 * chatted with wait for Join, but an invite from anyone we already know (a 1:1 chat with E2E, a key, a saved contact)
 * is still joined and answered at once: when the free relay reply cannot go (no relay key for the inviter), the "I
 * joined" reply (GROUP_ACCEPT) is held, and under the consent it is paid on-chain straight away (about 17,000
 * zatoshi per invite, no budget, any contact can trigger it). A later block or a later Allow pays any that were held.
 * On the phones: HONOR paid `7b03de311011` in the same block as the invite.
 *
 * Wanted: a group join is never paid under the standing consent. When the free relay reply cannot go, the invite
 * waits for Join (a needs-join record, the list row says so) and nothing is paid; Join pays that one group's reply
 * once and neither reads nor sets the consent. A reply a 2.13.0 test build stored for the consent becomes a
 * needs-join record at the first block, never a payment. (W-137, ZC-922: the free relay reply is no longer automatic,
 * every invite with a key waits for Join; Join sends it free when a relay takes it.) KEX and ZBOOT under
 * the consent still pay with no question. The consent dialog says it remembers the answer and that group joins
 * always ask.
 *
 * The new entry point (joinGroupInvite) is reached by name, so this file compiles before it exists. All addresses,
 * keys, group ids and txids are synthetic.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class KnownContactPaidGroupInviteWaitsForJoinTest {
    private val sendScopes = SendHarnessScopes()

    @Before
    fun setUp() {
        sendScopes.open()
        Dispatchers.setMain(UnconfinedTestDispatcher())
        AutoSpendVm.resetProcessState()
        GroupInviteDriver.resetKeystoneExplanation()
        NostrChatBridge.unregisterPublisher()
    }

    @After
    fun tearDown() {
        NostrChatBridge.unregisterPublisher()
        sendScopes.close()
        Dispatchers.resetMain()
        AutoSpendVm.resetProcessState()
        GroupInviteDriver.resetKeystoneExplanation()
    }

    private suspend fun screen(
        prefs: ZchatPreferences,
        seam: HandshakeSeam,
    ): ChatViewModel =
        chatViewModelWith(prefs, seam).also { vm ->
            AutoSpendVm.wireBootstrapFields(vm)
            AutoSpendVm.setField(vm, "contactBook", SavedContacts(emptySet()))
        }

    /** A phone that already has a 1:1 E2E chat with [inviter] (so D4 does not treat the invite as a stranger's). */
    private fun knownInviterPrefs(
        inviter: String,
        consent: Boolean,
    ): StrangerInvitePrefs = StrangerInvitePrefs(consent = consent).apply { e2eEnabled += inviter }

    /** The group screen's Join, by its new name (it does not exist before P1). */
    private suspend fun joinGroupInvite(
        vm: ChatViewModel,
        groupId: String,
    ): Boolean {
        val result =
            try {
                ChatViewModel::class.java.getMethod("joinGroupInvite", String::class.java).invoke(vm, groupId) as Boolean
            } catch (e: InvocationTargetException) {
                throw e.targetException
            }
        awaitIdle(vm)
        return result
    }

    /** The exact predicate the chat list uses for groupsAwaitingJoin (ChatListState.groupsAwaitingJoin). */
    private fun awaitingJoin(
        vm: ChatViewModel,
        groupId: String,
    ): Boolean =
        ChatViewModel::class.java
            .getDeclaredMethod("groupInviteNeedsJoin", String::class.java)
            .apply { isAccessible = true }
            .invoke(vm, groupId) != null

    private fun consentQuestionRaised(vm: ChatViewModel): Boolean =
        HandshakeHarness.sendMessageState(vm) is SendMessageState.NeedsHandshakeSpendConsent

    /** A group joined by a 2.13.0 test build and its "I joined" reply stored for the consent (the R3-4 record). */
    private fun seedStoredHeldAccept(
        prefs: StrangerInvitePrefs,
        groupId: String,
        inviter: String,
    ) {
        prefs.base.groupInfo[groupId] =
            ZMSGGroupProtocol.serializeGroupInfo(
                GroupInfo(
                    groupId = groupId,
                    name = "Synthetic held group",
                    creatorAddress = inviter,
                    createdAt = Instant.EPOCH,
                    groupKey = GroupInviteDriver.GROUP_KEY,
                    isActive = true,
                )
            )
        prefs.base.heldGroupAccepts[groupId] =
            JSONObject()
                .put("inviter", inviter)
                .put("accepter", HandshakeHarness.SELF)
                .put("memo", ZMSGGroupProtocol.createGroupAcceptMessage(groupId, HandshakeHarness.SELF, ""))
                .toString()
    }

    // ---- (a) a known contact's invite with no relay key waits for Join ------------------------------------------

    @Test
    fun `a known contact's invite whose reply cannot go over the relays is not paid under the consent and waits for Join`() =
        runBlocking {
            val inviter = SyntheticUnifiedAddress.mainnet(seed = 501)
            val prefs = knownInviterPrefs(inviter, consent = true)
            val seam = HandshakeSeam(inviter)
            val vm = screen(prefs.proxy, seam)

            processInvite(vm, "GKNOWN01", plaintextInvite(inviter))
            awaitIdle(vm)
            chainTip(vm, START_HEIGHT + 1)
            vm.grantHandshakeSpendConsent()
            awaitIdle(vm)

            assertTrue("fixture sanity: the group is saved", prefs.base.groupInfo.containsKey("GKNOWN01"))
            // [test-change-ok: W-137 ZC-922 every invite waits for Join] (was: the free relay reply was tried first)
            assertEquals("no reply, not even the free relay one, is tried before Join", 0, prefs.nostrLookups.get())
            assertEquals(
                "a known contact's group join was paid on-chain under the standing consent, with no question",
                0,
                seam.submits.get(),
            )
            assertEquals("a GROUP_ACCEPT was built for the chain", 0, seam.proposalRequests.get())
            assertTrue("the invite must wait for Join (a needs-join record)", prefs.needsJoin.containsKey("GKNOWN01"))
            assertTrue("the chat list must show the group as waiting for Join", awaitingJoin(vm, "GKNOWN01"))
            assertFalse("the reply must not stay held for the automatic sends", prefs.base.heldGroupAccepts.containsKey("GKNOWN01"))
            assertFalse("no consent question may be raised for a group join", consentQuestionRaised(vm))

            // [test-change-ok: W-137 ZC-922 ZC-1545 review follow-up] (was: the first Join paid the reply with no price shown)
            assertTrue("Join must release the waiting reply", joinGroupInvite(vm, "GKNOWN01"))
            assertEquals("the first Join paid the reply although no price was shown", 0, seam.submits.get())
            assertTrue(
                "the reply that cannot go free must be back to waiting for Join with its failed relay try (the price banner)",
                GroupInviteWaitRecord.relayFailed(prefs.needsJoin["GKNOWN01"]),
            )
            assertTrue("the second Join must release the reply", joinGroupInvite(vm, "GKNOWN01"))
            assertEquals("the second Join must pay exactly one reply", 1, seam.submits.get())
            assertFalse("Join must clear the waiting mark", prefs.needsJoin.containsKey("GKNOWN01"))

            chainTip(vm, START_HEIGHT + 2)
            vm.grantHandshakeSpendConsent()
            awaitIdle(vm)
            assertFalse("a second Join must send nothing", joinGroupInvite(vm, "GKNOWN01"))
            assertEquals("the reply was paid more than once", 1, seam.submits.get())
        }

    @Test
    fun `without consent a known contact's invite asks no question, and Join pays its one reply without touching the consent`() =
        runBlocking {
            val inviter = SyntheticUnifiedAddress.mainnet(seed = 502)
            val prefs = knownInviterPrefs(inviter, consent = false)
            val seam = HandshakeSeam(inviter)
            val vm = screen(prefs.proxy, seam)

            processInvite(vm, "GKNOWN02", plaintextInvite(inviter))
            awaitIdle(vm)
            chainTip(vm, START_HEIGHT + 1)

            assertEquals(0, seam.submits.get())
            assertTrue("the invite must wait for Join", prefs.needsJoin.containsKey("GKNOWN02"))
            assertFalse("the consent question was raised for a group join", consentQuestionRaised(vm))

            prefs.base.calls.clear()
            // [test-change-ok: W-137 ZC-922 ZC-1545 review follow-up] (was: the first Join paid, the price was never shown)
            assertTrue(joinGroupInvite(vm, "GKNOWN02"))
            assertEquals("the first Join paid the reply although no price was shown", 0, seam.submits.get())
            assertTrue(joinGroupInvite(vm, "GKNOWN02"))
            assertEquals("Join is the consent for its one reply: the shown-price Join must pay it", 1, seam.submits.get())
            assertFalse("Join raised the consent question", consentQuestionRaised(vm))
            assertFalse("Join gave the standing consent", prefs.base.consent)
            assertFalse(
                "Join wrote the standing consent",
                prefs.base.calls.any { it == "setConsentedToHandshakeSpend" },
            )
        }

    // ---- (b) the migration of a stored held reply ----------------------------------------------------------------

    @Test
    fun `a reply a test build stored for the consent becomes a needs-join record at the first block, never a payment`() =
        runBlocking {
            val inviter = SyntheticUnifiedAddress.mainnet(seed = 503)
            val prefs = knownInviterPrefs(inviter, consent = true)
            seedStoredHeldAccept(prefs, "GHELD03", inviter)
            val seam = HandshakeSeam(inviter)
            val vm = screen(prefs.proxy, seam)

            chainTip(vm, START_HEIGHT + 1)
            chainTip(vm, START_HEIGHT + 2)
            vm.grantHandshakeSpendConsent()
            awaitIdle(vm)

            assertEquals("a stored held reply was paid at the first block under the consent", 0, seam.submits.get())
            assertEquals(0, seam.proposalRequests.get())
            assertTrue("the stored reply must become a needs-join record", prefs.needsJoin.containsKey("GHELD03"))
            assertTrue("the chat list must show the group as waiting for Join", awaitingJoin(vm, "GHELD03"))
            assertFalse("the stored held copy must not stay behind", prefs.base.heldGroupAccepts.containsKey("GHELD03"))

            assertTrue(joinGroupInvite(vm, "GHELD03"))
            assertEquals("Join must pay the migrated reply once", 1, seam.submits.get())
        }

    // ---- (c) two invites, Join each (control: passes before and after) ------------------------------------------

    @Test
    fun `control - two invites from one contact, each joined, pay exactly two replies`() =
        runBlocking {
            val inviter = SyntheticUnifiedAddress.mainnet(seed = 504)
            val prefs = knownInviterPrefs(inviter, consent = true)
            val seam = HandshakeSeam(inviter)
            val vm = screen(prefs.proxy, seam)

            processInvite(vm, "GTWO04A", plaintextInvite(inviter))
            processInvite(vm, "GTWO04B", plaintextInvite(inviter))
            awaitIdle(vm)
            chainTip(vm, START_HEIGHT + 1)
            // [test-change-ok: W-137 ZC-922 ZC-1545 review follow-up] (was: one Join each paid; now the first Join shows the price)
            for ((paidSoFar, groupId) in listOf("GTWO04A", "GTWO04B").withIndex()) {
                joinGroupInvite(vm, groupId)
                assertEquals("the first Join of $groupId paid with no price shown", paidSoFar, seam.submits.get())
                joinGroupInvite(vm, groupId)
            }
            chainTip(vm, START_HEIGHT + 2)
            vm.grantHandshakeSpendConsent()
            awaitIdle(vm)
            chainTip(vm, START_HEIGHT + 3)

            assertEquals("two groups joined must pay exactly two replies", 2, seam.submits.get())
            assertTrue(prefs.needsJoin.isEmpty())
        }

    // ---- controls: the free relay reply stays automatic; KEX and ZBOOT under consent still pay ------------------

    // [test-change-ok: W-137 ZC-922 every invite waits for Join] (was: a reply the relays accept joined at once, no Join)
    @Test
    fun `control - a reply the relays accept goes free on Join, with nothing paid and nothing sent before it`() =
        runBlocking {
            val inviter = SyntheticUnifiedAddress.mainnet(seed = 505)
            val prefs = knownInviterPrefs(inviter, consent = true)
            val relayed = mutableListOf<String>()
            NostrChatBridge.registerPublisher { memo, _ ->
                relayed += memo
                NostrChatBridge.PublishResult(acks = 1, messageId = "synthetic-relay-ack")
            }
            val seam = HandshakeSeam(inviter)
            val vm = screen(withRelayKey(prefs.proxy, inviter), seam)

            processInvite(vm, "GRELAY05", plaintextInvite(inviter))
            awaitIdle(vm)
            chainTip(vm, START_HEIGHT + 1)

            assertEquals("nothing may go over the relay before Join", 0, relayed.size)
            assertTrue("the invite must wait for Join", prefs.needsJoin.containsKey("GRELAY05"))
            assertEquals(0, seam.submits.get())

            assertTrue(joinGroupInvite(vm, "GRELAY05"))
            assertEquals("Join must send the reply over the relay once", 1, relayed.size)
            assertEquals("a relay reply must cost nothing on-chain", 0, seam.submits.get())
            assertFalse("Join must clear the waiting mark", prefs.needsJoin.containsKey("GRELAY05"))
        }

    @Test
    fun `control - a KEX and a ZBOOT under the consent still pay with no question`() =
        runBlocking {
            val kexPeer = "usyntheticp1kexcontrol00001"
            val kexPrefs = AutoSpendPrefs(consent = true).apply { session(kexPeer, complete = false) }
            kexPrefs.ownBootSent[kexPeer] = true
            val kexSeam = HandshakeSeam(kexPeer)
            val kexVm = chatViewModelWith(kexPrefs.proxy, kexSeam).also(AutoSpendVm::wireBootstrapFields)
            kexVm.sendKEXMessage(kexPeer, HandshakeHarness.SELF)
            awaitIdle(kexVm)
            assertEquals("a KEX under the consent must still pay", 1, kexSeam.broadcasts.size)

            val bootPeer = "usyntheticp1zbootcontrol0001"
            val bootPrefs =
                AutoSpendPrefs(consent = true).apply {
                    session(bootPeer, complete = true, mode = co.electriccoin.zcash.ui.screen.chat.model.ConversationMode.TUNNEL)
                }
            val bootSeam = HandshakeSeam(bootPeer)
            val bootVm = chatViewModelWith(bootPrefs.proxy, bootSeam).also(AutoSpendVm::wireBootstrapFields)
            AutoSpendVm.setHistoryRead(true)
            AutoSpendVm.sendNostrBoot(bootVm, bootPeer)
            awaitIdle(bootVm)
            assertEquals("a ZBOOT under the consent must still pay", 1, bootSeam.broadcasts.size)
            assertFalse(consentQuestionRaised(kexVm))
            assertFalse(consentQuestionRaised(bootVm))
        }

    // ---- (d) the consent dialog says the answer is remembered and group joins always ask ------------------------

    @Test
    fun `the consent footer says the answer is remembered and group joins always ask, in English and Spanish`() {
        val en = strings("values")
        val es = strings("values-es")
        val enFooter = assertNotNullValue(en["handshake_consent_footer"], "EN handshake_consent_footer")
        val esFooter = assertNotNullValue(es["handshake_consent_footer"], "ES handshake_consent_footer")
        assertTrue(
            enFooter,
            enFooter.contains(
                "ZChat remembers this answer and pays later chat handshakes without asking again. Group joins always ask."
            ),
        )
        assertTrue(
            esFooter,
            esFooter.contains(
                "ZChat recuerda esta respuesta y paga los siguientes intercambios de claves de chat sin volver a " +
                    "preguntar. Unirse a un grupo siempre pregunta."
            ),
        )
        val enBanner = assertNotNullValue(en["group_invite_join_known_paid"], "EN group_invite_join_known_paid")
        val esBanner = assertNotNullValue(es["group_invite_join_known_paid"], "ES group_invite_join_known_paid")
        assertEquals(
            "%1\$s added you to “%2\$s”. Your “I joined” reply couldn’t go over the relays. Join tries them once more " +
                "first; if they still can’t take it, the reply costs at least %3\$s ZEC. Nothing has been paid.",
            enBanner,
        )
        for (placeholder in listOf("%1\$s", "%2\$s", "%3\$s")) {
            assertTrue("ES banner lacks $placeholder: $esBanner", esBanner.contains(placeholder))
        }
        assertTrue(esBanner, esBanner.contains("No se ha pagado nada."))
    }

    private fun assertNotNullValue(
        value: String?,
        what: String,
    ): String {
        assertNotNull("$what is missing", value)
        return value!!
    }

    /** [delegate] with a relay key for [peer], so the free relay reply can go. */
    private fun withRelayKey(
        delegate: ZchatPreferences,
        peer: String,
    ): ZchatPreferences =
        Proxy.newProxyInstance(
            ZchatPreferences::class.java.classLoader,
            arrayOf(ZchatPreferences::class.java),
        ) { p, method, args ->
            when {
                method.name == "getPeerNostrPubkey" && args?.get(0) == peer -> {
                    // Counted by the delegate too, so "the relay was tried" still reads right.
                    method.invoke(delegate, *args)
                    "ab".repeat(32)
                }
                method.name == "hashCode" -> System.identityHashCode(p)
                method.name == "equals" -> p === args?.get(0)
                else ->
                    try {
                        method.invoke(delegate, *(args ?: emptyArray()))
                    } catch (e: InvocationTargetException) {
                        throw e.targetException
                    }
            }
        } as ZchatPreferences

    private val repoRoot: File by lazy {
        var dir: File? = File("").absoluteFile
        while (dir != null && !(File(dir, "gradle.properties").isFile && File(dir, "README.md").isFile)) {
            dir = dir.parentFile
        }
        requireNotNull(dir) { "repo root not found above the test working dir" }
    }

    private fun strings(dir: String): Map<String, String> =
        File(repoRoot, "ui-lib/src/main/res/ui")
            .listFiles()
            .orEmpty()
            .flatMap { File(it, dir).listFiles { f -> f.extension == "xml" }.orEmpty().toList() }
            .fold(emptyMap()) { acc, f ->
                acc +
                    Regex("""<string name="([^"]+)"[^>]*>(.*?)</string>""", RegexOption.DOT_MATCHES_ALL)
                        .findAll(f.readText())
                        .associate { it.groupValues[1] to it.groupValues[2].replace("\\'", "'").replace("\\\"", "\"") }
            }
}
