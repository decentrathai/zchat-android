package co.electriccoin.zcash.ui.screen.chat.view

import co.electriccoin.zcash.ui.nostr.NostrChatBridge
import co.electriccoin.zcash.ui.screen.chat.datasource.ZchatPreferences
import co.electriccoin.zcash.ui.screen.chat.model.GroupInviteWaitRecord
import co.electriccoin.zcash.ui.screen.chat.viewmodel.AutoSpendVm
import co.electriccoin.zcash.ui.screen.chat.viewmodel.GroupInviteDriver
import co.electriccoin.zcash.ui.screen.chat.viewmodel.GroupInviteDriver.chainTip
import co.electriccoin.zcash.ui.screen.chat.viewmodel.GroupInviteDriver.plaintextInvite
import co.electriccoin.zcash.ui.screen.chat.viewmodel.GroupInviteDriver.processInvite
import co.electriccoin.zcash.ui.screen.chat.viewmodel.HandshakeHarness.START_HEIGHT
import co.electriccoin.zcash.ui.screen.chat.viewmodel.HandshakeHarness.awaitIdle
import co.electriccoin.zcash.ui.screen.chat.viewmodel.HandshakeHarness.chatViewModelWith
import co.electriccoin.zcash.ui.screen.chat.viewmodel.HandshakeSeam
import co.electriccoin.zcash.ui.screen.chat.viewmodel.SavedContacts
import co.electriccoin.zcash.ui.screen.chat.viewmodel.SendHarnessScopes
import co.electriccoin.zcash.ui.screen.chat.viewmodel.StrangerInvitePrefs
import co.electriccoin.zcash.ui.screen.chat.viewmodel.SyntheticUnifiedAddress
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.json.JSONObject
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.File
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * ZC-288 — the banner of an invite whose "I joined" reply could not go over the relays states a certain price.
 *
 * The banner (group_invite_join_known_paid, drawn by GroupInviteRequestBanner with replyIsPaid) says "Your “I joined”
 * reply can’t go over the relays, so it costs at least … ZEC. … Join to send it." But Join tries the relays once more
 * before anything is paid (ChatViewModel.sendJoinedGroupAccept), and when they take the reply it costs nothing: the
 * text promises a payment the code may not make. The stranger banner already says "can cost".
 *
 * Wanted, in English and Spanish with the same three placeholders (%1$s inviter, %2$s group, %3$s price): the reply
 * could not go over the relays; Join tries them once more first; only if they still cannot take it does the reply cost
 * at least the price; nothing has been paid.
 *
 * The control pins the behaviour the text describes: an invite that waits with a relay-failed record, joined when a
 * relay takes the reply, sends it free and builds no payment. All addresses, keys and group ids are synthetic.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class GroupJoinPaidBannerSaysJoinTriesRelaysFirstTest {
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

    @Test
    fun `the English banner says Join tries the relays once more first and states no certain price`() {
        val en = strings("values").getValue(KEY)
        assertTrue("Join tries them once more first" in en, en)
        assertTrue("if they still can’t take it" in en, en)
        assertTrue("at least %3\$s ZEC" in en, en)
        assertTrue("Nothing has been paid" in en, en)
        assertFalse("so it costs at least" in en, "the banner still states a certain price: $en")
        assertFalse("can’t go over the relays" in en, "the banner still says the relays cannot take the reply: $en")
    }

    @Test
    fun `the Spanish banner says Join tries the relays once more first and states no certain price`() {
        val en = strings("values").getValue(KEY)
        val es = strings("values-es")[KEY] ?: fail("values-es/$FILE has no $KEY")
        assertNotEquals(en, es, "$KEY is the English text in the Spanish file")
        assertTrue("Al tocar Unirme, primero se intenta enviarla una vez más por ellos" in es, es)
        assertFalse("Unirte" in es, "ZC-1062: the button is «Unirme», the banner says «Unirte»: $es")
        assertTrue("si aún no la aceptan" in es, es)
        assertTrue("al menos %3\$s ZEC" in es, es)
        assertTrue("No se ha pagado nada" in es, es)
        assertFalse("así que cuesta al menos" in es, "the banner still states a certain price: $es")
        assertFalse("no puede ir por los relays" in es, "the banner still says the relays cannot take the reply: $es")
    }

    @Test
    fun `English and Spanish keep the same three placeholders`() {
        val en = strings("values").getValue(KEY)
        val es = strings("values-es")[KEY] ?: fail("values-es/$FILE has no $KEY")
        assertEquals(listOf("%1\$s", "%2\$s", "%3\$s"), placeholders(en), "the English placeholders")
        assertEquals(placeholders(en), placeholders(es), "placeholders of $KEY")
    }

    @Test
    fun `control - Join on a relay-failed invite sends the reply free when a relay takes it, with no payment built`() =
        runBlocking {
            val inviter = SyntheticUnifiedAddress.mainnet(seed = 2881)
            val prefs = StrangerInvitePrefs(consent = false).apply { e2eEnabled += inviter }
            val relayKeyKnown = AtomicBoolean(false)
            val relayed = mutableListOf<String>()
            val seam = HandshakeSeam(inviter)
            val vm =
                chatViewModelWith(relayKeyOnceKnown(prefs.proxy, inviter, relayKeyKnown), seam).also { vm ->
                    AutoSpendVm.wireBootstrapFields(vm)
                    AutoSpendVm.setField(vm, "contactBook", SavedContacts(emptySet()))
                }

            // No relay key for the inviter yet: the free reply cannot go, so the invite waits with a relay-failed record.
            processInvite(vm, GROUP, plaintextInvite(inviter))
            awaitIdle(vm)
            chainTip(vm, START_HEIGHT + 1)
            // [test-change-ok: W-137 ZC-922 every invite waits for Join] The invite now waits before any relay try, so its
            // record has no relay-failed flag yet. The banner with the price is the state after a try that failed (a Join
            // that came back to waiting); the record is written here the way that return writes it (the flag, nothing else).
            prefs.needsJoin[GROUP]?.let { waiting ->
                if (!GroupInviteWaitRecord.relayFailed(waiting)) {
                    prefs.needsJoin[GROUP] = JSONObject(waiting).put(GroupInviteWaitRecord.RELAY_FAILED, true).toString()
                }
            }
            assertTrue(
                GroupInviteWaitRecord.relayFailed(prefs.needsJoin[GROUP]),
                "fixture: the invite must wait with a relay-failed record (the banner with the price)",
            )
            assertEquals(0, seam.submits.get(), "fixture: nothing is paid before Join")

            // The relays can take the reply by the time the user taps Join.
            relayKeyKnown.set(true)
            NostrChatBridge.registerPublisher { memo, _ ->
                relayed += memo
                NostrChatBridge.PublishResult(acks = 1, messageId = "synthetic-relay-ack")
            }
            assertTrue(vm.joinGroupInvite(GROUP), "Join must release the waiting reply")
            awaitIdle(vm)

            assertEquals(1, relayed.size, "Join must try the relays first, once")
            assertEquals(0, seam.proposalRequests.get(), "a reply the relays took must build no payment")
            assertEquals(0, seam.submits.get(), "a reply the relays took must cost nothing on-chain")
            assertFalse(prefs.needsJoin.containsKey(GROUP), "the invite must no longer wait for Join")
            assertFalse(prefs.base.heldGroupAccepts.containsKey(GROUP), "a delivered reply must not stay held")
        }

    /** [delegate], with a relay key for [peer] once [known] is set (before that the delegate's answer: none). */
    private fun relayKeyOnceKnown(
        delegate: ZchatPreferences,
        peer: String,
        known: AtomicBoolean,
    ): ZchatPreferences =
        Proxy.newProxyInstance(
            ZchatPreferences::class.java.classLoader,
            arrayOf(ZchatPreferences::class.java),
        ) { p, method, args ->
            when {
                method.name == "getPeerNostrPubkey" && known.get() && args?.get(0) == peer -> "ab".repeat(32)
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

    private fun placeholders(text: String): List<String> =
        Regex("""%(\d+\$)?[sd]""")
            .findAll(text)
            .map { it.value }
            .toList()
            .sorted()

    private fun strings(dir: String): Map<String, String> {
        val f = File(resRoot, "$dir/$FILE")
        if (!f.isFile) fail("missing ${f.path}")
        return Regex("""<string name="([^"]+)"[^>]*>(.*?)</string>""", RegexOption.DOT_MATCHES_ALL)
            .findAll(f.readText())
            .associate { m ->
                m.groupValues[1] to
                    m.groupValues[2]
                        .replace("\\'", "'")
                        .replace("\\\"", "\"")
                        .replace("&amp;", "&")
                        .replace(Regex("\\s+"), " ")
                        .trim()
            }
    }

    private companion object {
        const val FILE = "group_invite_request_strings.xml"
        const val KEY = "group_invite_join_known_paid"
        const val GROUP = "GZC28801"

        val resRoot: File by lazy {
            val start = File(System.getProperty("user.dir") ?: ".").absoluteFile
            generateSequence(start) { it.parentFile }
                .take(8)
                .flatMap { sequenceOf(it, it.resolve("ui-lib")) }
                .map { it.resolve("src/main/res/ui/common") }
                .firstOrNull { it.resolve("values").isDirectory }
                ?: fail("could not locate ui-lib/src/main/res/ui/common from ${start.path}")
        }
    }
}
