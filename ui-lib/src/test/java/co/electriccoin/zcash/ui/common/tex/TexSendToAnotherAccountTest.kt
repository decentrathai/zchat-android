package co.electriccoin.zcash.ui.common.tex

import cash.z.ecc.android.sdk.Synchronizer
import cash.z.ecc.android.sdk.model.AccountUuid
import cash.z.ecc.android.sdk.model.BlockHeight
import cash.z.ecc.android.sdk.model.TransactionId
import cash.z.ecc.android.sdk.model.TransactionOverview
import cash.z.ecc.android.sdk.model.TransactionRecipient
import cash.z.ecc.android.sdk.model.TransactionState
import cash.z.ecc.android.sdk.model.Zatoshi
import cash.z.ecc.android.sdk.model.Zip318Kind
import cash.z.ecc.android.sdk.type.AddressType
import co.electriccoin.zcash.ui.common.datasource.AccountDataSource
import co.electriccoin.zcash.ui.common.datasource.ProposalDataSource
import co.electriccoin.zcash.ui.common.datasource.ZashiSpendingKeyDataSource
import co.electriccoin.zcash.ui.common.model.SynchronizerError
import co.electriccoin.zcash.ui.common.model.ZashiAccount
import co.electriccoin.zcash.ui.common.provider.PersistableWalletProvider
import co.electriccoin.zcash.ui.common.provider.SynchronizerProvider
import co.electriccoin.zcash.ui.common.usecase.syntheticZashiAccount
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Test
import java.lang.reflect.Proxy
import java.util.Collections
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * T3b-3 (fix-all 2.13.0, train T3b; train T3 phone finding 1): the TEX checker must see a TEX send whose recipient is
 * an address of ANOTHER account of the same wallet (on the test phones, the other phone's imported viewing key; for a
 * user, any second account), and an address of the ZCHAT account itself.
 *
 * SDK 3.0.0 names, for every output, the account of the wallet it pays (`TransactionRecipient.accountUuid`), whichever
 * account that is. At fabed1157 [SdkTexWallet] reads "pays this wallet" as "names any account" (`accountUuid != null`),
 * and findTexSends needs leg 2's TEX output not to pay this wallet. So a TEX to another account (or to the ZCHAT
 * account) has no leg 2: no pair, not even "unmatched". Nothing is checked, looked up or shielded, the ZEC of a stuck
 * send stays at the one-time address with nothing said, and the leg-2 transaction detail offers "Send again" (the
 * checker read it and found no TEX send), so the exchange can be paid twice. On the Seeker 20,000 zat sit there.
 *
 * Every case goes through the real [SdkTexWallet] over a scripted SDK (its accounts, transactions, recipients and
 * address kinds); every id and address is synthetic.
 */
class TexSendToAnotherAccountTest {
    @Test
    fun a_tex_send_to_an_address_of_another_account_of_the_wallet_is_one_send() {
        val sdk = ScriptedTexSdk(leg2Recipient = TransactionRecipient(EXCHANGE_TEX, OTHER_ACCOUNT))

        val pairs = runBlocking { pairTexSends(sdk.wallet().transactions()) }

        assertEquals(1, pairs.size, "the TEX send to another account of this wallet is not found")
        assertEquals(sdk.leg1Id, pairs.single().leg1.txId)
        assertEquals(sdk.leg2Id, pairs.single().leg2.txId)
        assertEquals(EPHEMERAL, pairs.single().ephemeralAddress)
    }

    @Test
    fun the_stuck_zec_of_such_a_send_is_looked_up_for_the_zchat_account_and_send_again_is_withheld() {
        val sdk = ScriptedTexSdk(leg2Recipient = TransactionRecipient(EXCHANGE_TEX, OTHER_ACCOUNT))
        val tor = ScriptedTor()
        var idle = true
        val monitor =
            TexSendMonitor(
                wallet = sdk.wallet(),
                tor = tor,
                idle = { idle },
                store = InMemoryTexRecoveryStore(),
                notice = { },
                clock = { 0L },
            )

        // Pass 1: leg 1 mined, leg 2 expired unmined, Tor off, nothing else in progress: Tor is turned on by itself.
        val first = runBlocking { monitor.pass() }
        assertEquals(TexState.NEEDS_TOR, first.forTransaction(sdk.leg2Id)?.state, "no status for the stuck send: $first")
        assertEquals(1, tor.turnOns, "Tor was not turned on for the stuck send")

        // Pass 2: Tor on, a call in progress: the ephemeral address is looked up for the ZCHAT account; the shield waits.
        idle = false
        val second = runBlocking { monitor.pass() }
        assertEquals(TexState.RETURNED, second.forTransaction(sdk.leg2Id)?.state)
        assertEquals(listOf(sdk.zchatAccount to EPHEMERAL), sdk.lookups, "the ephemeral address was not looked up")

        val check = monitor.check.value
        assertFalse(texSendAgainAllowed(sdk.leg2Id, check), "\"Send again\" is offered for the stuck leg 2")
        assertFalse(texSendAgainAllowed(sdk.leg1Id, check), "\"Send again\" is offered for leg 1 of the stuck send")
    }

    @Test
    fun a_leg_two_listed_as_a_plain_transparent_address_of_another_account_is_still_paired() {
        val sdk = ScriptedTexSdk(leg2Recipient = TransactionRecipient(OTHER_ACCOUNT_T_ADDRESS, OTHER_ACCOUNT))

        val pairs = runBlocking { pairTexSends(sdk.wallet().transactions()) }

        assertEquals(1, pairs.size, "leg 2 listed as another account's transparent address is not paired")
        assertEquals(sdk.leg2Id, pairs.single().leg2.txId)
    }

    @Test
    fun a_tex_send_to_an_address_of_the_zchat_account_itself_is_one_send() {
        val sdk = ScriptedTexSdk(leg2Recipient = null)
        sdk.leg2Recipient = TransactionRecipient(EXCHANGE_TEX, sdk.zchatAccount)

        val pairs = runBlocking { pairTexSends(sdk.wallet().transactions()) }

        assertEquals(1, pairs.size, "a TEX send to the ZCHAT account's own address is not found")
        assertEquals(sdk.leg2Id, pairs.single().leg2.txId)
    }

    @Test
    fun control_a_tex_send_to_an_address_outside_the_wallet_is_one_send_as_before() {
        val sdk = ScriptedTexSdk(leg2Recipient = TransactionRecipient(EXCHANGE_TEX, null))

        val pairs = runBlocking { pairTexSends(sdk.wallet().transactions()) }

        assertEquals(1, pairs.size)
        assertEquals(EPHEMERAL, pairs.single().ephemeralAddress)
    }

    @Test
    fun control_a_plain_payment_to_another_account_of_the_wallet_is_no_tex_send() {
        val sdk = ScriptedTexSdk(leg2Recipient = TransactionRecipient(OTHER_ACCOUNT_U_ADDRESS, OTHER_ACCOUNT))
        sdk.withLeg1 = false

        val sends = runBlocking { findTexSends(sdk.wallet().transactions()) }

        assertTrue(sends.pairs.isEmpty(), "a plain payment was taken for a TEX send")
        assertTrue(sends.unmatched.isEmpty(), "a plain payment was taken for a leg of a TEX send")
    }

    @Test
    fun the_sdk_names_one_account_by_equal_uuids_read_apart() {
        // SdkTexWallet compares the account an output pays with the ZCHAT account's uuid; the two are read from the
        // database apart, so "own" rests on AccountUuid being equal by its bytes, not by object.
        val bytes = ByteArray(16) { (0x30 + it).toByte() }

        assertEquals(AccountUuid.new(bytes), AccountUuid.new(bytes.copyOf()))
        assertFalse(AccountUuid.new(bytes) == OTHER_ACCOUNT)
    }

    /**
     * The SDK as [SdkTexWallet] reads it: the ZCHAT account ([zchatAccount]) sent leg 1 (mined, paying its own
     * [EPHEMERAL] address and shielded change) and leg 2 (unmined, expired at [EXPIRY], paying [leg2Recipient]); the
     * scanned tip is [TIP]. Every lookup is recorded in [lookups] and answers "found".
     */
    private class ScriptedTexSdk(
        var leg2Recipient: TransactionRecipient?,
    ) {
        val zchatAccount: AccountUuid
        private val account: ZashiAccount = runBlocking { syntheticZashiAccount() }
        var withLeg1 = true
        val lookups: MutableList<Pair<AccountUuid, String>> = Collections.synchronizedList(mutableListOf())

        private val leg1Tx = TransactionId.new(ByteArray(TXID_BYTES) { (0x11 + it).toByte() })
        private val leg2Tx = TransactionId.new(ByteArray(TXID_BYTES) { (0x22 + it).toByte() })
        val leg1Id: String = leg1Tx.txIdString()
        val leg2Id: String = leg2Tx.txIdString()

        init {
            zchatAccount = account.sdkAccount.accountUuid
            check(zchatAccount != OTHER_ACCOUNT) { "fixture: the two accounts must differ" }
        }

        fun wallet(): SdkTexWallet =
            SdkTexWallet(
                synchronizerProvider = provider(),
                accountDataSource =
                    port<AccountDataSource> { name, _ ->
                        if (name == "getZashiAccount") account else unexpected("AccountDataSource.$name")
                    },
                proposalDataSource = port<ProposalDataSource> { name, _ -> unexpected("ProposalDataSource.$name") },
                spendingKeyDataSource = port<ZashiSpendingKeyDataSource> { name, _ -> unexpected("spending key: $name") },
                persistableWalletProvider = port<PersistableWalletProvider> { name, _ -> unexpected("wallet: $name") },
            )

        private fun overviews(): List<TransactionOverview> =
            listOfNotNull(
                if (withLeg1) overview(leg1Tx, mined = EXPIRY - 10) else null,
                overview(leg2Tx, mined = null),
            )

        private fun recipients(): Map<TransactionId, List<TransactionRecipient>> =
            buildMap {
                if (withLeg1) {
                    put(
                        leg1Tx,
                        listOf(TransactionRecipient(EPHEMERAL, zchatAccount), TransactionRecipient(CHANGE, zchatAccount)),
                    )
                }
                put(leg2Tx, listOfNotNull(leg2Recipient))
            }

        private val synchronizer: Synchronizer =
            port { name, args ->
                when (name) {
                    "getFullyScannedHeight" -> MutableStateFlow(BlockHeight.new(TIP))
                    "getInitializationError" -> null
                    "getTransactions" -> {
                        check(args[0] == zchatAccount) { "the checker read another account's transactions" }
                        flowOf(overviews())
                    }
                    "getRecipients" ->
                        if (args.size == 1) recipients() else unexpected("Synchronizer.getRecipients(overview)")
                    "validateAddress" ->
                        when (args[0] as String) {
                            EXCHANGE_TEX -> AddressType.Tex
                            EPHEMERAL, OTHER_ACCOUNT_T_ADDRESS -> AddressType.Transparent
                            else -> AddressType.Unified
                        }
                    "fetchUtxosByAddress" -> {
                        lookups += (args[0] as AccountUuid) to (args[1] as String)
                        true
                    }
                    else -> unexpected("Synchronizer.$name")
                }
            }

        private fun provider(): SynchronizerProvider =
            object : SynchronizerProvider {
                override val error: StateFlow<SynchronizerError?> = MutableStateFlow(null)
                override val synchronizer: StateFlow<Synchronizer?> = MutableStateFlow(this@ScriptedTexSdk.synchronizer)

                override suspend fun getSynchronizer(): Synchronizer = this@ScriptedTexSdk.synchronizer

                override fun resetSynchronizer() = Unit
            }
    }

    private class ScriptedTor : TexTor {
        var on = false
        var turnOns = 0

        override suspend fun isOn(): Boolean = on

        override suspend fun isFailing(): Boolean = false

        override suspend fun isExplicitlyOff(): Boolean = false

        override suspend fun turnOn() {
            turnOns++
            on = true
        }
    }

    private companion object {
        const val EXPIRY = 1_000L
        const val TIP = EXPIRY + 2
        const val TXID_BYTES = 32
        const val EPHEMERAL = "t1FixtureEphemeralAddressNotReal0001"
        const val EXCHANGE_TEX = "tex1fixtureexchangeaddressnotreal0001"
        const val CHANGE = "u1fixturechangenotreal0001"
        const val OTHER_ACCOUNT_T_ADDRESS = "t1FixtureOtherAccountAddressNotReal1"
        const val OTHER_ACCOUNT_U_ADDRESS = "u1fixtureotheraccountnotreal0001"
        val OTHER_ACCOUNT: AccountUuid = AccountUuid.new(ByteArray(16) { 0x5A })

        fun overview(
            id: TransactionId,
            mined: Long?,
        ) = TransactionOverview(
            id,
            mined?.let { BlockHeight.new(it) },
            BlockHeight.new(EXPIRY),
            null,
            null,
            true,
            Zatoshi(30_000),
            Zatoshi(30_000),
            Zatoshi(0),
            Zatoshi(10_000),
            false,
            0,
            1,
            0,
            null,
            if (mined != null) TransactionState.Confirmed else TransactionState.Expired,
            false,
            1,
            Zatoshi(0),
            true,
            Zip318Kind.NOT_CLASSIFIED,
        )

        fun unexpected(what: String): Nothing = throw UnsupportedOperationException("unexpected call: $what")

        inline fun <reified T> port(crossinline answer: (String, Array<out Any?>) -> Any?): T =
            Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { proxy, method, args ->
                when (method.name) {
                    "toString" -> "Scripted${T::class.java.simpleName}"
                    "hashCode" -> System.identityHashCode(proxy)
                    "equals" -> proxy === args!![0]
                    else -> answer(method.name, args.orEmpty())
                }
            } as T
    }
}
