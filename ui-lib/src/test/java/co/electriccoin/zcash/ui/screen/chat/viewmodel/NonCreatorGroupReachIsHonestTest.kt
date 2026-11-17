package co.electriccoin.zcash.ui.screen.chat.viewmodel

import androidx.lifecycle.viewModelScope
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.nostr.NostrChatBridge
import co.electriccoin.zcash.ui.screen.chat.datasource.ZchatPreferences
import co.electriccoin.zcash.ui.screen.chat.model.GroupConversation
import co.electriccoin.zcash.ui.screen.chat.model.GroupInfo
import co.electriccoin.zcash.ui.screen.chat.model.GroupMember
import co.electriccoin.zcash.ui.screen.chat.model.GroupMessageCost
import co.electriccoin.zcash.ui.screen.chat.model.InviteStatus
import co.electriccoin.zcash.ui.screen.chat.model.MemberStatus
import co.electriccoin.zcash.ui.screen.chat.model.ZMSGGroupProtocol
import co.electriccoin.zcash.ui.screen.chat.view.ChatResourceText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.lang.reflect.Proxy
import java.security.SecureRandom
import java.time.Instant
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap

/**
 * Round 8 P7 (PHONE_TRIAGE F4, minimum) — a member's phone only knows the members it has heard from.
 *
 * A compact invite carries no roster: the joiner B seeds only the inviter A and itself, and learns another member only
 * from that member's own GROUP_MSG. So on B's phone a group message goes to the members B knows, and the others never
 * get it. At 2ef3f04a3 B's cost line still says "This message is free right now: every member is reachable over the
 * relays." (group_message_cost_free, new in 2.13.0), and the header says "2 members" for a group of three.
 *
 * Wanted, on a phone that is not the group admin:
 * 1. groupMessageCost(G) reports knownOnly = true (and false on the admin's phone, whose roster is the whole group).
 * 2. The free line is the counted group_message_cost_free_known_count (QA2 Q2-62, W-137; was the uncounted
 *    group_message_cost_free_known), never group_message_cost_free.
 * 3. A paid line (group_message_cost_all / _some) ends with group_message_cost_known_suffix.
 * 4. The header count reads "%d known members" (group_member_count_known).
 * Every new line in English and Spanish.
 *
 * The new API (GroupMessageCost.knownOnly, view.GroupReachTextKt) is reached by reflection, so this file compiles on
 * the build before the fix. All addresses, keys and group ids are synthetic.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class NonCreatorGroupReachIsHonestTest {
    private val sendScopes = SendHarnessScopes()
    private val vms = mutableListOf<GroupViewModel>()
    private val scheduler = TestCoroutineScheduler()

    @Before
    fun setUp() {
        sendScopes.open()
        Dispatchers.setMain(UnconfinedTestDispatcher(scheduler))
        NostrChatBridge.unregisterPublisher()
    }

    @After
    fun tearDown() {
        NostrChatBridge.unregisterPublisher()
        sendScopes.close()
        vms.forEach { it.viewModelScope.cancel() }
        // A cancelled screen still resumes on Main while it ends; resetting Main first leaks into another test.
        kotlinx.coroutines.runBlocking {
            vms.toList().forEach { screen ->
                kotlinx.coroutines.withTimeoutOrNull(5_000L) {
                    screen.viewModelScope.coroutineContext[kotlinx.coroutines.Job]?.join()
                }
            }
        }
        vms.clear()
        Dispatchers.resetMain()
    }

    /** The relays are up, so a member with a relay key is reachable free. */
    private fun relaysUp() =
        NostrChatBridge.registerPublisher { _, _ ->
            NostrChatBridge.PublishResult(acks = 1, messageId = "synthetic-relay-event")
        }

    // ── 1 + 2. the free line on B ────────────────────────────────────────────────────────────────────

    @Test
    fun `on a member's phone that knows only the creator, the cost reports knownOnly and the free line is not every member`() =
        runBlocking {
            relaysUp()
            val b = MemberBPrefs(creatorReachableFree = true)
            val cost = memberViewModel(b).groupMessageCost(GROUP)

            assertEquals("fixture: B knows one other member (the creator A)", 1, cost.recipients)
            assertEquals("fixture: A is reachable free over the relays", 0, cost.onChainMessages)
            assertTrue("B's phone does not know the whole group, but its cost does not say so (knownOnly)", knownOnly(cost))

            val line = costLine(cost)
            assertNotEquals(
                "B's cost line says every member is reachable, but B only knows the members it has heard from",
                english("group_message_cost_free"),
                line,
            )
            assertEquals(
                "Free right now for the 1 member this phone has heard from. " +
                    "Members it hasn't heard from yet won't get this message.",
                line,
            )
        }

    // ── 3. a paid line on B ──────────────────────────────────────────────────────────────────────────

    @Test
    fun `on a member's phone a paid cost line says the members it has not heard from won't get it`() =
        runBlocking {
            relaysUp()
            val b = MemberBPrefs(creatorReachableFree = false)
            val cost = memberViewModel(b).groupMessageCost(GROUP)

            assertEquals("fixture: A is paid on-chain", 1, cost.onChainMessages)
            assertTrue(knownOnly(cost))
            val line = costLine(cost)
            val paid = ChatResourceText.englishPlural(R.plurals.group_message_cost_all, 1, listOf(1, zec(cost)))
            assertTrue("the paid line lost its figures: $line", line.startsWith(paid))
            assertTrue(
                "B's paid cost line does not say the members B hasn't heard from won't get it: $line",
                line.endsWith(english("group_message_cost_known_suffix")),
            )
        }

    // ── control: the admin's phone knows the whole roster ────────────────────────────────────────────

    @Test
    fun `control - on the admin's phone the free line still says every member is reachable`() =
        runBlocking {
            relaysUp()
            val f = GroupPaidOnceFixture(nostrKeyForP = { GroupPaidOnceFixture.SYNTHETIC_NOSTR_PUBKEY })
            f.scheduler = scheduler
            val vm = f.groupViewModel().also { vms += it }
            val cost = vm.groupMessageCost(GroupPaidOnceFixture.GROUP_ID)

            assertEquals(0, cost.onChainMessages)
            assertTrue("the admin's roster is the whole group, yet its cost says knownOnly", !knownOnly(cost))
            assertEquals(english("group_message_cost_free"), costLine(cost))
        }

    // ── 4. the header count ──────────────────────────────────────────────────────────────────────────

    @Test
    fun `on a member's phone the header counts known members, on the admin's phone members`() {
        val conversation =
            GroupConversation(
                groupInfo = MemberBPrefs.info(),
                members = MemberBPrefs.roster(),
                messages = emptyList(),
                lastMessage = null,
            )
        assertEquals("2 known members", headerLabel(conversation, knownOnly = true))
        assertEquals("2 members", headerLabel(conversation, knownOnly = false))
    }

    // ── the new lines in both languages ──────────────────────────────────────────────────────────────

    @Test
    fun `the known-members lines exist in English and Spanish`() {
        val en = ChatResourceText.strings("values")
        val es = ChatResourceText.strings("values-es")
        // QA2 Q2-62 (W-137): the counted free line replaces the uncounted group_message_cost_free_known.
        assertEquals(
            "Free right now for the %1\$d members this phone has heard from. " +
                "Members it hasn't heard from yet won't get this message.",
            ChatResourceText.plurals("values")["group_message_cost_free_known_count"]?.get("other"),
        )
        assertEquals(
            "Gratis ahora para los %1\$d miembros de los que este teléfono ha recibido algo. " +
                "Los miembros de los que aún no ha recibido nada no recibirán este mensaje.",
            ChatResourceText.plurals("values-es")["group_message_cost_free_known_count"]?.get("other"),
        )
        assertEquals("Members you haven't heard from yet won't get it.", en["group_message_cost_known_suffix"])
        assertEquals("Los miembros de los que aún no has recibido nada no lo recibirán.", es["group_message_cost_known_suffix"])

        val enCount = ChatResourceText.plurals("values")["group_member_count_known"]
        val esCount = ChatResourceText.plurals("values-es")["group_member_count_known"]
        assertEquals(mapOf("one" to "%1\$d known member", "other" to "%1\$d known members"), enCount)
        assertEquals(
            mapOf("one" to "%1\$d miembro conocido", "many" to "%1\$d miembros conocidos", "other" to "%1\$d miembros conocidos"),
            esCount,
        )
    }

    // ── plumbing ─────────────────────────────────────────────────────────────────────────────────────

    private fun english(name: String): String =
        ChatResourceText.strings("values")[name] ?: throw AssertionError("no English string $name")

    private fun zec(cost: GroupMessageCost): String =
        co.electriccoin.zcash.ui.screen.chat.model.PaidActionCopy
            .zec(cost.totalCost, java.util.Locale.US)

    private fun knownOnly(cost: GroupMessageCost): Boolean {
        val getter =
            GroupMessageCost::class.java.methods.firstOrNull { it.name == "getKnownOnly" }
                ?: throw AssertionError(
                    "GroupMessageCost has no knownOnly: the cost line can't tell a member's partial roster from the whole group",
                )
        return getter.invoke(cost) as Boolean
    }

    private fun reachText(): Class<*> =
        runCatching { Class.forName("co.electriccoin.zcash.ui.screen.chat.view.GroupReachTextKt") }.getOrNull()
            ?: throw AssertionError("no view.GroupReachTextKt: the cost line and the header count have no testable text")

    /** The composer's cost line for [cost], in English, as GroupDetailView shows it. */
    private fun costLine(cost: GroupMessageCost): String {
        val method = reachText().methods.first { it.name == "groupMessageCostText" }
        val string: (Int) -> String = { id -> ChatResourceText.english(id) }
        val plural: (Int, Int, List<Any>) -> String = { id, q, args -> ChatResourceText.englishPlural(id, q, args) }
        return method.invoke(null, cost, string, plural, java.util.Locale.US) as String
    }

    /** The member count under the group's name, in English, as GroupDetailView shows it. */
    private fun headerLabel(
        conversation: GroupConversation,
        knownOnly: Boolean,
    ): String {
        val method = reachText().methods.first { it.name == "groupMemberCountLabel" }
        val plural: (Int, Int, List<Any>) -> String = { id, q, args -> ChatResourceText.englishPlural(id, q, args) }
        return method.invoke(null, conversation, knownOnly, plural) as String
    }

    /** B's GroupViewModel: the fixture's wiring (and its SDK seam), over B's preferences, as B. */
    private suspend fun memberViewModel(b: MemberBPrefs): GroupViewModel {
        val f = b.fixture
        f.scheduler = scheduler
        val vm = f.groupViewModel().also { vms += it }
        GroupViewModel::class.java
            .getDeclaredField("zchatPreferences")
            .apply { isAccessible = true }
            .set(vm, b.proxy)
        @Suppress("UNCHECKED_CAST")
        (
            GroupViewModel::class.java
                .getDeclaredField("_currentUserAddress")
                .apply { isAccessible = true }
                .get(vm) as MutableStateFlow<String?>
        ).value = B
        return vm
    }

    /**
     * B's preferences after joining G from A's compact invite: the roster holds only A (the creator, admin) and B,
     * both ACTIVE at epoch 1. B has not heard from the third member, so B's phone does not list them.
     */
    private class MemberBPrefs(
        creatorReachableFree: Boolean,
    ) {
        val fixture = GroupPaidOnceFixture()
        private val infos = ConcurrentHashMap<String, String>()
        private val rosters = ConcurrentHashMap<String, String>()
        private val keys = ConcurrentHashMap<String, String>()

        init {
            infos[GROUP] = ZMSGGroupProtocol.serializeGroupInfo(info())
            rosters[GROUP] = ZMSGGroupProtocol.serializeGroupMembers(roster())
            keys["$GROUP|1"] = Base64.getEncoder().encodeToString(ByteArray(32).also { SecureRandom().nextBytes(it) })
        }

        private val creatorNostr = if (creatorReachableFree) A_NOSTR else null

        @Suppress("CyclomaticComplexMethod")
        val proxy: ZchatPreferences =
            Proxy.newProxyInstance(
                ZchatPreferences::class.java.classLoader,
                arrayOf(ZchatPreferences::class.java),
            ) { p, method, args ->
                fun arg(i: Int) = args!![i]
                when (method.name) {
                    "getAllGroupIds" -> infos.keys.toSet()
                    "getGroupInfo" -> infos[arg(0) as String]
                    "getGroupMembers" -> rosters[arg(0) as String]
                    "isGroupSelfCreated" -> false
                    "isSelfAddress" -> arg(0) == B
                    "resolvePeerAddress" -> arg(0)
                    "getGroupKeyEpoch" -> 1
                    "getGroupKey" -> keys["${arg(0)}|${arg(1)}"]
                    "getPeerNostrPubkey" -> if (arg(0) == A) creatorNostr else null
                    "getAllConversationPeerAddresses" -> emptySet<String>()
                    "getGroupMessageSpendConsent" -> emptySet<String>()
                    "toString" -> "MemberBPrefs"
                    "hashCode" -> System.identityHashCode(p)
                    "equals" -> p === args?.get(0)
                    else ->
                        when (method.returnType) {
                            java.lang.Boolean.TYPE -> false
                            java.lang.Integer.TYPE -> 0
                            java.lang.Long.TYPE -> 0L
                            List::class.java -> emptyList<Any>()
                            Set::class.java -> emptySet<Any>()
                            Map::class.java -> emptyMap<Any, Any>()
                            else -> null
                        }
                }
            } as ZchatPreferences

        companion object {
            fun info(): GroupInfo =
                GroupInfo(
                    groupId = GROUP,
                    name = "Known members",
                    creatorAddress = A,
                    createdAt = Instant.ofEpochMilli(1_000L),
                )

            fun roster(): List<GroupMember> =
                listOf(
                    GroupMember(address = A, joinedAt = Instant.ofEpochMilli(1_000L), status = MemberStatus.ACTIVE, isAdmin = true),
                    GroupMember(
                        address = B,
                        joinedAt = Instant.ofEpochMilli(2_000L),
                        status = MemberStatus.ACTIVE,
                        inviteStatus = InviteStatus.SENT,
                    ),
                )
        }
    }

    private companion object {
        const val GROUP = "zgrp_p7knownonly"
        const val A = "usyntheticp7creatora000001"
        const val B = "usyntheticp7memberb000001"
        const val A_NOSTR = "7a8a8a8a7a8a8a8a7a8a8a8a7a8a8a8a7a8a8a8a7a8a8a8a7a8a8a8a7a8a8a8a"
    }
}
