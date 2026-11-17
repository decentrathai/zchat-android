package co.electriccoin.zcash.ui.screen.update

import co.electriccoin.zcash.ui.common.model.NetworkUpgradeTestApi
import co.electriccoin.zcash.ui.common.model.moneyRefusalMessage
import co.electriccoin.zcash.ui.screen.chat.model.ChatSendCopy
import co.electriccoin.zcash.ui.screen.chat.util.toZchatUserMessage
import co.electriccoin.zcash.ui.screen.chat.view.ChatResourceText
import co.electriccoin.zcash.ui.screen.chat.view.SourceScan
import co.electriccoin.zcash.ui.screen.send.model.SendStage
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** ZC-538: one truthful refusal sentence reaches every existing money surface in both shipped languages. */
class UpdateRequiredTextsHaveSpanishAndStayHonestTest {
    @Before
    fun setUp() = NetworkUpgradeTestApi.resetIfPresent()

    @After
    fun tearDown() = NetworkUpgradeTestApi.resetIfPresent()

    @Test
    fun all_five_approved_drafts_exist_in_english_and_spanish_with_matching_placeholders() {
        val en = ChatResourceText.strings("values")
        val es = ChatResourceText.strings("values-es")
        for ((key, pair) in COPY) {
            assertEquals(pair.first, en[key], "missing or changed approved EN: $key")
            assertEquals(pair.second, es[key], "missing or changed approved ES draft: $key")
            val placeholders = Regex("""%\d+\$[sd]""")
            assertEquals(
                placeholders.findAll(pair.first).map { it.value }.toSet(),
                placeholders.findAll(pair.second).map { it.value }.toSet(),
            )
        }
    }

    @Test
    fun the_copy_names_update_safe_balance_nothing_paid_and_only_should_appear() {
        val en = ChatResourceText.strings("values")
        val es = ChatResourceText.strings("values-es")
        val bannerEn = assertNotNull(en["update_required_banner_body"])
        val bannerEs = assertNotNull(es["update_required_banner_body"])
        assertTrue("should appear" in bannerEn && "deberían aparecer" in bannerEs)
        val refusal = assertNotNull(en["update_required_refusal"])
        assertTrue("Nothing was sent or paid" in refusal && "Your balance is safe" in refusal)
        for (key in COPY.keys) {
            for (text in listOf(assertNotNull(en[key]), assertNotNull(es[key]))) {
                val bannedPhrases =
                    listOf(
                        "try again",
                        "retry",
                        "free",
                        "lost",
                        "a few seconds",
                        "inténtalo de nuevo",
                        "reintentar",
                        "gratis",
                        "perdido",
                        "unos segundos",
                    )
                for (banned in bannedPhrases) {
                    assertFalse(text.contains(banned, ignoreCase = true), "$key promises $banned")
                }
            }
        }
    }

    @Test
    fun typed_and_text_refusals_share_the_english_constant_and_spanish_resource_mapping() {
        val error = NetworkUpgradeTestApi.exception()
        assertEquals(NetworkUpgradeTestApi.MESSAGE, error.message)
        assertEquals(NetworkUpgradeTestApi.MESSAGE, moneyRefusalMessage(error))
        assertEquals(NetworkUpgradeTestApi.MESSAGE, RuntimeException("outer", error).toZchatUserMessage("generic retry"))
        assertEquals(NetworkUpgradeTestApi.MESSAGE, NetworkUpgradeTestApi.MESSAGE.toZchatUserMessage("generic retry"))
        val resource = assertNotNull(ChatResourceText.stringId("update_required_refusal"), "refusal resource absent")
        assertEquals(resource, ChatSendCopy.localizedResource(NetworkUpgradeTestApi.MESSAGE))
        assertEquals(NetworkUpgradeTestApi.MESSAGE, ChatResourceText.english(resource))
        val reason =
            assertNotNull(
                SendStage::class.java.declaredFields.firstOrNull { it.name == "REASON_UPDATE_REQUIRED" },
                "SendStage update reason absent",
            )
        reason.isAccessible = true
        assertEquals(reason.get(null), SendStage.reasonFor(error))
    }

    @Test
    fun send_error_and_progress_surfaces_use_update_copy_without_generic_retry_or_stacktrace() {
        val send = SourceScan.stripComments(ChatResourceText.source(SCREEN + "send/view/SendView.kt"))
        assertTrue(
            "REASON_UPDATE_REQUIRED" in send &&
                "R.string.update_required_refusal" in send &&
                "R.string.update_required_title" in send
        )
        val error = SourceScan.stripComments(ChatResourceText.source(SCREEN + "error/ErrorVM.kt"))
        val shielding =
            error.substringAfter("private fun createGeneralShieldingErrorState(").substringBefore("private fun create")
        assertTrue("moneyRefusalMessage(" in shielding, "shielding still exposes a stack trace for a typed refusal")
        val progress =
            SourceScan.stripComments(ChatResourceText.source(SCREEN + "transactionprogress/TransactionProgressVM.kt"))
        val failure = SourceScan.braceBlock(progress, "private fun createFailureTransactionState(")
        assertTrue("update_required_title" in failure && "update_required_refusal" in failure)
        assertTrue("txIds.isEmpty()" in failure, "stored first-attempt failures must retain their existing waiting behavior")
    }

    private companion object {
        const val SCREEN = "ui-lib/src/main/java/co/electriccoin/zcash/ui/screen/"
        val COPY =
            mapOf(
                "update_required_title" to ("Update ZChat" to "Actualiza ZChat"),
                "update_required_banner_body" to (
                    "This version can't send on the upgraded Zcash network. Your balance is safe. " +
                        "Shielded messages sent to you should appear after you update." to
                        "Esta versión no puede enviar en la red de Zcash actualizada. Tu saldo está a salvo. " +
                        "Los mensajes Shielded que te envíen deberían aparecer tras actualizar."
                ),
                "update_required_button" to ("Update" to "Actualizar"),
                "update_required_refusal" to (
                    NetworkUpgradeTestApi.MESSAGE to
                        "No se envió ni se pagó nada: esta versión de ZChat no puede enviar en la red de Zcash actualizada. " +
                        "Actualiza ZChat para enviar. Tu saldo está a salvo."
                ),
                "update_required_up_to_date_body" to (
                    "No newer version of ZChat is available yet, so ZChat can't send on the upgraded Zcash network. " +
                        "If you use a custom server, switch to another one. Your balance is safe." to
                        "Todavía no hay una versión más nueva de ZChat, así que no puede enviar en la red de Zcash actualizada. " +
                        "Si usas un servidor propio, cambia a otro. Tu saldo está a salvo."
                ),
            )
    }
}
