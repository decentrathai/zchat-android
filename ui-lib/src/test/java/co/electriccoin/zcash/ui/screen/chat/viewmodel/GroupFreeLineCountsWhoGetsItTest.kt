package co.electriccoin.zcash.ui.screen.chat.viewmodel

import cash.z.ecc.android.sdk.model.Zatoshi
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.nostr.NostrChatBridge
import co.electriccoin.zcash.ui.screen.chat.model.GroupMessageCost
import co.electriccoin.zcash.ui.screen.chat.model.GroupMessageType
import co.electriccoin.zcash.ui.screen.chat.model.PaidActionCopy
import co.electriccoin.zcash.ui.screen.chat.model.ZMSGGroupProtocol
import co.electriccoin.zcash.ui.screen.chat.view.ChatResourceText
import co.electriccoin.zcash.ui.screen.chat.viewmodel.OpenGroup.B
import co.electriccoin.zcash.ui.screen.chat.viewmodel.OpenGroup.GID
import co.electriccoin.zcash.ui.screen.chat.viewmodel.OpenGroup.W
import co.electriccoin.zcash.ui.screen.chat.viewmodel.OpenGroup.X
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Locale

/**
 * ZC-290 (R8 cross-family finding) — the free cost line says "every member" next to "N can't read".
 *
 * `groupMessageCostText` returns `group_message_cost_free` ("This message is free right now: every member is reachable
 * over the relays.") whenever nothing is paid on-chain, whatever `GroupMessageCost.cannotRead` says. A member who is
 * owed the current key and has no secure session is left out of the send, so the group screen shows that line and,
 * right under it, "1 member can't read new messages yet". The two lines contradict each other, and the first one
 * promises a delivery the send does not make.
 *
 * Wanted: when nothing is paid on-chain and at least one member cannot read, the free line counts who the message goes
 * to, from the send's own recipient list:
 * - on the admin's phone `group_message_cost_free_to` (plurals, quantity = recipients);
 * - on a member's phone (`knownOnly`) `group_message_cost_free_known_to` (plurals, quantity = recipients).
 * The lines for `cannotRead == 0` and the paid lines stay as they are. English and Spanish; text only, ZEC stays 0.
 *
 * The new plurals are read by name from the resource files, so this file compiles on the build before the fix. All
 * addresses, keys and ids are synthetic; nothing pays.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class GroupFreeLineCountsWhoGetsItTest {
    private val sendScopes = SendHarnessScopes()
    private val scheduler = TestCoroutineScheduler()
    private val net = RelayNet()

    @Before
    fun setUp() {
        sendScopes.open()
        Dispatchers.setMain(UnconfinedTestDispatcher(scheduler))
        AutoSpendVm.resetProcessState()
        NostrChatBridge.unregisterPublisher()
        MA3.resetRuntime()
    }

    @After
    fun tearDown() {
        NostrChatBridge.unregisterPublisher()
        MA3.resetRuntime()
        sendScopes.close()
        Dispatchers.resetMain()
        AutoSpendVm.resetProcessState()
    }

    // ── (a) the admin's phone ────────────────────────────────────────────────────────────────────────

    @Test
    fun `a - on the admin's phone a free message that one member can't read does not say every member is reachable`() {
        val cost = GroupMessageCost(recipients = 1, onChainMessages = 0, totalCost = Zatoshi(0), cannotRead = 1)
        val line = costLine(cost)

        assertNotEquals(
            "the free line says every member is reachable although one member can't read the message and is left out",
            english("group_message_cost_free"),
            line,
        )
        assertEquals(englishPlural("group_message_cost_free_to", 1), line)
        assertEquals("This message is free right now: it goes over the relays to 1 member.", line)
    }

    @Test
    fun `a - with several recipients the admin's free line counts them`() {
        val cost = GroupMessageCost(recipients = 3, onChainMessages = 0, totalCost = Zatoshi(0), cannotRead = 2)
        val line = costLine(cost)

        assertEquals(englishPlural("group_message_cost_free_to", 3), line)
        assertEquals("This message is free right now: it goes over the relays to 3 members.", line)
    }

    // ── (b) a member's phone ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `b - on a member's phone a free message that one member can't read counts the members it goes to`() {
        val cost =
            GroupMessageCost(recipients = 1, onChainMessages = 0, totalCost = Zatoshi(0), cannotRead = 1, knownOnly = true)
        val line = costLine(cost)

        assertNotEquals(
            "the free line speaks for all the members this phone has heard from although one of them is left out",
            // W-137 Q2-62: the counted free line of a member's phone (group_message_cost_free_known_count) for 1.
            "Free right now for the 1 member this phone has heard from. " +
                "Members it hasn't heard from yet won't get this message.",
            line,
        )
        assertEquals(englishPlural("group_message_cost_free_known_to", 1), line)
        assertEquals(
            "Free right now: it goes over the relays to 1 member this phone knows. " +
                "Members you haven't heard from yet won't get it.",
            line,
        )
        assertEquals(
            "Free right now: it goes over the relays to 2 members this phone knows. " +
                "Members you haven't heard from yet won't get it.",
            costLine(cost.copy(recipients = 2)),
        )
    }

    // ── (c) end to end: the real GroupViewModel's cost ───────────────────────────────────────────────

    @Test
    fun `c - a group with one member owed the key and one reachable free - the screen's free line counts one member`() =
        runBlocking {
            // The relays ack every copy except B's key copies, so after X's removal B is owed the new key with no
            // secure session (an all-Open group), and W holds it. Then this phone has no relay key for B any more, so
            // no route can bring B the key: the send's own recipient list leaves B out, before and after a send.
            NostrChatBridge.registerPublisher { plaintext, recipient ->
                net.published += recipient to plaintext
                val type = ZMSGGroupProtocol.parseMessageType(plaintext)
                val keyCopy = type == GroupMessageType.GROUP_KICK || type == GroupMessageType.GROUP_KEY
                val acks = if (recipient == OpenGroup.npub(B) && keyCopy) 0 else 1
                NostrChatBridge.PublishResult(acks = acks, messageId = "synthetic-zc290-${net.published.size}")
            }
            val phone = OpenGroup.creator(listOf(B, W, X))
            val f = GroupPaidOnceFixture().apply { scheduler = this@GroupFreeLineCountsWhoGetsItTest.scheduler }
            val seam = AnnounceSeam()
            val vm = MA3.groupVm(phone, f, seam)
            OpenGroup.kick(vm, f, X)
            phone.base.nostrKeys.remove(B)

            val cost = vm.groupMessageCost(GID)
            assertEquals("fixture: the message goes to W only", 1, cost.recipients)
            assertEquals("fixture: W is reachable free over the relays", 0, cost.onChainMessages)
            assertEquals("fixture: B is owed the key and has no session", 1, cost.cannotRead)
            assertEquals("fixture: nothing is quoted", 0L, cost.totalCost.value)
            assertFalse("fixture: the creator's phone knows the whole group", cost.knownOnly)

            val line = costLine(cost)
            assertNotEquals(
                "the screen says every member is reachable right above \"1 member can't read new messages yet\"",
                english("group_message_cost_free"),
                line,
            )
            assertEquals(englishPlural("group_message_cost_free_to", 1), line)
            assertTrue("something was paid on-chain: ${seam.proposals}", seam.proposals.isEmpty())
        }

    // ── (d) the new lines in both languages ──────────────────────────────────────────────────────────

    @Test
    fun `d - the counted free lines exist in English and Spanish with every plural form`() {
        val en = ChatResourceText.plurals("values")
        val es = ChatResourceText.plurals("values-es")

        assertEquals(
            mapOf(
                "one" to "This message is free right now: it goes over the relays to %1\$d member.",
                "other" to "This message is free right now: it goes over the relays to %1\$d members.",
            ),
            en["group_message_cost_free_to"],
        )
        assertEquals(
            mapOf(
                "one" to "Este mensaje es gratis ahora mismo: va por los relays a %1\$d miembro.",
                "many" to "Este mensaje es gratis ahora mismo: va por los relays a %1\$d miembros.",
                "other" to "Este mensaje es gratis ahora mismo: va por los relays a %1\$d miembros.",
            ),
            es["group_message_cost_free_to"],
        )
        assertEquals(
            mapOf(
                "one" to
                    "Free right now: it goes over the relays to %1\$d member this phone knows. " +
                    "Members you haven't heard from yet won't get it.",
                "other" to
                    "Free right now: it goes over the relays to %1\$d members this phone knows. " +
                    "Members you haven't heard from yet won't get it.",
            ),
            en["group_message_cost_free_known_to"],
        )
        assertEquals(
            mapOf(
                "one" to
                    "Gratis ahora: va por los relays a %1\$d miembro que conoce este teléfono. " +
                    "Los miembros de los que aún no has recibido nada no lo recibirán.",
                "many" to
                    "Gratis ahora: va por los relays a %1\$d miembros que conoce este teléfono. " +
                    "Los miembros de los que aún no has recibido nada no lo recibirán.",
                "other" to
                    "Gratis ahora: va por los relays a %1\$d miembros que conoce este teléfono. " +
                    "Los miembros de los que aún no has recibido nada no lo recibirán.",
            ),
            es["group_message_cost_free_known_to"],
        )
    }

    @Test
    fun `d - no counted free line speaks for every member and each keeps exactly the one count placeholder`() {
        val placeholder = Regex("""%(\d+\$)?[sd]""")
        for (name in listOf("group_message_cost_free_to", "group_message_cost_free_known_to")) {
            for (dir in listOf("values", "values-es")) {
                val items = ChatResourceText.plurals(dir)[name] ?: throw AssertionError("no plurals $name in $dir")
                for ((quantity, text) in items) {
                    assertEquals(
                        "$dir $name[$quantity] does not carry exactly the count: $text",
                        listOf("%1\$d"),
                        placeholder.findAll(text).map { it.value }.toList(),
                    )
                    assertFalse(
                        "$dir $name[$quantity] speaks for every member: $text",
                        text.contains("every member", ignoreCase = true) || text.contains("todos los miembros", ignoreCase = true),
                    )
                }
            }
        }
    }

    // ── controls: every other line is unchanged ──────────────────────────────────────────────────────

    @Test
    fun `control - when every member can read, the free lines are the ones the app had`() {
        val free = GroupMessageCost(recipients = 2, onChainMessages = 0, totalCost = Zatoshi(0))
        assertEquals("This message is free right now: every member is reachable over the relays.", costLine(free))
        assertEquals(
            // QA2 Q2-62 (W-137, ZC-1651): the known-only free line now counts who gets it.
            "Free right now for the 2 members this phone has heard from. Members it hasn't heard from yet won't get this message.",
            costLine(free.copy(knownOnly = true)),
        )
    }

    @Test
    fun `control - a paid line is unchanged when a member can't read`() {
        val paid = GroupMessageCost(recipients = 2, onChainMessages = 1, totalCost = Zatoshi(15_000), cannotRead = 1)
        val zec = PaidActionCopy.zec(paid.totalCost, Locale.US)
        assertEquals(
            ChatResourceText.englishPlural(R.plurals.group_message_cost_some, 1, listOf(1, 2, zec)),
            costLine(paid),
        )
        assertEquals(
            ChatResourceText.englishPlural(R.plurals.group_message_cost_some, 1, listOf(1, 2, zec)) +
                " " + english("group_message_cost_known_suffix"),
            costLine(paid.copy(knownOnly = true)),
        )
    }

    // ── plumbing (as NonCreatorGroupReachIsHonestTest) ───────────────────────────────────────────────

    private fun english(name: String): String =
        ChatResourceText.strings("values")[name] ?: throw AssertionError("no English string $name")

    /** The English text of the plurals resource [name] for [quantity], read by name (it may not exist yet). */
    private fun englishPlural(
        name: String,
        quantity: Int,
    ): String {
        val items =
            ChatResourceText.plurals("values")[name]
                ?: throw AssertionError("no English plurals $name: the free line cannot count who the message goes to")
        val pattern = (if (quantity == 1) items["one"] else null) ?: items.getValue("other")
        return String.format(Locale.ROOT, pattern, quantity)
    }

    /** The composer's cost line for [cost], in English, as GroupDetailView shows it. */
    private fun costLine(cost: GroupMessageCost): String {
        val method =
            Class
                .forName("co.electriccoin.zcash.ui.screen.chat.view.GroupReachTextKt")
                .methods
                .first { it.name == "groupMessageCostText" }
        val string: (Int) -> String = { id -> ChatResourceText.english(id) }
        val plural: (Int, Int, List<Any>) -> String = { id, q, args -> ChatResourceText.englishPlural(id, q, args) }
        return method.invoke(null, cost, string, plural, Locale.US) as String
    }
}
