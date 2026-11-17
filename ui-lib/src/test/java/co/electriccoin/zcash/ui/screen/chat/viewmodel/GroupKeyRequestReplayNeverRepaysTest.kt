package co.electriccoin.zcash.ui.screen.chat.viewmodel

import androidx.lifecycle.viewModelScope
import cash.z.ecc.android.sdk.model.TransactionId
import co.electriccoin.zcash.ui.screen.chat.crypto.E2EEncryption
import co.electriccoin.zcash.ui.screen.chat.crypto.E2EKeyPair
import co.electriccoin.zcash.ui.screen.chat.datasource.ZchatPreferences
import co.electriccoin.zcash.ui.screen.chat.model.GroupInfo
import co.electriccoin.zcash.ui.screen.chat.model.GroupMember
import co.electriccoin.zcash.ui.screen.chat.model.MemberStatus
import co.electriccoin.zcash.ui.screen.chat.model.SendMessageState
import co.electriccoin.zcash.ui.screen.chat.model.ZMSGGroupProtocol
import co.electriccoin.zcash.ui.screen.chat.viewmodel.HandshakeHarness.awaitIdle
import co.electriccoin.zcash.ui.screen.chat.viewmodel.HandshakeHarness.chatViewModelWith
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.lang.reflect.Proxy
import java.security.SecureRandom
import java.time.Instant
import java.util.Base64
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

/**
 * HS-5 — an old GROUP_KEY_REQUEST is re-granted and paid on every history scan, with no consent.
 *
 * A member who taps "sync group keys" with no NOSTR route to the admin sends a signed GROUP_KEY_REQUEST
 * on-chain. It stays in the admin's transaction history for good. convertToConversations hands EVERY group
 * transaction in that history to processGroupMessage(memo, tx.id, …) on EVERY pass (each new or changed
 * transaction, each ChatViewModel created by a chat open or app start), and at b50b6002d nothing records
 * that the request was already answered: processGroupMessage does not pass the txid on,
 * mayGrantKeyRequest only bounds the epoch, and sendGroupControlMemo falls back to a direct on-chain
 * submit (~17,000 zat) without the automatic-handshake consent gate. The admin's own grant transaction
 * then triggers the next pass.
 *
 * These tests drive processGroupMessage exactly as the scan loop calls it for one mined transaction, twice
 * over the SAME transaction (two scan passes), through the real CreateChunkedMessageProposalUseCase and
 * ZashiProposalRepositoryImpl (HandshakeChokepointHarness). The requester is a VAULT-only ACTIVE member:
 * a KEX session with the admin, no NOSTR pubkey. Every key is generated per run; every address is
 * synthetic.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class GroupKeyRequestReplayNeverRepaysTest {
    private val sendScopes = SendHarnessScopes()

    @Before
    fun setUp() {
        sendScopes.open()
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        sendScopes.close()
        Dispatchers.resetMain()
    }

    @Test
    fun `two scan passes over one on-chain key request pay at most one grant`() =
        runBlocking {
            val fixture = GroupGrantFixture(member = "usyntheticgroupkeyreplay001", consent = true)
            val seam = HandshakeSeam(fixture.member)
            val vm = chatViewModelWith(fixture.proxy, seam)
            val requestTx = fixture.txId(1)

            fixture.scan(vm, requestTx)
            awaitIdle(vm)
            assertEquals("fixture sanity: the first pass grants the request", 1, seam.proposalRequests.get())

            fixture.scan(vm, requestTx)
            awaitIdle(vm)

            assertEquals("fixture sanity", emptyList<String>(), fixture.unexpectedCalls)
            assertEquals(
                "the second scan pass over the SAME request transaction built and paid another grant. A granted " +
                    "request must be recorded durably so a rescan never pays it again.",
                1,
                seam.proposalRequests.get(),
            )
            assertEquals(1, seam.broadcasts.size)
        }

    @Test
    fun `with handshake consent false two scan passes pay nothing, and the request is granted once consent is given`() =
        runBlocking {
            val fixture = GroupGrantFixture(member = "usyntheticgroupkeyreplay002", consent = false)
            val seam = HandshakeSeam(fixture.member)
            val vm = chatViewModelWith(fixture.proxy, seam)
            val requestTx = fixture.txId(2)

            fixture.scan(vm, requestTx)
            awaitIdle(vm)
            fixture.scan(vm, requestTx)
            awaitIdle(vm)

            assertEquals("fixture sanity", emptyList<String>(), fixture.unexpectedCalls)
            assertEquals(
                "an automatic on-chain grant was paid without handshake-spend consent. The grant is an automatic " +
                    "spend nobody on this device asked for; it must go through the consent gate.",
                0,
                seam.proposalRequests.get(),
            )
            assertTrue(
                "the user must be asked",
                HandshakeHarness.sendMessageState(vm) is SendMessageState.NeedsHandshakeSpendConsent,
            )

            // The user agrees; the next pass answers the member's request exactly once.
            fixture.consent = true
            fixture.scan(vm, requestTx)
            awaitIdle(vm)
            fixture.scan(vm, requestTx)
            awaitIdle(vm)
            assertEquals(1, seam.proposalRequests.get())
            assertEquals(1, seam.broadcasts.size)
        }

    @Test
    fun `the chat list and the chat detail scanning the same request pay one grant`() =
        runBlocking {
            val fixture = GroupGrantFixture(member = "usyntheticgroupkeyreplay003", consent = true)
            val seam = HandshakeSeam(fixture.member, holdFirstSubmit = true)
            val chatList = chatViewModelWith(fixture.proxy, seam)
            val chatDetail = chatViewModelWith(fixture.proxy, seam)
            val requestTx = fixture.txId(3)

            fixture.scan(chatList, requestTx)
            withTimeout(HandshakeHarness.TIMEOUT_MS) { seam.firstSubmitEntered.await() }
            // While the chat list's grant is being broadcast, the chat detail's own pass reaches the request.
            fixture.scan(chatDetail, requestTx)
            seam.release.complete(Unit)
            awaitIdle(chatList, chatDetail)

            assertEquals("fixture sanity", emptyList<String>(), fixture.unexpectedCalls)
            assertEquals(
                "two ChatViewModels scanning the same history each paid a grant for one request",
                1,
                seam.broadcasts.size,
            )
        }

    @Test
    fun `backing out while the grant is broadcast never pays it again on the next scan`() =
        runBlocking {
            val fixture = GroupGrantFixture(member = "usyntheticgroupkeyreplay004", consent = true)
            val seam = HandshakeSeam(fixture.member, holdFirstSubmit = true)
            val first = chatViewModelWith(fixture.proxy, seam)
            val requestTx = fixture.txId(4)

            fixture.scan(first, requestTx)
            withTimeout(HandshakeHarness.TIMEOUT_MS) { seam.firstSubmitEntered.await() }
            // The ViewModel is cleared mid-broadcast; the detached repository scope still sends the grant.
            first.viewModelScope.coroutineContext.job
                .cancelAndJoin()
            seam.release.complete(Unit)
            withTimeout(HandshakeHarness.TIMEOUT_MS) { seam.firstSubmitDone.await() }

            val next = chatViewModelWith(fixture.proxy, seam)
            fixture.scan(next, requestTx)
            awaitIdle(next)

            assertEquals("fixture sanity", emptyList<String>(), fixture.unexpectedCalls)
            assertEquals(
                "the grant reached the network, but the back-out left no record of it, so the next scan paid again",
                1,
                seam.broadcasts.size,
            )
        }

    /**
     * Control. KI-23 (ZC-020): a new request of the SAME member for the SAME key is no longer paid for again (one paid
     * answer per member per key; RepeatedKeyRequestsPayOncePerKeyTest). What is still answered again, and paid for, is
     * a request for a NEW key (after a rotation) and a request from another member.
     */
    @Test
    fun `control - a new request for a new key or from another member is granted again`() =
        runBlocking {
            MA3.resetRuntime()
            val w = KeyGrantWorld(TestCoroutineScheduler())
            val seam = HandshakeSeam(MA3.A)
            val vm = w.chatVm(seam, ledger = false)

            w.chain(vm, MA3.A, tx = 5)
            awaitIdle(vm)
            assertEquals(1, seam.proposalRequests.get())

            // The group moved to a new key: the member's request for it is a new paid answer.
            w.rotate()
            w.chain(vm, MA3.A, tx = 6, haveEpoch = 1)
            awaitIdle(vm)
            assertEquals(2, seam.proposalRequests.get())

            // Another member's request for that key is its own paid answer.
            w.chain(vm, MA3.B, tx = 7, haveEpoch = 1)
            awaitIdle(vm)
            assertEquals(3, seam.proposalRequests.get())
            assertEquals(3, seam.broadcasts.size)
            MA3.resetRuntime()
        }
}

/**
 * The admin's side of one group: we created group [GROUP_ID], [member] is an ACTIVE member holding a KEX
 * session with us and no NOSTR pubkey, and the group is at epoch 1. [scan] feeds the member's signed
 * on-chain GROUP_KEY_REQUEST (have-epoch 0) to the real processGroupMessage.
 *
 * The preferences fake answers the calls the grant path makes; the durable grant marker is kept in
 * [grantedRequests] under whatever id the product chooses. Any other call is recorded in [unexpectedCalls]
 * and answered with null.
 */
internal class GroupGrantFixture(
    val member: String,
    consent: Boolean,
) {
    @Volatile var consent: Boolean = consent

    private val adminKeys: E2EKeyPair = E2EEncryption.generateKeyPair()
    private val memberKeys: E2EKeyPair = E2EEncryption.generateKeyPair()
    private val groupKey: String = Base64.getEncoder().encodeToString(ByteArray(32).also { SecureRandom().nextBytes(it) })
    private val infoJson =
        ZMSGGroupProtocol.serializeGroupInfo(
            GroupInfo(
                groupId = GROUP_ID,
                name = "Replay Test",
                creatorAddress = HandshakeHarness.SELF,
                createdAt = Instant.ofEpochMilli(1_000L),
            )
        )
    private val membersJson =
        ZMSGGroupProtocol.serializeGroupMembers(
            listOf(
                GroupMember(
                    address = HandshakeHarness.SELF,
                    joinedAt = Instant.ofEpochMilli(1_000L),
                    status = MemberStatus.ACTIVE,
                    isAdmin = true,
                ),
                GroupMember(address = member, joinedAt = Instant.ofEpochMilli(1_000L), status = MemberStatus.ACTIVE),
            )
        )

    val grantedRequests: MutableSet<String> = ConcurrentHashMap.newKeySet()
    val unexpectedCalls: MutableList<String> = Collections.synchronizedList(mutableListOf())

    /** KI-23: the paid deliveries per grant key, and the (max, window) of every unit of the daily budget that was used. */
    val paidCounts = ConcurrentHashMap<String, Int>()
    val budgetConsumes: MutableList<Pair<Int, Long>> = Collections.synchronizedList(mutableListOf())

    /** The member's signed request, as their "sync group keys" tap puts it on-chain. */
    val requestMemo: String =
        ZMSGGroupProtocol.createGroupKeyRequestMessage(
            GROUP_ID,
            member,
            0,
            E2EEncryption.sign(memberKeys.privateKey, ZMSGGroupProtocol.groupKeyRequestSignedData(GROUP_ID, member, 0)),
        )

    fun txId(n: Int): TransactionId = TransactionId.new(ByteArray(32) { (it + n * 31).toByte() })

    /** One scan pass reaching the request transaction: convertToConversations' call for a group memo. */
    fun scan(
        vm: ChatViewModel,
        txId: TransactionId
    ) {
        ChatViewModel::class.java
            .getDeclaredMethod(
                "processGroupMessage",
                String::class.java,
                TransactionId::class.java,
                Instant::class.java,
                String::class.java,
                String::class.java,
            ).apply { isAccessible = true }
            .invoke(vm, requestMemo, txId, Instant.ofEpochSecond(1_700_000_000L), null, null)
    }

    @Suppress("CyclomaticComplexMethod")
    val proxy: ZchatPreferences =
        Proxy.newProxyInstance(
            ZchatPreferences::class.java.classLoader,
            arrayOf(ZchatPreferences::class.java),
        ) { p, method, args ->
            fun arg(i: Int) = args!![i]

            fun forMember(): Boolean = arg(0) == member
            when (method.name) {
                "getGroupInfo" -> if (arg(0) == GROUP_ID) infoJson else null
                "isGroupSelfCreated" -> arg(0) == GROUP_ID
                "isSelfAddress" -> arg(0) == HandshakeHarness.SELF
                "getGroupMembers" -> if (arg(0) == GROUP_ID) membersJson else null
                "getGroupKeyEpoch" -> 1
                "getGroupKey" -> if (arg(0) == GROUP_ID && arg(1) == 1) groupKey else null
                "resolvePeerAddress" -> arg(0)
                "getE2EPeerPublicKey" -> if (forMember()) memberKeys.publicKey else null
                "getE2EPrivateKey" -> if (forMember()) adminKeys.privateKey else null
                "getE2EKeyVersion" -> 2
                "getPeerNostrPubkey" -> null
                "hasConsentedToHandshakeSpend" -> this.consent
                // C5: the consent question drops another wallet's queued setups after a phrase change; one wallet here.
                "walletScopeGeneration" -> 0L
                "getTunnelModePeers" -> emptyList<String>()
                "isGroupKeyRequestGranted" -> arg(0) in grantedRequests
                "setGroupKeyRequestGranted" -> {
                    if (arg(1) == true) grantedRequests.add(arg(0) as String) else grantedRequests.remove(arg(0) as String)
                    // R5-2: a working store reports its write.
                    true
                }
                // KI-23: a working store for the paid count and a daily budget that always has room.
                "getGroupKeyGrantPaidCount" -> paidCounts[arg(0) as String] ?: 0
                "setGroupKeyGrantPaidCount" -> {
                    if ((arg(1) as Int) > 0) paidCounts[arg(0) as String] = arg(1) as Int else paidCounts.remove(arg(0) as String)
                    true
                }
                "groupKeyGrantBudgetAvailable" -> true
                "tryConsumeGroupKeyGrantBudget" -> {
                    budgetConsumes += (arg(1) as Int) to (arg(2) as Long)
                    true
                }
                "toString" -> "GroupGrantFixturePrefs"
                "hashCode" -> System.identityHashCode(p)
                "equals" -> p === args?.get(0)
                else -> {
                    unexpectedCalls.add(method.name)
                    null
                }
            }
        } as ZchatPreferences

    companion object {
        const val GROUP_ID = "zgrp_6b65797265706c6179746573"
    }
}
