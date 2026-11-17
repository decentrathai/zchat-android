package co.electriccoin.zcash.ui.screen.chat.viewmodel

import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * R2-1 (a), plan step p2-6ccb00a01f88: the chat custom-amount box shows the raw text the user typed
 * and pays [ZchatComposeVM.parseCustomAmountZatoshi] of it. It shares ZashiNumberTextFieldParser with
 * the Send screen, so "0.5" on a German phone used to pay 5 ZEC and "0,00001" on a US phone 1 ZEC.
 */
class CustomAmountMarkMisreadTest {
    @Test
    fun `0_5 with a dot on a German phone pays half a ZEC, not 5`() =
        assertEquals(50_000_000L, ZchatComposeVM.parseCustomAmountZatoshi("0.5", Locale.GERMANY))

    @Test
    fun `0_00001 with a dot on a German phone pays 1000 zatoshi, not 1 ZEC`() =
        assertEquals(1_000L, ZchatComposeVM.parseCustomAmountZatoshi("0.00001", Locale.GERMANY))

    @Test
    fun `0,5 with a comma on a US phone pays half a ZEC, not 5`() =
        assertEquals(50_000_000L, ZchatComposeVM.parseCustomAmountZatoshi("0,5", Locale.US))

    @Test
    fun `0,00001 with a comma on a US phone pays 1000 zatoshi, not 1 ZEC`() =
        assertEquals(1_000L, ZchatComposeVM.parseCustomAmountZatoshi("0,00001", Locale.US))

    @Test
    fun `a US thousands group still pays 1234 ZEC`() =
        assertEquals(123_400_000_000L, ZchatComposeVM.parseCustomAmountZatoshi("1,234", Locale.US))
}
