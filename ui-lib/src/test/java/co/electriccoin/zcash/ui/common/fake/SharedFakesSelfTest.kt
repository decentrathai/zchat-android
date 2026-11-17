package co.electriccoin.zcash.ui.common.fake

import cash.z.ecc.android.sdk.model.AccountUuid
import cash.z.ecc.android.sdk.model.BlockHeight
import cash.z.ecc.android.sdk.model.Proposal
import cash.z.ecc.android.sdk.model.TransactionRecipient
import cash.z.ecc.android.sdk.model.TransactionSubmitResult
import cash.z.ecc.android.sdk.model.UnifiedSpendingKey
import co.electriccoin.lightwallet.client.model.LightWalletEndpoint
import co.electriccoin.zcash.ui.screen.walletstartup.ChatCopyPhoneFiles
import kotlinx.coroutines.runBlocking
import java.io.IOException
import java.lang.reflect.Proxy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * F0 (fix-all 2.13.0): the shared fakes do what the F2/F3/F4/F10 tests will rely on. A fake that silently answers
 * a default would let those tests pass for the wrong reason, so each behaviour is pinned here once.
 */
class SharedFakesSelfTest {
    private val leg1 = RecordingBroadcaster.leg(seed = 1, expiryHeight = 3_000_040)
    private val leg2 = RecordingBroadcaster.leg(seed = 2, expiryHeight = 3_000_040)

    // Neither is read by the fake; they only fill the SDK signature.
    private val proposal: Proposal = unusable()
    private val usk: UnifiedSpendingKey = unusable()
    private val endpoint = LightWalletEndpoint("fake.invalid", 443, true)

    @Test
    fun the_broadcaster_records_create_stored_and_every_submit_in_order() =
        runBlocking<Unit> {
            val broadcaster = RecordingBroadcaster(listOf(leg1, leg2))
            val created = broadcaster.createProposedTransactions(proposal, usk)
            broadcaster.onStored(created.map { it.txIdString() }, created.first().expiryHeight)
            created.forEach { broadcaster.submit(it, endpoint) }

            assertEquals(
                listOf(
                    "create",
                    "stored:${leg1.txIdString()},${leg2.txIdString()}@3000040",
                    "submit:${leg1.txIdString()}",
                    "submit:${leg2.txIdString()}",
                ),
                broadcaster.events.toList(),
            )
            assertEquals(listOf(leg1.txIdString(), leg2.txIdString()), broadcaster.submitted())
        }

    @Test
    fun the_broadcaster_answers_a_scripted_leg_and_success_for_the_rest() =
        runBlocking<Unit> {
            val broadcaster = RecordingBroadcaster(listOf(leg1, leg2))
            broadcaster.answerSubmit(leg1.txIdString()) { RecordingBroadcaster.grpcFailure(it) }

            val first = broadcaster.submit(leg1, endpoint)
            assertIs<TransactionSubmitResult.Failure>(first)
            assertTrue(first.grpcError)
            assertEquals(-1, first.code)
            assertIs<TransactionSubmitResult.Success>(broadcaster.submit(leg2, endpoint))
        }

    @Test
    fun a_scripted_throw_reaches_the_caller_after_the_submit_is_recorded() =
        runBlocking<Unit> {
            val broadcaster = RecordingBroadcaster(listOf(leg1))
            broadcaster.answerSubmit(leg1.txIdString()) { throw IOException("socket closed") }

            assertFailsWith<IOException> { broadcaster.submit(leg1, endpoint) }
            assertEquals(listOf(leg1.txIdString()), broadcaster.submitted())
        }

    @Test
    fun a_create_failure_is_thrown_once_and_recorded() =
        runBlocking<Unit> {
            val broadcaster = RecordingBroadcaster(listOf(leg1))
            broadcaster.createFailure = IllegalStateException("insufficient balance")

            assertFailsWith<IllegalStateException> { broadcaster.createProposedTransactions(proposal, usk) }
            assertEquals(listOf(leg1), broadcaster.createProposedTransactions(proposal, usk))
            assertEquals(listOf("create", "create"), broadcaster.events.toList())
        }

    @Test
    fun the_synchronizer_answers_the_scripted_reads_and_follows_height_changes() =
        runBlocking<Unit> {
            val broadcaster = RecordingBroadcaster(listOf(leg1))
            val fake = ScriptedSynchronizer(broadcaster = broadcaster, fullyScanned = 3_000_000, network = 3_000_005)
            val sync = fake.synchronizer

            assertEquals(BlockHeight.new(3_000_000), sync.fullyScannedHeight.value)
            fake.fullyScannedHeight.value = BlockHeight.new(2_999_990) // a rewind between two reads
            assertEquals(BlockHeight.new(2_999_990), sync.fullyScannedHeight.value)
            assertEquals(BlockHeight.new(3_000_005), sync.networkHeight.value)
            assertTrue(sync.broadcaster === broadcaster)

            val account = AccountUuid.new(ByteArray(ACCOUNT_UUID_BYTES) { 7 })
            fake.utxoAnswer = { _, address -> address == "t-fake-ephemeral" }
            assertTrue(sync.fetchUtxosByAddress(account, "t-fake-ephemeral"))
            assertEquals(listOf("t-fake-ephemeral"), fake.utxoFetches.toList())

            val recipient = TransactionRecipient("tex-fake-recipient", account)
            val id =
                cash.z.ecc.android.sdk.model.TransactionId
                    .new(leg1.txId)
            fake.recipients[id] = listOf(recipient)
            assertEquals(listOf(recipient), sync.getRecipients()[id])
        }

    @Test
    fun the_synchronizer_refuses_an_unscripted_call() {
        val sync = ScriptedSynchronizer().synchronizer
        assertFailsWith<UnsupportedOperationException> { sync.latestBirthdayHeight }
        assertFailsWith<UnsupportedOperationException> { sync.broadcaster }
    }

    @Test
    fun the_chat_copy_clock_ticks_advances_and_can_be_set_back() {
        val files = ChatCopyPhoneFiles()
        val start = files.now
        assertEquals(start + 1, files.tick())
        files.advance(DAY_MS)
        assertEquals(start + 1 + DAY_MS, files.now)
        assertFailsWith<IllegalArgumentException> { files.advance(-1) }
        files.now = start // the phone clock went back
        assertEquals(start + 1, files.tick())
    }

    private companion object {
        const val ACCOUNT_UUID_BYTES = 16
        const val DAY_MS = 24L * 60 * 60 * 1000

        @Suppress("UNCHECKED_CAST")
        inline fun <reified T> unusable(): T =
            if (T::class.java.isInterface) {
                Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, _, _ ->
                    throw UnsupportedOperationException("not read by the fake")
                } as T
            } else {
                val unsafe =
                    Class
                        .forName("sun.misc.Unsafe")
                        .getDeclaredField("theUnsafe")
                        .apply { isAccessible = true }
                        .get(null)
                unsafe.javaClass.getMethod("allocateInstance", Class::class.java).invoke(unsafe, T::class.java) as T
            }
    }
}
