package co.electriccoin.zcash.ui.common.tex

/**
 * F3: one TEX send as the SDK database shows it, and a wallet around it. Leg 1 pays the wallet's own ephemeral
 * transparent address (and its shielded change back to the wallet); leg 2 pays the exchange's TEX address. Both carry
 * [EXPIRY]. Every address is a synthetic placeholder; no test prints one.
 */
internal object TexFixture {
    const val EXPIRY = 1_000L
    const val EPHEMERAL = "t1FixtureEphemeralAddressNotReal0001"
    const val EXCHANGE_TEX = "tex1fixtureexchangeaddressnotreal0001"
    const val CHANGE = "u1fixturechangenotreal0001"
    const val FOUND_ZAT = 150_000L
    const val SHIELD_FEE_ZAT = 10_000L

    val LEG1: String = txid(0x11)
    val LEG2: String = txid(0x22)

    /** The shield the fake "creates": its id and expiry (see FakeTexWallet.shieldCreated). */
    val SHIELD: String = txid(0xA0)
    const val SHIELD_EXPIRY = 1_100L

    fun leg1(mined: Long? = EXPIRY - 10): Any =
        TexApi.facts(
            txId = LEG1,
            mined = mined,
            expiry = EXPIRY,
            outputs =
                listOf(
                    TexApi.output(EPHEMERAL, isOwn = true, kind = "TRANSPARENT"),
                    TexApi.output(CHANGE, isOwn = true, kind = "OTHER"),
                ),
        )

    fun leg2(mined: Long? = null): Any =
        TexApi.facts(
            txId = LEG2,
            mined = mined,
            expiry = EXPIRY,
            outputs = listOf(TexApi.output(EXCHANGE_TEX, isOwn = false, kind = "TEX")),
        )

    /** The shield transaction as the wallet lists it once created (mined or not). */
    fun shieldTx(mined: Long? = null): Any =
        TexApi.facts(txId = SHIELD, isShielding = true, mined = mined, expiry = SHIELD_EXPIRY)

    /** The rawIdHex the database gives leg 1 (TexApi.facts' default: the display id read backwards). */
    val LEG1_RAW: String get() = LEG1.reversedHex()

    /** A wallet whose TEX send is stuck: leg 1 mined, leg 2 expired unmined, scanned tip [tip]. */
    fun stuckWallet(tip: Long = EXPIRY + TexRulesView.CHECK_MARGIN): FakeTexWallet =
        FakeTexWallet().apply {
            this.tip = tip
            transactions = listOf(leg1(), leg2())
        }

    /** A shield that spends only leg 1's ephemeral output ([FOUND_ZAT]) and pays [SHIELD_FEE_ZAT]. */
    fun planSpendingLeg1(
        value: Long = FOUND_ZAT,
        fee: Long = SHIELD_FEE_ZAT,
        rawId: String = LEG1_RAW,
    ): Any = TexApi.shieldPlan(TexApi.shieldShape(listOf(TexApi.shieldInput(rawId, TRANSPARENT_POOL, value)), fee))

    /** A shield that spends another transparent output of the wallet (not the ZEC found). */
    fun planSpendingOtherFunds(): Any =
        TexApi.shieldPlan(
            TexApi.shieldShape(listOf(TexApi.shieldInput(txid(0x77), TRANSPARENT_POOL, 500_000)), SHIELD_FEE_ZAT),
        )
}
